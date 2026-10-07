package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 延期记录。每次延期一条，班次内 seq 递增；
 * originalFrom/originalTo 始终引用班次原计划，newFrom/newTo 为本次延期后的时间。
 * 延期不回写 t_work_shift.planned_*，原计划永久保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_postponement")
public class Postponement extends BaseEntity {
    private Long shiftId;
    /** 班次内第几次延期，从 1 开始 */
    private Integer seq;
    private LocalDateTime originalFrom;
    private LocalDateTime originalTo;
    private LocalDateTime newFrom;
    private LocalDateTime newTo;
    private String reason;
    private String operator;
    private LocalDateTime postponedAt;
}
