import com.dsh.android.kernel.ReleasePicker;
import com.dsh.android.kernel.Version;
import org.json.JSONArray;
import org.json.JSONObject;

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

    static JSONObject rel(String tag, boolean pre) {
        return rel(tag, pre, false);
    }

    static JSONObject rel(String tag, boolean pre, boolean draft) {
        JSONObject o = new JSONObject();
        try {
            o.put("tag_name", tag);
            o.put("prerelease", pre);
            o.put("draft", draft);
        } catch (Exception ignored) {}
        return o;
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

        System.out.println("t4 预发布阶段");
        ok(Version.compare("1.0 beta 1", "1.0 beta 1") == 0, "同一 beta 相等");
        ok(Version.compare("v1.0-beta1", "1.0 beta 1") == 0, "tag 与展示名等价");
        ok(Version.compare("1.0 beta 2", "1.0 beta 1") > 0, "beta 2 > beta 1");
        ok(Version.compare("1.0", "1.0 beta 1") > 0, "正式版 > beta");
        ok(!Version.isNewer("v1.0-beta1", "1.0"), "本地已是正式版，beta 不算更新");
        ok(Version.isNewer("v1.0", "1.0 beta 1"), "beta 用户能看到正式版");
        ok(Version.isNewer("v1.1-beta1", "1.0 beta 1"), "更高版本的 beta 也算更新");
        ok(Version.compare("1.0 rc 1", "1.0 beta 9") > 0, "rc > beta");
        ok(Version.compare("1.0 alpha 1", "1.0 beta 1") < 0, "alpha < beta");
        ok(Version.isPrerelease("1.0 beta 1"), "1.0 beta 1 是预发布");
        ok(Version.isPrerelease("v1.0-rc1"), "rc 是预发布");
        ok(!Version.isPrerelease("v1.0"), "v1.0 不是预发布");
        ok(!Version.isPrerelease("2.0.1+build7"), "构建元数据不算预发布");
        ok(Version.compare("1.0 beta 1", "0.1") > 0, "1.0 beta 1 比 0.1 新");
        ok(Version.compare("1.0 beta 2", "1.0 beta 1") > 0, "beta 2 比 beta 1 新");

        System.out.println("t5 真实场景（当前编号体系：0.1 -> 1.0 beta 1 -> 1.0 beta 2）");
        ok(Version.isNewer("v1.0-beta1", "0.1"), "0.1 用户能看到 1.0 beta 1");
        ok(Version.isNewer("v1.0-beta2", "1.0 beta 1"), "beta1 用户能看到 beta2");
        ok(!Version.isNewer("v1.0-beta2", "1.0 beta 2"), "同版本不提示");
        ok(Version.isNewer("v1.0-beta1", "0.9"), "旧的 0.9 安装仍能升到 beta1");

        System.out.println("t6 正式版与 beta 的关系");
        ok(Version.isNewer("v1.0", "1.0-beta1"), "beta 用户能看到同号正式版");
        ok(!Version.isNewer("v1.0-beta1", "1.0-beta1"), "同一 beta 不重复提示");
        ok(Version.isNewer("v1.0", "1.0-beta2"), "beta2 用户能看到正式版");

        System.out.println("t7 从发布列表挑最高版本");
        JSONArray rels = new JSONArray();
        rels.put(rel("v0.1", false));
        rels.put(rel("v1.0-beta1", true));
        ok("v1.0-beta1".equals(ReleasePicker.best(rels).optString("tag_name")),
                "0.1 与 1.0-beta1 并存时选 beta（latest 接口会选错）");
        rels.put(rel("v1.0-beta2", true));
        ok("v1.0-beta2".equals(ReleasePicker.best(rels).optString("tag_name")),
                "beta2 比 beta1 高");
        rels.put(rel("v1.0", false));
        ok("v1.0".equals(ReleasePicker.best(rels).optString("tag_name")),
                "有了正式版选正式版");
        rels.put(rel("v1.1-draft", false, true));
        ok("v1.0".equals(ReleasePicker.best(rels).optString("tag_name")),
                "草稿不参与挑选");
        JSONArray onlyDraft = new JSONArray();
        onlyDraft.put(rel("v9.9", false, true));
        ok(ReleasePicker.bestIndex(onlyDraft) < 0, "全是草稿时返回 -1");
        ok(ReleasePicker.bestIndex(new JSONArray()) < 0, "空列表返回 -1");
        JSONArray noise = new JSONArray();
        noise.put(rel("", false));
        noise.put(rel("nightly-abc", false));
        ok(ReleasePicker.bestIndex(noise) < 0, "没有版本号的 tag 被跳过");

        System.out.println();
        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
