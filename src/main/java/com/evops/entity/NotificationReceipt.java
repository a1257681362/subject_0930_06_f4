package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import com.evops.enums.ReceiptStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 通知回执：延期/预警通知的送达留痕。
 * 唯一约束 (shiftId, postponementId, channel) 保证同一次延期同一通道只发一条，
 * 重试发送复用同一条回执并推进状态，不产生重复通知。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_notification_receipt")
public class NotificationReceipt extends BaseEntity {
    private Long shiftId;
    /** 预警通知无关联延期时为空 */
    private Long postponementId;
    private String channel;
    private String recipient;
    private String content;
    private ReceiptStatus status;
    private LocalDateTime sentAt;
    private LocalDateTime deliveredAt;
    private String errorMsg;
}
