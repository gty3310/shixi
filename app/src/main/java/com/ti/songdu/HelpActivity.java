package com.ti.songdu;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;

/**
 * v1.8.2 内置帮助页：只含一个 WebView，打开 assets/help.html。
 * EXTRA_ANCHOR 可选（如 "add"）：定位到对应小节（help.html#add）。
 */
public class HelpActivity extends Activity {

    public static final String EXTRA_ANCHOR = "anchor";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(false);
        wv.getSettings().setDefaultTextEncodingName("utf-8");
        String url = "file:///android_asset/help.html";
        String anchor = getIntent() != null ? getIntent().getStringExtra(EXTRA_ANCHOR) : null;
        if (anchor != null && !anchor.isEmpty()) url += "#" + anchor;
        wv.loadUrl(url);
        setContentView(wv);
        setTitle("帮助");
    }
}
