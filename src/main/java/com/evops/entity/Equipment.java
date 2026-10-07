package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import com.evops.enums.EquipmentStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 高空作业装备（吊篮、安全绳具、高空作业车等）。
 * 可用窗口 [availableFrom, availableTo)；维修中 status=IN_REPAIR，
 * [now, repairFinishAt) 内不可被新班次占用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_equipment")
public class Equipment extends BaseEntity {
    /** 装备编号，全局唯一 */
    private String equipmentNo;
    private String name;
    private String equipmentType;
    private String site;
    private LocalDateTime availableFrom;
    private LocalDateTime availableTo;
    private EquipmentStatus status;
    /** 维修完成时刻；维修中的装备在此时刻前不可用 */
    private LocalDateTime repairFinishAt;
}
