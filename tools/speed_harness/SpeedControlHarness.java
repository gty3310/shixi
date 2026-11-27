import com.ti.songdu.SpeedControl;

/**
 * v1.33.0 自定义倍速纯逻辑桌面 harness：直接驱动 App 的 SpeedControl 本体
 * （-sourcepath 指向 App 源码，不另抄一份），覆盖状态表里可纯函数化的部分：
 * 滑杆范围/步进量化、整档微吸附、进度互转单调性、显示格式、预设判定。
 * 面板状态机（亮钮归属/自动收起）属 Android 视图层，不在本 harness 范围，
 * 靠代码逐条对照状态表自证（见 CHANGELOG 验收节）。
 * 运行：javac -d <out> -sourcepath app/src/main/java tools/speed_harness/SpeedControlHarness.java
 */
public class SpeedControlHarness {

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

    static boolean eq(float a, float b) {
        return Math.abs(a - b) < 0.001f;
    }

    public static void main(String[] args) {
        // 预设档：4.0× 已去、共 6 档（状态表 1 的预设排）
        check("预设 6 档", SpeedControl.PRESETS.length == 6);
        check("预设无 4.0", !SpeedControl.isPresetValue(4.0f));
        check("预设含 3.0", SpeedControl.isPresetValue(3.0f));
        check("1.35 非预设值", !SpeedControl.isPresetValue(1.35f));

        // 量化：0.05 网格
        check("quantize 1.32→1.30", eq(SpeedControl.quantize(1.32f), 1.30f));
        check("quantize 1.33→1.35", eq(SpeedControl.quantize(1.33f), 1.35f));
        check("quantize 1.35→1.35", eq(SpeedControl.quantize(1.35f), 1.35f));
        check("quantize 下限夹 0.5", eq(SpeedControl.quantize(0.4f), 0.5f));
        check("quantize 上限夹 3.0", eq(SpeedControl.quantize(3.2f), 3.0f));

        // 微吸附：半步内吸到整档、半步外不动
        check("snap 1.48→1.5", eq(SpeedControl.snap(1.48f), 1.5f));
        check("snap 1.52→1.5", eq(SpeedControl.snap(1.52f), 1.5f));
        check("snap 1.45 不动", eq(SpeedControl.snap(1.45f), 1.45f));
        check("snap 0.76→0.75", eq(SpeedControl.snap(0.76f), 0.75f));
        check("snap 2.98→3.0（经 fromSlider）", eq(SpeedControl.fromSlider(2.98f), 3.0f));

        // 进度互转：0..50 全程单调、步长恒 0.05、两端对齐
        boolean mono = true;
        float prev = SpeedControl.fromProgress(0);
        if (!eq(prev, 0.5f)) mono = false;
        for (int p = 1; p <= SpeedControl.SLIDER_STEPS; p++) {
            float v = SpeedControl.fromProgress(p);
            if (!eq(v - prev, 0.05f)) mono = false;
            prev = v;
        }
        check("进度 0..50 单调步长 0.05、末值 3.0", mono && eq(prev, 3.0f));
        check("toProgress(1.35)=17", SpeedControl.toProgress(1.35f) == 17);
        check("toProgress(0.5)=0", SpeedControl.toProgress(0.5f) == 0);
        check("toProgress(3.0)=50", SpeedControl.toProgress(3.0f) == 50);
        boolean roundTrip = true;
        for (int p = 0; p <= SpeedControl.SLIDER_STEPS; p++) {
            if (SpeedControl.toProgress(SpeedControl.fromProgress(p)) != p) roundTrip = false;
        }
        check("进度↔速度全程往返一致", roundTrip);
        check("fromProgress 越界夹紧", eq(SpeedControl.fromProgress(-3), 0.5f)
                && eq(SpeedControl.fromProgress(99), 3.0f));

        // 显示格式：去尾零、至少一位小数
        check("format 1.35", "1.35×".equals(SpeedControl.format(1.35f)));
        check("format 1.5", "1.5×".equals(SpeedControl.format(1.5f)));
        check("format 2.0", "2.0×".equals(SpeedControl.format(2.0f)));
        check("format 0.75", "0.75×".equals(SpeedControl.format(0.75f)));
        check("format 3.0", "3.0×".equals(SpeedControl.format(3.0f)));
        check("format 1.10→1.1", "1.1×".equals(SpeedControl.format(1.10f)));
        check("labelOf 预设 1.25", "1.25×".equals(SpeedControl.labelOf(1.25f)));
        check("labelOf 自定义 1.35", "1.35×".equals(SpeedControl.labelOf(1.35f)));

        System.out.println("speed control harness: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
