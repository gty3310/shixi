package com.ti.songdu;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.21.0 阅读计划引擎（纯逻辑、无 Android 依赖，桌面 harness 直驱本体验收）。
 * 口径按设计方案第四节「期限驱动」与任务书第三节：
 *  - 硬参数只有 startDate + termMonths + deadline；默认截止 = 开始日按日历月推
 *    termMonths 到同日序号（无同日序号取当月末日，LocalDate.plusMonths 语义）、含当天
 *  - 每日建议：剩余天数 = deadline - 今天 + 1；本阶段分得天数 = 剩余天数 × 本阶段
 *    shareDays ÷ 全部未完成阶段 shareDays 之和；今日建议 = 本阶段剩余量 ÷ 分得天数
 *    四舍五入取整；分得天数 <= 0 或剩余天数 = 1 时即全部剩余量
 *  - 章级化为「读 K 章 · 每章 N 遍」：N = passesPerChapter，K = max(1, ceil(取整建议 ÷ N))，
 *    今日达标线 = K×N（剩余量不足一章整额时以剩余量为线）
 *  - 三档状态：近 7 天（含今日）折算日均 vs 所需速度（剩余折算总量 ÷ 剩余天数）：
 *    >= ×1.1 快 / 0.9-1.1 正好 / < ×0.9 慢（预计晚 X 天）；近 7 天无数据时
 *    预计完成日 = 截止日、状态记「正好在节奏上」
 *  - 折算：阶段贡献遍数 = doneUnits ÷ totalUnits × totalPasses（封顶 totalPasses）
 *  - 阶段窗口：done 只计 [本阶段 startDate, 下一阶段 startDate)；阶段完成次日激活
 *    下一阶段；显式 startDate（提前开始）关闭上一阶段窗口
 * done 数据经 Provider 注入：records 型 / 章账型 / manualEntries 的汇总在 PlanStore 侧。
 */
public final class PlanEngine {

    public interface Provider {
        /** 某阶段在某一天的入账量（单位随阶段 unit） */
        int doneOn(int stageIndex, LocalDate date);
    }

    public enum Pace { FAST, ON_TRACK, SLOW }

    public static final class Result {
        public boolean finished = false;
        /** 当前阶段下标；全部完成时 = -1 */
        public int currentStage = -1;
        public final List<Integer> donePerStage = new ArrayList<>();
        /** 各阶段实际生效的开始日（推导或显式）；未激活为 null */
        public final List<LocalDate> effStartPerStage = new ArrayList<>();
        public final List<Boolean> completedPerStage = new ArrayList<>();
        /** 各阶段完成日；未完成为 null */
        public final List<LocalDate> completionPerStage = new ArrayList<>();
        public int remainingDays = 0;
        /** 今日建议量（整本=遍；章级=K×N 达标线） */
        public int todaySuggestionUnits = 0;
        /** 章级：今日建议读 K 章、每章 N 遍（整本阶段 K=0） */
        public int todayK = 0;
        public int todayN = 0;
        /** 今日已读（当前阶段、今天） */
        public int todayDone = 0;
        public Pace pace = Pace.ON_TRACK;
        public LocalDate predictedFinish = null;
        /** 慢于节奏时预计晚的天数（相对截止日），其余为 0 */
        public int lateDays = 0;
        /** 总进度百分比 0-100（折算口径） */
        public int totalProgressPct = 0;
    }

    /** 默认截止日：开始日按日历月推 months 个月到同日序号；无同日序号取当月末日 */
    public static LocalDate defaultDeadline(LocalDate start, int months) {
        return start.plusMonths(months);
    }

    /** 剩余天数：截止日 - 今天 + 1（两端都含） */
    public static int remainingDays(LocalDate deadline, LocalDate today) {
        return (int) (deadline.toEpochDay() - today.toEpochDay()) + 1;
    }

