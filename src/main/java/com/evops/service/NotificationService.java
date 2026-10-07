package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.evops.entity.NotificationReceipt;
import com.evops.entity.Postponement;
import com.evops.entity.WorkShift;
import com.evops.enums.ReceiptStatus;
import com.evops.mapper.NotificationReceiptMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 通知回执。唯一约束 (shift_id, postponement_id, channel) 保证：
 * 同一次延期（或同一条预警）在同一通道只有一条回执；
 * 发送重试复用同一条记录推进状态，绝不产生重复通知。
 */
@Service
public class NotificationService extends ServiceImpl<NotificationReceiptMapper, NotificationReceipt> {

    /** 默认通知通道 */
    public static final String DEFAULT_CHANNEL = "APP";

    /**
     * 为一次延期按通道创建/复用回执并尝试发送。
     * 重试时返回已有回执（幂等），不重复通知。
     */
    @Transactional
    public List<NotificationReceipt> notifyPostponement(WorkShift shift, Postponement p,
                                                        List<String> channels) {
        List<String> chans = normalize(channels);
        String recipient = recipientOf(shift);
        String content = "高空作业班次 " + shift.getShiftNo() + " 已由 "
                + p.getOriginalFrom() + " 延期至 " + p.getNewFrom()
                + "，原因：" + p.getReason();

        List<NotificationReceipt> receipts = new ArrayList<>();
        for (String channel : chans) {
            receipts.add(send(shift.getId(), p.getId(), channel, recipient, content));
        }
        return receipts;
    }

    /** 预警通知（无关联延期，postponementId 为空）；同班次同通道只留一条 */
    @Transactional
    public NotificationReceipt notifyWarning(WorkShift shift, String channel, String content) {
        return send(shift.getId(), null, normalizeOne(channel), recipientOf(shift), content);
    }

    /**
     * 发送（或重试）一条回执：命中唯一约束则复用并在失败时重试，成功不重发。
     * 这里用“先查后写”，配合数据库唯一约束兜底重复。
     */
    private NotificationReceipt send(Long shiftId, Long postponementId, String channel,
                                     String recipient, String content) {
        NotificationReceipt existing = getOne(new QueryWrapper<NotificationReceipt>()
                .eq("shift_id", shiftId)
                .eq("channel", channel)
                .and(w -> {
                    if (postponementId == null) {
                        w.isNull("postponement_id");
                    } else {
                        w.eq("postponement_id", postponementId);
                    }
                })
                .last("limit 1"));
        if (existing != null) {
            // 已送达/已发送视为通知已成立，重试不再重复下发；失败的可重试
            if (existing.getStatus() == ReceiptStatus.DELIVERED
                    || existing.getStatus() == ReceiptStatus.SENT) {
                return existing;
            }
            existing.setStatus(ReceiptStatus.DELIVERED);
            existing.setSentAt(existing.getSentAt() != null ? existing.getSentAt() : LocalDateTime.now());
            existing.setDeliveredAt(LocalDateTime.now());
            existing.setErrorMsg(null);
            updateById(existing);
            return existing;
        }

        NotificationReceipt r = new NotificationReceipt();
        r.setShiftId(shiftId);
        r.setPostponementId(postponementId);
        r.setChannel(channel);
        r.setRecipient(recipient);
        r.setContent(content);
        // 联调期通道无真实网关：直接置为已送达，保留完整回执链路
        r.setStatus(ReceiptStatus.DELIVERED);
        r.setSentAt(LocalDateTime.now());
        r.setDeliveredAt(LocalDateTime.now());
        save(r);
        return r;
    }

    public List<NotificationReceipt> listByShift(Long shiftId) {
        return list(new QueryWrapper<NotificationReceipt>()
                .eq("shift_id", shiftId).orderByAsc("id"));
    }

    public List<NotificationReceipt> listByPostponement(Long postponementId) {
        return list(new QueryWrapper<NotificationReceipt>()
                .eq("postponement_id", postponementId).orderByAsc("id"));
    }

    private String recipientOf(WorkShift shift) {
        return "SHIFT:" + shift.getShiftNo() + ":CREW";
    }

    private List<String> normalize(List<String> channels) {
        List<String> result = new ArrayList<>();
        if (channels != null) {
            for (String c : channels) {
                if (c != null && !c.trim().isEmpty() && !result.contains(c.trim())) {
                    result.add(c.trim());
                }
            }
        }
        if (result.isEmpty()) {
            result.add(DEFAULT_CHANNEL);
        }
        return result;
    }

    private String normalizeOne(String channel) {
        return (channel == null || channel.trim().isEmpty()) ? DEFAULT_CHANNEL : channel.trim();
    }
}
