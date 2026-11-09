package com.dsh.android.llm;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 聊天请求体的构造。抽成独立类是为了能用纯 JVM 测试锁住两件容易写错的事：
 *
 * <ol>
 *   <li>「推理强度」要真的发给服务端（{@code reasoning_effort}），
 *       而不是只把 {@code max_tokens} 调小——后者只限制输出长度，不影响思考深度。</li>
 *   <li>带 {@code tools} 时，assistant 历史里的 {@code reasoning_content}
 *       必须原样回传。官方文档写明：凡是带 tools 的请求，后续每一轮都要回传
 *       reasoning_content（即使该轮没有发生工具调用），否则服务端返回 400。</li>
 * </ol>
 *
 * <p>只依赖 org.json，不碰 Android API。
 */
public final class ChatBody {

    private ChatBody() {}

    /**
     * 组装请求体。
     *
     * @param model      模型名
     * @param messages   历史消息（可能含界面自用字段，这里会筛掉）
     * @param tools      工具声明；null 或空表示不启用工具
     * @param stream     是否流式
     * @param maxTokens  输出上限
     * @param effort     推理强度；null 表示不显式指定（取服务端默认）
     * @param thinkingOff 是否明确关闭思考模式
     */
    public static JSONObject build(String model, JSONArray messages, JSONArray tools,
                                   boolean stream, int maxTokens,
                                   String effort, boolean thinkingOff) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);

        boolean hasTools = tools != null && tools.length() > 0;

        // 历史里可能混有界面专用的记录（role 不是标准角色），
        // 直接发过去会被服务端当成非法格式整单拒绝，这里先筛一遍。
        JSONArray safe = new JSONArray();
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role", "");
                if (!"system".equals(role) && !"user".equals(role)
                        && !"assistant".equals(role) && !"tool".equals(role)) {
                    continue;
                }
                // 只保留接口认识的字段：历史里还存着 reasoning / interrupted / hidden
                // 这些界面自用标记，混进请求里某些服务端会直接整单拒绝。
                JSONObject clean = new JSONObject();
                clean.put("role", role);
                if (m.has("content")) clean.put("content", m.opt("content"));
                if (m.has("tool_calls")) clean.put("tool_calls", m.opt("tool_calls"));
                if (m.has("tool_call_id")) clean.put("tool_call_id", m.opt("tool_call_id"));
                if (m.has("name")) clean.put("name", m.opt("name"));
                // 带 tools 时思考链必须回传，否则下一轮 400。
                // reasoning 为空就不加，免得给服务端塞一个空串。
                if (hasTools && "assistant".equals(role)) {
                    String r = m.optString("reasoning", "");
                    if (r.length() > 0) clean.put("reasoning_content", r);
                }
                safe.put(clean);
            }
        }
        body.put("messages", safe);
        body.put("max_tokens", maxTokens);

        if (effort != null && effort.length() > 0) body.put("reasoning_effort", effort);
        if (thinkingOff) {
            JSONObject think = new JSONObject();
            think.put("type", "disabled");
            body.put("thinking", think);
        }

        if (hasTools) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        body.put("stream", stream);
        return body;
    }
}
