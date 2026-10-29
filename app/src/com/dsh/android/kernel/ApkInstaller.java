package com.dsh.android.kernel;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内更新：下载 APK → 交给系统安装器弹出安装确认。
 *
 * <p>目标是一步到底：点「安装更新」就开始下载，下完直接弹系统安装界面，
 * 不用跳浏览器、不用自己去文件管理器里找。
 *
 * <p>为什么用 {@link PackageInstaller} 而不是 {@code Intent.ACTION_VIEW}：
 * 后者从 Android 7 起不再接受 {@code file://}，走 {@code content://} 需要额外带一个
 * FileProvider（零依赖构建里没有 androidx）。PackageInstaller 只要写会话 + commit，
 * 系统自己保证权限与校验。
 */
public final class ApkInstaller {

    public interface Progress {
        void onProgress(long done, long total);
    }

    public interface Callback {
        /** err 为 null 表示成功。 */
        void onDone(String err);
    }

    private static final int MAX_APK = 64 * 1024 * 1024;
    private static final String ACTION_RESULT = "com.dsh.android.INSTALL_RESULT";

    private ApkInstaller() {}

    /** 下载 APK 到 cache/apk/update.apk。阻塞调用，放后台线程。 */
    public static File download(Context ctx, String url, Progress cb) throws Exception {
        File dir = new File(ctx.getCacheDir(), "apk");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建下载目录");
        File out = new File(dir, "update.apk");
        if (out.exists() && !out.delete()) throw new Exception("无法覆盖旧的安装包");

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "DShAndroid/" + C.VERSION);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("下载失败 HTTP " + code);
            long total = c.getContentLength();

            InputStream in = c.getInputStream();
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[16384];
                int n;
                long done = 0;
                int lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    done += n;
                    if (done > MAX_APK) throw new Exception("安装包超过 64MB，已中止");
                    fos.write(buf, 0, n);
                    if (cb != null && total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            cb.onProgress(done, total);
                        }
                    }
                }
                fos.flush();
            } finally {
                try { fos.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
            }
            if (out.length() < 1024) throw new Exception("下载内容不完整");
            return out;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 是否已获得「安装未知应用」权限（Android 8+）。 */
    public static boolean canInstall(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        try {
            return ctx.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 用系统安装器安装：写会话 → 注册结果监听 → commit。
     * 回调在主线程；Android 8+ 首次需要用户允许「安装未知应用」。
     */
    public static void install(final Context ctx, final File apk, final Callback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        if (!canInstall(ctx)) {
            ui.post(new Runnable() {
                public void run() {
                    if (cb != null) {
                        cb.onDone("需要先允许「安装未知应用」");
                    }
                }
            });
            return;
        }

        new Thread(new Runnable() {
            public void run() {
                String err = null;
                int sessionId = -1;
                PackageInstaller.Session session = null;
                try {
                    PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams params =
                            new PackageInstaller.SessionParams(
                                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    params.setAppPackageName(ctx.getPackageName());
                    sessionId = pi.createSession(params);
                    session = pi.openSession(sessionId);

                    long size = apk.length();
                    OutputStream os = session.openWrite("dsh-update.apk", 0, size);
                    InputStream in = new FileInputStream(apk);
                    try {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                        session.fsync(os);
                    } finally {
                        try { os.close(); } catch (Exception ignored) {}
                        try { in.close(); } catch (Exception ignored) {}
                    }

                    // 先挂监听再 commit，否则可能漏掉很快返回的结果
                    watchResult(ctx, sessionId, cb);
                    commit(ctx, session, sessionId);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                } finally {
                    if (session != null) {
                        try { session.close(); } catch (Exception ignored) {}
                    }
                }
                if (err != null) {
                    final String fe = err;
                    ui.post(new Runnable() {
                        public void run() {
                            if (cb != null) cb.onDone(fe);
                        }
                    });
                }
            }
        }, "apk-install").start();
    }

    private static void commit(Context ctx, PackageInstaller.Session session, int sessionId)
            throws Exception {
        Intent i = new Intent(ACTION_RESULT).setPackage(ctx.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(ctx, sessionId, i,
                PendingIntent.FLAG_UPDATE_CURRENT);
        session.commit(pi.getIntentSender());
    }

    /** 监听一次安装结果（进程内）。 */
    private static void watchResult(Context ctx, final int sessionId, final Callback cb) {
        final BroadcastReceiver[] holder = new BroadcastReceiver[1];
        holder[0] = new BroadcastReceiver() {
            public void onReceive(Context c, Intent intent) {
                String msg = null;
                try {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                    String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        msg = detail == null ? "安装未完成（状态 " + status + "）" : detail;
                    }
                } catch (Throwable t) {
                    msg = "安装结果读取失败";
                }
                try {
                    c.unregisterReceiver(holder[0]);
                } catch (Throwable ignored) {}
                if (cb != null) cb.onDone(msg);
            }
        };
        IntentFilter f = new IntentFilter(ACTION_RESULT);
        // API 33 的 RECEIVER_NOT_EXPORTED = 0x4；编译用的是 platform-29 存根，只能写字面量
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(holder[0], f, 0x4);
        } else {
            ctx.registerReceiver(holder[0], f);
        }
    }
}
