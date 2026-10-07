package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/** 装备建档入参；可用窗口 [availableFrom, availableTo)，两端可空 */
@Data
public class EquipmentCreateRequest {
    /** 为空时自动生成 */
    private String equipmentNo;
    @NotBlank(message = "装备名称不能为空")
    private String name;
    @NotBlank(message = "装备类型不能为空")
    private String equipmentType;
    @NotBlank(message = "场址不能为空")
    private String site;
    private LocalDateTime availableFrom;
    private LocalDateTime availableTo;
}
