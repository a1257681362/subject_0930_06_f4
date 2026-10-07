package com.evops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 气象窗口收窄自动预警扫描。
 * 补传/补录/重试带来新窗口后，周期重算三类窗口交集并对未开工班次预警；
 * 只预警、绝不改期（延期必须人工显式发起）。
 *
 * 通过 evops.shift-warning.scan.enabled 开关控制（测试环境关闭），
 * 扫描间隔 evops.shift-warning.scan.interval-ms，默认 5 分钟。
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "evops.shift-warning.scan.enabled", havingValue = "true",
        matchIfMissing = true)
public class ShiftWarningJob {

    private static final Logger log = LoggerFactory.getLogger(ShiftWarningJob.class);

    private final ShiftWarningService shiftWarningService;

    public ShiftWarningJob(ShiftWarningService shiftWarningService) {
        this.shiftWarningService = shiftWarningService;
    }

    @Scheduled(fixedDelayString = "${evops.shift-warning.scan.interval-ms:300000}",
            initialDelayString = "${evops.shift-warning.scan.initial-delay-ms:30000}")
    public void scan() {
        try {
            int created = shiftWarningService.scanAll();
            if (created > 0) {
                log.info("气象窗口收窄扫描新产生 {} 条班次预警（仅预警，未改期）", created);
            }
        } catch (Exception e) {
            // 扫描失败不得中断后续周期，也不得触发任何改期动作
            log.warn("气象窗口收窄扫描失败，本轮跳过：{}", e.getMessage());
        }
    }
}
