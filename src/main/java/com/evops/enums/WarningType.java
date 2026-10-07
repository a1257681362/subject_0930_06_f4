package com.evops.enums;

/**
 * 班次气象预警类型：
 * NARROWED  三类窗口交集收窄（起点后移或终点前移）但仍覆盖原计划；
 * UNCOVERED 收窄后的交集不再覆盖原计划开始时刻，须人工决定延期（系统不静默改期）。
 */
public enum WarningType {
    NARROWED, UNCOVERED
}
