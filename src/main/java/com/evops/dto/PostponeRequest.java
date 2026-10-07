package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 延期入参。延期为人工显式操作：原计划保留在 t_postponement，
 * 系统绝不因窗口收窄自动改期。channels 为通知通道（SMS/APP/EMAIL）。
 */
@Data
public class PostponeRequest {
    @NotNull(message = "延期后的开始时间不能为空")
    private LocalDateTime newFrom;
    @NotBlank(message = "延期原因不能为空")
    private String reason;
    private String operator;
    /** 通知通道，如 ["SMS","APP"]；按通道各留一条送达回执 */
    private java.util.List<String> channels;
}
