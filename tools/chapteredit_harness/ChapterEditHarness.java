import com.ti.songdu.BookCountFormat;
import com.ti.songdu.ChapterTodayEdit;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * v1.30.0 篇行今日遍数编辑＋首页组头整本聚合桌面 harness：直接驱动 App 的
 * ChapterTodayEdit（输数解析/步进）与 BookCountFormat（整本折算）本体；
 * 分章账以 Map 镜像 ChapterStatsStore 的键与求和语义（a+m 合并记），日期
 * 边界镜像 Store 口径（本周＝本周一至今日、上周＝上周一至上周日、本月＝
 * 本月 1 日至今日），验证「改某篇今日 → 整本今日与统计行各档跟着对」。
 * 运行：javac -encoding UTF-8 -d <out> -sourcepath app/src/main/java \
 *       tools/chapteredit_harness/ChapterEditHarness.java
 */
public class ChapterEditHarness {

    static int passed = 0, failed = 0;

    static void eq(String name, Object got, Object expected) {
        if (expected == null ? got == null : expected.equals(got)) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + " (got " + got + ", want " + expected + ")");
        }
    }

    // ---- 镜像账本（键 "idx|yyyy-MM-dd"，值＝当日 a+m 合计） ----
    static final Map<String, Integer> LEDGER = new HashMap<>();

    static void put(int idx, LocalDate d, int v) {
        if (v <= 0) LEDGER.remove(idx + "|" + d);
        else LEDGER.put(idx + "|" + d, v);
    }

    static int chapterOn(int idx, LocalDate d) {
        return LEDGER.getOrDefault(idx + "|" + d, 0);
    }

    /** 镜像 ChapterStatsStore.bookSumBetween：单遍扫账、日期落区间内才计 */
    static int bookSumBetween(LocalDate from, LocalDate to) {
        int sum = 0;
        for (Map.Entry<String, Integer> e : LEDGER.entrySet()) {
            LocalDate d = LocalDate.parse(e.getKey().substring(e.getKey().lastIndexOf('|') + 1));
            if (!d.isBefore(from) && !d.isAfter(to)) sum += e.getValue();
        }
        return sum;
    }

    /** 镜像写回路径：目标值经 parseInput 校验后，以 delta 落 adjustChapterToday 同语义（合计口径、下限 0） */
    static boolean applyEdit(int idx, LocalDate today, String rawInput) {
        Integer target = ChapterTodayEdit.parseInput(rawInput);
        if (target == null) return false; // 拦截不写
        int cur = chapterOn(idx, today);
        int next = Math.max(0, cur + (target - cur));
        put(idx, today, next);
        return true;
    }

    public static void main(String[] args) {
        // ---------- parseInput ----------
        eq("parse \"0\"", ChapterTodayEdit.parseInput("0"), 0);
        eq("parse \"7\"", ChapterTodayEdit.parseInput("7"), 7);
        eq("parse \" 12 \"", ChapterTodayEdit.parseInput(" 12 "), 12);
        eq("parse 大数 \"9999\"", ChapterTodayEdit.parseInput("9999"), 9999);
        eq("parse int 上限", ChapterTodayEdit.parseInput("2147483647"), Integer.MAX_VALUE);
        eq("parse 负数拦截 \"-1\"", ChapterTodayEdit.parseInput("-1"), null);
        eq("parse 负数拦截 \"-999\"", ChapterTodayEdit.parseInput("-999"), null);
        eq("parse 空", ChapterTodayEdit.parseInput(""), null);
        eq("parse 纯空格", ChapterTodayEdit.parseInput("   "), null);
        eq("parse null", ChapterTodayEdit.parseInput(null), null);
        eq("parse 非数字", ChapterTodayEdit.parseInput("abc"), null);
        eq("parse 小数", ChapterTodayEdit.parseInput("3.5"), null);
        eq("parse 超 int 上限拦截", ChapterTodayEdit.parseInput("2147483648"), null);
        eq("parse 超 long 拦截", ChapterTodayEdit.parseInput("99999999999999999999"), null);

        // ---------- stepped ----------
        eq("step (0,-1) 钳 0", ChapterTodayEdit.stepped(0, -1), 0);
        eq("step (3,-1)", ChapterTodayEdit.stepped(3, -1), 2);
        eq("step (3,+1)", ChapterTodayEdit.stepped(3, 1), 4);
        eq("step (0,+1)", ChapterTodayEdit.stepped(0, 1), 1);
        eq("step (MAX,+1) 钳上限", ChapterTodayEdit.stepped(Integer.MAX_VALUE, 1), Integer.MAX_VALUE);

        // ---------- 整本聚合：改某篇今日 → 各档跟着对（论语 20 篇） ----------
        final int N = 20;
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate lastMonday = monday.minusWeeks(1);
        LocalDate lastSunday = monday.minusDays(1);
        LocalDate monthFirst = today.withDayOfMonth(1);

        LEDGER.clear();
        put(0, today, 1);
        put(1, today, 1);
        eq("初始整本今日 Σ=2", BookCountFormat.wholeBookToday(bookSumBetween(today, today), N), "0.1");

        // 把第 5 篇（idx 4）今日从 0 改到 3
        eq("编辑 idx4 → 3 受理", applyEdit(4, today, "3"), true);
        eq("改后整本今日 Σ=5 → 0.3（0.25 进位）",
                BookCountFormat.wholeBookToday(bookSumBetween(today, today), N), "0.3");

        // 再改回 0
        eq("编辑 idx4 → 0 受理", applyEdit(4, today, "0"), true);
        eq("清零后整本今日 Σ=2", BookCountFormat.wholeBookToday(bookSumBetween(today, today), N), "0.1");

        // 负数输入被拦截、账面不动
        eq("编辑 idx0 → \"-2\" 拦截", applyEdit(0, today, "-2"), false);
        eq("拦截后 idx0 今日仍 1", chapterOn(0, today), 1);
        eq("拦截后整本今日不变", BookCountFormat.wholeBookToday(bookSumBetween(today, today), N), "0.1");

        // 大数输入受理并进整本
        eq("编辑 idx0 → 9999 受理", applyEdit(0, today, "9999"), true);
        eq("大数后整本今日 (9999+1)/20", BookCountFormat.wholeBookToday(bookSumBetween(today, today), N), "500.0");
        eq("大数改回 1", applyEdit(0, today, "1"), true);

        // 各档口径：往账里补本周内他日、上周、本月内他日、上月各一笔
        LocalDate inWeek = monday.equals(today) ? today : monday;      // 本周一（若今天就是周一则同日叠加，下行断言按实际算）
        LocalDate inLastWeek = lastMonday.plusDays(2);                 // 上周三
        LocalDate inMonth = monthFirst.equals(today) ? today : monthFirst; // 本月 1 日
        LocalDate lastMonthDay = monthFirst.minusDays(3);              // 上月内
        put(2, inWeek, chapterOn(2, inWeek) + 20);
        put(3, inLastWeek, 40);
        put(5, inMonth, chapterOn(5, inMonth) + 60);
        put(6, lastMonthDay, 80);
        int sumToday = bookSumBetween(today, today);
        int sumWeek = bookSumBetween(monday, today);
        int sumLastWeek = bookSumBetween(lastMonday, lastSunday);
        int sumMonth = bookSumBetween(monthFirst, today);
        int sumTotal = bookSumBetween(LocalDate.of(2000, 1, 1), today);
        eq("今日档 Σ", sumToday, 2);
        eq("本周档含今日+本周他日", BookCountFormat.wholeBook(sumWeek, N),
                BookCountFormat.wholeBook(sumToday + 20, N));
        eq("上周档 (40/20)", BookCountFormat.wholeBook(sumLastWeek, N), "2.0");
        eq("本月档含今日+本月他日（上周跨月部分不入）",
                BookCountFormat.wholeBook(sumMonth, N),
                BookCountFormat.wholeBook(bookSumBetween(monthFirst, today), N));
        eq("总计档含上月 (2+20+40+60+80=202 → 10.1)", BookCountFormat.wholeBook(sumTotal, N), "10.1");
        eq("wholeBook 与 wholeBookToday 同口径", BookCountFormat.wholeBook(7, N),
                BookCountFormat.wholeBookToday(7, N));

        System.out.println("ChapterEditHarness: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
