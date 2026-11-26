package com.ti.songdu;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.31.0 注音频包仓（云端预生成包的 App 侧）：包按「书＋注释版本」一份，
 * 服务端合成打包后放进 Gitee 私有仓 shixi-models（与识读模型同仓、同令牌、
 * 同 git blob 通道），App 首次启用该版本的「正文＋注释」时下载、解压到
 * 私有目录 files/tts-packs/<bookId>/<version>/，之后离线本地播放。
 *
 * 仓库侧契约（打包线按此上架，改动须两边同步）：
 *  - 索引 tts-packs/packs.json：
 *    {"packs":[{"bookId","version","file","size","md5","parts"}]}
 *    file 为 zip 文件名（位于 tts-packs/ 下）；parts=null 表示整件经 git
 *    blob 取；zip 大于约 40MB 时 parts=[{"path","size","md5"}]（2MiB 分片，
 *    与识读模型同 convention），逐片取下按序拼合。
 *  - zip 内含 manifest.json：
 *    {"bookId","version","entries":[{"key","file","durationMs"}]}
 *    key＝注块键（注数据条目键＝块首句 id），file 为包内 mp3 相对路径。
 *  - 整件与逐片都验 size＋md5，对不上整单作废（staging 区，不动已就绪包）。
 *
 * 就绪判定：包目录内 manifest.json 能解析、bookId/version 对得上、条目
 * 非空。fileFor 按 key 查 manifest 映射并验文件存在。
 */
public final class TtsPackStore {

    /** prepare 失败原因：该书＋版本的包尚未下载（框架据此停发后续触发） */
    public static final String ERR_NOT_DOWNLOADED = "PACK_NOT_DOWNLOADED";

    private static final String MODELS_REPO = "shixi-models";
    private static final String INDEX_PATH = "tts-packs/packs.json";
    private static final String PACKS_PREFIX = "tts-packs/";
    private static final int ATTEMPTS = 3;

    private TtsPackStore() {
    }

    // ---------- 索引 ----------

    public static final class PackEntry {
        public String bookId, version, file, md5;
        public long size;
        /** 分片清单（整件下载时为 null） */
        public JSONArray parts;

        public String label() {
            return bookId + "/" + version;
        }
    }

    public static final class Index {
        public final List<PackEntry> packs = new ArrayList<>();
        /** 仓库 path → git blob sha（下载 zip/分片时按 sha 取） */
        public Map<String, String> tree = new HashMap<>();

        public PackEntry find(String bookId, String version) {
            for (PackEntry e : packs) {
                if (e.bookId.equals(bookId) && e.version.equals(version)) return e;
            }
            return null;
        }
    }

    public interface ProgressCb {
        void onProgress(long done, long total);
    }

