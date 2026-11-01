package com.ti.songdu;

import android.app.Activity;
import android.os.Bundle;

/**
 * v1.24.0 就绪通知的落点：点「新版已下载 · 点此安装」通知直达本页，
 * 只做一件事——把就绪件交给 AppUpdater.installReady（复用既有 install
 * 入口与 UpdateFileProvider 调系统安装，不另起一套）；装完/去授权后由
 * installReady 收尾 finish。透明主题，不露空白页。
 */
public class UpdateInstallActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppUpdater.installReadyFromNotification(this);
    }
}
