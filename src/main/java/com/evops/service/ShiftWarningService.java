package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.evops.entity.ShiftWarning;
import com.evops.entity.WorkShift;
import com.evops.enums.ShiftStatus;
import com.evops.enums.WarningType;
import com.evops.mapper.ShiftWarningMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 窗口收窄预警。气象数据补传/补录/重试后，重新计算三类窗口交集：
 *  - 交集仍覆盖当前作业区间但较已记录可行区间收窄：NARROWED；
 *  - 交集不再完整覆盖当前作业区间：UNCOVERED（须人工延期，系统不静默改期）。
 * 预警按“班次 + 类型 + 收窄后区间”幂等：重复扫描/重试只产生一条预警。
 */
@Service
public class ShiftWarningService extends ServiceImpl<ShiftWarningMapper, ShiftWarning> {

    private final HighWorkScheduleService scheduleService;
    private final ShiftWindowGuard windowGuard;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public ShiftWarningService(HighWorkScheduleService scheduleService,
                               ShiftWindowGuard windowGuard,
                               NotificationService notificationService,
                               AuditService auditService) {
        this.scheduleService = scheduleService;
        this.windowGuard = windowGuard;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    /** 扫描全部未开工班次，返回本次新产生的预警数 */
    @Transactional
    public int scanAll() {
        List<WorkShift> shifts = scheduleService.list(new QueryWrapper<WorkShift>()
                .in("status", ShiftStatus.CANDIDATE, ShiftStatus.CONFIRMED));
        int count = 0;
        for (WorkShift shift : shifts) {
            count += scanOne(shift.getId()).size();
        }
        return count;
    }

    /** 扫描单个班次，返回新预警列表（幂等，已预警过的收窄状态不重复） */
    @Transactional
    public List<ShiftWarning> scanOne(Long shiftId) {
        WorkShift shift = scheduleService.mustGet(shiftId);
        List<ShiftWarning> created = new ArrayList<>();
        if (shift.getStatus() != ShiftStatus.CANDIDATE && shift.getStatus() != ShiftStatus.CONFIRMED) {
            return created;
        }

        List<ShiftWindowGuard.Interval> feasible =
                scheduleService.feasibleIntervals(shift.getTurbineId(), shift.getSite());
        LocalDateTime start = shift.getCurrentStartAt();
        LocalDateTime finish = shift.getCurrentFinishAt();

        boolean fullyCovered = windowGuard.coversFully(feasible, start, finish);
        ShiftWindowGuard.Interval covering = windowGuard.intervalCovering(feasible, start);

        if (!fullyCovered) {
            // 不再完整覆盖：高危预警，须人工决定延期；系统不改期
            String key = shift.getId() + "|UNCOVERED|" + start + "|" + finish;
            ShiftWarning w = raiseIfAbsent(shift, WarningType.UNCOVERED, key,
                    "气象窗口收窄，当前作业区间 [" + start + ", " + finish
                            + ") 已不能被风速/降雨/雷电安全窗口完整覆盖，需人工确认延期（系统不会自动改期）",
                    shift.getFeasibleFrom(), shift.getFeasibleTo(),
                    covering != null ? covering.getFrom() : null,
                    covering != null ? covering.getTo() : null);
            if (w != null) {
                created.add(w);
            }
            return created;
        }

        // 仍覆盖但交集收窄：起点后移或终点前移
        if (covering != null
                && (covering.getFrom().isAfter(shift.getFeasibleFrom())
                || covering.getTo().isBefore(shift.getFeasibleTo()))) {
            String key = shift.getId() + "|NARROWED|" + covering.getFrom() + "|" + covering.getTo();
            String msg = "气象窗口收窄：可行区间由 [" + shift.getFeasibleFrom() + ", "
                    + shift.getFeasibleTo() + ") 收窄为 [" + covering.getFrom() + ", "
                    + covering.getTo() + ")，当前计划 [" + start + ", " + finish
                    + ") 暂仍在安全窗口内";
            ShiftWarning w = raiseIfAbsent(shift, WarningType.NARROWED, key, msg,
                    shift.getFeasibleFrom(), shift.getFeasibleTo(),
                    covering.getFrom(), covering.getTo());
            if (w != null) {
                created.add(w);
                // 以最新收窄区间为基准，后续继续收窄可再次预警（区间不同则 key 不同）
                shift.setFeasibleFrom(covering.getFrom());
                shift.setFeasibleTo(covering.getTo());
                scheduleService.updateById(shift);
            }
        }
        return created;
    }

    private ShiftWarning raiseIfAbsent(WorkShift shift, WarningType type, String key, String message,
                                       LocalDateTime oldFrom, LocalDateTime oldTo,
                                       LocalDateTime newFrom, LocalDateTime newTo) {
        Long dup = count(new QueryWrapper<ShiftWarning>().eq("warning_key", key));
        if (dup > 0) {
            return null;
        }
        ShiftWarning warning = new ShiftWarning();
        warning.setShiftId(shift.getId());
        warning.setWarningType(type);
        warning.setMessage(message);
        warning.setOldFrom(oldFrom);
        warning.setOldTo(oldTo);
        warning.setNewFrom(newFrom);
        warning.setNewTo(newTo);
        warning.setWarningKey(key);
        save(warning);

        notificationService.notifyWarning(shift, NotificationService.DEFAULT_CHANNEL, message);
        auditService.record("WORK_SHIFT", shift.getId(),
                type == WarningType.UNCOVERED ? "SHIFT_WEATHER_UNCOVERED" : "SHIFT_WEATHER_NARROWED",
                "{\"warningKey\":\"" + key + "\"}", null);
        return warning;
    }

    public List<ShiftWarning> listByShift(Long shiftId) {
        return list(new QueryWrapper<ShiftWarning>()
                .eq("shift_id", shiftId).orderByAsc("id"));
    }
}
