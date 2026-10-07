package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import com.evops.enums.WeatherType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 气象安全窗口：风速/降雨/雷电三类，窗口统一为左闭右开 [windowFrom, windowTo)。
 * 风速窗口可带 safeMaxValue（区间内风速安全上限）；降雨/雷电窗口表示区间内无降雨/无雷电。
 * 同一 (sourceChannel, sourceDocNo, weatherType) 唯一，补传/补录/重试不产生重复窗口。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_weather_window")
public class WeatherWindow extends BaseEntity {
    private String site;
    /** 可空：为空表示场址级窗口 */
    private Long turbineId;
    private WeatherType weatherType;
    private LocalDateTime windowFrom;
    private LocalDateTime windowTo;
    /** 风速安全上限 m/s；降雨/雷电为 null */
    private Double safeMaxValue;
    /** 预报值（留档，不作生效判定） */
    private Double forecastValue;
    /** 来源通道，如 MET 气象服务 / 场站观测 / 人工补录 */
    private String sourceChannel;
    /** 来源单据号，用于幂等去重 */
    private String sourceDocNo;
    /** 修正预报时填写：替换掉的同来源旧单据号；被替换窗口不参与可行区间计算 */
    private String replacesSourceDocNo;
    private LocalDateTime receivedAt;
    private String remark;
}
