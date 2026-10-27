package com.ti.songdu.recite;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 识读模型下载 UI（设置页/阅读页共用）。
 *
 * 界面口径（定死）：只出现「基础版 AI」「高级版 AI」与大小，绝不出现真实
 * 模型名（真名只许在 ⓘ 说明与帮助页）。选项按缺失情况生成：
 *  - 只下载基础版 AI（约 25 MB）
 *  - 基础版＋高级版一起下载（约 264 MB）
 *  - 基础版已在、只缺高级版时：下载高级版 AI（约 239 MB）
 *
 * v1.20.1 重做（真机失败教训）：旧版用 setMessage＋setItems 拼选项，在用户
 * 机上选项没有呈现出来、只剩标题与「取消」，下载根本无从点起。现改自定义
 * 视图单对话框：选项是真实按钮；点选后同一对话框切到下载态——确定型进度
 * 条（已下载字节/总字节＋百分比）、可取消；失败切到失败态（原因＋重试＋
 * 关闭），不再是闪一下就没的 Toast，也不再用不可取消的 ProgressDialog。
 * 已有下载在跑时打开本对话框，直接挂到那次任务的进度上。
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
     * 引导下载主引擎所需模型：已就绪直接回调；否则弹下载对话框，完成后回调。
     */
    public static void ensureForEngine(Activity act, boolean needAdvanced, Runnable onReady) {
        boolean ready = needAdvanced ? AsrModelStore.isSenseVoiceReady(act)
                : AsrModelStore.isReady(act);
        if (ready) {
            onReady.run();
            return;
        }
        showChoice(act, needAdvanced, onReady);
    }

    public static void showChoice(Activity act, boolean needAdvanced, Runnable onReady) {
        if (AsrModelStore.isDownloading()) {
            new DownloadDialog(act, null, onReady).showProgressState();
            return;
        }
        List<Opt> opts = buildOptions(act, needAdvanced);
        if (opts == null) {
            if (onReady != null) onReady.run();
            return;
        }
        new DownloadDialog(act, opts, onReady).showChoiceState();
    }

    /** 兼容旧调用名（v1.18/1.19 的 download 入口已并入对话框流程） */
    public static void download(Activity act, boolean wantBasic, boolean wantSv, Runnable onDone) {
        List<Opt> one = new ArrayList<>();
        one.add(new Opt("", wantBasic, wantSv));
        new DownloadDialog(act, one, onDone).startDownload(one.get(0));
    }

    // ---------- 选项生成（与 v1.18.0 同语义） ----------

    private static final class Opt {
        final String label;
        final boolean basic, sv;

        Opt(String label, boolean basic, boolean sv) {
            this.label = label;
            this.basic = basic;
            this.sv = sv;
        }
    }

    /** 返回 null＝所需模型已全部就绪 */
    private static List<Opt> buildOptions(Activity act, boolean needAdvanced) {
        boolean basicReady = AsrModelStore.isReady(act);
        boolean svReady = AsrModelStore.isSenseVoiceReady(act);
        List<Opt> opts = new ArrayList<>();
        if (!basicReady && !needAdvanced) {
            opts.add(new Opt("只下载" + RecitePrefs.LABEL_BASIC + "（" + RecitePrefs.SIZE_BASIC + "）",
                    true, false));
            opts.add(new Opt(RecitePrefs.LABEL_BASIC + "＋" + RecitePrefs.LABEL_ADVANCED
                    + "一起下载（约 264 MB）", true, true));
        } else if (needAdvanced && !svReady) {
            if (!basicReady) {
                opts.add(new Opt(RecitePrefs.LABEL_BASIC + "＋" + RecitePrefs.LABEL_ADVANCED
                        + "一起下载（约 264 MB）", true, true));
                opts.add(new Opt("只下载" + RecitePrefs.LABEL_ADVANCED
                        + "（" + RecitePrefs.SIZE_ADVANCED + "）", false, true));
            } else {
                opts.add(new Opt("下载" + RecitePrefs.LABEL_ADVANCED
                        + "（" + RecitePrefs.SIZE_ADVANCED + "）", false, true));
            }
        } else {
            return null;
        }
        return opts;
    }

    private static String fmtBytes(long bytes) {
        double mb = bytes / 1_000_000.0;
        if (mb >= 100) return String.format(Locale.US, "%.0f MB", mb);
        return String.format(Locale.US, "%.1f MB", mb);
    }

    // ---------- 单对话框：选项 → 进度 → 失败/完成 ----------

    private static final class DownloadDialog {
        private final Activity act;
        private final List<Opt> opts; // attach 模式为 null
        private final Runnable onReady;
        private AlertDialog dialog;
        private LinearLayout optionsBox, progressBox, failedBox;
        private ProgressBar bar;
        private TextView progressText, failedText;
        private Opt currentOpt;
        private AsrModelStore.DlState watched;
        private boolean finished;
        private final AsrModelStore.StateListener listener;

        DownloadDialog(Activity act, List<Opt> opts, Runnable onReady) {
            this.act = act;
            this.opts = opts;
            this.onReady = onReady;
            this.listener = s -> {
                if (watched == null) {
                    if (s != AsrModelStore.active) return; // 已被新任务取代的残留状态
                    watched = s;
                }
                if (s != watched) return;
                act.runOnUiThread(() -> onState(s));
            };
        }

        private int dp(int v) {
            return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
        }

        private void build() {
            LinearLayout root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(24), dp(8), dp(24), dp(8));

            TextView msg = new TextView(act);
            msg.setText("只下一次，之后断网也能用。");
            msg.setTextSize(14);
            root.addView(msg);

            optionsBox = new LinearLayout(act);
            optionsBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams obLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            obLp.topMargin = dp(12);
            optionsBox.setLayoutParams(obLp);
            if (opts != null) {
                for (Opt o : opts) {
                    Button b = new Button(act);
                    b.setText(o.label);
                    b.setAllCaps(false);
                    b.setTextSize(15);
                    b.setOnClickListener(v -> startDownload(o));
                    optionsBox.addView(b);
                }
                Button cancelChoice = new Button(act);
                cancelChoice.setText("取消");
                cancelChoice.setAllCaps(false);
                cancelChoice.setOnClickListener(v -> close());
                optionsBox.addView(cancelChoice);
            }
            root.addView(optionsBox);

            progressBox = new LinearLayout(act);
            progressBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pbLp.topMargin = dp(16);
            progressBox.setLayoutParams(pbLp);
            bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            progressBox.addView(bar);
            progressText = new TextView(act);
            progressText.setTextSize(13);
            progressText.setPadding(0, dp(8), 0, 0);
            progressBox.addView(progressText);
            Button cancel = new Button(act);
            cancel.setText("取消下载");
            cancel.setAllCaps(false);
            cancel.setOnClickListener(v -> {
                AsrModelStore.cancelActiveDownload();
                close();
            });
            progressBox.addView(cancel);
            progressBox.setVisibility(View.GONE);
            root.addView(progressBox);

            failedBox = new LinearLayout(act);
            failedBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams fbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fbLp.topMargin = dp(16);
            failedBox.setLayoutParams(fbLp);
            failedText = new TextView(act);
            failedText.setTextSize(14);
            failedBox.addView(failedText);
            TextView resumeHint = new TextView(act);
            resumeHint.setText("已下载的部分会保留，重试时接着下，不会从头再来。");
            resumeHint.setTextSize(12);
            resumeHint.setPadding(0, dp(6), 0, 0);
            failedBox.addView(resumeHint);
            LinearLayout btnRow = new LinearLayout(act);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            Button retry = new Button(act);
            retry.setText("重试");
            retry.setAllCaps(false);
            retry.setTypeface(null, Typeface.BOLD);
            retry.setOnClickListener(v -> {
                if (currentOpt != null) startDownload(currentOpt);
            });
            btnRow.addView(retry);
            Button closeBtn = new Button(act);
            closeBtn.setText("关闭");
            closeBtn.setAllCaps(false);
            closeBtn.setOnClickListener(v -> close());
            btnRow.addView(closeBtn);
            failedBox.addView(btnRow);
            failedBox.setVisibility(View.GONE);
            root.addView(failedBox);

            dialog = new AlertDialog.Builder(act)
                    .setTitle("识读需要下载识别模型")
                    .setView(root)
                    .create();
            dialog.setOnDismissListener(d -> AsrModelStore.removeStateListener(listener));
        }

        void showChoiceState() {
            build();
            dialog.show();
        }

        void showProgressState() {
            build();
            optionsBox.setVisibility(View.GONE);
            progressBox.setVisibility(View.VISIBLE);
            progressText.setText("正在下载…");
            watched = AsrModelStore.active;
            AsrModelStore.addStateListener(listener);
            dialog.show();
        }

        void startDownload(Opt o) {
            if (dialog == null) build();
            currentOpt = o;
            finished = false;
            optionsBox.setVisibility(View.GONE);
            failedBox.setVisibility(View.GONE);
            progressBox.setVisibility(View.VISIBLE);
            bar.setProgress(0);
            progressText.setText("正在下载…");
            if (!dialog.isShowing()) dialog.show();
            // 先启动任务（ensureSet 入口即把 active 换成本次 DlState），再注册
            // 监听：addStateListener 会立即回放当前 active，watched 就此锁定，
            // 不会误挂到上一次任务的残留状态上。
            watched = null;
            final android.content.Context appCtx = act.getApplicationContext();
            new Thread(() -> AsrModelStore.ensureSet(appCtx, o.basic, o.sv, null)).start();
            AsrModelStore.addStateListener(listener);
        }

        private void onState(AsrModelStore.DlState s) {
            if (finished) return;
            if (s.running) {
                int permille = s.total > 0 ? (int) (s.done * 1000 / s.total) : 0;
                bar.setProgress(permille);
                int pct = s.total > 0 ? (int) (s.done * 100 / s.total) : 0;
                progressText.setText("已下载 " + fmtBytes(s.done) + " / " + fmtBytes(s.total)
                        + " · " + pct + "%");
            } else if (s.cancelled) {
                finished = true;
                close();
            } else if (s.failed) {
                finished = true;
                progressBox.setVisibility(View.GONE);
                failedBox.setVisibility(View.VISIBLE);
                failedText.setText("下载失败：" + (s.error == null ? "未知错误" : s.error));
                AsrModelStore.removeStateListener(listener);
            } else {
                finished = true;
                AsrModelStore.removeStateListener(listener);
                close();
                if (onReady != null) onReady.run();
            }
        }

        private void close() {
            AsrModelStore.removeStateListener(listener);
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        }
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
