package com.ti.songdu;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * v1.22.0 更新包文件共享（框架版 FileProvider 等价物）。
 *
 * 本工程零第三方依赖、不引入 androidx，故不用 androidx 的 FileProvider 类，
 * 改用框架 ContentProvider 实现同一件事：把 cacheDir/updates/ 下的 APK 以
 * content:// 临时授权（grantUriPermissions）交给系统安装器读。exported=false，
 * 只认 updates 目录内的文件，路径穿越（../）按规范化路径前缀校验挡下。
 */
public class UpdateFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.ti.songdu.updatefileprovider";

    /** 更新包存放目录（AppUpdater 下载与本类共用同一口径） */
    public static File updatesDir(android.content.Context c) {
        return new File(c.getCacheDir(), "updates");
    }

    public static Uri uriForFile(android.content.Context c, File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(f.getName()).build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("no context");
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("bad path");
        }
        File dir = updatesDir(getContext());
        File f = new File(dir, name);
        try {
            String canonDir = dir.getCanonicalPath();
            if (!f.getCanonicalPath().startsWith(canonDir + File.separator)) {
                throw new FileNotFoundException("outside updates dir");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("canonical check failed");
        }
        if (!f.isFile()) throw new FileNotFoundException("not found: " + name);
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
