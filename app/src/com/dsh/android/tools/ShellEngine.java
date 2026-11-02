package com.dsh.android.tools;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 命令执行：通过系统的 {@code /system/bin/sh} 跑命令。
 *
 * <p>不引入 rootfs 或 proot——那会让安装包涨十几兆，还要按 ABI 附带原生库。
 * 系统自带的 sh + toybox 已经覆盖 ls / cat / ps / df / getprop / dumpsys 等常用命令，
 * 对"查看设备状态"这个场景足够了。
 *
 * <p>权限边界：以本应用身份运行，只能访问应用有权访问的路径；
 * 没有 root 就做不了需要 root 的事，这是 Android 的沙箱决定的，不是限制做少了。
 */
public final class ShellEngine {

    private static final int MAX_OUTPUT = 20000;
    private static final int DEFAULT_TIMEOUT = 20000;

    private ShellEngine() {}

    public static final class Result {
        public int exit = -1;
        public String out = "";
        public String err = "";
        public boolean timeout;
        public String error;
    }

    public static Result run(String command, int timeoutMs) {
        Result r = new Result();
        if (command == null || command.trim().length() == 0) {
            r.error = "命令为空";
            return r;
        }
        int timeout = timeoutMs <= 0 ? DEFAULT_TIMEOUT : Math.min(timeoutMs, 120000);

        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(false)
                    .start();
            final Process proc = p;
            final long deadline = System.currentTimeMillis() + timeout;

            // 两个流都要读，否则输出一多就会卡住
            final String[] outHolder = new String[1];
            final String[] errHolder = new String[1];
            Thread t1 = new Thread(new Runnable() {
                public void run() { outHolder[0] = read(proc.getInputStream()); }
            });
            Thread t2 = new Thread(new Runnable() {
                public void run() { errHolder[0] = read(proc.getErrorStream()); }
            });
            t1.start();
            t2.start();

            while (true) {
                try {
                    r.exit = proc.exitValue();
                    break;
                } catch (IllegalThreadStateException e) {
                    if (System.currentTimeMillis() > deadline) {
                        r.timeout = true;
                        proc.destroy();
                        break;
                    }
                    Thread.sleep(50);
                }
            }
            t1.join(2000);
            t2.join(2000);
            r.out = clip(outHolder[0]);
            r.err = clip(errHolder[0]);
            return r;
        } catch (Exception e) {
            r.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return r;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static String read(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                if (total < MAX_OUTPUT * 2) {
                    bos.write(buf, 0, n);
                    total += n;
                }
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_OUTPUT) return s;
        return s.substring(0, MAX_OUTPUT) + "\n…（已截断）";
    }
}
