package com.dsh.android.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.dsh.android.tools.ShellEngine;

/**
 * 终端：在应用内跑系统 shell 命令，输出实时追加。
 *
 * <p>和 AI 侧的 {@code shell.run} 用的是同一套执行器，区别只在于这里由人直接输入。
 */
public final class TerminalActivity extends Activity {

    private TextView out;
    private ScrollView scroll;
    private EditText input;
    private boolean running;

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
        print("$ 输入命令后回车执行。以本应用身份运行，没有 root 就做不了需要 root 的事。\n");
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg());
        root.addView(Ui.topBar(this, "终端"));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(Theme.dp(this, 14), 0, Theme.dp(this, 14), Theme.dp(this, 4));
        TextView clear = new TextView(this);
        clear.setText("清屏");
        clear.setTextSize(12);
        clear.setTextColor(Theme.link());
        clear.setPadding(0, Theme.dp(this, 4), Theme.dp(this, 16), Theme.dp(this, 4));
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                out.setText("");
            }
        });
        tools.addView(clear);
        root.addView(tools);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        out = new TextView(this);
        out.setTextSize(11.5f);
        out.setTextColor(Theme.text2());
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        out.setPadding(Theme.dp(this, 14), Theme.dp(this, 8),
                Theme.dp(this, 14), Theme.dp(this, 8));
        scroll.addView(out);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Theme.dp(this, 12), Theme.dp(this, 6),
                Theme.dp(this, 12), Theme.dp(this, 12));

        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(13);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setTextColor(Theme.text());
        input.setHintTextColor(Theme.caption());
        input.setHint("ls /sdcard");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setPadding(Theme.dp(this, 12), Theme.dp(this, 10),
                Theme.dp(this, 12), Theme.dp(this, 10));
        input.setBackground(Theme.round(Theme.surface2(), 12, this));
        bar.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = new TextView(this);
        go.setText("运行");
        go.setTextSize(13);
        go.setTextColor(Theme.onPrimary());
        go.setPadding(Theme.dp(this, 18), Theme.dp(this, 10),
                Theme.dp(this, 18), Theme.dp(this, 10));
        go.setBackground(Theme.round(Theme.primary(), 12, this));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.setMargins(Theme.dp(this, 8), 0, 0, 0);
        go.setLayoutParams(glp);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { submit(); }
        });
        bar.addView(go);

        root.addView(bar);
        return root;
    }

    private void submit() {
        if (running) {
            Snack.show(this, "上一条还没跑完");
            return;
        }
        final String cmd = input.getText().toString().trim();
        if (cmd.length() == 0) return;
        input.setText("");
        print("$ " + cmd + "\n");
        running = true;
        new Thread(new Runnable() {
            public void run() {
                final ShellEngine.Result r = ShellEngine.run(cmd, 60000);
                runOnUiThread(new Runnable() {
                    public void run() {
                        StringBuilder sb = new StringBuilder();
                        if (r.error != null) sb.append("! ").append(r.error).append('\n');
                        if (r.out.length() > 0) sb.append(r.out);
                        if (r.err.length() > 0) sb.append(r.err);
                        if (sb.length() == 0) sb.append("（没有输出）");
                        if (!sb.toString().endsWith("\n")) sb.append('\n');
                        sb.append("[exit ").append(r.exit).append("]\n\n");
                        print(sb.toString());
                        running = false;
                    }
                });
            }
        }, "terminal").start();
    }

    private void print(String text) {
        out.append(text);
        scroll.post(new Runnable() {
            public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
    }
}
