package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.evops.common.ShiftPlanException;
import com.evops.dto.ShiftGenerateRequest;
import com.evops.dto.WeatherConfirmRequest;
import com.evops.entity.Equipment;
import com.evops.entity.Turbine;
import com.evops.entity.WeatherWindow;
import com.evops.entity.WorkShift;
import com.evops.entity.Worker;
import com.evops.enums.ConfirmStatus;
import com.evops.enums.ShiftStatus;
import com.evops.enums.WeatherType;
import com.evops.mapper.WorkShiftMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 高空作业排班核心。
 *
 * 候选班次生成：读取风速/降雨/雷电三类窗口（同类取并集、三类取交集，左闭右开），
 * 在交集中按维修时长寻找最早可行起点；结合人员资质（作业开始时刻有效，过期者排除）、
 * 装备可用性（维修中/窗口不覆盖排除）生成一条候选班次。
 *
 * 同一排产请求（幂等键 resultKey）在补传/补录/重试下只产生一条可用结果；
 * 已存在则直接返回，不重复生成。
 */
@Service
public class HighWorkScheduleService extends ServiceImpl<WorkShiftMapper, WorkShift> {

    private final TurbineService turbineService;
    private final WeatherWindowService weatherWindowService;
    private final WorkerService workerService;
    private final EquipmentService equipmentService;
    private final ShiftWindowGuard windowGuard;
    private final AuditService auditService;

    public HighWorkScheduleService(TurbineService turbineService,
                                   WeatherWindowService weatherWindowService,
                                   WorkerService workerService,
                                   EquipmentService equipmentService,
                                   ShiftWindowGuard windowGuard,
                                   AuditService auditService) {
        this.turbineService = turbineService;
        this.weatherWindowService = weatherWindowService;
        this.workerService = workerService;
        this.equipmentService = equipmentService;
        this.windowGuard = windowGuard;
        this.auditService = auditService;
    }

    /** 生成候选班次（幂等：同一排产意图只落一条可用结果） */
    @Transactional
    public WorkShift generate(ShiftGenerateRequest req) {
        String resultKey = buildResultKey(req);
        WorkShift existing = getOne(new QueryWrapper<WorkShift>()
                .eq("result_key", resultKey)
                .ne("status", ShiftStatus.CANCELLED)
                .last("limit 1"));
        if (existing != null) {
            // 补传/补录/重试：同一排产意图只形成一条可用结果
            return existing;
        }

        Turbine turbine = turbineService.mustGet(req.getTurbineId());
        LocalDateTime notBefore = req.getNotBefore() != null ? req.getNotBefore() : LocalDateTime.now();

        // 1) 三类气象窗口：同类并集、三类交集
        List<ShiftWindowGuard.Interval> feasible = feasibleIntervals(turbine.getId(), turbine.getSite());

        List<String> reasons = new ArrayList<>();
        for (WeatherType type : WeatherType.values()) {
            if (weatherWindowService.listForTurbine(turbine.getId(), turbine.getSite(), type).isEmpty()) {
                reasons.add("缺少" + typeName(type) + "安全窗口，无法判定可作业时段");
            }
        }

        // 2) 按维修时长找最早起点
        LocalDateTime start = windowGuard.earliestStart(feasible, notBefore, req.getDurationMinutes());
        LocalDateTime finish = start != null ? start.plusMinutes(req.getDurationMinutes()) : null;
        if (start == null) {
            long maxMinutes = feasible.stream().mapToLong(ShiftWindowGuard.Interval::lengthMinutes).max().orElse(0);
            reasons.add("三类气象窗口交集中，不存在能容纳 " + req.getDurationMinutes()
                    + " 分钟维修作业的连续时段（最长连续可行区间 " + maxMinutes + " 分钟）");
        }

        // 3) 装备可用性
        Equipment equipment = null;
        if (start != null && StringUtils.hasText(req.getEquipmentType())) {
            List<Equipment> available = equipmentService.findAvailable(
                    turbine.getSite(), req.getEquipmentType(), start, finish);
            if (available.isEmpty()) {
                reasons.add("无在 [" + start + ", " + finish + ") 可用的「"
                        + req.getEquipmentType() + "」装备（可能维修中或可用窗口不覆盖）");
            } else {
                equipment = available.get(0);
            }
        }

        // 4) 人员资质：作业开始时刻必须有效，过期资质人员排除
        List<Worker> crew = new ArrayList<>();
        if (start != null) {
            crew = workerService.findQualifiedWorkers(turbine.getSite(),
                    req.getRequiredQualType(), req.getRequiredQualLevel(), start, null);
            if (crew.size() < req.getCrewSize()) {
                reasons.add("持有效「" + req.getRequiredQualType() + "/" + req.getRequiredQualLevel()
                        + "」资质且在作业开始时刻 " + start + " 未过期的人员不足：需要 "
                        + req.getCrewSize() + " 人，仅 " + crew.size() + " 人");
            }
        }

        if (!reasons.isEmpty()) {
            throw new ShiftPlanException(reasons);
        }

        List<Worker> selected = crew.subList(0, req.getCrewSize());
        ShiftWindowGuard.Interval covering = windowGuard.intervalCovering(feasible, start);

        WorkShift shift = new WorkShift();
        shift.setShiftNo("WS" + System.currentTimeMillis());
        shift.setSite(turbine.getSite());
        shift.setTurbineId(turbine.getId());
        shift.setWorkContent(req.getWorkContent());
        shift.setRequiredQualType(req.getRequiredQualType());
        shift.setRequiredQualLevel(req.getRequiredQualLevel());
        shift.setCrewSize(req.getCrewSize());
        shift.setDurationMinutes(req.getDurationMinutes());
        shift.setEquipmentId(equipment != null ? equipment.getId() : null);
        shift.setWorkerIds(selected.stream().map(w -> String.valueOf(w.getId()))
                .collect(Collectors.joining(",")));
        shift.setFeasibleFrom(covering.getFrom());
        shift.setFeasibleTo(covering.getTo());
        shift.setPlannedFrom(start);
        shift.setPlannedTo(finish);
        shift.setCurrentStartAt(start);
        shift.setCurrentFinishAt(finish);
        shift.setStatus(ShiftStatus.CANDIDATE);
        shift.setConfirmStatus(ConfirmStatus.NONE);
        shift.setResultKey(resultKey);
        save(shift);

        auditService.record("WORK_SHIFT", shift.getId(), "SHIFT_GENERATE",
                "{\"plannedFrom\":\"" + start + "\",\"durationMinutes\":" + req.getDurationMinutes()
                        + ",\"crewSize\":" + req.getCrewSize()
                        + (equipment != null ? ",\"equipmentId\":" + equipment.getId() : "")
                        + ",\"workerIds\":\"" + shift.getWorkerIds() + "\"}", null);
        return shift;
    }

