import com.dsh.android.kernel.C;
import com.dsh.android.llm.ChatBody;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 请求体测试。用纯 JVM 跑，锁住两件靠肉眼看不出来的事：
 * 推理强度要真的写进请求，带 tools 时思考链要回传。
 */
public class ChatBodyTest {

    static int pass = 0;
    static int fail = 0;

    static void ok(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("  PASS  " + msg); }
        else { fail++; System.out.println("  FAIL  " + msg); }
    }

    static JSONArray msgs(String... roleContent) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; i + 1 < roleContent.length; i += 2) {
            JSONObject m = new JSONObject();
            m.put("role", roleContent[i]);
            m.put("content", roleContent[i + 1]);
            a.put(m);
        }
        return a;
    }

    static JSONArray oneTool() throws Exception {
        JSONArray tools = new JSONArray();
        JSONObject t = new JSONObject();
        t.put("type", "function");
        JSONObject f = new JSONObject();
        f.put("name", "shell_execute");
        t.put("function", f);
        tools.put(t);
        return tools;
    }

    public static void main(String[] a) throws Exception {
        System.out.println("t1 推理强度真的写进请求体");
        JSONObject b = ChatBody.build("deepseek-v4-pro", msgs("user", "hi"), null, true,
                C.maxTokensFor(C.LEVEL_MAX), C.reasoningEffortFor(C.LEVEL_MAX), false);
        ok("max".equals(b.optString("reasoning_effort")), "max 档 → reasoning_effort=max");
        ok(b.optInt("max_tokens") == 65536, "max 档 → max_tokens=65536");

        System.out.println("t2 六档都映射到接口认的三个值之一");
        String[] levels = {C.LEVEL_LOW, C.LEVEL_MEDIUM, C.LEVEL_HIGH, C.LEVEL_XHIGH, C.LEVEL_MAX};
        for (String lv : levels) {
            String e = C.reasoningEffortFor(lv);
            boolean valid = "low".equals(e) || "high".equals(e) || "max".equals(e);
            ok(valid, lv + " → " + e + " 合法");
        }
        ok("low".equals(C.reasoningEffortFor(C.LEVEL_LOW)), "low → low");
        ok("high".equals(C.reasoningEffortFor(C.LEVEL_MEDIUM)), "medium → high（文档映射）");
        ok("high".equals(C.reasoningEffortFor(C.LEVEL_XHIGH)), "xhigh → high（文档映射）");
        ok("max".equals(C.reasoningEffortFor(C.LEVEL_MAX)), "max → max");

        System.out.println("t3 off 档关闭思考，不带 reasoning_effort");
        ok(C.reasoningEffortFor(C.LEVEL_OFF) == null, "off → effort 为 null");
        ok(C.thinkingOff(C.LEVEL_OFF), "off → thinkingOff 为真");
        ok(!C.thinkingOff(C.LEVEL_HIGH), "high → thinkingOff 为假");
        JSONObject off = ChatBody.build("deepseek-v4-pro", msgs("user", "hi"), null, true,
                C.maxTokensFor(C.LEVEL_OFF), C.reasoningEffortFor(C.LEVEL_OFF),
                C.thinkingOff(C.LEVEL_OFF));
        ok(!off.has("reasoning_effort"), "off 档请求体里没有 reasoning_effort");
        ok("disabled".equals(off.optJSONObject("thinking").optString("type")),
                "off 档 thinking.type=disabled");

        System.out.println("t4 带 tools 时思考链必须回传");
        JSONArray h = new JSONArray();
        JSONObject u = new JSONObject();
        u.put("role", "user"); u.put("content", "读一下文件");
        h.put(u);
        JSONObject asst = new JSONObject();
        asst.put("role", "assistant");
        asst.put("content", "");
        asst.put("reasoning", "我需要先看看目录");
        asst.put("hidden", true);          // 界面自用标记，不该发出去
        asst.put("interrupted", false);
        h.put(asst);
        JSONObject body = ChatBody.build("deepseek-v4-pro", h, oneTool(), true, 16384, "high", false);
        JSONObject sent = body.getJSONArray("messages").getJSONObject(1);
        ok("我需要先看看目录".equals(sent.optString("reasoning_content")),
                "assistant 的 reasoning → reasoning_content");
        ok(!sent.has("hidden"), "界面自用字段 hidden 被剔除");
        ok(!sent.has("reasoning"), "字段名是 reasoning_content，不是 reasoning");
        ok(!sent.has("interrupted"), "界面自用字段 interrupted 被剔除");

        System.out.println("t5 不带 tools 时不塞 reasoning_content");
        JSONObject noTools = ChatBody.build("deepseek-v4-pro", h, null, true, 16384, "high", false);
        ok(!noTools.getJSONArray("messages").getJSONObject(1).has("reasoning_content"),
                "无 tools 时不回传 reasoning_content");

        System.out.println("t6 空思考链不多塞字段");
        JSONObject emptyReason = new JSONObject();
        emptyReason.put("role", "assistant"); emptyReason.put("content", "好的");
        JSONArray h2 = new JSONArray(); h2.put(emptyReason);
        JSONObject b2 = ChatBody.build("deepseek-v4-pro", h2, oneTool(), true, 8192, "low", false);
        ok(!b2.getJSONArray("messages").getJSONObject(0).has("reasoning_content"),
                "reasoning 为空时字段不出现");

        System.out.println("t7 非标准角色的历史记录被剔除");
        JSONArray h3 = msgs("user", "问");
        JSONObject note = new JSONObject();
        note.put("role", "divider"); note.put("content", "---");
        h3.put(note);
        JSONObject b3 = ChatBody.build("m", h3, null, false, 1024, null, false);
        ok(b3.getJSONArray("messages").length() == 1, "非法角色不进请求");

        System.out.println("t8 工具声明与流式开关");
        jsonEq(oneTool().toString(), body.getJSONArray("tools").toString(), "tools 原样带上");
        ok("auto".equals(body.optString("tool_choice")), "tool_choice=auto");
        ok(body.optBoolean("stream"), "stream=true");

        System.out.println("t9 输出上限不再停在 8192");
        for (String lv : levels) {
            ok(C.maxTokensFor(lv) >= 8192, lv + " 档上限 " + C.maxTokensFor(lv) + " ≥ 8192");
        }
        ok(C.maxTokensFor(C.LEVEL_MAX) <= 384000, "上限不超过 384K");

        System.out.println();
        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    static void jsonEq(String want, String got, String msg) {
        ok(want.equals(got), msg);
    }
}
