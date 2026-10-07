package com.evops;

import com.evops.common.ShiftPlanException;
import com.evops.dto.EquipmentCreateRequest;
import com.evops.dto.EquipmentRepairRequest;
import com.evops.dto.PostponeRequest;
import com.evops.dto.QualificationRequest;
import com.evops.dto.ShiftGenerateRequest;
import com.evops.dto.TurbineCreateRequest;
import com.evops.dto.WeatherConfirmRequest;
import com.evops.dto.WeatherWindowIngestRequest;
import com.evops.dto.WorkerCreateRequest;
import com.evops.entity.Equipment;
import com.evops.entity.NotificationReceipt;
import com.evops.entity.Postponement;
import com.evops.entity.ShiftWarning;
import com.evops.entity.Turbine;
import com.evops.entity.WeatherWindow;
import com.evops.entity.WorkShift;
import com.evops.entity.Worker;
import com.evops.enums.ConfirmStatus;
import com.evops.enums.ReceiptStatus;
import com.evops.enums.ShiftStatus;
import com.evops.enums.WarningType;
import com.evops.enums.WeatherType;
import com.evops.service.EquipmentService;
import com.evops.service.HighWorkScheduleService;
import com.evops.service.NotificationService;
import com.evops.service.PostponementService;
import com.evops.service.ShiftWarningService;
import com.evops.service.TurbineService;
import com.evops.service.WeatherWindowService;
import com.evops.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F4 高空作业排班：
 * 风速/降雨/雷电三窗口交集 + 人员资质 + 装备可用性 + 维修时长生成候选班次；
 * 窗口收窄只预警不静默改期；开工前二次确认天气；延期保留原计划与通知回执；
 * 过期资质人员不得排入；补传/补录/重试只形成一条可用结果。
 */
@SpringBootTest
@Transactional
class HighWorkScheduleTest {

    private static final String SITE = "张北坝上高空作业测试场";
    private static final String QUAL = "HIGH_WORK";

    @Autowired private TurbineService turbineService;
    @Autowired private WeatherWindowService weatherWindowService;
    @Autowired private WorkerService workerService;
    @Autowired private EquipmentService equipmentService;
    @Autowired private HighWorkScheduleService scheduleService;
    @Autowired private ShiftWarningService warningService;
    @Autowired private PostponementService postponementService;
    @Autowired private NotificationService notificationService;

    /** 基准时刻取未来，保证装备维修、资质有效期等相对时刻自然有效 */
    private final LocalDateTime base = LocalDateTime.now().plusDays(1).withHour(8).withMinute(0)
            .withSecond(0).withNano(0);

    // ---------- 辅助构造 ----------

    private Turbine newTurbine(String no) {
        TurbineCreateRequest req = new TurbineCreateRequest();
        req.setTurbineNo(no);
        req.setSite(SITE);
        req.setModel("WT3000-D140");
        req.setGridConnectedAt(base.minusYears(1));
        return turbineService.create(req);
    }

    private WeatherWindow addWindow(WeatherType type, LocalDateTime from, LocalDateTime to,
                                    Double safeMax, String docNo) {
        return addWindow(type, from, to, safeMax, docNo, null);
    }

    private WeatherWindow addWindow(WeatherType type, LocalDateTime from, LocalDateTime to,
                                    Double safeMax, String docNo, String replacesDocNo) {
        WeatherWindowIngestRequest req = new WeatherWindowIngestRequest();
        req.setSite(SITE);
        req.setWeatherType(type);
        req.setWindowFrom(from);
        req.setWindowTo(to);
        req.setSafeMaxValue(safeMax);
        req.setSourceChannel("MET");
        req.setSourceDocNo(docNo);
        req.setReplacesSourceDocNo(replacesDocNo);
        req.setReceivedAt(LocalDateTime.now());
        return weatherWindowService.ingest(req);
    }

    private void threeWindows(Turbine t, LocalDateTime from, LocalDateTime to) {
        addWindow(WeatherType.WIND, from, to, 10.0, t.getTurbineNo() + "-W");
        addWindow(WeatherType.RAIN, from, to, null, t.getTurbineNo() + "-R");
        addWindow(WeatherType.LIGHTNING, from, to, null, t.getTurbineNo() + "-L");
    }

