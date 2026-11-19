package com.dsh.android.kernel;

import org.json.JSONObject;

/**
 * QQ 官方机器人协议的纯逻辑部分。
 *
 * <p>只做 JSON 的拼装与解析，不碰网络：这样握手、事件解析、去重这些
 * 容易出错的地方可以脱离设备测试。
 *
 * <p>依据官方 Bot API：WS 网关、op 码、intents 位、以及 v2 消息发送格式。
 */
public final class QqProtocol {

    /** 群聊与单聊消息事件。 */
    public static final int INTENT_GROUP_AND_C2C = 1 << 25;

    // ---- op 码 ----
    public static final int OP_DISPATCH = 0;
    public static final int OP_HEARTBEAT = 1;
    public static final int OP_IDENTIFY = 2;
    public static final int OP_RESUME = 6;
    public static final int OP_RECONNECT = 7;
    public static final int OP_INVALID_SESSION = 9;
    public static final int OP_HELLO = 10;
    public static final int OP_HEARTBEAT_ACK = 11;

    private QqProtocol() {}

    /** 群 @ 机器人的事件名。 */
    public static final String EVENT_GROUP_AT = "GROUP_AT_MESSAGE_CREATE";
    /** 单聊消息事件名。 */
    public static final String EVENT_C2C = "C2C_MESSAGE_CREATE";

    /** 网关鉴权串：官方要求 QQBot + 空格 + access_token。 */
    public static String authToken(String accessToken) {
        return "QQBot " + (accessToken == null ? "" : accessToken);
    }

    /** 构造 identify（op=2）。 */
    public static String identify(String accessToken, int intents) {
        try {
            JSONObject d = new JSONObject();
            d.put("token", authToken(accessToken));
            d.put("intents", intents);
            JSONObject props = new JSONObject();
            props.put("$os", "android");
            props.put("$browser", "dshandroid");
            props.put("$device", "phone");
            d.put("properties", props);

            JSONObject root = new JSONObject();
            root.put("op", OP_IDENTIFY);
            root.put("d", d);
            return root.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 构造心跳（op=1）；seq 为最近一次收到的事件序号。 */
    public static String heartbeat(Long seq) {
        JSONObject root = new JSONObject();
        try {
            root.put("op", OP_HEARTBEAT);
            root.put("d", seq == null ? JSONObject.NULL : seq);
        } catch (Throwable ignored) {}
        return root.toString();
    }

    /** 收到的一帧。 */
    public static final class Frame {
        public int op;
        public String type = "";      // dispatch 事件名
        public long seq = -1;
        public JSONObject d;
    }

    /** 解析服务端下发的帧；格式不对返回 null。 */
    public static Frame parseFrame(String raw) {
        if (raw == null || raw.trim().length() == 0) return null;
        try {
            JSONObject o = new JSONObject(raw);
            Frame f = new Frame();
            f.op = o.optInt("op", -1);
            f.type = o.optString("t", "");
            f.seq = o.optLong("s", -1);
            f.d = o.optJSONObject("d");
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一条收到的消息。 */
    public static final class Incoming {
        public String kind = "";      // group / c2c
        public String openid = "";    // 群 openid 或用户 openid
        public String msgId = "";     // 被动回复必须带上
        public String content = "";
        public String authorId = "";

        /** 用于去重：同一个 msg_id 只处理一次。 */
        public String dedupKey() {
            return kind + ":" + msgId;
        }
    }

    /**
     * 从 dispatch 帧里取出消息。
     *
     * <p>群消息的正文通常带一个前导空格（@ 之后），这里去掉；
     * 其他情况保持原文，避免误删用户有意输入的空格。
     */
    public static Incoming incoming(Frame f) {
        if (f == null || f.op != OP_DISPATCH || f.d == null) return null;
        if (!EVENT_GROUP_AT.equals(f.type) && !EVENT_C2C.equals(f.type)) return null;

        Incoming in = new Incoming();
        in.kind = EVENT_GROUP_AT.equals(f.type) ? "group" : "c2c";
        in.msgId = f.d.optString("id", "");
        in.content = cleanContent(f.d.optString("content", ""));
        JSONObject author = f.d.optJSONObject("author");
        in.authorId = author == null ? "" : author.optString("id", "");
        if (EVENT_GROUP_AT.equals(f.type)) {
            in.openid = f.d.optString("group_openid", "");
        } else {
            // 单聊回复走 /v2/users/{openid}，用的是 author.user_openid
            String uo = author == null ? "" : author.optString("user_openid", "");
            in.openid = uo.length() > 0 ? uo : in.authorId;
        }
        if (in.msgId.length() == 0) return null;
        return in;
    }

    /**
     * 清理群消息正文。
     *
     * <p>群里 @ 机器人时，正文可能带 {@code <@!123456>} 这样的提及标记，
     * 也可能只是残留一个前导空格。标记不清掉会让模型不知道要回答什么。
     */
    public static String cleanContent(String content) {
        if (content == null) return "";
        String s = content.replaceAll("<@!?[0-9]+>", " ");
        while (s.startsWith(" ")) s = s.substring(1);
        return s.trim().length() == 0 ? "" : s;
    }

    /**
     * 构造发送消息的请求体。
     *
     * @param passiveMsgId 被动回复要带的 msg_id；为空表示主动消息
     * @param seq          同一 msg_id 下的第几条回复，官方要求递增
     */
    public static String messageBody(String content, String passiveMsgId, int seq) {
        try {
            JSONObject o = new JSONObject();
            o.put("content", content == null ? "" : content);
            o.put("msg_type", 0);
            if (passiveMsgId != null && passiveMsgId.length() > 0) {
                o.put("msg_id", passiveMsgId);
                o.put("msg_seq", Math.max(1, seq));
            }
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }
}
