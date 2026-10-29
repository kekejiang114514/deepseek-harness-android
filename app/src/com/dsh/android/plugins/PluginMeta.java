package com.dsh.android.plugins;

import org.json.JSONObject;
import org.json.JSONArray;

/**
 * 插件元数据，对应插件目录里的 plugin.json。
 * 声明式描述：id、名称、描述、以及暴露的工具（工具映射到工具运行时的内置执行器）。
 */
public final class PluginMeta {
    public final String id;
    public final String name;
    public final String version;
    public final String description;
    public final String author;
    /** 声明式工具定义（JSON 数组），供 ToolRuntime 解析。 */
    public final JSONArray declarations;

    public PluginMeta(String id, String name, String version,
                      String description, String author, JSONArray declarations) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.description = description;
        this.author = author;
        this.declarations = declarations;
    }

    /** 从 plugin.json 文本解析。 */
    public static PluginMeta fromJson(String text) {
        try {
            JSONObject o = new JSONObject(text);
            JSONArray decl = o.optJSONArray("tools");
            if (decl == null) decl = new JSONArray();
            return new PluginMeta(
                o.optString("id", "unknown"),
                o.optString("name", o.optString("id", "unknown")),
                o.optString("version", "0.0.0"),
                o.optString("description", ""),
                o.optString("author", ""),
                decl);
        } catch (Exception e) {
            return null;
        }
    }
}
