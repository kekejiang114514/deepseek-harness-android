package com.dsh.android.plugins.js;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * DSH 的工具参数声明 → OpenAI/JSON Schema。
 *
 * <p>DSH（Cordis）用的是一种紧凑写法：
 * <pre>
 * parameters: {
 *   name: { type: 'string', required: true, description: 'The name to greet' }
 * }
 * </pre>
 * 而模型 API 要的是标准 JSON Schema：
 * <pre>
 * {"type":"object","properties":{"name":{"type":"string","description":"..."}},
 *  "required":["name"]}
 * </pre>
 * 这个类只做形状转换，不校验具体类型是否合法（DSH 自己会校验）。
 */
public final class Schema {

    private Schema() {}

    /** 把 DSH 的 parameters 对象转成 JSON Schema。null/空时给一个空 object schema。 */
    public static JSONObject toJsonSchema(JSONObject dsh) {
        JSONObject out = new JSONObject();
        try {
            out.put("type", "object");
            JSONObject props = new JSONObject();
            JSONArray required = new JSONArray();
            if (dsh != null) {
                java.util.Iterator<String> it = dsh.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    Object raw = dsh.opt(key);
                    JSONObject prop = new JSONObject();
                    if (raw instanceof JSONObject) {
                        JSONObject d = (JSONObject) raw;
                        String type = d.optString("type", "string");
                        prop.put("type", normalizeType(type));
                        String desc = d.optString("description", "");
                        if (desc.length() > 0) prop.put("description", desc);
                        if (d.optBoolean("required", false)) required.put(key);
                        if ("enum".equals(type)) {
                            JSONArray ev = d.optJSONArray("values");
                            if (ev != null) prop.put("enum", ev);
                        }
                        if (d.has("items")) prop.put("items", d.opt("items"));
                        if (d.has("default")) prop.put("default", d.opt("default"));
                        if (d.has("properties")) prop.put("properties", d.opt("properties"));
                    } else if (raw instanceof String) {
                        prop.put("type", normalizeType((String) raw));
                    } else {
                        prop.put("type", "string");
                    }
                    props.put(key, prop);
                }
            }
            out.put("properties", props);
            if (required.length() > 0) out.put("required", required);
        } catch (Exception ignored) {}
        return out;
    }

    /** DSH 里写过 'array'/'object'/'number' 等都合法；这里只统一小写与个别别名。 */
    static String normalizeType(String type) {
        if (type == null) return "string";
        String t = type.trim().toLowerCase();
        if ("int".equals(t) || "integer".equals(t)) return "integer";
        if ("float".equals(t) || "double".equals(t) || "num".equals(t)) return "number";
        if ("bool".equals(t) || "boolean".equals(t)) return "boolean";
        if ("arr".equals(t) || "array".equals(t)) return "array";
        if ("obj".equals(t) || "object".equals(t)) return "object";
        if ("str".equals(t) || "string".equals(t) || "text".equals(t)) return "string";
        return "string";
    }
}
