package com.dsh.android.kernel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * QQ 机器人的本地配置。
 *
 * <p>clientSecret 用 SecureStore 加密存放；白名单为空时**不响应任何人**，
 * 避免机器人被陌生群拉进去白烧额度。
 */
public final class QqConfig {

    private static final String SP = "dsh_qq_bot";

    private QqConfig() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static String appId(Context ctx) {
        return sp(ctx).getString("app_id", "");
    }

    public static String secret(Context ctx) {
        return SecureStore.open(sp(ctx).getString("secret", ""));
    }

    public static void save(Context ctx, String appId, String secret) {
        sp(ctx).edit()
                .putString("app_id", appId == null ? "" : appId.trim())
                .putString("secret", SecureStore.seal(secret == null ? "" : secret.trim()))
                .apply();
    }

    public static boolean enabled(Context ctx) {
        return sp(ctx).getBoolean("enabled", false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("enabled", on).apply();
    }

    /** 白名单：群 openid 或用户 openid，一行一个。 */
    public static java.util.List<String> whitelist(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        String raw = sp(ctx).getString("whitelist", "");
        String[] parts = raw.split("\n");
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            if (s.length() > 0) out.add(s);
        }
        return out;
    }

    public static void setWhitelist(Context ctx, java.util.List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(list.get(i).trim());
        }
        sp(ctx).edit().putString("whitelist", sb.toString()).apply();
    }

    /** 名单模式：white 只响应名单内，black 只拦截名单内，all 全部响应。 */
    public static String mode(Context ctx) {
        // 默认「全部」：刚接入时用户往往还不知道 openid 长什么样，
        // 默认白名单会导致「连上了却不回话」。额度保护交给每日配额。
        return sp(ctx).getString("mode", "all");
    }

    public static void setMode(Context ctx, String mode) {
        sp(ctx).edit().putString("mode", mode == null ? "white" : mode).apply();
    }

    public static boolean allowGroup(Context ctx) {
        return sp(ctx).getBoolean("allow_group", true);
    }

    public static boolean allowC2c(Context ctx) {
        return sp(ctx).getBoolean("allow_c2c", true);
    }

    public static void setAllowGroup(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("allow_group", on).apply();
    }

    public static void setAllowC2c(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("allow_c2c", on).apply();
    }

    /**
     * 是否应该响应这条来源。
     *
     * @param kind   group / c2c
     * @param openid 群或用户的 openid
     */
    public static boolean allowed(Context ctx, String kind, String openid) {
        if ("group".equals(kind) && !allowGroup(ctx)) return false;
        if ("c2c".equals(kind) && !allowC2c(ctx)) return false;

        String m = mode(ctx);
        boolean inList = false;
        java.util.List<String> list = whitelist(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equals(openid)) { inList = true; break; }
        }
        if ("all".equals(m)) return true;
        if ("black".equals(m)) return !inList;
        // 白名单：名单为空时视为什么都不放开，避免被陌生群白烧额度
        if (list.isEmpty()) return false;
        return inList;
    }

    // ---------------------------------------------------------------- 最近来源

    /** 记下最近出现过的来源，方便用户把它们填进名单。 */
    public static void noteSource(Context ctx, String kind, String openid) {
        if (openid == null || openid.length() == 0) return;
        java.util.List<String> cur = recent(ctx);
        String entry = kind + "  " + openid;
        cur.remove(entry);
        cur.add(0, entry);
        while (cur.size() > 8) cur.remove(cur.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cur.size(); i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(cur.get(i));
        }
        sp(ctx).edit().putString("recent", sb.toString()).apply();
    }

    public static java.util.List<String> recent(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        String raw = sp(ctx).getString("recent", "");
        String[] parts = raw.split("\n");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].trim().length() > 0) out.add(parts[i].trim());
        }
        return out;
    }

    /** 今日已回复条数（用于限额）。 */
    public static int todayCount(Context ctx) {
        String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
        if (!day.equals(sp(ctx).getString("count_day", ""))) return 0;
        return sp(ctx).getInt("count_today", 0);
    }

    public static void bumpToday(Context ctx) {
        String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
        int n = todayCount(ctx);
        sp(ctx).edit().putString("count_day", day).putInt("count_today", n + 1).apply();
    }

    /** 每日回复上限；0 表示不限制。 */
    public static int dailyLimit(Context ctx) {
        return sp(ctx).getInt("daily_limit", 300);
    }

    /** 上限的展示文本。 */
    public static String dailyLimitText(Context ctx) {
        int n = dailyLimit(ctx);
        return n <= 0 ? "不限制" : (n + " 条/天");
    }

    public static void setDailyLimit(Context ctx, int n) {
        sp(ctx).edit().putInt("daily_limit", n).apply();
    }

    /** 最近一次运行状态，设置页展示。 */
    public static String status(Context ctx) {
        return sp(ctx).getString("status", "尚未启动");
    }

    public static void setStatus(Context ctx, String s) {
        sp(ctx).edit().putString("status", s == null ? "" : s).apply();
    }
}
