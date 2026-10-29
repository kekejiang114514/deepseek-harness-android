package com.dsh.android.plugins;

import com.dsh.android.kernel.EventBus;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 联网插件：网页搜索与正文抓取。
 * 搜索走 DuckDuckGo（无需 API Key），抓取自己解析正文，避免依赖第三方服务。
 */
public final class WebPlugin extends Plugin {

    private static final PluginMeta BUILD_META = buildMeta();

    public WebPlugin(EventBus bus) {
        super(BUILD_META, bus);
    }

    private static PluginMeta buildMeta() {
        return new PluginMeta("web", "联网", "1.0.0",
                "搜索网页与抓取页面内容", "dsh", declarations());
    }

    private static JSONArray declarations() {
        JSONArray arr = new JSONArray();

        // ---- web.search ----
        JSONObject search = new JSONObject();
        try {
            search.put("name", "web.search");
            search.put("type", "web.search");
            search.put("description", "在互联网上搜索。当需要最新信息、事实核查、或用户问题涉及当前事件时必须使用。返回标题、链接和摘要。");
            JSONObject p = new JSONObject();
            p.put("type", "object");
            JSONObject props = new JSONObject();

            JSONObject q = new JSONObject();
            q.put("type", "string");
            q.put("description", "搜索关键词，用自然语言描述要找什么");
            props.put("query", q);

            JSONObject n = new JSONObject();
            n.put("type", "integer");
            n.put("description", "返回结果数量，1-10，默认 6");
            props.put("count", n);

            p.put("properties", props);
            p.put("required", new JSONArray().put("query"));
            search.put("parameters", p);
            arr.put(search);

            // ---- web.fetch ----
            JSONObject fetch = new JSONObject();
            fetch.put("name", "web.fetch");
            fetch.put("type", "web.fetch");
            fetch.put("description", "抓取指定网页并返回正文文本。用于读取搜索结果的详细内容。");
            JSONObject p2 = new JSONObject();
            p2.put("type", "object");
            JSONObject props2 = new JSONObject();
            JSONObject u = new JSONObject();
            u.put("type", "string");
            u.put("description", "完整网址，以 http:// 或 https:// 开头");
            props2.put("url", u);
            p2.put("properties", props2);
            p2.put("required", new JSONArray().put("url"));
            fetch.put("parameters", p2);
            arr.put(fetch);
        } catch (Exception ignored) {}
        return arr;
    }

    @Override
    public void apply() {
        // 无后台状态，工具由 ToolRuntime 执行
    }

    @Override
    public void dispose() {
        // 无资源需释放
    }
}
