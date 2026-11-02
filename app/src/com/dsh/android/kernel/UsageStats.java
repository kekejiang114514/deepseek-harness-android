package com.dsh.android.kernel;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地用量统计：请求次数与 token 消耗。
 *
 * <p>只记录服务端在响应里回传的 usage，不在本地估算——估算出来的数字对不上账单，
 * 反而误导用户。余额另由 {@link Balance} 查询。
 */
public final class UsageStats {

    private static final String K_REQ = "usage_requests";
    private static final String K_PROMPT = "usage_prompt";
    private static final String K_COMPLETION = "usage_completion";

    private UsageStats() {}

    public static void addRequest(Context c) {
        SharedPreferences sp = prefs(c);
        sp.edit().putInt(K_REQ, sp.getInt(K_REQ, 0) + 1).apply();
    }

    public static void addUsage(Context c, long prompt, long completion) {
        if (prompt <= 0 && completion <= 0) return;
        SharedPreferences sp = prefs(c);
        sp.edit()
                .putLong(K_PROMPT, sp.getLong(K_PROMPT, 0L) + Math.max(0, prompt))
                .putLong(K_COMPLETION, sp.getLong(K_COMPLETION, 0L) + Math.max(0, completion))
                .apply();
    }

    public static int requests(Context c) {
        return prefs(c).getInt(K_REQ, 0);
    }

    public static long promptTokens(Context c) {
        return prefs(c).getLong(K_PROMPT, 0L);
    }

    public static long completionTokens(Context c) {
        return prefs(c).getLong(K_COMPLETION, 0L);
    }

    public static long totalTokens(Context c) {
        return promptTokens(c) + completionTokens(c);
    }

    public static void reset(Context c) {
        prefs(c).edit().remove(K_REQ).remove(K_PROMPT).remove(K_COMPLETION).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE);
    }
}
