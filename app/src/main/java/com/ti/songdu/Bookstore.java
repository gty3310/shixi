package com.ti.songdu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v1.14.0 在线书库数据层（无 UI；书库页 BookstoreActivity 与主页删书路由共用）。
 *
 * 数据来源：Gitee 私有仓——总目录仓 catalog.json 给书目与每本书的文件清单
 * （path/role/size/md5/durationMs），一本书一个仓。取文件只走 contents API
 * 返回的 base64 content 流式解码落盘（实测 download_url 与 raw 直链对仓库级
 * 令牌一律 403，禁用）；列目录只用于清单，不取文件体。
 *
 * 落盘与注册：整本先下到 files/books_staging/&lt;bookId&gt;/，逐文件验 size
 * 与 md5，全齐才整体移入 files/books/&lt;bookId&gt;/ 并注册曲目（半成品自动
 * 清理、不注册）。下载书的曲目 id 沿用预置口径（preset-zhongyong-full /
 * preset-lunyu-NN），records 按 id 绑定、与预置时代无缝衔接。
 *
 * 删书红线：只删本地文件与曲目条目，records.json 一律不动——阅读时长与
 * 历史记录保留，重新下载后按同一 id 自动接回。
 */
public final class Bookstore {

    private Bookstore() {
    }

    // ---------- 清单模型 ----------
    public static class PackFile {
        public String path;
        public String role;
        public long size;
        public String md5;
        public long durationMs;
    }

    public static class Book {
        public String id;
        public String title;
        public String repo;
        public long totalSize;
        public final List<PackFile> files = new ArrayList<>();
    }

    public static class BookStatus {
        /** 期望曲目数 / 已在列表中的曲目数 */
        public int totalTracks;
        public int presentTracks;
        /** 内容包已在本地（books 目录） */
        public boolean contentOk;

        public boolean isDownloaded() {
            return totalTracks > 0 && presentTracks >= totalTracks && contentOk;
        }

        public boolean isNone() {
            return presentTracks <= 0 && !contentOk;
        }
    }

    public interface ProgressListener {
        void onProgress(long doneBytes, long totalBytes);

        void onDone(boolean ok, String error);
    }

    /** 下载串行执行（大文件 base64 解码吃内存，多本同时下只会互相拖慢） */
    private static final ExecutorService DL_EXEC = Executors.newSingleThreadExecutor();
    private static final Set<String> DOWNLOADING = Collections.synchronizedSet(new HashSet<>());

    public static boolean isDownloading(String bookId) {
        return DOWNLOADING.contains(bookId);
    }

    // ---------- 路径 ----------
    public static File booksRoot(Context ctx) {
        return new File(ctx.getFilesDir(), "books");
    }

    public static File bookDir(Context ctx, String bookId) {
        return new File(booksRoot(ctx), bookId);
    }

    public static File stagingDir(Context ctx, String bookId) {
        return new File(ctx.getFilesDir(), "books_staging/" + bookId);
    }

    public static File contentFile(Context ctx, String bookId) {
        return new File(bookDir(ctx, bookId), "content/" + bookId + ".json");
    }

    public static File notesFile(Context ctx, String bookId) {
        return new File(bookDir(ctx, bookId), "notes/" + bookId + "_notes.json");
    }

    // ---------- 目录拉取 ----------
    /** 后台线程调用：经 contents API 取 catalog.json 并解析；失败抛异常（带可读信息） */
    public static List<Book> fetchCatalog(Context ctx) throws Exception {
        File tmp = new File(ctx.getCacheDir(), "catalog_dl.json");
        downloadContentsFile(StoreConfig.CATALOG_REPO, "catalog.json", tmp, null, null);
        String text = readText(tmp);
        tmp.delete();
        return parseCatalog(text);
    }

