package com.dsh.android.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.dsh.android.kernel.ScheduleStore;

/** 开机与更新后重新登记闹钟：Android 重启会清掉所有 AlarmManager 闹钟。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            new ScheduleStore(ctx).rescheduleAll();
        } catch (Throwable ignored) {}
    }
}
