import com.dsh.android.plugins.PluginHttpExecutor;
import com.dsh.android.plugins.PluginSample;
import com.dsh.android.plugins.PluginSpec;
import com.dsh.android.plugins.PluginToolSpec;

import org.json.JSONObject;

/**
 * 端到端验证：用真正的 PluginSample.JSON 走完整条链路
 * （解析清单 → 模板替换 → 发 HTTP → resultPath 取值 → 输出工具结果）。
 * 需要联网，只在本地手工跑，不进 test/run.sh。
 */
public class PluginE2E {

    public static void main(String[] a) throws Exception {
        PluginSpec spec = PluginSpec.parse(PluginSample.JSON, null);
        System.out.println("清单解析 OK：" + spec.id + " / " + spec.name
                + " / " + spec.tools.size() + " 个工具");

        PluginToolSpec city = spec.tools.get(0);
        System.out.println("\n--- " + city.name + " ---");
        System.out.println("url 模板: " + city.url);
        System.out.println("resultPath: " + city.resultPath);

        JSONObject args = new JSONObject();
        args.put("city", "北京");
        String r1 = new PluginHttpExecutor(spec, city).execute(args);
        System.out.println("结果: " + r1);

        JSONObject o1 = new JSONObject(r1);
        if (!o1.optBoolean("ok")) throw new IllegalStateException("第一个工具失败");

        JSONObject place = o1.getJSONObject("data");
        System.out.println("取到: " + place.optString("name") + " "
                + place.optDouble("latitude") + "," + place.optDouble("longitude"));

        // 第二个工具用第一个的返回值，模拟模型串起两个调用
        PluginToolSpec weather = spec.tools.get(1);
        System.out.println("\n--- " + weather.name + " ---");
        JSONObject args2 = new JSONObject();
        args2.put("lat", place.optDouble("latitude"));
        args2.put("lon", place.optDouble("longitude"));
        String r2 = new PluginHttpExecutor(spec, weather).execute(args2);
        System.out.println("结果: " + r2);
        JSONObject o2 = new JSONObject(r2);
        if (!o2.optBoolean("ok")) throw new IllegalStateException("第二个工具失败");

        // 缺参数应当报错而不是发一个坏请求
        System.out.println("\n--- 缺参数 ---");
        System.out.println(new PluginHttpExecutor(spec, city).execute(new JSONObject()));

        // 错地址应当返回 ok:false 而不是崩
        System.out.println("\n--- 404 ---");
        PluginToolSpec bad = PluginSpec.parse("{\"id\":\"bad\",\"tools\":["
                + "{\"name\":\"bad.x\",\"url\":\"https://geocoding-api.open-meteo.com/nope\"}]}",
                null).tools.get(0);
        System.out.println(new PluginHttpExecutor(
                PluginSpec.parse("{\"id\":\"bad\",\"tools\":["
                        + "{\"name\":\"bad.x\",\"url\":\"https://geocoding-api.open-meteo.com/nope\"}]}", null),
                bad).execute(new JSONObject()));

        System.out.println("\nE2E OK");
    }
}
