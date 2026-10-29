package com.dsh.android.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import com.dsh.android.kernel.C;

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
 * 插件发现与启用状态。
 *
 * 扫描三个目录，按优先级：
 *   1. /sdcard/DShAndroid/plugins          用户自己放，最容易改（需要存储权限才读得到）
 *   2. <App 外部私有>/plugins              /sdcard/Android/data/<pkg>/files/plugins，无需权限
 *   3. <App 内部>/plugins                  应用私有目录，adb push 用
 *
 * 目录里两种形态都认：
 *   plugins/天气/plugin.json              推荐，一个插件一个文件夹，方便放图标/说明
 *   plugins/weather.json                  单个文件也可，适合从聊天里直接存下来
 *
 * 同一个 id 出现多次时以先扫描到的为准，后面的记一条冲突提示。
 */
public final class PluginStore {

    private static final int MAX_MANIFEST = 256 * 1024;

    /** 扫描结果。 */
    public static final class Scan {
        public final List<PluginSpec> specs = new ArrayList<PluginSpec>();
        /** 形如「天气/plugin.json：缺少 id」，直接展示给用户。 */
        public final List<String> errors = new ArrayList<String>();
    }

    private final Context ctx;
    private final SharedPreferences prefs;

    public PluginStore(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE);
    }

    // ---------- 目录 ----------

    /** 所有候选目录（不保证存在）。 */
    public List<File> dirs() {
        List<File> out = new ArrayList<File>();
        File external = new File(Environment.getExternalStorageDirectory(), C.PLUGIN_DIR);
        out.add(external);
        File ext2 = ctx.getExternalFilesDir(null);
        if (ext2 != null) out.add(new File(ext2, "plugins"));
        out.add(new File(ctx.getFilesDir(), "plugins"));
        return out;
    }

    /** 建出可写的目录，返回实际使用的第一个目录（给用户看路径用）。 */
    public File ensureDirs() {
        File first = null;
        for (File d : dirs()) {
            try {
                if (!d.exists()) d.mkdirs();
                if (d.isDirectory() && d.canRead()) {
                    if (first == null) first = d;
                }
            } catch (Throwable ignored) {}
        }
        return first;
    }

    /** 界面上展示的推荐路径。 */
    public String primaryPath() {
        File f = dirs().get(0);
        if (f.isDirectory() && f.canRead()) return f.getAbsolutePath();
        File e = ctx.getExternalFilesDir(null);
        if (e != null) return new File(e, "plugins").getAbsolutePath();
        return new File(ctx.getFilesDir(), "plugins").getAbsolutePath();
    }

    // ---------- 扫描 ----------

    public Scan scan() {
        Scan scan = new Scan();
        Set<String> seenIds = new HashSet<String>();

        for (File dir : dirs()) {
            if (dir == null || !dir.isDirectory() || !dir.canRead()) continue;
            File[] children = dir.listFiles();
            if (children == null) continue;
            sortByName(children);

            for (File child : children) {
                if (!child.canRead()) continue;
                File manifest = null;
                if (child.isDirectory()) {
                    File m = new File(child, "plugin.json");
                    if (m.isFile()) manifest = m;
                } else if (child.getName().toLowerCase().endsWith(".json")
                        && !child.getName().equalsIgnoreCase("plugin.json")) {
                    manifest = child;
                }
                if (manifest == null) continue;

                String label = manifest.getParentFile().equals(dir)
                        ? manifest.getName()
                        : manifest.getParentFile().getName() + "/" + manifest.getName();

                try {
                    String text = readText(manifest);
                    PluginSpec spec = PluginSpec.parse(text, manifest);
                    if (!seenIds.add(spec.id)) {
                        scan.errors.add(label + "：id " + spec.id + " 与其它插件重复，已跳过");
                        continue;
                    }
                    scan.specs.add(spec);
                } catch (IllegalArgumentException e) {
                    scan.errors.add(label + "：" + e.getMessage());
                } catch (Throwable t) {
                    scan.errors.add(label + "：读取失败（" + t.getClass().getSimpleName() + "）");
                }
            }
        }
        return scan;
    }

    private static void sortByName(File[] files) {
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
    }

    private static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_MANIFEST) {
                    throw new IllegalArgumentException("plugin.json 超过 256KB");
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    // ---------- 启用状态 ----------

    /** 新发现的插件默认启用（用户既然把文件放进来了，就是想用）。 */
    public boolean isEnabled(String id) {
        return prefs.getBoolean(C.KEY_PLUGIN_ON + id, true);
    }

    public void setEnabled(String id, boolean on) {
        prefs.edit().putBoolean(C.KEY_PLUGIN_ON + id, on).commit();
    }

    /** 是否为第一次见到这个 id（用于提示「新发现 N 个插件」）。 */
    public boolean isNew(String id) {
        return !prefs.getBoolean(C.KEY_PLUGIN_SEEN + id, false);
    }

    public void markSeen(String id) {
        prefs.edit().putBoolean(C.KEY_PLUGIN_SEEN + id, true).commit();
    }
}
