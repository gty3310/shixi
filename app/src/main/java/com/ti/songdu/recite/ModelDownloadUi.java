package com.ti.songdu.recite;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.widget.Toast;

/**
 * v1.18.0 识读模型下载二选一（设置页/阅读页共用）。
 *
 * 界面口径（定死）：只出现「基础版 AI」「高级版 AI」与大小，绝不出现真实
 * 模型名。选项按缺失情况生成：
 *  - 只下载基础版 AI（约 25 MB）
 *  - 基础版＋高级版一起下载（约 264 MB）
 *  - 基础版已在、只缺高级版时：下载高级版 AI（约 239 MB）
 * 进度/失败沿用 AsrModelStore 的重试与校验健壮性。
 */
public final class ModelDownloadUi {

    private ModelDownloadUi() {}

    /** true=所选集合已全部就绪（无需弹窗） */
    public static boolean allReadyForChoice(Activity act, boolean wantBasic, boolean wantSv) {
        if (wantBasic && !AsrModelStore.isReady(act)) return false;
        if (wantSv && !AsrModelStore.isSenseVoiceReady(act)) return false;
        return true;
    }

    /**
     * 引导下载主引擎所需模型：已就绪直接回调；否则弹二选一，下载完成后回调。
     * needAdvanced=true 时集合必须含高级版；基础版缺失时一并提供补下选项。
     */
    public static void ensureForEngine(Activity act, boolean needAdvanced, Runnable onReady) {
        boolean basicReady = AsrModelStore.isReady(act);
        boolean svReady = AsrModelStore.isSenseVoiceReady(act);
        if (needAdvanced && svReady) { onReady.run(); return; }
        if (!needAdvanced && basicReady) { onReady.run(); return; }
        showChoice(act, needAdvanced, onReady);
    }

    public static void showChoice(Activity act, boolean needAdvanced, Runnable onReady) {
        boolean basicReady = AsrModelStore.isReady(act);
        boolean svReady = AsrModelStore.isSenseVoiceReady(act);
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<boolean[]> sets = new java.util.ArrayList<>();
        if (!basicReady && !needAdvanced) {
            labels.add("只下载" + RecitePrefs.LABEL_BASIC + "（" + RecitePrefs.SIZE_BASIC + "）");
            sets.add(new boolean[]{true, false});
            labels.add(RecitePrefs.LABEL_BASIC + "＋" + RecitePrefs.LABEL_ADVANCED
                    + "一起下载（约 264 MB）");
            sets.add(new boolean[]{true, true});
        } else if (needAdvanced && !svReady) {
            if (!basicReady) {
                labels.add(RecitePrefs.LABEL_BASIC + "＋" + RecitePrefs.LABEL_ADVANCED
                        + "一起下载（约 264 MB）");
                sets.add(new boolean[]{true, true});
                labels.add("只下载" + RecitePrefs.LABEL_ADVANCED + "（" + RecitePrefs.SIZE_ADVANCED + "）");
                sets.add(new boolean[]{false, true});
            } else {
                labels.add("下载" + RecitePrefs.LABEL_ADVANCED + "（" + RecitePrefs.SIZE_ADVANCED + "）");
                sets.add(new boolean[]{false, true});
            }
        } else {
            if (onReady != null) onReady.run();
            return;
        }
        String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(act)
                .setTitle("识读需要下载识别模型")
                .setMessage("只下一次，之后断网也能用。")
                .setItems(items, (d, which) -> {
                    boolean[] s = sets.get(which);
                    download(act, s[0], s[1], onReady);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public static void download(Activity act, boolean wantBasic, boolean wantSv, Runnable onDone) {
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setTitle("正在下载识别模型");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> AsrModelStore.ensureSet(act, wantBasic, wantSv, new AsrModelStore.Callback() {
            @Override public void onProgress(long done, long total) {
                act.runOnUiThread(() -> pd.setProgress(total > 0 ? (int) (done * 100 / total) : 0));
            }
            @Override public void onDone() {
                act.runOnUiThread(() -> {
                    pd.dismiss();
                    if (onDone != null) onDone.run();
                });
            }
            @Override public void onError(String message) {
                act.runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(act, "模型下载失败：" + message, Toast.LENGTH_LONG).show();
                });
            }
        })).start();
    }

    /** ⓘ 说明弹窗：唯一允许在设置区出现真实模型名的说明处之一（与帮助页） */
    public static void showEngineInfo(Activity act) {
        new AlertDialog.Builder(act)
                .setTitle("两种识别引擎")
                .setMessage("基础版 AI（约 25 MB）：流式小模型（zipformer），边读边跟、"
                        + "跟随快，但听辨成色糙、容易听错常用字。\n\n"
                        + "高级版 AI（约 239 MB）：SenseVoice 大模型，离线分块识别，"
                        + "读完一小段再判，慢一点点，但准得多。\n\n"
                        + "两个都下载时，没在用的那个会在每句读完后悄悄复核："
                        + "两个引擎都报同一个字读错，这个字才更可信。")
                .setPositiveButton("知道了", null)
                .show();
    }
}
