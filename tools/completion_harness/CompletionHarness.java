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

        // v1.26.0 loopTarget 全组合：单章范围恒回本曲；整书范围有下一曲接下一曲、
        // 书末多曲目回书首、书末单曲目回本曲；无书时调用方传 (false, false) 同单曲目口径
        check("loopTarget chapter scope, has next, multi -> RESTART_SELF",
                CompletionPolicy.loopTarget(true, true, true) == CompletionPolicy.LoopTarget.RESTART_SELF);
        check("loopTarget chapter scope, has next, single -> RESTART_SELF",
                CompletionPolicy.loopTarget(true, true, false) == CompletionPolicy.LoopTarget.RESTART_SELF);
        check("loopTarget chapter scope, book end, multi -> RESTART_SELF",
                CompletionPolicy.loopTarget(true, false, true) == CompletionPolicy.LoopTarget.RESTART_SELF);
        check("loopTarget chapter scope, book end, single -> RESTART_SELF",
                CompletionPolicy.loopTarget(true, false, false) == CompletionPolicy.LoopTarget.RESTART_SELF);
        check("loopTarget book scope, has next, multi -> NEXT_IN_BOOK",
                CompletionPolicy.loopTarget(false, true, true) == CompletionPolicy.LoopTarget.NEXT_IN_BOOK);
        check("loopTarget book scope, has next, single-flag -> NEXT_IN_BOOK",
                CompletionPolicy.loopTarget(false, true, false) == CompletionPolicy.LoopTarget.NEXT_IN_BOOK);
        check("loopTarget book scope, book end, multi -> FIRST_OF_BOOK",
                CompletionPolicy.loopTarget(false, false, true) == CompletionPolicy.LoopTarget.FIRST_OF_BOOK);
        check("loopTarget book scope, book end, single -> RESTART_SELF",
                CompletionPolicy.loopTarget(false, false, false) == CompletionPolicy.LoopTarget.RESTART_SELF);
        check("loopTarget book scope, no book (hasNext=false, multi=false) -> RESTART_SELF",
                CompletionPolicy.loopTarget(false, false, false) == CompletionPolicy.LoopTarget.RESTART_SELF);

        System.out.println("CompletionHarness: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
