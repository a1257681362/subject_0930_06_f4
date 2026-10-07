package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 人员资质：类型 + 等级，有效期窗口 [validFrom, validTo) 左闭右开。
 * 作业开始时刻 t 满足 validFrom <= t < validTo 才算有效；validTo 时刻即过期，
 * 过期资质人员不得被排入任务。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_worker_qualification")
public class WorkerQualification extends BaseEntity {
    private Long workerId;
    /** 资质类型，如 HIGH_WORK（高空作业证） */
    private String qualType;
    /** 资质等级，如 PRIMARY / ADVANCED */
    private String qualLevel;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    /** VALID / REVOKED */
    private String status;
}
