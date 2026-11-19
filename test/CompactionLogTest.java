import com.dsh.android.kernel.CompactionLog;
import org.json.JSONObject;
import java.io.File;

/** 压缩日志的纯 JVM 测试。 */
public final class CompactionLogTest {

    private static int pass = 0, fail = 0;
    private static void ok(boolean c, String w) {
        if (c) { pass++; System.out.println("  PASS  " + w); }
        else { fail++; System.out.println("  FAIL  " + w); }
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dsh-compact-test");
        dir.mkdirs();
        new File(dir, "compaction.log").delete();
        CompactionLog log = new CompactionLog(dir);

        System.out.println("t1 一条记录是合法 JSON");
        String line = log.write("soft-threshold", 24000, 14000, 3, 5, false);
        JSONObject o = new JSONObject(line);
        ok("soft-threshold".equals(o.optString("reason")), "原因写入");
        ok(o.optInt("before") == 24000 && o.optInt("after") == 14000, "前后 token 写入");
        ok(o.optInt("folded") == 3, "折叠轮数写入");
        ok(o.optInt("slimmed") == 5, "截断的工具结果数写入");
        ok(!o.optBoolean("overflow"), "溢出标记写入");

        System.out.println("t2 追加与读取");
        log.write("context-overflow", 40000, 6000, 9, 12, true);
        String all = log.read();
        ok(all.split("\n").length == 2, "两行");
        ok(all.contains("context-overflow"), "第二行在里面");

        System.out.println("t3 只取最近几行");
        String tail = log.tail(1);
        ok(tail.split("\n").length == 1, "tail(1) 只有一行");
        ok(tail.contains("context-overflow"), "取到的是最新那条");

        System.out.println("t4 超上限滚动丢弃");
        for (int i = 0; i < CompactionLog.MAX_LINES + 20; i++) {
            log.write("soft-threshold", 1000 + i, 500, 1, 1, false);
        }
        String many = log.read();
        ok(many.split("\n").length <= CompactionLog.MAX_LINES, "行数不超过上限");
        ok(!many.contains("\"before\":24000"), "最早的记录已被丢弃");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
