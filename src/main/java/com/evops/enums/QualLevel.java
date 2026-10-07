package com.evops.enums;

/**
 * 高空作业资质等级。rank 越大等级越高；
 * 排班要求某等级时，等级不低于它的人员都可排入（ADVANCED 可做 PRIMARY 要求的活）。
 */
public enum QualLevel {
    PRIMARY(1),
    ADVANCED(2);

    private final int rank;

    QualLevel(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }

    public static int rankOf(String level) {
        if (level == null) {
            throw new IllegalArgumentException("资质等级不能为空");
        }
        try {
            return valueOf(level.trim().toUpperCase()).rank;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知资质等级: " + level);
        }
    }
}
