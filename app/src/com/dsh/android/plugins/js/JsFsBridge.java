package com.dsh.android.plugins.js;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 给 JS 插件用的文件系统桥（Node {@code fs} 的后端）。
 *
 * <p>插件代码里 {@code require('fs')} 最终落到这里。所有路径都被限制在
 * 该插件自己的数据目录下：插件 A 读不到插件 B 的文件，也读不到用户的其他文件。
 * 这不是「实现不完整」，而是必须有——否则一个从市场装的第三方插件就能
 * 把用户的照片、短信备份、下载目录全读走再发到网上。
 *
 * <p>越界的路径不会静默失败，而是返回明确的错误，插件作者能看出是沙箱限制。
 */
public final class JsFsBridge {

    private static final int MAX_READ = 4 * 1024 * 1024;
    private static final int MAX_WRITE = 4 * 1024 * 1024;

    private final File base;

    public JsFsBridge(File base) {
        this.base = base;
        if (!base.exists()) base.mkdirs();
    }

    /** 插件 id → 沙箱根目录（每个插件一个子目录，互不可见）。 */
    private File rootOf(String pluginId) {
        String id = pluginId == null || pluginId.length() == 0 ? "_default" : pluginId;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < id.length() && sb.length() < 64; i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            sb.append(ok ? c : '_');
        }
        File f = new File(base, sb.toString());
        if (!f.exists()) f.mkdirs();
        return f;
    }

    // ---------- 路径解析 ----------

    /** 解析成沙箱内的绝对路径；越界返回 null。 */
    private File resolve(String pluginId, String p) {
        File root = rootOf(pluginId);
        try {
            String path = p == null ? "" : p.replace('\\', '/').trim();
            if (path.startsWith("file://")) path = path.substring(7);
            // 常见写法：插件用绝对路径拼自己的文件，落到沙箱根
            while (path.startsWith("/")) path = path.substring(1);
            File f = new File(root, path);
            String canonical = f.getCanonicalPath();
            String rootPath = root.getCanonicalPath();
            if (canonical.equals(rootPath) || canonical.startsWith(rootPath + File.separator)) {
                return f;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String ok() {
        return "{\"ok\":true}";
    }

    private static String ok(Object data) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("data", data);
            return o.toString();
        } catch (Exception e) {
            return ok();
        }
    }

    private static String err(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", msg);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"error\"}";
        }
    }

    private String outside(String p) {
        return "路径超出插件可访问范围：" + p
                + "（Android 上插件只能读写自己的数据目录，这是平台存储权限决定的）";
    }

    // ---------- 给 JS 的接口 ----------

    @JavascriptInterface
    public String cwd(String pluginId) {
        return rootOf(pluginId).getAbsolutePath();
    }

    @JavascriptInterface
    public String exists(String pluginId, String path) {
        File f = resolve(pluginId, path);
        if (f == null) return err(outside(path));
        return ok(f.exists());
    }

    @JavascriptInterface
    public String readText(String pluginId, String path) {
        File f = resolve(pluginId, path);
        if (f == null) return err(outside(path));
        if (!f.isFile()) return err("文件不存在：" + path);
        if (f.length() > MAX_READ) return err("文件超过 4MB");
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return ok(new String(bos.toByteArray(), "UTF-8"));
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return err("读取失败：" + e.getMessage());
        }
    }

    @JavascriptInterface
    public String writeText(String pluginId, String path, String text) {
        File f = resolve(pluginId, path);
        if (f == null) return err(outside(path));
        try {
            byte[] raw = (text == null ? "" : text).getBytes("UTF-8");
            if (raw.length > MAX_WRITE) return err("写入内容超过 4MB");
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return err("无法创建目录");
            }
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(raw);
                out.flush();
            } finally {
                out.close();
            }
            return ok();
        } catch (Exception e) {
            return err("写入失败：" + e.getMessage());
        }
    }

    @JavascriptInterface
    public String append(String pluginId, String path, String text) {
        File f = resolve(pluginId, path);
        if (f == null) return err(outside(path));
        try {
            byte[] raw = (text == null ? "" : text).getBytes("UTF-8");
            if (f.length() + raw.length > MAX_WRITE) return err("追加后超过 4MB");
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(raw);
                out.flush();
            } finally {
                out.close();
            }
            return ok();
        } catch (Exception e) {
            return err("追加失败：" + e.getMessage());
        }
    }

    @JavascriptInterface
    public String list(String pluginId, String path) {
        File f = resolve(pluginId, path);
        if (f == null) return err(outside(path));
        if (!f.isDirectory()) return err("目录不存在：" + path);
        String[] names = f.list();
        JSONArray arr = new JSONArray();
        if (names != null) {
            java.util.Arrays.sort(names);
            for (String n : names) arr.put(n);
        }
        return ok(arr);
    }

    @JavascriptInterface
    public String mkdir(String pluginId, String path) {
        File f = resolve(pluginId, path);
        if (f == null) return err(outside(path));
        if (f.exists()) return ok();
        return f.mkdirs() ? ok() : err("创建目录失败");
    }

    @JavascriptInterface
    public String remove(String pluginId, String path) {
        File f = resolve(pluginId, path);
        if (f == null) return err(outside(path));
        return deleteRecursive(f) ? ok() : err("删除失败");
    }

    @JavascriptInterface
    public String stat(String pluginId, String path) {
        File f = resolve(pluginId, path);
        if (f == null) return err(outside(path));
        try {
            JSONObject o = new JSONObject();
            o.put("exists", f.exists());
            o.put("isFile", f.isFile());
            o.put("isDirectory", f.isDirectory());
            o.put("size", f.length());
            o.put("mtime", f.lastModified());
            return ok(o);
        } catch (Exception e) {
            return err("stat 失败");
        }
    }

    private static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return false;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        return f.delete();
    }
}
