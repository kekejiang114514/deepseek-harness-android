package com.dsh.android.plugins.js;

import android.content.Context;
import android.os.Environment;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 发现 DSH 风格的 JS 插件。
 *
 * <p>目录与声明式插件共用（{@code /sdcard/DShAndroid/plugins/}），靠入口文件区分：
 * 有 {@code plugin.json} 的按声明式 HTTP 插件处理；有 {@code package.json} 或
 * {@code plugin.js}/{@code index.js} 的按 DSH JS 插件处理。
 *
 * <p>只做发现与读取，装载交给 {@link JsHost}。
 */
public final class JsPluginStore {

    private static final int MAX_SOURCE = 512 * 1024;

    public static final class JsPlugin {
        public String id;
        public String name;
        public String version = "0.0.0";
        public String description = "";
        public File dir;
        public File entry;
        /** 读取/解析阶段的错误，null 表示正常。 */
        public String error;
    }

    private final Context ctx;

    public JsPluginStore(Context ctx) {
        this.ctx = ctx;
    }

    private List<File> dirs() {
        List<File> out = new ArrayList<File>();
        out.add(new File(Environment.getExternalStorageDirectory(), "DShAndroid/plugins"));
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) out.add(new File(ext, "plugins"));
        out.add(new File(ctx.getFilesDir(), "plugins"));
        return out;
    }

    public List<JsPlugin> scan() {
        List<JsPlugin> out = new ArrayList<JsPlugin>();
        Set<String> ids = new HashSet<String>();

        for (File dir : dirs()) {
            if (dir == null || !dir.isDirectory() || !dir.canRead()) continue;
            File[] children = dir.listFiles();
            if (children == null) continue;
            Arrays.sort(children, new Comparator<File>() {
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });

            for (File child : children) {
                if (!child.isDirectory()) continue;
                // 声明式插件有自己的 plugin.json，交给 PluginStore
                if (new File(child, "plugin.json").isFile()) continue;

                File pkg = new File(child, "package.json");
                File entry = null;
                JsPlugin p = new JsPlugin();
                p.dir = child;

                if (pkg.isFile()) {
                    try {
                        JSONObject o = new JSONObject(read(pkg));
                        String main = o.optString("main", "");
                        p.id = o.optString("name", child.getName());
                        p.name = o.optString("displayName", o.optString("name", child.getName()));
                        p.version = o.optString("version", "0.0.0");
                        p.description = o.optString("description", "");
                        if (main.length() > 0) {
                            File m = new File(child, main);
                            if (m.isFile()) entry = m;
                        }
                    } catch (Exception e) {
                        p.error = "package.json 解析失败：" + e.getMessage();
                    }
                } else {
                    p.id = child.getName();
                    p.name = child.getName();
                }

                if (entry == null) {
                    for (String cand : new String[]{"plugin.js", "index.js", "dist/plugin.js", "dist/index.js"}) {
                        File f = new File(child, cand);
                        if (f.isFile()) {
                            entry = f;
                            break;
                        }
                    }
                }
                if (entry == null) continue;      // 不是 JS 插件，忽略

                p.entry = entry;
                if (p.error == null) {
                    String src;
                    try {
                        src = read(entry);
                    } catch (Exception e) {
                        p.error = "读取源码失败：" + e.getMessage();
                        out.add(p);
                        continue;
                    }
                    String banned = Esm.firstUnsupportedImport(src);
                    if (banned != null) {
                        p.error = "依赖 Node 专有模块 " + banned + "，Android 上无法运行";
                    }
                }
                if (p.id == null || p.id.length() == 0) p.id = child.getName();
                if (!ids.add(p.id)) {
                    continue;
                }
                out.add(p);
            }
        }
        return out;
    }

    /** 读取插件源码（已去掉 TS 类型标注不现实，只做 ESM 语法改写）。 */
    public static String sourceOf(JsPlugin p) throws Exception {
        return read(p.entry);
    }

    private static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_SOURCE) throw new IllegalStateException("源码超过 512KB");
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
