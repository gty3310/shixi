package com.ti.songdu.recite;

import android.content.Context;

import com.ti.songdu.Bookstore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * v1.16.0 识读模型仓：sherpa-onnx 流式 Zipformer（中文 14M，int8）四个文件，
 * 共约 25.4MB——不进 APK（APK 只涨 .so 的约 27MB），首次开启识读时按需下载到
 * files/asr-model/，口径同在线书库（下临时文件、验 md5、再改名）。
 *
 * v1.16.2 下载源改序：首选用户自己的 Gitee 私有仓 shixi-models——经 contents
 * API（与书库同一令牌机制）先取 model-manifest.json，再按清单下载：整文件
 * 逐件验 size＋md5；encoder 整件 21.6MB 超过 contents 通道单文件可传上限，
 * 仓内以 2MiB 分片存放，按 manifest 的 delivery 段逐片下载、逐片验 md5、
 * 按序拼合后验整件 size＋md5。Gitee 路线任一环节重试仍失败时，回退
 * HuggingFace 主源 huggingface.co／备源 hf-mirror.com 的整文件路线（原实现），
 * 保证识读在任何网络下仍可开通。仓内清单与内置校验值（FILES 的 size/md5）
 * 对不上即视为该路线失败，不用清单值覆盖内置校验。
 */
public final class AsrModelStore {

    public static final class ModelFile {
        public final String name;
        public final long size;
        public final String md5;

        ModelFile(String name, long size, String md5) {
            this.name = name;
            this.size = size;
            this.md5 = md5;
        }
    }

    public static final ModelFile[] FILES = {
            new ModelFile("encoder-epoch-99-avg-1.int8.onnx", 21621684L, "c9ecbe3dfb3e621fd9456c25bf27c567"),
            new ModelFile("decoder-epoch-99-avg-1.int8.onnx", 1888682L, "2e9d02477ff9ed962947ffee2b8415e0"),
            new ModelFile("joiner-epoch-99-avg-1.int8.onnx", 1795562L, "bdd63b53f8da4f1da03cc13a0af567c1"),
            new ModelFile("tokens.txt", 48697L, "4c13b185ca1b5ab7df238a95cc891785"),
    };

    public static final long TOTAL_BYTES;

    static {
        long t = 0;
        for (ModelFile f : FILES) t += f.size;
        TOTAL_BYTES = t;
    }

    /** 首选源：用户 Gitee 私有模型仓（contents API，令牌同书库） */
    private static final String MODELS_REPO = "shixi-models";
    private static final String MANIFEST_PATH = "model-manifest.json";
    /** Gitee 路线单步尝试上限（清单/整文件/每一片）与重试间隔 */
    private static final int GITEE_ATTEMPTS = 3;
    private static final long[] GITEE_RETRY_DELAYS_MS = {2000, 5000};

    /** 兜底源：HuggingFace（模型上游） */
    private static final String REPO =
            "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23";
    private static final String[] BASES = {
            "https://huggingface.co/" + REPO + "/resolve/main/",
            "https://hf-mirror.com/" + REPO + "/resolve/main/",
    };

    public interface Callback {
        /** done/total 为全模型合计字节 */
        void onProgress(long done, long total);

        void onDone();

        void onError(String message);
    }

