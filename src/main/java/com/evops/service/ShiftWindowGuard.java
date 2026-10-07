package com.evops.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 气象窗口区间运算。所有窗口统一为左闭右开 [from, to)：
 * 交集取 [max(from), min(to))，下限等于上限即空区间。
 * 同类窗口取并集（多来源补传互补），三类窗口之间取交集。
 */
@Component
public class ShiftWindowGuard {

    /** 左闭右开区间 */
    public static class Interval {
        private final LocalDateTime from;
        private final LocalDateTime to;

        public Interval(LocalDateTime from, LocalDateTime to) {
            this.from = from;
            this.to = to;
        }

        public LocalDateTime getFrom() { return from; }
        public LocalDateTime getTo() { return to; }

        public boolean isEmpty() {
            return from == null || to == null || !to.isAfter(from);
        }

        public long lengthMinutes() {
            return java.time.Duration.between(from, to).toMinutes();
        }

        public boolean contains(LocalDateTime t) {
            return !t.isBefore(from) && t.isBefore(to);
        }
    }

    /** 多个同类窗口取并集：排序后合并重叠与首尾相接（[a,b)+[b,c) 连续安全）区间 */
    public List<Interval> union(List<Interval> intervals) {
        List<Interval> valid = new ArrayList<>();
        for (Interval i : intervals) {
            if (i != null && !i.isEmpty()) {
                valid.add(i);
            }
        }
        valid.sort((a, b) -> a.from.compareTo(b.from));
        List<Interval> merged = new ArrayList<>();
        for (Interval cur : valid) {
            if (merged.isEmpty()) {
                merged.add(cur);
                continue;
            }
            Interval last = merged.get(merged.size() - 1);
            if (cur.from.isAfter(last.to)) {
                merged.add(cur);
            } else {
                LocalDateTime newTo = cur.to.isAfter(last.to) ? cur.to : last.to;
                merged.set(merged.size() - 1, new Interval(last.from, newTo));
            }
        }
        return merged;
    }

    /** 两组已排序区间取交集（左闭右开） */
    public List<Interval> intersect(List<Interval> a, List<Interval> b) {
        List<Interval> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            Interval x = a.get(i);
            Interval y = b.get(j);
            LocalDateTime lo = x.from.isAfter(y.from) ? x.from : y.from;
            LocalDateTime hi = x.to.isBefore(y.to) ? x.to : y.to;
            if (hi.isAfter(lo)) {
                result.add(new Interval(lo, hi));
            }
            if (x.to.isBefore(y.to)) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }

    /**
     * 返回不早于 notBefore、能容纳 durationMinutes 作业的最早可行区间起点；
     * 放不下返回 null。
     */
    public LocalDateTime earliestStart(List<Interval> feasible, LocalDateTime notBefore, int durationMinutes) {
        for (Interval iv : feasible) {
            LocalDateTime start = iv.from.isBefore(notBefore) ? notBefore : iv.from;
            LocalDateTime finish = start.plusMinutes(durationMinutes);
            if (!finish.isAfter(iv.to) && start.isBefore(iv.to)) {
                return start;
            }
        }
        return null;
    }

    /** 找到覆盖时刻 t 的区间 */
    public Interval intervalCovering(List<Interval> intervals, LocalDateTime t) {
        for (Interval iv : intervals) {
            if (iv.contains(t)) {
                return iv;
            }
        }
        return null;
    }

    /**
     * 区间 [from, to) 是否被可行区间完整覆盖（作业全程安全）。
     */
    public boolean coversFully(List<Interval> feasible, LocalDateTime from, LocalDateTime to) {
        LocalDateTime cursor = from;
        for (Interval iv : feasible) {
            if (iv.to.compareTo(cursor) <= 0) {
                continue;
            }
            if (iv.from.isAfter(cursor)) {
                return false;
            }
            if (!iv.to.isBefore(to)) {
                return true;
            }
            cursor = iv.to;
        }
        return !cursor.isBefore(to);
    }
}
