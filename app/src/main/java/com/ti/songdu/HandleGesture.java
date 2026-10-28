package com.ti.songdu;

/**
 * 播放条「进入阅读界面」把手的手势判定（v1.20.6；纯逻辑、无 Android 依赖，可桌面单测）。
 *
 * 把手只有两个有效手势，殊途同归到同一动作 OPEN_READER（打开当前播放音频的阅读页）：
 *   1. 点按；
 *   2. 向上轻扫（fling）：位移明显向上、带向上速度、且纵向主导。
 * 其余手势（下滑、横滑、慢拖）一律 NONE——把手不拦截它们，也没有别的功能分支。
 * （沿革：把手原为「上拉展开字幕」，字幕展开层早已取消；v1.20.6 文案改「进入阅读界面」，
 *   点按与上滑按用户批准方案并回这一入口。）
 */
public final class HandleGesture {

    public enum Action { NONE, OPEN_READER }

    /** 上滑位移门槛（像素，与触摸事件同单位） */
    static final float MIN_FLING_DISTANCE_PX = 48f;
    /** 上滑速度门槛（像素/秒，向上为负） */
    static final float MIN_FLING_VELOCITY_PX_S = 120f;

    private HandleGesture() {}

    /** 点按：打开阅读页 */
    public static Action onTap() {
        return Action.OPEN_READER;
    }

    /**
     * 轻扫判定。dx/dy 为起点到终点的位移（向上滑 dy 为负），
     * velocityX/velocityY 为系统报告的滑动速度（向上为负）。
     */
    public static Action onFling(float dx, float dy, float velocityX, float velocityY) {
        if (dy > -MIN_FLING_DISTANCE_PX) return Action.NONE;
        if (velocityY > -MIN_FLING_VELOCITY_PX_S) return Action.NONE;
        if (Math.abs(velocityY) <= Math.abs(velocityX)) return Action.NONE;
        return Action.OPEN_READER;
    }
}
