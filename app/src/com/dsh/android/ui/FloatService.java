package com.dsh.android.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dsh.android.kernel.RunStatus;

import java.util.List;

/**
 * 悬浮窗：应用退到后台后仍能看到 Agent 在做什么。
 *
 * <p>做成前台服务有两个用处：一是悬浮窗本身需要一个活着的上下文；二是常驻通知能让
 * 进程不那么容易被系统回收，避免「划掉应用之后无障碍开关跟着掉」。
 *
 * <p>交互照 Minis 来：小的圆形气泡，拖动可换位置、松手自动贴边，点一下展开面板，
 * 面板里是当前状态和最近几次工具调用。气泡在运行中会显示一个跳动的点。
 */
public final class FloatService extends Service {

    private static final String CHANNEL = "dsh_bg";
    private static final int NOTIF_ID = 4711;

    public static final String ACTION_STOP = "com.dsh.android.float.STOP";

    private static volatile boolean running = false;

    private WindowManager wm;
    private View bubble;
    private View panel;
    private WindowManager.LayoutParams bubbleLp;
    private WindowManager.LayoutParams panelLp;

    private TextView panelState;
    private TextView panelTools;
    private TextView bubbleBadge;

    private boolean expanded = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final RunStatus.Listener statusListener = new RunStatus.Listener() {
        public void onStatusChanged() {
            ui.post(new Runnable() {
                public void run() { refresh(); }
            });
        }
    };

    // ------------------------------------------------------------ 生命周期

    public static boolean isRunning() { return running; }

    /** 需要 SYSTEM_ALERT_WINDOW；低版本系统没有这个限制。 */
    public static boolean canOverlay(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(c);
    }

    /** true 时挂气泡；false 只保留常驻通知，用来在后台吊住进程。 */
    private static volatile boolean wantBubble = true;

    public static void start(Context c) {
        start(c, true);
    }

