package com.dsh.android.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 细圆角进度条：底色浅灰，前景用主题主色铺满，数值变化时有短过渡。
 *
 * <p>不用系统 ProgressBar：它的默认样式高度和配色跟卡片不搭，
 * 自定义 drawable 又受主题色约束。这里只需要画两段圆角矩形，几十行就够。
 */
public final class ThinProgress extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float target;     // 0..1
    private float shown;      // 当前显示值（动画中间态）
    private ValueAnimator anim;
    private final float height;

    public ThinProgress(Context c) {
        super(c);
        height = 6 * c.getResources().getDisplayMetrics().density;
    }

    public void setProgress(float v) {
        float t = Math.max(0f, Math.min(1f, v));
        if (anim != null) anim.cancel();
        target = t;
        anim = ValueAnimator.ofFloat(shown, t);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (int) height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = height / 2f;
        float w = getWidth();

        paint.setColor(Theme.surface2());
        rect.set(0, 0, w, height);
        canvas.drawRoundRect(rect, r, r, paint);

        float fw = w * Math.max(0f, Math.min(1f, shown));
        if (fw > 0) {
            paint.setColor(Theme.primary());
            rect.set(0, 0, Math.max(fw, height), height);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }
}
