package com.dsh.android.llm;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Agent 循环：用户输入 → LLM（流式）→ 工具调用 → 结果回填 → 再 LLM，直到模型不再调用工具。
 * 对应 DSH 的 tool-calling orchestration。
 */
public final class AgentLoop {

    public interface Sink {
        void onDelta(String delta);                 // 流式文本增量
        void onToolCall(String name, String args);  // 开始调用工具
        void onToolResult(String name, String result); // 工具结果
        void onError(String msg);

        /** 上下文被压缩时回调，用于在界面上提示用户。 */
        default void onCompact(HistoryCompactor.Report r) {}
    }

    /** 触发压缩的阈值（估算 token）。留足余量给系统提示词和本轮输出。 */
    private static final int CTX_SOFT = 24000;
    /** 压缩目标：宁可多删一点，避免连续几轮都触发压缩。 */
    private static final int CTX_TARGET = 14000;
    /** 请求被服务端以「超出上下文」拒绝后的紧急目标。 */
    private static final int CTX_EMERGENCY = 6000;

    private final LlmClient client;
    private final com.dsh.android.tools.ToolRuntime tools;
    private String systemPrompt;
    private volatile boolean cancelled = false;

    public AgentLoop(LlmClient client, com.dsh.android.tools.ToolRuntime tools, String systemPrompt) {
        this.client = client;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
    }

    /** 只读工具并发执行用。数量不大，固定 4 个线程足够。 */
    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    /**
     * 这个工具是否只读：只读才能并行，涉及写入/删除/等待用户的必须串行。
     * 判据是工具名前缀，插件工具默认按不安全处理。
     */
    private static boolean isReadOnlyTool(String name) {
        if (name == null) return false;
        String n = name.replace("__", ".");
        if (n.equals("ui.ask")) return false;
        if (n.startsWith("fs.write") || n.startsWith("fs.delete") || n.startsWith("fs.mkdir")) return false;
        if (n.startsWith("a11y.click") || n.startsWith("a11y.tap") || n.startsWith("a11y.swipe")
                || n.startsWith("a11y.longpress") || n.startsWith("a11y.longclick")
                || n.startsWith("a11y.doubleclick") || n.startsWith("a11y.settext")
                || n.startsWith("a11y.cleartext") || n.startsWith("a11y.scroll")
                || n.startsWith("a11y.enter") || n.startsWith("a11y.back")
                || n.startsWith("a11y.home") || n.startsWith("a11y.recents")) return false;
        if (n.startsWith("app.open") || n.startsWith("notify")) return false;
        if (n.startsWith("fs.") || n.startsWith("web.") || n.startsWith("a11y.dump")
                || n.startsWith("a11y.findtext")) return true;
        return false;
    }