    /**
     * 作业开始前二次确认天气：
     * 重新读取三类窗口，当前计划作业区间必须被完整覆盖；现场实测风速不得超过覆盖窗口安全上限；
     * fieldSafe=false 直接不通过。不通过则禁止开工（不自动改期）。
     */
    @Transactional
    public WorkShift confirmWeather(Long id, WeatherConfirmRequest req) {
        WorkShift shift = mustGet(id);
        if (shift.getStatus() != ShiftStatus.CANDIDATE && shift.getStatus() != ShiftStatus.CONFIRMED) {
            throw new IllegalArgumentException("班次状态不允许天气确认: " + shift.getStatus());
        }

        List<String> failures = new ArrayList<>();
        Turbine turbine = turbineService.mustGet(shift.getTurbineId());
        List<ShiftWindowGuard.Interval> feasible = feasibleIntervals(shift.getTurbineId(), shift.getSite());

        LocalDateTime start = shift.getCurrentStartAt();
        LocalDateTime finish = shift.getCurrentFinishAt();
        if (!windowGuard.coversFully(feasible, start, finish)) {
            failures.add("最新风速/降雨/雷电窗口交集不能完整覆盖作业区间 ["
                    + start + ", " + finish + ")");
        }
        if (Boolean.FALSE.equals(req.getFieldSafe())) {
            failures.add("现场观测结论为不可作业（fieldSafe=false）");
        }
        if (req.getObservedWindSpeed() != null) {
            WeatherWindow wind = weatherWindowService.windWindowCovering(
                    shift.getTurbineId(), shift.getSite(), start);
            if (wind == null) {
                failures.add("作业开始时刻无覆盖的风速安全窗口");
            } else if (wind.getSafeMaxValue() != null
                    && req.getObservedWindSpeed() > wind.getSafeMaxValue()) {
                failures.add("实测风速 " + req.getObservedWindSpeed() + " m/s 超过安全上限 "
                        + wind.getSafeMaxValue() + " m/s");
            }
        }

        if (!failures.isEmpty()) {
            shift.setConfirmStatus(ConfirmStatus.FAILED);
            shift.setWeatherSnapshotJson(buildSnapshot(shift, req, false, failures));
            updateById(shift);
            auditService.record("WORK_SHIFT", shift.getId(), "WEATHER_CONFIRM_FAIL",
                    "{\"reasons\":\"" + String.join("；", failures) + "\"}", req.getObserver());
            throw new IllegalArgumentException("开工前天气二次确认未通过，禁止开工："
                    + String.join("；", failures));
        }

        shift.setConfirmStatus(ConfirmStatus.PASSED);
        shift.setStatus(ShiftStatus.CONFIRMED);
        shift.setConfirmedAt(LocalDateTime.now());
        shift.setWeatherSnapshotJson(buildSnapshot(shift, req, true, failures));
        updateById(shift);
        auditService.record("WORK_SHIFT", shift.getId(), "WEATHER_CONFIRM_PASS",
                "{\"observer\":\"" + req.getObserver() + "\"}", req.getObserver());
        return shift;
    }

