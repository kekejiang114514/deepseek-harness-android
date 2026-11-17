import com.dsh.android.kernel.Conversation;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 分支流程的端到端验证（纯 JVM）。
 *
 * <p>模拟真实的两次发送：先在「答一」上开出分支，再往新分支写两条消息，
 * 然后检查原分支是否还在、能不能切回去。用户反馈「分支像是直接覆盖」，
 * 这里就是判定它是否真的覆盖的地方。
 */
public final class BranchFlowTest {

    private static int pass = 0, fail = 0;

    private static void ok(boolean cond, String what) {
        if (cond) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    private static JSONObject msg(String id, String parent, String role, String text) {
        JSONObject o = new JSONObject();
        try {
            o.put("role", role);
            o.put("content", text);
            if (id != null) o.put("id", id);
            if (parent != null) o.put("parent", parent);
        } catch (Exception ignored) {}
        return o;
    }

    private static String idOf(JSONArray a, int i) {
        return a.optJSONObject(i).optString("id");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("t1 原始会话：u1 → a1 → u2 → a2");
        JSONArray all = new JSONArray();
        all.put(msg("u1", null, "user", "问一"));
        all.put(msg("a1", "u1", "assistant", "答一"));
        all.put(msg("u2", "a1", "user", "问二"));
        all.put(msg("a2", "u2", "assistant", "答二"));
        Conversation.migrate(all);
        ok(all.length() == 4, "4 条消息");
        ok(Conversation.siblingsOf(all, "a2").size() == 1, "答二 此时没有兄弟");

        System.out.println("t2 从「答一」开出新分支并写入两条新消息");
        String leaf = "a1";
        JSONArray path = Conversation.path(all, leaf);
        ok(path.length() == 2, "分支起点路径是 u1/a1 两条");
        int from = path.length();
        // AgentLoop 会把 user 与 assistant 追加进 path
        path.put(msg(null, null, "user", "换个问法"));
        path.put(msg(null, null, "assistant", "新答"));
        String newLeaf = Conversation.mergeAppended(all, path, from, leaf);
        ok(all.length() == 6, "合并后共 6 条，原 4 条没被删");
        ok(Conversation.indexOf(all, "a2") >= 0, "原分支的「答二」仍在");
        ok("a1".equals(all.optJSONObject(4).optString("parent")), "新提问挂在「答一」下面");

        System.out.println("t3 两条分支都能走通");
        JSONArray oldBranch = Conversation.path(all, Conversation.lastDescendant(all, "u2"));
        JSONArray newBranch = Conversation.path(all, newLeaf);
        ok(oldBranch.length() == 4, "原分支路径 4 条");
        ok("答二".equals(oldBranch.getJSONObject(3).optString("content")), "原分支末端是「答二」");
        ok(newBranch.length() == 4, "新分支路径 4 条");
        ok("新答".equals(newBranch.getJSONObject(3).optString("content")), "新分支末端是「新答」");
        // 兄弟 = 同一个父节点的其它子节点；u2 与新提问都挂在「答一」下
        ok(Conversation.siblingsOf(all, "u2").size() == 2, "「答一」下有两个分支");

        System.out.println("t4 再从「答一」分支一次，不能覆盖前两条");
        JSONArray p2 = Conversation.path(all, "a1");
        int f2 = p2.length();
        p2.put(msg(null, null, "user", "第三种问法"));
        Conversation.mergeAppended(all, p2, f2, "a1");
        ok(all.length() == 7, "第三条分支追加后共 7 条");
        ok(Conversation.siblingsOf(all, "u2").size() == 3, "「答一」下有三个分支");
        ok(Conversation.path(all, "a2").length() == 4, "原分支依然完整");

        System.out.println("t5 重复合并同一批消息不能翻倍");
        int before = all.length();
        Conversation.mergeAppended(all, p2, f2, "a1");
        ok(all.length() == before, "已有 id 的消息不会被重复写入");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