    public static List<Book> parseCatalog(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.getJSONArray("books");
        List<Book> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Book b = new Book();
            b.id = o.getString("id");
            b.title = o.optString("title", b.id);
            b.repo = o.getString("repo");
            b.totalSize = o.optLong("totalSize", 0);
            JSONArray fs = o.getJSONArray("files");
            for (int k = 0; k < fs.length(); k++) {
                JSONObject fo = fs.getJSONObject(k);
                PackFile pf = new PackFile();
                pf.path = fo.getString("path");
                pf.role = fo.optString("role", "");
                pf.size = fo.optLong("size", 0);
                pf.md5 = fo.isNull("md5") ? null : fo.optString("md5", null);
                pf.durationMs = fo.isNull("durationMs") ? 0 : fo.optLong("durationMs", 0);
                b.files.add(pf);
            }
            out.add(b);
        }
        return out;
    }

    // ---------- 曲目规格（书 → 期望曲目） ----------
    /** 《论语》二十篇篇名（与预置时代一致：繁体篇名＋第几） */
    private static final String[] LUNYU_TITLES = {
            "學而第一", "為政第二", "八佾第三", "里仁第四", "公冶長第五",
            "雍也第六", "述而第七", "泰伯第八", "子罕第九", "鄉黨第十",
            "先進第十一", "顏淵第十二", "子路第十三", "憲問第十四", "衛靈公第十五",
            "季氏第十六", "陽貨第十七", "微子第十八", "子張第十九", "堯曰第二十"};

    public static class TrackSpec {
        public String trackId;
        public String title;
        public String group;
        public String audioPath;
        public long durationMs;
    }

    public static List<TrackSpec> trackSpecs(Book b) {
        List<PackFile> audios = new ArrayList<>();
        for (PackFile pf : b.files) if ("audio".equals(pf.role)) audios.add(pf);
        Collections.sort(audios, new Comparator<PackFile>() {
            @Override
            public int compare(PackFile a, PackFile c) {
                return a.path.compareTo(c.path);
            }
        });
        List<TrackSpec> out = new ArrayList<>();
        if ("zhongyong".equals(b.id) && audios.size() == 1) {
            TrackSpec s = new TrackSpec();
            s.trackId = "preset-zhongyong-full";
            s.title = "《中庸》";
            s.group = null;
            s.audioPath = audios.get(0).path;
            s.durationMs = audios.get(0).durationMs;
            out.add(s);
            return out;
        }
        if ("lunyu".equals(b.id) && !audios.isEmpty()) {
            for (PackFile pf : audios) {
                int n = lunyuNumber(pf.path);
                if (n < 1 || n > 20) continue;
                TrackSpec s = new TrackSpec();
                s.trackId = String.format(Locale.ROOT, "preset-lunyu-%02d", n);
                s.title = LUNYU_TITLES[n - 1];
                s.group = "论语";
                s.audioPath = pf.path;
                s.durationMs = pf.durationMs;
                out.add(s);
            }
            return out;
        }
        // 通用书（未来新增经典）：每段音频一曲，contentId 绑定内容包
        for (int i = 0; i < audios.size(); i++) {
            TrackSpec s = new TrackSpec();
            s.trackId = "store-" + b.id + "-" + (i + 1);
            s.title = audios.size() > 1 ? "《" + b.title + "》" + (i + 1) : "《" + b.title + "》";
            s.group = audios.size() > 1 ? b.title : null;
            s.audioPath = audios.get(i).path;
            s.durationMs = audios.get(i).durationMs;
            out.add(s);
        }
        return out;
    }

    private static int lunyuNumber(String path) {
        try {
            int a = path.lastIndexOf('_');
            int d = path.lastIndexOf('.');
            return Integer.parseInt(path.substring(a + 1, d));
        } catch (Exception e) {
            return -1;
        }
    }

    /** 曲目 → 所属书库 bookId；不属于任何书库书（内置《大学》、用户自加）时返回 null */
    public static String bookIdOfTrack(Track t) {
        if (t == null) return null;
        if ("preset-zhongyong-full".equals(t.id)) return "zhongyong";
        if (t.id != null && t.id.startsWith("preset-lunyu-")) return "lunyu";
        if (t.id != null && t.id.startsWith("store-")) {
            String rest = t.id.substring("store-".length());
            int dash = rest.lastIndexOf('-');
            return dash > 0 ? rest.substring(0, dash) : rest;
        }
        if (t.uri != null) {
            String marker = "/books/";
            int i = t.uri.indexOf(marker);
            if (i >= 0) {
                int j = t.uri.indexOf('/', i + marker.length());
                if (j > i) return t.uri.substring(i + marker.length(), j);
            }
        }
        return null;
    }

    public static String bookTitleOf(String bookId) {
        if ("zhongyong".equals(bookId)) return "《中庸》";
        if ("lunyu".equals(bookId)) return "《论语》";
        return "《" + bookId + "》";
    }

    // ---------- 状态 ----------
    public static BookStatus status(Context ctx, Store store, Book b) {
        BookStatus st = new BookStatus();
        List<TrackSpec> specs = trackSpecs(b);
        st.totalTracks = specs.size();
        int present = 0;
        for (TrackSpec s : specs) if (store.findTrack(s.trackId) != null) present++;
        st.presentTracks = present;
        File cf = contentFile(ctx, b.id);
        st.contentOk = cf.exists() && cf.length() > 0;
        return st;
    }

    // ---------- 下载 ----------
    /**
     * 在下载线程池里执行整本下载（串行排队）。流程：staging 落盘 → 逐文件验
     * size+md5 → 整体装入 books 目录 → 注册缺失曲目。已在列表里的曲目若其
     * 音频文件大小与清单一致（老用户预置副本迁移来的同一母带），音频跳过
     * 不重下、只补文字侧文件。失败时 staging 自动清理、不注册、不动旧数据。
     */
    public static void downloadBookAsync(final Context ctx, final Book book,
                                         final ProgressListener pl) {
        final Context app = ctx.getApplicationContext();
        if (!DOWNLOADING.add(book.id)) {
            if (pl != null) pl.onDone(false, "这本书正在下载中");
            return;
        }
        DL_EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    downloadBook(app, book, pl);
                    if (pl != null) pl.onDone(true, null);
                } catch (Exception e) {
                    if (pl != null) pl.onDone(false, readableError(e));
                } finally {
                    DOWNLOADING.remove(book.id);
                }
            }
        });
    }

    private static void downloadBook(Context ctx, Book book, ProgressListener pl) throws Exception {
        Store store = Store.get(ctx);
        List<TrackSpec> specs = trackSpecs(book);
        File staging = stagingDir(ctx, book.id);
        deleteRecursive(staging);
        if (!staging.mkdirs() && !staging.exists()) throw new Exception("无法创建下载目录");

        // 决定每个文件下不下：音频若已有同尺寸的曲目文件（迁移副本）在，不重下
        List<PackFile> toFetch = new ArrayList<>();
        long total = 0;
        for (PackFile pf : book.files) {
            if ("audio".equals(pf.role) && existingAudioMatches(store, specs, pf)) continue;
            toFetch.add(pf);
            total += pf.size;
        }
        final long totalBytes = total;
        final long[] done = {0};
        // chunkPl 收到的是每段解码字节数（增量），累加即整本进度
        ProgressListener chunkPl = pl == null ? null : new ProgressListener() {
            @Override
            public void onProgress(long d, long t) {
                done[0] += d;
                pl.onProgress(Math.min(done[0], totalBytes), totalBytes);
            }

            @Override
            public void onDone(boolean ok, String error) {
            }
        };
        try {
            for (PackFile pf : toFetch) {
                File dest = new File(staging, pf.path);
                if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
                MessageDigest md = MessageDigest.getInstance("MD5");
                long written = downloadContentsFile(book.repo, pf.path, dest, md, chunkPl);
                if (written != pf.size) {
                    throw new Exception(pf.path + " 大小不对（" + written + "/" + pf.size + "）");
                }
                if (pf.md5 != null && !pf.md5.equalsIgnoreCase(hex(md.digest()))) {
                    throw new Exception(pf.path + " 校验失败（md5 不符）");
                }
            }
        } catch (Exception e) {
            deleteRecursive(staging);
            throw e;
        }

        // 装入：替换 books/<bookId>（旧的半成品/旧包先删，staging 整体改名移入）
        File target = bookDir(ctx, book.id);
        deleteRecursive(target);
        if (target.getParentFile() != null) target.getParentFile().mkdirs();
        if (!staging.renameTo(target)) {
            copyRecursive(staging, target);
            deleteRecursive(staging);
        }

        // 注册缺失曲目（已在列表的一律不动：位置、进度、改过的名字都保留）
        int insertAt = -1;
        for (int i = 0; i < store.tracks.size(); i++) {
            Track t = store.tracks.get(i);
            if ("preset-daxue-full".equals(t.id) || bookIdOfTrack(t) != null) {
                insertAt = Math.max(insertAt, i + 1);
            }
        }
        if (insertAt < 0) insertAt = store.tracks.size();
        boolean changed = false;
        for (TrackSpec s : specs) {
            if (store.findTrack(s.trackId) != null) continue;
            File audio = new File(target, s.audioPath);
            long dur = s.durationMs > 0 ? s.durationMs : fileDurationMs(audio.getAbsolutePath());
            Track t = new Track(s.trackId, audio.getAbsolutePath(), s.title,
                    "在线书库", dur, 0, 0, Track.KIND_FILE);
            t.group = s.group;
            t.contentId = book.id;
            store.tracks.add(insertAt++, t);
            changed = true;
        }
        if (changed) store.saveTracks();
        ContentRepo.invalidate(book.id);
    }

    /** 已有曲目（按规格 id）的文件大小与清单一致 → 这段音频不用重下 */
    private static boolean existingAudioMatches(Store store, List<TrackSpec> specs, PackFile pf) {
        for (TrackSpec s : specs) {
            if (!s.audioPath.equals(pf.path)) continue;
            Track t = store.findTrack(s.trackId);
            if (t == null || t.uri == null) return false;
            File f = new File(t.uri);
            return f.exists() && f.length() == pf.size;
        }
        return false;
    }

    // ---------- 删书（只删文件与条目，绝不动 records） ----------
    public static void deleteBook(Context ctx, Store store, String bookId) {
        PlayerService svc = PlayerService.instance;
        if (svc != null && svc.current != null && bookId.equals(bookIdOfTrack(svc.current))) {
            Intent i = new Intent(ctx, PlayerService.class).setAction(PlayerService.ACTION_STOP);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
                else ctx.startService(i);
            } catch (Exception ignored) {
            }
        }
        File dir = bookDir(ctx, bookId);
        List<File> extra = new ArrayList<>();
        Iterator<Track> it = store.tracks.iterator();
        while (it.hasNext()) {
            Track t = it.next();
            if (!bookId.equals(bookIdOfTrack(t))) continue;
            if (t.isFile() && t.uri != null) {
                File f = new File(t.uri);
                // 迁移来的预置副本音频不在 books 目录里，一并删（它就是这本书的本地文件）
                if (!isUnder(f, dir)) extra.add(f);
            }
            it.remove();
        }
        store.saveTracks();
        deleteRecursive(dir);
        deleteRecursive(stagingDir(ctx, bookId));
        for (File f : extra) {
            try {
                if (f.exists()) f.delete();
            } catch (Exception ignored) {
            }
        }
        ContentRepo.invalidate(bookId);
    }

    /** 删书确认框（书库页与主页共用；文案为定案口径，不许改动核心句） */
    public static void confirmDeleteBook(final Activity act, final String bookId,
                                         final Runnable onDone) {
        new AlertDialog.Builder(act)
                .setTitle("删除" + bookTitleOf(bookId) + "？")
                .setMessage("只删除本地文件，阅读时长和历史记录不会删除，需要时随时可以重新下载。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    deleteBook(act, Store.get(act), bookId);
                    Toast.makeText(act, "已删除" + bookTitleOf(bookId) + "（记录保留）",
                            Toast.LENGTH_SHORT).show();
                    if (onDone != null) onDone.run();
                })
                .show();
    }

    // ---------- contents API 取文件（流式 base64 解码） ----------
    private static final String B64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";

    /**
     * 经 Gitee contents API 取一个文件：响应是约文件 4/3 大小的 JSON，content
     * 字段为 base64。本方法流式扫描响应、边读边解码写盘，不整表解析、不留
     * 多份大拷贝（13MB 级响应实测可完整取回并 md5 对上母本）。
     * 返回写入字节数；md 不为 null 时同步喂入摘要；chunkPl 不为 null 时按解码
     * 字节数回调进度（t 参数无意义，调用方按整本口径换算）。
     */
    static long downloadContentsFile(String repo, String path, File dest,
                                     MessageDigest md, ProgressListener chunkPl) throws Exception {
        String token = StoreConfig.TOKEN;
        if (token == null || token.isEmpty()) throw new Exception("缺少书库令牌（构建时未注入）");
        String url = StoreConfig.API_BASE + "/repos/" + StoreConfig.OWNER + "/" + repo
                + "/contents/" + encodePath(path)
                + "?access_token=" + URLEncoder.encode(token, "UTF-8") + "&ref=master";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("User-Agent", "Shixi-Android");
        int code = c.getResponseCode();
        if (code != 200) {
            String err = readText(c.getErrorStream());
            c.disconnect();
            throw new Exception("下载 " + path + " 失败（HTTP " + code + "）"
                    + (err != null && !err.isEmpty() ? "：" + trimMsg(err) : ""));
        }
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(dest);
        long written = 0;
        try {
            ByteReader br = new ByteReader(in);
            // 阶段 0：找到 "content" 键；阶段 1：跳到值的起始引号后
            if (!seekContentValue(br)) throw new Exception("响应里没有 content 字段（" + path + "）");
            byte[] acc = new byte[70000];
            int accLen = 0;
            boolean end = false;
            while (!end) {
                int ch = br.read();
                if (ch < 0) throw new Exception("响应中途断开（" + path + "）");
                if (ch == '\\') { // JSON 转义（如 \n）：两字节都跳过
                    br.read();
                    continue;
                }
                if (ch == '"') break;
                if (B64_ALPHABET.indexOf(ch) < 0) continue;
                acc[accLen++] = (byte) ch;
                if (accLen >= 65536) { // 65536 是 4 的倍数，整段解码
                    byte[] dec = Base64.decode(acc, 0, 65536, Base64.NO_WRAP);
                    out.write(dec);
                    if (md != null) md.update(dec);
                    written += dec.length;
                    if (chunkPl != null) chunkPl.onProgress(dec.length, 0);
                    int rem = accLen - 65536;
                    System.arraycopy(acc, 65536, acc, 0, rem);
                    accLen = rem;
                }
            }
            if (accLen > 0) {
                byte[] dec = Base64.decode(acc, 0, accLen, Base64.DEFAULT);
                out.write(dec);
                if (md != null) md.update(dec);
                written += dec.length;
                if (chunkPl != null) chunkPl.onProgress(dec.length, 0);
            }
        } finally {
            try {
                out.close();
            } catch (Exception ignored) {
            }
            try {
                in.close();
            } catch (Exception ignored) {
            }
            c.disconnect();
        }
        return written;
    }

    /** 在 JSON 字节流里定位 "content" 的值起始引号之后；成功返回 true */
    private static boolean seekContentValue(ByteReader br) throws Exception {
        String key = "\"content\"";
        int matched = 0;
        int ch;
        while ((ch = br.read()) >= 0) {
            if (ch == key.charAt(matched)) {
                matched++;
                if (matched == key.length()) break;
            } else {
                matched = ch == '"' ? 1 : 0;
            }
        }
        if (matched < key.length()) return false;
        // 跳过冒号与空白，停在值引号之后
        while ((ch = br.read()) >= 0) {
            if (ch == '"') return true;
        }
        return false;
    }

    /** 带缓冲的逐字节读取（只用于 JSON 头部定位与 base64 串扫描，吞吐足够） */
    private static class ByteReader {
        private final InputStream in;
        private final byte[] buf = new byte[32768];
        private int pos = 0;
        private int len = 0;

        ByteReader(InputStream in) {
            this.in = in;
        }

        int read() throws Exception {
            if (pos >= len) {
                len = in.read(buf);
                pos = 0;
                if (len < 0) return -1;
            }
            return buf[pos++] & 0xFF;
        }
    }

    private static String encodePath(String path) throws Exception {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    // ---------- 小工具 ----------
    static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(buf, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String readText(InputStream in) {
        if (in == null) return null;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimMsg(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    private static String readableError(Exception e) {
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : "网络或服务器异常，请重试";
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        return sb.toString();
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private static void copyRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists()) dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyRecursive(k, new File(dst, k.getName()));
            return;
        }
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    private static boolean isUnder(File f, File dir) {
        String fp = f.getAbsolutePath();
        String dp = dir.getAbsolutePath();
        return fp.equals(dp) || fp.startsWith(dp + File.separator);
    }

    private static long fileDurationMs(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) return Long.parseLong(d);
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }
}
