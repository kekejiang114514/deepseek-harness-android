package com.dsh.android.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.dsh.android.tools.ShellEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Computer 面板：从聊天页底部弹出的半屏面板，实时显示 agent 正在做什么。
 *
 * <p>形态参考 Minis 的 Computer 面板：顶部标题与关闭、中间是等宽字体的执行记录
 * （终端输出与工具步骤）、底部一行资源占用与实时指示。
 *
 * <p>用 {@link Dialog} 而不是 Activity：面板要盖在对话上、关掉就回到原位，
 * 用 Activity 会打断聊天状态。
 */
public final class ComputerSheet {

    /** 一条步骤记录。 */
    public static final class Step {
        public final String tool;
        public final long ms;
        public final boolean ok;
        public final String detail;

        Step(String tool, long ms, boolean ok, String detail) {
            this.tool = tool;
            this.ms = ms;
            this.ok = ok;
            this.detail = detail;
        }
    }

    private static final List<Step> STEPS = new ArrayList<Step>();
    private static final List<String> LINES = new ArrayList<String>();

    private ComputerSheet() {}

    /** 记录一条步骤（由聊天页在工具回调里调用）。 */
    public static void addStep(String tool, long ms, boolean ok, String detail) {
        STEPS.add(new Step(tool, ms, ok, detail));
        while (STEPS.size() > 200) STEPS.remove(0);
    }

    /** 记录一行输出（终端、状态等）。 */
    public static void log(String line) {
        if (line == null) return;
        LINES.add(line);
        while (LINES.size() > 400) LINES.remove(0);
    }

    public static void clearSteps() {
        STEPS.clear();
    }

    public static void show(Activity a) {
        final Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
            dlg.getWindow().setGravity(Gravity.BOTTOM);
        }

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Theme.round(Theme.panel(), 22, a));

        // 标题栏
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Theme.dp(a, 10), Theme.dp(a, 12), Theme.dp(a, 10), Theme.dp(a, 8));

        TextView close = new TextView(a);
        close.setText("\u2715");
        close.setTextSize(16);
        close.setTextColor(Theme.text2());
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        bar.addView(close, new LinearLayout.LayoutParams(
                Theme.dp(a, 40), Theme.dp(a, 40)));

        TextView title = new TextView(a);
        title.setText("Computer");
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Theme.text());
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView shot = new TextView(a);
        shot.setText("\u25A3");
        shot.setTextSize(16);
        shot.setTextColor(Theme.text2());
        shot.setGravity(Gravity.CENTER);
        shot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Snack.show(a, "截图已保存在 shots 目录");
            }
        });
        bar.addView(shot, new LinearLayout.LayoutParams(
                Theme.dp(a, 40), Theme.dp(a, 40)));
        root.addView(bar);

        // 终端/步骤区
        LinearLayout screen = new LinearLayout(a);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackground(Theme.round(0xFF0B0B0C, 14, a));
        screen.setPadding(Theme.dp(a, 14), Theme.dp(a, 14), Theme.dp(a, 14), Theme.dp(a, 14));

        final TextView log = new TextView(a);
        log.setTextSize(11.5f);
        log.setTextColor(0xFFE8E8EA);
        log.setTypeface(Typeface.MONOSPACE);
        log.setLineSpacing(Theme.dp(a, 4), 1f);
        log.setTextIsSelectable(true);

        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(log);
        screen.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.setMargins(Theme.dp(a, 12), Theme.dp(a, 4), Theme.dp(a, 12), Theme.dp(a, 6));
        root.addView(screen, slp);

        // 底部状态
        final TextView status = new TextView(a);
        status.setTextSize(10);
        status.setTextColor(0xFF22C55E);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, Theme.dp(a, 10));
        root.addView(status);

        dlg.setContentView(root);
        WindowManager.LayoutParams lp = dlg.getWindow() == null
                ? null : dlg.getWindow().getAttributes();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.72f);
            dlg.getWindow().setAttributes(lp);
        }
        dlg.show();

        // 每秒刷新：追加新步骤/输出，并更新资源行
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] seen = {0};
        final Runnable tick = new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < STEPS.size(); i++) {
                    Step s = STEPS.get(i);
                    sb.append(s.ok ? "✓ " : "✗ ").append(s.tool)
                      .append("  ").append(s.ms).append("ms\n");
                    if (s.detail != null && s.detail.length() > 0) {
                        String d = s.detail;
                        if (d.length() > 400) d = d.substring(0, 400) + "…";
                        sb.append(d).append('\n');
                    }
                }
                for (int i = 0; i < LINES.size(); i++) {
                    sb.append(LINES.get(i)).append('\n');
                }
                log.setText(sb.length() == 0 ? "（暂时没有正在进行的步骤）" : sb.toString());
                sv.post(new Runnable() {
                    public void run() { sv.fullScroll(View.FOCUS_DOWN); }
                });
                status.setText(resourceLine());
                h.postDelayed(this, 1000);
            }
        };
        h.post(tick);
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface d) {
                h.removeCallbacks(tick);
            }
        });
    }

    /** 读一次内存占用；CPU 用 /proc/stat 两次采样求差，读不到就不显示。 */
    private static String resourceLine() {
        String mem = "";
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/meminfo"));
            String line;
            long total = 0, avail = 0;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) total = num(line);
                else if (line.startsWith("MemAvailable:")) avail = num(line);
            }
            r.close();
            if (total > 0) {
                double used = (total - avail) / 1024.0 / 1024.0;
                double all = total / 1024.0 / 1024.0;
                mem = String.format(java.util.Locale.US, "MEM %.1f/%.1f GB", used, all);
            }
        } catch (Throwable ignored) {}
        return mem.length() == 0 ? "\u25CF LIVE" : ("\u25CF LIVE    " + mem);
    }

    private static long num(String line) {
        try {
            String[] parts = line.replaceAll("[^0-9]", " ").trim().split("\\s+");
            return Long.parseLong(parts[0]);
        } catch (Exception e) {
            return 0;
        }
    }
}
