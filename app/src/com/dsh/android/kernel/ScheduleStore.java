package com.dsh.android.kernel;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 定时任务的存储与闹钟登记。
 *
 * <p>用 {@code setAndAllowWhileIdle} 而不是精确闹钟：Android 12 起精确闹钟
 * 需要单独授权，而「每天早上汇总」这类任务差几分钟无妨，不值得为它多要一个
 * 权限。代价是系统可能晚几分钟触发。
 */
public final class ScheduleStore {

    private static final String SP = "dsh_schedules";
    private static final String KEY = "tasks";

    private final Context ctx;
    private final SharedPreferences sp;

    public ScheduleStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public Schedules load() {
        return Schedules.fromJson(sp.getString(KEY, ""));
    }

    public void save(Schedules s) {
        sp.edit().putString(KEY, s.toJson().toString()).apply();
    }

    /** 重新登记全部启用的任务（开机、改配置后调用）。 */
    public void rescheduleAll() {
        Schedules s = load();
        for (int i = 0; i < s.items().size(); i++) {
            Schedules.Task t = s.items().get(i);
            cancel(t.id);
            if (!t.enabled) continue;
            long at = Schedules.nextTrigger(t, System.currentTimeMillis());
            if (at <= 0) continue;
            arm(t.id, at);
        }
    }

    /** 登记一条任务的下一次触发。 */
    public void arm(String taskId, long triggerAt) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending(taskId));
        } catch (Throwable ignored) {}
    }

    public void cancel(String taskId) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending(taskId));
        } catch (Throwable ignored) {}
    }

    private PendingIntent pending(String taskId) {
        Intent i = new Intent("com.dsh.android.TASK_TICK");
        i.setClassName(ctx, "com.dsh.android.boot.TaskReceiver");
        i.putExtra("task_id", taskId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, taskId.hashCode(), i, flags);
    }
}
