import com.dsh.android.kernel.C;

/**
 * 推理等级的纯 JVM 测试：旧三档迁移、六档归一、max_tokens 映射。
 * C 只依赖 JDK，可以直接在桌面跑。
 */
public class LevelTest {

    static int pass = 0;
    static int fail = 0;

    static void ok(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("  PASS  " + msg); }
        else { fail++; System.out.println("  FAIL  " + msg); }
    }

    static void eq(String got, String want, String msg) {
        ok(want.equals(got), msg + " (得到 '" + got + "')");
    }

    public static void main(String[] a) {
        System.out.println("t1 旧三档迁移到六档");
        eq(C.normalizeLevel("fast"), C.LEVEL_LOW, "fast → low");
        eq(C.normalizeLevel("balanced"), C.LEVEL_MEDIUM, "balanced → medium");
        eq(C.normalizeLevel("deep"), C.LEVEL_HIGH, "deep → high");

        System.out.println("t2 六档原样保留");
        for (int i = 0; i < C.LEVELS.length; i++) {
            String k = C.LEVELS[i][0];
            eq(C.normalizeLevel(k), k, k + " 保持不变");
        }

        System.out.println("t3 异常输入兜底");
        eq(C.normalizeLevel(null), C.LEVEL_MEDIUM, "null → medium");
        eq(C.normalizeLevel(""), C.LEVEL_MEDIUM, "空串 → medium");
        eq(C.normalizeLevel("turbo"), C.LEVEL_MEDIUM, "未知值 → medium");

        System.out.println("t4 max_tokens 映射");
        ok(C.maxTokensFor(C.LEVEL_OFF) == 1024, "off = 1024");
        ok(C.maxTokensFor(C.LEVEL_LOW) == 2048, "low = 2048");
        ok(C.maxTokensFor(C.LEVEL_MEDIUM) == 4096, "medium = 4096");
        ok(C.maxTokensFor(C.LEVEL_HIGH) == 6144, "high = 6144");
        ok(C.maxTokensFor(C.LEVEL_XHIGH) == 8192, "xhigh = 8192");
        ok(C.maxTokensFor(C.LEVEL_MAX) == 8192, "max = 8192（接口上限）");
        ok(C.maxTokensFor("deep") == 6144, "旧值 deep 也走新映射");

        System.out.println("t5 单调不减");
        int prev = 0;
        boolean mono = true;
        for (int i = 0; i < C.LEVELS.length; i++) {
            int v = C.maxTokensFor(C.LEVELS[i][0]);
            if (v < prev) mono = false;
            prev = v;
        }
        ok(mono, "档位越高，输出上限不减少");

        System.out.println("t6 六档定义完整");
        ok(C.LEVELS.length == 6, "正好六档");
        ok("off".equals(C.LEVELS[0][0]) && "max".equals(C.LEVELS[5][0]), "首尾是 off / max");
        for (int i = 0; i < C.LEVELS.length; i++) {
            ok(C.LEVELS[i][1].length() > 0, C.LEVELS[i][0] + " 有说明文字");
        }

        System.out.println();
        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
