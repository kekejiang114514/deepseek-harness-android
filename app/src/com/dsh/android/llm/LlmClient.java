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
        /** 思考过程全文；模型不支持该字段时为空串。 */
        public String reasoning = "";
        public List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        public boolean hasToolCalls() { return !toolCalls.isEmpty(); }
    }

    /** 流式回调：增量文本或工具调用。 */
    public interface StreamSink {
        void onDelta(String delta);                    // 文本增量
        /** 思考过程增量（DeepSeek reasoner 的 reasoning_content）。 */
        void onReasoning(String delta);
        void onToolCallStart(int index, String id, String name); // 工具调用开始
        void onToolCallDelta(int index, String argsDelta);       // 工具参数增量
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    /** 记录用量需要 Context；为空时统计跳过，不影响对话。 */
    private android.content.Context ctx;

    public LlmClient(android.content.Context ctx, String baseUrl, String apiKey,
                     String model, int maxTokens) {
        this.ctx = ctx;
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

    /** 中断探针：返回 true 时尽快结束本次请求。 */
    public interface Stopper { boolean stopped(); }

    /** 主动中断：调用方据此保留已经收到的半截内容。 */
    public static final class Cancelled extends Exception {
        public Cancelled() { super("interrupted"); }
    }

    /** 当前正在读的流。abort() 直接断开，阻塞中的 readLine 会立刻返回。 */
    private volatile HttpURLConnection active;

    /** 断开当前请求（用户在思考过程中暂停或停止）。 */
    public void abort() {
        HttpURLConnection c = active;
        if (c != null) {
            try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    public Result chatStream(JSONArray messages, JSONArray tools, StreamSink sink) throws Exception {
        return chatStream(messages, tools, sink, null);
    }

    /**
     * 流式 chat：边收边回调，并累积 tool_calls。
     *
     * <p>以前是先把整个响应读完再解析，界面上看到的「流式」其实是最后一次性涌出来的；
     * 而且用户点了暂停也得等服务端把这个响应发完。现在改成逐行读流，
     * 每行前检查 stopper，配合 abort() 断开连接，暂停能立刻生效。
     */
    public Result chatStream(JSONArray messages, JSONArray tools, StreamSink sink, Stopper stopper)
            throws Exception {
        JSONObject body = buildBody(messages, tools, true);
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);

        try {
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            if (code >= 400) {
                String resp = readAll(conn.getErrorStream());
                String msg = resp;
                try {
                    JSONObject e = new JSONObject(resp);
                    JSONObject er = e.optJSONObject("error");
                    if (er != null) msg = er.optString("message", resp);
                } catch (Exception ignored) {}
                throw new Exception("HTTP " + code + ": " + msg);
            }

            active = conn;
            Result r = new Result();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (stopper != null && stopper.stopped()) throw new Cancelled();
                if (!line.startsWith("data:")) continue;
                handleEvent(line.substring(5).trim(), r, sink);
            }
            return r;
        } catch (Cancelled c) {
            throw c;
        } catch (java.io.IOException e) {
            // abort() 掐断连接时这里会抛 IO 异常，用户已经要求停下就别报成网络错误
            if (stopper != null && stopper.stopped()) throw new Cancelled();
            throw e;
        } finally {
            active = null;
            try { conn.disconnect(); } catch (Throwable ignored) {}
        }
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
                // 只保留接口认识的字段：历史里还存着 reasoning / interrupted / hidden
                // 这些界面自用标记，混进请求里某些服务端会直接整单拒绝
                JSONObject clean = new JSONObject();
                clean.put("role", role);
                if (m.has("content")) clean.put("content", m.opt("content"));
                if (m.has("tool_calls")) clean.put("tool_calls", m.opt("tool_calls"));
                if (m.has("tool_call_id")) clean.put("tool_call_id", m.opt("tool_call_id"));
                if (m.has("name")) clean.put("name", m.opt("name"));
                safe.put(clean);
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

    /** 把响应里的 usage 累加到本地统计；没有就跳过。 */
    private void recordUsage(JSONObject o) {
        if (ctx == null) return;
        JSONObject usage = o.optJSONObject("usage");
        if (usage == null) return;
        com.dsh.android.kernel.UsageStats.addUsage(ctx,
                usage.optLong("prompt_tokens", 0), usage.optLong("completion_tokens", 0));
    }

    private Result parseNonStream(String json) throws Exception {
        JSONObject resp = new JSONObject(json);
        recordUsage(resp);
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

    /** 处理一条 SSE data 事件，把增量派发给界面并累积到结果里。 */
    private void handleEvent(String data, Result r, StreamSink sink) {
        if (data.length() == 0 || "[DONE]".equals(data)) return;
        JSONObject obj;
        try {
            obj = new JSONObject(data);
        } catch (Exception e) {
            return;
        }
        recordUsage(obj);
        JSONArray choices = obj.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return;
        JSONObject first = choices.optJSONObject(0);
        JSONObject delta = first == null ? null : first.optJSONObject("delta");
        if (delta == null) return;

        // 思考过程（reasoner 专有字段）
        Object rc = delta.opt("reasoning_content");
        if (rc instanceof String && ((String) rc).length() > 0) {
            r.reasoning += (String) rc;
            if (sink != null) sink.onReasoning((String) rc);
        }

        // 文本增量
        Object c = delta.opt("content");
        if (c instanceof String && ((String) c).length() > 0) {
            r.content += (String) c;
            if (sink != null) sink.onDelta((String) c);
        }

        // 工具调用增量
        JSONArray tc = delta.optJSONArray("tool_calls");
        if (tc == null) return;
        for (int i = 0; i < tc.length(); i++) {
            JSONObject t = tc.optJSONObject(i);
            if (t == null) continue;
            int idx = t.optInt("index", 0);
            String id = t.optString("id", null);
            JSONObject fn = t.optJSONObject("function");
            String name = fn == null ? null : fn.optString("name", null);
            String argsDelta = fn == null ? null : fn.optString("arguments", null);

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
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }
}
