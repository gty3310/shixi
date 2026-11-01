package com.ti.songdu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
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
 */
public final class AppUpdater {

    private AppUpdater() {
    }

    public static final String PREFS = "update_prefs";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final String KEY_IGNORED = "ignored_code";
    private static final String KEY_WIFI_ONLY = "wifi_only";
    private static final String KEY_AUTO_CHECK = "auto_check";

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

    // ---------- 检查入口 ----------

    /** 冷启动自检：24 小时节流、失败静默；有新版且未忽略才弹框 */
    public static void maybeAutoCheck(final Activity act) {
        SharedPreferences sp = prefs(act);
        long now = System.currentTimeMillis();
        if (!UpdateLogic.shouldAutoCheck(sp.getLong(KEY_LAST_CHECK, 0), now,
                sp.getBoolean(KEY_AUTO_CHECK, true))) {
            return;
        }
        sp.edit().putLong(KEY_LAST_CHECK, now).apply();
        final int ignored = sp.getInt(KEY_IGNORED, 0);
        new Thread(() -> {
            final UpdateLogic.Manifest m;
            try {
                m = fetchManifest();
            } catch (Exception e) {
                return; // 自动检查失败静默，不打扰
            }
            if (m == null) return;
            act.runOnUiThread(() -> {
                if (act.isFinishing()) return;
                if (UpdateLogic.shouldAutoShow(m, BuildInfo.VERSION_CODE, ignored)) {
                    showUpdateDialog(act, m);
                }
            });
        }).start();
    }

    /** 设置页手动检查：失败明示原因；无新版也明说；忽略过的版本照样能查到 */
    public static void manualCheck(final Activity act) {
        prefs(act).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
        Toast.makeText(act, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
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
                    new AlertDialog.Builder(act)
                            .setTitle("检查更新失败")
                            .setMessage(fe != null ? fe : "没有取到更新清单，请稍后再试")
                            .setPositiveButton("知道了", null)
                            .show();
                } else if (!UpdateLogic.hasUpdate(fm.versionCode, BuildInfo.VERSION_CODE)) {
                    Toast.makeText(act, "已是最新版本（v" + BuildInfo.VERSION_NAME + "）",
                            Toast.LENGTH_LONG).show();
                } else {
                    showUpdateDialog(act, fm);
                }
            });
        }).start();
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
            String lastErr = null;
            UpdateLogic.Failure lastFailure = UpdateLogic.Failure.NETWORK;
            File okFile = null;
            for (int si = 0; si < m.sources.size(); si++) {
                if (cancelled.get()) break;
                UpdateLogic.Source src = m.sources.get(si);
                try {
                    File f = downloadSource(act, src, m, cancelled, bar, pct);
                    // 校验：不符删文件、按失败处理（可换源）
                    String sha = null;
                    try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                        sha = UpdateLogic.sha256Hex(in);
                    }
                    UpdateLogic.Verify v = UpdateLogic.verifyFile(
                            f.length(), m.size, sha, m.sha256);
                    if (v != UpdateLogic.Verify.OK) {
                        f.delete();
                        lastFailure = UpdateLogic.Failure.VERIFY;
                        lastErr = verifyText(v);
                    } else {
                        File dir = UpdateFileProvider.updatesDir(act);
                        String name = (m.apkFileName != null && !m.apkFileName.isEmpty())
                                ? m.apkFileName : "update.apk";
                        File dest = new File(dir, name);
                        if (!f.equals(dest)) {
                            if (dest.exists()) dest.delete();
                            if (!f.renameTo(dest)) {
                                throw new java.io.IOException("更新包落位失败");
                            }
                        }
                        okFile = dest;
                        break;
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
                // 换下一个源前清进度
                act.runOnUiThread(() -> {
                    bar.setProgress(0);
                    pct.setText("换一个来源重试…");
                });
            }
            final File doneFile = okFile;
            final String fe = lastErr;
            final boolean wasCancelled = cancelled.get();
            act.runOnUiThread(() -> {
                if (act.isFinishing()) return;
                dlg.dismiss();
                if (wasCancelled) {
                    Toast.makeText(act, "已取消更新", Toast.LENGTH_SHORT).show();
                } else if (doneFile != null) {
                    launchInstaller(act, doneFile);
                } else {
                    new AlertDialog.Builder(act)
                            .setTitle("更新失败")
                            .setMessage(fe != null ? fe : "下载没有完成，请稍后再试")
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
        }).start();
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
    private static File downloadSource(final Activity act, UpdateLogic.Source src,
                                       UpdateLogic.Manifest m,
                                       final AtomicBoolean cancelled,
                                       final ProgressBar bar, final TextView pct)
            throws Exception {
        File dir = UpdateFileProvider.updatesDir(act);
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
                        if (total > 0) {
                            final int p = (int) Math.min(100, done * 100 / total);
                            final long dNow = done;
                            final long tNow = total;
                            act.runOnUiThread(() -> {
                                bar.setProgress(p);
                                pct.setText(p + "% · "
                                        + String.format("%.1f", dNow / 1048576.0) + " / "
                                        + String.format("%.1f", tNow / 1048576.0) + " MB");
                            });
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
