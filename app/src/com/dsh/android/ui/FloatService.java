package com.dsh.android.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dsh.android.kernel.RunStatus;

/**
 * 悬浮窗：只在「有任务在跑」且「应用不在前台」时出现。
 *
 * <p>形态和 Minis 一致——一个贴着屏幕边缘的小胶囊，左边图标、右边一行字说明现在在干什么，
 * 可以直接拖到任意位置，点一下回到应用。没有任务时它不该存在：一个静止的气泡
 * 只是挡视线。
 *
 * <p>它同时是个前台服务：通知栏那条低优先级通知顺带把进程吊住，
 * 这也是无障碍服务不会跟着进程一起被回收的关键。
 */
public final class FloatService extends Service {

    private static final String CHANNEL = "dsh_bg";
    private static final int NOTIF_ID = 4711;
    public static final String ACTION_STOP = "com.dsh.android.FLOAT_STOP";

    private static volatile boolean running = false;
    private static volatile boolean wantBubble = true;

    private WindowManager wm;
    private View capsule;
    private TextView label;
    private IconView icon;
    private IconView action;
    private WindowManager.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 应用自己的界面在几层前台；>0 时不显示气泡。 */
    private final java.util.concurrent.atomic.AtomicInteger started =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private android.app.Application.ActivityLifecycleCallbacks lifecycle;

    /** 用户长按气泡暂时收起，下一次任务开始时恢复。 */
    private boolean mutedForNow = false;

