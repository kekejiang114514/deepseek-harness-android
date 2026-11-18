package com.dsh.android.kernel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** MCP 服务器配置的读写（名称、地址、令牌）。 */
public final class McpServers {

    private McpServers() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences("dsh_mcp", Context.MODE_PRIVATE);
    }

    public static JSONArray load(Context ctx) {
        try {
            String raw = sp(ctx).getString("servers", "[]");
            JSONArray arr = new JSONArray(raw);
            return arr == null ? new JSONArray() : arr;
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    public static void save(Context ctx, JSONArray servers) {
        sp(ctx).edit().putString("servers", servers == null ? "[]" : servers.toString()).apply();
    }

    public static JSONArray add(Context ctx, String name, String url, String token) {
        JSONArray arr = load(ctx);
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("url", url);
            o.put("token", token == null ? "" : token);
            arr.put(o);
        } catch (Throwable ignored) {}
        save(ctx, arr);
        return arr;
    }

    public static JSONArray remove(Context ctx, int index) {
        JSONArray arr = load(ctx);
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            if (i == index) continue;
            out.put(arr.opt(i));
        }
        save(ctx, out);
        return out;
    }
}