    public static Result compute(PlanData plan, Provider provider, LocalDate today) {
        Result r = new Result();
        int n = plan.stages.size();
        LocalDate planStart = LocalDate.parse(plan.startDate);
        LocalDate deadline = LocalDate.parse(plan.deadline);
        r.remainingDays = remainingDays(deadline, today);

        LocalDate prevCompletion = null;
        boolean haveCurrent = false;
        for (int i = 0; i < n; i++) {
            PlanData.Stage st = plan.stages.get(i);
            LocalDate explicit = st.startDate != null && !st.startDate.isEmpty()
                    ? LocalDate.parse(st.startDate) : null;
            LocalDate eff;
            if (explicit != null) eff = explicit;
            else if (i == 0) eff = planStart;
            else if (prevCompletion != null) eff = prevCompletion.plusDays(1);
            else eff = null;
            r.effStartPerStage.add(eff);
            if (eff == null) {
                r.donePerStage.add(0);
                r.completedPerStage.add(false);
                r.completionPerStage.add(null);
                continue;
            }
            LocalDate nextExplicit = null;
            if (i + 1 < n) {
                PlanData.Stage nx = plan.stages.get(i + 1);
                if (nx.startDate != null && !nx.startDate.isEmpty())
                    nextExplicit = LocalDate.parse(nx.startDate);
            }
            LocalDate winEndExcl = nextExplicit;
            LocalDate scanEnd = winEndExcl != null ? winEndExcl.minusDays(1) : today;
            int cum = 0;
            LocalDate completion = null;
            for (LocalDate d = eff; !d.isAfter(scanEnd); d = d.plusDays(1)) {
                cum += provider.doneOn(i, d);
                if (completion == null && cum >= st.totalUnits && st.totalUnits > 0) completion = d;
            }
            boolean completed = st.totalUnits > 0 && cum >= st.totalUnits;
            r.donePerStage.add(cum);
            r.completedPerStage.add(completed);
            r.completionPerStage.add(completion);
            if (completed) {
                prevCompletion = completion != null ? completion : eff;
            } else {
                prevCompletion = null;
                if (nextExplicit == null && !haveCurrent) {
                    r.currentStage = i;
                    haveCurrent = true;
                }
                // 有显式下一阶段时，本阶段窗口已关、被越过，继续看下一阶段
            }
        }
        // 当前阶段：第一个未完成且已激活的阶段；若都被越过/未激活，取最后一个已激活未完成者
        if (!haveCurrent) {
            for (int i = n - 1; i >= 0; i--) {
                if (!r.completedPerStage.get(i) && r.effStartPerStage.get(i) != null) {
                    r.currentStage = i; haveCurrent = true; break;
                }
            }
        }
        r.finished = n > 0;
        for (int i = 0; i < n; i++) if (!r.completedPerStage.get(i)) { r.finished = false; break; }
        if (r.finished) r.currentStage = -1;

        // 总进度（折算，贡献封顶）
        double totalPassesSum = 0, contribSum = 0;
        for (int i = 0; i < n; i++) {
            PlanData.Stage st = plan.stages.get(i);
            totalPassesSum += st.totalPasses;
            if (st.totalUnits > 0) {
                double c = (double) Math.min(r.donePerStage.get(i), st.totalUnits)
                        / st.totalUnits * st.totalPasses;
                contribSum += c;
            }
        }
        r.totalProgressPct = totalPassesSum > 0
                ? (int) Math.round(contribSum / totalPassesSum * 100.0) : 0;

        if (r.currentStage >= 0) {
            int ci = r.currentStage;
            PlanData.Stage cur = plan.stages.get(ci);
            r.todayDone = provider.doneOn(ci, today);
            int remainingUnits = Math.max(0, cur.totalUnits - r.donePerStage.get(ci));
            // 未完成阶段 share 之和（含当前）
            long shareSum = 0;
            for (int i = 0; i < n; i++)
                if (!r.completedPerStage.get(i)) shareSum += Math.max(0, plan.stages.get(i).shareDays);
            int sugg;
            if (remainingUnits <= 0) {
                sugg = 0;
            } else if (r.remainingDays <= 1) {
                sugg = remainingUnits;
            } else {
                double allocDays = shareSum > 0
                        ? (double) r.remainingDays * Math.max(0, cur.shareDays) / shareSum : 0;
                if (allocDays <= 0) sugg = remainingUnits;
                else sugg = (int) Math.round(remainingUnits / allocDays);
                if (sugg < 1) sugg = 1;
                if (sugg > remainingUnits) sugg = remainingUnits;
            }
            if (cur.isZhang()) {
                int nn = cur.passesPerChapter > 0 ? cur.passesPerChapter : 3;
                r.todayN = nn;
                if (sugg <= 0) {
                    r.todayK = 0; r.todaySuggestionUnits = 0;
                } else {
                    int k = (int) Math.ceil((double) sugg / nn);
                    if (k < 1) k = 1;
                    r.todayK = k;
                    r.todaySuggestionUnits = Math.min(k * nn, Math.max(remainingUnits, sugg));
                    if (remainingUnits >= k * nn) r.todaySuggestionUnits = k * nn;
                }
            } else {
                r.todaySuggestionUnits = sugg;
            }
        }

        // 三档状态与预计完成日（全计划折算口径）
        double remainingFolded = 0;
        for (int i = 0; i < n; i++) {
            PlanData.Stage st = plan.stages.get(i);
            if (r.completedPerStage.get(i) || st.totalUnits <= 0) continue;
            int rem = Math.max(0, st.totalUnits - r.donePerStage.get(i));
            remainingFolded += (double) rem / st.totalUnits * st.totalPasses;
        }
        double last7Folded = 0;
        for (int back = 0; back < 7; back++) {
            LocalDate d = today.minusDays(back);
            for (int i = 0; i < n; i++) {
                PlanData.Stage st = plan.stages.get(i);
                if (st.totalUnits <= 0) continue;
                LocalDate eff = r.effStartPerStage.get(i);
                if (eff == null || d.isBefore(eff)) continue;
                int v = provider.doneOn(i, d);
                if (v != 0) last7Folded += (double) v / st.totalUnits * st.totalPasses;
            }
        }
        if (r.finished) {
            r.pace = Pace.ON_TRACK;
            r.predictedFinish = r.completionPerStage.get(n - 1);
        } else if (last7Folded <= 0) {
            r.pace = Pace.ON_TRACK;
            r.predictedFinish = deadline;
        } else {
            double actualDaily = last7Folded / 7.0;
            double required = r.remainingDays > 0 ? remainingFolded / r.remainingDays : remainingFolded;
            long daysNeeded = (long) Math.ceil(remainingFolded / actualDaily);
            r.predictedFinish = today.plusDays(Math.max(0, daysNeeded - 1));
            if (required <= 0 || actualDaily >= required * 1.1) r.pace = Pace.FAST;
            else if (actualDaily >= required * 0.9) r.pace = Pace.ON_TRACK;
            else {
                r.pace = Pace.SLOW;
                long late = r.predictedFinish.toEpochDay() - deadline.toEpochDay();
                r.lateDays = late > 0 ? (int) late : 0;
            }
        }
        return r;
    }

    private PlanEngine() {}
}
