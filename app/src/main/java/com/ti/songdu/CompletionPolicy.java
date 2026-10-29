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

    private CompletionPolicy() {}
}
