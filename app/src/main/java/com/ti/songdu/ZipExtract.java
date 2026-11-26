package com.ti.songdu;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 纯 Java 解压（仅依赖 java.util.zip，桌面 harness 可直驱）：把 zip 解到
 * 目标目录，逐条校验条目路径不得逃出目标目录（zip-slip 防护，包来自
 * 云端、按不可信输入处理）。返回解出的文件数。
 */
public final class ZipExtract {

    private ZipExtract() {
    }

    public static int extract(File zipFile, File destDir) throws Exception {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IllegalStateException("无法创建目录：" + destDir);
        }
        String destCanon = destDir.getCanonicalPath();
        int count = 0;
        byte[] buf = new byte[65536];
        InputStream fis = new FileInputStream(zipFile);
        try {
            ZipInputStream zis = new ZipInputStream(fis);
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                File out = new File(destDir, e.getName());
                String canon = out.getCanonicalPath();
                if (!canon.equals(destCanon) && !canon.startsWith(destCanon + File.separator)) {
                    throw new IllegalStateException("压缩包条目路径越界：" + e.getName());
                }
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                if (out.getParentFile() != null) {
                    //noinspection ResultOfMethodCallIgnored
                    out.getParentFile().mkdirs();
                }
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    int n;
                    while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                } finally {
                    fos.close();
                }
                count++;
            }
            zis.close();
        } finally {
            fis.close();
        }
        return count;
    }
}
