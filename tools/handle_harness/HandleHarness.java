import com.ti.songdu.HandleGesture;

/**
 * v1.20.6 播放条把手手势判定桌面 harness：直接驱动 App 的 HandleGesture 本体。
 * 断言口径：点按与上滑都映射到 OPEN_READER；下滑、横滑、慢拖、速度不足一律 NONE。
 * 运行：javac -d <out> -sourcepath app/src/main/java tools/handle_harness/HandleHarness.java
 */
public class HandleHarness {

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
        // 点按 → 打开阅读页
        check("tap -> OPEN_READER",
                HandleGesture.onTap() == HandleGesture.Action.OPEN_READER);

        // 典型上滑：位移 -120、纵向速度 -600、无横向分量
        check("swipe up -> OPEN_READER",
                HandleGesture.onFling(0f, -120f, 0f, -600f) == HandleGesture.Action.OPEN_READER);
        // 带一点横向偏移的上滑（纵向仍主导）
        check("swipe up with slight dx -> OPEN_READER",
                HandleGesture.onFling(30f, -150f, 120f, -700f) == HandleGesture.Action.OPEN_READER);

        // 下滑 → 不触发
        check("swipe down -> NONE",
                HandleGesture.onFling(0f, 120f, 0f, 600f) == HandleGesture.Action.NONE);
        // 横滑（横向主导）→ 不触发
        check("horizontal fling -> NONE",
                HandleGesture.onFling(200f, -60f, 800f, -300f) == HandleGesture.Action.NONE);
        // 位移太短 → 不触发
        check("short upward flick -> NONE",
                HandleGesture.onFling(0f, -20f, 0f, -600f) == HandleGesture.Action.NONE);
        // 慢拖上移（速度不足）→ 不触发
        check("slow upward drag -> NONE",
                HandleGesture.onFling(0f, -200f, 0f, -50f) == HandleGesture.Action.NONE);
        // 无速度 → 不触发
        check("zero velocity -> NONE",
                HandleGesture.onFling(0f, -200f, 0f, 0f) == HandleGesture.Action.NONE);

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
