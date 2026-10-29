package com.dsh.android.plugins;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

/**
 * 插件请求模板：把 {参数} 与 ${密钥} 替换成实际值。
 *
 * 参数占位符有三种写法，区别只在「怎么转义」：
 *   {city}        默认，做 URL 编码（空格 -> %20，中文 -> %E5%8C%97），用于 query 与路径
 *   {city|raw}    原样插入，用于数字、布尔这类不该转义的值
 *   {city|json}   转义成 JSON 字符串内容（不含两侧引号），模板里自己写引号： "city":"{city|json}"
 *
 * 密钥占位符 ${KEY} 从插件自身 secrets 取值，同样可以加 |raw（默认原样，因为密钥不走 URL 场合居多，
 * 放在 query 里时写 ${KEY} 会被自动编码？不会——密钥一律原样，需要编码请放到参数里）。
 * 想输出字面量大括号写 {{。
 *
 * 缺值不抛异常：缺的键记进 missing，渲染结果为空串，由调用方决定报错还是放行。
 */
public final class Template {

    private Template() {}

    public static String render(String tpl, JSONObject args, JSONObject secrets, List<String> missing) {
        if (tpl == null || tpl.length() == 0) return "";
        StringBuilder sb = new StringBuilder(tpl.length() + 32);
        int i = 0;
        int n = tpl.length();

        while (i < n) {
            char c = tpl.charAt(i);

            // {{ -> 单个 {
            if (c == '{' && i + 1 < n && tpl.charAt(i + 1) == '{') {
                sb.append('{');
                i += 2;
                continue;
            }

            if (c == '{') {
                int end = tpl.indexOf('}', i + 1);
                if (end > i) {
                    String inner = tpl.substring(i + 1, end);
                    String key = inner;
                    String mode = "";
                    int bar = inner.indexOf('|');
                    if (bar >= 0) {
                        key = inner.substring(0, bar);
                        mode = inner.substring(bar + 1).trim();
                    }
                    key = key.trim();
                    // ${...} 由下面的分支处理，这里跳过
                    if (validKey(key) && (!hasMode(inner) || validMode(mode))) {
                        if (!has(args, key)) {
                            if (missing != null) missing.add(key);
                        } else {
                            sb.append(format(value(args, key), mode));
                        }
                        i = end + 1;
                        continue;
                    }
                }
            }

            if (c == '$' && i + 1 < n && tpl.charAt(i + 1) == '{') {
                int end = tpl.indexOf('}', i + 2);
                if (end > i) {
                    String key = tpl.substring(i + 2, end).trim();
                    if (!validKey(key)) {
                        // 不像密钥名（比如 JSON 里的 ${"a":1}），按字面量处理
                        sb.append(c);
                        i++;
                        continue;
                    }
                    String v = secrets == null ? null : secrets.optString(key, null);
                    if (v == null) {
                        if (missing != null) missing.add("$" + key);
                    } else {
                        sb.append(v);
                    }
                    i = end + 1;
                    continue;
                }
            }

            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** 模板里声明了哪些参数名（用于校验 parameters 是否覆盖了 url/body 用到的键）。 */
    public static java.util.Set<String> placeholders(String tpl) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        if (tpl == null) return out;
        int i = 0;
        while (i < tpl.length()) {
            char c = tpl.charAt(i);
            if (c == '{' && i + 1 < tpl.length() && tpl.charAt(i + 1) == '{') { i += 2; continue; }
            if (c == '{') {
                int end = tpl.indexOf('}', i + 1);
                if (end < 0) break;
                String key = tpl.substring(i + 1, end);
                int bar = key.indexOf('|');
                if (bar >= 0) key = key.substring(0, bar);
                key = key.trim();
                if (validKey(key)) out.add(key);
                i = end + 1;
                continue;
            }
            if (c == '$' && i + 1 < tpl.length() && tpl.charAt(i + 1) == '{') {
                int end = tpl.indexOf('}', i + 2);
                if (end < 0) break;
                String sk = tpl.substring(i + 2, end).trim();
                if (validKey(sk)) out.add("$" + sk);
                i = end + 1;
                continue;
            }
            i++;
        }
        return out;
    }

    // ---------- 内部 ----------

    /**
     * 占位符名是否合法。
     * 这条判断同时解决了「JSON 花括号被当成占位符」的问题：
     * {"text":"{s|json}"} 里的第一个 { 后面跟的是引号，不是一个参数名，
     * 所以按字面量输出，只有里面的 {s|json} 会被替换。
     */
    static boolean validKey(String key) {
        if (key == null || key.length() == 0 || key.length() > 64) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean hasMode(String inner) {
        return inner.indexOf('|') >= 0;
    }

    private static boolean validMode(String mode) {
        return "raw".equals(mode) || "json".equals(mode) || mode.length() == 0;
    }

    private static boolean has(JSONObject args, String key) {
        return args != null && args.has(key) && !args.isNull(key);
    }

    private static Object value(JSONObject args, String key) {
        try {
            return args.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private static String format(Object v, String mode) {
        if (v == null) return "";
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append(plain(a.opt(i)));
            }
            return format(sb.toString(), mode);
        }
        String s = plain(v);
        if ("raw".equals(mode)) return s;
        if ("json".equals(mode)) return escapeJson(s);
        return urlEncode(s);
    }

    private static String plain(Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        return String.valueOf(v);
    }

    /** URL 编码；把 URLEncoder 的空格 + 换回 %20，这样路径段里也正确。 */
    public static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /** JSON 字符串内容转义（不含两侧引号）。 */
    public static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
