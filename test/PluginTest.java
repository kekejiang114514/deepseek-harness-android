import com.dsh.android.plugins.JsonPath;
import com.dsh.android.plugins.PluginSpec;
import com.dsh.android.plugins.PluginToolSpec;
import com.dsh.android.plugins.Template;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件系统纯 JVM 测试：模板替换、resultPath 取值、清单校验、冲突检测。
 * 都不碰 Android，可以直接在桌面 JDK 上跑。
 */
public class PluginTest {

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

    static void throwsIae(String msg, Runnable r) {
        try {
            r.run();
            ok(false, msg + "（没有抛异常）");
        } catch (IllegalArgumentException e) {
            ok(true, msg + " → " + e.getMessage());
        } catch (Throwable t) {
            ok(false, msg + "（抛了 " + t.getClass().getSimpleName() + "）");
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("t1 模板：参数替换");
        JSONObject p = new JSONObject();
        p.put("city", "北京");
        p.put("n", 5);
        p.put("flag", true);
        ok(Template.render("q={city}", p, null, null).equals("q=%E5%8C%97%E4%BA%AC"),
                "中文参数做 URL 编码");
        ok(Template.render("q={city}", p, null, null).indexOf('+') < 0, "空格不出现 +");
        eq(Template.render("n={n|raw}", p, null, null), "n=5", "raw 不转义");
        eq(Template.render("f={flag|raw}", p, null, null), "f=true", "布尔 raw");

        System.out.println("t2 模板：JSON 转义");
        JSONObject tricky = new JSONObject();
        tricky.put("s", "he said \"hi\"\nline2\\end");
        String body = Template.render("{\"text\":\"{s|json}\"}", tricky, null, null);
        JSONObject parsed = new JSONObject(body);
        eq(parsed.getString("text"), "he said \"hi\"\nline2\\end", "转义后仍是合法 JSON 且值不变");

        System.out.println("t3 模板：密钥与缺值");
        JSONObject secrets = new JSONObject();
        secrets.put("KEY", "abc123");
        eq(Template.render("?k=${KEY}", p, secrets, null), "?k=abc123", "密钥替换");

        List<String> missing = new ArrayList<String>();
        eq(Template.render("{city}/{nope}", p, null, missing), "%E5%8C%97%E4%BA%AC/",
                "缺的参数渲染成空串");
        ok(missing.size() == 1 && missing.get(0).equals("nope"), "缺的参数被记录");

        List<String> miss2 = new ArrayList<String>();
        Template.render("${NOKEY}", p, secrets, miss2);
        ok(miss2.contains("$NOKEY"), "缺的密钥被记录");

        System.out.println("t4 模板：转义与边界");
        eq(Template.render("{{city}", p, null, null), "{city}", "{{ 转义成字面量 {，不再替换");
        ok(Template.render("{\"a\":{\"b\":1}}", p, null, null).equals("{\"a\":{\"b\":1}}"),
                "纯 JSON 花括号原样输出，不被当占位符");
        eq(Template.render("", p, null, null), "", "空模板");
        eq(Template.render(null, p, null, null), "", "null 模板");
        eq(Template.render("no placeholder", p, null, null), "no placeholder", "无占位符原样");
        eq(Template.render("{n}", p, null, null), "5", "数字参数");
        JSONArray arr = new JSONArray();
        arr.put("a");
        arr.put("b");
        JSONObject withArr = new JSONObject();
        withArr.put("tags", arr);
        eq(Template.render("{tags|raw}", withArr, null, null), "a,b", "数组用逗号拼接");

        System.out.println("t5 resultPath 取值");
        JSONObject root = new JSONObject(
                "{\"data\":{\"list\":[{\"name\":\"first\"},{\"name\":\"second\"}],\"total\":2}}");
        eq((String) JsonPath.pick(root, "data.list[1].name"), "second", "数组下标 + 字段");
        eq((String) JsonPath.pick(root, "data.list[0].name"), "first", "第一个元素");
        ok(JsonPath.pick(root, "data.total") == Integer.valueOf(2), "数字取值");
        ok(JsonPath.pick(root, "data.nope") == null, "字段不存在返回 null");
        ok(JsonPath.pick(root, "data.list[9]") == null, "下标越界返回 null");
        ok(JsonPath.pick(root, "data.list.x") == null, "非数字下标返回 null");
        ok(JsonPath.pick(root, "data.list.name") == null, "数组上取字段返回 null");
        ok(JsonPath.pick(root, "") == root, "空路径返回根");
        ok(JsonPath.pick(root, "$") == root, "$ 返回根");
        eq(JsonPath.pickText(root, "data.list[0]"), "{\"name\":\"first\"}", "对象取文本");

        System.out.println("t6 清单校验");
        PluginSpec good = PluginSpec.parse(minimalManifest(), new File("/tmp/plugin.json"));
        ok(good.tools.size() == 1, "解析出一个工具");
        eq(good.id, "demo", "id 正确");
        eq(good.tools.get(0).url, "https://api.example.com/v1/now?city={city}", "相对 url 拼上 baseUrl");

        throwsIae("缺 id", new Runnable() {
            public void run() {
                PluginSpec.parse("{\"tools\":[{\"name\":\"a.b\",\"url\":\"https://x.com\"}]}", null);
            }
        });
        throwsIae("id 含非法字符", new Runnable() {
            public void run() {
                PluginSpec.parse("{\"id\":\"a b\",\"tools\":[{\"name\":\"a.b\",\"url\":\"https://x.com\"}]}", null);
            }
        });
        throwsIae("没有 tools", new Runnable() {
            public void run() {
                PluginSpec.parse("{\"id\":\"demo\"}", null);
            }
        });
        throwsIae("tools 为空数组", new Runnable() {
            public void run() {
                PluginSpec.parse("{\"id\":\"demo\",\"tools\":[]}", null);
            }
        });
        throwsIae("工具重名", new Runnable() {
            public void run() {
                PluginSpec.parse("{\"id\":\"demo\",\"tools\":["
                        + "{\"name\":\"a.b\",\"url\":\"https://x.com/1\"},"
                        + "{\"name\":\"a.b\",\"url\":\"https://x.com/2\"}]}", null);
            }
        });
        throwsIae("相对地址没有 baseUrl", new Runnable() {
            public void run() {
                PluginSpec.parse("{\"id\":\"demo\",\"tools\":[{\"name\":\"a.b\",\"url\":\"/v1\"}]}", null);
            }
        });
        throwsIae("不支持的 method", new Runnable() {
            public void run() {
                PluginSpec.parse("{\"id\":\"demo\",\"tools\":["
                        + "{\"name\":\"a.b\",\"url\":\"https://x.com\",\"method\":\"TRACE\"}]}", null);
            }
        });
        throwsIae("baseUrl 协议非法", new Runnable() {
            public void run() {
                PluginSpec.parse("{\"id\":\"demo\",\"baseUrl\":\"ftp://x.com\",\"tools\":["
                        + "{\"name\":\"a.b\",\"url\":\"/v1\"}]}", null);
            }
        });
        throwsIae("坏 JSON", new Runnable() {
            public void run() {
                PluginSpec.parse("{not json", null);
            }
        });

        System.out.println("t7 请求默认值与上限");
        PluginToolSpec flat = PluginSpec.parse("{\"id\":\"d\",\"tools\":["
                + "{\"name\":\"x.y\",\"url\":\"https://x.com/a\",\"method\":\"post\","
                + "\"request\":{\"timeoutMs\":999999}}]}", null).tools.get(0);
        eq(flat.method, "POST", "method 转大写");
        eq(flat.contentType, "json", "contentType 默认 json");
        ok(flat.timeoutMs == 120000, "超时被夹到 120s 上限");
        ok(flat.resultPath == null, "未声明 resultPath 时为 null");
        ok(flat.parameters.has("properties"), "未声明 parameters 时给空 schema");

        System.out.println("t8 清单里的工具名导出给模型");
        JSONArray decl = good.declarations();
        eq(decl.getJSONObject(0).getString("name"), "demo.now", "declarations 带出工具名");
        ok(decl.getJSONObject(0).has("parameters"), "declarations 带出参数 schema");

        System.out.println();
        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    private static String minimalManifest() {
        return "{\n"
                + "  \"id\": \"demo\",\n"
                + "  \"name\": \"演示\",\n"
                + "  \"version\": \"1.0.0\",\n"
                + "  \"baseUrl\": \"https://api.example.com\",\n"
                + "  \"tools\": [{\n"
                + "    \"name\": \"demo.now\",\n"
                + "    \"description\": \"查当前值\",\n"
                + "    \"parameters\": {\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}},\n"
                + "    \"request\": {\"method\":\"GET\",\"url\":\"/v1/now?city={city}\"}\n"
                + "  }]\n"
                + "}";
    }
}
