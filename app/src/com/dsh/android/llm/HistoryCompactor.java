package com.dsh.android.llm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩器。
 *
 * <p>对话越聊越长，尤其是 a11y.dump / web.fetch 这类工具一次就吐几万字，
 * 请求体很快超过模型窗口，服务端直接 400，表现就是「消息发不出去」。
 *
 * <p>这里做三级渐进压缩，只在超过预算时才动手：
 * <ol>
 *   <li>单条消息瘦身：超长内容留头尾，中间标出省略量；</li>
 *   <li>工具结果瘦身：dump / 抓网页的结果保留首尾关键信息；</li>
 *   <li>早期轮次折叠：从最旧的一轮开始，整轮换成摘要，直到落回预算内。</li>
 * </ol>
 *
 * <p>折叠按「轮」切分（以 user 消息为起点），绝不会把 assistant 的 tool_calls
 * 和对应的 tool 结果拆开 —— 那种断裂历史会被接口直接判为非法请求。
 */
public final class HistoryCompactor {

    /** 摘要消息的标记前缀，用于识别并替换旧摘要，避免越压越长。 */
    public static final String SUMMARY_MARK = "【较早对话摘要（自动生成）】";

    private static final int TOOL_MAX = 1200;      // 单条工具结果超过这个长度就瘦身
    private static final int TOOL_HEAD = 700;
    private static final int TOOL_TAIL = 300;

    private static final int MSG_MAX = 6000;       // 普通消息上限
    private static final int MSG_HEAD = 4000;
    private static final int MSG_TAIL = 1500;

    private static final int SUMMARY_MAX = 2400;   // 摘要正文上限
    private static final int MIN_KEEP_TURNS = 3;   // 至少保留最后 3 轮完整内容

    /** 一次压缩的结果，用于向界面反馈。 */
    public static final class Report {
        public boolean changed;
        public int tokensBefore;
        public int tokensAfter;
        public int slimmedMessages;
        public int foldedTurns;
        public boolean overflow;   // 压到底仍然超预算

        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (foldedTurns > 0) {
                sb.append("已折叠 ").append(foldedTurns).append(" 轮较早对话");
            }
            if (slimmedMessages > 0) {
                if (sb.length() > 0) sb.append("、");
                sb.append("精简 ").append(slimmedMessages).append(" 条长内容");
            }
            if (sb.length() == 0) sb.append("已压缩上下文");
            sb.append("  ")
              .append(fmt(tokensBefore)).append(" → ").append(fmt(tokensAfter)).append(" tokens");
            return sb.toString();
        }

