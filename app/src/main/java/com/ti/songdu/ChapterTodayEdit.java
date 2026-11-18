package com.ti.songdu;

/**
 * v1.30.0 篇行「今日 X 遍」编辑的纯逻辑（无 Android 依赖，桌面 harness 直驱）：
 * 直接输数的解析校验与步进钳制。写回仍走 ChapterStatsStore.adjustChapterToday
 * （以「目标值 − 今日现值」为增量、下限 0 同口径），本类只管「用户输入算不算数、
 * 步进落在哪」；非法输入一律返回 null 由调用方拦截不写。
 */
public final class ChapterTodayEdit {

    /** 解析直接输数：0 或正整数（int 范围内）返回其值；空/非数字/负数/超 int 上限返回 null */
    public static Integer parseInput(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        final long v;
        try {
            v = Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
        if (v < 0 || v > Integer.MAX_VALUE) return null;
        return (int) v;
    }

    /** 步进：cur + delta，下限 0、上限 Integer.MAX_VALUE */
    public static int stepped(int cur, int delta) {
        long v = (long) cur + delta;
        if (v < 0) return 0;
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) v;
    }

    private ChapterTodayEdit() {}
}
