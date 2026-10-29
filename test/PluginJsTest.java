import com.dsh.android.plugins.js.Esm;
import com.dsh.android.plugins.js.Schema;
import com.dsh.android.plugins.js.Ts;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * DSH JS 插件的纯逻辑测试：TS 剥离、ESM 改写、参数 schema 转换。
 * 这三步决定插件能不能被装载，出问题的概率也最高，所以单独测。
 * （真正的 JS 执行在 WebView 里，靠 test/dshjstest.html 在浏览器里手工验证。）
 */
public class PluginJsTest {

    static int pass = 0;
    static int fail = 0;

    static void ok(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("  PASS  " + msg); }
        else { fail++; System.out.println("  FAIL  " + msg); }
    }

    static void eq(String got, String want, String msg) {
        ok(want.equals(got), msg + " (得到 '" + got + "')");
    }

    static boolean has(String s, String sub) {
        return s != null && s.indexOf(sub) >= 0;
    }

    static final String GREET =
        "import type { Context } from '@deepseek-ai/cordis'\n" +
        "import { defineTool } from '@deepseek-ai/dsh-tools'\n" +
        "\n" +
        "export const name = 'greet-tool'\n" +
        "export const inject = ['tools']\n" +
        "\n" +
        "export function apply(ctx: Context) {\n" +
        "  ctx.tools.register(defineTool({\n" +
        "    name: 'greet',\n" +
        "    description: 'Greet someone by name.',\n" +
        "    parameters: {\n" +
        "      name: { type: 'string', required: true, description: 'The name to greet' },\n" +
        "    },\n" +
        "    async execute(args) {\n" +
        "      return `Hello, ${args.name}!`\n" +
        "    },\n" +
        "  }))\n" +
        "}\n";

    public static void main(String[] a) {
        System.out.println("t1 TS 类型剥离");
        String stripped = Ts.strip(GREET);
        ok(!has(stripped, "ctx: Context"), "去掉参数类型标注");
        ok(has(stripped, "function apply(ctx) {"), "参数列表只剩形参名");
        ok(has(stripped, "async execute(args)"), "方法参数不受影响");
        ok(has(stripped, "parameters: {"), "对象字面量的冒号没被误删");
        ok(has(stripped, "name: 'greet',"), "对象字段保持原样");
        ok(has(stripped, "`Hello, ${args.name}!`"), "模板字符串完好");

        String opt = Ts.strip("function f(a?: string, b: number = 3) {}");
        ok(has(opt, "function f(a, b = 3)"), "可选参数与默认值：" + opt.trim());

        String as = Ts.strip("const x = y as Foo;\nconst z = 1;");
        ok(has(as, "const x = y;") && has(as, "const z = 1;"), "as 断言被去掉");

        String iface = Ts.strip("interface A { x: number }\nconst k = 1;");
        ok(has(iface, "const k = 1;") && !has(iface, "interface"), "interface 整块删除");

        String typ = Ts.strip("type X = { a: string };\nlet v = 2;");
        ok(has(typ, "let v = 2;") && !has(typ, "type X"), "type 声明删除");

        String ternary = Ts.strip("function g(n) { return n > 0 ? 'p' : 'neg'; }");
        ok(has(ternary, "? 'p' : 'neg'"), "三元表达式里的冒号不受影响");

        System.out.println("t2 ESM 改写");
        String esm = Esm.transform(Ts.strip(GREET));
        ok(has(esm, "const name = 'greet-tool'"), "export const 去掉 export");
        ok(has(esm, "function apply(ctx)"), "export function 去掉 export");
        ok(has(esm, "var { defineTool } = __mod('@deepseek-ai/dsh-tools')"), "具名 import 转 __mod");
        ok(!has(esm, "import type"), "import type 整行删除");
        ok(!has(esm, "export "), "不残留 export 关键字");

        eq(Esm.transform("export default 42"), "var __default = 42", "export default");
        eq(Esm.transform("import x from 'mod'"), "var x = __mod('mod');", "默认导入");
        eq(Esm.transform("import * as ns from 'mod'"), "var ns = __mod('mod');", "命名空间导入");
        eq(Esm.transform("import './side-effect'"), ";", "副作用导入删除");

        System.out.println("t3 不支持的依赖能提前发现");
        ok(Esm.firstUnsupportedImport("import fs from 'fs'\n") == null, "fs 现在由桥接提供，不再拦截");
        ok(Esm.firstUnsupportedImport("import path from 'node:path'") == null, "path 允许");
        eq(Esm.firstUnsupportedImport("import { spawn } from 'node:child_process'"),
                "child_process", "child_process 仍被拦截（带 node: 前缀也能认）");
        eq(Esm.firstUnsupportedImport("import net from 'net'"), "net", "net 被拦截");
        ok(Esm.firstUnsupportedImport(GREET) == null, "正常插件不误报");

        System.out.println("t4 参数 schema 转换");
        JSONObject dsh = new JSONObject();
        try {
            JSONObject name = new JSONObject();
            name.put("type", "string");
            name.put("required", true);
            name.put("description", "城市名");
            dsh.put("city", name);

            JSONObject n = new JSONObject();
            n.put("type", "int");
            dsh.put("count", n);

            JSONObject tags = new JSONObject();
            tags.put("type", "array");
            dsh.put("tags", tags);
        } catch (Exception ignored) {}

        JSONObject schema = Schema.toJsonSchema(dsh);
        eq(schema.optString("type"), "object", "顶层是 object");
        JSONObject props = schema.optJSONObject("properties");
        eq(props.optJSONObject("city").optString("type"), "string", "string 保持");
        eq(props.optJSONObject("city").optString("description"), "城市名", "描述带过来");
        eq(props.optJSONObject("count").optString("type"), "integer", "int 归一成 integer");
        eq(props.optJSONObject("tags").optString("type"), "array", "array 保持");
        JSONArray req = schema.optJSONArray("required");
        ok(req != null && req.length() == 1 && "city".equals(req.optString(0)), "required 收集正确");

        JSONObject empty = Schema.toJsonSchema(null);
        eq(empty.optString("type"), "object", "null 参数给空 schema");
        ok(empty.optJSONObject("properties") != null, "空 schema 也有 properties");

        System.out.println();
        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
