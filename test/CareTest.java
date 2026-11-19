import com.dsh.android.kernel.CareCheck;

/** 心理危机预警的纯 JVM 测试。 */
public final class CareTest {

    private static int pass = 0, fail = 0;
    private static void ok(boolean c, String w) {
        if (c) { pass++; System.out.println("  PASS  " + w); }
        else { fail++; System.out.println("  FAIL  " + w); }
    }

    public static void main(String[] a) {
        System.out.println("t1 明确的表达要命中");
        ok(CareCheck.looksDistressed("我不想活了"), "「不想活」");
        ok(CareCheck.looksDistressed("最近总是想自杀"), "「自杀」");
        ok(CareCheck.looksDistressed("我想结束生命"), "「结束生命」");
        ok(CareCheck.looksDistressed("I want to die"), "英文 want to die");
        ok(CareCheck.looksDistressed("I've been thinking about suicide"), "英文 suicide");
        ok(CareCheck.looksDistressed("最近在自残"), "「自残」");

        System.out.println("t2 不该误报");
        ok(!CareCheck.looksDistressed("今天天气不错"), "普通句子");
        ok(!CareCheck.looksDistressed("这个 bug 烦死了"), "口头抱怨");
        ok(!CareCheck.looksDistressed("游戏里被打死了"), "游戏用语");
        ok(!CareCheck.looksDistressed(""), "空串");
        ok(!CareCheck.looksDistressed(null), "null");

        System.out.println("t3 文案含可核实资源");
        String t = CareCheck.careText();
        ok(t.contains("12356"), "全国心理援助热线");
        ok(t.contains("82951332"), "北京危机干预中心");
        ok(t.contains("120") && t.contains("110"), "急救与报警");
        ok(t.contains("以当地最新公布为准"), "提示号码可能调整");
        ok(t.contains("不能替代专业帮助"), "说明边界");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
