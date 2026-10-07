package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** 候选班次生成入参：维修时长 + 资质要求 + 班组人数 + 可选装备类型 */
@Data
public class ShiftGenerateRequest {
    @NotNull(message = "风机不能为空")
    private Long turbineId;
    @NotBlank(message = "维修工作内容不能为空")
    private String workContent;
    @NotBlank(message = "资质类型不能为空")
    private String requiredQualType;
    /** PRIMARY / ADVANCED */
    @NotBlank(message = "资质等级要求不能为空")
    private String requiredQualLevel;
    @Min(value = 1, message = "班组人数至少 1 人")
    private int crewSize;
    /** 计划维修时长（分钟） */
    @Min(value = 1, message = "维修时长至少 1 分钟")
    private int durationMinutes;
    /** 需要的装备类型，可空（不占用装备） */
    private String equipmentType;
    /** 不早于该时刻排产；为空取当前时刻 */
    private LocalDateTime notBefore;
}
