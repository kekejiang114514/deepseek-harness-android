package com.dsh.android.market;

import android.content.Context;

import com.dsh.android.plugins.PluginStore;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;

/**
 * 一键安装：查 tarball → 下载 → 解包 → 落到插件目录。
 *
 * <p>「无感」的意思是：用户点一下，插件出现在插件列表里并已启用，
 * 不需要自己去 GitHub 找包、解压、拷目录、改文件名。
 */
public final class Installer {

    private static final int MAX_TGZ = 20 * 1024 * 1024;

    public static final class Result {
        public boolean ok;
        public String error;
        public String dir;
        public String entry;
    }

    private final Context ctx;
    private final PluginStore store;

    public Installer(Context ctx, PluginStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    /** 安装一个 npm 包形式的插件。阻塞调用，放后台线程。 */
    public Result install(String npm, String version) {
        Result r = new Result();
        try {
            String tarball = MarketClient.tarballOf(npm, version);
            byte[] tgz = MarketClient.downloadBytes(tarball, MAX_TGZ);

            List<Tgz.Entry> entries = Tgz.read(new ByteArrayInputStream(tgz));
            if (entries.isEmpty()) {
                r.error = "包里没有文件";
                return r;
            }

            File dir = new File(store.ensureDirs(), slugOf(npm));
            if (dir.exists()) deleteRecursive(dir);

            int files = Tgz.extract(entries, dir);
            r.dir = dir.getAbsolutePath();

            File entry = findEntry(dir);
            if (entry == null) {
                r.error = "包里没有找到插件入口（plugin.js / index.js / package.json main）";
                deleteRecursive(dir);
                return r;
            }
            r.entry = entry.getName();
            r.ok = files > 0;
            if (!r.ok) r.error = "解包后没有写入任何文件";
            return r;
        } catch (Exception e) {
            r.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return r;
        }
    }

    /** 判断某个包是否已经装过（按目录名）。 */
    public boolean isInstalled(String npm) {
        return new File(store.ensureDirs(), slugOf(npm)).isDirectory();
    }

    public static String slugOf(String npm) {
        String s = npm;
        if (s.startsWith("@")) s = s.substring(1);
        s = s.replace('/', '-').replace("..", "_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            sb.append(ok ? c : '_');
        }
        String out = sb.toString();
        return out.length() == 0 ? "plugin" : out;
    }

    private static File findEntry(File dir) {
        for (String n : new String[]{"plugin.js", "index.js", "dist/plugin.js", "dist/index.js"}) {
            File f = new File(dir, n);
            if (f.isFile()) return f;
        }
        // package.json 里声明了 main 但文件还没找到的情况，按 main 再试一次
        File pkg = new File(dir, "package.json");
        if (pkg.isFile()) {
            try {
                String text = new String(readAll(pkg), "UTF-8");
                org.json.JSONObject o = new org.json.JSONObject(text);
                String main = o.optString("main", "");
                if (main.length() > 0) {
                    File m = new File(dir, main);
                    if (m.isFile()) return m;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static byte[] readAll(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
