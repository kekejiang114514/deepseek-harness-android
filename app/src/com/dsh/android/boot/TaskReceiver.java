package com.dsh.android.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.dsh.android.service.AutoTaskService;

/**
 * 闹钟到点。
 *
 * <p>只做一件事：把任务交给前台服务执行。真正的网络与工具调用都在服务里，
 * 广播本身必须立刻返回，否则会被系统判定超时。
 */
public class TaskReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String id = intent == null ? null : intent.getStringExtra("task_id");
        if (id == null || id.length() == 0) return;
        try {
            Intent svc = new Intent(ctx, AutoTaskService.class);
            svc.putExtra("task_id", id);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
        } catch (Throwable ignored) {}
    }
}