    private final RunStatus.Listener statusListener = new RunStatus.Listener() {
        public void onStatusChanged() {
            ui.post(new Runnable() {
                public void run() { refresh(); }
            });
        }
    };

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        Theme.init(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startForegroundSafe();
        RunStatus.addListener(statusListener);
        watchAppVisible();
        if (wantBubble && canOverlay(this)) {
            addCapsule();
        }
        running = true;
        refresh();
        ui.postDelayed(ticker, 1000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // 已经在跑又被 start 一次：按新的意愿增删气泡
        if (wantBubble && capsule == null && canOverlay(this)) {
            addCapsule();
            refresh();
        } else if (!wantBubble && capsule != null) {
            removeCapsule();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        RunStatus.removeListener(statusListener);
        ui.removeCallbacksAndMessages(null);
        if (lifecycle != null) {
            try {
                getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
            } catch (Throwable ignored) {}
            lifecycle = null;
        }
        removeCapsule();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------ 对外开关

    public static boolean isRunning() { return running; }

    public static boolean canOverlay(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void start(Context c) { start(c, true); }

    /**
     * @param bubble true 显示气泡；false 只保留常驻通知，用来在后台吊住进程
     */
    public static void start(Context c, boolean bubble) {
        wantBubble = bubble;
        Intent i = new Intent(c, FloatService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            // Android 12 起后台启动前台服务会被拒：应用在前台时不会走到这里
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, FloatService.class));
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 通知

    private void startForegroundSafe() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "后台运行",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }

            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

            Intent stop = new Intent(this, FloatService.class).setAction(ACTION_STOP);
            PendingIntent sp = PendingIntent.getService(this, 1, stop,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            b.setContentTitle("DShAndroid 正在后台运行")
                    .setContentText(wantBubble ? "悬浮窗显示任务状态，点此回到应用" : "保持无障碍服务连接")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .addAction(new Notification.Action.Builder(null, "关闭", sp).build());
            startForeground(NOTIF_ID, b.build());
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 前台判定

    private void watchAppVisible() {
        try {
            android.app.Application app = getApplication();
            if (app == null || lifecycle != null) return;
            lifecycle = new android.app.Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
                public void onActivityStarted(android.app.Activity a) {
                    started.incrementAndGet();
                    refresh();
                }
                public void onActivityResumed(android.app.Activity a) {}
                public void onActivityPaused(android.app.Activity a) {}
                public void onActivityStopped(android.app.Activity a) {
                    // 页面之间切换会一闪而过地 stop，稍等一下再决定要不要露出来
                    if (started.decrementAndGet() <= 0) {
                        started.set(0);
                        ui.postDelayed(new Runnable() {
                            public void run() { refresh(); }
                        }, 320);
                    }
                }
                public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
                public void onActivityDestroyed(android.app.Activity a) {}
            };
            app.registerActivityLifecycleCallbacks(lifecycle);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 气泡

    private void addCapsule() {
        if (capsule != null || wm == null) return;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), dp(8), dp(14), dp(8));
        box.setBackground(Theme.round(Theme.surface2(), 22f, this));
        box.setElevation(dp(6));

        icon = new IconView(this, IconView.THINKING, 14);
        icon.setColor(Theme.text2());
        box.addView(icon, new LinearLayout.LayoutParams(dp(14), dp(14)));

        label = new TextView(this);
        label.setText("思考中");
        label.setTextSize(12);
        label.setTextColor(Theme.text());
        label.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(7);
        box.addView(label, tlp);

        // 右侧小按钮：直接暂停/继续，不用回到应用里
        action = new IconView(this, IconView.PAUSE, 13);
        action.setColor(Theme.text2());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(13), dp(13));
        alp.leftMargin = dp(10);
        box.addView(action, alp);
        action.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                RunStatus.Control c = RunStatus.control();
                if (c == null) {
                    android.widget.Toast.makeText(FloatService.this, "请先打开应用",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (RunStatus.PAUSED.equals(RunStatus.phase())) c.resume();
                else c.pause();
            }
        });

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = displayHeight() / 3;

        // 按钮自己处理点击，别让胶囊的触摸逻辑把它吃掉
        box.setClickable(false);
        box.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // 落在右侧按钮上就交给按钮自己处理
                        if (action != null && e.getX() >= action.getLeft() - dp(6)) return false;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            lp.x = startX + dx;
                            lp.y = startY + dy;
                            try { wm.updateViewLayout(capsule, lp); } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) snapToEdge();
                        else openApp();
                        return true;
                }
                return false;
            }
        });
        box.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                // 长按收起本次，任务再开始时自己回来
                mutedForNow = true;
                refresh();
                android.widget.Toast.makeText(FloatService.this, "已收起，下次任务开始时出现",
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        capsule = box;
        try {
            wm.addView(capsule, lp);
        } catch (Throwable t) {
            capsule = null;
        }
    }

    private void removeCapsule() {
        if (capsule == null || wm == null) return;
        try { wm.removeView(capsule); } catch (Throwable ignored) {}
        capsule = null;
    }

    /** 松手后贴回最近的一侧，别停在屏幕中间挡内容。 */
    private void snapToEdge() {
        if (capsule == null) return;
        int screenW = displayWidth();
        int mineW = capsule.getWidth();
        lp.x = (lp.x + mineW / 2) < screenW / 2 ? dp(12) : screenW - mineW - dp(12);
        if (lp.y < dp(24)) lp.y = dp(24);
        int maxY = displayHeight() - capsule.getHeight() - dp(24);
        if (lp.y > maxY) lp.y = maxY;
        try { wm.updateViewLayout(capsule, lp); } catch (Throwable ignored) {}
    }

    private void openApp() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 刷新

    /** 每秒刷一次耗时；只在有任务时跑。 */
    private final Runnable ticker = new Runnable() {
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    private void refresh() {
        String phase = RunStatus.phase();
        boolean busy = !RunStatus.IDLE.equals(phase);

        // 没有任务就把气泡收掉——静止的气泡只是挡视线
        if (!busy || mutedForNow) {
            setVisible(false);
            if (RunStatus.IDLE.equals(phase)) mutedForNow = false;
            return;
        }
        // 应用在前台时也不显示：在自己界面里看自己的状态没意义
        boolean foreground = started.get() > 0;
        setVisible(wantBubble && !foreground && canOverlay(this));

        if (label == null || icon == null) return;
        if (RunStatus.PAUSED.equals(phase)) {
            icon.setType(IconView.PAUSE);
            label.setText("已暂停");
            if (action != null) action.setType(IconView.PLAY);
        } else if (RunStatus.TOOL.equals(phase)) {
            icon.setType(IconView.WRENCH);
            label.setText(shortTool(RunStatus.detail()) + " · " + elapsed());
            if (action != null) action.setType(IconView.PAUSE);
        } else {
            icon.setType(IconView.THINKING);
            label.setText("思考中 · " + elapsed());
            if (action != null) action.setType(IconView.PAUSE);
        }
    }

    private void setVisible(boolean visible) {
        if (capsule == null) return;
        int want = visible ? View.VISIBLE : View.GONE;
        if (capsule.getVisibility() != want) capsule.setVisibility(want);
    }

    private String elapsed() {
        long t = RunStatus.startedAt();
        if (t <= 0) return "0s";
        long s = (System.currentTimeMillis() - t) / 1000;
        return s < 60 ? s + "s" : (s / 60) + "m" + (s % 60) + "s";
    }

    /** 工具名太长会把胶囊撑满屏幕，取最后一段并且截断。 */
    private static String shortTool(String name) {
        if (name == null || name.length() == 0) return "工具";
        // 标签已经是人话了，只做长度裁剪；没有标签的裸工具名才取尾段
        if (name.indexOf(' ') > 0) {
            return name.length() > 20 ? name.substring(0, 20) + "…" : name;
        }
        String n = name;
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) n = n.substring(dot + 1);
        if (n.length() > 12) n = n.substring(0, 12) + "…";
        return n;
    }

    // ------------------------------------------------------------ 尺寸

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @SuppressWarnings("deprecation")
    private int displayWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    @SuppressWarnings("deprecation")
    private int displayHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }
}
