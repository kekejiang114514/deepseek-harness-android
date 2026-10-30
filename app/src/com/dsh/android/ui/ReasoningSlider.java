package com.dsh.android.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 推理强度滑块（off → low → medium → high → xhigh → max）。
 *
 * <p>参照 lemonorangeapple/dsh-effort-switcher 的结构重做：
 * <ul>
 *   <li>轨道：26dp 高的胶囊，未填充部分是主题灰</li>
 *   <li>填充：纯色 #4C8DFF，宽度跟着滑块中心走</li>
 *   <li>到 max 才“绽放”：填充层叠加 #4C8DFF → #7B6CFF(52%) → #B56BFF 的渐变，淡入</li>
 *   <li>滑块：30dp 白色圆点，始终贴在填充末端</li>
 *   <li>刻度：4dp 白点（38% 不透明），左右各留 15dp</li>
 *   <li>动画：315ms，缓出</li>
 * </ul>
 *
 * <p>上一版的错误是把六档涂成六种颜色、并把当前档位那段留灰，所以选到 max 反而没颜色。
 * 现在颜色只表达「用了多少力气」：蓝色是常态，紫色是拉满。
 */
public final class ReasoningSlider extends View {

    public interface OnChange {
        void onChanged(int index, String key);
    }

    public static final String[] KEYS = {"off", "low", "medium", "high", "xhigh", "max"};
    private static final String[] LABELS = {"Off", "Low", "Medium", "High", "XHigh", "Max"};
    private static final String[] DESCS = {
            "不推理，直接给答案",
            "少量思考，响应最快",
            "日常默认",
            "更细致的推理",
            "复杂问题深推理，较慢",
            "最大强度，耗时明显",
    };

    private static final int FILL = 0xFF4C8DFF;
    private static final int BLOOM_MID = 0xFF7B6CFF;
    private static final int BLOOM_END = 0xFFB56BFF;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float pos;          // 0..5 的浮点，动画中间态
    private int index;
    private ValueAnimator anim;
    private OnChange listener;

    private float trackH, knobR, inset, tickR;

    /** 拉满时向右流动的粒子。归一化 x ∈ [0,1]，跨过右端就从左侧重来。 */
    private static final int PARTICLE_COUNT = 30;
    private final java.util.ArrayList<Particle> particles = new java.util.ArrayList<Particle>();
    private long lastFrameNs;
    private float density = 1f;

    private static final class Particle {
        float x;
        float yOff;      // -1..1，垂直位置（相对轨道高度）
        float speed;     // 每秒前进的归一化距离
        float radius;    // dp
        float alpha;
    }

    public ReasoningSlider(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        density = d;
        trackH = 26 * d;
        knobR = 15 * d;
        inset = 15 * d;
        tickR = 2 * d;
        setClickable(true);
    }

    // ---------- 对外 ----------

    public void setLevel(String key) {
        index = indexOf(key);
        pos = index;
        invalidate();
    }

    public static int indexOf(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return i;
        }
        return 2;
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

    public void setOnChangeListener(OnChange l) {
        listener = l;
    }

