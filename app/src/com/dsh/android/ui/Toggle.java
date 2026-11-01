package com.dsh.android.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 自绘开关，替代系统 Switch。
 *
 * <p>系统 Switch 在深色主题下轨道与拇指配色不受控，也和卡片风格不搭。
 * 这里画一个胶囊轨道 + 圆形滑块，开合有 180ms 过渡，颜色全部取自主题。
 */
public final class Toggle extends View {

    public interface OnChange {
        void onChanged(boolean checked);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean checked;
    private float pos;          // 0..1
    private ValueAnimator anim;
    private OnChange listener;
    private final float h, w, knob;

    public Toggle(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        h = 22 * d;
        w = 40 * d;
        knob = 18 * d;
        setClickable(true);
    }

    public void setChecked(boolean v) {
        if (checked == v) return;
        checked = v;
        animateTo(v ? 1f : 0f);
    }

    public boolean isChecked() { return checked; }

    public void setOnChangeListener(OnChange l) { listener = l; }

    private void animateTo(float target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(pos, target);
        anim.setDuration(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension((int) w, (int) h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float top = 0;
        float r = h / 2f;
        // 轨道：关闭时浅灰，开启时主色按进度混合
        int off = Theme.surface2();
        int on = Theme.primary();
        paint.setColor(blend(off, on, pos));
        rect.set(0, top, w, h);
        canvas.drawRoundRect(rect, r, r, paint);

        // 滑块
        float margin = (h - knob) / 2f;
        float cx = margin + knob / 2f + (w - knob - margin * 2) * pos;
        paint.setColor(Theme.isDark() ? 0xFF151517 : 0xFFFFFFFF);
        canvas.drawCircle(cx, h / 2f, knob / 2f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            checked = !checked;
            animateTo(checked ? 1f : 0f);
            if (listener != null) listener.onChanged(checked);
            performClick();
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private static int blend(int a, int b, float t) {
        float f = Math.max(0f, Math.min(1f, t));
        int aa = (int) (((a >>> 24) & 0xFF) + ((((b >>> 24) & 0xFF) - ((a >>> 24) & 0xFF)) * f));
        int rr = (int) (((a >> 16) & 0xFF) + ((((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * f));
        int gg = (int) (((a >> 8) & 0xFF) + ((((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * f));
        int bb = (int) ((a & 0xFF) + (((b & 0xFF) - (a & 0xFF)) * f));
        return (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }
}
