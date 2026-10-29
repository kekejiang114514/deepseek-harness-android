import com.dsh.android.ui.AskLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * 选择题卡片从历史重建的测试（纯 JVM）。
 *
 * <p>盯的是「错位」这类问题：卡片显示的必须是它自己当时那个问题，
 * 不能因为中间有别的工具调用、或历史被压缩过，就配到别人的答案上。
 */
public class AskLogTest {

    static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        t1_basic();
        t2_otherToolsBetween();
        t3_compacted();
        t4_missingResult();
        t5_cancelled();
        t6_multiAnswer();
        t7_badArguments();
        t8_oldName();
        System.out.println("\n==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    // ---- 构造工具 ----

    static JSONObject user(String t) throws Exception {
        return new JSONObject().put("role", "user").put("content", t);
    }

    static JSONObject assistant(String t) throws Exception {
        return new JSONObject().put("role", "assistant").put("content", t);
    }

    /** 一条 assistant 消息，带若干工具调用。 */
    static JSONObject calls(Object[]... items) throws Exception {
        JSONArray tcs = new JSONArray();
        for (int i = 0; i < items.length; i++) {
            String id = (String) items[i][0];
            String name = (String) items[i][1];
            String args = (String) items[i][2];
            tcs.put(new JSONObject().put("id", id).put("type", "function")
                    .put("function", new JSONObject().put("name", name)
                            .put("arguments", args)));
        }
        return new JSONObject().put("role", "assistant").put("content", "")
                .put("tool_calls", tcs);
    }

    static JSONObject tool(String id, String content) throws Exception {
        return new JSONObject().put("role", "tool").put("tool_call_id", id)
                .put("content", content);
    }

    static String askArgs(String q, String... opts) throws Exception {
        JSONArray oa = new JSONArray();
        for (String o : opts) oa.put(o);
        return new JSONObject().put("question", q).put("options", oa)
                .put("multi", false).toString();
    }

    static void ok(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("  PASS  " + msg); }
        else { fail++; System.out.println("  FAIL  " + msg); }
    }

    // ---- 用例 ----

    static void t1_basic() throws Exception {
        System.out.println("t1 基本重建");
        JSONArray h = new JSONArray();
        h.put(user("帮我选个方案"));
        h.put(calls(new Object[]{"c1", "ui__ask", askArgs("走 A 还是 B？", "A", "B")}));
        h.put(tool("c1", "{\"ok\":true,\"selected\":\"A\"}"));

        Map<Integer, List<AskLog.Card>> m = AskLog.byIndex(h);
        ok(m.size() == 1, "一张卡片 (" + m.size() + ")");
        AskLog.Card c = m.get(1).get(0);
        ok("走 A 还是 B？".equals(c.question), "问题文本还原");
        ok(c.options.length == 2 && "B".equals(c.options[1]), "选项还原");
        ok("A".equals(c.answer), "答案还原");
        ok(c.answered(), "answered() 为 true");
    }

    /** 卡片之间夹着别的工具调用（读文件等），不能串。 */
    static void t2_otherToolsBetween() throws Exception {
        System.out.println("t2 中间夹其他工具");
        JSONArray h = new JSONArray();
        h.put(user("看看文件再问我"));
        h.put(calls(new Object[]{"f1", "fs.read", "{\"path\":\"a.txt\"}"}));
        h.put(tool("f1", "{\"ok\":true,\"content\":\"hello\"}"));
        h.put(calls(new Object[]{"a1", "ui__ask", askArgs("删还是留？", "删", "留")}));
        h.put(tool("a1", "{\"ok\":true,\"selected\":\"留\"}"));

        Map<Integer, List<AskLog.Card>> m = AskLog.byIndex(h);
        ok(m.size() == 1, "只识别出 ui.ask 那一张");
        ok("留".equals(m.get(3).get(0).answer), "答案对应自己的 call id");
    }

