package com.ti.songdu;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.21.0 章界自动计数判定（纯逻辑、无 Android 依赖，桌面 harness 直驱本体）。
 * 口径（设计方案三之补 + 任务书第 6 块）：播放位置自然越过某章 endMs 即该章 +1。
 * 防误计基线：进入某章时须曾处于「章界 - 小阈值」之前（armed），才认随后越界；
 * 把进度拖到章尾附近再播完，进入时位置已过基线、未 armed，不计。
 * 不查倍速、不查中途快进；整本连播逐章计、单章循环每满一遍计（回绕后重新进入即重新 armed）。
 * 阈值：min(2000ms, max(500ms, 章长/10))。
 */
public final class ChapterProgressTracker {

    private long[] starts = new long[0];
    private long[] ends = new long[0];
    private int cur = -1;
    private boolean armed = false;
    private long lastPos = -1;

    public static long thresholdFor(long startMs, long endMs) {
        long len = Math.max(0, endMs - startMs);
        return Math.min(2000L, Math.max(500L, len / 10));
    }

    public void setChapters(long[] startMs, long[] endMs) {
        starts = startMs != null ? startMs : new long[0];
        ends = endMs != null ? endMs : new long[0];
        reset();
    }

    public void reset() {
        cur = -1; armed = false; lastPos = -1;
    }

    /** 换章/回绕后由调用方重置到某章起点：该章按已 armed 起步（正常从章首播） */
    public void resetToChapter(int chapterIndex, long posMs) {
        cur = chapterIndex; lastPos = posMs;
        armed = chapterIndex >= 0 && chapterIndex < ends.length
                && posMs <= ends[chapterIndex] - thresholdFor(starts[chapterIndex], ends[chapterIndex]);
    }

    private int chapterFor(long pos) {
        for (int i = 0; i < ends.length; i++) if (pos < ends[i]) return i;
        return ends.length - 1;
    }

    /**
     * 喂入一个位置样本，返回本次应计数的章下标列表（通常为空）。
     * playing=false 时只同步位置、不判定（暂停/未就绪不产生计数）。
     */
    public List<Integer> feed(long posMs, boolean playing) {
        List<Integer> out = new ArrayList<>();
        if (ends.length == 0) return out;
        if (!playing) { lastPos = posMs; return out; }
        if (cur < 0) {
            cur = chapterFor(posMs);
            armed = posMs <= ends[cur] - thresholdFor(starts[cur], ends[cur]);
            lastPos = posMs;
            return out;
        }
        // 越界判定：上一位置在本章界内、当前位置越过章界，且本章已 armed
        if (armed && lastPos >= 0 && lastPos < ends[cur] && posMs >= ends[cur]) {
            out.add(cur);
            if (cur + 1 < ends.length) {
                cur = cur + 1;
                armed = posMs <= ends[cur] - thresholdFor(starts[cur], ends[cur]);
            } else {
                armed = false; // 末章已计，停在章界外不再重复计
            }
            lastPos = posMs;
            return out;
        }
        int c = chapterFor(posMs);
        if (c != cur) {
            // seek 或回绕进入另一章：按进入位置定 armed
            cur = c;
            armed = posMs <= ends[cur] - thresholdFor(starts[cur], ends[cur]);
        } else if (posMs <= ends[cur] - thresholdFor(starts[cur], ends[cur])) {
            armed = true; // 在本章内回到基线之前（重读）重新 armed
        }
        lastPos = posMs;
        return out;
    }

    /** 整轨自然播完时兜底：当前章 armed 且位置已进入本章，则该章计一遍（返回章下标或 -1） */
    public int flushAtTrackEnd() {
        if (cur >= 0 && armed && lastPos >= 0 && lastPos < ends[cur]) {
            int c = cur;
            armed = false;
            return c;
        }
        return -1;
    }

    public int currentChapter() { return cur; }
    public boolean isArmed() { return armed; }
}
