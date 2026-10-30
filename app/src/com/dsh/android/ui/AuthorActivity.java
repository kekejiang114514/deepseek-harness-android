package com.dsh.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.android.kernel.About;

/**
 * 关于作者：名字、仓库、社交链接。链接集中在 {@link About}，改那一处即可。
 */
public final class AuthorActivity extends Activity {

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
        root.addView(Ui.topBar(this, "关于项目"));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(40));

        // 项目
        body.addView(Ui.sectionLabel(this, "项目"));
        LinearLayout nameCard = Ui.card(this);
        TextView name = new TextView(this);
        name.setText(About.NAME);
        name.setTextSize(20);
        name.setTextColor(Theme.text());
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setPadding(dp(16), dp(16), dp(16), dp(6));
        nameCard.addView(name);
        TextView sub = new TextView(this);
        sub.setText("MIT 开源 · 纯 Java 构建的 Android DeepSeek 客户端");
        sub.setTextSize(12);
        sub.setTextColor(Theme.caption());
        sub.setPadding(dp(16), 0, dp(16), dp(16));
        nameCard.addView(sub);
        body.addView(nameCard);

        // 链接
        body.addView(Ui.sectionLabel(this, "链接"));
        LinearLayout linkCard = Ui.card(this);
        linkCard.addView(linkRow("源代码", About.REPO_NAME, About.REPO));
        linkCard.addView(Ui.divider(this));
        linkCard.addView(linkRow("问题反馈", "提 issue", About.ISSUES));
        linkCard.addView(Ui.divider(this));
        linkCard.addView(linkRow("开源协议", "MIT", About.REPO + "/blob/main/LICENSE"));
        body.addView(linkCard);

        body.addView(Ui.hint(this, About.FOOTER));

        LinearLayout copyCard = Ui.card(this);
        copyCard.addView(Ui.action(this, "复制项目信息", new View.OnClickListener() {
            public void onClick(View v) { copyAll(); }
        }));
        body.addView(copyCard);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /** 一行链接：左边名字，右边 URL 摘要，点击打开。 */
    private View linkRow(String title, String sub, final String url) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { open(url); }
        });

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(Theme.text());
        row.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12);
        s.setTextColor(Theme.link());
        s.setPadding(0, dp(3), 0, 0);
        row.addView(s);
        return row;
    }

    private void copyAll() {
        String all = About.NAME + "\n"
                + "仓库: " + About.REPO + "\n"
                + "问题反馈: " + About.ISSUES;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("author", all));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private void open(String url) {
        try {
            Intent i;
            if (url.startsWith("mailto:")) {
                i = new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse(url));
            } else {
                i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            }
            startActivity(i);
        } catch (Exception e) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("link", url));
            Toast.makeText(this, "没有可用的应用，链接已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(float v) { return Theme.dp(this, v); }
}
