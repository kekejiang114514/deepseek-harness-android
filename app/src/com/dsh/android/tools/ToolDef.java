package com.dsh.android.tools;

import org.json.JSONObject;

/**
 * 一个可执行工具的定义（对标 OpenAI function 的 JSON Schema + 执行器绑定）。
 */
public final class ToolDef {
    public final String name;
    public final String description;
    public final JSONObject parameters; // JSON Schema (properties/required)
    public final ToolExecutor executor; // 实际执行器（内置：http/a11y/notify 等）

    public ToolDef(String name, String description, JSONObject parameters, ToolExecutor executor) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.executor = executor;
    }
}
