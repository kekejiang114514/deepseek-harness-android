package com.dsh.android.kernel;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * QQ 机器人 HTTP 接口：换 token、发消息。
 *
 * <p>只做官方 Bot API，不涉及任何个人号协议。所有请求都带超时，
 * 失败返回可读错误而不是抛到调用方炸掉服务。
 */
public final class QqApi {

    /**
     * 域名说明（踩过的坑）：
     *
     * <p>官方 Agent 接入用的 SDK 是 {@code @tencent-connect/qqbot-nodejs}，
     * 它的 baseUrl 与 tokenBaseUrl 都是 {@code https://api.bot.qq.com}。
     * 旧文档里的 {@code bots.qq.com} + {@code api.sgroup.qq.com} 能换到 token、
     * 也能建立 WS 并收到 READY，但**不会推送消息事件**——表现就是
     * 「连上了、也显示已就绪，但发消息没有任何反应」。
     */
    private static final String BASE = "https://api.bot.qq.com";
    private static final String TOKEN_URL = BASE + "/app/getAppAccessToken";
    private static final String API_BASE = BASE;

    /** 旧平台兜底：新域名不通时再试。 */
    private static final String TOKEN_URL_OLD = "https://bots.qq.com/app/getAppAccessToken";
    private static final String API_BASE_OLD = "https://api.sgroup.qq.com";

    private QqApi() {}

    /** 换 access token 的结果。 */
    public static final class Token {
        public String value = "";
        public long expiresAt = 0;
        public String error = "";

        public boolean ok() {
            return value.length() > 0;
        }
    }

    /**
     * 用 appId + clientSecret 换 access token。
     *
     * <p>官方返回的 expires_in 是秒（通常 7200），这里换算成绝对时间并
     * 提前 60 秒视为过期，避免边界上正好失效。
     */
    public static Token fetchToken(String appId, String secret) {
        Token t = new Token();
        if (appId == null || appId.length() == 0 || secret == null || secret.length() == 0) {
            t.error = "缺少 appId 或 secret";
            return t;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("appId", appId);
            body.put("clientSecret", secret);

            JSONObject resp;
            try {
                resp = postJson(TOKEN_URL, body.toString(), null);
            } catch (Throwable first) {
                resp = postJson(TOKEN_URL_OLD, body.toString(), null);
            }
            String token = resp.optString("access_token", "");
            if (token.length() == 0) {
                t.error = "换取 token 失败：" + resp.toString();
                return t;
            }
            long expiresIn = resp.optLong("expires_in", 7200);
            t.value = token;
            t.expiresAt = System.currentTimeMillis() + Math.max(60, expiresIn - 60) * 1000L;
        } catch (Throwable e) {
            t.error = "换取 token 失败：" + e.getMessage();
        }
        return t;
    }

    /**
     * 发送文本消息。
     *
     * @param kind  group / c2c
     * @param openid 群 openid 或用户 openid
     * @param passiveMsgId 被动回复的 msg_id；为空则发主动消息
     * @param seq 同一 msg_id 下的序号
     * @return 空串表示成功，否则是错误说明
     */
    public static String sendText(String token, String kind, String openid,
                                  String content, String passiveMsgId, int seq) {
        if (token == null || token.length() == 0) return "没有可用的 access token";
        if (openid == null || openid.length() == 0) return "缺少 openid";
        String path = "group".equals(kind)
                ? "/v2/groups/" + openid + "/messages"
                : "/v2/users/" + openid + "/messages";
        try {
            JSONObject resp = postJson(API_BASE + path,
                    QqProtocol.messageBody(content, passiveMsgId, seq), token);
            String id = resp.optString("id", "");
            String msg = resp.optString("message", "");
            if (id.length() > 0) return "";
            return msg.length() > 0 ? msg : resp.toString();
        } catch (Throwable e) {
            return e.getMessage() == null ? "发送失败" : e.getMessage();
        }
    }

    /** 取 WS 网关地址。 */
    public static String gateway(String token) {
        try {
            JSONObject resp = getJson(API_BASE + "/gateway", token);
            String url = resp.optString("url", "");
            if (url.length() > 0) return url;
        } catch (Throwable ignored) {}
        try {
            JSONObject resp = getJson(API_BASE_OLD + "/gateway", token);
            return resp.optString("url", "");
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static JSONObject postJson(String url, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (token != null && token.length() > 0) {
                c.setRequestProperty("Authorization", QqProtocol.authToken(token));
            }
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = readAll(in);
            if (code >= 400) throw new Exception("HTTP " + code + "：" + trim(text));
            return new JSONObject(text.length() == 0 ? "{}" : text);
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject getJson(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            if (token != null && token.length() > 0) {
                c.setRequestProperty("Authorization", QqProtocol.authToken(token));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = readAll(in);
            if (code >= 400) throw new Exception("HTTP " + code + "：" + trim(text));
            return new JSONObject(text.length() == 0 ? "{}" : text);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static String trim(String s) {
        return s == null ? "" : (s.length() <= 200 ? s : s.substring(0, 200) + "…");
    }
}
