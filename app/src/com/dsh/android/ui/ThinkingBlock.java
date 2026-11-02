package com.dsh.android.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 思考过程块：把模型的 reasoning_content 折叠展示。
 *
 * <p>流式生成时自动展开、实时追加；开始输出正文后自动收起，只留一行「思考 · N 字」，
 * 想回看随时点开。历史重放时同样以收起状态呈现。
 */
public final class ThinkingBlock extends LinearLayout {

    private final TextView header;
    private final TextView body;
    private final StringBuilder text = new StringBuilder();

    private boolean expanded = true;

    public ThinkingBlock(Context c, String initial) {
        super(c);
        setOrientation(VERTICAL);
        setBackground(Theme.roundOutlined(Theme.surface(), 12, Theme.border(), c));
        setPadding(dp(12), dp(8), dp(12), dp(8));

        header = new TextView(c);
        header.setTextSize(11);
        header.setTextColor(Theme.caption());
        header.setSingleLine(true);
        header.setEllipsize(TextUtils.TruncateAt.END);
        addView(header);

        body = new TextView(c);
        body.setTextSize(12);
        body.setTextColor(Theme.text3());
        body.setLineSpacing(dp(3), 1f);
        body.setPadding(0, dp(6), 0, dp(2));
        body.setTextIsSelectable(true);
        addView(body);

        if (initial != null && initial.length() > 0) {
            text.append(initial);
            body.setText(initial);
        }
        refresh(false);
        setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });
    }

    /** 追加增量。 */
    public void append(String delta) {
        if (delta == null || delta.length() == 0) return;
        text.append(delta);
        body.setText(text.toString());
        refresh(false);
    }

    public String text() { return text.toString(); }

    /** 正文开始时收起，避免长思考挡住回答。 */
    public void collapse() {
        if (expanded) toggle();
    }

    private void toggle() {
        expanded = !expanded;
        refresh(true);
    }

    private void refresh(boolean animate) {
        body.setVisibility(expanded ? VISIBLE : GONE);
        int len = text.length();
        header.setText((expanded ? "\u25BE " : "\u25B8 ") + "思考 · " + len + " 字");
        if (animate) {
            body.setAlpha(0f);
            body.animate().alpha(1f).setDuration(140).start();
        }
    }

    private int dp(float v) { return Theme.dp(getContext(), v); }
}
