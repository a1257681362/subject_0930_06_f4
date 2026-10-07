package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import com.evops.enums.ConfirmStatus;
import com.evops.enums.ShiftStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 高空作业班次（排班结果）。
 *
 * 原计划 plannedFrom/plannedTo 在候选生成时写定，永不修改；延期只追加
 * t_postponement 并更新 currentStartAt/currentFinishAt，保证“不静默改期”、
 * 原计划始终可查。feasibleFrom/feasibleTo 为生成时刻三类气象窗口的交集。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_work_shift")
public class WorkShift extends BaseEntity {
    /** 班次编号，全局唯一 */
    private String shiftNo;
    private String site;
    private Long turbineId;
    /** 维修工作内容 */
    private String workContent;
    /** 要求资质类型 */
    private String requiredQualType;
    /** 要求资质最低等级 */
    private String requiredQualLevel;
    /** 班组人数 */
    private Integer crewSize;
    /** 计划维修时长（分钟），决定所需窗口长度 */
    private Integer durationMinutes;
    /** 占用的装备，可空 */
    private Long equipmentId;
    /** 排入人员 id，逗号分隔 */
    private String workerIds;
    /** 生成时刻气象交集可行区间 [feasibleFrom, feasibleTo) */
    private LocalDateTime feasibleFrom;
    private LocalDateTime feasibleTo;
    /** 原计划开始/结束，永不修改 */
    private LocalDateTime plannedFrom;
    private LocalDateTime plannedTo;
    /** 当前应执行时间；首次=原计划，延期后更新为最新延期时间 */
    private LocalDateTime currentStartAt;
    private LocalDateTime currentFinishAt;
    private ShiftStatus status;
    private ConfirmStatus confirmStatus;
    /** 开工前二次确认时的天气快照（三窗口/观测值 JSON） */
    private String weatherSnapshotJson;
    private LocalDateTime confirmedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    /** 幂等键：同一 风机+原计划开始 只生成一条可用结果 */
    private String resultKey;
}
