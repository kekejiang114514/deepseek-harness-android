package com.dsh.android.plugins;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个插件的完整清单（plugin.json 解析结果）。
 *
 * 最小可用清单：
 *   {
 *     "id": "weather", "name": "天气", "version": "1.0.0",
 *     "baseUrl": "https://api.example.com",
 *     "headers": { "X-Token": "${KEY}" },
 *     "secrets": { "KEY": "abc123" },
 *     "tools": [ { ...见 PluginToolSpec... } ]
 *   }
 *
 * 解析失败一律抛 IllegalArgumentException，错误信息直接给用户看。
 */
public final class PluginSpec {

    public final String id;
    public final String name;
    public final String version;
    public final String description;
    public final String author;
    public final String baseUrl;
    public final JSONObject headers;
    public final JSONObject secrets;
    public final List<PluginToolSpec> tools;
    /** 来源文件（plugin.json 或单个 .json），用于界面展示与排错。 */
    public final File source;

    private PluginSpec(String id, String name, String version, String description, String author,
                       String baseUrl, JSONObject headers, JSONObject secrets,
                       List<PluginToolSpec> tools, File source) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.description = description;
        this.author = author;
        this.baseUrl = baseUrl;
        this.headers = headers;
        this.secrets = secrets;
        this.tools = tools;
        this.source = source;
    }

    public JSONArray declarations() {
        JSONArray arr = new JSONArray();
        for (PluginToolSpec t : tools) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", t.name);
                o.put("description", t.description);
                o.put("parameters", t.parameters);
            } catch (Exception ignored) {}
            arr.put(o);
        }
        return arr;
    }

    public static PluginSpec parse(JSONObject o, File source) {
        String id = o.optString("id", "").trim();
        if (id.length() == 0) throw new IllegalArgumentException("缺少 id");
        if (!id.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("id 非法：" + id + "（只能用字母数字和 _ -，1-32 位）");
        }

        String baseUrl = o.optString("baseUrl", "").trim();
        if (baseUrl.length() > 0
                && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("baseUrl 必须以 http:// 或 https:// 开头");
        }

        JSONArray toolArr = o.optJSONArray("tools");
        if (toolArr == null || toolArr.length() == 0) {
            throw new IllegalArgumentException("没有声明任何 tools");
        }

        List<PluginToolSpec> tools = new ArrayList<PluginToolSpec>();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < toolArr.length(); i++) {
            JSONObject d = toolArr.optJSONObject(i);
            if (d == null) continue;
            PluginToolSpec t = PluginToolSpec.fromJson(d, baseUrl);
            if (!seen.add(t.name)) {
                throw new IllegalArgumentException("工具名重复：" + t.name);
            }
            tools.add(t);
        }
        if (tools.isEmpty()) throw new IllegalArgumentException("没有可用的 tools");

        String name = o.optString("name", id).trim();
        if (name.length() == 0) name = id;

        return new PluginSpec(
                id,
                name,
                o.optString("version", "0.0.0").trim(),
                o.optString("description", "").trim(),
                o.optString("author", "").trim(),
                baseUrl,
                o.optJSONObject("headers") == null ? new JSONObject() : o.optJSONObject("headers"),
                o.optJSONObject("secrets") == null ? new JSONObject() : o.optJSONObject("secrets"),
                tools,
                source);
    }

    public static PluginSpec parse(String text, File source) {
        try {
            return parse(new JSONObject(text), source);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败：" + e.getMessage());
        }
    }
}
