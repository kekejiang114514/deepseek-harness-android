package com.dsh.android.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.dsh.android.kernel.C;
import com.dsh.android.kernel.QqApi;
import com.dsh.android.kernel.QqConfig;
import com.dsh.android.kernel.QqProtocol;
import com.dsh.android.kernel.QqSocket;
import com.dsh.android.kernel.SecureStore;
import com.dsh.android.kernel.SessionStore;
import com.dsh.android.llm.AgentLoop;
import com.dsh.android.llm.LlmClient;
import com.dsh.android.tools.ToolRuntime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * QQ 机器人常驻服务。
 *
 * <p>官方只提供 WebSocket 长连接（手机无公网回调），所以这里维持一条连接：
 * 收到 HELLO 开始心跳、发 identify、收到消息交给 Agent 跑一轮，再用
 * 被动回复（带 msg_id）把结果发回去。
 *
 * <p>被动回复有官方限制：5 分钟内、同一 msg_id 最多 5 条。所以先回
 * 「处理中」，结果作为第二条。
 */
public class QqBotService extends Service {

    private static final String CHANNEL = "dsh_qq_bot";
    private static final int NOTI_ID = 4300;

    /** 已处理过的消息 id，避免重连后重复回复。 */
    private final Set<String> seen = new LinkedHashSet<String>();
    private QqSocket socket;
    private volatile boolean running = false;
    private String token = "";
    private long tokenExpireAt = 0;
    private volatile Long seq = null;
    /** 当前正在跑的循环，供看门狗取消。 */
    private volatile AgentLoop currentLoop;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!startForegroundCompat()) return START_NOT_STICKY;
        status("服务已启动，正在连接…");
        if (!running) {
            running = true;
            new Thread(new Runnable() {
                public void run() { loop(); }
            }, "qq-bot").start();
        }
        return START_STICKY;
    }

    /**
     * 建立前台通知。
     *
     * <p>必须成功：startForegroundService 之后如果没在 5 秒内调用
     * startForeground，系统会直接杀掉进程——用户看到的就是「点启用，
     * 应用重启了一遍」。所以这里逐级降级，绝不静默失败。
     */
    private boolean startForegroundCompat() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "QQ 机器人",
                        NotificationManager.IMPORTANCE_LOW));
            }
        } catch (Throwable ignored) {}

        Notification n = buildNotification();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTI_ID, n, 0x40000000);   // specialUse
            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, n, 0);
            } else {
                startForeground(NOTI_ID, n);
            }
            return true;
        } catch (Throwable e) {
            QqConfig.setStatus(this, "前台服务启动失败：" + e.getMessage());
            try {
                // 再退一步：不声明类型
                startForeground(NOTI_ID, n);
                return true;
            } catch (Throwable e2) {
                // 彻底不行就自己退出，避免被系统判定超时后杀进程
                stopSelf();
                return false;
            }
        }
    }

    /** 通知构建单独兜底：任何一个 API 出问题都退回最简通知。 */
    private Notification buildNotification() {
        try {
            return new Notification.Builder(this)
                    .setContentTitle("QQ 机器人运行中")
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setOngoing(true)
                    .build();
        } catch (Throwable e) {
            return new Notification();
        }
    }

    /** 主循环：连接 → 断线退避重连。 */
    private void loop() {
        int backoff = 5;
        while (running) {
            try {
                if (!ensureToken()) {
                    status("获取 token 失败，稍后重试");
                    sleep(15000);
                    continue;
                }
                String gw = QqApi.gateway(token);
                if (gw.length() == 0) {
                    status("获取网关失败");
                    sleep(15000);
                    continue;
                }

                final QqSocket s = new QqSocket();
                socket = s;
                s.connect(gw, new QqSocket.Listener() {
                    public void onOpen() { status("已握手，等待 READY…"); }

                    public void onText(String text) {
                        try {
                            handleFrame(s, text);
                        } catch (Throwable ignored) {}
                    }

                    public void onClosed(String reason) { status(reason); }
                });
                backoff = 5;
                while (running && s.isOpen()) sleep(1000);
            } catch (Throwable e) {
                status("连接失败：" + (e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage()));
            }
            if (socket != null && socket.lastError().length() > 0) {
                status("连接异常：" + socket.lastError());
            }
            closeSocket();
            if (!running) break;
            sleep(backoff * 1000);
            backoff = Math.min(60, backoff * 2);
        }
    }

    private void handleFrame(QqSocket s, String text) {
        QqProtocol.Frame f = QqProtocol.parseFrame(text);
        if (f == null) {
            QqConfig.log(this, "无法解析的帧：" + text);
            return;
        }
        // 只记录关键事件，避免日志被心跳刷满
        if (f.op != QqProtocol.OP_HEARTBEAT_ACK) {
            QqConfig.log(this, "op=" + f.op + (f.type.length() > 0 ? " t=" + f.type : ""));
        }
        if (f.seq > 0) seq = Long.valueOf(f.seq);

        switch (f.op) {
            case QqProtocol.OP_HELLO: {
                int interval = f.d == null ? 30000 : f.d.optInt("heartbeat_interval", 30000);
                try {
                    s.send(QqProtocol.identify(token, QqProtocol.FULL_INTENTS));
                } catch (Throwable ignored) {}
                startHeartbeat(s, interval);
                return;
            }
            case QqProtocol.OP_RECONNECT:
            case QqProtocol.OP_INVALID_SESSION:
                closeSocket();
                return;
            case QqProtocol.OP_DISPATCH:
                if ("READY".equals(f.type)) {
                    QqConfig.log(this, "READY");
                    startEventProbe();
                    status("已就绪（连接建立，等待消息）");
                    return;
                }
                onDispatch(s, f);
                return;
            default:
                return;
        }
    }

    private void startHeartbeat(final QqSocket s, final int intervalMs) {
        new Thread(new Runnable() {
            public void run() {
                while (running && s.isOpen()) {
                    try {
                        s.send(QqProtocol.heartbeat(seq));
                    } catch (Throwable ignored) {}
                    sleep(Math.max(5000, intervalMs));
                }
            }
        }, "qq-heartbeat").start();
    }

    private void onDispatch(final QqSocket s, QqProtocol.Frame f) {
        final QqProtocol.Incoming in = QqProtocol.incoming(f);
        if (in == null) {
            // 事件类型认识但解析不出消息，说明字段结构和预期不同，必须留痕
            if (QqProtocol.EVENT_GROUP_AT.equals(f.type) || QqProtocol.EVENT_C2C.equals(f.type)) {
                QqConfig.log(this, "事件无法解析：" + f.type + " d=" + (f.d == null ? "null" : f.d.toString()));
            }
            return;
        }
        QqConfig.log(this, "收到 " + in.kind + " " + in.openid + "：" + shorten(in.content));
        if (!seen.contains(in.dedupKey())) {
            seen.add(in.dedupKey());
            if (seen.size() > 500) seen.clear();      // 避免无限增长
            // 记下来源，方便用户把它们加入名单
            QqConfig.noteSource(this, in.kind, in.openid);
            if (!QqConfig.allowed(this, in.kind, in.openid)) {
                status("已忽略一条消息（不在允许范围）：" + in.kind + " " + in.openid);
                return;
            }
            int limit = QqConfig.dailyLimit(this);
            if (limit > 0 && QqConfig.todayCount(this) >= limit) {
                status("已达到今日回复上限 " + limit + " 条");
                return;
            }
            QqConfig.bumpToday(this);
            if (in.content.trim().length() == 0) return;

            // 先回「处理中」，因为被动回复窗口只有 5 分钟
            reply(in, "收到，正在处理…", 1);
            final boolean[] done = {false};
            // 看门狗：先安抚一句，再硬性中止，避免用户那边一直「正在输入」
            startWatchdog(in, done, 45, 180);
            new Thread(new Runnable() {
                public void run() {
                    String answer;
                    try {
                        answer = runAgent(in.content);
                    } catch (Throwable e) {
                        answer = "处理时出错：" + e.getMessage();
                    }
                    done[0] = true;
                    if (answer == null || answer.trim().length() == 0) {
                        answer = "（没有生成内容）";
                    }
                    reply(in, answer, 2);
                }
            }, "qq-reply").start();
        }
    }

    /** 跑一轮 Agent，返回文本结果；超过时限会中止并说明。 */
    private String runAgent(String prompt) {
        final StringBuilder out = new StringBuilder();
        QqConfig.log(this, "开始生成…");
        try {
            String base = getSharedPreferences(C.SP_NAME, MODE_PRIVATE)
                    .getString(C.KEY_BASE_URL, C.DEFAULT_BASE_URL);
            String key = SecureStore.readKey(getSharedPreferences(C.SP_NAME, MODE_PRIVATE));
            String model = getSharedPreferences(C.SP_NAME, MODE_PRIVATE)
                    .getString(C.KEY_MODEL, C.MODEL_CHAT);
            if (key.length() == 0) return "本机还没有配置 API Key。";

            LlmClient client = new LlmClient(this, base, key, model, 8192);
            ToolRuntime tools = new ToolRuntime(this);
            AgentLoop loop = new AgentLoop(client, tools,
                    "你是通过 QQ 与用户对话的助手。回答简洁，不要使用 Markdown 表格。");
            currentLoop = loop;
            JSONArray history = new JSONArray();
            loop.run(history, prompt, new AgentLoop.Sink() {
                public void onDelta(String d) { out.append(d); }
                public void onToolCall(String name, String args) {}
                public void onToolResult(String name, String result) {}
                public void onError(String msg) { out.append("[错误] ").append(msg); }
            });

            QqConfig.log(this, "生成完成，长度 " + out.length());

            SessionStore sessions = new SessionStore(this);
            sessions.importSession("QQ · " + prompt.substring(0, Math.min(12, prompt.length())),
                    "QQ 机器人", history);
        } catch (Throwable e) {
            QqConfig.log(this, "生成异常：" + e.getMessage());
            if (out.length() == 0) return "处理失败：" + e.getMessage();
        }
        String text = out.toString().trim();
        if (text.length() == 0) text = "（没有产生回复）";
        return text.length() > 1500 ? text.substring(0, 1500) + "…" : text;
    }

    /** 发送回复；无论成败都写日志，避免「界面没反应」却查不到原因。 */
    private void reply(QqProtocol.Incoming in, String text, int seqNo) {
        try {
            if (token.length() == 0) {
                QqConfig.log(this, "回复失败：没有 token");
                return;
            }
            String err = QqApi.sendText(token, in.kind, in.openid, text, in.msgId, seqNo);
            QqConfig.log(this, "回复#" + seqNo + (err.length() == 0 ? " 成功" : " 失败：" + err));
            if (err.length() > 0) status("发送失败：" + err);
        } catch (Throwable e) {
            QqConfig.log(this, "回复异常：" + e.getMessage());
        }
    }

    /**
     * 生成看门狗。
     *
     * @param remindAfter 多少秒后先回一句「还在处理」
     * @param abortAfter  多少秒后强制取消生成并告知结果
     */
    private void startWatchdog(final QqProtocol.Incoming in, final boolean[] done,
                               final int remindAfter, final int abortAfter) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(remindAfter * 1000L);
                    if (!done[0]) {
                        reply(in, "还在处理，稍等一下…", 3);
                        QqConfig.log(QqBotService.this, "已发送进度提醒");
                    }
                    long left = (abortAfter - remindAfter) * 1000L;
                    if (left > 0) Thread.sleep(left);
                    if (!done[0]) {
                        AgentLoop l = currentLoop;
                        if (l != null) l.cancel();
                        QqConfig.log(QqBotService.this, "生成超时，已取消");
                    }
                } catch (Throwable ignored) {}
            }
        }, "qq-watchdog").start();
    }

    /** READY 之后 90 秒内有没有收到任何消息事件。 */
    private volatile long readyAt = 0;
    private volatile int dispatchCount = 0;

    private void startEventProbe() {
        readyAt = System.currentTimeMillis();
        dispatchCount = 0;
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(90000);
                } catch (Throwable ignored) {}
                if (dispatchCount == 0 && running) {
                    status("已连接，但 90 秒内没有收到任何消息事件："
                            + "请在 q.qq.com 检查「沙箱测试成员」是否包含你自己的 QQ，"
                            + "以及机器人的私聊/群消息事件订阅是否已开通");
                    QqConfig.log(QqBotService.this, "事件自检：90 秒零事件");
                }
            }
        }, "qq-probe").start();
    }

    private static String shorten(String s) {
        if (s == null) return "";
        String t = s.replace("\n", " ");
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private boolean ensureToken() {
        if (token.length() > 0 && System.currentTimeMillis() < tokenExpireAt) return true;
        QqApi.Token t = QqApi.fetchToken(QqConfig.appId(this), QqConfig.secret(this));
        if (!t.ok()) {
            status(t.error);
            return false;
        }
        token = t.value;
        tokenExpireAt = t.expiresAt;
        return true;
    }

    private void status(String s) {
        QqConfig.setStatus(this, s);
    }

    private void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (Throwable ignored) {}
        socket = null;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        running = false;
        closeSocket();
        status("已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
