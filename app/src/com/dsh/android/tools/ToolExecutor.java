package com.dsh.android.tools;

import org.json.JSONObject;

/**
 * 工具执行器接口。执行声明式工具，返回 JSON 结果字符串。
 */
public interface ToolExecutor {
    /** 执行工具，args 为模型给出的参数。返回结果 JSON 字符串。 */
    String execute(JSONObject args) throws Exception;
}
