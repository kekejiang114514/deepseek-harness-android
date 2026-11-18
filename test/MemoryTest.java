import com.dsh.android.kernel.MemoryStore;
import java.io.File;

/** 长期记忆的纯 JVM 测试：用临时目录，不依赖 Android。 */
public final class MemoryTest {

    private static int pass = 0, fail = 0;
    private static void ok(boolean c, String w) {
        if (c) { pass++; System.out.println("  PASS  " + w); }
        else { fail++; System.out.println("  FAIL  " + w); }
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dsh-memory-test");
        dir.mkdirs();
        File f = new File(dir, "memory.md");
        f.delete();

        MemoryStore m = new MemoryStore(dir);
        System.out.println("t1 空文件");
        ok("".equals(m.read()), "没有文件时读到空串");
        ok(!f.exists(), "读取不会创建文件");

        System.out.println("t2 追加");
        m.append("用户偏好深色模式");
        m.append("项目使用 1.5 版本");
        String got = m.read();
        ok(got.contains("用户偏好深色模式"), "第一条在");
        ok(got.contains("项目使用 1.5 版本"), "第二条在");
        ok(got.indexOf("深色模式") < got.indexOf("1.5 版本"), "按写入顺序排列");
        ok(got.startsWith("- "), "条目带项目符号");

        System.out.println("t3 覆盖与清空");
        m.write("自定义内容");
        ok("自定义内容".equals(m.read().trim()), "write 覆盖整份");
        m.clear();
        ok(m.read().length() == 0, "clear 之后为空");

        System.out.println("t4 超限裁掉最早的行");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 900; i++) big.append("- 第").append(i).append("条记忆内容填充\n");
        String trimmed = MemoryStore.trim(big.toString());
        ok(trimmed.getBytes("UTF-8").length <= MemoryStore.MAX_BYTES, "裁到上限以内");
        ok(trimmed.contains("第899条"), "最新的保留");
        ok(!trimmed.contains("第0条记忆"), "最早的被丢弃");

        System.out.println("t5 空追加不写垃圾");
        m.append("   ");
        ok(m.read().length() == 0, "空白内容不写入");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
