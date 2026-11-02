package com.dsh.android.kernel;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 查询账户余额：GET {base}/user/balance。
 *
 * <p>与对话请求共用同一个 API Key，返回里可能有多币种，取第一条。
 */
public final class Balance {

    public static final class Info {
        public String amount = "";
        public String currency = "";
        public String error;
    }

    private Balance() {}

    /** 阻塞调用，放后台线程。 */
    public static Info fetch(Context c) {
        Info info = new Info();
        android.content.SharedPreferences sp =
                c.getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE);
        String base = sp.getString(C.KEY_BASE_URL, C.DEFAULT_BASE_URL);
        String key = sp.getString(C.KEY_API_KEY, "");
        if (key == null || key.length() == 0) {
            info.error = "未设置 API Key";
            return info;
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/user/balance").openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("User-Agent", "DShAndroid/" + C.VERSION);
            int code = conn.getResponseCode();
            if (code != 200) {
                info.error = "HTTP " + code;
                return info;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();

            JSONObject o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONArray arr = o.optJSONArray("balance_infos");
            if (arr != null && arr.length() > 0) {
                JSONObject first = arr.optJSONObject(0);
                if (first != null) {
                    info.amount = first.optString("total_balance", "");
                    info.currency = first.optString("currency", "");
                }
            }
            if (info.amount.length() == 0) info.error = "没有余额字段";
            return info;
        } catch (Exception e) {
            info.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return info;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
