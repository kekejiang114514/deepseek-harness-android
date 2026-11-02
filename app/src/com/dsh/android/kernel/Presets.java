package com.dsh.android.kernel;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 提示词预设：用户事先写好的背景说明（工作环境、项目约定、语气偏好等）。
 *
 * <p>启用中的条目会拼进系统提示词，模型每轮都能看到，不需要每次在对话里重复交代。
 * 存普通文件即可——这里放的是工作背景，不是凭据（凭据在 {@link Vault}）。
 */
public final class Presets {

    private static final String FILE = "presets.json";

    public static final class Item {
        public final String id;
        public final String title;
        public final String content;
        public final boolean enabled;

        Item(String id, String title, String content, boolean enabled) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.enabled = enabled;
        }
    }

    private Presets() {}

    public static List<Item> all(Context c) {
        List<Item> out = new ArrayList<Item>();
        JSONArray arr = read(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(new Item(o.optString("id"), o.optString("title"),
                    o.optString("content"), o.optBoolean("enabled", true)));
        }
        return out;
    }

    public static void put(Context c, String id, String title, String content, boolean enabled) {
        JSONArray arr = read(c);
        JSONArray next = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id != null && id.equals(o.optString("id"))) {
                next.put(build(id, title, content, enabled));
                replaced = true;
            } else if (o != null) {
                next.put(o);
            }
        }
        if (!replaced) {
            next.put(build(id == null ? String.valueOf(System.currentTimeMillis()) : id,
                    title, content, enabled));
        }
        write(c, next);
    }

    public static void remove(Context c, String id) {
        JSONArray arr = read(c);
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) next.put(o);
        }
        write(c, next);
    }

    public static void setEnabled(Context c, String id, boolean on) {
        JSONArray arr = read(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) {
                try {
                    o.put("enabled", on);
                } catch (Exception ignored) {}
            }
        }
        write(c, arr);
    }

    /** 所有启用条目的拼接文本；没有则返回空串。 */
    public static String activeText(Context c) {
        List<Item> items = all(c);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (!it.enabled || it.content.trim().length() == 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append("· ").append(it.title).append('\n').append(it.content.trim());
        }
        return sb.toString();
    }

    private static JSONObject build(String id, String title, String content, boolean enabled) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("title", title == null ? "" : title);
            o.put("content", content == null ? "" : content);
            o.put("enabled", enabled);
        } catch (Exception ignored) {}
        return o;
    }

    private static JSONArray read(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.isFile()) return new JSONArray();
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONArray arr = root.optJSONArray("items");
            return arr == null ? new JSONArray() : arr;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void write(Context c, JSONArray items) {
        try {
            JSONObject root = new JSONObject();
            root.put("items", items);
            FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), FILE));
            try {
                out.write(root.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Exception ignored) {}
    }
}
