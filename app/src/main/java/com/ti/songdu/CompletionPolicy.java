package com.ti.songdu;

/**
 * 播完一遍后的去向判定（v1.20.7，用户真机实报「循环开着、一本读完就停」后抽出）。
 * 纯逻辑、无 Android 依赖，桌面 harness 直驱本类验收。
 *
 * 背景：播完处理原先在 PlayerService.onCompletion 与跟读的 finishFollowPass 里
 * 各写一份 if/else，且主界面播放条上的「单曲循环 开」是写死文字、从不读真开关
 * （真源 reader_prefs/loop 只在阅读页/设置页可见可改）——开关一旦在别处关掉，
 * 主界面照样显示「正在循环播放」，播完却按关的口径接下一本或停下，用户无从
 * 察觉。本类把「播完去哪」收成一处判定：计数由调用方在判定前统一完成
 * （口径不变、不与循环互相牺牲），本判定只管去向。
 *
 * hasNextPlayable 的口径在 PlayerService.nextPlayableTrack：列表顺序的下一曲，
 * 且不是「未选声」的双声音书——那种书 playTrack 会先拉选声页并不出声，
 * 自动接播撞上它＝静默停播（息屏时选声页还会被系统拦下），故不算可接。
 */
public final class CompletionPolicy {

    public enum Action {
        /** 循环开：计数后从头重播同一本 */
        RESTART,
        /** 循环关且有可接的下一本：计数后自动接播 */
        ADVANCE,
        /** 循环关且无可接的下一本：计数后停在结尾 */
        STOP
    }

    public static Action decide(boolean loopEnabled, boolean hasNextPlayable) {
        if (loopEnabled) return Action.RESTART;
        if (hasNextPlayable) return Action.ADVANCE;
        return Action.STOP;
    }

    // ---------- v1.21.0 章界去向（循环范围口径） ----------

    public enum ChapterAction {
        /** 到达章末：计数后回本章章首续播（单章循环） */
        WRAP_CHAPTER,
        /** 章级播放且范围为整书：计数后停在本章章尾，不接整本 */
        STOP_CHAPTER,
        /** 普通整本播放：计数后照常继续向后播 */
        CONTINUE
    }

    /**
     * 自然播到当前章末（章计数已由调用方完成）后的去向：
     *  - 章级播放：范围=单章且循环开 -> 回绕；范围=整书 -> 停在章尾
     *  - 整本播放：范围=单章且循环开 -> 回绕本章；否则继续向后
     *  - 循环关时范围不生效（章级播放仍按其定义停在章尾）
     */
    public static ChapterAction chapterBoundary(boolean loopEnabled,
                                                boolean loopScopeChapter,
                                                boolean chapterPlayback) {
        if (chapterPlayback) {
            return (loopEnabled && loopScopeChapter)
                    ? ChapterAction.WRAP_CHAPTER : ChapterAction.STOP_CHAPTER;
        }
        if (loopEnabled && loopScopeChapter) return ChapterAction.WRAP_CHAPTER;
        return ChapterAction.CONTINUE;
    }

    private CompletionPolicy() {}
}