    private Worker newWorker(String no, LocalDateTime qualFrom, LocalDateTime qualTo) {
        WorkerCreateRequest wreq = new WorkerCreateRequest();
        wreq.setWorkerNo(no);
        wreq.setName("作业员-" + no);
        wreq.setSite(SITE);
        Worker w = workerService.create(wreq);

        QualificationRequest q = new QualificationRequest();
        q.setQualType(QUAL);
        q.setQualLevel("PRIMARY");
        q.setValidFrom(qualFrom);
        q.setValidTo(qualTo);
        workerService.addQualification(w.getId(), q);
        return w;
    }

    private Equipment newEquipment(String no) {
        EquipmentCreateRequest ereq = new EquipmentCreateRequest();
        ereq.setEquipmentNo(no);
        ereq.setName("高空作业吊篮-" + no);
        ereq.setEquipmentType("BASKET");
        ereq.setSite(SITE);
        return equipmentService.create(ereq);
    }

    private ShiftGenerateRequest shiftReq(Turbine t, String content, int crew, int minutes) {
        ShiftGenerateRequest req = new ShiftGenerateRequest();
        req.setTurbineId(t.getId());
        req.setWorkContent(content);
        req.setRequiredQualType(QUAL);
        req.setRequiredQualLevel("PRIMARY");
        req.setCrewSize(crew);
        req.setDurationMinutes(minutes);
        req.setEquipmentType("BASKET");
        req.setNotBefore(base);
        return req;
    }

    private WeatherConfirmRequest confirm(Double wind, boolean fieldSafe) {
        WeatherConfirmRequest c = new WeatherConfirmRequest();
        c.setObservedWindSpeed(wind);
        c.setFieldSafe(fieldSafe);
        c.setObserver("现场负责人");
        return c;
    }

    // ---------- 用例 ----------

    @Test
    void 候选班次_综合三窗口交集资质装备与维修时长() {
        Turbine t = newTurbine("F401");
        // 风速窗口最短：[8,12)；降雨 [7,13)；雷电 [6,14)，交集应为 [8,12)
        addWindow(WeatherType.WIND, base, base.plusHours(4), 10.0, "F401-W");
        addWindow(WeatherType.RAIN, base.minusHours(1), base.plusHours(5), null, "F401-R");
        addWindow(WeatherType.LIGHTNING, base.minusHours(2), base.plusHours(6), null, "F401-L");
        newWorker("F401-甲", base.minusDays(30), base.plusDays(300));
        newWorker("F401-乙", base.minusDays(30), base.plusDays(300));
        newWorker("F401-丙", base.minusDays(30), base.plusDays(300));
        Equipment eq = newEquipment("F401-EQ");

        WorkShift shift = scheduleService.generate(
                shiftReq(t, "叶尖接闪器更换", 2, 120));

        assertEquals(ShiftStatus.CANDIDATE, shift.getStatus());
        assertEquals(ConfirmStatus.NONE, shift.getConfirmStatus());
        // 交集 [8,12)，最早起点 8:00，维修 120 分钟
        assertEquals(base, shift.getPlannedFrom());
        assertEquals(base.plusHours(2), shift.getPlannedTo());
        assertEquals(base, shift.getFeasibleFrom());
        assertEquals(base.plusHours(4), shift.getFeasibleTo());
        assertEquals(2, shift.getCrewSize());
        assertEquals(2, shift.getWorkerIds().split(",").length);
        assertEquals(eq.getId(), shift.getEquipmentId());

        // 重复生成（重试）同一排产意图：只形成一条可用结果
        WorkShift again = scheduleService.generate(shiftReq(t, "叶尖接闪器更换", 2, 120));
        assertEquals(shift.getId(), again.getId());
        long total = scheduleService.count(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WorkShift>()
                        .eq("result_key", shift.getResultKey()));
        assertEquals(1, total);
    }

    @Test
    void 交集放不下维修时长_被拦截并给出原因() {
        Turbine t = newTurbine("F402");
        addWindow(WeatherType.WIND, base, base.plusHours(1), 10.0, "F402-W");
        addWindow(WeatherType.RAIN, base, base.plusHours(4), null, "F402-R");
        addWindow(WeatherType.LIGHTNING, base, base.plusHours(4), null, "F402-L");
        newWorker("F402-甲", base.minusDays(1), base.plusDays(300));
        newEquipment("F402-EQ");

        ShiftPlanException ex = assertThrows(ShiftPlanException.class,
                () -> scheduleService.generate(shiftReq(t, "叶片前缘补强", 1, 120)));
        assertTrue(ex.getReasons().stream().anyMatch(r -> r.contains("60 分钟")),
                "应说明最长连续可行区间 60 分钟: " + ex.getReasons());
    }