    /** 开工：必须已通过开工前天气二次确认 */
    @Transactional
    public WorkShift start(Long id) {
        WorkShift shift = mustGet(id);
        if (shift.getStatus() != ShiftStatus.CONFIRMED
                || shift.getConfirmStatus() != ConfirmStatus.PASSED) {
            throw new IllegalArgumentException("未通过开工前天气二次确认，不允许开工");
        }
        shift.setStatus(ShiftStatus.STARTED);
        shift.setStartedAt(LocalDateTime.now());
        updateById(shift);
        auditService.record("WORK_SHIFT", shift.getId(), "SHIFT_START", null, null);
        return shift;
    }

    @Transactional
    public WorkShift complete(Long id) {
        WorkShift shift = mustGet(id);
        if (shift.getStatus() != ShiftStatus.STARTED) {
            throw new IllegalArgumentException("班次状态不允许完成: " + shift.getStatus());
        }
        shift.setStatus(ShiftStatus.COMPLETED);
        shift.setCompletedAt(LocalDateTime.now());
        updateById(shift);
        auditService.record("WORK_SHIFT", shift.getId(), "SHIFT_COMPLETE", null, null);
        return shift;
    }

    @Transactional
    public WorkShift cancel(Long id) {
        WorkShift shift = mustGet(id);
        if (shift.getStatus() == ShiftStatus.STARTED || shift.getStatus() == ShiftStatus.COMPLETED) {
            throw new IllegalArgumentException("已开工/已完成班次不能取消");
        }
        shift.setStatus(ShiftStatus.CANCELLED);
        updateById(shift);
        auditService.record("WORK_SHIFT", shift.getId(), "SHIFT_CANCEL", null, null);
        return shift;
    }

    /**
     * 某风机当前三类气象窗口的可行交集：每类窗口先并集（多来源互补），三类再交集。
     */
    public List<ShiftWindowGuard.Interval> feasibleIntervals(Long turbineId, String site) {
        List<ShiftWindowGuard.Interval> result = null;
        for (WeatherType type : WeatherType.values()) {
            List<ShiftWindowGuard.Interval> unioned = windowGuard.union(
                    weatherWindowService.intervalsOfType(turbineId, site, type));
            if (result == null) {
                result = unioned;
            } else {
                result = windowGuard.intersect(result, unioned);
            }
        }
        return result == null ? new ArrayList<>() : result;
    }

    public WorkShift mustGet(Long id) {
        WorkShift shift = getById(id);
        if (shift == null) {
            throw new IllegalArgumentException("高空作业班次不存在: " + id);
        }
        return shift;
    }

    /** 幂等键：同一排产意图（风机+工作内容+资质+人数+时长+装备类型+不早于时刻）重复提交只落一条 */
    private String buildResultKey(ShiftGenerateRequest req) {
        String raw = req.getTurbineId() + "|" + req.getWorkContent() + "|"
                + req.getRequiredQualType() + "|" + req.getRequiredQualLevel() + "|"
                + req.getCrewSize() + "|" + req.getDurationMinutes() + "|"
                + (req.getEquipmentType() == null ? "" : req.getEquipmentType()) + "|"
                + (req.getNotBefore() == null ? "" : req.getNotBefore());
        return "T" + req.getTurbineId() + "-H" + Math.abs(raw.hashCode());
    }

    private String buildSnapshot(WorkShift shift, WeatherConfirmRequest req,
                                 boolean passed, List<String> failures) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"at\":\"").append(LocalDateTime.now()).append("\"");
        sb.append(",\"currentStartAt\":\"").append(shift.getCurrentStartAt()).append("\"");
        sb.append(",\"currentFinishAt\":\"").append(shift.getCurrentFinishAt()).append("\"");
        sb.append(",\"fieldSafe\":").append(req.getFieldSafe());
        if (req.getObservedWindSpeed() != null) {
            sb.append(",\"observedWindSpeed\":").append(req.getObservedWindSpeed());
        }
        sb.append(",\"passed\":").append(passed);
        if (!failures.isEmpty()) {
            sb.append(",\"failures\":\"").append(String.join("；", failures)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String typeName(WeatherType type) {
        switch (type) {
            case WIND: return "风速";
            case RAIN: return "降雨";
            case LIGHTNING: return "雷电";
            default: return type.name();
        }
    }
}
