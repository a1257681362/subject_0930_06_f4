package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.QualificationRequest;
import com.evops.dto.WorkerCreateRequest;
import com.evops.entity.Worker;
import com.evops.entity.WorkerQualification;
import com.evops.service.WorkerService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 作业人员与资质；过期资质人员不会被排入高空作业班次。 */
@RestController
@RequestMapping("/api/workers")
public class WorkerController {

    private final WorkerService workerService;

    public WorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @PostMapping
    public ApiResponse<Worker> create(@Validated @RequestBody WorkerCreateRequest req) {
        return ApiResponse.ok(workerService.create(req));
    }

    /** 登记资质（有效期左闭右开） */
    @PostMapping("/{id}/qualifications")
    public ApiResponse<WorkerQualification> addQualification(@PathVariable Long id,
                                                             @Validated @RequestBody QualificationRequest req) {
        return ApiResponse.ok(workerService.addQualification(id, req));
    }

    @GetMapping("/{id}/qualifications")
    public ApiResponse<List<WorkerQualification>> qualifications(@PathVariable Long id) {
        return ApiResponse.ok(workerService.listQualifications(id));
    }
}
