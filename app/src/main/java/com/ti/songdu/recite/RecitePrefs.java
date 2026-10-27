package com.ti.songdu.recite;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * v1.18.0 识读偏好：主引擎选择（基础版 AI／高级版 AI）。
 * 界面口径：只许用这两个名字，真实模型名只出现在说明弹窗与帮助页。
 */
public final class RecitePrefs {

    public static final String ENGINE_BASIC = "basic";
    public static final String ENGINE_ADVANCED = "advanced";
    public static final String LABEL_BASIC = "基础版 AI";
    public static final String LABEL_ADVANCED = "高级版 AI";
    public static final String SIZE_BASIC = "约 25 MB";
    public static final String SIZE_ADVANCED = "约 239 MB";

    private static final String PREFS = "recite_prefs";
    private static final String KEY_ENGINE = "engine";

    private RecitePrefs() {}

    public static String engine(Context ctx) {
        return prefs(ctx).getString(KEY_ENGINE, ENGINE_BASIC);
    }

    public static void setEngine(Context ctx, String engine) {
        prefs(ctx).edit().putString(KEY_ENGINE, engine).apply();
    }

    public static boolean isAdvanced(Context ctx) {
        return ENGINE_ADVANCED.equals(engine(ctx));
    }

    /** 当前主引擎所选模型是否就绪 */
    public static boolean primaryReady(Context ctx) {
        return isAdvanced(ctx) ? AsrModelStore.isSenseVoiceReady(ctx)
                : AsrModelStore.isReady(ctx);
    }

    /** 所选引擎的展示名＋大小（界面唯一口径） */
    public static String primaryLabel(Context ctx) {
        return isAdvanced(ctx) ? LABEL_ADVANCED + "（" + SIZE_ADVANCED + "）"
                : LABEL_BASIC + "（" + SIZE_BASIC + "）";
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
