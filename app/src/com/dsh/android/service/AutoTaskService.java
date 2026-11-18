package com.dsh.android.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.dsh.android.kernel.MemoryStore;
import com.dsh.android.kernel.ScheduleStore;
import com.dsh.android.kernel.Schedules;
import com.dsh.android.kernel.SecureStore;
import com.dsh.android.kernel.SessionStore;
import com.dsh.android.kernel.C;
import com.dsh.android.llm.AgentLoop;
import com.dsh.android.llm.LlmClient;
import com.dsh.android.tools.ToolRuntime;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 后台执行一条定时任务。
 *
 * <p>没有界面，所以用一个只收集文本的 Sink；结果写成一个新会话，
 * 并推一条通知说明这次跑了什么。
 */
public class AutoTaskService extends Service {

    private static final String CHANNEL = "dsh_auto_task";
    private static final int NOTI_ID = 4100;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String taskId = intent == null ? null : intent.getStringExtra("task_id");
        startForegroundCompat();
        if (taskId == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    execute(taskId);
                } catch (Throwable ignored) {
                } finally {
                    stopSelf();
                }
            }
        }, "auto-task").start();
        return START_NOT_STICKY;
    }

    /** 前台服务：Android 14 要求声明类型，这里用 specialUse。 */
    private void startForegroundCompat() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "定时任务",
                        NotificationManager.IMPORTANCE_LOW);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Notification n = new Notification.Builder(this)
                    .setContentTitle("定时任务执行中")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .build();
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                // 0x40000000 = FOREGROUND_SERVICE_TYPE_SPECIAL_USE；
                // 精简 android.jar 里没有这个常量，直接写字面值
                startForeground(NOTI_ID, n, 0x40000000);
            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, n, 0);
            } else {
                startForeground(NOTI_ID, n);
            }
        } catch (Throwable ignored) {}
    }

    private void execute(String taskId) throws Exception {
        ScheduleStore store = new ScheduleStore(this);
        Schedules all = store.load();
        Schedules.Task task = all.byId(taskId);
        if (task == null || !task.enabled) return;

        String base = getSharedPreferences(C.SP_NAME, MODE_PRIVATE).getString(C.KEY_BASE_URL, C.DEFAULT_BASE_URL);
        String key = SecureStore.readKey(getSharedPreferences(C.SP_NAME, MODE_PRIVATE));
        String model = getSharedPreferences(C.SP_NAME, MODE_PRIVATE).getString(C.KEY_MODEL, C.MODEL_CHAT);
        if (key.length() == 0) {
            notifyResult("定时任务未执行：还没有配置 API Key", task.prompt);
            return;
        }

        final StringBuilder out = new StringBuilder();
        LlmClient client = new LlmClient(this, base, key, model, 8192);
        ToolRuntime tools = new ToolRuntime(this);
        AgentLoop loop = new AgentLoop(client, tools, systemPrompt());
        JSONArray history = new JSONArray();
        loop.run(history, task.prompt, new AgentLoop.Sink() {
            public void onDelta(String d) { out.append(d); }
            public void onToolCall(String name, String args) {}
            public void onToolResult(String name, String result) {}
            public void onError(String msg) { out.append("\n[错误] ").append(msg); }
        });

        // 存成一个会话，便于事后翻看
        SessionStore sessions = new SessionStore(this);
        sessions.importSession("定时任务 · " + task.timeText(), "定时任务", history);

        task.lastRun = System.currentTimeMillis();
        store.save(all);
        store.rescheduleAll();      // 每天/工作日任务登记下一次

        notifyResult("定时任务已完成：" + truncate(task.prompt, 24), out.toString());
    }

    private String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个在手机后台自动执行定时任务的助手。简洁地完成任务并给出结果。\n\n");
        String mem = new MemoryStore(getFilesDir()).read();
        if (mem.trim().length() > 0) sb.append("长期记忆：\n").append(mem.trim()).append("\n");
        return sb.toString();
    }

    private void notifyResult(String title, String body) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "定时任务",
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification n = new Notification.Builder(this)
                    .setContentTitle(title)
                    .setContentText(truncate(body, 80))
                    .setStyle(new Notification.BigTextStyle().bigText(truncate(body, 800)))
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTI_ID + 1, n);
        } catch (Throwable ignored) {}
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
