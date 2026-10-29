import com.dsh.android.llm.HistoryCompactor;
import org.json.JSONArray;
import org.json.JSONObject;

/** HistoryCompactor 的纯 JVM 测试（用 org.json 真实实现，不依赖设备）。 */
public class CompactTest {

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String name) {
        if (cond) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name); }
    }

    static JSONObject msg(String role, String content) {
        JSONObject m = new JSONObject();
        try {
            m.put("role", role);
            m.put("content", content);
        } catch (Exception e) { throw new RuntimeException(e); }
        return m;
    }

    /** 造一轮：user -> assistant(tool_calls) -> tool result -> assistant 文本 */
    static void addTurn(JSONArray h, int i, String toolBody) {
        h.put(msg("user", "第" + i + "个问题：帮我看看这个东西是怎么回事，需要你认真分析一下。"));
        try {
            JSONObject a = new JSONObject();
            a.put("role", "assistant");
            a.put("content", "好的，我先查一下。");
            JSONArray tc = new JSONArray();
            JSONObject one = new JSONObject();
            one.put("id", "call_" + i);
            one.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", "web.fetch");
            fn.put("arguments", "{\"url\":\"https://example.com/" + i + "\"}");
            one.put("function", fn);
            tc.put(one);
            a.put("tool_calls", tc);
            h.put(a);

            JSONObject t = new JSONObject();
            t.put("role", "tool");
            t.put("tool_call_id", "call_" + i);
            t.put("content", "结果" + i + "：" + toolBody);
            h.put(t);
        } catch (Exception e) { throw new RuntimeException(e); }
        h.put(msg("assistant", "第" + i + "轮的分析结论是：一切正常，继续推进。"));
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        // 1. 预算内不动手
        JSONArray h1 = new JSONArray();
        h1.put(msg("system", "你是助手"));
        addTurn(h1, 1, "短结果");
        int before = HistoryCompactor.estimate(h1);
        HistoryCompactor.Report r1 = HistoryCompactor.fit(h1, 24000, 14000);
        ok(!r1.changed, "预算内不压缩");
        ok(h1.length() == 5, "预算内消息数不变 (" + h1.length() + ")");
        ok(HistoryCompactor.estimate(h1) == before, "预算内 token 估算不变");

        // 2. 只有超长工具结果 -> 只瘦身，不折叠
        JSONArray h2 = new JSONArray();
        h2.put(msg("system", "你是助手"));
        addTurn(h2, 1, repeat("很长的网页正文内容。", 4000));
        int len2 = h2.optJSONObject(3).optString("content").length();
        HistoryCompactor.Report r2 = HistoryCompactor.fit(h2, 24000, 14000);
        String slim2 = h2.optJSONObject(3).optString("content");
        ok(r2.slimmedMessages >= 1, "超长工具结果触发瘦身");
        ok(slim2.length() < len2, "工具结果变短 " + len2 + " -> " + slim2.length());
        ok(slim2.contains("已省略"), "标注了省略字数");
        ok(!r2.changed || r2.foldedTurns == 0, "轮次不足时不折叠");

        // 3. 大量轮次 -> 折叠，且头部/尾部保留
        JSONArray h3 = new JSONArray();
        h3.put(msg("system", "你是助手"));
        for (int i = 1; i <= 30; i++) addTurn(h3, i, repeat("数据", 400));
        int turnsBefore = h3.length();
        HistoryCompactor.Report r3 = HistoryCompactor.fit(h3, 24000, 14000);
        int afterEst = HistoryCompactor.estimate(h3);
        ok(r3.changed, "超预算触发压缩");
        ok(r3.foldedTurns > 0, "折叠了早期轮次 (" + r3.foldedTurns + ")");
        ok(afterEst <= 24000, "压缩后回到预算内 " + r3.tokensBefore + " -> " + afterEst);
        ok(h3.length() < turnsBefore, "消息数减少 " + turnsBefore + " -> " + h3.length());
        ok(h3.optJSONObject(0).optString("role").equals("system"), "system 仍在最前");
        String firstUser = firstRealUser(h3);
        ok(!firstUser.startsWith("第1个问题"), "最旧的轮次已被折叠（首条真实 user 已不是第 1 轮）: "
                + firstUser.substring(0, Math.min(12, firstUser.length())));
        ok(hasSummary(h3), "插入了摘要消息");
        String lastTurnText = h3.optJSONObject(h3.length() - 1).optString("content");
        ok(lastTurnText.contains("第30轮"), "最新一轮内容完整保留");

        // 4. 结构完整性：assistant(tool_calls) 必须跟着对应 tool 结果
        ok(intactToolPairs(h3), "tool_calls 与 tool 结果未被拆散");

        // 5. 摘要可延续：压两次，旧摘要内容不丢
        String sum1 = summaryBody(h3);
        JSONArray h4 = new JSONArray(h3.toString());
        for (int i = 31; i <= 70; i++) addTurn(h4, i, repeat("数据", 400));
        HistoryCompactor.Report r4 = HistoryCompactor.fit(h4, 24000, 14000);
        ok(r4.foldedTurns > 0, "第二轮继续折叠");
        ok(countSummaries(h4) == 1, "摘要消息始终只有一条（不越压越长）: " + countSummaries(h4));
        String sum2 = summaryBody(h4);
        ok(sum2.length() > 0 && sum2.length() <= 2600, "摘要长度受控 " + sum2.length());
        ok(sum1.length() == 0 || sum2.length() > 0, "摘要非空");
        ok(intactToolPairs(h4), "二次压缩后结构仍完整");

        // 6. 极端：软预算极小 -> 仍不崩、仍合法
        JSONArray h5 = new JSONArray(new JSONArray().put(msg("system", "sys")).toString());
        for (int i = 1; i <= 8; i++) addTurn(h5, i, repeat("xyz", 200));
        HistoryCompactor.Report r5 = HistoryCompactor.fit(h5, 1, 6000);
        ok(intactToolPairs(h5), "紧急压缩后结构仍完整");
        ok(h5.optJSONObject(0).optString("role").equals("system"), "紧急压缩保留 system");
        ok(r5.tokensAfter > 0, "报告了压缩后 token 数");

        // 7. 幂等：压缩完再压一次不该再变
        int est6 = HistoryCompactor.estimate(h3);
        HistoryCompactor.Report r6 = HistoryCompactor.fit(h3, 24000, 14000);
        ok(!r6.changed, "已在预算内，重复压缩无副作用");
        ok(HistoryCompactor.estimate(h3) == est6, "重复压缩 token 不变");

        // 8. token 估算：中文比 ASCII 更耗
        ok(HistoryCompactor.estimate("中文中文中文") > HistoryCompactor.estimate("abcdefghij"),
                "中文估算高于等长 ASCII");

        System.out.println("\n==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    static String firstRealUser(JSONArray h) {
        for (int i = 0; i < h.length(); i++) {
            JSONObject m = h.optJSONObject(i);
            if (!"user".equals(m.optString("role"))) continue;
            String c = m.optString("content", "");
            if (c.startsWith(HistoryCompactor.SUMMARY_MARK)) continue;
            return c;
        }
        return "";
    }

    static boolean hasSummary(JSONArray h) { return countSummaries(h) > 0; }

    static int countSummaries(JSONArray h) {
        int n = 0;
        for (int i = 0; i < h.length(); i++) {
            JSONObject m = h.optJSONObject(i);
            if ("user".equals(m.optString("role"))
                    && m.optString("content", "").startsWith(HistoryCompactor.SUMMARY_MARK)) n++;
        }
        return n;
    }

    static String summaryBody(JSONArray h) {
        for (int i = 0; i < h.length(); i++) {
            JSONObject m = h.optJSONObject(i);
            String c = m.optString("content", "");
            if (c.startsWith(HistoryCompactor.SUMMARY_MARK)) {
                int nl = c.indexOf('\n');
                return nl < 0 ? "" : c.substring(nl + 1);
            }
        }
        return "";
    }

    /** 每个 tool 消息都必须能找到前面最近一条 assistant 的 tool_calls 里的 id。 */
    static boolean intactToolPairs(JSONArray h) {
        java.util.Set<String> declared = new java.util.HashSet<String>();
        for (int i = 0; i < h.length(); i++) {
            JSONObject m = h.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            if ("assistant".equals(role)) {
                JSONArray tc = m.optJSONArray("tool_calls");
                declared.clear();
                if (tc != null) {
                    for (int k = 0; k < tc.length(); k++) {
                        JSONObject t = tc.optJSONObject(k);
                        if (t != null) declared.add(t.optString("id"));
                    }
                }
            } else if ("tool".equals(role)) {
                if (!declared.contains(m.optString("tool_call_id"))) return false;
            } else {
                declared.clear();
            }
        }
        return true;
    }
}