    /** 拉包索引（阻塞，请在后台线程调用）：git 树 → packs.json 解析 */
    public static Index fetchIndex(Context ctx) throws Exception {
        Map<String, String> tree = fetchTreeMap();
        String sha = tree.get(INDEX_PATH);
        if (sha == null) throw new IllegalStateException("仓内找不到包索引 " + INDEX_PATH);
        File tmp = new File(ctx.getCacheDir(), "tts-packs-index.json");
        fetchBlobWithRetry(sha, tmp, null);
        JSONObject root = new JSONObject(readText(tmp));
        Index idx = new Index();
        idx.tree = tree;
        JSONArray arr = root.getJSONArray("packs");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            PackEntry e = new PackEntry();
            e.bookId = o.getString("bookId");
            e.version = o.getString("version");
            e.file = o.getString("file");
            e.size = o.getLong("size");
            e.md5 = o.getString("md5");
            e.parts = o.isNull("parts") ? null : o.getJSONArray("parts");
            idx.packs.add(e);
        }
        return idx;
    }

    private static Map<String, String> fetchTreeMap() throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (attempt > 1) sleep(2000L * attempt);
            try {
                String token = StoreConfig.TOKEN;
                if (token == null || token.isEmpty()) {
                    throw new IllegalStateException("缺少书库令牌（构建时未注入）");
                }
                String url = StoreConfig.API_BASE + "/repos/" + StoreConfig.OWNER + "/"
                        + MODELS_REPO + "/git/trees/master?recursive=1&access_token=" + token;
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Shixi-Android");
                int code = c.getResponseCode();
                if (code != 200) {
                    c.disconnect();
                    throw new IllegalStateException("获取仓内索引失败（HTTP " + code + "）");
                }
                String body = readText(c.getInputStream());
                c.disconnect();
                JSONObject root = new JSONObject(body);
                JSONArray tree = root.getJSONArray("tree");
                Map<String, String> map = new HashMap<>();
                for (int i = 0; i < tree.length(); i++) {
                    JSONObject o = tree.getJSONObject(i);
                    if ("blob".equals(o.optString("type"))) {
                        map.put(o.getString("path"), o.getString("sha"));
                    }
                }
                return map;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("获取仓内索引失败");
    }

    // ---------- 本地包 ----------

    public static File packDir(Context ctx, String bookId, String version) {
        return new File(new File(packsRoot(ctx), sanitize(bookId)), sanitize(version));
    }

    /** 路径段净化（书 id/版本都是自家枚举值，此为兜底防御） */
    static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "_";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    private static File packsRoot(Context ctx) {
        File d = new File(ctx.getFilesDir(), "tts-packs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** 已解析的 manifest 映射（bookId\nversion → key→相对路径），进程内缓存 */
    private static final Map<String, Map<String, String>> MEMO = new HashMap<>();

    private static Map<String, String> manifestMap(Context ctx, String bookId, String version) {
        String k = bookId + "\n" + version;
        synchronized (MEMO) {
            Map<String, String> m = MEMO.get(k);
            if (m != null) return m;
        }
        Map<String, String> m = loadManifest(ctx, bookId, version);
        if (m != null) {
            synchronized (MEMO) {
                MEMO.put(k, m);
            }
        }
        return m;
    }

    private static Map<String, String> loadManifest(Context ctx, String bookId, String version) {
        try {
            File mf = new File(packDir(ctx, bookId, version), "manifest.json");
            if (!mf.exists()) return null;
            JSONObject root = new JSONObject(readText(mf));
            if (!bookId.equals(root.optString("bookId"))) return null;
            if (!version.equals(root.optString("version"))) return null;
            JSONArray arr = root.getJSONArray("entries");
            if (arr.length() == 0) return null;
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                m.put(o.getString("key"), o.getString("file"));
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** 该书＋版本的包是否已就绪（可离线播放） */
    public static boolean isReady(Context ctx, String bookId, String version) {
        return manifestMap(ctx, bookId, version) != null;
    }

    /** 按注块键取包内音频文件；包未就绪/键不存在/文件缺失返回 null */
    public static File fileFor(Context ctx, String bookId, String version, String blockKey) {
        Map<String, String> m = manifestMap(ctx, bookId, version);
        if (m == null || blockKey == null) return null;
        String rel = m.get(blockKey);
        if (rel == null) return null;
        try {
            File dir = packDir(ctx, bookId, version);
            File f = new File(dir, rel);
            String dc = dir.getCanonicalPath();
            String fc = f.getCanonicalPath();
            if (!fc.startsWith(dc + File.separator)) return null;
            return f.exists() && f.length() > 0 ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 下载 ----------

    /**
     * 下载并安装一个包（阻塞，请在后台线程调用）：zip 取到 staging →
     * 验 size＋md5 → 解压 → 验 manifest（bookId/version 对得上、条目非空）
     * → 整目录换入正式位。任一步失败抛异常且不动已就绪的旧包。
     */
    public static void downloadPack(Context ctx, PackEntry e, Map<String, String> tree,
                                    ProgressCb pcb) throws Exception {
        File root = packsRoot(ctx);
        File staging = new File(root, ".staging-" + sanitize(e.bookId) + "-" + sanitize(e.version));
        Bookstore.deleteRecursive(staging);
        //noinspection ResultOfMethodCallIgnored
        staging.mkdirs();
        File zip = new File(staging, "pack.zip");
        final long[] doneBox = {0};
        Bookstore.ProgressListener pl = new Bookstore.ProgressListener() {
            @Override
            public void onProgress(long d, long t) {
                doneBox[0] += d;
                if (pcb != null) pcb.onProgress(Math.min(doneBox[0], e.size), e.size);
            }

            @Override
            public void onDone(boolean ok, String error) {
            }

            @Override
            public void onFileRetry(String path, int attempt, int maxAttempts, String reason) {
            }
        };
        if (e.parts == null) {
            String path = PACKS_PREFIX + e.file;
            String sha = tree.get(path);
            if (sha == null) throw new IllegalStateException("仓内找不到包文件 " + path);
            fetchBlobWithRetry(sha, zip, pl);
            verifyFile(zip, e.size, e.md5, "音频包");
        } else {
            FileOutputStream out = new FileOutputStream(zip, false);
            try {
                byte[] buf = new byte[65536];
                for (int i = 0; i < e.parts.length(); i++) {
                    JSONObject part = e.parts.getJSONObject(i);
                    String ppath = part.getString("path");
                    long psize = part.getLong("size");
                    String pmd5 = part.optString("md5", null);
                    String sha = tree.get(ppath);
                    if (sha == null) throw new IllegalStateException("仓内找不到分片 " + ppath);
                    File pf = new File(staging, "part" + i);
                    fetchBlobWithRetry(sha, pf, pl);
                    verifyFile(pf, psize, pmd5, "分片 " + ppath);
                    FileInputStream in = new FileInputStream(pf);
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    //noinspection ResultOfMethodCallIgnored
                    pf.delete();
                }
            } finally {
                out.close();
            }
            if (zip.length() != e.size) throw new IllegalStateException("音频包拼合大小不对");
            String zmd5 = md5HexOfFile(zip);
            if (!e.md5.equalsIgnoreCase(zmd5)) throw new IllegalStateException("音频包校验失败");
        }
        File extract = new File(staging, "x");
        ZipExtract.extract(zip, extract);
        //noinspection ResultOfMethodCallIgnored
        zip.delete();
        // manifest 验真：与索引声称的书＋版本对得上才准换入
        Map<String, String> m = null;
        try {
            File mf = new File(extract, "manifest.json");
            JSONObject r = new JSONObject(readText(mf));
            if (e.bookId.equals(r.optString("bookId")) && e.version.equals(r.optString("version"))) {
                JSONArray arr = r.getJSONArray("entries");
                if (arr.length() > 0) {
                    m = new HashMap<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        m.put(o.getString("key"), o.getString("file"));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (m == null) throw new IllegalStateException("音频包清单对不上（书/版本不符或条目为空）");
        File dest = packDir(ctx, e.bookId, e.version);
        Bookstore.deleteRecursive(dest);
        if (dest.getParentFile() != null) {
            //noinspection ResultOfMethodCallIgnored
            dest.getParentFile().mkdirs();
        }
        if (!extract.renameTo(dest)) {
            throw new IllegalStateException("音频包落盘失败");
        }
        Bookstore.deleteRecursive(staging);
        synchronized (MEMO) {
            MEMO.put(e.bookId + "\n" + e.version, m);
        }
        if (pcb != null) pcb.onProgress(e.size, e.size);
    }

    /** 验 size＋md5（md5 按落盘文件现算，不经下载流，口径单一不会错位） */
    private static void verifyFile(File f, long size, String md5, String label) throws Exception {
        if (f.length() != size) {
            throw new IllegalStateException(label + "大小不对（" + f.length() + "/" + size + "）");
        }
        if (md5 != null && !md5.equalsIgnoreCase(md5HexOfFile(f))) {
            throw new IllegalStateException(label + "校验失败");
        }
    }

    private static String md5HexOfFile(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        in.close();
        return Bookstore.hex(md.digest());
    }

    /** git blob 取单文件（带重试；contents 路线的定点截断教训在前，模型仓一律走 blob） */
    private static long fetchBlobWithRetry(String sha, File dest,
                                           Bookstore.ProgressListener pl) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (attempt > 1) sleep(1500L * attempt);
            try {
                return Bookstore.downloadBlobFile(MODELS_REPO, sha, dest, null, pl);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("下载失败");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static String readText(File f) throws Exception {
        return readText(new FileInputStream(f));
    }

    private static String readText(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