    @Test
    void 过期资质人员不能被排入任务() {
        Turbine t = newTurbine("F403");
        threeWindows(t, base, base.plusHours(4));
        Worker valid = newWorker("F403-甲", base.minusDays(30), base.plusDays(300));
        // 资质恰在计划开始时刻过期：[..., validTo) 左闭右开，8:00 当刻已失效
        Worker expired = newWorker("F403-乙", base.minusDays(30), base);
        newEquipment("F403-EQ");

        // 直接判定：在作业开始时刻无效
        assertTrue(workerService.isQualifiedAt(valid.getId(), QUAL, "PRIMARY", base));
        assertFalse(workerService.isQualifiedAt(expired.getId(), QUAL, "PRIMARY", base));

        // 需要 2 人但只有 1 人有效：拦截
        ShiftPlanException ex = assertThrows(ShiftPlanException.class,
                () -> scheduleService.generate(shiftReq(t, "叶根裂纹修复", 2, 60)));
        assertTrue(ex.getMessage().contains("仅 1 人"));

        // 只需 1 人时可生成，且被排的是有效人员而非过期人员
        WorkShift shift = scheduleService.generate(shiftReq(t, "叶根裂纹修复", 1, 60));
        assertEquals(String.valueOf(valid.getId()), shift.getWorkerIds());
        assertFalse(shift.getWorkerIds().contains(String.valueOf(expired.getId())));
    }

    @Test
    void 装备维修中不可被新班次占用() {
        Turbine t = newTurbine("F404");
        threeWindows(t, base, base.plusHours(4));
        newWorker("F404-甲", base.minusDays(1), base.plusDays(300));
        Equipment eq = newEquipment("F404-EQ");

        EquipmentRepairRequest repair = new EquipmentRepairRequest();
        repair.setRepairFinishAt(base.plusHours(1));
        equipmentService.sendToRepair(eq.getId(), repair);

        ShiftPlanException ex = assertThrows(ShiftPlanException.class,
                () -> scheduleService.generate(shiftReq(t, "防雷引下线更换", 1, 60)));
        assertTrue(ex.getMessage().contains("BASKET"));

        // 维修完成后可正常排班
        equipmentService.finishRepair(eq.getId());
        WorkShift shift = scheduleService.generate(shiftReq(t, "防雷引下线更换", 1, 60));
        assertEquals(eq.getId(), shift.getEquipmentId());
    }

    @Test
    void 补传与重试_气象窗口幂等且排班只有一条结果() {
        Turbine t = newTurbine("F405");
        threeWindows(t, base, base.plusHours(4));
        newWorker("F405-甲", base.minusDays(1), base.plusDays(300));
        newEquipment("F405-EQ");

        // 同一来源单据重试接入：只保留一条
        WeatherWindow first = addWindow(WeatherType.WIND, base, base.plusHours(4), 10.0, "F405-W");
        WeatherWindow retry = addWindow(WeatherType.WIND, base, base.plusHours(4), 10.0, "F405-W");
        assertEquals(first.getId(), retry.getId());
        long windCount = weatherWindowService.list(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WeatherWindow>()
                        .eq("source_doc_no", "F405-W")).size();
        assertEquals(1, windCount);

        // 不同来源补传同一时段（并集互补），不影响排班幂等结论
        addWindow(WeatherType.WIND, base.plusHours(4), base.plusHours(6), 10.0, "F405-W-EXTRA");

        WorkShift s1 = scheduleService.generate(shiftReq(t, "涂层修补", 1, 60));
        WorkShift s2 = scheduleService.generate(shiftReq(t, "涂层修补", 1, 60));
        WorkShift s3 = scheduleService.generate(shiftReq(t, "涂层修补", 1, 60));
        assertEquals(s1.getId(), s2.getId());
        assertEquals(s1.getId(), s3.getId());
    }

