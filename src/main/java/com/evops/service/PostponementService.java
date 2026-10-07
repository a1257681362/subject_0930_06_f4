package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.evops.dto.PostponeRequest;
import com.evops.entity.NotificationReceipt;
import com.evops.entity.Postponement;
import com.evops.entity.WorkShift;
import com.evops.enums.ShiftStatus;
import com.evops.mapper.PostponementMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 延期处理。延期是人工显式动作，系统绝不因窗口收窄静默改期。
 *
 * 原计划保留：每次延期写一条 t_postponement，其 originalFrom/originalTo 始终等于
 * 班次生成时的 plannedFrom/plannedTo（永不修改）；只更新班次的 currentStartAt/currentFinishAt。
 * 延期同时按通道发送通知并留送达回执，回执按通道幂等、重试不重复通知。
 */
@Service
public class PostponementService extends ServiceImpl<PostponementMapper, Postponement> {

    private final HighWorkScheduleService scheduleService;
    private final ShiftWindowGuard windowGuard;
    private final WorkerService workerService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public PostponementService(HighWorkScheduleService scheduleService,
                               ShiftWindowGuard windowGuard,
                               WorkerService workerService,
                               NotificationService notificationService,
                               AuditService auditService) {
        this.scheduleService = scheduleService;
        this.windowGuard = windowGuard;
        this.workerService = workerService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    @Transactional
    public Postponement postpone(Long shiftId, PostponeRequest req) {
        WorkShift shift = scheduleService.mustGet(shiftId);
        if (shift.getStatus() == ShiftStatus.STARTED
                || shift.getStatus() == ShiftStatus.COMPLETED
                || shift.getStatus() == ShiftStatus.CANCELLED) {
            throw new IllegalArgumentException("班次状态不允许延期: " + shift.getStatus());
        }
        LocalDateTime newFrom = req.getNewFrom();
        LocalDateTime newTo = newFrom.plusMinutes(shift.getDurationMinutes());
        if (!newFrom.isAfter(shift.getCurrentStartAt())) {
            throw new IllegalArgumentException("延期后的开始时间必须晚于当前计划开始时间 "
                    + shift.getCurrentStartAt());
        }

        // 延期后的作业区间必须落在最新三类气象窗口交集内
        List<ShiftWindowGuard.Interval> feasible =
                scheduleService.feasibleIntervals(shift.getTurbineId(), shift.getSite());
        if (!windowGuard.coversFully(feasible, newFrom, newTo)) {
            throw new IllegalArgumentException("延期目标时段 [" + newFrom + ", " + newTo
                    + ") 未被风速/降雨/雷电安全窗口完整覆盖，不能延期到该时段");
        }

        // 过期资质人员不能被排入：逐人复核新开始时刻资质
        List<Long> crewIds = parseWorkerIds(shift.getWorkerIds());
        List<String> expired = new ArrayList<>();
        for (Long wid : crewIds) {
            if (!workerService.isQualifiedAt(wid, shift.getRequiredQualType(),
                    shift.getRequiredQualLevel(), newFrom)) {
                expired.add(String.valueOf(wid));
            }
        }
        if (!expired.isEmpty()) {
            throw new IllegalArgumentException("以下人员资质在延期开始时刻 " + newFrom
                    + " 已过期或等级不足，不能排入：" + String.join(",", expired));
        }

        int seq = (int) count(new QueryWrapper<Postponement>().eq("shift_id", shiftId)) + 1;
        Postponement p = new Postponement();
        p.setShiftId(shiftId);
        p.setSeq(seq);
        // 原计划始终引用班次生成时的计划，绝不随多次延期漂移
        p.setOriginalFrom(shift.getPlannedFrom());
        p.setOriginalTo(shift.getPlannedTo());
        p.setNewFrom(newFrom);
        p.setNewTo(newTo);
        p.setReason(req.getReason());
        p.setOperator(req.getOperator());
        p.setPostponedAt(LocalDateTime.now());
        save(p);

        // 只更新当前执行时间；planned_* 保持不变，原计划保留
        shift.setCurrentStartAt(newFrom);
        shift.setCurrentFinishAt(newTo);
        scheduleService.updateById(shift);

        List<NotificationReceipt> receipts =
                notificationService.notifyPostponement(shift, p, req.getChannels());

        auditService.record("WORK_SHIFT", shiftId, "SHIFT_POSTPONE",
                "{\"seq\":" + seq + ",\"originalFrom\":\"" + p.getOriginalFrom()
                        + "\",\"newFrom\":\"" + newFrom + "\",\"receipts\":" + receipts.size() + "}",
                req.getOperator());
        return p;
    }

    public List<Postponement> listByShift(Long shiftId) {
        return list(new QueryWrapper<Postponement>()
                .eq("shift_id", shiftId).orderByAsc("seq"));
    }

    private List<Long> parseWorkerIds(String workerIds) {
        List<Long> ids = new ArrayList<>();
        if (workerIds == null || workerIds.isEmpty()) {
            return ids;
        }
        for (String s : Arrays.asList(workerIds.split(","))) {
            if (!s.trim().isEmpty()) {
                ids.add(Long.valueOf(s.trim()));
            }
        }
        return ids;
    }
}
