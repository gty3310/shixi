import com.ti.songdu.ChapterProgressTracker;
import com.ti.songdu.CompletionPolicy;
import com.ti.songdu.PlanData;
import com.ti.songdu.PlanEngine;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21.0 阅读计划桌面 harness：直驱 App 的 PlanEngine / ChapterProgressTracker /
 * CompletionPolicy.chapterBoundary 本体。
 * 运行：javac -d <out> -sourcepath app/src/main/java tools/plan_harness/PlanHarness.java
 */
public class PlanHarness {

    static int passed = 0, failed = 0;

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }

    /** 可编程 done 数据源：key = stageIndex@date */
    static final class FakeProvider implements PlanEngine.Provider {
        final Map<String, Integer> map = new HashMap<>();
        void set(int stage, LocalDate d, int v) { map.put(stage + "@" + d, v); }
        void add(int stage, LocalDate d, int v) { map.put(stage + "@" + d, get0(stage, d) + v); }
        int get0(int stage, LocalDate d) { return map.getOrDefault(stage + "@" + d, 0); }
        @Override public int doneOn(int stageIndex, LocalDate date) { return get0(stageIndex, date); }
    }

    static PlanData.Stage stage(String book, String unit, int chapterCount, int totalUnits,
                                int share, int n) {
        PlanData.Stage s = new PlanData.Stage();
        s.bookId = book; s.bookTitle = book; s.unit = unit;
        s.unitName = chapterCount > 0 ? "篇" : "";
        s.chapterCount = chapterCount; s.totalPasses = 21; s.totalUnits = totalUnits;
        s.shareDays = share; s.passesPerChapter = n;
        return s;
    }

    static PlanData plan(LocalDate start, LocalDate deadline, PlanData.Stage... stages) {
        PlanData p = new PlanData();
        p.name = "harness"; p.source = "custom";
        p.startDate = start.toString(); p.deadline = deadline.toString(); p.termMonths = 9;
        for (PlanData.Stage s : stages) p.stages.add(s);
        if (!p.stages.isEmpty()) p.stages.get(0).startDate = p.startDate;
        return p;
    }

    public static void main(String[] args) {
        // ---------- 截止日默认推算 ----------
        check("deadline Jan31 +1mo -> Feb end (non-leap)",
                PlanEngine.defaultDeadline(LocalDate.of(2026, 1, 31), 1)
                        .equals(LocalDate.of(2026, 2, 28)));
        check("deadline Jan31 +1mo -> Feb29 (leap 2028)",
                PlanEngine.defaultDeadline(LocalDate.of(2028, 1, 31), 1)
                        .equals(LocalDate.of(2028, 2, 29)));
        check("deadline 2026-10-30 +9mo -> 2027-07-30",
                PlanEngine.defaultDeadline(LocalDate.of(2026, 10, 30), 9)
                        .equals(LocalDate.of(2027, 7, 30)));
        // ---------- 剩余天数含两端 ----------
        check("remainingDays inclusive both ends",
                PlanEngine.remainingDays(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1)) == 5);
        check("remainingDays same day = 1",
                PlanEngine.remainingDays(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1)) == 1);

        // ---------- 配比分天后照建议读末日恰好读完（逐日模拟消费） ----------
        {
            LocalDate start = LocalDate.of(2026, 10, 30);
            LocalDate deadline = LocalDate.of(2027, 7, 30); // 274 天（含两端）
            PlanData p = plan(start, deadline,
                    stage("daxue", PlanData.UNIT_WHOLE, 0, 21, 4, 0),
                    stage("zhongyong", PlanData.UNIT_WHOLE, 0, 21, 7, 0));
            FakeProvider prov = new FakeProvider();
            LocalDate day = start;
            LocalDate finishDay = null;
            boolean suggestionAlwaysIntWithinRemaining = true;
            boolean lastDayRuleSeen = false, finishedBeforeDeadline = false;
            for (int guard = 0; guard < 400; guard++) {
                PlanEngine.Result r = PlanEngine.compute(p, prov, day);
                if (r.finished) {
                    finishDay = day.minusDays(1);
                    finishedBeforeDeadline = finishDay.isBefore(deadline);
                    break;
                }
                int cur = r.currentStage;
                int remaining = p.stages.get(cur).totalUnits - r.donePerStage.get(cur);
                int sugg = r.todaySuggestionUnits;
                if (sugg < 0 || sugg > remaining) suggestionAlwaysIntWithinRemaining = false;
                // 模拟到达截止日当天（剩余天数=1）时，建议必须 = 全部剩余
                if (r.remainingDays == 1) lastDayRuleSeen = sugg == remaining && sugg > 0;
                prov.add(cur, day, sugg); // 照建议读
                day = day.plusDays(1);
            }
            check("simulate: following suggestion finishes by deadline",
                    finishDay != null && !finishDay.isAfter(deadline));
            check("simulate: suggestion always within remaining (integer by type)",
                    suggestionAlwaysIntWithinRemaining);
            // 取整会让进度略超精确值，模拟通常提前读完（同样满足「末日恰好读完」）；
            // 若一路读到截止日当天，则末日建议必须恰为全部剩余——两种结局必居其一
            check("simulate: last-day rule (suggestion == all remaining) or finished early",
                    lastDayRuleSeen || finishedBeforeDeadline);
        }

        // ---------- 最后一天建议 = 全部剩余（直接构造） ----------
        {
            LocalDate today = LocalDate.of(2027, 7, 30);
            PlanData p = plan(LocalDate.of(2026, 10, 30), today,
                    stage("daxue", PlanData.UNIT_WHOLE, 0, 21, 4, 0));
            FakeProvider prov = new FakeProvider();
            prov.set(0, LocalDate.of(2027, 7, 29), 18); // 窗口内已读 18（须在阶段窗口内）
            // 窗口从 startDate 起算，上行日期在窗口内，done 合计 18
            PlanEngine.Result r = PlanEngine.compute(p, prov, today);
            check("last day: suggestion == all remaining (3)",
                    r.todaySuggestionUnits == 3 && r.currentStage == 0);
        }

        // ---------- 章级整数化：K×N >= 取整建议、无小数 ----------
        {
            LocalDate start = LocalDate.of(2026, 10, 30);
            LocalDate deadline = start.plusDays(79); // 分得天数 = 80（单阶段）
            PlanData p = plan(start, deadline,
                    stage("lunyu", PlanData.UNIT_ZHANG, 20, 420, 80, 5));
            FakeProvider prov = new FakeProvider();
            PlanEngine.Result r = PlanEngine.compute(p, prov, start);
            // 剩余 420 / 80 天 = 5.25 -> 取整 5；K = ceil(5/5)=1，达标线 5
            check("zhang: K>=1 and N==5", r.todayK >= 1 && r.todayN == 5);
            check("zhang: target == K*N and >= rounded suggestion",
                    r.todaySuggestionUnits == r.todayK * r.todayN && r.todaySuggestionUnits >= 5);
            PlanData p2 = plan(start, start.plusDays(39),
                    stage("lunyu", PlanData.UNIT_ZHANG, 20, 420, 80, 5));
            PlanEngine.Result r2 = PlanEngine.compute(p2, prov, start);
            // 剩余 420/40 = 10.5 -> 11；K = ceil(11/5) = 3，达标线 15 >= 11
            check("zhang: K=ceil(rounded/N), target>=rounded",
                    r2.todayK == 3 && r2.todaySuggestionUnits == 15 && r2.todaySuggestionUnits >= 11);
            // 道德经口径：N=2，K 基准按 5 章一组演化——剩余大时 K 可 > 5（只按量）
            PlanData p3 = plan(start, start.plusDays(31),
                    stage("daodejing", PlanData.UNIT_ZHANG, 81, 1701, 32, 2));
            PlanEngine.Result r3 = PlanEngine.compute(p3, prov, start);
            check("zhang daodejing: N==2, target=K*2",
                    r3.todayN == 2 && r3.todaySuggestionUnits == r3.todayK * 2);
        }

        // ---------- 改截止日后建议量逆推变化 ----------
        {
            LocalDate start = LocalDate.of(2026, 10, 30);
            PlanData near = plan(start, start.plusDays(9),
                    stage("daxue", PlanData.UNIT_WHOLE, 0, 21, 4, 0));
            PlanData far = plan(start, start.plusDays(99),
                    stage("daxue", PlanData.UNIT_WHOLE, 0, 21, 4, 0));
            FakeProvider prov = new FakeProvider();
            int sNear = PlanEngine.compute(near, prov, start).todaySuggestionUnits;
            int sFar = PlanEngine.compute(far, prov, start).todaySuggestionUnits;
            check("deadline change re-derives suggestion (near > far)", sNear > sFar);
            check("whole suggestion integer bounds", sNear == 2 && sFar == 1);
        }

        // ---------- 三档状态三分支 + 无数据 ----------
        {
            LocalDate today = LocalDate.of(2026, 11, 15);
            LocalDate start = today.minusDays(6);
            LocalDate deadline = today.plusDays(99); // 剩余 100 天
            // 阶段总量 700、所需 = 剩余折算/100；近 7 天全在窗口内
            java.util.function.IntFunction<PlanEngine.Result> run = last7Total -> {
                PlanData p = plan(start, deadline,
                        stage("big", PlanData.UNIT_WHOLE, 0, 700, 10, 0));
                // totalPasses 需与折算一致：stage() 设 21，总量 700 -> 每单位折算 21/700
                FakeProvider prov = new FakeProvider();
                int per = last7Total / 7, rem = last7Total % 7;
                for (int i = 0; i < 7; i++) prov.set(0, start.plusDays(i), per + (i < rem ? 1 : 0));
                return PlanEngine.compute(p, prov, today);
            };
            // 已读 X 后剩余 = 700-X；所需日均折算 = (700-X)*21/700/100；实际日均折算 = X/7*21/700
            // X=350: 实际=1.5折算/日 所需=1.05 -> 快
            check("pace FAST branch", run.apply(350).pace == PlanEngine.Pace.FAST);
            // X=70: 实际=0.3 所需=(630)*0.03/100=0.189 -> 0.3>=0.208 快？重算：
            // 0.3 vs required*1.1=0.208 -> FAST。取 X=49: 实际=0.21，所需=(651)*.03/100=0.1953，*1.1=0.215，*0.9=0.176 -> ON
            check("pace ON_TRACK branch", run.apply(49).pace == PlanEngine.Pace.ON_TRACK);
            // X=7: 实际=0.03 所需=0.2079*0.9=0.187 -> SLOW 且预计晚 >0
            PlanEngine.Result slow = run.apply(7);
            check("pace SLOW branch + lateDays>0", slow.pace == PlanEngine.Pace.SLOW && slow.lateDays > 0);
            // 无数据
            PlanData p0 = plan(start, deadline, stage("big", PlanData.UNIT_WHOLE, 0, 700, 10, 0));
            PlanEngine.Result none = PlanEngine.compute(p0, new FakeProvider(), today);
            check("pace no-data: ON_TRACK and predicted == deadline",
                    none.pace == PlanEngine.Pace.ON_TRACK && deadline.equals(none.predictedFinish));
        }

        // ---------- 阶段完成次日激活 + 窗口 ----------
        {
            LocalDate d1 = LocalDate.of(2026, 10, 30);
            PlanData p = plan(d1, d1.plusDays(200),
                    stage("daxue", PlanData.UNIT_WHOLE, 0, 2, 4, 0),
                    stage("zhongyong", PlanData.UNIT_WHOLE, 0, 21, 7, 0));
            FakeProvider prov = new FakeProvider();
            prov.set(0, d1, 2);            // 第一天读完第一本
            prov.set(1, d1, 5);            // 同一天第二本的量在窗口外（次日才激活），不计
            prov.set(1, d1.plusDays(1), 3);
            PlanEngine.Result r = PlanEngine.compute(p, prov, d1.plusDays(1));
            check("stage completes -> next starts next day",
                    d1.plusDays(1).equals(r.effStartPerStage.get(1)));
            check("window: next stage excludes completion-day counts",
                    r.donePerStage.get(1) == 3);
            check("current stage advanced", r.currentStage == 1);
        }

        // ---------- 提前开始：窗口关闭、已有量保留 ----------
        {
            LocalDate today = LocalDate.of(2026, 11, 10);
            LocalDate start = LocalDate.of(2026, 11, 1);
            PlanData p = plan(start, start.plusDays(200),
                    stage("daxue", PlanData.UNIT_WHOLE, 0, 21, 4, 0),
                    stage("zhongyong", PlanData.UNIT_WHOLE, 0, 21, 7, 0));
            p.stages.get(1).startDate = today.toString(); // 提前开始
            FakeProvider prov = new FakeProvider();
            prov.set(0, start, 3);
            prov.set(0, today, 4); // 窗口已关：不计入阶段 0
            prov.set(1, today, 2);
            PlanEngine.Result r = PlanEngine.compute(p, prov, today);
            check("early start: prev window closed, done kept",
                    r.donePerStage.get(0) == 3);
            check("early start: current = next stage", r.currentStage == 1);
            check("early start: next done counts from today", r.donePerStage.get(1) == 2);
        }

        // ---------- 总进度折算 ----------
        {
            LocalDate today = LocalDate.of(2026, 11, 1);
            PlanData p = plan(today, today.plusDays(100),
                    stage("lunyu", PlanData.UNIT_ZHANG, 20, 420, 80, 5));
            FakeProvider prov = new FakeProvider();
            prov.set(0, today, 210); // 一半章·遍 -> 折算 10.5/21 = 50%
            PlanEngine.Result r = PlanEngine.compute(p, prov, today);
            check("total progress folded 50%", r.totalProgressPct == 50);
        }

        // ---------- 章界计数 ----------
        {
            ChapterProgressTracker tr = new ChapterProgressTracker();
            tr.setChapters(new long[]{0, 60000}, new long[]{60000, 120000});
            List<Integer> c1 = new java.util.ArrayList<>();
            for (long pos : new long[]{0, 30000, 59999, 61000}) c1.addAll(tr.feed(pos, true));
            check("chapter: normal pass counts 1", c1.size() == 1 && c1.get(0) == 0);

            ChapterProgressTracker tr2 = new ChapterProgressTracker();
            tr2.setChapters(new long[]{0, 60000}, new long[]{60000, 120000});
            List<Integer> c2 = new java.util.ArrayList<>();
            for (long pos : new long[]{58500, 60500}) c2.addAll(tr2.feed(pos, true));
            check("chapter: seek near end then finish does NOT count", c2.isEmpty());

            ChapterProgressTracker tr3 = new ChapterProgressTracker();
            tr3.setChapters(new long[]{0, 60000}, new long[]{60000, 120000});
            List<Integer> c3 = new java.util.ArrayList<>();
            for (long pos : new long[]{0, 30000, 61000, 90000, 121000}) c3.addAll(tr3.feed(pos, true));
            check("chapter: whole-book pass counts 2 chapters",
                    c3.size() == 2 && c3.get(0) == 0 && c3.get(1) == 1);

            ChapterProgressTracker tr4 = new ChapterProgressTracker();
            tr4.setChapters(new long[]{0}, new long[]{60000});
            List<Integer> c4 = new java.util.ArrayList<>();
            for (long pos : new long[]{0, 30000, 60100}) c4.addAll(tr4.feed(pos, true));
            tr4.resetToChapter(0, 0); // 单章循环回绕
            for (long pos : new long[]{100, 30000, 60200}) c4.addAll(tr4.feed(pos, true));
            check("chapter: single-chapter loop two rounds counts 2", c4.size() == 2);

            ChapterProgressTracker tr5 = new ChapterProgressTracker();
            tr5.setChapters(new long[]{0}, new long[]{60000});
            List<Integer> c5 = new java.util.ArrayList<>();
            c5.addAll(tr5.feed(0, true));
            c5.addAll(tr5.feed(30000, false)); // 暂停样本不判定
            int flushed = tr5.flushAtTrackEnd();
            check("chapter: flush at track end counts armed chapter", flushed == 0 && c5.isEmpty());
        }

        // ---------- 章界去向矩阵 ----------
        check("boundary: chapter play + chapter scope + loop -> WRAP",
                CompletionPolicy.chapterBoundary(true, true, true)
                        == CompletionPolicy.ChapterAction.WRAP_CHAPTER);
        check("boundary: chapter play + book scope -> STOP",
                CompletionPolicy.chapterBoundary(true, false, true)
                        == CompletionPolicy.ChapterAction.STOP_CHAPTER);
        check("boundary: chapter play + loop off -> STOP",
                CompletionPolicy.chapterBoundary(false, true, true)
                        == CompletionPolicy.ChapterAction.STOP_CHAPTER);
        check("boundary: whole play + chapter scope + loop -> WRAP",
                CompletionPolicy.chapterBoundary(true, true, false)
                        == CompletionPolicy.ChapterAction.WRAP_CHAPTER);
        check("boundary: whole play + book scope -> CONTINUE",
                CompletionPolicy.chapterBoundary(true, false, false)
                        == CompletionPolicy.ChapterAction.CONTINUE);
        check("boundary: whole play + loop off -> CONTINUE (scope inert)",
                CompletionPolicy.chapterBoundary(false, true, false)
                        == CompletionPolicy.ChapterAction.CONTINUE);

        System.out.println("PlanHarness: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
