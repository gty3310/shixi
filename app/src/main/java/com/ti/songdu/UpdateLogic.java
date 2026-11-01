package com.ti.songdu;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用内更新纯逻辑（v1.22.0，无 Android 依赖）：清单解析、版本比较、忽略
 * 名单判定、双源回退判定、下载后 size/sha256 校验判定。App（AppUpdater）
 * 与桌面 harness（tools/update_harness）共用同一份实现，禁止在 harness
 * 里另写一份冒充 App 逻辑。
 *
 * 清单 update.json 形态（与两边 release 附件同版发布）：
 * {
 *   "versionCode": 46, "versionName": "1.22.0",
 *   "apkFileName": "弦歌-v1220.apk",
 *   "size": 38200000, "sha256": "…",
 *   "notes": "更新说明短文（与 WhatsNew 本版文案同源）",
 *   "sources": {
 *     "gitee":  { "apk": "整件直链" }                       // 整件
 *            或 { "parts": [ {"url": "…"}, … ] },          // 分片（按序拼合）
 *     "github": { "apk": "整件直链" }
 *   }
 * }
 * 取源顺序恒为 gitee → github。字段容错口径：notes/versionName/apkFileName
 * 缺失给空串、size 缺失给 -1（校验时跳过 size 项）、单侧源缺失只用另一侧；
 * 但 versionCode 缺失/非正、或两侧源都不可用 → 整份清单判无效（返回 null），
 * 不猜、不凑合。校验口径：size 不符先判 SIZE_MISMATCH；清单没给 sha256
 * 判 SHA_MISSING（视为校验失败，不放行安装）。
 */
public final class UpdateLogic {

    private UpdateLogic() {
    }

    /** 自动检查节流间隔：24 小时 */
    public static final long CHECK_INTERVAL_MS = 24L * 3600L * 1000L;

    /** 下载/取清单失败分类，决定是否换兜底源再试 */
    public enum Failure {NETWORK, HTTP, IO, VERIFY, BAD_MANIFEST, CANCELLED, NO_WIFI}

    /** 下载后校验结论 */
    public enum Verify {OK, SIZE_MISMATCH, SHA_MISMATCH, SHA_MISSING}

    /** 一个下载源：urls 长度 1＝整件，>1＝分片按序拼合 */
    public static final class Source {
        public final String name;
        public final String[] urls;

        Source(String n, String[] u) {
            name = n;
            urls = u;
        }

        public boolean sharded() {
            return urls.length > 1;
        }
    }

    public static final class Manifest {
        public int versionCode;
        public String versionName = "";
        public String apkFileName = "";
        public long size = -1;
        public String sha256 = "";
        public String notes = "";
        /** 取用顺序：gitee 在前、github 兜底 */
        public final List<Source> sources = new ArrayList<>();
    }

    /** 解析清单；致命缺失（无 versionCode / 无可用源 / 非 JSON）返回 null */
    public static Manifest parseManifest(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            int vc = o.optInt("versionCode", 0);
            if (vc <= 0) return null;
            Manifest m = new Manifest();
            m.versionCode = vc;
            m.versionName = o.optString("versionName", "");
            m.apkFileName = o.optString("apkFileName", "");
            m.size = o.optLong("size", -1);
            m.sha256 = o.optString("sha256", "");
            m.notes = o.optString("notes", "");
            JSONObject src = o.optJSONObject("sources");
            if (src != null) {
                addSource(m, src, "gitee");
                addSource(m, src, "github");
            }
            if (m.sources.isEmpty()) return null;
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static void addSource(Manifest m, JSONObject src, String key) {
        JSONObject s = src.optJSONObject(key);
        if (s == null) return;
        String apk = s.optString("apk", "");
        if (!apk.isEmpty()) {
            m.sources.add(new Source(key, new String[]{apk}));
            return;
        }
        JSONArray parts = s.optJSONArray("parts");
        if (parts != null && parts.length() > 0) {
            String[] urls = new String[parts.length()];
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                urls[i] = p == null ? "" : p.optString("url", "");
                if (urls[i].isEmpty()) return; // 分片有一片没地址，整源不可用
            }
            m.sources.add(new Source(key, urls));
        }
    }

    /** 版本比较：远端 > 本地返回正数（Integer.compare 口径） */
    public static int compareCodes(int remote, int local) {
        return Integer.compare(remote, local);
    }

    public static boolean hasUpdate(int remoteCode, int localCode) {
        return remoteCode > localCode;
    }

    /** 忽略名单：用户点过「忽略此版本」的 versionCode 命中判定 */
    public static boolean isIgnored(int remoteCode, int ignoredCode) {
        return ignoredCode > 0 && remoteCode == ignoredCode;
    }

    /** 自动弹框判定：有新版且未被忽略才弹（手动检查不走这条） */
    public static boolean shouldAutoShow(Manifest m, int localCode, int ignoredCode) {
        return m != null && hasUpdate(m.versionCode, localCode)
                && !isIgnored(m.versionCode, ignoredCode);
    }

    /** 冷启动节流：开了自动检查、且距上次检查已满 24 小时（从未查过算满） */
    public static boolean shouldAutoCheck(long lastCheckMs, long nowMs, boolean autoEnabled) {
        if (!autoEnabled) return false;
        if (lastCheckMs <= 0) return true;
        return nowMs - lastCheckMs >= CHECK_INTERVAL_MS;
    }

    /** 换源判定：网络/HTTP/IO/校验/清单类失败换兜底源；用户取消与 Wi-Fi 限制不换 */
    public static boolean shouldFallback(Failure f) {
        return f == Failure.NETWORK || f == Failure.HTTP || f == Failure.IO
                || f == Failure.VERIFY || f == Failure.BAD_MANIFEST;
    }

    /** 下载后校验：expectedSize < 0 表示清单未给 size、跳过该项 */
    public static Verify verifyFile(long actualSize, long expectedSize,
                                    String actualSha256, String expectedSha256) {
        if (expectedSize >= 0 && actualSize != expectedSize) return Verify.SIZE_MISMATCH;
        if (expectedSha256 == null || expectedSha256.isEmpty()) return Verify.SHA_MISSING;
        if (actualSha256 == null || !actualSha256.equalsIgnoreCase(expectedSha256)) {
            return Verify.SHA_MISMATCH;
        }
        return Verify.OK;
    }

    /** 流式 sha256（小写十六进制），App 与 harness 共用 */
    public static String sha256Hex(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