    /** 历史被压缩（开头折叠成摘要）后，剩下的卡片内容依旧正确。 */
    static void t3_compacted() throws Exception {
        System.out.println("t3 压缩后仍正确");
        JSONArray h = new JSONArray();
        h.put(assistant("【早前对话摘要】用户之前在选部署方式……"));
        h.put(user("继续"));
        h.put(calls(new Object[]{"b1", "ui__ask", askArgs("部署到哪？", "线上", "本地")}));
        h.put(tool("b1", "{\"ok\":true,\"selected\":\"本地\"}"));

        List<AskLog.Card> all = AskLog.replay(h);
        ok(all.size() == 1, "一张 (" + all.size() + ")");
        ok("部署到哪？".equals(all.get(0).question), "问题没被张冠李戴");
        ok("本地".equals(all.get(0).answer), "答案没被张冠李戴");
    }

    /** 中断/崩溃导致 tool 结果没落盘：卡片保留，答案为空。 */
    static void t4_missingResult() throws Exception {
        System.out.println("t4 缺 tool 结果");
        JSONArray h = new JSONArray();
        h.put(calls(new Object[]{"z1", "ui__ask", askArgs("继续吗？", "继续", "停")}));
        Map<Integer, List<AskLog.Card>> m = AskLog.byIndex(h);
        ok(m.size() == 1, "卡片还在");
        ok(!m.get(0).get(0).answered(), "答案为空 -> 显示「未选择」");
    }

    /** 用户点了停止：工具返回 ok:false，不算选过。 */
    static void t5_cancelled() throws Exception {
        System.out.println("t5 取消");
        JSONArray h = new JSONArray();
        h.put(calls(new Object[]{"x1", "ui__ask", askArgs("选吧", "甲", "乙")}));
        h.put(tool("x1", "{\"ok\":false,\"error\":\"用户取消或跳过\"}"));
        ok(!AskLog.byIndex(h).get(0).get(0).answered(), "取消视为未选");
    }

    /** 多选答案用「、」连接，原样保留。 */
    static void t6_multiAnswer() throws Exception {
        System.out.println("t6 多选");
        JSONArray h = new JSONArray();
        JSONArray oa = new JSONArray();
        oa.put("读文件"); oa.put("搜索"); oa.put("截屏");
        String args = new JSONObject().put("question", "要开哪些权限？")
                .put("options", oa).put("multi", true).toString();
        h.put(calls(new Object[]{"m1", "ui__ask", args}));
        h.put(tool("m1", "{\"ok\":true,\"selected\":\"读文件、截屏\"}"));

        AskLog.Card c = AskLog.byIndex(h).get(0).get(0);
        ok(c.multi, "multi 标记保持");
        ok("读文件、截屏".equals(c.answer), "多选答案完整");
    }

    /** 参数损坏（旧版本残留 / 空选项）时跳过，不抛异常。 */
    static void t7_badArguments() throws Exception {
        System.out.println("t7 坏参数");
        JSONArray h = new JSONArray();
        h.put(calls(new Object[]{"e1", "ui__ask", "not a json"}));
        h.put(calls(new Object[]{"e2", "ui__ask", "{\"question\":\"空选项\",\"options\":[]}"}));
        h.put(calls(new Object[]{"e3", "ui__ask", askArgs("正常", "OK")}));
        h.put(tool("e3", "{\"ok\":true,\"selected\":\"OK\"}"));

        Map<Integer, List<AskLog.Card>> m = AskLog.byIndex(h);
        ok(m.size() == 1, "坏参数被跳过，只留正常那张 (" + m.size() + ")");
        ok("正常".equals(m.get(2).get(0).question), "正常卡片仍能识别");
        ok(AskLog.replay(null).isEmpty(), "null 输入不崩");
    }

    /** 旧版本工具名带点号（ui.ask）也要认出来。 */
    static void t8_oldName() throws Exception {
        System.out.println("t8 旧工具名");
        JSONArray h = new JSONArray();
        h.put(calls(new Object[]{"o1", "ui.ask", askArgs("旧格式", "是", "否")}));
        h.put(tool("o1", "{\"ok\":true,\"selected\":\"是\"}"));
        List<AskLog.Card> all = AskLog.replay(h);
        ok(all.size() == 1, "ui.ask 也识别");
        ok("是".equals(all.get(0).answer), "答案正确");
    }
}
