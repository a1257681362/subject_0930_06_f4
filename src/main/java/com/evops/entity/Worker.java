package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 高空作业人员。是否能排入某班次由 t_worker_qualification 中在作业时刻
 * 有效的资质决定（有效期 [validFrom, validTo) 左闭右开）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_worker")
public class Worker extends BaseEntity {
    /** 工号，全局唯一 */
    private String workerNo;
    private String name;
    private String site;
    /** ACTIVE / INACTIVE */
    private String status;
}
