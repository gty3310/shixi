package com.ti.songdu;

/**
 * 阅读跟随状态机（v1.19.0，用户 2026-10-27 亲口定稿；纯逻辑、无 Android 依赖，可桌面单测）。
 *
 * 进入跟随只有两种办法：
 *   1. 用户点击「跟随」按钮（暂停、播放时都可点）；
 *   2. 播放器由暂停变为播放（点播放键即自动进入跟随）。
 * 除此之外任何时机（切句、进阅读页、状态恢复等）都不许自动进入跟随。
 *
 * 退出跟随只有一种办法：用户手滑动屏幕——立刻脱离，暂停、播放皆然。
 *
 * 行为推论：
 *   - 暂停＋不跟随：阅读器绝不自动跳转（治「暂停后自己读、页面不断跳回暂停处」）；
 *   - 播放＋不跟随：播放与句进度照常推进，视图不动；
 *   - 暂停＋跟随：视图停在暂停句；播放＋跟随：视图随当前句推进。
 *
 * 本类只管「跟随与否」这一位状态；何时滚动、滚到哪里由 ReaderActivity 决定。
 * 跟读（PlayerService.followEnabled）与识读模式不走本类，口径不变。
 */
public final class FollowGate {

    private boolean following;

    public boolean isFollowing() {
        return following;
    }

    /** 入口一：用户点「跟随」按钮 */
    public void onFollowButtonTap() {
        following = true;
    }

    /** 入口二：播放器由暂停变为播放 */
    public void onPlayStarted() {
        following = true;
    }

    /** 唯一出口：用户手滑动屏幕 */
    public void onUserSwipe() {
        following = false;
    }
}
