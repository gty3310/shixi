import com.ti.songdu.BookCountFormat;

/**
 * v1.26.0 分章书整本今日遍数折算桌面 harness：直接驱动 App 的 BookCountFormat 本体。
 * 口径：整本遍数＝Σ各章今日 ÷ 章数，一位小数四舍五入（用户亲口例：论语 7 章
 * 0.35 显示 0.4、9 章 0.45 显示 0.5）。
 * 运行：javac -encoding UTF-8 -d <out> -sourcepath app/src/main/java tools/bookcount_harness/BookCountHarness.java
 */
public class BookCountHarness {

    static int passed = 0, failed = 0;

    static void check(int sum, int n, String expected) {
        String got = BookCountFormat.wholeBookToday(sum, n);
        String name = "wholeBookToday(" + sum + ", " + n + ") -> " + expected;
        if (expected.equals(got)) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + " (got " + got + ")");
        }
    }

    public static void main(String[] args) {
        check(1, 20, "0.1");
        check(7, 20, "0.4");
        check(8, 20, "0.4");
        check(9, 20, "0.5");
        check(10, 20, "0.5");
        check(0, 20, "0.0");
        check(20, 20, "1.0");
        check(21, 20, "1.1");
        check(1, 30, "0.0");
        check(15, 30, "0.5");
        check(2, 30, "0.1");

        System.out.println("BookCountHarness: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
