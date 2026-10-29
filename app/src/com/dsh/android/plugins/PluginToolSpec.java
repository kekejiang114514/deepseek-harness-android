package com.dsh.android.plugins;

import org.json.JSONObject;

/**
 * 插件里的一个工具：描述（给模型看）+ 一次 HTTP 请求的模板。
 *
 * 声明形如：
 *   {
 *     "name": "weather.now",
 *     "description": "查询城市当前天气",
 *     "parameters": { "type": "object", "properties": { "city": { "type": "string" } },
 *                     "required": ["city"] },
 *     "request": {
 *       "method": "GET",
 *       "url": "/v1/now?city={city}&key=${KEY}",
 *       "contentType": "json",     // json | form | 任意 MIME，仅 POST/PUT/PATCH 用
 *       "body": "{\"city\":\"{city|json}\"}",
 *       "timeoutMs": 20000,
 *       "resultPath": "data.temperature"
 *     }
 *   }
 */
public final class PluginToolSpec {

    public final String name;
    public final String description;
    public final JSONObject parameters;
    public final String method;
    /** 已与 baseUrl 拼接后的绝对地址模板。 */
    public final String url;
    public final String body;
    public final String contentType;
    public final String resultPath;
    public final int timeoutMs;

    PluginToolSpec(String name, String description, JSONObject parameters, String method,
                   String url, String body, String contentType, String resultPath, int timeoutMs) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.method = method;
        this.url = url;
        this.body = body;
        this.contentType = contentType;
        this.resultPath = resultPath;
        this.timeoutMs = timeoutMs;
    }

    /** 解析一个工具声明。request 可以嵌套，也可以和 name 平铺写。 */
    static PluginToolSpec fromJson(JSONObject d, String baseUrl) {
        String name = d.optString("name", "").trim();
        if (name.length() == 0) throw new IllegalArgumentException("工具缺少 name");
        if (!name.matches("[A-Za-z0-9_.-]{1,48}")) {
            throw new IllegalArgumentException("工具名 " + name + " 非法：只能用字母数字和 _ . -，长度 1-48");
        }

        // request 可以嵌套；没写 request 时字段直接平铺在工具上。
        // 两者同时存在时以 request 为准，缺的字段回落到外层，避免用户漏写一处就整条废掉。
        JSONObject req = d.optJSONObject("request");
        if (req == null) req = d;

        String url = pick(req, d, "url", "").trim();
        if (url.length() == 0) throw new IllegalArgumentException("工具 " + name + " 缺少 url");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            if (baseUrl == null || baseUrl.length() == 0) {
                throw new IllegalArgumentException("工具 " + name + " 用的是相对地址，但插件没有 baseUrl");
            }
            String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            if (!url.startsWith("/")) url = "/" + url;
            url = b + url;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("工具 " + name + " 的 url 必须是 http/https");
        }

        String method = pick(req, d, "method", "GET").trim().toUpperCase();
        if (!method.matches("GET|POST|PUT|PATCH|DELETE")) {
            throw new IllegalArgumentException("工具 " + name + " 的 method 不支持：" + method);
        }

        String body = pick(req, d, "body", null);
        if (body != null && body.length() == 0) body = null;
        String ct = pick(req, d, "contentType", "json").trim();
        String resultPath = pick(req, d, "resultPath", "").trim();
        int timeout = 20000;
        try {
            timeout = Integer.parseInt(pick(req, d, "timeoutMs", "20000").trim());
        } catch (Exception ignored) {}
        if (timeout < 1000) timeout = 1000;
        if (timeout > 120000) timeout = 120000;

        JSONObject params = d.optJSONObject("parameters");
        if (params == null) {
            params = new JSONObject();
            try {
                params.put("type", "object");
                params.put("properties", new JSONObject());
            } catch (Exception ignored) {}
        }

        String desc = d.optString("description", name);
        return new PluginToolSpec(name, desc, params, method, url, body,
                ct.length() == 0 ? "json" : ct, resultPath.length() == 0 ? null : resultPath, timeout);
    }

    /** 先看 request 里的值，没有再用外层工具的。 */
    private static String pick(JSONObject req, JSONObject flat, String key, String def) {
        if (req != null && req.has(key) && !req.isNull(key)) return req.optString(key, def);
        if (flat != null && flat != req && flat.has(key) && !flat.isNull(key)) {
            return flat.optString(key, def);
        }
        return def;
    }
}
