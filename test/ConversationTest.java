import com.dsh.android.kernel.Conversation;
import org.json.JSONArray;
import org.json.JSONObject;

/** Conversation 的纯逻辑测试：迁移、取路径、分支、合并回写。 */
public final class ConversationTest {
    static int pass = 0, fail = 0;

    static void ok(boolean c, String what) {
        if (c) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    static JSONObject msg(String role, String content) throws Exception {
        JSONObject o = new JSONObject();
        o.put("role", role);
        o.put("content", content);
        return o;
    }

    public static void main(String[] a) throws Exception {
        System.out.println("t1 旧数据迁移成线性链");
        JSONArray old = new JSONArray();
        old.put(msg("user", "问题一"));
        old.put(msg("assistant", "回答一"));
        old.put(msg("user", "问题二"));
        Conversation.migrate(old);
        boolean allId = true, linked = true;
        for (int i = 0; i < old.length(); i++) {
            JSONObject m = old.getJSONObject(i);
            if (m.optString("id", "").length() == 0) allId = false;
            if (i == 0) linked &= !m.has("parent") || m.isNull("parent");
            else linked &= m.optString("parent").equals(old.getJSONObject(i - 1).optString("id"));
        }
        ok(allId, "每条都分到 id");
        ok(linked, "parent 依次串成一条线");
        ok(Conversation.path(old, Conversation.lastId(old)).length() == 3, "路径 = 全部 3 条");

        System.out.println("t2 第二次迁移不改变已迁移数据");
        String firstId = old.getJSONObject(0).optString("id");
        Conversation.migrate(old);
        ok(firstId.equals(old.getJSONObject(0).optString("id")), "id 保持稳定");

        System.out.println("t3 从中间消息分叉");
        // 记下原分支的末端，分叉后再核对它没被动过
        final String q2id = old.getJSONObject(2).optString("id");
        final String answerOne = old.getJSONObject(1).optString("id");
        // 在「回答一」下面再写一条分支
        JSONObject branch = msg("user", "换个问法");
        branch.put("id", "b1");
        branch.put("parent", answerOne);
        old.put(branch);
        ok(Conversation.childrenOf(old, answerOne).size() == 2, "回答一 有两个子节点 = 分叉");
        JSONArray p1 = Conversation.path(old, q2id);
        ok(p1.length() == 3, "原分支路径仍 3 条");
        JSONArray p2 = Conversation.path(old, "b1");
        ok(p2.length() == 3, "新分支路径 3 条（user/assistant/b1）");
        ok("换个问法".equals(p2.getJSONObject(2).optString("content")), "新分支末端是 b1");
        ok("问题二".equals(p1.getJSONObject(2).optString("content")), "原分支末端没被改写");

        System.out.println("t4 兄弟节点与末端");
        ok(Conversation.siblingsOf(old, "b1").size() == 2, "b1 与「问题二」是兄弟");
        ok(q2id.equals(Conversation.lastDescendant(old, q2id)), "原分支末端就是它自己");
        ok("b1".equals(Conversation.lastDescendant(old, answerOne)), "回答一 的末端走了最后写入的子节点");
        ok(Conversation.isFork(old, answerOne), "回答一是分叉点");
        ok(Conversation.forkCount(old) >= 1, "统计到分支点");

        System.out.println("t5 循环写回：新消息接在 leaf 后面");
        JSONArray path = Conversation.path(old, "b1");
        int before = path.length();
        path.put(msg("assistant", "新回答"));
        JSONObject user = msg("user", "追问");
        path.put(user);
        String newLeaf = Conversation.mergeAppended(old, path, before, "b1");
        ok(old.length() == 6, "完整历史多了 2 条（现在 6 条）");
        ok(newLeaf.equals(path.getJSONObject(path.length() - 1).optString("id")), "返回最后一条的 id");
        JSONArray after = Conversation.path(old, newLeaf);
        ok(after.length() == 5, "新分支路径 5 条");
        ok("追问".equals(after.getJSONObject(4).optString("content")), "路径末端是追问");
        ok("问题二".equals(Conversation.path(old, q2id).getJSONObject(2).optString("content")), "另一分支不受影响");

        System.out.println("t6 重复合并不会产生重复节点");
        int lenBefore = old.length();
        Conversation.mergeAppended(old, path, before, "b1");
        ok(old.length() == lenBefore, "同一条路径二次合并长度不变");

        System.out.println("t7 找不到 leaf 时退回全部");
        JSONArray fb = Conversation.path(old, "不存在的id");
        ok(fb.length() == old.length(), "退回完整历史");

        System.out.println("t8 空历史");
        ok(Conversation.path(new JSONArray(), null).length() == 0, "空数组返回空");
        ok(Conversation.lastId(new JSONArray()) == null, "空数组没有末端");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
