package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.evops.dto.EquipmentCreateRequest;
import com.evops.dto.EquipmentRepairRequest;
import com.evops.entity.Equipment;
import com.evops.enums.EquipmentStatus;
import com.evops.mapper.EquipmentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 高空作业装备可用性。
 * 装备可用需同时满足：状态 AVAILABLE；作业区间落在其可用窗口 [availableFrom, availableTo) 内；
 * 维修中（IN_REPAIR）装备在 repairFinishAt 前不可被新班次占用。
 */
@Service
public class EquipmentService extends ServiceImpl<EquipmentMapper, Equipment> {

    private final AuditService auditService;

    public EquipmentService(AuditService auditService) {
        this.auditService = auditService;
    }

    @Transactional
    public Equipment create(EquipmentCreateRequest req) {
        String no = StringUtils.hasText(req.getEquipmentNo())
                ? req.getEquipmentNo()
                : "EQ" + System.currentTimeMillis();
        Long dup = count(new QueryWrapper<Equipment>().eq("equipment_no", no));
        if (dup > 0) {
            throw new IllegalArgumentException("装备编号已存在: " + no);
        }
        if (req.getAvailableTo() != null && req.getAvailableFrom() != null
                && !req.getAvailableTo().isAfter(req.getAvailableFrom())) {
            throw new IllegalArgumentException("装备可用窗口结束必须晚于开始（左闭右开）");
        }
        Equipment e = new Equipment();
        e.setEquipmentNo(no);
        e.setName(req.getName());
        e.setEquipmentType(req.getEquipmentType());
        e.setSite(req.getSite());
        e.setAvailableFrom(req.getAvailableFrom());
        e.setAvailableTo(req.getAvailableTo());
        e.setStatus(EquipmentStatus.AVAILABLE);
        save(e);
        return e;
    }

    /** 登记装备送修：[now, repairFinishAt) 内不可用 */
    @Transactional
    public Equipment sendToRepair(Long id, EquipmentRepairRequest req) {
        Equipment e = mustGet(id);
        if (req.getRepairFinishAt() == null || !req.getRepairFinishAt().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("维修完成时刻必须晚于当前时刻");
        }
        e.setStatus(EquipmentStatus.IN_REPAIR);
        e.setRepairFinishAt(req.getRepairFinishAt());
        updateById(e);
        auditService.record("EQUIPMENT", e.getId(), "EQUIPMENT_REPAIR",
                "{\"repairFinishAt\":\"" + req.getRepairFinishAt() + "\"}", null);
        return e;
    }

    /** 维修完成，恢复可用 */
    @Transactional
    public Equipment finishRepair(Long id) {
        Equipment e = mustGet(id);
        e.setStatus(EquipmentStatus.AVAILABLE);
        e.setRepairFinishAt(null);
        updateById(e);
        auditService.record("EQUIPMENT", e.getId(), "EQUIPMENT_REPAIRED", null, null);
        return e;
    }

    /**
     * 装备在作业区间 [start, finish)（左闭右开）是否可用：
     * 非维修中（或维修已完成）、且区间被装备可用窗口完整覆盖。
     */
    public boolean isAvailableFor(Equipment e, LocalDateTime start, LocalDateTime finish) {
        LocalDateTime now = LocalDateTime.now();
        if (e.getStatus() == EquipmentStatus.IN_REPAIR) {
            // 维修在开工前仍未结束则不可用
            if (e.getRepairFinishAt() != null && e.getRepairFinishAt().isAfter(now)
                    && start.isBefore(e.getRepairFinishAt())) {
                return false;
            }
            if (e.getRepairFinishAt() == null) {
                return false;
            }
        }
        if (e.getAvailableFrom() != null && start.isBefore(e.getAvailableFrom())) {
            return false;
        }
        if (e.getAvailableTo() != null && finish.isAfter(e.getAvailableTo())) {
            return false;
        }
        return true;
    }

    /** 场址内可用于 [start, finish) 的某类装备 */
    public List<Equipment> findAvailable(String site, String equipmentType,
                                         LocalDateTime start, LocalDateTime finish) {
        QueryWrapper<Equipment> qw = new QueryWrapper<Equipment>()
                .eq("site", site)
                .orderByAsc("id");
        if (StringUtils.hasText(equipmentType)) {
            qw.eq("equipment_type", equipmentType);
        }
        List<Equipment> result = new ArrayList<>();
        for (Equipment e : list(qw)) {
            if (isAvailableFor(e, start, finish)) {
                result.add(e);
            }
        }
        return result;
    }

    public Equipment mustGet(Long id) {
        Equipment e = getById(id);
        if (e == null) {
            throw new IllegalArgumentException("装备不存在: " + id);
        }
        return e;
    }
}
