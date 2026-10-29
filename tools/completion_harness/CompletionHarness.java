import com.ti.songdu.CompletionPolicy;

/**
 * v1.20.7 播完去向判定桌面 harness：直接驱动 App 的 CompletionPolicy 本体。
 * 断言口径（判定不依赖声音轨/模式——法师原声与 AI 轨、普通播放与跟读共用同一
 * 判定，跟读只是介入顺序不同，去向矩阵一致）：
 *   循环开 × 有/无下一本 → RESTART（计数在调用方已完成，随后从头重播）
 *   循环关 × 有可接下一本 → ADVANCE；循环关 × 无可接 → STOP
 * 运行：javac -d <out> -sourcepath app/src/main/java tools/completion_harness/CompletionHarness.java
 */
public class CompletionHarness {

    static int passed = 0, failed = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    public static void main(String[] args) {
        // 循环开：无论后面有没有下一本，都回本本开头（用户实报场景的核心断言）
        check("loop on, has next -> RESTART",
                CompletionPolicy.decide(true, true) == CompletionPolicy.Action.RESTART);
        check("loop on, no next -> RESTART",
                CompletionPolicy.decide(true, false) == CompletionPolicy.Action.RESTART);
        // 循环关：有可接的下一本才接；没有（末尾/本需先选声）就停
        check("loop off, has next -> ADVANCE",
                CompletionPolicy.decide(false, true) == CompletionPolicy.Action.ADVANCE);
        check("loop off, no next -> STOP",
                CompletionPolicy.decide(false, false) == CompletionPolicy.Action.STOP);

        System.out.println("CompletionHarness: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
