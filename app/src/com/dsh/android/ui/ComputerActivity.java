package com.dsh.android.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dsh.android.tools.BrowserEngine;

import java.io.File;

/**
 * 「电脑」面板：把内置浏览器的渲染画面实时显示出来。
 *
 * <p>类似 Minis 的 Computer 视图——AI 在浏览什么，这里就能看到什么。
 * 画面来自 WebView 的绘制结果（每秒抓一帧），不是录屏，因此不需要任何系统权限。
 *
 * <p>底部横向滚动的是历史截图（{@code browser.screenshot} 或手动截图产生），点开看大图。
 */
public final class ComputerActivity extends Activity {

    private static final int PREVIEW_W = 720;
    private static final int PREVIEW_H = 1280;
    private static final long FRAME_MS = 1000;

    private ImageView screen;
    private TextView status;
    private LinearLayout shots;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean live = true;

    private final Runnable frame = new Runnable() {
        public void run() {
            if (!live) return;
            refreshFrame();
            ui.postDelayed(this, FRAME_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.app.ActionBar ab = getActionBar();
        if (ab != null) ab.hide();
        Theme.init(this);
        getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(Theme.bg()));
        setContentView(build());
        ui.post(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        live = true;
        ui.removeCallbacks(frame);
        ui.post(frame);
        refreshShots();
    }

    @Override
    protected void onPause() {
        live = false;
        ui.removeCallbacks(frame);
        super.onPause();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg());
        root.addView(Ui.topBar(this, "电脑"));

        // 画面
        FrameLayout frameBox = new FrameLayout(this);
        frameBox.setBackground(Theme.round(Theme.surface2(), 12, this));
        screen = new ImageView(this);
        screen.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screen.setPadding(Theme.dp(this, 4), Theme.dp(this, 4),
                Theme.dp(this, 4), Theme.dp(this, 4));
        frameBox.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView empty = new TextView(this);
        empty.setText("还没有画面\n让 AI 打开一个网页，或点下方「打开浏览器」");
        empty.setTextSize(12);
        empty.setTextColor(Theme.caption());
        empty.setGravity(Gravity.CENTER);
        frameBox.addView(empty);

        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        flp.setMargins(Theme.dp(this, 12), Theme.dp(this, 6),
                Theme.dp(this, 12), Theme.dp(this, 6));
        root.addView(frameBox, flp);

        status = new TextView(this);
        status.setTextSize(11);
        status.setTextColor(Theme.caption());
        status.setPadding(Theme.dp(this, 16), 0, Theme.dp(this, 16), Theme.dp(this, 6));
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        root.addView(status);

        // 操作
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(Theme.dp(this, 12), 0, Theme.dp(this, 12), Theme.dp(this, 8));

        actions.addView(btn("打开浏览器", new View.OnClickListener() {
            public void onClick(View v) {
                BrowserActivity.open(ComputerActivity.this, BrowserEngine.currentUrl());
            }
        }));
        actions.addView(btn("手动截图", new View.OnClickListener() {
            public void onClick(View v) {
                grabShot();
            }
        }));
        root.addView(actions);

        // 截图条
        TextView label = new TextView(this);
        label.setText("截图");
        label.setTextSize(11);
        label.setTextColor(Theme.caption());
        label.setPadding(Theme.dp(this, 16), Theme.dp(this, 4), Theme.dp(this, 16), Theme.dp(this, 4));
        root.addView(label);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        shots = new LinearLayout(this);
        shots.setOrientation(LinearLayout.HORIZONTAL);
        shots.setPadding(Theme.dp(this, 12), 0, Theme.dp(this, 12), Theme.dp(this, 14));
        hs.addView(shots);
        root.addView(hs);
        return root;
    }

    private TextView btn(String text, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Theme.text2());
        t.setGravity(Gravity.CENTER);
        t.setPadding(Theme.dp(this, 14), Theme.dp(this, 9),
                Theme.dp(this, 14), Theme.dp(this, 9));
        t.setBackground(Theme.round(Theme.surface2(), 10, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, Theme.dp(this, 8), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(click);
        return t;
    }

    /** 抓一帧并显示。 */
    private void refreshFrame() {
        final Bitmap bmp = BrowserEngine.capture(PREVIEW_W, PREVIEW_H);
        runOnUiThread(new Runnable() {
            public void run() {
                if (bmp != null) screen.setImageBitmap(bmp);
                String url = BrowserEngine.currentUrl();
                status.setText(url.length() > 0 ? url : "尚未访问任何页面");
            }
        });
    }

    /** 手动截一帧存成 PNG，然后刷新截图条。 */
    private void grabShot() {
        final Bitmap bmp = BrowserEngine.capture(1080, 1920);
        if (bmp == null) {
            Snack.show(this, "还没有画面可以截");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    File dir = new File(getFilesDir(), "shots");
                    if (!dir.exists()) dir.mkdirs();
                    final File f = new File(dir, "shot-" + System.currentTimeMillis() + ".png");
                    java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, out);
                    out.flush();
                    out.close();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Snack.show(ComputerActivity.this, "已保存 " + f.getName());
                            refreshShots();
                        }
                    });
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() { Snack.show(ComputerActivity.this, "保存失败"); }
                    });
                }
            }
        }, "shot").start();
    }

    /** 列出 shots/ 下最近的截图。 */
    private void refreshShots() {
        if (shots == null) return;
        shots.removeAllViews();
        File dir = new File(getFilesDir(), "shots");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            TextView t = new TextView(this);
            t.setText("还没有截图");
            t.setTextSize(11);
            t.setTextColor(Theme.caption());
            shots.addView(t);
            return;
        }
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        int shown = 0;
        for (int i = 0; i < files.length && shown < 12; i++) {
            final File f = files[i];
            if (!f.getName().endsWith(".png")) continue;
            ImageView iv = new ImageView(this);
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp == null) continue;
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Theme.round(Theme.surface2(), 8, this));
            int w = Theme.dp(this, 72);
            int h = Theme.dp(this, 128);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMargins(0, 0, Theme.dp(this, 8), 0);
            iv.setLayoutParams(lp);
            iv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showFull(f); }
            });
            shots.addView(iv);
            shown++;
        }
        if (shown == 0) {
            TextView t = new TextView(this);
            t.setText("还没有截图");
            t.setTextSize(11);
            t.setTextColor(Theme.caption());
            shots.addView(t);
        }
    }

    /** 点缩略图看大图。 */
    private void showFull(File f) {
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (bmp == null) return;
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(iv);
        new AppDialog.Builder(this)
                .setTitle(f.getName())
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    public static void open(Context c) {
        try {
            c.startActivity(new Intent(c, ComputerActivity.class));
        } catch (Throwable ignored) {}
    }
}