        private static String fmt(int n) {
            if (n < 1000) return String.valueOf(n);
            return (n / 1000) + "." + ((n % 1000) / 100) + "k";
        }
    }

    private HistoryCompactor() {}

    // ---------------- 估算 ----------------

    /**
     * 粗略估算 token 数。汉字按 1 个、其余字符按 0.28 个计，整体偏保守
     * （宁可早一点压缩，也不要发出去被拒）。
     */
    public static int estimate(String s) {
        if (s == null || s.length() == 0) return 0;
        int cjk = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            else if (c >= 0x3000 && c <= 0x303F) cjk++;
            else if (c >= 0xFF00 && c <= 0xFFEF) cjk++;
        }
        int other = s.length() - cjk;
        return (int) (cjk * 1.0 + other * 0.28) + 1;
    }

    public static int estimate(JSONArray history) {
        int total = 0;
        for (int i = 0; i < history.length(); i++) {
            JSONObject m = history.optJSONObject(i);
            if (m != null) total += estimateMessage(m);
        }
        return total;
    }

    public static int estimateMessage(JSONObject m) {
        int t = 4;
        Object c = m.opt("content");
        if (c instanceof String) {
            t += estimate((String) c);
        } else if (c != null && c != JSONObject.NULL) {
            t += estimate(c.toString());
        }
        JSONArray tc = m.optJSONArray("tool_calls");
        if (tc != null) t += estimate(tc.toString());
        return t;
    }

    // ---------------- 入口 ----------------

    /**
     * 把历史压到 sofTBudget 以内。
     *
     * @param softBudget 触发压缩的阈值（估算 token）
     * @param targetBudget 压缩目标，低于此值就停手
     */
    public static Report fit(JSONArray history, int softBudget, int targetBudget) {
        Report rep = new Report();
        try {
            return fitInternal(history, softBudget, targetBudget, rep);
        } catch (Exception e) {
            return rep;   // 压缩失败就当没发生，绝不阻断请求
        }
    }

    private static Report fitInternal(JSONArray history, int softBudget, int targetBudget,
                                      Report rep) throws Exception {
        rep.tokensBefore = estimate(history);
        rep.tokensAfter = rep.tokensBefore;
        if (rep.tokensBefore <= softBudget) return rep;

        // 第一级：超长单条 + 工具结果瘦身
        rep.slimmedMessages = slimAll(history);
        int after = estimate(history);
        if (after <= targetBudget) {
            rep.changed = rep.slimmedMessages > 0;
            rep.tokensAfter = after;
            return rep;
        }

        // 第二级：早期轮次折叠为摘要
        rep.foldedTurns = foldEarlyTurns(history, targetBudget);
        rep.tokensAfter = estimate(history);
        rep.changed = rep.slimmedMessages > 0 || rep.foldedTurns > 0;
        rep.overflow = rep.tokensAfter > softBudget * 2;
        return rep;
    }

    // ---------------- 第二级：瘦身 ----------------

    private static int slimAll(JSONArray history) throws Exception {
        int n = 0;
        for (int i = 0; i < history.length(); i++) {
            JSONObject m = history.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            Object c = m.opt("content");
            if (!(c instanceof String)) continue;
            String s = (String) c;
            if (s.length() == 0) continue;

            boolean isTool = "tool".equals(role);
            int max = isTool ? TOOL_MAX : MSG_MAX;
            int head = isTool ? TOOL_HEAD : MSG_HEAD;
            int tail = isTool ? TOOL_TAIL : MSG_TAIL;

            if (s.length() > max) {
                int omitted = s.length() - head - tail;
                String cut = s.substring(0, head)
                        + "\n…[已省略 " + omitted + " 字]…\n"
                        + s.substring(s.length() - tail);
                m.put("content", cut);
                n++;
            }
        }
        return n;
    }

    // ---------------- 第三级：折叠早期轮次 ----------------

    private static int foldEarlyTurns(JSONArray history, int targetBudget) throws Exception {
        // 已有摘要的正文要延续下去，否则每压一次就丢掉上一批的记忆
        String previous = existingSummaryBody(history);

        JSONArray removed = new JSONArray();
        int folded = 0;

        while (true) {
            List<Integer> starts = turnStarts(history);
            if (starts.size() <= MIN_KEEP_TURNS) break;
            if (estimate(history) <= targetBudget) break;

            // 折叠最旧的一轮：从它起点到下一轮起点（含其中的 tool_calls / tool 结果）
            int first = starts.get(0);
            int second = starts.get(1);
            for (int i = first; i < second; i++) {
                JSONObject m = history.optJSONObject(i);
                if (m != null) removed.put(m);
            }
            for (int i = second - 1; i >= first; i--) history.remove(i);
            folded++;
        }

        if (folded == 0) return 0;

        String text = summaryText(removed, previous);
        insertSummary(history, text);
        return folded;
    }

    /** 取出历史中已有摘要消息的正文（去掉标记与说明）。 */
    private static String existingSummaryBody(JSONArray history) {
        for (int i = 0; i < history.length(); i++) {
            JSONObject m = history.optJSONObject(i);
            if (m == null) continue;
            String c = m.optString("content", "");
            if (!c.startsWith(SUMMARY_MARK)) continue;
            int nl = c.indexOf('\n');
            return nl < 0 ? "" : c.substring(nl + 1).trim();
        }
        return "";
    }

    /** 轮次起点：role == user 的消息下标（跳过开头的 system 与旧摘要）。 */
    private static List<Integer> turnStarts(JSONArray history) {
        List<Integer> starts = new ArrayList<Integer>();
        for (int i = 0; i < history.length(); i++) {
            JSONObject m = history.optJSONObject(i);
            if (m == null) continue;
            if (!"user".equals(m.optString("role"))) continue;
            String c = m.optString("content", "");
            if (c.startsWith(SUMMARY_MARK)) continue;
            starts.add(i);
        }
        return starts;
    }

    /** 把被折叠的消息压成可读摘要；预算不够时优先保留较近的内容。 */
    private static String summaryText(JSONArray removed, String previous) {
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < removed.length(); i++) {
            JSONObject m = removed.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            String c = m.optString("content", "");
            if ("user".equals(role)) {
                if (c.length() == 0) continue;
                lines.add("· 用户：" + clip(c, 150));
            } else if ("assistant".equals(role)) {
                JSONArray tc = m.optJSONArray("tool_calls");
                if (c.trim().length() > 0) {
                    lines.add("· 助手：" + clip(c, 200));
                } else if (tc != null && tc.length() > 0) {
                    StringBuilder names = new StringBuilder();
                    for (int k = 0; k < tc.length(); k++) {
                        JSONObject t = tc.optJSONObject(k);
                        if (t == null) continue;
                        JSONObject fn = t.optJSONObject("function");
                        if (fn == null) continue;
                        if (names.length() > 0) names.append("、");
                        names.append(fn.optString("name"));
                    }
                    if (names.length() > 0) lines.add("· 助手调用了工具：" + names);
                }
            }
            // tool 结果不进摘要：它们通常又长又只对当时那一步有意义
        }

        // 从最近往前装，超预算就停
        StringBuilder sb = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (sb.length() + line.length() + 1 > SUMMARY_MAX) break;
            sb.insert(0, line + "\n");
        }

        // 旧摘要接在最前面（它本身也是更早的内容）
        if (previous != null && previous.length() > 0) {
            int room = SUMMARY_MAX - sb.length();
            if (room > 80) {
                String keep = previous.length() > room
                        ? previous.substring(previous.length() - room) : previous;
                sb.insert(0, keep.trim() + "\n");
            }
        }
        return sb.toString().trim();
    }

    private static void insertSummary(JSONArray history, String text) throws Exception {
        // 先移除旧摘要（保留的轮次里可能已有）
        for (int i = history.length() - 1; i >= 0; i--) {
            JSONObject m = history.optJSONObject(i);
            if (m == null) continue;
            if ("user".equals(m.optString("role"))
                    && m.optString("content", "").startsWith(SUMMARY_MARK)) {
                history.remove(i);
            }
        }
        if (text.length() == 0) return;

        JSONObject sm = new JSONObject();
        sm.put("role", "user");
        sm.put("content", SUMMARY_MARK + "\n以下是本次会话中较早的一段对话，"
                + "原文因长度超出上下文窗口已被折叠。请在需要时把它当作背景资料，"
                + "不要复述这份摘要：\n" + text);

        // 放在 system 之后、第一个真实 user 之前
        int pos = 0;
        for (int i = 0; i < history.length(); i++) {
            JSONObject m = history.optJSONObject(i);
            if (m != null && "system".equals(m.optString("role"))) pos = i + 1;
        }
        JSONArray rebuilt = new JSONArray();
        for (int i = 0; i < pos; i++) rebuilt.put(history.opt(i));
        rebuilt.put(sm);
        for (int i = pos; i < history.length(); i++) rebuilt.put(history.opt(i));
        while (history.length() > 0) history.remove(0);
        for (int i = 0; i < rebuilt.length(); i++) history.put(rebuilt.opt(i));
    }

    private static String clip(String s, int max) {
        String flat = s.replace("\n", " ").replace("\r", " ").trim();
        if (flat.length() <= max) return flat;
        return flat.substring(0, max) + "…";
    }
}
