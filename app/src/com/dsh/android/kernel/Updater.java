package com.dsh.android.kernel;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检查 GitHub Releases 有没有新版本。
 *
 * <p>只用系统自带的 HttpURLConnection，不引第三方库。
 * 结果走回调（主线程），失败也不抛异常——查更新失败不该影响用应用。
 */
public final class Updater {

    /** 仓库地址，写死在这里，设置页只展示结果。 */
    public static final String REPO = "kekejiang114514/deepseek-harness-android";
    /**
     * 用「列出版本」而不是 /releases/latest：latest 会跳过预发布，
     * 一旦 1.0-beta1 标成 prerelease，latest 会退回 0.9，装了 0.9 的人就看不到 beta。
     * 拿列表自己挑最高版本，beta 和正式版都能正确提示。
     */
    private static final String API_LIST =
            "https://api.github.com/repos/" + REPO + "/releases?per_page=30";

    /** 自动检查的节流：一天一次，避免每次冷启动都打一次接口。 */
    private static final long AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 12000;

    public static final String KEY_AUTO = "update_auto";
    public static final String KEY_LAST_TS = "update_last_ts";
    public static final String KEY_LAST_TAG = "update_last_tag";
    public static final String KEY_LAST_ERR = "update_last_err";
    /** 用户点过「稍后」的版本，同一个版本不再弹窗打扰。 */
    public static final String KEY_SKIP = "update_skip";

    private Updater() {}

    /** 一个版本的信息。 */
    public static class Release {
        public String tag = "";        // v1.0-beta1（比较用原样 tag，预发布阶段不能丢）
        public String version = "";    // 1.0 beta1（展示用）
        public boolean prerelease = false;
        public String notes = "";       // 更新说明（Markdown 原文）
        public String pageUrl = "";    // 发布页
        public String apkUrl = "";     // 直链 APK，可能为空
        public long apkSize = 0;

        public boolean hasApk() { return apkUrl != null && apkUrl.length() > 0; }
        public String sizeText() {
            if (apkSize <= 0) return "";
            return (apkSize / 1024) + " KB";
        }
    }

    public interface Callback {
        /** r 为 null 表示已是最新版本。 */
        void onResult(Release r, String error);
    }

    // ------------------------------------------------------------ 查询

    /** 后台查一次，回调在主线程。 */
    public static void checkAsync(final Context ctx, final Callback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                Release r = null;
                String err = null;
                try {
                    Release latest = fetch();
                    if (latest == null) {
                        err = "没有找到发布记录";
                    } else if (isNewer(latest.tag, C.VERSION)) {
                        r = latest;
                    }
                    saveLast(ctx, err == null ? (r == null ? "" : r.tag) : "", err);
                } catch (Throwable e) {
                    err = readable(e);
                    saveLast(ctx, "", err);
                }
                final Release fr = r;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() { cb.onResult(fr, fe); }
                });
            }
        }, "update-check").start();
    }

    /** 同步拉取版本列表并挑出最高的一个（在后台线程调用）。 */
    public static Release fetch() throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API_LIST).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            // GitHub API 强制要 User-Agent，缺了直接 403
            c.setRequestProperty("User-Agent", "DeepSeekHarness-Android/" + C.VERSION);

            int code = c.getResponseCode();
            if (code == 404) return null;          // 还没有任何 release
            if (code == 403) {
                String body = read(c.getErrorStream());
                if (body.contains("rate limit")) {
                    throw new Exception("查询太频繁，请稍后再试");
                }
                throw new Exception("被 GitHub 拒绝（403）");
            }
            if (code != 200) throw new Exception("HTTP " + code);

            JSONArray arr = new JSONArray(read(c.getInputStream()));
            JSONObject best = ReleasePicker.best(arr);
            return best == null ? null : parseOne(best);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Release parseOne(JSONObject o) {
        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        r.version = pretty(r.tag);
        r.prerelease = o.optBoolean("prerelease", false);
        r.notes = o.optString("body", "");
        r.pageUrl = o.optString("html_url", "");

        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                String url = a.optString("browser_download_url", "");
                if (name.toLowerCase().endsWith(".apk") && url.length() > 0) {
                    // 目前只发一个包，取第一个 APK
                    r.apkUrl = url;
                    r.apkSize = a.optLong("size", 0);
                    break;
                }
            }
        }
        return r;
    }

    /** 展示用版本名：v1.0-beta1 → 1.0 beta1 */
    public static String pretty(String tag) {
        String s = tag == null ? "" : tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s.replace('-', ' ').trim();
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    // ------------------------------------------------------------ 版本比较

    /** 见 {@link Version}：单独成类是为了能在纯 JVM 下做单测。 */
    public static String normalize(String tag) {
        return Version.normalize(tag);
    }

    public static int compare(String a, String b) {
        return Version.compare(a, b);
    }

    public static boolean isNewer(String remote, String local) {
        return Version.isNewer(remote, local);
    }

    // ------------------------------------------------------------ 状态存取

    /** 自动检查是否开启（默认开）。 */
    public static boolean autoEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO, true);
    }

    public static void setAutoEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply();
    }

    /** 距上次检查是否已经超过一天（从没查过也算需要查）。 */
    public static boolean autoDue(Context ctx) {
        long last = prefs(ctx).getLong(KEY_LAST_TS, 0L);
        return System.currentTimeMillis() - last > AUTO_INTERVAL_MS;
    }

    public static String lastCheckedText(Context ctx) {
        long ts = prefs(ctx).getLong(KEY_LAST_TS, 0L);
        if (ts <= 0) return "还没查过";
        long min = (System.currentTimeMillis() - ts) / 60000L;
        if (min < 1) return "刚刚查过";
        if (min < 60) return min + " 分钟前查过";
        long hr = min / 60;
        if (hr < 24) return hr + " 小时前查过";
        return (hr / 24) + " 天前查过";
    }

    public static String lastTag(Context ctx) {
        return prefs(ctx).getString(KEY_LAST_TAG, "");
    }

    public static String lastError(Context ctx) {
        return prefs(ctx).getString(KEY_LAST_ERR, "");
    }

    public static void skipVersion(Context ctx, String tag) {
        prefs(ctx).edit().putString(KEY_SKIP, tag == null ? "" : tag).apply();
    }

    public static boolean isSkipped(Context ctx, String tag) {
        if (tag == null || tag.length() == 0) return false;
        return tag.equals(prefs(ctx).getString(KEY_SKIP, ""));
    }

    private static void saveLast(Context ctx, String tag, String err) {
        prefs(ctx).edit()
                .putLong(KEY_LAST_TS, System.currentTimeMillis())
                .putString(KEY_LAST_TAG, tag == null ? "" : tag)
                .putString(KEY_LAST_ERR, err == null ? "" : err)
                .apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE);
    }

    /** 把网络异常翻译成用户能看懂的一句话。 */
    private static String readable(Throwable e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String low = m.toLowerCase();
        if (low.contains("permission") || low.contains("denied")) return "没有网络权限";
        if (low.contains("timeout") || low.contains("timed out")) return "连接超时";
        if (low.contains("unable to resolve host") || low.contains("unknownhost")) {
            return "解析不了域名，检查网络";
        }
        if (low.contains("network is unreachable") || low.contains("unreachable")) {
            return "网络不可用";
        }
        return m;
    }
}
