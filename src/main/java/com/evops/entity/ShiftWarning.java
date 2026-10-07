package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import com.evops.enums.WarningType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 班次气象预警。窗口收窄时由扫描任务生成，只预警、绝不改期。
 * warningKey 对同一收窄状态幂等：补传/补录/重试触发的重复扫描不会产生重复预警。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_shift_warning")
public class ShiftWarning extends BaseEntity {
    private Long shiftId;
    private WarningType warningType;
    /** 触发收窄的气象窗口类型，可空（交集整体变化） */
    private String weatherType;
    private String message;
    private LocalDateTime oldFrom;
    private LocalDateTime oldTo;
    private LocalDateTime newFrom;
    private LocalDateTime newTo;
    /** 幂等键：班次 + 收窄后的交集区间 + 预警类型 */
    private String warningKey;
}
