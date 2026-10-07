package com.evops.enums;

/**
 * 高空作业班次状态机：
 * CANDIDATE  候选（系统按气象/资质/装备/时长生成，尚未二次确认）；
 * CONFIRMED  作业开始前二次确认天气通过；
 * STARTED    已开工；
 * COMPLETED  已完成；
 * CANCELLED  已取消。
 * 延期不改变状态、不改原计划，只追加 t_postponement。
 */
public enum ShiftStatus {
    CANDIDATE, CONFIRMED, STARTED, COMPLETED, CANCELLED
}
