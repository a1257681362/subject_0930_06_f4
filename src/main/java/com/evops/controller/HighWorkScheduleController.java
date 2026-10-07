package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PostponeRequest;
import com.evops.dto.ShiftGenerateRequest;
import com.evops.dto.WeatherConfirmRequest;
import com.evops.entity.NotificationReceipt;
import com.evops.entity.Postponement;
import com.evops.entity.ShiftWarning;
import com.evops.entity.WorkShift;
import com.evops.service.HighWorkScheduleService;
import com.evops.service.NotificationService;
import com.evops.service.PostponementService;
import com.evops.service.ShiftWarningService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高空作业排班。
 * 候选生成 → 开工前天气二次确认 → 开工 → 完成；窗口收窄只预警，延期保留原计划与回执。
 */
@RestController
@RequestMapping("/api/high-work/shifts")
public class HighWorkScheduleController {

    private final HighWorkScheduleService scheduleService;
    private final ShiftWarningService warningService;
    private final PostponementService postponementService;
    private final NotificationService notificationService;

    public HighWorkScheduleController(HighWorkScheduleService scheduleService,
                                     ShiftWarningService warningService,
                                     PostponementService postponementService,
                                     NotificationService notificationService) {
        this.scheduleService = scheduleService;
        this.warningService = warningService;
        this.postponementService = postponementService;
        this.notificationService = notificationService;
    }

    /** 生成候选班次（同一排产意图重复提交只返回一条可用结果） */
    @PostMapping
    public ApiResponse<WorkShift> generate(@Validated @RequestBody ShiftGenerateRequest req) {
        return ApiResponse.ok(scheduleService.generate(req));
    }

    /** 全量扫描一次：补传/补录/重试后即时核对所有未开工班次的窗口收窄（只预警不改期） */
    @PostMapping("/rescan-all")
    public ApiResponse<Integer> rescanAll() {
        return ApiResponse.ok(warningService.scanAll());
    }

    /** 详情：班次 + 收窄预警 + 延期记录（含原计划）+ 通知回执 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        WorkShift shift = scheduleService.mustGet(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("shift", shift);
        detail.put("warnings", warningService.listByShift(id));
        detail.put("postponements", postponementService.listByShift(id));
        detail.put("receipts", notificationService.listByShift(id));
        return ApiResponse.ok(detail);
    }

    /** 作业开始前二次确认天气；不通过禁止开工（不自动改期） */
    @PostMapping("/{id}/confirm-weather")
    public ApiResponse<WorkShift> confirmWeather(@PathVariable Long id,
                                                 @Validated @RequestBody WeatherConfirmRequest req) {
        return ApiResponse.ok(scheduleService.confirmWeather(id, req));
    }

    /** 开工（必须先通过天气二次确认） */
    @PostMapping("/{id}/start")
    public ApiResponse<WorkShift> start(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.start(id));
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<WorkShift> complete(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.complete(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<WorkShift> cancel(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.cancel(id));
    }

    /** 人工延期：原计划保留，按通道留通知回执；系统不会因收窄自动调用它 */
    @PostMapping("/{id}/postpone")
    public ApiResponse<Postponement> postpone(@PathVariable Long id,
                                              @Validated @RequestBody PostponeRequest req) {
        return ApiResponse.ok(postponementService.postpone(id, req));
    }

    /** 手动触发一次窗口收窄扫描（定时任务之外的补传/重试后即时核对） */
    @PostMapping("/{id}/rescan")
    public ApiResponse<List<ShiftWarning>> rescan(@PathVariable Long id) {
        return ApiResponse.ok(warningService.scanOne(id));
    }

    @GetMapping("/{id}/warnings")
    public ApiResponse<List<ShiftWarning>> warnings(@PathVariable Long id) {
        return ApiResponse.ok(warningService.listByShift(id));
    }

    @GetMapping("/{id}/postponements")
    public ApiResponse<List<Postponement>> postponements(@PathVariable Long id) {
        return ApiResponse.ok(postponementService.listByShift(id));
    }

    @GetMapping("/{id}/receipts")
    public ApiResponse<List<NotificationReceipt>> receipts(@PathVariable Long id) {
        return ApiResponse.ok(notificationService.listByShift(id));
    }
}
