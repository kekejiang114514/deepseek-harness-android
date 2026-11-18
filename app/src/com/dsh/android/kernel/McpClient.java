package com.dsh.android.kernel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 极简 MCP 客户端（HTTP 传输）。
 *
 * <p>MCP 就是 JSON-RPC 2.0：{@code tools/list} 拿工具清单，
 * {@code tools/call} 调工具。这里不实现 stdio 传输——Android 上没有
 * child_process，起不了本地子进程式服务器；HTTP 型与本项目已有的
 * 网络能力天然契合。
 */
public final class McpClient {

    private final String url;
    private final String token;
    private int nextId = 1;

    public McpClient(String url, String token) {
        this.url = url == null ? "" : url.trim();
        this.token = token == null ? "" : token.trim();
    }

    public boolean usable() {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /** 列出工具；失败抛异常，由调用方转成可读错误。 */
    public JSONArray listTools() throws Exception {
        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("id", nextId++);
        req.put("method", "tools/list");
        req.put("params", new JSONObject());

        JSONObject resp = post(req);
        JSONObject result = resp.optJSONObject("result");
        JSONArray tools = result == null ? null : result.optJSONArray("tools");
        return tools == null ? new JSONArray() : tools;
    }

    /** 调用工具，返回文本结果。 */
    public String callTool(String tool, JSONObject args) throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", tool);
        params.put("arguments", args == null ? new JSONObject() : args);

        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("id", nextId++);
        req.put("method", "tools/call");
        req.put("params", params);

        JSONObject resp = post(req);
        if (resp.has("error")) {
            return "MCP 错误：" + resp.optJSONObject("error").optString("message", "未知");
        }
        JSONObject result = resp.optJSONObject("result");
        if (result == null) return "";
        JSONArray content = result.optJSONArray("content");
        if (content == null) return result.toString();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject c = content.optJSONObject(i);
            if (c == null) continue;
            String text = c.optString("text", "");
            if (text.length() > 0) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(text);
            }
        }
        return sb.length() == 0 ? result.toString() : sb.toString();
    }

    private JSONObject post(JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            if (token.length() > 0) {
                c.setRequestProperty("Authorization", token.startsWith("Bearer ") ? token : "Bearer " + token);
            }
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = readAll(in);
            if (code >= 400) throw new Exception("HTTP " + code + "：" + text);
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }
}
