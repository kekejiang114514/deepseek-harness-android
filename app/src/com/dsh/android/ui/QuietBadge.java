package com.dsh.android.ui;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 执行自动化动作时显示的小提示。
 *
 * <p>用 {@code TYPE_ACCESSIBILITY_OVERLAY}：无障碍服务不需要悬浮窗权限
 * 就能显示它。关键是加 {@code FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE}——
 * 提示只是提示，不能抢走焦点，也不能吃掉用户的手势，否则自动化过程中
 * 用户点到的是这层提示而不是真实界面。
 */
public final class QuietBadge {

    private static View view;
    private static WindowManager wm;
    private static Runnable hideTask;

    private QuietBadge() {}

    /** 显示一条短提示，1.2 秒后自动消失。 */
    public static void show(AccessibilityService svc, String text) {
        if (svc == null || text == null) return;
        try {
            if (wm == null) wm = (WindowManager) svc.getSystemService(AccessibilityService.WINDOW_SERVICE);
            if (wm == null) return;

            if (view == null) {
                TextView t = new TextView(svc);
                t.setTextSize(12);
                t.setTextColor(0xFFFFFFFF);
                t.setBackground(Theme.round(0xCC000000, Theme.R_LG, svc));
                t.setPadding(dp(svc, 14), dp(svc, 8), dp(svc, 14), dp(svc, 8));
                view = t;

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = dp(svc, 96);
                wm.addView(view, lp);
            }

            ((TextView) view).setText(text);
            view.setVisibility(View.VISIBLE);

            if (hideTask != null) view.removeCallbacks(hideTask);
            hideTask = new Runnable() {
                public void run() {
                    if (view != null) view.setVisibility(View.GONE);
                }
            };
            view.postDelayed(hideTask, 1200);
        } catch (Throwable ignored) {}
    }

    /** 服务销毁时清理，避免 WindowManager 泄漏窗口。 */
    public static void release() {
        try {
            if (wm != null && view != null) wm.removeView(view);
        } catch (Throwable ignored) {}
        view = null;
        wm = null;
    }

    private static int dp(AccessibilityService svc, int v) {
        return Math.round(v * svc.getResources().getDisplayMetrics().density);
    }
}