    public static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "asr-model");
    }

    public static File file(Context ctx, String name) {
        return new File(dir(ctx), name);
    }

    /** 就绪＝四文件都在且字节数对（md5 在下载完成时已验，启动只查大小） */
    public static boolean isReady(Context ctx) {
        for (ModelFile f : FILES) {
            File pf = file(ctx, f.name);
            if (!pf.exists() || pf.length() != f.size) return false;
        }
        return true;
    }

    /** 缺失/不对的字节数（用于下载前估算与进度分母修正） */
    public static long missingBytes(Context ctx) {
        long done = 0;
        for (ModelFile f : FILES) {
            File pf = file(ctx, f.name);
            if (pf.exists() && pf.length() == f.size) done += f.size;
        }
        return TOTAL_BYTES - done;
    }

    public static long presentBytes(Context ctx) {
        return TOTAL_BYTES - missingBytes(ctx);
    }

    /**
     * 确保模型就绪（阻塞，请在后台线程调用）：先走 Gitee 模型仓路线；整条
     * 路线失败（清单取不到/对不上、文件多次重试仍下不全）再回退 HF 路线。
     * 两条路线都只补缺失文件、逐件验 size＋md5，已就绪的文件不重下。
     */
    public static void ensure(Context ctx, Callback cb) {
        File d = dir(ctx);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        try {
            ensureFromGitee(ctx, cb);
        } catch (Exception ignored) {
            // 落到下面的 HF 兜底；已由 Gitee 路线下好的文件按大小直接复用
        }
        if (isReady(ctx)) {
            if (cb != null) cb.onDone();
            return;
        }
        try {
            ensureFromHf(ctx, cb);
            if (!isReady(ctx)) throw new IllegalStateException("模型文件不完整");
            if (cb != null) cb.onDone();
        } catch (Exception e) {
            if (cb != null) cb.onError(e.getMessage() == null ? "下载失败" : e.getMessage());
        }
    }

    // ---------- 首选：Gitee 模型仓（manifest 驱动） ----------

    private static void ensureFromGitee(Context ctx, Callback cb) throws Exception {
        JSONObject manifest = fetchManifest(ctx);
        // 清单 files 段按文件名建索引，并逐件与内置校验值核对——清单只是
        // 取货单，校验仍以内置 FILES 为准，对不上即整条路线失败、走兜底。
        Map<String, JSONObject> byName = new HashMap<>();
        JSONArray files = manifest.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject o = files.getJSONObject(i);
            String path = o.getString("path");
            byName.put(path.substring(path.lastIndexOf('/') + 1), o);
        }
        JSONObject delivery = manifest.optJSONObject("delivery");
        final long[] progress = {presentBytes(ctx)};
        final long[] attemptDelta = {0};
        Bookstore.ProgressListener chunk = new Bookstore.ProgressListener() {
            @Override
            public void onProgress(long d, long t) {
                progress[0] += d;
                attemptDelta[0] += d;
                if (cb != null) {
                    cb.onProgress(Math.min(progress[0], TOTAL_BYTES), TOTAL_BYTES);
                }
            }

            @Override
            public void onDone(boolean ok, String error) {
            }

            @Override
            public void onFileRetry(String path, int attempt, int maxAttempts, String reason) {
            }
        };
        for (ModelFile f : FILES) {
            File dest = file(ctx, f.name);
            if (dest.exists() && dest.length() == f.size) continue;
            JSONObject fe = byName.get(f.name);
            if (fe == null || fe.getLong("size") != f.size
                    || !f.md5.equalsIgnoreCase(fe.optString("md5", ""))) {
                throw new IllegalStateException("模型清单与内置校验值不一致：" + f.name);
            }
            JSONObject del = delivery == null ? null : delivery.optJSONObject(f.name);
            if (del != null && "split_parts_contents_api".equals(del.optString("method"))) {
                fetchPartsAssemble(ctx, f, del, dest, progress, attemptDelta, chunk, cb);
            } else {
                File tmp = new File(dir(ctx), f.name + ".dl");
                fetchVerifiedGitee(fe.getString("path"), tmp, f.size, f.md5,
                        progress, attemptDelta, chunk, cb);
                renameInto(tmp, dest);
            }
        }
    }

    private static JSONObject fetchManifest(Context ctx) throws Exception {
        File tmp = new File(ctx.getCacheDir(), "asr_model_manifest.json");
        Exception last = null;
        for (int attempt = 1; attempt <= GITEE_ATTEMPTS; attempt++) {
            if (attempt > 1) sleepGitee(GITEE_RETRY_DELAYS_MS[attempt - 2]);
            try {
                Bookstore.downloadContentsFile(MODELS_REPO, MANIFEST_PATH, tmp, null, null);
                return new JSONObject(readTextFile(tmp));
            } catch (Exception e) {
                last = e;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        throw last != null ? last : new IllegalStateException("模型清单下载失败");
    }

    /**
     * encoder 的分片路线：逐片下载到临时文件、逐片验 size＋md5 后顺序追加进
     * 拼合文件；全片齐后验整件 size＋md5，再改名为正式文件。分片临时文件与
     * 拼合半成品在成功后不残留。
     */
    private static void fetchPartsAssemble(Context ctx, ModelFile f, JSONObject del,
                                           File dest, long[] progress, long[] attemptDelta,
                                           Bookstore.ProgressListener chunk, Callback cb)
            throws Exception {
        long assembledSize = del.getLong("assembled_size");
        String assembledMd5 = del.getString("assembled_md5");
        if (assembledSize != f.size || !f.md5.equalsIgnoreCase(assembledMd5)) {
            throw new IllegalStateException("模型清单与内置校验值不一致：" + f.name);
        }
        JSONArray parts = del.getJSONArray("parts");
        File assembly = new File(dir(ctx), f.name + ".part");
        File seg = new File(dir(ctx), f.name + ".seg");
        FileOutputStream out = new FileOutputStream(assembly, false);
        try {
            byte[] buf = new byte[65536];
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.getJSONObject(i);
                fetchVerifiedGitee(p.getString("path"), seg, p.getLong("size"),
                        p.optString("md5", null), progress, attemptDelta, chunk, cb);
                FileInputStream in = new FileInputStream(seg);
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                in.close();
                //noinspection ResultOfMethodCallIgnored
                seg.delete();
            }
        } finally {
            out.close();
        }
        if (assembly.length() != assembledSize) {
            //noinspection ResultOfMethodCallIgnored
            assembly.delete();
            throw new IllegalStateException("拼合大小不对：" + f.name);
        }
        if (!assembledMd5.equalsIgnoreCase(md5Of(assembly))) {
            //noinspection ResultOfMethodCallIgnored
            assembly.delete();
            throw new IllegalStateException("拼合校验失败：" + f.name);
        }
        renameInto(assembly, dest);
    }

    /**
     * 经 contents API 取仓内一个文件到 tmp，边下边验 size＋md5；失败重试
     * （间隔递增），重试前把本次已上报的进度回退，避免重复计数。
     */
    private static void fetchVerifiedGitee(String repoPath, File tmp, long size, String md5,
                                           long[] progress, long[] attemptDelta,
                                           Bookstore.ProgressListener chunk, Callback cb)
            throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= GITEE_ATTEMPTS; attempt++) {
            if (attempt > 1) sleepGitee(GITEE_RETRY_DELAYS_MS[attempt - 2]);
            attemptDelta[0] = 0;
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                long written = Bookstore.downloadContentsFile(MODELS_REPO, repoPath, tmp, md, chunk);
                if (written != size) {
                    throw new IllegalStateException(
                            "大小不对：" + repoPath + " " + written + "/" + size);
                }
                if (md5 != null && !toHex(md.digest()).equalsIgnoreCase(md5)) {
                    throw new IllegalStateException("校验失败：" + repoPath);
                }
                return;
            } catch (Exception e) {
                last = e;
                progress[0] = Math.max(0, progress[0] - attemptDelta[0]);
                if (cb != null) {
                    cb.onProgress(Math.min(progress[0], TOTAL_BYTES), TOTAL_BYTES);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        throw last != null ? last : new IllegalStateException("下载失败：" + repoPath);
    }

    private static void renameInto(File tmp, File dest) throws Exception {
        if (dest.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
        }
        if (!tmp.renameTo(dest)) {
            throw new IllegalStateException("落盘失败：" + dest.getName());
        }
    }

    private static void sleepGitee(long ms) throws Exception {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("下载被中断");
        }
    }

    private static String readTextFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    // ---------- 兜底：HuggingFace 整文件路线（v1.16.0 原实现） ----------

    private static void ensureFromHf(Context ctx, Callback cb) throws Exception {
        long doneBase = presentBytes(ctx);
        for (ModelFile f : FILES) {
            File pf = file(ctx, f.name);
            if (pf.exists() && pf.length() == f.size) continue;
            downloadOne(f, pf, doneBase, cb);
            doneBase += f.size;
        }
    }

    private static void downloadOne(ModelFile f, File dest, long doneBase, Callback cb)
            throws Exception {
        Exception last = null;
        for (String base : BASES) {
            try {
                downloadFrom(base + f.name, f, dest, doneBase, cb);
                return;
            } catch (Exception e) {
                last = e;
                //noinspection ResultOfMethodCallIgnored
                new File(dest.getParentFile(), f.name + ".part").delete();
            }
        }
        throw last != null ? last : new IllegalStateException("下载失败：" + f.name);
    }

    private static void downloadFrom(String urlStr, ModelFile f, File dest,
                                     long doneBase, Callback cb) throws Exception {
        File part = new File(dest.getParentFile(), f.name + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code / 100 != 2) throw new IllegalStateException("HTTP " + code);
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(part);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[65536];
            long got = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                got += n;
                if (cb != null) cb.onProgress(doneBase + got, TOTAL_BYTES);
            }
            out.close();
            in.close();
            if (got != f.size) {
                throw new IllegalStateException("大小不对：" + f.name + " " + got + "/" + f.size);
            }
            String hex = toHex(md.digest());
            if (!hex.equals(f.md5)) {
                throw new IllegalStateException("校验失败：" + f.name);
            }
            if (dest.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
            if (!part.renameTo(dest)) {
                throw new IllegalStateException("落盘失败：" + f.name);
            }
        } finally {
            conn.disconnect();
        }
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /** md5 工具（fetch 校验/调试用） */
    public static String md5Of(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
            in.close();
            return toHex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }
}
