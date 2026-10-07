package com.evops.dto;

import com.evops.enums.WeatherType;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 气象窗口接入入参（含补传/补录/重试）。
 * 幂等键 = sourceChannel + sourceDocNo + weatherType。
 */
@Data
public class WeatherWindowIngestRequest {
    @NotBlank(message = "场址不能为空")
    private String site;
    /** 为空表示场址级窗口 */
    private Long turbineId;
    @NotNull(message = "气象类型不能为空")
    private WeatherType weatherType;
    @NotNull(message = "窗口开始时间不能为空")
    private LocalDateTime windowFrom;
    @NotNull(message = "窗口结束时间不能为空")
    private LocalDateTime windowTo;
    /** 风速窗口必填：安全风速上限 m/s */
    private Double safeMaxValue;
    private Double forecastValue;
    @NotBlank(message = "来源通道不能为空")
    private String sourceChannel;
    @NotBlank(message = "来源单据号不能为空")
    private String sourceDocNo;
    /** 修正/补录预报：填写被本窗口替换的同来源旧单据号；被替换窗口不再参与排班 */
    private String replacesSourceDocNo;
    private LocalDateTime receivedAt;
    private String remark;
}
