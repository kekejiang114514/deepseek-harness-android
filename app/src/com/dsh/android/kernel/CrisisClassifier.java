package com.dsh.android.kernel;

import org.json.JSONObject;

/**
 * 危机判断：交给模型，而不是关键词表。
 *
 * <p>关键词只能抓字面表达，像「我不想再撑了」「想消失」这类会漏掉；
 * 反过来「这 bug 让我想死」又会误伤。这里用一次极短的分类请求解决：
 * 只让模型回答 risk=yes/no，参数压到最小，成本与延迟都可忽略。
 *
 * <p>分类器只做判断，不生成安慰话术——话术是固定的，避免模型自由发挥
 * 给出不合适的建议。
 */
public final class CrisisClassifier {

    private CrisisClassifier() {}

    /** 系统提示：要求严格输出一行 JSON。 */
    public static String systemPrompt() {
        return "你是一个安全检查器。判断用户这条消息是否表达了自伤、轻生或"
                + "严重的自我伤害意图。\n"
                + "只输出一行 JSON，不要解释、不要安慰、不要任何多余文字：\n"
                + "{\"risk\":true} 或 {\"risk\":false}\n"
                + "判断标准：明确的求死/自伤意图才算 true；只是情绪低落、抱怨、"
                + "骂人或比喻（例如「这 bug 让我想死」）算 false。\n"
                + "拿不准时输出 false。";
    }

    /** 用户侧提示：把待判断的原文包进去。 */
    public static String userPrompt(String text) {
        return "待判断的消息：\n" + (text == null ? "" : text);
    }

    /**
     * 解析模型回答。
     *
     * <p>模型有时会带 markdown 代码块或多余空格，所以不做严格 JSON 解析：
     * 先找 "risk"，再看紧跟的 true/false。解析不出来一律按 false 处理——
     * 误弹一次求助电话会打断对话，漏一次只是没有额外提示。
     */
    public static boolean parseResponse(String raw) {
        if (raw == null) return false;
        String s = raw.toLowerCase(java.util.Locale.US);
        int i = s.indexOf("risk");
        if (i < 0) return false;
        String tail = s.substring(i, Math.min(s.length(), i + 24));
        int t = tail.indexOf("true");
        int f = tail.indexOf("false");
        if (t >= 0 && (f < 0 || t < f)) return true;
        return false;
    }

    /** 从分类结果里取一句给用户看的理由（可为空）。 */
    public static String reasonOf(String raw) {
        try {
            JSONObject o = new JSONObject(raw.trim());
            return o.optString("reason", "");
        } catch (Throwable t) {
            return "";
        }
    }
}
