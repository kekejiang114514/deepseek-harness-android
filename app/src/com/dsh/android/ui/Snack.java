package com.dsh.android.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 轻提示，替代系统 Toast。
 *
 * <p>系统的 Toast 样式由 ROM 决定（圆角、字号、位置都不受控），
 * 而且和深色主题对不上。这里做成贴在内容区底部的一小块圆角气泡，
 * 淡入 → 停留 → 淡出，不占焦点、不拦点击。
 */
public final class Snack {

    private Snack() {}

    public static void show(Context ctx, String text) {
        show(ctx, text, 1900);
    }

    public static void show(Context ctx, String text, int durationMs) {
        if (text == null || text.length() == 0) return;

        Activity act = null;
        if (ctx instanceof Activity) {
            act = (Activity) ctx;
        } else if (ctx != null && ctx instanceof android.content.ContextWrapper) {
            Context base = ctx;
            for (int i = 0; i < 5 && base instanceof android.content.ContextWrapper; i++) {
                if (base instanceof Activity) {
                    act = (Activity) base;
                    break;
                }
                base = ((android.content.ContextWrapper) base).getBaseContext();
            }
        }
        if (act == null || act.isFinishing()) {
            // 没有 Activity 时退回系统提示，总比什么都不显示好
            Snack.show(ctx, text);
            return;
        }

        final Activity a = act;
        View root = a.findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup)) {
            Snack.show(ctx, text);
            return;
        }
        final ViewGroup host = (ViewGroup) root;

        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Theme.isDark() ? 0xFF101114 : 0xFFF7F8FA);
        t.setTypeface(null, Typeface.NORMAL);
        t.setPadding(Theme.dp(a, 16), Theme.dp(a, 10), Theme.dp(a, 16), Theme.dp(a, 10));
        int bg = Theme.isDark() ? 0xFF2C2C2E : 0xFF33363B;
        t.setBackground(Theme.round(bg, 20, a));
        t.setMaxLines(4);
        t.setElevation(Theme.dp(a, 6));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Theme.dp(a, 72);
        lp.leftMargin = Theme.dp(a, 24);
        lp.rightMargin = Theme.dp(a, 24);

        t.setAlpha(0f);
        t.setTranslationY(Theme.dp(a, 8));
        host.addView(t, lp);
        t.animate().alpha(1f).translationY(0).setDuration(160).start();

        final TextView view = t;
        t.postDelayed(new Runnable() {
            public void run() {
                view.animate().alpha(0f).translationY(Theme.dp(a, 8)).setDuration(180)
                        .withEndAction(new Runnable() {
                            public void run() {
                                try {
                                    host.removeView(view);
                                } catch (Throwable ignored) {}
                            }
                        }).start();
            }
        }, Math.max(600, durationMs));
    }
}
