package com.dsh.android.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 电脑面板：按步骤展示 agent 正在做什么。
 *
 * <p>每一步是工具调用；如果是 shell 显示实时终端输出，如果是 browser 显示实时渲染画面。
 * 面板从聊天页底部弹出，关掉不影响对话。
 */
public final class ComputerSheet {

    /** 一步。 */
    public static final class Step {
        public String name = "";
        public String args = "";
        public String output = "";
        public long ms;
        public boolean ok = true;
        public boolean running = true;
        public boolean browser;
    }

    private static final List<Step> STEPS = new ArrayList<Step>();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static Dialog dialog;
    private static LinearLayout list;
    private static Activity host;

    private ComputerSheet() {}

    // ---------- 记录 ----------

    /** 新开一步，返回可后续填充的对象。 */
    public static Step begin(String name, String args, boolean browser) {
        Step s = new Step();
        s.name = name;
        s.args = args == null ? "" : args;
        s.browser = browser;
        STEPS.add(s);
        final Activity a = host;
        if (a != null) a.runOnUiThread(new Runnable() {
            public void run() { refresh(); }
        });
        return s;
    }

    public static void finish(Step s, boolean ok, String output, long ms) {
        if (s == null) return;
        s.ok = ok;
        s.running = false;
        s.output = output == null ? "" : output;
        s.ms = ms;
        final Activity a = host;
        if (a != null) a.runOnUiThread(new Runnable() {
            public void run() { refresh(); }
        });
    }

    public static void clear() {
        STEPS.clear();
    }

    // ---------- 界面 ----------

    public static void show(final Activity a) {
        host = a;
        dialog = new Dialog(a);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setGravity(Gravity.BOTTOM);
        }

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Theme.round(Theme.panel(), 20, a));

        // 标题栏
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 8));

        TextView close = new TextView(a);
        close.setText("\u2715");
        close.setTextSize(15);
        close.setTextColor(Theme.text2());
        close.setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 6));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); }
        });
        bar.addView(close);

        TextView title = new TextView(a);
        title.setText("Computer");
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text());
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView live = new TextView(a);
        live.setText("\u25CF Live");
        live.setTextSize(11);
        live.setTextColor(0xFF22C55E);
        bar.addView(live);
        root.addView(bar);

        // 底部资源：CPU 与内存（只读 /proc，无需权限）
        final TextView res = new TextView(a);
        res.setTextSize(11);
        res.setTextColor(0xFF22C55E);
        res.setTypeface(Typeface.MONOSPACE);
        res.setGravity(Gravity.CENTER);
        res.setPadding(0, dp(a, 6), 0, dp(a, 10));
        root.addView(res);
        final Runnable resTick = new Runnable() {
            public void run() {
                if (host == null) return;
                new Thread(new Runnable() {
                    public void run() {
                        final String txt = com.dsh.android.tools.ResMonitor.sample();
                        if (txt.length() == 0) return;
                        a.runOnUiThread(new Runnable() {
                            public void run() { res.setText(txt); }
                        });
                    }
                }, "res-monitor").start();
                UI.postDelayed(this, 2500);
            }
        };
        UI.postDelayed(resTick, 400);

        ScrollView sv = new ScrollView(a);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(a, 12), 0, dp(a, 12), dp(a, 16));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(root);
        WindowManager.LayoutParams lp = dialog.getWindow() == null
                ? null : dialog.getWindow().getAttributes();
        if (lp != null) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.78f);
            dialog.getWindow().setAttributes(lp);
        }
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface d) {
                host = null;
                dialog = null;
            }
        });
        dialog.show();
        refresh();
    }

    /** 重建步骤列表（步骤数量有限，整体重建比增量维护简单可靠）。 */
    private static void refresh() {
        if (list == null || host == null) return;
        list.removeAllViews();
        if (STEPS.isEmpty()) {
            TextView empty = new TextView(host);
            empty.setText("还没有操作。让助手跑一次工具就会出现在这里。");
            empty.setTextSize(12);
            empty.setTextColor(Theme.caption());
            empty.setPadding(0, dp(host, 20), 0, 0);
            list.addView(empty);
            return;
        }
        for (int i = 0; i < STEPS.size(); i++) {
            list.addView(stepView(host, STEPS.get(i), i == STEPS.size() - 1));
        }
    }

    private static View stepView(final Activity a, final Step s, boolean latest) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
        box.setBackground(Theme.roundOutlined(Theme.surface(), 14, Theme.border(), a));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(a, 4), 0, dp(a, 4));
        box.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = new TextView(a);
        dot.setText(s.running ? "\u25CF" : (s.ok ? "\u2713" : "\u2715"));
        dot.setTextSize(12);
        dot.setTextColor(s.running ? Theme.primary() : (s.ok ? 0xFF22C55E : Theme.danger()));
        dot.setPadding(0, 0, dp(a, 8), 0);
        head.addView(dot);

        TextView name = new TextView(a);
        name.setText(s.name + (s.args.length() > 0 ? "  " + s.args : ""));
        name.setTextSize(12);
        name.setTextColor(Theme.text2());
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView time = new TextView(a);
        time.setText(s.running ? "运行中" : (s.ms + "ms"));
        time.setTextSize(11);
        time.setTextColor(Theme.caption());
        head.addView(time);
        box.addView(head);

        // 浏览器步骤：实时画面
        if (s.browser) {
            final ImageView shot = new ImageView(a);
            shot.setAdjustViewBounds(true);
            shot.setScaleType(ImageView.ScaleType.FIT_CENTER);
            shot.setBackgroundColor(0xFF000000);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 220));
            slp.setMargins(0, dp(a, 8), 0, 0);
            box.addView(shot, slp);
            if (latest) startFrames(a, shot);
            return box;
        }

        // 其它步骤：输出文本
        if (s.output.length() > 0) {
            TextView out = new TextView(a);
            String t = s.output.trim();
            if (t.length() > 1500) t = t.substring(0, 1500) + "\n…";
            out.setText(t);
            out.setTextSize(11);
            out.setTypeface(Typeface.MONOSPACE);
            out.setTextColor(0xFF9BE59B);
            out.setBackground(Theme.round(0xFF101312, 10, a));
            out.setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 8));
            out.setTextIsSelectable(true);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            olp.setMargins(0, dp(a, 8), 0, 0);
            box.addView(out, olp);
        }
        return box;
    }

    /** 浏览器步骤每秒抓一帧，形成"实时"感。 */
    private static void startFrames(final Activity a, final ImageView target) {
        final Runnable[] tick = new Runnable[1];
        tick[0] = new Runnable() {
            public void run() {
                if (host == null || target.getParent() == null) return;
                new Thread(new Runnable() {
                    public void run() {
                        final android.graphics.Bitmap bmp =
                                com.dsh.android.tools.BrowserEngine.capture(720, 1280);
                        if (bmp == null) return;
                        a.runOnUiThread(new Runnable() {
                            public void run() {
                                if (target.getParent() != null) target.setImageBitmap(bmp);
                            }
                        });
                    }
                }, "computer-frame").start();
                UI.postDelayed(tick[0], 1200);
            }
        };
        UI.postDelayed(tick[0], 300);
    }

    private static int dp(Context c, float v) { return Theme.dp(c, v); }
}
