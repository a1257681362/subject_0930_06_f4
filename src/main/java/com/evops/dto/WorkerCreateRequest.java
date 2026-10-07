package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 作业人员建档入参 */
@Data
public class WorkerCreateRequest {
    /** 为空时自动生成 */
    private String workerNo;
    @NotBlank(message = "姓名不能为空")
    private String name;
    @NotBlank(message = "场址不能为空")
    private String site;
}