    public static void start(Context c, boolean bubble) {
        wantBubble = bubble;
        Intent i = new Intent(c, FloatService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, FloatService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Theme.init(this);
        startForegroundSafe();
        if (wantBubble && canOverlay(this)) addBubble();
        RunStatus.addListener(statusListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // 服务已经在跑时又被 start 一次（比如从「仅通知」切到「带气泡」）：按新的意愿增删气泡
        if (wantBubble && bubble == null && canOverlay(this)) {
            addBubble();
        } else if (!wantBubble && bubble != null) {
            closePanel();
            try { wm.removeView(bubble); } catch (Throwable ignored) {}
            bubble = null;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        RunStatus.removeListener(statusListener);
        try { if (panel != null) wm.removeView(panel); } catch (Throwable ignored) {}
        try { if (bubble != null) wm.removeView(bubble); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    // ------------------------------------------------------------ 通知

    private void startForegroundSafe() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "后台运行",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent close = PendingIntent.getService(this, 1,
                new Intent(this, FloatService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("DShAndroid 正在后台运行")
                .setContentText(wantBubble ? "悬浮窗显示 Agent 状态，点此回到应用"
                        : "保持无障碍服务连接，点此回到应用")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(open);
        b.addAction(new Notification.Action.Builder(null, "关闭悬浮窗", close).build());
        try {
            startForeground(NOTIF_ID, b.build());
        } catch (Throwable t) {
            // 通知发不出去也不能让悬浮窗起不来
        }
    }

    // ------------------------------------------------------------ 气泡

    private void addBubble() {
        FrameLayout box = new FrameLayout(this);
        // 圆形底：主色偏暗一点，叠在别的应用上不刺眼
        box.setBackground(Theme.round(0xFF2B2D31, 26f, this));

        IconView icon = new IconView(this, IconView.THINKING, 20);
        icon.setColor(0xFFF2F3F5);
        box.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));

        bubbleBadge = new TextView(this);
        bubbleBadge.setTextSize(9);
        bubbleBadge.setTextColor(0xFFF2F3F5);
        bubbleBadge.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM | Gravity.END);
        blp.setMargins(0, 0, dp(2), dp(2));
        bubbleBadge.setBackground(Theme.round(Theme.primary(), 9f, this));
        bubbleBadge.setVisibility(View.GONE);
        box.addView(bubbleBadge, blp);

        bubble = box;

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        bubbleLp = new WindowManager.LayoutParams(dp(52), dp(52), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = Math.max(0, screenW() - dp(66));
        bubbleLp.y = screenH() / 3;

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = bubbleLp.x;
                        startY = bubbleLp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        bubbleLp.x = startX + dx;
                        bubbleLp.y = startY + dy;
                        try { wm.updateViewLayout(bubble, bubbleLp); } catch (Throwable ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            snapToEdge();
                        } else {
                            togglePanel();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        try { wm.addView(bubble, bubbleLp); } catch (Throwable ignored) {}
        refresh();
    }

    /** 松手后贴到左右最近的一边，别停在屏幕中间挡内容。 */
    private void snapToEdge() {
        int w = screenW();
        int margin = dp(10);
        int left = margin;
        int right = w - dp(52) - margin;
        int target = (bubbleLp.x + dp(26)) < w / 2 ? left : right;
        final int from = bubbleLp.x;
        final int to = target;
        // 简单位移动画：几帧滑过去
        for (int i = 1; i <= 6; i++) {
            final float t = i / 6f;
            ui.postDelayed(new Runnable() {
                public void run() {
                    bubbleLp.x = (int) (from + (to - from) * t);
                    try { wm.updateViewLayout(bubble, bubbleLp); } catch (Throwable ignored) {}
                    if (expanded) positionPanel();
                }
            }, i * 16L);
        }
    }

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ 面板

    private void togglePanel() {
        if (expanded) closePanel();
        else openPanel();
    }

    private void openPanel() {
        if (panel != null) return;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(12));
        col.setBackground(Theme.round(0xFF232529, 16f, this));

        panelState = new TextView(this);
        panelState.setTextSize(13);
        panelState.setTextColor(0xFFF2F3F5);
        col.addView(panelState);

        panelTools = new TextView(this);
        panelTools.setTextSize(11);
        panelTools.setTextColor(0xFFA9ADB4);
        panelTools.setLineSpacing(dp(3), 1f);
        panelTools.setPadding(0, dp(8), 0, dp(8));
        col.addView(panelTools);

        TextView open = new TextView(this);
        open.setText("打开应用");
        open.setTextSize(12);
        open.setTextColor(Theme.primary());
        open.setPadding(0, dp(6), 0, dp(2));
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(FloatService.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                closePanel();
            }
        });
        col.addView(open);

        panel = col;

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        panelLp = new WindowManager.LayoutParams(dp(230),
                WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        positionPanel();
        try { wm.addView(panel, panelLp); } catch (Throwable ignored) {}
        expanded = true;
        refresh();
    }

    private void positionPanel() {
        if (panelLp == null) return;
        boolean onRight = bubbleLp.x > screenW() / 2;
        int w = screenW();
        panelLp.x = onRight ? Math.max(dp(8), w - dp(230) - dp(10)) : dp(10);
        panelLp.y = Math.max(dp(8), bubbleLp.y + dp(60));
        if (panel != null) {
            try { wm.updateViewLayout(panel, panelLp); } catch (Throwable ignored) {}
        }
    }

    private void closePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (Throwable ignored) {}
        }
        panel = null;
        panelState = null;
        panelTools = null;
        expanded = false;
    }

    // ------------------------------------------------------------ 呼吸动画

    private android.animation.ValueAnimator pulse;

    /** 运行中气泡轻微呼吸，静止时保持不闪——和 Minis 那个点了会动的球一个意思。 */
    private void setPulsing(boolean on) {
        if (bubble == null) return;
        if (on && pulse == null) {
            pulse = android.animation.ValueAnimator.ofFloat(1f, 0.72f, 1f);
            pulse.setDuration(1500);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    if (bubble != null) bubble.setAlpha((Float) a.getAnimatedValue());
                }
            });
            pulse.start();
        } else if (!on && pulse != null) {
            pulse.cancel();
            pulse = null;
            bubble.setAlpha(1f);
        }
    }

    // ------------------------------------------------------------ 状态刷新

    private void refresh() {
        String phase = RunStatus.phase();
        String label;
        if (RunStatus.THINKING.equals(phase)) label = "思考中";
        else if (RunStatus.TOOL.equals(phase)) label = "工具 · " + RunStatus.detail();
        else if (RunStatus.PAUSED.equals(phase)) label = "已暂停";
        else label = "空闲";

        boolean busy = !RunStatus.IDLE.equals(phase) && !RunStatus.PAUSED.equals(phase);
        setPulsing(busy);
        if (bubbleBadge != null) {
            if (busy && RunStatus.toolCount() > 0) {
                bubbleBadge.setText(String.valueOf(RunStatus.toolCount()));
                bubbleBadge.setVisibility(View.VISIBLE);
            } else {
                bubbleBadge.setVisibility(View.GONE);
            }
        }

        if (panelState != null) {
            String when = "";
            if (RunStatus.startedAt() > 0 && !RunStatus.IDLE.equals(phase)) {
                long sec = Math.max(0, (System.currentTimeMillis() - RunStatus.startedAt()) / 1000);
                when = " · " + sec + "s";
            }
            panelState.setText(label + when);
        }

        if (panelTools != null) {
            List<String> recent = RunStatus.recent();
            if (recent.isEmpty()) {
                panelTools.setText("还没有调用工具");
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < recent.size(); i++) {
                    if (i > 0) sb.append('\n');
                    sb.append("· ").append(recent.get(i));
                }
                panelTools.setText(sb.toString());
            }
        }
    }
}
