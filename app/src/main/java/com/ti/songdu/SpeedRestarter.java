package com.ti.songdu;

/**
 * 改速重启编排（v1.20.2，用户真机发现定案；纯逻辑、无 Android 依赖，可桌面单测）。
 *
 * 病灶：MediaPlayer 播放途中直接 setPlaybackParams 改速，声音出现毛刺；
 * 暂停一下再以同样速度重播则干干净净——扳机是「播放中途改参」，素材高频
 * 丰富只是让毛刺更明显。故用户改速时 App 自动替他完成这套动作：
 * 记精确位置 → 暂停 → 应用新速度 → seek 回原位 → 重播，用户无感知。
 *
 * 暂停/未就绪时不走重启：只记参数即可——各起播入口在 start 前本就会
 * 重新 applySpeed，起播即定速、天然干净。
 *
 * 状态隔离：内部重启不是用户的暂停/起播。PlayerService 落地时全程不翻转
 * playing 字段、不走 pausePlayer/resume 的语义包装；本类以 isRestarting()
 * 标记重启期间，ReaderActivity 的 FollowGate 边沿检测凭此跳过判定，
 * 绝不让内部重启误入跟随模式，跟随当前句锚点与暂停语义均不受扰动。
 */
public final class SpeedRestarter {

    /** 播放器原语落地口：由 PlayerService 实现，只许碰播放器本身，不带任何上层语义 */
    public interface Ops {
        boolean isPrepared();

        boolean isPlaying();

        long positionMs();

        void pauseRaw();

        void applyParams();

        void seekBackTo(long posMs);

        void startRaw();
    }

    private boolean restarting;

    /** 内部重启进行中（供 FollowGate 边沿检测隔离） */
    public boolean isRestarting() {
        return restarting;
    }

    /**
     * 用户改速时调用（调用方须先把新速度存好，applyParams 按已存值生效）。
     *
     * @return true＝已走内部重启；false＝未在播放，只记参数、起播时生效
     */
    public boolean onSpeedChanged(Ops ops) {
        if (!ops.isPrepared() || !ops.isPlaying()) return false;
        restarting = true;
        try {
            long pos = ops.positionMs();
            ops.pauseRaw();
            ops.applyParams();
            ops.seekBackTo(pos);
            ops.startRaw();
        } finally {
            restarting = false;
        }
        return true;
    }
}
