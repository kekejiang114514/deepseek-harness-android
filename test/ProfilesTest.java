import com.dsh.android.kernel.Profiles;

/** 配置档案的纯逻辑测试：序列化、增删、激活下标、旧数据迁移。 */
public final class ProfilesTest {

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String what) {
        if (cond) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    public static void main(String[] a) {
        System.out.println("t1 空集合与迁移");
        Profiles p = new Profiles();
        ok(p.items().isEmpty(), "初始为空");
        ok(p.active() == null, "没有当前项时不崩");
        boolean migrated = p.migrateLegacy("https://api.deepseek.com", "sk-1", "deepseek-v4-pro",
                "high", "https://api.deepseek.com");
        ok(migrated, "旧数据被迁移");
        ok(p.items().size() == 1, "迁移出一份档案");
        ok("默认".equals(p.active().name), "名字是默认");
        ok("sk-1".equals(p.active().apiKey), "密钥带过来");
        ok(!p.migrateLegacy("x", "y", "z", "low", "b"), "已有档案时不再重复迁移");

        System.out.println("t2 增删与激活");
        p.add(new Profiles.Profile("中转", "https://relay.example.com/v1", "sk-2", "deepseek-flash", "low"));
        ok(p.items().size() == 2, "新增后两份");
        ok(p.activeIndex() == 1, "新增的自动成为当前");
        p.setActive(0);
        ok("默认".equals(p.active().name), "切回第一份");
        p.remove(0);
        ok(p.items().size() == 1, "删除后剩一份");
        ok("中转".equals(p.active().name), "当前项跟随调整");
        p.remove(0);
        ok(p.items().isEmpty() && p.active() == null, "删空后不崩");

        System.out.println("t3 重名判断");
        Profiles q = new Profiles();
        q.add(new Profiles.Profile("A", "u1", "k1", "m", "high"));
        q.add(new Profiles.Profile("B", "u2", "k2", "m", "high"));
        ok(q.nameTaken("A", 1), "别的档案占用了 A");
        ok(!q.nameTaken("A", 0), "自己不算重名");
        ok(!q.nameTaken("", 0), "空名字不算重名");

        System.out.println("t4 序列化往返");
        String json = q.toJson().toString();
        Profiles back = Profiles.fromJson(json);
        ok(back.items().size() == 2, "两份都还原");
        ok("B".equals(back.active().name), "当前项还原");
        ok("k2".equals(back.items().get(1).apiKey), "密钥还原");
        ok("high".equals(back.items().get(1).level), "强度还原");

        System.out.println("t5 损坏数据要能兜底");
        ok(Profiles.fromJson("not json").items().isEmpty(), "非 JSON 返回空集合");
        ok(Profiles.fromJson("").items().isEmpty(), "空串返回空集合");
        ok(Profiles.fromJson("{\"active\":99,\"profiles\":[]}").items().isEmpty(), "空列表不越界");

        System.out.println("t6 越界下标不能崩");
        Profiles r = new Profiles();
        r.add(new Profiles.Profile("X", "u", "k", "m", "low"));
        r.get(5);
        r.update(5, new Profiles.Profile());
        r.remove(9);
        r.setActive(9);
        ok(r.items().size() == 1, "越界操作都被忽略");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
