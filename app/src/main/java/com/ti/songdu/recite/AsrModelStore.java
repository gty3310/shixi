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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 识读模型仓：基础版＝sherpa-onnx 流式 Zipformer（中文 14M，int8）四文件约
 * 25.4MB；高级版＝SenseVoice 离线大模型两文件约 239.5MB。都不进 APK，首次
 * 开启识读时按需下载到 files/asr-model/。
 *
 * 下载源：用户 Gitee 私有仓 shixi-models（令牌同书库）——经 git trees API
 * 拿 path→sha 索引，再经 git blobs API 按 sha 取 model-manifest.json 与各
 * 文件：整文件逐件验 size＋md5；大文件仓内以 2MiB 分片存放，逐片下载、逐
 * 片验 md5、按序拼合后验整件 size＋md5。清单值与内置校验值（FILES/
 * SV_FILES）对不上即视为该路线失败，绝不用清单覆盖内置校验。基础版 Gitee
 * 路线失败时回退 HuggingFace 整文件路线（v1.16.0 原兜底）。
 * 通道为何不用 contents API：v1.20.1 诊断实测——contents 对个别 2MiB 分片
 * 定点截断（part007 三次尝试都恰好少 187,678 字节，确定性失败、重试无用），
 * 同一片经 git blob 完整通过。书库小文件不受影响、仍走 contents。
 *
 * v1.20.1 健壮性重做（真机 239MB 下载失败的教训）：
 *  - 分片断点续传：已验过的分片落盘在 parts-<文件名>/ 里，失败/取消/重试都
 *    复用，只补缺片；旧版每次尝试把拼合文件截断从第 0 片重来，任一片网络
 *    抖动即整单作废，239MB 几乎下不完。
 *  - 进度按所选集合的真实字节计：total＝待下文件总字节（已落盘分片先行计
 *    入 done），done 随每块解码字节推进、单片重试时回退本次增量，不虚报。
 *  - 活动下载状态对外发布（active＋StateListener），设置页状态行与下载对
 *    话框都读同一份状态；支持取消（片粒度生效）。
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

    public static final ModelFile[] SV_FILES = {
            new ModelFile("sensevoice-model.int8.onnx", 239233841L, "0e8e550411d3589be9f0af06f1c8fec3"),
            new ModelFile("sensevoice-tokens.txt", 315894L, "17cdd9d527315e28553153706d23c719"),
    };
    public static final long SV_TOTAL_BYTES;

    static {
        long t2 = 0;
        for (ModelFile f : SV_FILES) t2 += f.size;
        SV_TOTAL_BYTES = t2;
    }
    public static final String SV_MODEL_NAME = SV_FILES[0].name;
    public static final String SV_TOKENS_NAME = SV_FILES[1].name;

    private static final String MODELS_REPO = "shixi-models";
    private static final String MANIFEST_PATH = "model-manifest.json";
    private static final int GITEE_ATTEMPTS = 3;
    private static final long[] GITEE_RETRY_DELAYS_MS = {2000, 5000};

    private static final String REPO =
            "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23";
    private static final String[] BASES = {
            "https://huggingface.co/" + REPO + "/resolve/main/",
            "https://hf-mirror.com/" + REPO + "/resolve/main/",
    };

    public interface Callback {
        /** done/total 为所选集合的字节口径（已落盘分片计入 done） */
        void onProgress(long done, long total);

        void onDone();

        void onError(String message);
    }

    // ---------- 活动下载状态（UI 订阅） ----------

    public static final class DlState {
        public volatile boolean running;
        public volatile boolean wantBasic, wantSv;
        public volatile long done, total;
        public volatile boolean failed, cancelled;
        public volatile String error;
    }

    public static volatile DlState active;
    private static volatile boolean cancelRequested;

    public interface StateListener {
        void onState(DlState s);
    }

    private static final CopyOnWriteArrayList<StateListener> STATE_LISTENERS =
            new CopyOnWriteArrayList<>();

    public static void addStateListener(StateListener l) {
        if (!STATE_LISTENERS.contains(l)) STATE_LISTENERS.add(l);
        DlState s = active;
        if (s != null) l.onState(s);
    }

    public static void removeStateListener(StateListener l) {
        STATE_LISTENERS.remove(l);
    }

    private static void publishState() {
        DlState s = active;
        if (s == null) return;
        for (StateListener l : STATE_LISTENERS) l.onState(s);
    }

    public static boolean isDownloading() {
        DlState s = active;
        return s != null && s.running;
    }

    public static void cancelActiveDownload() {
        if (isDownloading()) cancelRequested = true;
    }

    private static final class Cancelled extends Exception {
    }

    private static void checkCancelled() throws Cancelled {
        if (cancelRequested) throw new Cancelled();
    }

    // ---------- 就绪判定 ----------

    public static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "asr-model");
    }

    public static File file(Context ctx, String name) {
        return new File(dir(ctx), name);
    }

    private static boolean fileReady(Context ctx, ModelFile f) {
        File pf = file(ctx, f.name);
        return pf.exists() && pf.length() == f.size;
    }

    /** 就绪＝四文件都在且字节数对（md5 在下载完成时已验，启动只查大小） */
    public static boolean isReady(Context ctx) {
        for (ModelFile f : FILES) if (!fileReady(ctx, f)) return false;
        return true;
    }

    public static boolean isSenseVoiceReady(Context ctx) {
        for (ModelFile f : SV_FILES) if (!fileReady(ctx, f)) return false;
        return true;
    }

    public static long presentBytes(Context ctx) {
        long done = 0;
        for (ModelFile f : FILES) if (fileReady(ctx, f)) done += f.size;
        return done;
    }

    public static long missingBytes(Context ctx) {
        return TOTAL_BYTES - presentBytes(ctx);
    }

    public static long svMissingBytes(Context ctx) {
        long done = 0;
        for (ModelFile f : SV_FILES) if (fileReady(ctx, f)) done += f.size;
        return SV_TOTAL_BYTES - done;
    }

    // ---------- 进度跟踪 ----------

    private static final class Tracker {
        final Callback cb;
        final DlState state;
        final long total;
        long done;
        long attemptDelta;

        Tracker(long credited, long total, Callback cb, DlState state) {
            this.done = credited;
            this.total = total;
            this.cb = cb;
            this.state = state;
        }

        synchronized void addDelta(long d) {
            done += d;
            attemptDelta += d;
            fire();
        }

        synchronized void rollbackAttempt() {
            done = Math.max(0, done - attemptDelta);
            attemptDelta = 0;
            fire();
        }

        synchronized void resetAttempt() {
            attemptDelta = 0;
        }

        synchronized void complete() {
            done = total;
            fire();
        }

        void fire() {
            long d, t;
            synchronized (this) {
                d = Math.min(done, total);
                t = total;
            }
            if (state != null) {
                state.done = d;
                state.total = t;
                publishState();
            }
            if (cb != null) cb.onProgress(d, t);
        }
    }

    /** Bookstore 分片进度 → Tracker 增量 */
    private static Bookstore.ProgressListener chunkTo(final Tracker tr) {
        return new Bookstore.ProgressListener() {
            @Override
            public void onProgress(long d, long t) {
                tr.addDelta(d);
            }

            @Override
            public void onDone(boolean ok, String error) {
            }

            @Override
            public void onFileRetry(String path, int attempt, int maxAttempts, String reason) {
            }
        };
    }

    // ---------- 下载计划 ----------

    private static final class FilePlan {
        ModelFile f;
        String repoPath;
        String sha; // git blob sha（trees 索引）
        Map<String, String> shaByPath; // 分片按 path 查 sha 用
        JSONArray parts; // null＝整文件
    }

    /**
     * 按选择集确保模型就绪（阻塞，请在后台线程调用）。已就绪文件不重下；
     * 分片文件复用已落盘的合格分片续传。
     */
    public static void ensureSet(Context ctx, boolean wantBasic, boolean wantSv, Callback cb) {
        DlState st = new DlState();
        st.running = true;
        st.wantBasic = wantBasic;
        st.wantSv = wantSv;
        active = st;
        cancelRequested = false;
        publishState();
        try {
            //noinspection ResultOfMethodCallIgnored
            dir(ctx).mkdirs();
            Map<String, String> shaByPath = null;
            JSONObject manifest = null;
            Exception manifestErr = null;
            try {
                shaByPath = fetchTreeMap(ctx);
                manifest = fetchManifest(ctx, shaByPath);
            } catch (Exception e) {
                manifestErr = e;
            }
            List<FilePlan> basicPlanList = new ArrayList<>();
            List<FilePlan> svPlanList = new ArrayList<>();
            boolean basicViaHf = false;
            if (wantBasic && !isReady(ctx)) {
                if (manifest != null) {
                    try {
                        basicPlanList.addAll(basicPlans(ctx, manifest, shaByPath));
                    } catch (Exception e) {
                        basicPlanList.clear();
                        basicViaHf = true;
                    }
                } else {
                    basicViaHf = true;
                }
            }
            if (wantSv && !isSenseVoiceReady(ctx)) {
                if (manifest == null) {
                    throw manifestErr != null ? manifestErr
                            : new IllegalStateException("模型清单下载失败");
                }
                svPlanList.addAll(svPlans(ctx, manifest, shaByPath));
            }
            long total = 0, credited = 0;
            for (FilePlan p : basicPlanList) {
                total += p.f.size;
                credited += creditedBytes(ctx, p);
            }
            for (FilePlan p : svPlanList) {
                total += p.f.size;
                credited += creditedBytes(ctx, p);
            }
            if (basicViaHf) {
                for (ModelFile f : FILES) if (!fileReady(ctx, f)) total += f.size;
            }
            Tracker tr = new Tracker(credited, total, cb, st);
            tr.fire();
            if (basicViaHf) {
                ensureBasicHf(ctx, tr);
            } else {
                try {
                    for (FilePlan p : basicPlanList) executePlan(ctx, p, tr);
                } catch (Cancelled c) {
                    throw c;
                } catch (Exception e) {
                    // Gitee 路线中途失败：基础版未落盘文件改走 HF 兜底（只补
                    // 缺失整文件；回退段进度可能短暂虚高，完成时由 complete 归位）
                    ensureBasicHf(ctx, tr);
                }
            }
            for (FilePlan p : svPlanList) executePlan(ctx, p, tr);
            if (wantBasic && !isReady(ctx)) throw new IllegalStateException("基础版模型不完整");
            if (wantSv && !isSenseVoiceReady(ctx)) throw new IllegalStateException("高级版模型不完整");
            tr.complete();
            st.running = false;
            publishState();
            if (cb != null) cb.onDone();
        } catch (Cancelled c) {
            st.running = false;
            st.cancelled = true;
            publishState();
            if (cb != null) cb.onError("已取消");
        } catch (Exception e) {
            st.running = false;
            st.failed = true;
            st.error = e.getMessage() == null ? "下载失败" : e.getMessage();
            publishState();
            if (cb != null) cb.onError(st.error);
        }
    }

    private static void executePlan(Context ctx, FilePlan p, Tracker tr) throws Exception {
        checkCancelled();
        if (p.parts != null) fetchSplit(ctx, p, tr);
        else fetchWhole(ctx, p, tr);
    }

    /** 基础版计划：清单 files 按 basename 索引，逐件与内置校验核对 */
    private static List<FilePlan> basicPlans(Context ctx, JSONObject manifest,
                                             Map<String, String> shaByPath) throws Exception {
        Map<String, JSONObject> byName = new HashMap<>();
        JSONArray files = manifest.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject o = files.getJSONObject(i);
            String path = o.getString("path");
            byName.put(path.substring(path.lastIndexOf('/') + 1), o);
        }
        JSONObject delivery = manifest.optJSONObject("delivery");
        List<FilePlan> out = new ArrayList<>();
        for (ModelFile f : FILES) {
            if (fileReady(ctx, f)) continue;
            JSONObject fe = byName.get(f.name);
            if (fe == null || fe.getLong("size") != f.size
                    || !f.md5.equalsIgnoreCase(fe.optString("md5", ""))) {
                throw new IllegalStateException("模型清单与内置校验值不一致：" + f.name);
            }
            FilePlan p = new FilePlan();
            p.f = f;
            p.repoPath = fe.getString("path");
            p.sha = shaByPath.get(p.repoPath);
            p.shaByPath = shaByPath;
            if (p.sha == null) {
                throw new IllegalStateException("仓内找不到文件：" + p.repoPath);
            }
            JSONObject del = delivery == null ? null : delivery.optJSONObject(f.name);
            if (del != null && "split_parts_contents_api".equals(del.optString("method"))) {
                long assembledSize = del.getLong("assembled_size");
                String assembledMd5 = del.getString("assembled_md5");
                if (assembledSize != f.size || !f.md5.equalsIgnoreCase(assembledMd5)) {
                    throw new IllegalStateException("模型清单与内置校验值不一致：" + f.name);
                }
                p.parts = del.getJSONArray("parts");
            }
            out.add(p);
        }
        return out;
    }

    /** 高级版计划：清单 sensevoice 键驱动，逐件与内置校验核对 */
    private static List<FilePlan> svPlans(Context ctx, JSONObject manifest,
                                          Map<String, String> shaByPath) throws Exception {
        JSONObject sv = manifest.optJSONObject("sensevoice");
        if (sv == null) throw new IllegalStateException("模型清单缺少高级版条目");
        Map<String, JSONObject> byName = new HashMap<>();
        JSONArray files = sv.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject o = files.getJSONObject(i);
            byName.put(o.getString("name"), o);
        }
        List<FilePlan> out = new ArrayList<>();
        for (ModelFile f : SV_FILES) {
            if (fileReady(ctx, f)) continue;
            JSONObject fe = byName.get(f.name);
            if (fe == null || fe.getLong("size") != f.size
                    || !f.md5.equalsIgnoreCase(fe.optString("md5", ""))) {
                throw new IllegalStateException("模型清单与内置校验值不一致：" + f.name);
            }
            FilePlan p = new FilePlan();
            p.f = f;
            p.repoPath = fe.getString("repo_path");
            p.sha = shaByPath.get(p.repoPath);
            p.shaByPath = shaByPath;
            if (p.sha == null) {
                throw new IllegalStateException("仓内找不到文件：" + p.repoPath);
            }
            JSONObject del = fe.optJSONObject("delivery");
            if (del != null && "split_parts_contents_api".equals(del.optString("method"))) {
                p.parts = del.getJSONArray("parts");
            }
            out.add(p);
        }
        return out;
    }

    // ---------- 分片落盘与续传 ----------

    private static File partsDir(Context ctx, ModelFile f) {
        return new File(dir(ctx), "parts-" + f.name);
    }

    private static File partFile(Context ctx, ModelFile f, int idx) {
        return new File(partsDir(ctx, f),
                String.format(java.util.Locale.US, "part%03d", idx));
    }

    private static boolean partValid(File pf, long size, String md5) {
        return pf.exists() && pf.length() == size
                && (md5 == null || md5.equalsIgnoreCase(md5Of(pf)));
    }

    /** 已落盘合格分片的字节数（进度先行计入） */
    private static long creditedBytes(Context ctx, FilePlan p) throws Exception {
        if (p.parts == null) return 0;
        long sum = 0;
        for (int i = 0; i < p.parts.length(); i++) {
            JSONObject part = p.parts.getJSONObject(i);
            if (partValid(partFile(ctx, p.f, i), part.getLong("size"),
                    part.optString("md5", null))) {
                sum += part.getLong("size");
            }
        }
        return sum;
    }

    private static void fetchWhole(Context ctx, FilePlan p, Tracker tr) throws Exception {
        File tmp = new File(dir(ctx), p.f.name + ".dl");
        fetchVerifiedBlob(p.sha, p.repoPath, tmp, p.f.size, p.f.md5, tr);
        renameInto(tmp, file(ctx, p.f.name));
    }

    /**
     * 分片文件：逐片取到 parts-<文件名>/partNNN 并逐片验 size＋md5（合格片
     * 保留，失败/取消后重入时复用）；全片齐后按序拼合、验整件、改名、清分片。
     */
    private static void fetchSplit(Context ctx, FilePlan p, Tracker tr) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        partsDir(ctx, p.f).mkdirs();
        for (int i = 0; i < p.parts.length(); i++) {
            checkCancelled();
            JSONObject part = p.parts.getJSONObject(i);
            long size = part.getLong("size");
            String md5 = part.optString("md5", null);
            File pf = partFile(ctx, p.f, i);
            if (partValid(pf, size, md5)) continue;
            String partPath = part.getString("path");
            fetchVerifiedBlob(p.shaByPath.get(partPath), partPath, pf, size, md5, tr);
        }
        File assembly = new File(dir(ctx), p.f.name + ".part");
        FileOutputStream out = new FileOutputStream(assembly, false);
        try {
            byte[] buf = new byte[65536];
            for (int i = 0; i < p.parts.length(); i++) {
                FileInputStream in = new FileInputStream(partFile(ctx, p.f, i));
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                in.close();
            }
        } finally {
            out.close();
        }
        if (assembly.length() != p.f.size) {
            //noinspection ResultOfMethodCallIgnored
            assembly.delete();
            throw new IllegalStateException("拼合大小不对：" + p.f.name);
        }
        if (!p.f.md5.equalsIgnoreCase(md5Of(assembly))) {
            //noinspection ResultOfMethodCallIgnored
            assembly.delete();
            throw new IllegalStateException("拼合校验失败：" + p.f.name);
        }
        renameInto(assembly, file(ctx, p.f.name));
        deleteRecursive(partsDir(ctx, p.f));
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---------- Gitee git blob 取单个文件（验 size＋md5，失败重试） ----------

    private static void fetchVerifiedBlob(String sha, String repoPath, File tmp, long size,
                                          String md5, Tracker tr) throws Exception {
        if (sha == null) {
            throw new IllegalStateException("仓内找不到文件：" + repoPath);
        }
        Exception last = null;
        for (int attempt = 1; attempt <= GITEE_ATTEMPTS; attempt++) {
            checkCancelled();
            if (attempt > 1) sleepGitee(GITEE_RETRY_DELAYS_MS[attempt - 2]);
            tr.resetAttempt();
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                long written = Bookstore.downloadBlobFile(
                        MODELS_REPO, sha, tmp, md, chunkTo(tr));
                if (written != size) {
                    throw new IllegalStateException(
                            "大小不对：" + repoPath + " " + written + "/" + size);
                }
                if (md5 != null && !toHex(md.digest()).equalsIgnoreCase(md5)) {
                    throw new IllegalStateException("校验失败：" + repoPath);
                }
                tr.resetAttempt();
                return;
            } catch (Cancelled c) {
                tr.rollbackAttempt();
                throw c;
            } catch (Exception e) {
                last = e;
                tr.rollbackAttempt();
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        throw last != null ? last : new IllegalStateException("下载失败：" + repoPath);
    }

    /** git trees 索引：根树＋一层子树，建 path→blob sha 全表（小 JSON，带重试） */
    private static Map<String, String> fetchTreeMap(Context ctx) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= GITEE_ATTEMPTS; attempt++) {
            checkCancelled();
            if (attempt > 1) sleepGitee(GITEE_RETRY_DELAYS_MS[attempt - 2]);
            try {
                Map<String, String> map = new HashMap<>();
                JSONObject root = fetchTreeJson("master");
                collectTree(root, "", map, true);
                return map;
            } catch (Cancelled c) {
                throw c;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("仓内文件索引获取失败");
    }

    private static void collectTree(JSONObject tree, String prefix,
                                    Map<String, String> map, boolean top) throws Exception {
        JSONArray arr = tree.getJSONArray("tree");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            String path = prefix + e.getString("path");
            String type = e.optString("type", "blob");
            if ("tree".equals(type)) {
                if (top) collectTree(fetchTreeJson(e.getString("sha")), path + "/", map, false);
            } else {
                map.put(path, e.getString("sha"));
            }
        }
    }

    private static JSONObject fetchTreeJson(String ref) throws Exception {
        String token = com.ti.songdu.StoreConfig.TOKEN;
        if (token == null || token.isEmpty()) throw new Exception("缺少书库令牌（构建时未注入）");
        String url = com.ti.songdu.StoreConfig.API_BASE + "/repos/"
                + com.ti.songdu.StoreConfig.OWNER + "/" + MODELS_REPO
                + "/git/trees/" + ref
                + "?access_token=" + java.net.URLEncoder.encode(token, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Shixi-Android");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("文件索引获取失败（HTTP " + code + "）");
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            in.close();
            return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    /** 清单改走 blob 通道（按 trees 索引的 sha 取，与分片同一条已验证通道） */
    private static JSONObject fetchManifest(Context ctx, Map<String, String> shaByPath)
            throws Exception {
        String sha = shaByPath.get(MANIFEST_PATH);
        if (sha == null) throw new IllegalStateException("仓内找不到模型清单");
        File tmp = new File(ctx.getCacheDir(), "asr_model_manifest.json");
        Exception last = null;
        for (int attempt = 1; attempt <= GITEE_ATTEMPTS; attempt++) {
            checkCancelled();
            if (attempt > 1) sleepGitee(GITEE_RETRY_DELAYS_MS[attempt - 2]);
            try {
                Bookstore.downloadBlobFile(MODELS_REPO, sha, tmp, null, null);
                return new JSONObject(readTextFile(tmp));
            } catch (Cancelled c) {
                throw c;
            } catch (Exception e) {
                last = e;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        throw last != null ? last : new IllegalStateException("模型清单下载失败");
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

    // ---------- 兜底：HuggingFace 整文件路线（基础版 Gitee 路线失败时） ----------

    private static void ensureBasicHf(Context ctx, Tracker tr) throws Exception {
        for (ModelFile f : FILES) {
            if (fileReady(ctx, f)) continue;
            checkCancelled();
            downloadOneHf(ctx, f, tr);
        }
    }

    private static void downloadOneHf(Context ctx, ModelFile f, Tracker tr) throws Exception {
        Exception last = null;
        for (String base : BASES) {
            checkCancelled();
            tr.resetAttempt();
            try {
                downloadFromHf(base + f.name, ctx, f, tr);
                return;
            } catch (Cancelled c) {
                tr.rollbackAttempt();
                throw c;
            } catch (Exception e) {
                last = e;
                tr.rollbackAttempt();
                //noinspection ResultOfMethodCallIgnored
                new File(dir(ctx), f.name + ".part").delete();
            }
        }
        throw last != null ? last : new IllegalStateException("下载失败：" + f.name);
    }

    private static void downloadFromHf(String urlStr, Context ctx, ModelFile f, Tracker tr)
            throws Exception {
        File dest = file(ctx, f.name);
        File part = new File(dir(ctx), f.name + ".part");
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
                tr.addDelta(n);
            }
            out.close();
            in.close();
            if (got != f.size) {
                throw new IllegalStateException("大小不对：" + f.name + " " + got + "/" + f.size);
            }
            if (!toHex(md.digest()).equals(f.md5)) {
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
