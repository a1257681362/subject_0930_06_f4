package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.evops.dto.QualificationRequest;
import com.evops.dto.WorkerCreateRequest;
import com.evops.entity.Worker;
import com.evops.entity.WorkerQualification;
import com.evops.mapper.WorkerMapper;
import com.evops.mapper.WorkerQualificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 作业人员与资质。资质有效期为左闭右开 [validFrom, validTo)：
 * 作业开始时刻 t 满足 validFrom <= t < validTo 才算有效，validTo 当刻即过期。
 */
@Service
public class WorkerService extends ServiceImpl<WorkerMapper, Worker> {

    private final WorkerQualificationMapper qualificationMapper;

    public WorkerService(WorkerQualificationMapper qualificationMapper) {
        this.qualificationMapper = qualificationMapper;
    }

    @Transactional
    public Worker create(WorkerCreateRequest req) {
        String workerNo = StringUtils.hasText(req.getWorkerNo())
                ? req.getWorkerNo()
                : "WK" + System.currentTimeMillis();
        Long dup = count(new QueryWrapper<Worker>().eq("worker_no", workerNo));
        if (dup > 0) {
            throw new IllegalArgumentException("工号已存在: " + workerNo);
        }
        Worker w = new Worker();
        w.setWorkerNo(workerNo);
        w.setName(req.getName());
        w.setSite(req.getSite());
        w.setStatus("ACTIVE");
        save(w);
        return w;
    }

    /** 给人员登记一条资质 */
    @Transactional
    public WorkerQualification addQualification(Long workerId, QualificationRequest req) {
        mustGet(workerId);
        if (!req.getValidTo().isAfter(req.getValidFrom())) {
            throw new IllegalArgumentException("资质有效期结束必须晚于开始（左闭右开）");
        }
        WorkerQualification q = new WorkerQualification();
        q.setWorkerId(workerId);
        q.setQualType(req.getQualType());
        q.setQualLevel(req.getQualLevel());
        q.setValidFrom(req.getValidFrom());
        q.setValidTo(req.getValidTo());
        q.setStatus("VALID");
        qualificationMapper.insert(q);
        return q;
    }

    public List<WorkerQualification> listQualifications(Long workerId) {
        return qualificationMapper.selectList(new QueryWrapper<WorkerQualification>()
                .eq("worker_id", workerId).orderByAsc("id"));
    }

    /**
     * 人员在时刻 t 是否持有 类型匹配且等级不低于要求、且在有效期 [validFrom, validTo) 内的资质。
     * 过期（t >= validTo）或未生效（t < validFrom）都视为不可排入。
     */
    public boolean isQualifiedAt(Long workerId, String qualType, String requiredLevel, LocalDateTime t) {
        List<WorkerQualification> qs = qualificationMapper.selectList(new QueryWrapper<WorkerQualification>()
                .eq("worker_id", workerId)
                .eq("qual_type", qualType)
                .eq("status", "VALID"));
        int requiredRank = com.evops.enums.QualLevel.rankOf(requiredLevel);
        for (WorkerQualification q : qs) {
            boolean inWindow = !t.isBefore(q.getValidFrom()) && t.isBefore(q.getValidTo());
            if (inWindow && com.evops.enums.QualLevel.rankOf(q.getQualLevel()) >= requiredRank) {
                return true;
            }
        }
        return false;
    }

    /**
     * 筛选场址内在时刻 t 持有有效资质、可排入班次的人员。
     * 过期资质人员不会出现在结果中。
     */
    public List<Worker> findQualifiedWorkers(String site, String qualType, String requiredLevel,
                                             LocalDateTime at, List<Long> excludeWorkerIds) {
        List<Worker> workers = list(new QueryWrapper<Worker>()
                .eq("site", site)
                .eq("status", "ACTIVE")
                .orderByAsc("id"));
        List<Worker> qualified = new ArrayList<>();
        for (Worker w : workers) {
            if (excludeWorkerIds != null && excludeWorkerIds.contains(w.getId())) {
                continue;
            }
            if (isQualifiedAt(w.getId(), qualType, requiredLevel, at)) {
                qualified.add(w);
            }
        }
        return qualified;
    }

    public Worker mustGet(Long id) {
        Worker w = getById(id);
        if (w == null) {
            throw new IllegalArgumentException("作业人员不存在: " + id);
        }
        return w;
    }
}
