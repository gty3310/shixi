package com.ti.songdu.recite;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * v1.16.0 识读模型仓：sherpa-onnx 流式 Zipformer（中文 14M，int8）四个文件，
 * 共约 25.4MB——不进 APK（APK 只涨 .so 的约 27MB），首次开启识读时按需下载到
 * files/asr-model/，口径同在线书库（下 .part、验 md5、再改名）。
 *
 * 模型来源：HuggingFace csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23
 * 主源 huggingface.co，备源 hf-mirror.com（大陆直连 HF 不稳时自动切换）。
 * md5 为 spike 阶段实测值（2026-10-24 与官方文件逐一核对）。
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
     * 确保模型就绪（阻塞，请在后台线程调用）：逐个下载缺失文件，每个文件
     * 先试主源、失败换备源；下完验 size＋md5，不过删掉报错。
     */
    public static void ensure(Context ctx, Callback cb) {
        File d = dir(ctx);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        long doneBase = presentBytes(ctx);
        try {
            for (ModelFile f : FILES) {
                File pf = file(ctx, f.name);
                if (pf.exists() && pf.length() == f.size) continue;
                downloadOne(f, pf, doneBase, cb);
                doneBase += f.size;
            }
            if (!isReady(ctx)) throw new IllegalStateException("模型文件不完整");
            if (cb != null) cb.onDone();
        } catch (Exception e) {
            if (cb != null) cb.onError(e.getMessage() == null ? "下载失败" : e.getMessage());
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
