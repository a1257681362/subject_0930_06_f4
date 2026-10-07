package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.evops.dto.WeatherWindowIngestRequest;
import com.evops.entity.WeatherWindow;
import com.evops.enums.WeatherType;
import com.evops.mapper.WeatherWindowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 气象安全窗口接入。
 * 幂等：同一 (sourceChannel, sourceDocNo, weatherType) 只保留一条，
 * 补传 / 补录 / 重试命中同一来源单据直接返回已有窗口，不产生重复数据。
 */
@Service
public class WeatherWindowService extends ServiceImpl<WeatherWindowMapper, WeatherWindow> {

    private final AuditService auditService;

    public WeatherWindowService(AuditService auditService) {
        this.auditService = auditService;
    }

    /** 接入（含补传/补录/重试）一条气象窗口；同来源单据幂等。 */
    @Transactional
    public WeatherWindow ingest(WeatherWindowIngestRequest req) {
        if (req.getWindowFrom() == null || req.getWindowTo() == null) {
            throw new IllegalArgumentException("气象窗口起止时间不能为空");
        }
        if (!req.getWindowTo().isAfter(req.getWindowFrom())) {
            throw new IllegalArgumentException("气象窗口结束时间必须晚于开始时间（左闭右开）");
        }
        if (req.getWeatherType() == WeatherType.WIND && req.getSafeMaxValue() == null) {
            throw new IllegalArgumentException("风速窗口必须给出安全风速上限 safeMaxValue");
        }

        WeatherWindow existing = findByDoc(req.getSourceChannel(), req.getSourceDocNo(),
                req.getWeatherType());
        if (existing != null) {
            // 补传/补录/重试：同一来源单据只形成一条可用结果，直接复用，不新增、不改时间
            return existing;
        }

        WeatherWindow w = new WeatherWindow();
        w.setSite(req.getSite());
        w.setTurbineId(req.getTurbineId());
        w.setWeatherType(req.getWeatherType());
        w.setWindowFrom(req.getWindowFrom());
        w.setWindowTo(req.getWindowTo());
        w.setSafeMaxValue(req.getSafeMaxValue());
        w.setForecastValue(req.getForecastValue());
        w.setSourceChannel(req.getSourceChannel());
        w.setSourceDocNo(req.getSourceDocNo());
        w.setReplacesSourceDocNo(req.getReplacesSourceDocNo());
        w.setReceivedAt(req.getReceivedAt() != null ? req.getReceivedAt() : LocalDateTime.now());
        w.setRemark(req.getRemark());
        save(w);

        // 修正预报：标记被替换旧单据（旧窗口保留留痕，后续查询不再纳入可行区间）
        boolean replacing = req.getReplacesSourceDocNo() != null;
        auditService.record("WEATHER_WINDOW", w.getId(), "WEATHER_INGEST",
                "{\"sourceDocNo\":\"" + w.getSourceDocNo() + "\",\"weatherType\":\""
                        + w.getWeatherType() + "\",\"dedup\":false,\"replaces\":\""
                        + (replacing ? req.getReplacesSourceDocNo() : "") + "\"}", null);
        return w;
    }

    public WeatherWindow findByDoc(String channel, String docNo, WeatherType type) {
        return getOne(new QueryWrapper<WeatherWindow>()
                .eq("source_channel", channel)
                .eq("source_doc_no", docNo)
                .eq("weather_type", type)
                .last("limit 1"));
    }

    /**
     * 某风机某类气象的全部安全窗口：风机专属窗口 + 同场址的场址级窗口。
     * 被后续修正预报替换掉的旧窗口（其单据号出现在某窗口 replaces_source_doc_no 中）
     * 保留在库用于追溯，但不纳入排班可行区间。
     */
    public List<WeatherWindow> listForTurbine(Long turbineId, String site, WeatherType type) {
        List<WeatherWindow> all = list(new QueryWrapper<WeatherWindow>()
                .eq("site", site)
                .eq("weather_type", type)
                .and(w -> w.eq("turbine_id", turbineId).or().isNull("turbine_id"))
                .orderByAsc("window_from"));
        List<String> replacedDocNos = list(new QueryWrapper<WeatherWindow>()
                .isNotNull("replaces_source_doc_no")).stream()
                .map(WeatherWindow::getReplacesSourceDocNo)
                .collect(java.util.stream.Collectors.toList());
        List<WeatherWindow> active = new ArrayList<>();
        for (WeatherWindow w : all) {
            if (!replacedDocNos.contains(w.getSourceDocNo())) {
                active.add(w);
            }
        }
        return active;
    }

    /** 某风机某类气象窗口转为区间（左闭右开） */
    public List<ShiftWindowGuard.Interval> intervalsOfType(Long turbineId, String site, WeatherType type) {
        List<ShiftWindowGuard.Interval> intervals = new ArrayList<>();
        for (WeatherWindow w : listForTurbine(turbineId, site, type)) {
            intervals.add(new ShiftWindowGuard.Interval(w.getWindowFrom(), w.getWindowTo()));
        }
        return intervals;
    }

    /** 找到覆盖时刻 t 的风速安全窗口（用于开工前比对实测风速） */
    public WeatherWindow windWindowCovering(Long turbineId, String site, LocalDateTime t) {
        for (WeatherWindow w : listForTurbine(turbineId, site, WeatherType.WIND)) {
            if (!t.isBefore(w.getWindowFrom()) && t.isBefore(w.getWindowTo())) {
                return w;
            }
        }
        return null;
    }
}
