package com.dsh.android.plugins;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 极简 JSON 取值路径，支持 a.b.c 与数组下标 list[0].name。
 * 只为了插件能声明 resultPath 精简响应，不做完整 JSONPath。
 */
public final class JsonPath {

    private JsonPath() {}

    /** 找不到返回 null。 */
    public static Object pick(Object root, String path) {
        if (root == null || path == null) return null;
        String p = path.trim();
        if (p.length() == 0 || "$".equals(p) || ".".equals(p)) return root;

        Object cur = root;
        int i = 0;
        int n = p.length();
        while (i < n && cur != null) {
            char c = p.charAt(i);
            if (c == '.') { i++; continue; }

            if (c == '[') {
                int close = p.indexOf(']', i);
                if (close < 0) return null;
                String idxStr = p.substring(i + 1, close).trim();
                int idx;
                try {
                    idx = Integer.parseInt(idxStr);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (!(cur instanceof JSONArray)) return null;
                cur = ((JSONArray) cur).opt(idx);
                i = close + 1;
                continue;
            }

            int end = i;
            while (end < n && p.charAt(end) != '.' && p.charAt(end) != '[') end++;
            String key = p.substring(i, end);
            if (key.length() == 0) return null;
            if (!(cur instanceof JSONObject)) return null;
            JSONObject o = (JSONObject) cur;
            if (!o.has(key)) return null;
            cur = o.opt(key);
            i = end;
        }
        return cur;
    }

    /** 取值为文本；数组/对象转 JSON 文本。 */
    public static String pickText(Object root, String path) {
        Object v = pick(root, path);
        if (v == null) return null;
        if (v instanceof JSONObject || v instanceof JSONArray) return v.toString();
        if (v == JSONObject.NULL) return null;
        return String.valueOf(v);
    }
}
