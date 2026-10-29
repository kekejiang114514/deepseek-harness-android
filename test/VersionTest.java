import com.dsh.android.kernel.Version;

/** 版本号比较的纯 JVM 测试（不依赖 Android）。 */
public class VersionTest {

    static int pass = 0;
    static int fail = 0;

    static void ok(boolean cond, String msg) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + msg);
        } else {
            fail++;
            System.out.println("  FAIL  " + msg);
        }
    }

    static void eq(String got, String want, String msg) {
        ok(want.equals(got), msg + " (得到 '" + got + "')");
    }

    public static void main(String[] args) {
        System.out.println("t1 normalize");
        eq(Version.normalize("v0.10"), "0.10", "去掉 v 前缀");
        eq(Version.normalize("1.0"), "1.0", "纯数字不变");
        eq(Version.normalize("release-1.2"), "1.2", "任意非数字前缀");
        eq(Version.normalize("v1.0.0-beta.2"), "1.0.0", "去掉预发布尾巴");
        eq(Version.normalize("2.0.1+build7"), "2.0.1", "去掉构建元数据");
        eq(Version.normalize(""), "", "空串");
        eq(Version.normalize(null), "", "null 不崩");
        eq(Version.normalize("v1."), "1", "尾部多余点");

        System.out.println("t2 逐段数值比较");
        ok(Version.compare("0.10", "0.9") > 0, "0.10 > 0.9（按字符串比会错）");
        ok(Version.compare("1.0", "0.9") > 0, "1.0 > 0.9");
        ok(Version.compare("1.0", "1.0") == 0, "1.0 == 1.0");
        ok(Version.compare("1.0.0", "1.0") == 0, "1.0.0 == 1.0");
        ok(Version.compare("1.0.1", "1.0") > 0, "1.0.1 > 1.0");
        ok(Version.compare("0.9", "1.0") < 0, "0.9 < 1.0");
        ok(Version.compare("10.0", "9.9") > 0, "10.0 > 9.9");

        System.out.println("t3 isNewer");
        ok(Version.isNewer("v1.0", "0.9"), "远端 v1.0 比本地 0.9 新");
        ok(!Version.isNewer("0.9", "1.0"), "远端 0.9 不比本地 1.0 新");
        ok(!Version.isNewer("1.0", "1.0"), "同版本不提示");
        ok(!Version.isNewer("", "1.0"), "空 tag 视为不新");
        ok(!Version.isNewer(null, "1.0"), "null tag 视为不新");

        System.out.println("t4 真实场景");
        ok(Version.isNewer("v0.9", "0.7.0"), "0.7.0 用户能看到 v0.9");
        ok(Version.isNewer("v1.0", "0.9"), "0.9 用户能看到 v1.0");

        System.out.println();
        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
