import java.util.ArrayList;
import java.util.List;

/**
 * 暂停/继续的行为约束。
 *
 * <p>这几条是纯逻辑：不联网、不依赖 Android，只钉住「暂停保留内容、停止作废、
 * 继续只从被打断的那条接」这几个容易写反的规则。
 */
public final class PauseTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<String>();

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else failures.add(name);
    }

    /** 模拟 AgentLoop.stopWithPartial 的写历史规则。 */
    private static String save(String content, String reasoning, boolean paused) {
        boolean hasText = (content != null && content.length() > 0)
                || (reasoning != null && reasoning.length() > 0);
        if (!hasText) return "none";
        return paused ? "assistant+interrupted" : "assistant";
    }

    /** 模拟 MainActivity.tailInterrupted。 */
    private static boolean tailInterrupted(String[] roles, boolean[] interrupted) {
        for (int i = roles.length - 1; i >= 0; i--) {
            if ("assistant".equals(roles[i])) return interrupted[i];
            if ("user".equals(roles[i])) return false;
        }
        return false;
    }

    public static void main(String[] args) {
        // 暂停：半截文字要留下，并打上 interrupted，界面才知道可以继续
        check("暂停保留半截文字", "assistant+interrupted".equals(save("写到一半", "", true)));
        check("暂停保留半截思考", "assistant+interrupted".equals(save("", "想到一半", true)));
        check("暂停时什么都没收到就不必建消息", "none".equals(save("", "", true)));

        // 停止：同样保留内容，但不该给「继续」入口
        check("停止不作 interrupted 标记", "assistant".equals(save("写到一半", "", false)));

        // 尾消息判断
        String[] a = {"system", "user", "assistant"};
        check("被打断的回答可继续", tailInterrupted(a, new boolean[]{false, false, true}));
        check("正常结束的回答无继续", !tailInterrupted(a, new boolean[]{false, false, false}));

        String[] b = {"system", "user", "assistant", "user"};
        check("后面又有提问就不再算中断",
                !tailInterrupted(b, new boolean[]{false, false, true, false}));

        String[] c = {"system", "user", "assistant", "user", "assistant"};
        check("只看最后一条回答",
                tailInterrupted(c, new boolean[]{false, false, true, false, true}));

        check("没有历史时不可继续", !tailInterrupted(new String[0], new boolean[0]));

        System.out.println("=== " + passed + " passed, " + failures.size() + " failed ===");
        for (String f : failures) System.out.println("  FAIL: " + f);
        if (!failures.isEmpty()) System.exit(1);
    }
}
