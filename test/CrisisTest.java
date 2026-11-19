import com.dsh.android.kernel.CrisisClassifier;

/** 模型分类器的纯 JVM 测试：只看输出解析与提示词，不发请求。 */
public final class CrisisTest {

    private static int pass = 0, fail = 0;
    private static void ok(boolean c, String w) {
        if (c) { pass++; System.out.println("  PASS  " + w); }
        else { fail++; System.out.println("  FAIL  " + w); }
    }

    public static void main(String[] a) {
        System.out.println("t1 解析 true");
        ok(CrisisClassifier.parseResponse("{\"risk\":true}"), "标准 JSON");
        ok(CrisisClassifier.parseResponse("{\"risk\": true, \"reason\":\"x\"}"), "带额外字段");
        ok(CrisisClassifier.parseResponse("```json\n{\"risk\":true}\n```"), "带代码块");

        System.out.println("t2 解析 false");
        ok(!CrisisClassifier.parseResponse("{\"risk\":false}"), "标准 false");
        ok(!CrisisClassifier.parseResponse("拿不准，false"), "只有 false");
        ok(!CrisisClassifier.parseResponse(""), "空回答");
        ok(!CrisisClassifier.parseResponse(null), "null");
        ok(!CrisisClassifier.parseResponse("risk unknown"), "没有布尔值时不误判");

        System.out.println("t3 提示词约束");
        String sys = CrisisClassifier.systemPrompt();
        ok(sys.contains("{\"risk\":true}"), "要求输出 JSON");
        ok(sys.contains("拿不准"), "要求不确定时给 false");
        ok(CrisisClassifier.userPrompt("我不想再撑了").contains("我不想再撑了"), "原文被带入");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
