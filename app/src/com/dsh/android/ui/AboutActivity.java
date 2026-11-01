package com.dsh.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.android.kernel.About;
import com.dsh.android.kernel.C;
import com.dsh.android.kernel.Updater;

/**
 * 关于软件：版本、更新、开源协议这些。
 * 作者信息单独一页（{@link AuthorActivity}），两边互不干扰。
 */
public final class AboutActivity extends Activity {

    private TextView updateStatus;
    private boolean checking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.app.ActionBar ab = getActionBar();
        if (ab != null) ab.hide();
        Theme.init(this);
        setContentView(build());
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg());
        root.addView(Ui.topBar(this, "关于软件"));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(40));

        body.addView(Ui.sectionLabel(this, "版本"));
        LinearLayout verCard = Ui.card(this);
        verCard.addView(Ui.line(this, "当前版本", C.VERSION));
        verCard.addView(Ui.divider(this));
        verCard.addView(Ui.line(this, "包名", getPackageName()));
        verCard.addView(Ui.divider(this));
        verCard.addView(Ui.line(this, "构建方式", "纯手工工具链（javac + d8 + 自写 AXML/ARSC）"));
        body.addView(verCard);

        body.addView(Ui.sectionLabel(this, "更新"));
        LinearLayout upCard = Ui.card(this);

        updateStatus = Ui.line(this, "状态", Updater.lastCheckedText(this));
        upCard.addView(updateStatus);
        upCard.addView(Ui.divider(this));
        upCard.addView(Ui.action(this, "检查更新", new View.OnClickListener() {
            public void onClick(View v) { checkUpdate(); }
        }));
        upCard.addView(Ui.divider(this));
        upCard.addView(Ui.switchRow(this, "自动检查更新", "每次打开应用时查一次，每天最多一次",
                Updater.autoEnabled(this), new Ui.SwitchCallback() {
                    public void onChanged(boolean checked) {
                        Updater.setAutoEnabled(AboutActivity.this, checked);
                    }
                }));
        body.addView(upCard);

        body.addView(Ui.sectionLabel(this, "开源"));
        LinearLayout osCard = Ui.card(this);
        osCard.addView(Ui.hint(this, "本项目以 MIT 协议开源，源码与构建脚本都在仓库里，"
                + "可以自己跑一遍 build.sh 再签名。"));
        osCard.addView(Ui.divider(this));
        osCard.addView(Ui.action(this, "查看源码仓库", new View.OnClickListener() {
            public void onClick(View v) { open(About.REPO); }
        }));
        osCard.addView(Ui.divider(this));
        osCard.addView(Ui.hint(this, About.REPO_NAME));
        body.addView(osCard);

        // 关于作者作为关于软件的子页面，放在最下面，用一行小号蓝字做入口
        TextView authorLink = new TextView(this);
        authorLink.setText("关于作者");
        authorLink.setTextSize(12);
        authorLink.setTextColor(Theme.link());
        authorLink.setPadding(dp(6), dp(22), dp(6), dp(8));
        authorLink.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(AboutActivity.this, AuthorActivity.class));
            }
        });
        body.addView(authorLink);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // ------------------------------------------------------------ 更新

    private void checkUpdate() {
        if (checking) return;
        checking = true;
        updateStatus.setText("正在检查…");
        Updater.checkAsync(this, new Updater.Callback() {
            public void onResult(Updater.Release r, String error) {
                checking = false;
                updateStatus.setText(error != null ? ("检查失败：" + error)
                        : (r == null ? "已是最新版本" : "有新版本：" + r.version));
                if (error != null) {
                    toast(error);
                } else if (r != null) {
                    showUpdate(r);
                } else {
                    toast("已是最新版本");
                }
            }
        });
    }

    private void showUpdate(final Updater.Release r) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前 ").append(C.VERSION).append("  →  最新 ").append(r.version);
        if (r.apkSize > 0) sb.append("\n安装包 ").append(r.sizeText());
        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 0) {
            String[] lines = notes.split("\n");
            StringBuilder cut = new StringBuilder();
            for (int i = 0; i < lines.length && i < 8; i++) cut.append(lines[i]).append("\n");
            if (lines.length > 8) cut.append("…");
            sb.append("\n\n").append(cut.toString().trim());
        }

        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + r.version)
                .setMessage(sb.toString())
                .setPositiveButton("下载并安装", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (!r.hasApk()) {
                            toast("这个版本没有附带安装包");
                            open(r.pageUrl);
                            return;
                        }
                        downloadAndInstall(r);
                    }
                })
                .setNeutralButton("发布页", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { open(r.pageUrl); }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void downloadAndInstall(final Updater.Release r) {
        if (!com.dsh.android.kernel.ApkInstaller.canInstall(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("Android 8 起，应用安装更新需要你允许「安装未知应用」。"
                            + "点「去设置」打开本应用的开关，回来再点一次更新。")
                    .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            try {
                                startActivity(new Intent(
                                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        android.net.Uri.parse("package:" + getPackageName())));
                            } catch (Exception e) {
                                toast("请手动到 设置 → 应用 → 特殊权限 里允许");
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("正在下载更新");
        pd.setMessage("0%");
        pd.setCancelable(true);
        pd.show();

        new Thread(new Runnable() {
            public void run() {
                try {
                    final java.io.File apk = com.dsh.android.kernel.ApkInstaller.download(
                            AboutActivity.this, r.apkUrl,
                            new com.dsh.android.kernel.ApkInstaller.Progress() {
                                public void onProgress(final long done, final long total) {
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            pd.setMessage((done * 100 / Math.max(1, total)) + "%"
                                                    + "（" + (done / 1024) + "KB / " + (total / 1024) + "KB）");
                                        }
                                    });
                                }
                            });
                    runOnUiThread(new Runnable() {
                        public void run() {
                            dismiss(pd);
                            confirmInstall(apk);
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            dismiss(pd);
                            new AlertDialog.Builder(AboutActivity.this)
                                    .setTitle("下载失败")
                                    .setMessage(t.getMessage() == null
                                            ? t.getClass().getSimpleName() : t.getMessage())
                                    .setPositiveButton("去发布页", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) { open(r.pageUrl); }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    });
                }
            }
        }, "apk-download").start();
    }

    private void confirmInstall(final java.io.File apk) {
        long mb = Math.max(1, apk.length() / 1048576);
        new AlertDialog.Builder(this)
                .setTitle("下载完成")
                .setMessage("安装包已下载（约 " + mb + "MB）。\n\n"
                        + "点「立即安装」后系统会弹出安装界面，装完会保留你的设置与对话。")
                .setPositiveButton("立即安装", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        com.dsh.android.kernel.ApkInstaller.install(
                                AboutActivity.this, apk,
                                new com.dsh.android.kernel.ApkInstaller.Callback() {
                                    public void onDone(final String err) {
                                        if (err != null) {
                                            new AlertDialog.Builder(AboutActivity.this)
                                                    .setTitle("无法启动安装")
                                                    .setMessage(err + "\n\n可以到设置 → 关于作者 里"
                                                            + "打开应用信息清除权限后重试，"
                                                            + "或直接从发布页手动下载。")
                                                    .setPositiveButton("知道了", null)
                                                    .show();
                                        }
                                    }
                                });
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void dismiss(android.app.Dialog d) {
        try { if (d != null && d.isShowing()) d.dismiss(); } catch (Throwable ignored) {}
    }

    private void open(String url) {
        if (url == null || url.length() == 0) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            toast("没有可用的浏览器");
        }
    }

    private int dp(float v) { return Theme.dp(this, v); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
