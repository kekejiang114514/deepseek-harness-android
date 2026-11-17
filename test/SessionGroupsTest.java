import com.dsh.android.kernel.SessionGroups;
import com.dsh.android.kernel.SessionMeta;

import java.util.ArrayList;
import java.util.List;

public final class SessionGroupsTest {

    private static int pass = 0, fail = 0;

    private static void ok(boolean cond, String what) {
        if (cond) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    private static SessionMeta m(String id, String group) {
        return new SessionMeta(id, id, 0, group);
    }

    public static void main(String[] args) {
        System.out.println("会话分组分桶");

        System.out.println("t1 没有分组");
        List<SessionMeta> all = new ArrayList<SessionMeta>();
        all.add(m("a", ""));
        all.add(m("b", ""));
        List<SessionGroups.Bucket> b1 = SessionGroups.split(all, new ArrayList<String>());
        ok(b1.size() == 1, "只有一个段");
        ok(b1.get(0).isUngrouped(), "这个段是未分组");
        ok(b1.get(0).sessions.size() == 2, "两条都在里面");
        ok(!SessionGroups.showHeaders(b1), "单段未分组不显示标题");

        System.out.println("t2 有分组（回归：曾经在这里越界闪退）");
        List<String> groups = new ArrayList<String>();
        groups.add("工作");
        List<SessionMeta> all2 = new ArrayList<SessionMeta>();
        all2.add(m("a", "工作"));
        all2.add(m("b", ""));
        List<SessionGroups.Bucket> b2 = SessionGroups.split(all2, groups);
        ok(b2.size() == 2, "分成两段");
        ok("工作".equals(b2.get(0).name), "第一段是命名的组");
        ok(b2.get(1).isUngrouped(), "未分组排在最后");
        ok(SessionGroups.showHeaders(b2), "多段时显示标题");

        System.out.println("t3 空分组不出现");
        List<String> g3 = new ArrayList<String>();
        g3.add("工作");
        g3.add("空组");
        List<SessionGroups.Bucket> b3 = SessionGroups.split(all2, g3);
        ok(b3.size() == 2, "空组被跳过");
        ok(!b3.get(0).isUngrouped(), "第一段仍是工作");

        System.out.println("t4 会话指向已删除的组");
        List<SessionMeta> all4 = new ArrayList<SessionMeta>();
        all4.add(m("x", "不存在的组"));
        List<SessionGroups.Bucket> b4 = SessionGroups.split(all4, new ArrayList<String>());
        ok(b4.size() == 1 && b4.get(0).isUngrouped(), "按未分组处理");

        System.out.println("t5 空输入");
        ok(SessionGroups.split(null, null).isEmpty(), "null 不炸");
        ok(SessionGroups.split(new ArrayList<SessionMeta>(), new ArrayList<String>()).isEmpty(),
                "空列表得到空结果");
        ok(!SessionGroups.showHeaders(null), "null 不显示标题");

        System.out.println("t6 组内顺序保持原样");
        List<SessionMeta> all6 = new ArrayList<SessionMeta>();
        all6.add(m("1", "组A"));
        all6.add(m("2", "组B"));
        all6.add(m("3", "组A"));
        List<String> g6 = new ArrayList<String>();
        g6.add("组A"); g6.add("组B");
        List<SessionGroups.Bucket> b6 = SessionGroups.split(all6, g6);
        ok("1".equals(b6.get(0).sessions.get(0).id)
                && "3".equals(b6.get(0).sessions.get(1).id), "组A 内顺序不变");

        System.out.println("=== " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
    }
}
