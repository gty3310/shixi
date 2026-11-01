package com.ti.songdu;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用内更新（v1.22.0，Android 侧装配层）：取清单（先 Gitee latest release
 * API、失败换 GitHub latest release API）、更新框、下载进度（同框、可取消、
 * 主源失败自动换兜底源再试一次）、下载后 size＋sha256 校验、调起系统安装。
 * 判定逻辑全在纯逻辑类 UpdateLogic，本类只做网络/文件/界面装配。
 *
 * 红线：本类只读写自己的 update_prefs 与 cacheDir/updates/，绝不碰
 * plan.json / records.json / tracks.json / chapter_stats.json 等用户数据。
 * 偏好（update_prefs）：last_check_ms 上次检查时刻、ignored_code 忽略的
 * versionCode（仅自动弹框跳过，手动检查仍能查到）、wifi_only 仅 Wi-Fi
 * 下载（默认关）、auto_check 自动检查（默认开）。
 * v1.24.0：auto_download 自动下载（新键、缺省开）；自检/手动查到未被忽略
 * 的新版后经 UpdateLogic.decideAutoDownload 分流——自动开且网络约束允许
 * 则后台下载（低优先级可取消通知），验 size+sha256 过后写就绪键
 * （ready_code/ready_version_name/ready_path/ready_notes，另存 size/sha
 * 供复查），前台弹「新版已备好」、后台发通知点之即装；冷启动全校验复查
 * 就绪件，不过作废清键；就绪件低于远端新版时以下载更高版为准、新件验
 * 过后删旧件。自动关或仅-Wi-Fi 挡住时退回 v1.22.0 旧弹框流程，一字未动。
 */
public final class AppUpdater {

    private AppUpdater() {
    }

    public static final String PREFS = "update_prefs";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final String KEY_IGNORED = "ignored_code";
    private static final String KEY_WIFI_ONLY = "wifi_only";
    private static final String KEY_AUTO_CHECK = "auto_check";
    // v1.24.0 自动下载与就绪件
    private static final String KEY_AUTO_DOWNLOAD = "auto_download";
    private static final String KEY_READY_CODE = "ready_code";
    private static final String KEY_READY_NAME = "ready_version_name";
    private static final String KEY_READY_PATH = "ready_path";
    private static final String KEY_READY_NOTES = "ready_notes";
    private static final String KEY_READY_SIZE = "ready_size";
    private static final String KEY_READY_SHA = "ready_sha256";
    private static final String CH_DOWNLOAD = "xiange_update_download";
    private static final String CH_READY = "xiange_update_ready";
    private static final int NOTIF_DOWNLOAD_ID = 4201;
    private static final int NOTIF_READY_ID = 4202;

    private static final String GITEE_LATEST_API =
            "https://gitee.com/api/v5/repos/gty3310/xiange-releases/releases/latest";
    private static final String GITHUB_LATEST_API =
            "https://api.github.com/repos/gty3310/shixi/releases/latest";
    private static final String MANIFEST_ASSET = "update.json";

