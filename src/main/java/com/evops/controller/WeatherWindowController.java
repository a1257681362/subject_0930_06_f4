package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.WeatherWindowIngestRequest;
import com.evops.entity.WeatherWindow;
import com.evops.enums.WeatherType;
import com.evops.service.WeatherWindowService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 气象安全窗口接入：风速/降雨/雷电；补传/补录/重试按来源单据幂等。 */
@RestController
@RequestMapping("/api/weather-windows")
public class WeatherWindowController {

    private final WeatherWindowService weatherWindowService;

    public WeatherWindowController(WeatherWindowService weatherWindowService) {
        this.weatherWindowService = weatherWindowService;
    }

    /** 接入一条气象窗口（同来源单据重复提交只保留一条） */
    @PostMapping
    public ApiResponse<WeatherWindow> ingest(@Validated @RequestBody WeatherWindowIngestRequest req) {
        return ApiResponse.ok(weatherWindowService.ingest(req));
    }

    /** 查询某风机某类气象窗口（含场址级窗口） */
    @GetMapping
    public ApiResponse<List<WeatherWindow>> list(@RequestParam Long turbineId,
                                                 @RequestParam String site,
                                                 @RequestParam WeatherType weatherType) {
        return ApiResponse.ok(weatherWindowService.listForTurbine(turbineId, site, weatherType));
    }
}
