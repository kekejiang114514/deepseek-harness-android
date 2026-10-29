package com.dsh.android.plugins;

import com.dsh.android.tools.ToolExecutor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 声明式 HTTP 工具的执行器：按 PluginToolSpec 拼请求、发出去、把响应整理成工具结果。
 *
 * 约束（都是有意为之）：
 *   - 只允许 http/https，防止 file:// 之类把本地文件读出去。
 *   - 响应最多读 512KB，交给模型前再截到 20000 字符，避免一次抓个网页把上下文撑爆。
 *   - 非 2xx 不算异常，返回 ok:false + status + 响应片段，让模型自己判断。
 *   - 超时上限 120s，由声明控制。
 */
public final class PluginHttpExecutor implements ToolExecutor {

    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_TEXT = 20000;
    private static final String UA = "DShAndroid-Plugin/1.0";

    private final PluginSpec owner;
    private final PluginToolSpec spec;

    public PluginHttpExecutor(PluginSpec owner, PluginToolSpec spec) {
        this.owner = owner;
        this.spec = spec;
    }

    public String execute(JSONObject args) throws Exception {
        List<String> missing = new ArrayList<String>();
        String url = Template.render(spec.url, args, owner.secrets, missing);
        if (!missing.isEmpty()) return fail("缺少参数：" + join(missing));

        String body = null;
        if (spec.body != null && spec.method.matches("POST|PUT|PATCH|DELETE")) {
            body = Template.render(spec.body, args, owner.secrets, missing);
            if (!missing.isEmpty()) return fail("缺少参数：" + join(missing));
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return fail("不允许的协议：" + url);
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(spec.method);
            conn.setConnectTimeout(Math.min(20000, spec.timeoutMs));
            conn.setReadTimeout(spec.timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");

            // 插件自定义头（占位符在这里同样生效，便于把密钥放 header）
            Iterator<String> it = owner.headers.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = owner.headers.optString(k, "");
                conn.setRequestProperty(k, Template.render(v, args, owner.secrets, null));
            }

            if (body != null) {
                byte[] bytes = body.getBytes("UTF-8");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", contentType());
                conn.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(bytes);
                    os.flush();
                } finally {
                    os.close();
                }
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readCapped(in);
            String ctype = conn.getContentType();
            boolean json = looksJson(text, ctype);

            JSONObject out = new JSONObject();
            if (code < 200 || code >= 300) {
                out.put("ok", false);
                out.put("status", code);
                out.put("error", "HTTP " + code);
                out.put("body", clip(text, 800));
                return out.toString();
            }

            out.put("ok", true);
            out.put("status", code);
            if (json) {
                Object root;
                try {
                    root = text.trim().startsWith("[")
                            ? new org.json.JSONArray(text)
                            : new JSONObject(text);
                } catch (Exception e) {
                    root = null;
                }
                if (root != null) {
                    if (spec.resultPath != null) {
                        Object picked = JsonPath.pick(root, spec.resultPath);
                        if (picked == null) {
                            out.put("warning", "resultPath " + spec.resultPath + " 没有取到值，返回完整响应");
                            putData(out, root);
                        } else {
                            putData(out, picked);
                        }
                    } else {
                        putData(out, root);
                    }
                    return out.toString();
                }
                out.put("data", clip(text, MAX_TEXT));
                return out.toString();
            }

            out.put("data", clip(text, MAX_TEXT));
            return out.toString();
        } catch (java.net.SocketTimeoutException e) {
            return fail("请求超时（" + spec.timeoutMs + "ms）");
        } catch (Exception e) {
            String m = e.getMessage();
            return fail(m == null ? e.getClass().getSimpleName() : m);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String contentType() {
        String ct = spec.contentType;
        if ("json".equalsIgnoreCase(ct)) return "application/json; charset=utf-8";
        if ("form".equalsIgnoreCase(ct)) return "application/x-www-form-urlencoded; charset=utf-8";
        if ("text".equalsIgnoreCase(ct)) return "text/plain; charset=utf-8";
        return ct;
    }

    private static boolean looksJson(String text, String ctype) {
        if (ctype != null && ctype.toLowerCase().indexOf("json") >= 0) return true;
        String t = text.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    private static String readCapped(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            if (total + n > MAX_BYTES) {
                bos.write(buf, 0, Math.max(0, MAX_BYTES - total));
                break;
            }
            bos.write(buf, 0, n);
            total += n;
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /**
     * 把取到的值放进 data。
     * 对象/数组原样放（模型读 JSON 比读转义字符串省很多 token），
     * 只有超长时才降级成截断后的字符串。
     */
    private static void putData(JSONObject out, Object v) throws Exception {
        if (v == null) {
            out.put("data", JSONObject.NULL);
            return;
        }
        if (v instanceof JSONObject || v instanceof JSONArray) {
            String s = v.toString();
            if (s.length() <= MAX_TEXT) {
                out.put("data", v);
            } else {
                out.put("data", clip(s, MAX_TEXT));
                out.put("truncated", true);
            }
            return;
        }
        out.put("data", clip(String.valueOf(v), MAX_TEXT));
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n…（已截断，原始长度 " + s.length() + " 字符）";
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private static String fail(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", msg);
        } catch (Exception ignored) {}
        return o.toString();
    }
}
