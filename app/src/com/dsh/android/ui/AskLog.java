package com.dsh.android.ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从对话历史里重建选择题卡片。
 *
 * <p>不另外存一份卡片数据：历史里本来就完整记录了「调用了 ui.ask，参数是这些问题和选项」
 * （assistant 消息的 tool_calls）以及「用户选了什么」（紧随其后的 tool 消息）。
 * 从历史重建有两个好处——切会话、重启 App 后卡片自然还在；上下文被压缩后
 * 也不会出现「卡片配错答案」的错位，因为每张卡都自带自己的问题。
 */
public final class AskLog {

    /** 一张要回放的卡片。 */
    public static final class Card {
        public String question = "";
        public String[] options = new String[0];
        public boolean multi;
        /** 用户的选择；空串表示没选（超时、跳过、或结果还没落盘）。 */
        public String answer = "";

        public boolean answered() { return answer.length() > 0; }
    }

    private AskLog() {}

    /** 历史下标 → 这条 assistant 消息里发生的选择题（正常最多一张）。 */
    public static Map<Integer, List<Card>> byIndex(JSONArray hist) {
        Map<Integer, List<Card>> map = new HashMap<Integer, List<Card>>();
        if (hist == null) return map;

        // 先把所有 tool 结果按 tool_call_id 建索引：答案就在里面
        Map<String, String> results = ids(hist);
        for (int i = 0; i < hist.length(); i++) {
            JSONObject m = hist.optJSONObject(i);
            if (m == null || !"assistant".equals(m.optString("role"))) continue;
            JSONArray tcs = m.optJSONArray("tool_calls");
            if (tcs == null) continue;
            for (int k = 0; k < tcs.length(); k++) {
                JSONObject t = tcs.optJSONObject(k);
                if (t == null) continue;
                JSONObject fn = t.optJSONObject("function");
                if (fn == null) continue;
                String name = fn.optString("name", "");
                if (!"ui__ask".equals(name) && !"ui.ask".equals(name)) continue;
                Card c = parse(fn.optString("arguments", ""));
                if (c == null) continue;
                c.answer = pick(t.optString("id", ""), results);
                List<Card> list = map.get(Integer.valueOf(i));
                if (list == null) {
                    list = new ArrayList<Card>();
                    map.put(Integer.valueOf(i), list);
                }
                list.add(c);
            }
        }
        return map;
    }

    /** 按历史顺序取出所有选择题卡片。 */
    public static List<Card> replay(JSONArray hist) {
        List<Card> out = new ArrayList<Card>();
        if (hist == null) return out;
        List<Integer> keys = new ArrayList<Integer>(byIndex(hist).keySet());
        java.util.Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            out.addAll(byIndex(hist).get(keys.get(i)));
        }
        return out;
    }

    private static Map<String, String> ids(JSONArray hist) {
        Map<String, String> results = new HashMap<String, String>();

        for (int i = 0; i < hist.length(); i++) {
            JSONObject m = hist.optJSONObject(i);
            if (m == null || !"tool".equals(m.optString("role"))) continue;
            String id = m.optString("tool_call_id", "");
            if (id.length() == 0) continue;
            results.put(id, m.optString("content", ""));
        }
        return results;
    }

    /** 解析 ui.ask 的参数；解析不出来（旧版本格式损坏）就返回 null 跳过。 */
    private static Card parse(String args) {
        if (args == null || args.length() == 0) return null;
        try {
            JSONObject a = new JSONObject(args);
            String q = a.optString("question", "");
            if (q.length() == 0) return null;
            JSONArray oa = a.optJSONArray("options");
            if (oa == null || oa.length() == 0) return null;
            Card c = new Card();
            c.question = q;
            c.multi = a.optBoolean("multi", false);
            c.options = new String[oa.length()];
            for (int i = 0; i < oa.length(); i++) c.options[i] = oa.optString(i, "");
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从对应的 tool 结果里取 selected；没选/失败一律当空。 */
    private static String pick(String callId, Map<String, String> results) {
        String content = results.get(callId);
        if (content == null) return "";
        try {
            JSONObject o = new JSONObject(content);
            if (!o.optBoolean("ok", false)) return "";
            return o.optString("selected", "");
        } catch (Exception e) {
            return "";
        }
    }
}
