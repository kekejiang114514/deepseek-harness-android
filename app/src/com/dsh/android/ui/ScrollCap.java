package com.dsh.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

/**
 * 有最大高度限制的 ScrollView，并在右侧自绘一条滚动指示条。
 *
 * <p>系统自带的那条滚动条颜色来自主题样式，这套界面是自绘主题，
 * 深色模式下它几乎看不见。改成自己画：颜色取主题的说明色，
 * 只在内容超出可视区域时出现。
 */
public class ScrollCap extends ScrollView {

    private int maxHeight = 0;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();

    public ScrollCap(Context c) {
        super(c);
        init();
    }

    public ScrollCap(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setVerticalScrollBarEnabled(false);   // 关掉系统滚动条，改自己画
        setWillNotDraw(false);
    }

    public void setMaxHeight(int px) {
        this.maxHeight = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (maxHeight > 0) {
            heightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        View child = getChildCount() > 0 ? getChildAt(0) : null;
        if (child == null) return;
        int viewH = getHeight() - getPaddingTop() - getPaddingBottom();
        int contentH = child.getHeight();
        if (contentH <= viewH || viewH <= 0) return;

        int range = contentH - viewH;
        float ratio = viewH / (float) contentH;
        float barH = Math.max(viewH * ratio, dp(24));
        float travel = viewH - barH;
        float offset = range <= 0 ? 0 : (getScrollY() / (float) range) * travel;

        float w = dp(3);
        float right = getWidth() - getPaddingRight() - dp(2);
        bar.set(right - w, getPaddingTop() + offset,
                right, getPaddingTop() + offset + barH);

        paint.setColor(Theme.scrollbar());
        canvas.drawRoundRect(bar, w / 2f, w / 2f, paint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
