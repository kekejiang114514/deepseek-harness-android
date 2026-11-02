package com.dsh.android.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 工具调用胶囊：一行显示「状态点 + 工具名 + 参数摘要 + 耗时」，点开看完整结果。
 *
 * <p>设计参考 OpenMinis 的 ToolCallPill，用 Java 重写、未使用其代码：
 * 工具调用不该占满一整块屏幕，而应像聊天里的一条状态——紧凑、可折叠、完成后收起。
 */
public final class ToolPill extends LinearLayout {

    private final Dot dot;
    private final TextView title;
    private final TextView chevron;
    private final TextView detail;

    private final long startedAt;
    private boolean done;
    private boolean expanded;

    public ToolPill(Context c, String toolLabel, String argsSummary) {
        super(c);
        setOrientation(VERTICAL);
        setBackground(Theme.round(Theme.surface2(), 14, c));
        setPadding(dp(12), dp(7), dp(12), dp(7));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        dot = new Dot(c);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.setMargins(0, 0, dp(8), 0);
        head.addView(dot, dotLp);

        title = new TextView(c);
        title.setText(toolLabel + (argsSummary.length() > 0 ? "  " + argsSummary : "")
                + "  ·  运行中");
        title.setTextSize(12);
        title.setTextColor(Theme.text2());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        chevron = new TextView(c);
        chevron.setText("\u02C4");
        chevron.setTextSize(11);
        chevron.setTextColor(Theme.caption());
        chevron.setPadding(dp(6), 0, 0, 0);
        head.addView(chevron);

        addView(head);

        detail = new TextView(c);
        detail.setTextSize(11);
        detail.setTextColor(Theme.text3());
        detail.setPadding(0, dp(7), 0, dp(1));
        detail.setTextIsSelectable(true);
        detail.setSingleLine(false);
        detail.setHorizontallyScrolling(false);
        // 长 JSON 里常有无空格长串，Android 默认不在这里断行，会撑出屏幕
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            detail.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
            detail.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        }
        detail.setVisibility(GONE);
        addView(detail);

        startedAt = System.currentTimeMillis();
        setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });
    }

    public void finish(boolean ok, String result) {
        done = true;
        dot.stop(ok);
        long ms = System.currentTimeMillis() - startedAt;
        String head = title.getText().toString();
        int idx = head.lastIndexOf("  ·  ");
        if (idx > 0) head = head.substring(0, idx);
        title.setText(head + "  ·  " + (ok ? "完成" : "失败") + "  " + ms + "ms");
        title.setTextColor(ok ? Theme.text2() : Theme.danger());

        String body = result == null ? "" : result.trim();
        if (body.length() > 2000) body = body.substring(0, 2000) + "\n…";
        detail.setText(body);
        // 失败时自动展开，直接看到原因
        if (!ok && !expanded) toggle();
    }

    public boolean isDone() { return done; }

    private void toggle() {
        expanded = !expanded;
        detail.setVisibility(expanded ? VISIBLE : GONE);
        chevron.setText(expanded ? "\u02C5" : "\u02C4");
        requestLayout();
        if (expanded) {
            detail.setAlpha(0f);
            detail.animate().alpha(1f).setDuration(140).start();
        }
        if (onExpand != null) onExpand.onToggled(this);
    }

    /** 展开后通知宿主重新贴底（否则内容变高，视口停在半空）。 */
    public interface OnExpand {
        void onToggled(ToolPill pill);
    }

    private OnExpand onExpand;

    public void setOnExpandListener(OnExpand l) { onExpand = l; }

    private int dp(float v) { return Theme.dp(getContext(), v); }

    /** 状态点：运行中缓慢呼吸，结束后变实心（绿=成功，红=失败）。 */
    private final class Dot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator anim;
        private float alpha = 1f;
        private int color = Theme.primary();

        Dot(Context c) {
            super(c);
            anim = ValueAnimator.ofFloat(0.35f, 1f);
            anim.setDuration(760);
            anim.setRepeatMode(ValueAnimator.REVERSE);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator a) {
                    alpha = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        void stop(boolean ok) {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            alpha = 1f;
            color = ok ? 0xFF22C55E : Theme.danger();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(color);
            paint.setAlpha((int) (255 * alpha));
            float r = Math.min(getWidth(), getHeight()) / 2f;
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r, paint);
        }

        @Override
        protected void onDetachedFromWindow() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            super.onDetachedFromWindow();
        }
    }
}
