package com.dsh.android.plugins;

import com.dsh.android.kernel.EventBus;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 内置文件插件：让 Agent 读写文件。作用域由 ToolRuntime 按「受限/全局」模式强制约束。
 */
public final class FilePlugin extends Plugin {

    public FilePlugin(EventBus bus) {
        super(buildMeta(), bus);
    }

    private static PluginMeta buildMeta() {
        JSONArray tools = new JSONArray();
        try {
            add(tools, "fs.list", "fs.list",
                "列出目录内容。路径可以是相对路径（相对工作区）或绝对路径。",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"目录路径，默认工作区根目录\"}}}");
            add(tools, "fs.read", "fs.read",
                "读取文本文件内容（上限 256KB）。",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");
            add(tools, "fs.write", "fs.write",
                "写入文本文件（自动创建父目录，可追加）。",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"append\":{\"type\":\"boolean\"}},\"required\":[\"path\",\"content\"]}");
            add(tools, "fs.mkdir", "fs.mkdir",
                "创建目录。",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");
            add(tools, "fs.delete", "fs.delete",
                "删除文件或目录（递归）。",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");
            add(tools, "fs.info", "fs.info",
                "查询路径信息（是否存在、大小、可读可写）。",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new PluginMeta("builtin.fs", "文件系统", "1.0.0",
            "内置：读写文件，受工作区/全局作用域约束。", "DShAndroid", tools);
    }

    private static void add(JSONArray arr, String name, String type, String desc, String schema) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("type", type);
        o.put("description", desc);
        o.put("parameters", schema == null ? new JSONObject().put("type", "object") : new JSONObject(schema));
        arr.put(o);
    }

    @Override
    public void apply() {}
}
