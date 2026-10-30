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
 * 六档推理强度滑块（off → low → medium → high → xhigh → max）。
 *
 * <p>自绘而不是用 SeekBar：需要「分段填充 + 每档不同颜色 + 过渡动画」，
 * SeekBar 的 progressDrawable 做不出这种效果，硬塞反而更难维护。
 *
 * <p>画法：6 个圆角段；第 i 段的填充比例 = clamp(位置 - i, 0, 1)，
 * 颜色在该档颜色与底色之间插值。位置是浮点数，动画时逐帧变化，
 * 所以切档时是「从旧档滑到新档、颜色一路渐变」，不是硬切换。
 *
 * <p>交互：按下或拖动都会即时选中，拖动时按整数档位触发回调，
 * 不会每移动一个像素就重建一次请求客户端。
 */
public final class ReasoningSlider extends View {

    public interface OnChange {
        void onChanged(int index, String key);
    }

    public static final String[] KEYS = {"off", "low", "medium", "high", "xhigh", "max"};
    private static final String[] LABELS = {"off", "low", "medium", "high", "xhigh", "max"};
    private static final String[] DESCS = {
            "不推理，直接给答案",
            "少量思考，响应最快",
            "日常默认",
            "更细致的推理",
            "复杂问题深推理，较慢",
            "最大强度，耗时明显",
    };
    /** 由冷到暖，视觉上「越往右越用力」。 */
    private static final int[] COLORS = {
            0xFF9AA0A6, 0xFF4C8DFF, 0xFF22C1C3, 0xFF22C55E, 0xFFF59E0B, 0xFFEC4899,
    };

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float pos;          // 当前显示位置（浮点，动画中间态）
    private int index;          // 目标档位
    private float segW;
    private float gap;
    private ValueAnimator anim;
    private OnChange listener;

    public ReasoningSlider(Context c) {
        super(c);
        gap = dp(c, 4);
        labelPaint.setTextSize(dp(c, 10));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    public void setLevel(String key) {
        int i = indexOf(key);
        index = i;
        pos = i;
        invalidate();
    }

    public static int indexOf(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return i;
        }
        return 2;   // 默认 medium
    }

    public static String keyAt(int i) {
        return KEYS[Math.max(0, Math.min(KEYS.length - 1, i))];
    }

    public static String labelOf(String key) {
        return LABELS[indexOf(key)];
    }

    public static String descOf(String key) {
        return DESCS[indexOf(key)];
    }

    public static int colorOf(String key) {
        return COLORS[indexOf(key)];
    }

    public void setOnChangeListener(OnChange l) {
        listener = l;
    }

    /** 高度：条 + 下面一行档位名。 */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = (int) (dp(getContext(), 10) + dp(getContext(), 16));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = KEYS.length;
        float h = dp(getContext(), 10);
        float top = 0;
        segW = (getWidth() - gap * (n - 1)) / (float) n;

        int base = Theme.surface2();
        for (int i = 0; i < n; i++) {
            float left = i * (segW + gap);
            rect.set(left, top, left + segW, top + h);

            float fill = clamp(pos - i, 0f, 1f);
            int color = blend(base, COLORS[i], fill);

            // 已完全点亮的段再加一点光晕，让当前档位更醒目
            barPaint.setColor(color);
            barPaint.setShadowLayer(0, 0, 0, 0);
            canvas.drawRoundRect(rect, h / 2, h / 2, barPaint);

            // 档位名
            labelPaint.setColor(fill > 0.5f ? COLORS[i] : Theme.caption());
            float cx = rect.centerX();
            canvas.drawText(LABELS[i], cx, h + dp(getContext(), 12), labelPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                if (segW <= 0) return true;
                int i = (int) (e.getX() / (segW + gap));
                i = Math.max(0, Math.min(KEYS.length - 1, i));
                select(i, e.getActionMasked() == MotionEvent.ACTION_MOVE);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                performClick();
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void select(int i, boolean dragging) {
        if (i == index) return;
        index = i;
        animateTo(i);
        if (listener != null) listener.onChanged(i, KEYS[i]);
    }

    private void animateTo(final int target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(pos, (float) target);
        anim.setDuration(260);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    // ---------- 小工具 ----------

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 两色按比例混合（含 alpha），用于填充过渡。 */
    private static int blend(int from, int to, float t) {
        float f = clamp(t, 0f, 1f);
        int a = (int) (alpha(from) + (alpha(to) - alpha(from)) * f);
        int r = (int) (red(from) + (red(to) - red(from)) * f);
        int g = (int) (green(from) + (green(to) - green(from)) * f);
        int b = (int) (blue(from) + (blue(to) - blue(from)) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int alpha(int c) { return (c >>> 24) & 0xFF; }
    private static int red(int c) { return (c >> 16) & 0xFF; }
    private static int green(int c) { return (c >> 8) & 0xFF; }
    private static int blue(int c) { return c & 0xFF; }

    private static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }
}
