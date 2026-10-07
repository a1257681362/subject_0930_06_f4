package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** 装备送修入参；维修完成时刻前装备不可被新班次占用 */
@Data
public class EquipmentRepairRequest {
    @NotNull(message = "维修完成时刻不能为空")
    private LocalDateTime repairFinishAt;
}
