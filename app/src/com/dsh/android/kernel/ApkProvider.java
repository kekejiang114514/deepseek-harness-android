package com.dsh.android.kernel;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 FileProvider：把 cache/apk/ 下的安装包以 {@code content://} 暴露给系统安装器。
 *
 * <p>为什么要自己写：Android 7 起 {@code Intent.ACTION_VIEW} 不再接受 {@code file://}，
 * 必须给 {@code content://} + 临时读权限；而官方 FileProvider 在 androidx 里，
 * 这套零依赖构建装不进来。这里只需要「读一个 APK」这一件事，几十行就够。
 *
 * <p>安全：只允许访问 cacheDir/apk/ 下的文件，路径里出现 {@code ..} 直接拒绝，
 * provider 本身 {@code exported=false} + {@code grantUriPermissions=true}，
 * 只有拿到临时授权的应用（就是系统安装器）能读。
 */
public final class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.dsh.android.apk";

    /** content://com.dsh.android.apk/<文件名> */
    public static Uri uriFor(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + name);
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0 || name.contains("..") || name.contains("/")) {
            throw new FileNotFoundException("非法路径");
        }
        File dir = new File(getContext().getCacheDir(), "apk");
        File f = new File(dir, name);
        try {
            String canonical = f.getCanonicalPath();
            if (!canonical.startsWith(dir.getCanonicalPath() + File.separator)) {
                throw new FileNotFoundException("路径越界");
            }
        } catch (Exception e) {
            throw new FileNotFoundException("路径解析失败");
        }
        if (!f.isFile()) throw new FileNotFoundException("文件不存在");
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new FileNotFoundException("只读");
        }
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File f = fileFor(uri);
            String[] cols = projection != null ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor c = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
                else row[i] = f.getName();
            }
            c.addRow(row);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("只读");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("只读");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("只读");
    }
}
