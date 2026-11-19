package com.dsh.android.kernel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * 从非前台场景安全地拉起界面。
 *
 * <p>Android 10 起，后台应用被限制启动 Activity；14 上更严格，违规会抛
 * {@code BackgroundActivityStartDenied}。工具调用（app.open）与安装器
 * 都可能在没有界面的时刻触发，所以统一走这里：
 *
 * <ol>
 *   <li>带上 NEW_TASK 直接尝试；</li>
 *   <li>失败或被拒时退回通知，由用户点开——这也是系统推荐的做法。</li>
 * </ol>
 */
public final class SafeStart {

    private static final String CHANNEL = "dsh_open";
    private static final int BASE_ID = 4200;

    private SafeStart() {}

    /**
     * @param what 通知标题里要说明「打开了什么」
     * @return true 表示已直接拉起；false 表示退回通知
     */
    public static boolean startOrNotify(Context ctx, Intent intent, String what) {
        if (ctx == null || intent == null) return false;
        try {
            Intent i = new Intent(intent);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable denied) {
            return notifyInstead(ctx, intent, what);
        }
    }

    /** 起不来时，用一条通知把入口留给用户。 */
    private static boolean notifyInstead(Context ctx, Intent intent, String what) {
        try {
            Intent i = new Intent(intent);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, BASE_ID, i, flags);

            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "打开应用",
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification n = new Notification.Builder(ctx)
                    .setContentTitle("需要手动打开")
                    .setContentText(what == null ? "后台无法直接打开，点这里继续" : what)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(BASE_ID, n);
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