    // ---------- 尺寸 ----------

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, (int) (trackH + knobR));
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas canvas) {
        int n = KEYS.length;
        float cy = getHeight() / 2f;
        float top = cy - trackH / 2f;
        float bottom = cy + trackH / 2f;
        float radius = trackH / 2f;
        float left = 0;
        float right = getWidth();
        float span = n - 1;
        float frac = span <= 0 ? 0 : Math.max(0f, Math.min(1f, pos / span));

        // 滑块中心：从 inset 走到 width-inset
        float knobX = inset + (right - left - inset * 2) * frac;

        // 轨道底
        paint.setShader(null);
        paint.setColor(Theme.hoverSolid());
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // 填充：纯蓝，右端就是滑块中心
        float fillRight = Math.max(left + radius, knobX);
        rect.set(left, top, fillRight, bottom);
        paint.setColor(FILL);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // 拉满时叠加渐变（“绽放”）：在最后一段（span-1 → span）之间淡入。
        // 注意不能写成 pos - span，那样在 max 时正好等于 0，渐变永远不出现。
        float bloom = Math.max(0f, Math.min(1f, pos - (span - 1f)));
        if (bloom > 0.001f) {
            paint.setShader(new LinearGradient(left, 0, right, 0,
                    new int[]{FILL, BLOOM_MID, BLOOM_END},
                    new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
            int alpha = (int) (255 * bloom);
            paint.setAlpha(alpha);
            rect.set(left, top, fillRight, bottom);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setAlpha(255);
            paint.setShader(null);
        }

        // 拉满时：粒子向右流动（只在填充区域内，被滑块压住的地方自然遮掉）
        if (bloom > 0.01f) {
            stepParticles();
            for (int i = 0; i < particles.size(); i++) {
                Particle p = particles.get(i);
                float x = left + (right - left) * p.x;
                if (x >= fillRight) continue;
                float y = cy + p.yOff * (trackH * 0.26f);
                paint.setColor(0xFFFFFFFF);
                paint.setAlpha((int) (255 * p.alpha * bloom));
                canvas.drawCircle(x, y, p.radius * density, paint);
            }
            paint.setAlpha(255);
            postInvalidateOnAnimation();
        } else if (!particles.isEmpty()) {
            particles.clear();
            lastFrameNs = 0;
        }

        // 刻度点：均分，压在轨道上
        paint.setColor(0x61FFFFFF);   // 白 38%
        for (int i = 0; i < n; i++) {
            float x = inset + (right - left - inset * 2) * (i / span);
            // 被滑块盖住的点没必要画
            if (Math.abs(x - knobX) < knobR * 0.9f) continue;
            canvas.drawCircle(x, cy, tickR, paint);
        }

        // 滑块：白色圆点 + 轻微阴影
        paint.setShadowLayer(3f, 0, 1f, 0x40000000);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(knobX, cy, knobR, paint);
        paint.setShadowLayer(0f, 0, 0, 0);
    }

    // ---------- 粒子 ----------

    private void stepParticles() {
        long now = System.nanoTime();
        if (lastFrameNs == 0) lastFrameNs = now;
        // 掉帧时限制步长，避免粒子一下子跳很远
        float dt = Math.min(0.05f, (now - lastFrameNs) / 1_000_000_000f);
        lastFrameNs = now;

        if (particles.isEmpty()) {
            for (int i = 0; i < PARTICLE_COUNT; i++) particles.add(newParticle(true));
        }
        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            p.x += p.speed * dt;
            if (p.x > 1.02f) {
                Particle fresh = newParticle(false);
                p.x = fresh.x;
                p.yOff = fresh.yOff;
                p.speed = fresh.speed;
                p.radius = fresh.radius;
                p.alpha = fresh.alpha;
            }
        }
    }

    /** scattered=true 时散布在整条轨道上（初始状态），否则从左侧排队进入。 */
    private Particle newParticle(boolean scattered) {
        Particle p = new Particle();
        p.x = scattered ? (float) Math.random() : (float) (-Math.random() * 0.25f);
        p.yOff = (float) (Math.random() * 2 - 1) * 0.8f;
        p.speed = 0.16f + (float) Math.random() * 0.22f;
        p.radius = 0.7f + (float) Math.random() * 0.9f;
        p.alpha = 0.25f + (float) Math.random() * 0.45f;
        return p;
    }

    // ---------- 交互 ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                float usable = getWidth() - inset * 2;
                if (usable <= 0) return true;
                float frac = (e.getX() - inset) / usable;
                int i = Math.round(frac * (KEYS.length - 1));
                i = Math.max(0, Math.min(KEYS.length - 1, i));
                select(i);
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

    private void select(int i) {
        if (i == index) return;
        index = i;
        animateTo(i);
        if (listener != null) listener.onChanged(i, KEYS[i]);
    }

    private void animateTo(final int target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(pos, (float) target);
        anim.setDuration(315);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }
}
