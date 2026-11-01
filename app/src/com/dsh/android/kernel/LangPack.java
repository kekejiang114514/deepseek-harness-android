package com.dsh.android.kernel;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * 语言包：除内置语言外的界面文案，按需从仓库下载后缓存在本地。
 *
 * <p>内置语言（简体、繁体三地、英、俄、日、韩）直接打进安装包；其余语言做成
 * {@code lang/<code>.json} 放在仓库里，选中时下载一次即可离线使用。
 *
 * <p>下载源有两个，任一成功即可：raw.githubusercontent.com 与 jsDelivr CDN
 * （后者在国内通常更稳）。
 */
public final class LangPack {

    private static final String RAW = "https://raw.githubusercontent.com/"
            + "kekejiang114514/deepseek-harness-android/main/lang/";
    private static final String CDN = "https://cdn.jsdelivr.net/gh/"
            + "kekejiang114514/deepseek-harness-android@main/lang/";
    private static final int TIMEOUT = 20000;

    /** 内存缓存：语言代码 -> (key -> 译文)。 */
    private static final Map<String, JSONObject> CACHE = new HashMap<String, JSONObject>();

    private LangPack() {}

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "langpack");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File fileOf(Context c, String code) {
        return new File(dir(c), code + ".json");
    }

    public static boolean installed(Context c, String code) {
        return fileOf(c, code).isFile();
    }

    /** 读取语言包；没装返回 null。 */
    public static JSONObject load(Context c, String code) {
        synchronized (CACHE) {
            if (CACHE.containsKey(code)) return CACHE.get(code);
        }
        File f = fileOf(c, code);
        if (!f.isFile()) return null;
        try {
            String text = readAll(new FileInputStream(f));
            JSONObject o = new JSONObject(text);
            synchronized (CACHE) {
                CACHE.put(code, o);
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** 取一条译文；没有返回 null。 */
    public static String translate(Context c, String code, String key) {
        JSONObject pack = load(c, code);
        if (pack == null) return null;
        JSONObject strings = pack.optJSONObject("strings");
        if (strings == null) return null;
        String v = strings.optString(key, null);
        return v == null || v.length() == 0 ? null : v;
    }

    /** 下载语言包。返回 null 表示成功，否则是给用户看的原因。 */
    public static String download(Context c, String code) {
        String err = null;
        for (String base : new String[]{RAW, CDN}) {
            try {
                String text = httpGet(base + code + ".json");
                JSONObject o = new JSONObject(text);
                if (!o.has("strings")) throw new Exception("格式不对");
                File f = fileOf(c, code);
                FileOutputStream out = new FileOutputStream(f);
                try {
                    out.write(text.getBytes("UTF-8"));
                    out.flush();
                } finally {
                    out.close();
                }
                synchronized (CACHE) {
                    CACHE.put(code, o);
                }
                return null;
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        return err == null ? "下载失败" : err;
    }

    public static void remove(Context c, String code) {
        fileOf(c, code).delete();
        synchronized (CACHE) {
            CACHE.remove(code);
        }
    }

    // ---------- 内部 ----------

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "DShAndroid/" + C.VERSION);
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            return readAll(conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
