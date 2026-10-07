package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/** 开工前天气二次确认入参；observedWindSpeed 为现场实测风速（m/s） */
@Data
public class WeatherConfirmRequest {
    /** 现场实测风速；为空时只按气象窗口区间复核 */
    private Double observedWindSpeed;
    /** 确认人 */
    private String observer;
    /** 现场观测到降雨/雷电等不可作业情况时置 false，直接判不通过 */
    @NotNull(message = "必须给出现场是否可作业的结论")
    private Boolean fieldSafe;
}
