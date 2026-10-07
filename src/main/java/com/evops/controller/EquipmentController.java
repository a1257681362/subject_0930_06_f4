package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.EquipmentCreateRequest;
import com.evops.dto.EquipmentRepairRequest;
import com.evops.entity.Equipment;
import com.evops.service.EquipmentService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 高空作业装备；维修中在维修完成前不可被新班次占用。 */
@RestController
@RequestMapping("/api/equipments")
public class EquipmentController {

    private final EquipmentService equipmentService;

    public EquipmentController(EquipmentService equipmentService) {
        this.equipmentService = equipmentService;
    }

    @PostMapping
    public ApiResponse<Equipment> create(@Validated @RequestBody EquipmentCreateRequest req) {
        return ApiResponse.ok(equipmentService.create(req));
    }

    /** 送修：维修完成时刻前装备不可用 */
    @PostMapping("/{id}/repair")
    public ApiResponse<Equipment> sendToRepair(@PathVariable Long id,
                                               @Validated @RequestBody EquipmentRepairRequest req) {
        return ApiResponse.ok(equipmentService.sendToRepair(id, req));
    }

    /** 维修完成，恢复可用 */
    @PostMapping("/{id}/repair-finish")
    public ApiResponse<Equipment> finishRepair(@PathVariable Long id) {
        return ApiResponse.ok(equipmentService.finishRepair(id));
    }
}
