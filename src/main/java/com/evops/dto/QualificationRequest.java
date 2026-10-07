package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** 人员资质登记入参；有效期 [validFrom, validTo) 左闭右开 */
@Data
public class QualificationRequest {
    @NotBlank(message = "资质类型不能为空")
    private String qualType;
    /** PRIMARY / ADVANCED */
    @NotBlank(message = "资质等级不能为空")
    private String qualLevel;
    @NotNull(message = "资质生效时间不能为空")
    private LocalDateTime validFrom;
    @NotNull(message = "资质失效时间不能为空")
    private LocalDateTime validTo;
}
