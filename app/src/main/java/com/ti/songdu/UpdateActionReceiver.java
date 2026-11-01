package com.ti.songdu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * v1.24.0 更新通知动作接收：目前只有下载进度通知的「取消」一钮，
 * 置位 AppUpdater 里的后台下载取消标志（下载线程据此停手删残件）。
 */
public class UpdateActionReceiver extends BroadcastReceiver {

    public static final String ACTION_CANCEL_DOWNLOAD = "com.ti.songdu.UPDATE_CANCEL_DOWNLOAD";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_CANCEL_DOWNLOAD.equals(intent.getAction())) {
            AppUpdater.cancelBackgroundDownload();
        }
    }
}