    @Test
    void 修正预报收窄_预警但不静默改期_重复扫描不重复告警() {
        Turbine t = newTurbine("F406");
        threeWindows(t, base, base.plusHours(4));
        newWorker("F406-甲", base.minusDays(1), base.plusDays(300));
        newEquipment("F406-EQ");

        WorkShift shift = scheduleService.generate(shiftReq(t, "接闪器紧固", 1, 120));
        assertEquals(base, shift.getPlannedFrom());

        // 修正预报替换原风速窗口：风速可行区间终点由 12:00 提前到 11:00（起点放宽到 7:30）
        addWindow(WeatherType.WIND, base.minusMinutes(30), base.plusHours(3), 10.0,
                "F406-W-V2", "F406-W");

        // 交集变为 [7:30, 11:00)，仍完整覆盖计划 [8:00,10:00)，属于收窄
        List<ShiftWarning> warnings = warningService.scanOne(shift.getId());
        assertEquals(1, warnings.size());
        assertEquals(WarningType.NARROWED, warnings.get(0).getWarningType());

        // 关键：只预警不改期，原计划与状态不变
        WorkShift reloaded = scheduleService.mustGet(shift.getId());
        assertEquals(base, reloaded.getPlannedFrom());
        assertEquals(base.plusHours(2), reloaded.getPlannedTo());
        assertEquals(ShiftStatus.CANDIDATE, reloaded.getStatus());
        assertEquals(0, postponementService.listByShift(shift.getId()).size());

        // 重复扫描（重试）：同一收窄状态不重复告警
        List<ShiftWarning> again = warningService.scanOne(shift.getId());
        assertEquals(0, again.size());
        assertEquals(1, warningService.listByShift(shift.getId()).size());
    }

    @Test
    void 修正预报使计划不再被覆盖_高危预警且不自动改期() {
        Turbine t = newTurbine("F407");
        threeWindows(t, base, base.plusHours(4));
        newWorker("F407-甲", base.minusDays(1), base.plusDays(300));
        newEquipment("F407-EQ");

        WorkShift shift = scheduleService.generate(shiftReq(t, "叶尖检修", 1, 120));

        // 风速安全窗口起点后移到 8:30，原计划 8:00 开始已不被覆盖
        addWindow(WeatherType.WIND, base.plusMinutes(30), base.plusHours(3), 10.0,
                "F407-W-V2", "F407-W");

        List<ShiftWarning> warnings = warningService.scanOne(shift.getId());
        assertEquals(1, warnings.size());
        assertEquals(WarningType.UNCOVERED, warnings.get(0).getWarningType());

        // 系统绝不静默改期：原计划保留、无延期记录、仍为候选
        WorkShift reloaded = scheduleService.mustGet(shift.getId());
        assertEquals(base, reloaded.getPlannedFrom());
        assertEquals(ShiftStatus.CANDIDATE, reloaded.getStatus());
        assertEquals(0, postponementService.listByShift(shift.getId()).size());
    }

    @Test
    void 开工前必须二次确认天气_通过才开工_不通过禁止开工() {
        Turbine t = newTurbine("F408");
        threeWindows(t, base, base.plusHours(4));
        newWorker("F408-甲", base.minusDays(1), base.plusDays(300));
        newEquipment("F408-EQ");

        // 未确认直接开工：拒绝
        WorkShift shift = scheduleService.generate(shiftReq(t, "风速仪校准", 1, 60));
        assertThrows(IllegalArgumentException.class, () -> scheduleService.start(shift.getId()));

        // 实测风速 9 m/s，现场安全，二次确认通过 → 可开工 → 完成
        scheduleService.confirmWeather(shift.getId(), confirm(9.0, true));
        WorkShift confirmed = scheduleService.mustGet(shift.getId());
        assertEquals(ConfirmStatus.PASSED, confirmed.getConfirmStatus());
        assertEquals(ShiftStatus.CONFIRMED, confirmed.getStatus());
        assertNotNull(confirmed.getConfirmedAt());
        assertNotNull(confirmed.getWeatherSnapshotJson());

        scheduleService.start(shift.getId());
        assertEquals(ShiftStatus.STARTED, scheduleService.mustGet(shift.getId()).getStatus());
        scheduleService.complete(shift.getId());
        assertEquals(ShiftStatus.COMPLETED, scheduleService.mustGet(shift.getId()).getStatus());

        // 另一班次：实测风速 11 m/s 超过安全上限 10，确认不通过，禁止开工
        WorkShift bad = scheduleService.generate(shiftReq(t, "风速仪校准-超限", 1, 60));
        assertThrows(IllegalArgumentException.class,
                () -> scheduleService.confirmWeather(bad.getId(), confirm(11.0, true)));
        assertEquals(ConfirmStatus.FAILED, scheduleService.mustGet(bad.getId()).getConfirmStatus());
        assertThrows(IllegalArgumentException.class, () -> scheduleService.start(bad.getId()));

        // 现场判定不可作业：同样不通过
        WorkShift unsafe = scheduleService.generate(shiftReq(t, "风速仪校准-雷雨", 1, 60));
        assertThrows(IllegalArgumentException.class,
                () -> scheduleService.confirmWeather(unsafe.getId(), confirm(5.0, false)));
        assertThrows(IllegalArgumentException.class, () -> scheduleService.start(unsafe.getId()));
    }