    // ---------- 偏好（SettingsActivity 共用） ----------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isWifiOnly(Context c) {
        return prefs(c).getBoolean(KEY_WIFI_ONLY, false);
    }

    public static void setWifiOnly(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_WIFI_ONLY, v).apply();
    }

    public static boolean isAutoCheck(Context c) {
        return prefs(c).getBoolean(KEY_AUTO_CHECK, true);
    }

    public static void setAutoCheck(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_AUTO_CHECK, v).apply();
    }

    /** v1.24.0「自动下载更新」开关：缺省视为开（新键默认 true） */
    public static boolean isAutoDownload(Context c) {
        return prefs(c).getBoolean(KEY_AUTO_DOWNLOAD, true);
    }

    public static void setAutoDownload(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_AUTO_DOWNLOAD, v).apply();
    }

    // ---------- v1.24.0 就绪件（已下载并验过、只差点一次安装） ----------

    /** 就绪件快照（update_prefs 里的记录；文件存活另行校验） */
    public static final class ReadyInfo {
        public int code;
        public String versionName = "";
        public String path = "";
        public String notes = "";
        public long size = -1;
        public String sha256 = "";
    }

    public static ReadyInfo getReadyInfo(Context c) {
        SharedPreferences sp = prefs(c);
        int code = sp.getInt(KEY_READY_CODE, 0);
        if (code <= 0) return null;
        ReadyInfo r = new ReadyInfo();
        r.code = code;
        r.versionName = sp.getString(KEY_READY_NAME, "");
        r.path = sp.getString(KEY_READY_PATH, "");
        r.notes = sp.getString(KEY_READY_NOTES, "");
        r.size = sp.getLong(KEY_READY_SIZE, -1);
        r.sha256 = sp.getString(KEY_READY_SHA, "");
        return r;
    }

    private static void saveReady(Context c, UpdateLogic.Manifest m, File apk) {
        ReadyInfo old = getReadyInfo(c);
        if (old != null && old.path != null && !old.path.isEmpty()
                && !old.path.equals(apk.getAbsolutePath())) {
            File oldFile = new File(old.path);
            if (oldFile.isFile()) oldFile.delete(); // 更高版验过落位后删旧件
        }
        prefs(c).edit()
                .putInt(KEY_READY_CODE, m.versionCode)
                .putString(KEY_READY_NAME, m.versionName == null ? "" : m.versionName)
                .putString(KEY_READY_PATH, apk.getAbsolutePath())
                .putString(KEY_READY_NOTES, m.notes == null ? "" : m.notes)
                .putLong(KEY_READY_SIZE, m.size)
                .putString(KEY_READY_SHA, m.sha256 == null ? "" : m.sha256)
                .apply();
    }

    private static void clearReady(Context c, boolean deleteFile) {
        ReadyInfo old = getReadyInfo(c);
        if (deleteFile && old != null && old.path != null && !old.path.isEmpty()) {
            File f = new File(old.path);
            if (f.isFile()) f.delete();
        }
        prefs(c).edit().remove(KEY_READY_CODE).remove(KEY_READY_NAME)
                .remove(KEY_READY_PATH).remove(KEY_READY_NOTES)
                .remove(KEY_READY_SIZE).remove(KEY_READY_SHA).apply();
    }

    /** 快速校验（主线程可用）：记录在、版本高于本地、文件在、大小对得上 */
    public static ReadyInfo validateReadyQuick(Context c) {
        ReadyInfo r = getReadyInfo(c);
        if (r == null) return null;
        if (r.code <= BuildInfo.VERSION_CODE) { // 已装上这个版本，就绪记录过期
            clearReady(c, true);
            return null;
        }
        File f = (r.path == null || r.path.isEmpty()) ? null : new File(r.path);
        if (f == null || !f.isFile() || (r.size > 0 && f.length() != r.size)) {
            clearReady(c, true);
            return null;
        }
        return r;
    }

    /** 全校验（只许后台线程调）：快速校验之上再核 sha256；不过就绪作废、删坏件 */
    private static ReadyInfo validateReadyFull(Context c) {
        ReadyInfo r = validateReadyQuick(c);
        if (r == null) return null;
        if (r.sha256 != null && !r.sha256.isEmpty()) {
            try (InputStream in = new BufferedInputStream(new FileInputStream(new File(r.path)))) {
                String sha = UpdateLogic.sha256Hex(in);
                if (!r.sha256.equalsIgnoreCase(sha)) {
                    clearReady(c, true);
                    return null;
                }
            } catch (Exception e) {
                clearReady(c, true);
                return null;
            }
        }
        return r;
    }

    // ---------- 前台活动追踪（决定就绪呈现走弹框还是通知） ----------

    private static volatile Activity resumedActivity;
    private static boolean lifecycleHooked;

    private static synchronized void hookLifecycle(Context c) {
        if (lifecycleHooked) return;
        lifecycleHooked = true;
        Context app = c.getApplicationContext();
        if (!(app instanceof Application)) return;
        ((Application) app).registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityResumed(Activity a) { resumedActivity = a; }
                    @Override public void onActivityPaused(Activity a) {
                        if (resumedActivity == a) resumedActivity = null;
                    }
                    @Override public void onActivityCreated(Activity a, Bundle b) { }
                    @Override public void onActivityStarted(Activity a) { }
                    @Override public void onActivityStopped(Activity a) { }
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                    @Override public void onActivityDestroyed(Activity a) {
                        if (resumedActivity == a) resumedActivity = null;
                    }
                });
    }

    // ---------- 检查入口 ----------

    /** 冷启动自检：24 小时节流、失败静默；先复查就绪件，再按决策矩阵分流 */
    public static void maybeAutoCheck(final Activity act) {
        hookLifecycle(act);
        final SharedPreferences sp = prefs(act);
        final long now = System.currentTimeMillis();
        final boolean due = UpdateLogic.shouldAutoCheck(sp.getLong(KEY_LAST_CHECK, 0), now,
                sp.getBoolean(KEY_AUTO_CHECK, true));
        final int ignored = sp.getInt(KEY_IGNORED, 0);
        if (due) sp.edit().putLong(KEY_LAST_CHECK, now).apply();
        new Thread(() -> {
            // 冷启动复查：就绪记录在但文件没了/校验不过 → 作废清键，可重新自动下载
            final ReadyInfo ready = validateReadyFull(act);
            if (!due) {
                // 未到检查节流点：已有有效就绪件仍呈现一次（前台弹框）
                if (ready != null) {
                    act.runOnUiThread(() -> {
                        if (!act.isFinishing()) showReadyDialog(act, ready);
                    });
                }
                return;
            }
            final UpdateLogic.Manifest m;
            try {
                m = fetchManifest();
            } catch (Exception e) {
                return; // 自动检查失败静默，不打扰
            }
            if (m == null) return;
            act.runOnUiThread(() -> {
                if (act.isFinishing()) return;
                UpdateLogic.AutoDecision d = UpdateLogic.decideAutoDownload(
                        isAutoDownload(act),
                        UpdateLogic.isIgnored(m.versionCode, ignored),
                        UpdateLogic.hasUpdate(m.versionCode, BuildInfo.VERSION_CODE),
                        isWifiOnly(act), isOnWifi(act),
                        ready == null ? 0 : ready.code,
                        m.versionCode, BuildInfo.VERSION_CODE);
                switch (d) {
                    case READY_INSTALL:
                        showReadyDialog(act, ready);
                        break;
                    case AUTO_DOWNLOAD:
                        startBackgroundDownload(act, m);
                        break;
                    case SHOW_DIALOG:
                        showUpdateDialog(act, m);
                        break;
                    default:
                        break; // NONE：被忽略或无新版，什么都不做
                }
            });
        }).start();
    }

    /** 设置页手动检查：失败明示；已有就绪件直接呈现安装、不重复下载；
     *  就绪件低于远端新版时走与自检相同的分流（自动关则弹框） */
    public static void manualCheck(final Activity act) {
        hookLifecycle(act);
        prefs(act).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
        Toast.makeText(act, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final ReadyInfo ready = validateReadyFull(act);
            UpdateLogic.Manifest m = null;
            String err = null;
            try {
                m = fetchManifest();
            } catch (Exception e) {
                err = e.getMessage();
            }
            final UpdateLogic.Manifest fm = m;
            final String fe = err;
            act.runOnUiThread(() -> {
                if (act.isFinishing()) return;
                if (fm == null) {
                    if (ready != null) { // 清单没取到，但手头已有下好的新版：直接呈现安装
                        showReadyDialog(act, ready);
                        return;
                    }
                    new AlertDialog.Builder(act)
                            .setTitle("检查更新失败")
                            .setMessage(fe != null ? fe : "没有取到更新清单，请稍后再试")
                            .setPositiveButton("知道了", null)
                            .show();
                } else if (!UpdateLogic.hasUpdate(fm.versionCode, BuildInfo.VERSION_CODE)) {
                    if (ready != null) {
                        showReadyDialog(act, ready);
                    } else {
                        Toast.makeText(act, "已是最新版本（v" + BuildInfo.VERSION_NAME + "）",
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    // 手动检查不受忽略名单影响：ignored 传 false
                    UpdateLogic.AutoDecision d = UpdateLogic.decideAutoDownload(
                            isAutoDownload(act), false, true,
                            isWifiOnly(act), isOnWifi(act),
                            ready == null ? 0 : ready.code,
                            fm.versionCode, BuildInfo.VERSION_CODE);
                    switch (d) {
                        case READY_INSTALL:
                            showReadyDialog(act, ready);
                            break;
                        case AUTO_DOWNLOAD:
                            Toast.makeText(act, "新版 v" + fm.versionName
                                    + " 正在后台下载，下好后点一次就能安装",
                                    Toast.LENGTH_LONG).show();
                            startBackgroundDownload(act, fm);
                            break;
                        default:
                            showUpdateDialog(act, fm);
                            break;
                    }
                }
            });
        }).start();
    }

    // ---------- 就绪呈现与安装 ----------

    /** 「新版已备好」框（前台）：版本号＋说明＋安装/稍后；稍后不清除就绪 */
    private static void showReadyDialog(final Activity act, final ReadyInfo ready) {
        if (ready == null) return;
        StringBuilder msg = new StringBuilder();
        msg.append("弦歌 v").append(ready.versionName).append(" 已经下载好，点「安装」就能装");
        if (ready.notes != null && !ready.notes.isEmpty()) {
            msg.append("\n\n").append(ready.notes);
        }
        new AlertDialog.Builder(act)
                .setTitle("新版已备好")
                .setMessage(msg.toString())
                .setPositiveButton("安装", (d, w) -> installReady(act, ready))
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 点「安装」（就绪路径）：先过安装授权关，再复用既有 install 入口调系统安装 */
    public static void installReady(final Activity act, final ReadyInfo ready) {
        ReadyInfo r = validateReadyQuick(act);
        if (r == null || (ready != null && r.code != ready.code)) {
            Toast.makeText(act, "下好的更新包不见了或对不上，请重新检查更新",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !act.getPackageManager().canRequestPackageInstalls()) {
            AlertDialog dlg = new AlertDialog.Builder(act)
                    .setTitle("还差一步")
                    .setMessage("安装更新需要允许弦歌「安装未知应用」的一次性授权，"
                            + "在系统页里打开一次就好。开启后回来，再点一次「安装」即可")
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            act.startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        } catch (Exception e) {
                            try {
                                act.startActivity(new Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                            } catch (Exception ignored) {
                            }
                        }
                    })
                    .setNegativeButton("稍后再说", null)
                    .create();
            dlg.setOnDismissListener(d -> {
                if (act instanceof UpdateInstallActivity) act.finish();
            });
            dlg.show();
            return;
        }
        launchInstaller(act, new File(r.path));
        if (act instanceof UpdateInstallActivity) act.finish();
    }

    /** 通知点装入口（UpdateInstallActivity 调）：无就绪件直接收尾 */
    public static void installReadyFromNotification(final Activity act) {
        ReadyInfo r = getReadyInfo(act);
        if (r == null) {
            act.finish();
            return;
        }
        installReady(act, r);
    }

    // ---------- 更新框 ----------

    private static void showUpdateDialog(final Activity act, final UpdateLogic.Manifest m) {
        StringBuilder msg = new StringBuilder();
        if (m.notes != null && !m.notes.isEmpty()) msg.append(m.notes);
        if (m.size > 0) {
            if (msg.length() > 0) msg.append("\n\n");
            msg.append("大小约 ").append(String.format("%.1f", m.size / 1048576.0)).append(" MB");
        }
        new AlertDialog.Builder(act)
                .setTitle("发现新版本 v" + m.versionName)
                .setMessage(msg.length() > 0 ? msg.toString() : "有新版本可用")
                .setPositiveButton("更新", (d, w) -> onUpdateClicked(act, m))
                .setNeutralButton("以后再说", null)
                .setNegativeButton("忽略此版本", (d, w) ->
                        prefs(act).edit().putInt(KEY_IGNORED, m.versionCode).apply())
                .show();
    }

    /** 点「更新」：先过安装授权关（没授权先引导去开，一次性的事），再下载 */
    private static void onUpdateClicked(final Activity act, final UpdateLogic.Manifest m) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !act.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(act)
                    .setTitle("还差一步")
                    .setMessage("安装更新需要允许弦歌「安装未知应用」的一次性授权，"
                            + "在系统页里打开一次就好，以后更新不用再开。开启后回来，"
                            + "再点一次「检查更新」里的更新即可")
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            act.startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        } catch (Exception e) {
                            try {
                                act.startActivity(new Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                            } catch (Exception ignored) {
                            }
                        }
                    })
                    .setNegativeButton("先不更新", null)
                    .show();
            return;
        }
        if (isWifiOnly(act) && !isOnWifi(act)) {
            new AlertDialog.Builder(act)
                    .setTitle("现在不是 Wi-Fi")
                    .setMessage("你开了「仅 Wi-Fi 下载更新」，连上 Wi-Fi 后再更新；"
                            + "或到设置里关掉这一项，移动流量也能下")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        startDownload(act, m);
    }

    // ---------- 下载与安装 ----------

    private interface ProgressListener {
        void onProgress(int pct, long done, long total);
    }

    private static final class DownloadResult {
        File file;          // 成功时验过的落位文件
        String error;       // 失败原因（给用户看的短句）
        boolean cancelled;
    }

    /** 下载核心（对话框与后台自动下载共用）：逐源下载→验 size+sha256→落位；
     *  校验不过删文件并按失败换源；取消/失败不留残件、不写就绪 */
    private static DownloadResult downloadAndVerify(final Context ctx,
                                                    final UpdateLogic.Manifest m,
                                                    final AtomicBoolean cancelled,
                                                    final ProgressListener pl) {
        DownloadResult res = new DownloadResult();
        String lastErr = null;
        UpdateLogic.Failure lastFailure = UpdateLogic.Failure.NETWORK;
        for (int si = 0; si < m.sources.size(); si++) {
            if (cancelled.get()) break;
            UpdateLogic.Source src = m.sources.get(si);
            try {
                File f = downloadSource(ctx, src, m, cancelled, pl);
                String sha = null;
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                    sha = UpdateLogic.sha256Hex(in);
                }
                UpdateLogic.Verify v = UpdateLogic.verifyFile(f.length(), m.size, sha, m.sha256);
                if (v != UpdateLogic.Verify.OK) {
                    f.delete();
                    lastFailure = UpdateLogic.Failure.VERIFY;
                    lastErr = verifyText(v);
                } else {
                    File dir = UpdateFileProvider.updatesDir(ctx);
                    String name = (m.apkFileName != null && !m.apkFileName.isEmpty())
                            ? m.apkFileName : "update.apk";
                    File dest = new File(dir, name);
                    if (!f.equals(dest)) {
                        if (dest.exists()) dest.delete();
                        if (!f.renameTo(dest)) {
                            throw new java.io.IOException("更新包落位失败");
                        }
                    }
                    res.file = dest;
                    return res;
                }
            } catch (CancelledException ce) {
                break;
            } catch (HttpException he) {
                lastFailure = UpdateLogic.Failure.HTTP;
                lastErr = "服务器返回 " + he.code;
            } catch (java.io.IOException ioe) {
                lastFailure = UpdateLogic.Failure.IO;
                lastErr = ioe.getMessage();
            } catch (Exception e) {
                lastFailure = UpdateLogic.Failure.NETWORK;
                lastErr = e.getMessage();
            }
            if (!UpdateLogic.shouldFallback(lastFailure)) break;
            if (pl != null) pl.onProgress(0, 0, m.size > 0 ? m.size : -1);
        }
        res.cancelled = cancelled.get();
        res.error = lastErr;
        return res;
    }

    private static void startDownload(final Activity act, final UpdateLogic.Manifest m) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 20), dp(act, 8), dp(act, 20), dp(act, 4));
        final ProgressBar bar = new ProgressBar(act, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(bar);
        final TextView pct = new TextView(act);
        pct.setText("准备下载…");
        pct.setTextSize(13);
        pct.setPadding(0, dp(act, 8), 0, 0);
        box.addView(pct);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("正在更新到 v" + m.versionName)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> cancelled.set(true))
                .create();
        dlg.show();

        new Thread(() -> {
            final DownloadResult r = downloadAndVerify(act, m, cancelled, (p2, dNow, tNow) ->
                    act.runOnUiThread(() -> {
                        if (p2 == 0 && dNow == 0) {
                            bar.setProgress(0);
                            pct.setText("换一个来源重试…");
                        } else {
                            bar.setProgress(p2);
                            pct.setText(p2 + "% · "
                                    + String.format("%.1f", dNow / 1048576.0) + " / "
                                    + String.format("%.1f", tNow / 1048576.0) + " MB");
                        }
                    }));
            act.runOnUiThread(() -> {
                if (act.isFinishing()) return;
                dlg.dismiss();
                if (r.cancelled) {
                    Toast.makeText(act, "已取消更新", Toast.LENGTH_SHORT).show();
                } else if (r.file != null) {
                    launchInstaller(act, r.file);
                } else {
                    new AlertDialog.Builder(act)
                            .setTitle("更新失败")
                            .setMessage(r.error != null ? r.error : "下载没有完成，请稍后再试")
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
        }).start();
    }

    // ---------- v1.24.0 后台自动下载 ----------

    private static final AtomicBoolean bgDownloading = new AtomicBoolean(false);
    private static final AtomicBoolean bgCancelled = new AtomicBoolean(false);

    /** 通知里点「取消」经 UpdateActionReceiver 回调到这里 */
    public static void cancelBackgroundDownload() {
        bgCancelled.set(true);
    }

    /**
     * 后台下载（自检/手动分流共用）：不弹更新框；下载中只一条低优先级、
     * 可取消的进度通知；验过后写就绪——App 在前台弹「新版已备好」框，
     * 不在前台发通知点之即装。失败/取消静默收尾、不留残件不留假就绪。
     */
    private static void startBackgroundDownload(final Activity act,
                                                final UpdateLogic.Manifest m) {
        if (!bgDownloading.compareAndSet(false, true)) return; // 已有一趟在下，不重复
        hookLifecycle(act);
        bgCancelled.set(false);
        final Context appCtx = act.getApplicationContext();
        notifyDownloadProgress(appCtx, m, 0);
        new Thread(() -> {
            DownloadResult r = null;
            try {
                r = downloadAndVerify(appCtx, m, bgCancelled, (pct, dNow, tNow) ->
                        notifyDownloadProgress(appCtx, m, pct));
            } finally {
                bgDownloading.set(false);
            }
            NotificationManager nm = (NotificationManager)
                    appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_DOWNLOAD_ID);
            if (r == null || r.cancelled || r.file == null) return; // 静默收尾
            saveReady(appCtx, m, r.file);
            ReadyInfo ready = getReadyInfo(appCtx);
            final Activity fg = resumedActivity;
            if (fg != null && !fg.isFinishing()) {
                fg.runOnUiThread(() -> {
                    if (!fg.isFinishing()) showReadyDialog(fg, ready);
                });
            } else {
                notifyReady(appCtx, ready);
            }
        }).start();
    }

    private static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_DOWNLOAD) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CH_DOWNLOAD, "更新下载", NotificationManager.IMPORTANCE_LOW));
        }
        if (nm.getNotificationChannel(CH_READY) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CH_READY, "新版已备好", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    /** 下载中通知：低优先级、一条、可取消（取消钮经广播置位 bgCancelled） */
    private static void notifyDownloadProgress(Context c, UpdateLogic.Manifest m, int pct) {
        try {
            ensureChannels(c);
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent ci = new Intent(c, UpdateActionReceiver.class);
            ci.setAction(UpdateActionReceiver.ACTION_CANCEL_DOWNLOAD);
            PendingIntent cancelPi = PendingIntent.getBroadcast(c, 0, ci,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = new Notification.Builder(c, CH_DOWNLOAD)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("弦歌新版 v" + m.versionName + " 正在下载")
                    .setContentText(pct > 0 ? ("已下载 " + pct + "%") : "准备下载…")
                    .setProgress(100, pct, pct <= 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .addAction(new Notification.Action.Builder(
                            android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPi).build());
            nm.notify(NOTIF_DOWNLOAD_ID, b.build());
        } catch (Exception ignored) {
            // 通知发不出（未授通知权限等）不影响下载本身
        }
    }

    /** 就绪通知：点之直达 UpdateInstallActivity 调系统安装（复用既有 install 入口） */
    private static void notifyReady(Context c, ReadyInfo ready) {
        if (ready == null) return;
        try {
            ensureChannels(c);
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent i = new Intent(c, UpdateInstallActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = new Notification.Builder(c, CH_READY)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("弦歌新版已备好")
                    .setContentText("弦歌新版 v" + ready.versionName + " 已下载 · 点此安装")
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(NOTIF_READY_ID, b.build());
        } catch (Exception ignored) {
            // 通知发不出不影响就绪件本身，下次进 App 仍会呈现
        }
    }

    private static String verifyText(UpdateLogic.Verify v) {
        switch (v) {
            case SIZE_MISMATCH:
                return "下载的文件大小对不上，已删除，请重试";
            case SHA_MISMATCH:
                return "下载的文件校验没通过（可能传坏了），已删除，请重试";
            case SHA_MISSING:
                return "更新清单缺少校验值，为安全起见没有安装";
            default:
                return "校验失败";
        }
    }

    /** 下载一个源（整件或分片按序拼到同一 .part 文件），返回落盘文件 */
    private static File downloadSource(final Context ctx, UpdateLogic.Source src,
                                       UpdateLogic.Manifest m,
                                       final AtomicBoolean cancelled,
                                       final ProgressListener pl)
            throws Exception {
        File dir = UpdateFileProvider.updatesDir(ctx);
        if (!dir.exists()) dir.mkdirs();
        String name = (m.apkFileName != null && !m.apkFileName.isEmpty())
                ? m.apkFileName : "update.apk";
        File part = new File(dir, name + ".part");
        long total = m.size > 0 ? m.size : -1;
        long done = 0;
        try (OutputStream out = new FileOutputStream(part)) {
            for (String url : src.urls) {
                HttpURLConnection c = open(url);
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    c.disconnect();
                    throw new HttpException(code);
                }
                if (total < 0) total = c.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (cancelled.get()) throw new CancelledException();
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0 && pl != null) {
                            pl.onProgress((int) Math.min(100, done * 100 / total), done, total);
                        }
                    }
                } finally {
                    c.disconnect();
                }
            }
            out.flush();
        } catch (Exception e) {
            part.delete();
            throw e;
        }
        if (cancelled.get()) {
            part.delete();
            throw new CancelledException();
        }
        return part;
    }

    private static void launchInstaller(Activity act, File apk) {
        try {
            Uri uri = UpdateFileProvider.uriForFile(act, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            new AlertDialog.Builder(act)
                    .setTitle("调起安装失败")
                    .setMessage("更新包已经下载并校验通过，但没能打开系统安装器："
                            + e.getMessage())
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    // ---------- 取清单（Gitee → GitHub） ----------

    static UpdateLogic.Manifest fetchManifest() throws Exception {
        String e1;
        try {
            String url = findManifestUrl(httpGet(GITEE_LATEST_API));
            UpdateLogic.Manifest m = UpdateLogic.parseManifest(httpGet(url));
            if (m != null) return m;
            e1 = "Gitee 清单内容不可用";
        } catch (Exception e) {
            e1 = "Gitee：" + e.getMessage();
        }
        try {
            String url = findManifestUrl(httpGet(GITHUB_LATEST_API));
            UpdateLogic.Manifest m = UpdateLogic.parseManifest(httpGet(url));
            if (m != null) return m;
            throw new Exception("GitHub 清单内容不可用（先前 " + e1 + "）");
        } catch (Exception e) {
            throw new Exception("两个来源都没取到更新清单——" + e1 + "；" + e.getMessage()
                    + "。请检查网络后重试");
        }
    }

    /** 从 release JSON 里找 update.json 附件的下载地址 */
    private static String findManifestUrl(String releaseJson) throws Exception {
        JSONObject o = new JSONObject(releaseJson);
        JSONArray assets = o.optJSONArray("assets");
        if (assets == null) throw new Exception("发行信息里没有附件列表");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a != null && MANIFEST_ASSET.equals(a.optString("name"))) {
                String u = a.optString("browser_download_url");
                if (!u.isEmpty()) return u;
            }
        }
        throw new Exception("发行附件里没有 " + MANIFEST_ASSET);
    }

    // ---------- 网络小件 ----------

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Xiange-Updater/" + BuildInfo.VERSION_NAME);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = open(url);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new HttpException(code);
        }
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static boolean isOnWifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network nw = cm.getActiveNetwork();
            if (nw == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(nw);
            return cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class HttpException extends Exception {
        final int code;

        HttpException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static final class CancelledException extends Exception {
    }
}
