package com.ti.songdu;

import java.util.Locale;

/**
 * 倍速口径收口（v1.33.0）：预设档、自定义滑杆的范围/步进、量化、整档微吸附、
 * 显示格式——主页与阅读页共用这一份。旧实现两处各存一份 SPEEDS 常量
 * （MainActivity 与 ReaderActivity），口径靠人工同步，现收口为单一出处。
 * 纯逻辑、无 Android 依赖，可桌面单测（tools/speed_harness）。
 */
public final class SpeedControl {

    /** 预设档（v1.33.0 起去掉 4.0×，末位换「自定义」钮，见 SpeedPanelView） */
    public static final float[] PRESETS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f};
    public static final String[] PRESET_LABELS = {"0.75×", "1.0×", "1.25×", "1.5×", "2.0×", "3.0×"};

    /** 自定义滑杆范围与步进（用户 2026-11-26 冻结：0.5–3.0×、步进 0.05） */
    public static final float MIN = 0.5f;
    public static final float MAX = 3.0f;
    public static final float STEP = 0.05f;
    /** 滑杆格数：(3.0−0.5)/0.05＝50 */
    public static final int SLIDER_STEPS = 50;

    private SpeedControl() {
    }

    public static float clamp(float v) {
        return Math.max(MIN, Math.min(MAX, v));
    }

    /** 量化到 0.05 网格并夹紧范围 */
    public static float quantize(float v) {
        return clamp(Math.round(clamp(v) / STEP) * STEP);
    }

    /** 整档微吸附：落在某预设档半步（0.025）内即吸到该档 */
    public static float snap(float v) {
        for (float p : PRESETS) {
            if (Math.abs(v - p) <= STEP / 2f + 0.001f) return p;
        }
        return v;
    }

    /** 滑杆原始值 → 实际倍速：先量化、再微吸附（预设档都在网格上，吸附保证停得准） */
    public static float fromSlider(float raw) {
        return snap(quantize(raw));
    }

    public static int toProgress(float v) {
        return Math.round((quantize(v) - MIN) / STEP);
    }

    public static float fromProgress(int progress) {
        int p = Math.max(0, Math.min(SLIDER_STEPS, progress));
        return fromSlider(MIN + p * STEP);
    }

    public static boolean isPresetValue(float v) {
        for (float p : PRESETS) {
            if (Math.abs(v - p) < 0.01f) return true;
        }
        return false;
    }

    /** 显示格式：两位小数去尾零、至少留一位小数（1.35× / 1.5× / 2.0×） */
    public static String format(float v) {
        String s = String.format(Locale.US, "%.2f", v);
        while (s.endsWith("0") && !s.endsWith(".0")) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "×";
    }

    /** 钮面文案：预设值用预设标签，其余用 format */
    public static String labelOf(float v) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (Math.abs(v - PRESETS[i]) < 0.01f) return PRESET_LABELS[i];
        }
        return format(v);
    }
}