    /** 请求中断当前回合。 */
    public void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }

    /** 更新系统提示词（作用域/无障碍状态变化时调用）。 */
    public void setSystemPrompt(String prompt) { this.systemPrompt = prompt; }

    /**
     * 运行一轮 agent 循环（流式）。
     * history 是完整对话历史（不含本轮 user），会被原地追加。
     */
    public void run(JSONArray history, String userMessage, Sink sink) {
        try {
            cancelled = false;
            ensureSystem(history);
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", userMessage);
            history.put(u);

            JSONArray toolSchemas = tools.schemas();

            // 同一工具 + 同一参数、且返回一模一样，说明原地打转。
            // 命中时在工具结果末尾点明，比让模型自己发现要有效得多。
            java.util.Map<String, String> seenCalls = new java.util.HashMap<String, String>();
            // 不是轮数上限，是「原地打转」保险：同一调用连续这么多次返回完全一样的结果，
            // 说明模型卡住了，再转下去只是烧 token。
            int stallCount = 0;
            String lastKey = null;

            // 不设轮数上限：复杂的多步任务（读屏→定位→点击→复查）本来就没法预估步数。
            // 防止原地打转靠下面的「相同调用 + 相同结果」检测，以及用户随时可点停止。
            while (true) {
                if (cancelled) { sink.onError("已停止"); return; }
                final boolean[] gotToolCall = {false};
                final StringBuilder textBuf = new StringBuilder();

                // 每步请求前压一次：工具结果是最容易把上下文撑爆的地方，
                // 一步步累积下来，到这一步才发现发不出去就晚了
                compact(history, CTX_SOFT, CTX_TARGET, sink);

                LlmClient.Result r = requestWithRetry(history, toolSchemas, sink,
                        new LlmClient.StreamSink() {
                            public void onDelta(String delta) {
                                textBuf.append(delta);
                                sink.onDelta(delta);
                            }
                            public void onToolCallStart(int index, String id, String name) {
                                gotToolCall[0] = true;
                            }
                            public void onToolCallDelta(int index, String argsDelta) {
                                // 参数增量：不打断文本流，累积在 r.toolCalls 中
                            }
                        });

                if (r.hasToolCalls()) {
                    appendAssistantToolCalls(history, r);

                    // 同一步里模型可能一次要调多个工具。只读的那几个可以并发跑，
                    // 但结果必须按模型给出的顺序回写，否则 tool_call_id 会对不上。
                    final int count = r.toolCalls.size();
                    final String[] results = new String[count];
                    if (count > 1) {
                        java.util.List<java.util.concurrent.Future<?>> futures =
                                new java.util.ArrayList<java.util.concurrent.Future<?>>();
                        for (int i = 0; i < count; i++) {
                            final int idx = i;
                            final LlmClient.ToolCall c = r.toolCalls.get(i);
                            if (!isReadOnlyTool(c.name)) continue;
                            futures.add(POOL.submit(new Runnable() {
                                public void run() {
                                    results[idx] = tools.call(c.name, c.args);
                                }
                            }));
                        }
                        for (int i = 0; i < futures.size(); i++) {
                            try {
                                futures.get(i).get();
                            } catch (Exception ignored) {}
                        }
                    }

                    for (int i = 0; i < count; i++) {
                        LlmClient.ToolCall tc = r.toolCalls.get(i);
                        sink.onToolCall(tc.name, tc.args);
                        String result = results[i];
                        if (result == null) result = tools.call(tc.name, tc.args);
                        sink.onToolResult(tc.name, result);

                        String key = tc.name + "\u0000" + tc.args;
                        String prev = seenCalls.put(key, result);
                        if (prev != null && prev.equals(result)) {
                            stallCount = key.equals(lastKey) ? stallCount + 1 : 1;
                            lastKey = key;
                            result = result + "\n\n（这与上一次完全相同的调用结果一致，说明没有进展。"
                                    + "请换参数、换工具，或者直接结束本轮。）";
                        } else {
                            stallCount = 0;
                            lastKey = null;
                        }

                        JSONObject tr = new JSONObject();
                        tr.put("role", "tool");
                        tr.put("tool_call_id", tc.id);
                        tr.put("content", result);
                        history.put(tr);
                    }
                    if (stallCount >= 6) {
                        sink.onError("同一个工具连续 " + stallCount + " 次返回完全相同的结果，"
                                + "判断为卡住了，已停下。可以换个说法让我重试。");
                        return;
                    }
                    continue;
                }

                if (cancelled) {
                    // 用户点了停止：已经流出来的文字不能丢，落进历史再说
                    String partial = textBuf.length() > 0 ? textBuf.toString()
                            : (r.content == null ? "" : r.content);
                    if (partial.length() > 0) {
                        JSONObject a = new JSONObject();
                        a.put("role", "assistant");
                        a.put("content", partial);
                        history.put(a);
                    }
                    sink.onError("已停止");
                    return;
                }

                if (r.content != null && r.content.length() > 0) {
                    JSONObject a = new JSONObject();
                    a.put("role", "assistant");
                    a.put("content", r.content);
                    history.put(a);
                }
                return;
            }
        } catch (Exception e) {
            sink.onError("错误: " + e.getMessage());
        }
    }

    /** 往历史里插一条给模型的提示（不进界面）。 */
    private void note(JSONArray history, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("role", "system");
            o.put("content", text);
            history.put(o);
        } catch (Exception ignored) {}
    }

    /** 超预算就压缩，并把结果告诉界面。 */
    private void compact(JSONArray history, int soft, int target, Sink sink) {
        try {
            HistoryCompactor.Report rep = HistoryCompactor.fit(history, soft, target);
            if (rep.changed) sink.onCompact(rep);
        } catch (Throwable ignored) {
            // 压缩本身失败不能影响这次请求，宁可原样发出去
        }
    }

    /** 请求被拒且原因是上下文过长时，激进压缩后重试一次。 */
    private LlmClient.Result requestWithRetry(JSONArray history, JSONArray toolSchemas,
                                              Sink sink, LlmClient.StreamSink streamSink)
            throws Exception {
        int attempt = 0;
        while (true) {
            try {
                return client.chatStream(history, toolSchemas, streamSink);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();

                // 上下文超长：压到极限再试一次，与 DSH 的 request-error 处理同思路
                if (isContextOverflow(msg)) {
                    HistoryCompactor.Report rep = HistoryCompactor.fit(history, 1, CTX_EMERGENCY);
                    if (!rep.changed) throw e;
                    sink.onCompact(rep);
                    return client.chatStream(history, toolSchemas, streamSink);
                }

                // 网络/限流类错误：指数退避重试，最多三次
                if (isRetryable(msg) && attempt < 2) {
                    long wait = 800L << attempt;    // 800ms → 1600ms
                    attempt++;
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
    }

    /** 网络抖动、限流、服务端 5xx 这类可以退了再试。 */
    private static boolean isRetryable(String msg) {
        String m = msg == null ? "" : msg.toLowerCase();
        if (m.contains("timeout") || m.contains("timed out")) return true;
        if (m.contains("connection") || m.contains("reset by peer")) return true;
        if (m.contains("429") || m.contains("too many requests") || m.contains("rate limit")) return true;
        if (m.contains("502") || m.contains("503") || m.contains("504")) return true;
        if (m.contains("temporarily") || m.contains("overloaded")) return true;
        return false;
    }

    /**
     * 判断错误是不是「上下文超长」。
     *
     * <p>各家服务端措辞不一：DeepSeek 的 `context length exceeded`、
     * OpenAI 的 `maximum context length`、还有 `too many tokens`、
     * `prompt is too long` 等，都归到这一类。
     */
    private static boolean isContextOverflow(String msg) {
        String m = msg.toLowerCase();
        if (m.contains("context") && (m.contains("length") || m.contains("exceed")
                || m.contains("too long") || m.contains("maximum"))) return true;
        if (m.contains("too many tokens") || m.contains("token limit")) return true;
        if (m.contains("prompt is too long") || m.contains("request too large")) return true;
        if (m.contains("maximum context") || m.contains("context_length_exceeded")) return true;
        return false;
    }

    private void ensureSystem(JSONArray history) throws Exception {
        if (history.length() > 0) {
            JSONObject first = history.optJSONObject(0);
            if (first != null && "system".equals(first.optString("role"))) {
                // 作用域/无障碍开关变化时刷新提示词
                first.put("content", systemPrompt);
                return;
            }
        }
        JSONObject s = new JSONObject();
        s.put("role", "system");
        s.put("content", systemPrompt);
        JSONArray shifted = new JSONArray();
        shifted.put(s);
        for (int i = 0; i < history.length(); i++) shifted.put(history.opt(i));
        while (history.length() > 0) history.remove(0);
        for (int i = 0; i < shifted.length(); i++) history.put(shifted.opt(i));
    }

    private void appendAssistantToolCalls(JSONArray history, LlmClient.Result r) throws Exception {
        JSONObject a = new JSONObject();
        a.put("role", "assistant");
        a.put("content", r.content == null ? "" : r.content);
        JSONArray tcArr = new JSONArray();
        for (LlmClient.ToolCall tc : r.toolCalls) {
            JSONObject t = new JSONObject();
            t.put("id", tc.id);
            t.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", tc.name);
            fn.put("arguments", tc.args);
            t.put("function", fn);
            tcArr.put(t);
        }
        a.put("tool_calls", tcArr);
        history.put(a);
    }
}
