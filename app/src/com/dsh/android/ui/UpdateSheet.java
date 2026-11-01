package com.dsh.android.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.android.kernel.ApkInstaller;
import com.dsh.android.kernel.C;
import com.dsh.android.kernel.Updater;

/**
 * 「发现新版本」卡片。设置页与主界面的启动检查共用同一套 UI。
 *
 * <p>结构：标题 → 版本变化（旧 → 新）→ 安装包大小 → 更新说明（可滚动）→
 * 进度条与百分比（下载时才出现）→ 稍后 / 下载并安装。
 *
 * <p>下载走 {@link ApkInstaller}，完成后交给系统安装器；失败会在原位置显示原因，
 * 按钮变成「重试」，不弹第二个框。
 */
public final class UpdateSheet {

    private UpdateSheet() {}

    public static void show(final Activity act, final Updater.Release r) {
        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
        }

        int pad = dp(act, 20);
        LinearLayout sheet = new LinearLayout(act);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(Theme.round(Theme.panel(), 22, act));
        sheet.setPadding(pad, pad, pad, dp(act, 14));

        TextView title = new TextView(act);
        title.setText("发现新版本");
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Theme.text());
        sheet.addView(title);

        // 版本变化
        LinearLayout verRow = new LinearLayout(act);
        verRow.setOrientation(LinearLayout.HORIZONTAL);
        verRow.setGravity(Gravity.CENTER_VERTICAL);
        verRow.setPadding(0, dp(act, 8), 0, dp(act, 2));
        verRow.addView(chip(act, C.VERSION, false));
        TextView arrow = new TextView(act);
        arrow.setText("\u2192");
        arrow.setTextSize(14);
        arrow.setTextColor(Theme.caption());
        arrow.setPadding(dp(act, 8), 0, dp(act, 8), 0);
        verRow.addView(arrow);
        verRow.addView(chip(act, r.version, true));
        sheet.addView(verRow);

        TextView size = new TextView(act);
        size.setText(r.apkSize > 0 ? ("安装包 " + r.sizeText()) : "未附带安装包");
        size.setTextSize(11);
        size.setTextColor(Theme.caption());
        size.setPadding(0, dp(act, 2), 0, dp(act, 10));
        sheet.addView(size);

        // 更新说明
        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 0) {
            TextView body = new TextView(act);
            body.setText(notes);
            body.setTextSize(12.5f);
            body.setTextColor(Theme.text2());
            body.setLineSpacing(dp(act, 3), 1f);
            body.setPadding(dp(act, 2), dp(act, 4), dp(act, 2), dp(act, 10));

            ScrollView sv = new ScrollView(act);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(body);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            lp.setMargins(0, dp(act, 4), 0, dp(act, 10));
            sheet.addView(sv, lp);
        }

        // 进度
        final ThinProgress bar = new ThinProgress(act);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(act, 4), 0, dp(act, 6));
        sheet.addView(bar, blp);

        final TextView progressText = new TextView(act);
        progressText.setTextSize(11);
        progressText.setTextColor(Theme.caption());
        progressText.setVisibility(View.GONE);
        sheet.addView(progressText);

        // 按钮
        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(act, 10), 0, 0);

        TextView later = new TextView(act);
        later.setText("稍后");
        later.setTextSize(14);
        later.setTextColor(Theme.text2());
        later.setPadding(dp(act, 16), dp(act, 10), dp(act, 16), dp(act, 10));
        later.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        btns.addView(later);

        final TextView action = new TextView(act);
        action.setText(r.hasApk() ? "下载并安装" : "打开发布页");
        action.setTextSize(14);
        action.setTextColor(Theme.onPrimary());
        action.setPadding(dp(act, 20), dp(act, 10), dp(act, 20), dp(act, 10));
        action.setBackground(Theme.round(Theme.primary(), 20, act));
        action.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!r.hasApk()) {
                    open(act, r.pageUrl);
                    dlg.dismiss();
                    return;
                }
                action.setEnabled(false);
                action.setText("下载中…");
                bar.setVisibility(View.VISIBLE);
                progressText.setVisibility(View.VISIBLE);
                startDownload(act, r, dlg, bar, progressText, action);
            }
        });
        btns.addView(action);
        sheet.addView(btns);

        dlg.setContentView(sheet);
        WindowManager.LayoutParams lp = dlg.getWindow() == null ? null : dlg.getWindow().getAttributes();
        if (lp != null) {
            lp.width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.92f);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            dlg.getWindow().setAttributes(lp);
        }
        dlg.show();
    }

    private static void startDownload(final Activity act, final Updater.Release r,
                                      final Dialog dlg, final ThinProgress bar,
                                      final TextView progressText, final TextView action) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    final java.io.File apk = ApkInstaller.download(act, r.apkUrl,
                            new ApkInstaller.Progress() {
                                public void onProgress(final long done, final long total) {
                                    act.runOnUiThread(new Runnable() {
                                        public void run() {
                                            int pct = (int) (done * 100 / Math.max(1, total));
                                            bar.setProgress(pct / 100f);
                                            progressText.setText(pct + "%　　"
                                                    + (done / 1024) + " KB / " + (total / 1024) + " KB");
                                        }
                                    });
                                }
                            });
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            dlg.dismiss();
                            confirmInstall(act, apk);
                        }
                    });
                } catch (final Throwable t) {
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            action.setEnabled(true);
                            action.setText("重试");
                            bar.setVisibility(View.GONE);
                            progressText.setVisibility(View.VISIBLE);
                            progressText.setText("下载失败：" + (t.getMessage() == null
                                    ? t.getClass().getSimpleName() : t.getMessage()));
                        }
                    });
                }
            }
        }, "apk-download").start();
    }

    private static void confirmInstall(final Activity act, final java.io.File apk) {
        long mb = Math.max(1, apk.length() / 1048576);
        new android.app.AlertDialog.Builder(act)
                .setTitle("下载完成")
                .setMessage("安装包已下载（约 " + mb + "MB）。点「立即安装」后由系统完成安装，"
                        + "设置与对话都会保留。")
                .setPositiveButton("立即安装", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ApkInstaller.install(act, apk, new ApkInstaller.Callback() {
                            public void onDone(final String err) {
                                if (err != null) {
                                    Toast.makeText(act, "无法启动安装：" + err,
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private static TextView chip(Context c, String text, boolean highlight) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(highlight ? Theme.link() : Theme.caption());
        t.setBackground(Theme.round(highlight ? Theme.primarySoft() : Theme.surface2(), 8, c));
        t.setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4));
        return t;
    }

    private static void open(Context c, String url) {
        if (url == null || url.length() == 0) return;
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(c, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private static int dp(Context c, float v) {
        return Theme.dp(c, v);
    }
}