    @Test
    void 延期保留原计划_通知回执幂等可重试() {
        Turbine t = newTurbine("F409");
        threeWindows(t, base, base.plusHours(10));
        newWorker("F409-甲", base.minusDays(1), base.plusDays(300));
        newEquipment("F409-EQ");

        WorkShift shift = scheduleService.generate(shiftReq(t, "齿轮箱高空检查", 1, 120));
        assertEquals(base, shift.getPlannedFrom());

        // 人工延期到 14:00
        PostponeRequest req = new PostponeRequest();
        req.setNewFrom(base.plusHours(6));
        req.setReason("午后雷暴窗口收窄，人工决定延期");
        req.setOperator("值长-郑十");
        req.setChannels(Arrays.asList("SMS", "APP"));
        Postponement p = postponementService.postpone(shift.getId(), req);

        // 原计划保留
        assertEquals(base, p.getOriginalFrom());
        assertEquals(base.plusHours(2), p.getOriginalTo());
        assertEquals(base.plusHours(6), p.getNewFrom());
        WorkShift reloaded = scheduleService.mustGet(shift.getId());
        assertEquals(base, reloaded.getPlannedFrom());
        assertEquals(base.plusHours(2), reloaded.getPlannedTo());
        assertEquals(base.plusHours(6), reloaded.getCurrentStartAt());

        // 两个通道各一条已送达回执
        List<NotificationReceipt> receipts = notificationService.listByShift(shift.getId());
        assertEquals(2, receipts.size());
        assertTrue(receipts.stream().allMatch(r -> r.getStatus() == ReceiptStatus.DELIVERED));

        // 通知重试：复用同一回执，不产生重复通知
        List<NotificationReceipt> retried =
                notificationService.notifyPostponement(reloaded, p, Arrays.asList("SMS", "APP"));
        assertEquals(2, retried.size());
        assertEquals(2, notificationService.listByShift(shift.getId()).size());

        // 再次延期：seq 递增，original 始终是最初计划
        PostponeRequest req2 = new PostponeRequest();
        req2.setNewFrom(base.plusHours(8));
        req2.setReason("现场仍有阵雨，二次延期");
        req2.setOperator("值长-郑十");
        req2.setChannels(Arrays.asList("SMS"));
        Postponement p2 = postponementService.postpone(shift.getId(), req2);
        assertEquals(2, p2.getSeq());
        assertEquals(base, p2.getOriginalFrom());
        assertEquals(base.plusHours(8), p2.getNewTo().minusHours(2));
    }

    @Test
    void 延期目标时刻资质已过期_拒绝排入() {
        Turbine t = newTurbine("F410");
        threeWindows(t, base, base.plusHours(10));
        // 资质在 13:00 过期：原计划 8:00 可排，延期到 14:00 时已过期
        newWorker("F410-甲", base.minusDays(1), base.plusHours(5));
        newEquipment("F410-EQ");

        WorkShift shift = scheduleService.generate(shiftReq(t, "叶内照明检修", 1, 120));
        assertEquals(base, shift.getPlannedFrom());

        PostponeRequest req = new PostponeRequest();
        req.setNewFrom(base.plusHours(6));
        req.setReason("气象原因延期");
        req.setChannels(Arrays.asList("APP"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> postponementService.postpone(shift.getId(), req));
        assertTrue(ex.getMessage().contains("已过期"), ex.getMessage());
        // 原计划未被改动
        assertEquals(base, scheduleService.mustGet(shift.getId()).getPlannedFrom());
    }
}
