package com.evops.common;

import java.util.List;

/**
 * 候选班次生成不可行：携带全部拦截原因
 * （气象窗口不足 / 无有效资质人员 / 装备不可用 / 维修时长放不下）。
 */
public class ShiftPlanException extends RuntimeException {
    private final List<String> reasons;

    public ShiftPlanException(List<String> reasons) {
        super(String.join("；", reasons));
        this.reasons = reasons;
    }

    public List<String> getReasons() {
        return reasons;
    }
}
