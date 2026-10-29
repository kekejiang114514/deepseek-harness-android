package com.dsh.android.llm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.ArrayList;

/**
 * DeepSeek / OpenAI 兼容聊天客户端。
 * 支持 tool-calling 与 SSE 流式输出。
 */
public final class LlmClient {

    public static final class ToolCall {
        public String id;
        public String name;
        public String args;
        public ToolCall(String id, String name, String args) {
            this.id = id; this.name = name; this.args = args;
        }
    }

    public static final class Result {
        public String content = "";
        public List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        public boolean hasToolCalls() { return !toolCalls.isEmpty(); }
    }

    /** 流式回调：增量文本或工具调用。 */
    public interface StreamSink {
        void onDelta(String delta);                    // 文本增量
        void onToolCallStart(int index, String id, String name); // 工具调用开始
        void onToolCallDelta(int index, String argsDelta);       // 工具参数增量
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public LlmClient(String baseUrl, String apiKey, String model, int maxTokens) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    public String getModel() { return model; }

    /**
     * 验证 API Key 并拉取可用模型列表。
     * 直接打 GET {base}/models —— DeepSeek / OpenAI 兼容服务都支持。
     * 失败时抛异常，message 里带可读原因。
     */
    public static List<String> fetchModels(String baseUrl, String apiKey) throws Exception {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/models").openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);

            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String resp = readAllStatic(is);
            if (code == 401 || code == 403) {
                throw new Exception("API Key 无效或已过期（HTTP " + code + "）");
            }
            if (code >= 400) {
                String msg = resp;
                try {
                    JSONObject e = new JSONObject(resp);
                    JSONObject er = e.optJSONObject("error");
                    if (er != null) msg = er.optString("message", resp);
                } catch (Exception ignored) {}
                throw new Exception("HTTP " + code + ": " + msg);
            }

            List<String> ids = new ArrayList<String>();
            JSONObject o = new JSONObject(resp);
            JSONArray data = o.optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject m = data.optJSONObject(i);
                    if (m == null) continue;
                    String id = m.optString("id", "");
                    if (id.length() > 0) ids.add(id);
                }
            }
            // 兼容少数返回 {"models":[...]} 的服务
            if (ids.isEmpty()) {
                JSONArray alt = o.optJSONArray("models");
                if (alt != null) {
                    for (int i = 0; i < alt.length(); i++) {
                        String id = alt.optString(i, "");
                        if (id.length() > 0) ids.add(id);
                    }
                }
            }
            if (ids.isEmpty()) throw new Exception("验证通过，但接口未返回任何模型");
            return ids;
        } catch (java.net.SocketTimeoutException e) {
            throw new Exception("连接超时，请检查网络或 Base URL");
        } catch (java.net.UnknownHostException e) {
            throw new Exception("域名无法解析，请检查 Base URL");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAllStatic(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    /** 非流式 chat（用于需要精确 tool_calls 的场景，保留兼容）。 */
    public Result chat(JSONArray messages, JSONArray tools) throws Exception {
        JSONObject body = buildBody(messages, tools, false);
        String json = post("/chat/completions", body.toString());
        return parseNonStream(json);
    }

    /**
     * 流式 chat：逐 token 回调文本，并累积 tool_calls。
     * 返回完整的 Result（含拼接后的工具调用）。
     */
    public Result chatStream(JSONArray messages, JSONArray tools, StreamSink sink) throws Exception {
        JSONObject body = buildBody(messages, tools, true);
        String resp = post("/chat/completions", body.toString());
        return parseStream(resp, sink);
    }

    private JSONObject buildBody(JSONArray messages, JSONArray tools, boolean stream) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        // 历史里可能混有界面专用的记录（role 不是标准角色），
        // 直接发过去会被服务端当成非法格式整单拒绝，这里先筛一遍
        JSONArray safe = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            if ("system".equals(role) || "user".equals(role)
                    || "assistant".equals(role) || "tool".equals(role)) {
                safe.put(m);
            }
        }
        body.put("messages", safe);
        // DeepSeek 的 reasoner 模型不接受 temperature，统一改用 max_tokens 控制输出规模
        body.put("max_tokens", maxTokens);
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        body.put("stream", stream);
        return body;
    }

    private Result parseNonStream(String json) throws Exception {
        JSONObject resp = new JSONObject(json);
        JSONArray choices = resp.optJSONArray("choices");
        Result r = new Result();
        if (choices != null && choices.length() > 0) {
            JSONObject first = choices.optJSONObject(0);
            JSONObject msg = first.optJSONObject("message");
            if (msg != null) {
                Object content = msg.opt("content");
                if (content instanceof String) r.content = (String) content;
                else if (content != null) r.content = String.valueOf(content);
                JSONArray tc = msg.optJSONArray("tool_calls");
                if (tc != null) collectToolCalls(tc, r);
            }
        }
        return r;
    }

    /** 解析 SSE 流，累积文本与工具调用。 */
    private Result parseStream(String resp, StreamSink sink) throws Exception {
        Result r = new Result();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(resp.getBytes("UTF-8")), "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.length() == 0 || "[DONE]".equals(data)) continue;
            JSONObject obj;
            try {
                obj = new JSONObject(data);
            } catch (Exception e) {
                continue;
            }
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) continue;
            JSONObject first = choices.optJSONObject(0);
            JSONObject delta = first.optJSONObject("delta");
            if (delta == null) continue;

            // 文本增量
            Object c = delta.opt("content");
            if (c instanceof String && ((String) c).length() > 0) {
                r.content += (String) c;
                if (sink != null) sink.onDelta((String) c);
            }
            // 工具调用增量
            JSONArray tc = delta.optJSONArray("tool_calls");
            if (tc != null) {
                for (int i = 0; i < tc.length(); i++) {
                    JSONObject t = tc.optJSONObject(i);
                    if (t == null) continue;
                    int idx = t.optInt("index", 0);
                    String id = t.optString("id", null);
                    JSONObject fn = t.optJSONObject("function");
                    String name = fn == null ? null : fn.optString("name", null);
                    String argsDelta = fn == null ? null : fn.optString("arguments", null);

                    // 初始化到列表
                    while (r.toolCalls.size() <= idx) {
                        r.toolCalls.add(new ToolCall("", "", ""));
                    }
                    ToolCall target = r.toolCalls.get(idx);
                    if (id != null) target.id = id;
                    if (name != null) {
                        target.name = name;
                        if (sink != null) sink.onToolCallStart(idx, id, name);
                    }
                    if (argsDelta != null) {
                        target.args += argsDelta;
                        if (sink != null) sink.onToolCallDelta(idx, argsDelta);
                    }
                }
            }
        }
        return r;
    }

    private void collectToolCalls(JSONArray tc, Result r) {
        for (int i = 0; i < tc.length(); i++) {
            JSONObject t = tc.optJSONObject(i);
            if (t == null) continue;
            String id = t.optString("id", "");
            JSONObject fn = t.optJSONObject("function");
            String name = fn == null ? "" : fn.optString("name", "");
            String args = fn == null ? "{}" : fn.optString("arguments", "{}");
            r.toolCalls.add(new ToolCall(id, name, args));
        }
    }

    private String post(String path, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);

        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();

        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String resp = readAll(is);
        if (code >= 400) {
            // 尝试解析错误信息
            String msg = resp;
            try {
                JSONObject e = new JSONObject(resp);
                JSONObject er = e.optJSONObject("error");
                if (er != null) msg = er.optString("message", resp);
            } catch (Exception ignored) {}
            throw new Exception("HTTP " + code + ": " + msg);
        }
        return resp;
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }
}
