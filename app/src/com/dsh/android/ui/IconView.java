package com.dsh.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 自绘图标。
 *
 * <p>界面里的 ☰ ⚙ ‹ ✕ ＋ / ⌫ 这类字符，在不同 ROM 的字体里字重、基线、留白都不一样，
 * 有的还会因为缺字变成方块。这里统一用 Canvas 画，尺寸和线条粗细都由自己控制。
 */
public final class IconView extends View {

    public static final int SLASH = 1;
    public static final int PLUS = 2;
    public static final int SEND = 3;
    public static final int TRASH = 4;
    public static final int COMPACT = 5;
    public static final int THINKING = 6;
    public static final int MEMORY = 7;
    public static final int HELP = 8;
    public static final int PLUGIN = 9;
    public static final int BACK = 10;
    public static final int CLOSE = 11;
    public static final int MENU = 12;
    public static final int GEAR = 13;
    public static final int STOP = 14;
    public static final int KEY = 15;
    public static final int WRENCH = 16;
    public static final int PALETTE = 17;
    public static final int GLOBE = 18;
    public static final int INFO = 19;
    public static final int LOCK = 20;
    public static final int DOC = 21;
    public static final int WINDOW = 22;
    public static final int TERMINAL = 23;
    public static final int BAG = 24;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int type;
    private int color;
    private float dpSize;

    public IconView(Context c, int type, float sizeDp) {
        super(c);
        this.type = type;
        this.dpSize = sizeDp;
        this.color = Theme.text2();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
    }

    public void setType(int t) {
        type = t;
        invalidate();
    }

    public void setColor(int c) {
        color = c;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float d = getResources().getDisplayMetrics().density;
        int px = (int) (dpSize * d);
        setMeasuredDimension(px, px);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float s = Math.min(w, h);
        float stroke = Math.max(1.6f, 1.8f * d);

        paint.setColor(color);
        paint.setStrokeWidth(stroke);

        switch (type) {
            case SLASH:
                canvas.drawLine(cx - s * 0.20f, cy + s * 0.26f,
                        cx + s * 0.20f, cy - s * 0.26f, paint);
                break;

            case PLUS:
                canvas.drawLine(cx - s * 0.24f, cy, cx + s * 0.24f, cy, paint);
                canvas.drawLine(cx, cy - s * 0.24f, cx, cy + s * 0.24f, paint);
                break;

            case SEND:
                canvas.drawLine(cx, cy + s * 0.26f, cx, cy - s * 0.22f, paint);
                canvas.drawLine(cx - s * 0.16f, cy - s * 0.06f, cx, cy - s * 0.24f, paint);
                canvas.drawLine(cx + s * 0.16f, cy - s * 0.06f, cx, cy - s * 0.24f, paint);
                break;

            case TRASH:
                canvas.drawLine(cx - s * 0.22f, cy - s * 0.18f,
                        cx + s * 0.22f, cy - s * 0.18f, paint);
                canvas.drawLine(cx - s * 0.08f, cy - s * 0.18f,
                        cx - s * 0.08f, cy - s * 0.28f, paint);
                canvas.drawLine(cx + s * 0.08f, cy - s * 0.18f,
                        cx + s * 0.08f, cy - s * 0.28f, paint);
                canvas.drawLine(cx - s * 0.08f, cy - s * 0.28f,
                        cx + s * 0.08f, cy - s * 0.28f, paint);
                rect.set(cx - s * 0.17f, cy - s * 0.12f,
                        cx + s * 0.17f, cy + s * 0.28f);
                canvas.drawRoundRect(rect, s * 0.06f, s * 0.06f, paint);
                break;

            case COMPACT:
                // 上下两条箭头指向中线：表示「压扁」
                canvas.drawLine(cx - s * 0.22f, cy - s * 0.22f,
                        cx - s * 0.22f, cy - s * 0.06f, paint);
                canvas.drawLine(cx - s * 0.30f, cy - s * 0.14f,
                        cx - s * 0.22f, cy - s * 0.06f, paint);
                canvas.drawLine(cx - s * 0.14f, cy - s * 0.14f,
                        cx - s * 0.22f, cy - s * 0.06f, paint);

                canvas.drawLine(cx + s * 0.22f, cy + s * 0.22f,
                        cx + s * 0.22f, cy + s * 0.06f, paint);
                canvas.drawLine(cx + s * 0.30f, cy + s * 0.14f,
                        cx + s * 0.22f, cy + s * 0.06f, paint);
                canvas.drawLine(cx + s * 0.14f, cy + s * 0.14f,
                        cx + s * 0.22f, cy + s * 0.06f, paint);

                canvas.drawLine(cx - s * 0.16f, cy, cx + s * 0.16f, cy, paint);
                break;

            case THINKING:
                canvas.drawCircle(cx, cy - s * 0.04f, s * 0.20f, paint);
                canvas.drawLine(cx - s * 0.07f, cy + s * 0.20f,
                        cx + s * 0.07f, cy + s * 0.20f, paint);
                canvas.drawLine(cx - s * 0.05f, cy + s * 0.27f,
                        cx + s * 0.05f, cy + s * 0.27f, paint);
                break;

            case MEMORY:
                rect.set(cx - s * 0.24f, cy - s * 0.18f,
                        cx + s * 0.24f, cy + s * 0.20f);
                canvas.drawRoundRect(rect, s * 0.07f, s * 0.07f, paint);
                canvas.drawLine(cx - s * 0.13f, cy - s * 0.02f,
                        cx + s * 0.13f, cy - s * 0.02f, paint);
                canvas.drawLine(cx - s * 0.13f, cy + s * 0.09f,
                        cx + s * 0.05f, cy + s * 0.09f, paint);
                break;

            case HELP:
                rect.set(cx - s * 0.15f, cy - s * 0.24f,
                        cx + s * 0.15f, cy + s * 0.02f);
                canvas.drawArc(rect, 180, -270, false, paint);
                canvas.drawLine(cx, cy - s * 0.02f, cx, cy + s * 0.08f, paint);
                canvas.drawCircle(cx, cy + s * 0.21f, stroke * 0.6f, paint);
                break;

            case PLUGIN:
                // 菱形结：四条边构成一个「扩展」符号
                canvas.drawLine(cx, cy - s * 0.26f, cx + s * 0.20f, cy, paint);
                canvas.drawLine(cx + s * 0.20f, cy, cx, cy + s * 0.26f, paint);
                canvas.drawLine(cx, cy + s * 0.26f, cx - s * 0.20f, cy, paint);
                canvas.drawLine(cx - s * 0.20f, cy, cx, cy - s * 0.26f, paint);
                break;

            case BACK:
                canvas.drawLine(cx + s * 0.10f, cy - s * 0.22f,
                        cx - s * 0.10f, cy, paint);
                canvas.drawLine(cx - s * 0.10f, cy,
                        cx + s * 0.10f, cy + s * 0.22f, paint);
                break;

            case CLOSE:
                canvas.drawLine(cx - s * 0.18f, cy - s * 0.18f,
                        cx + s * 0.18f, cy + s * 0.18f, paint);
                canvas.drawLine(cx + s * 0.18f, cy - s * 0.18f,
                        cx - s * 0.18f, cy + s * 0.18f, paint);
                break;

            case MENU:
                canvas.drawLine(cx - s * 0.24f, cy - s * 0.16f,
                        cx + s * 0.24f, cy - s * 0.16f, paint);
                canvas.drawLine(cx - s * 0.24f, cy,
                        cx + s * 0.24f, cy, paint);
                canvas.drawLine(cx - s * 0.24f, cy + s * 0.16f,
                        cx + s * 0.24f, cy + s * 0.16f, paint);
                break;

            case GEAR:
                canvas.drawCircle(cx, cy, s * 0.15f, paint);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    float x1 = (float) (cx + Math.cos(a) * s * 0.20f);
                    float y1 = (float) (cy + Math.sin(a) * s * 0.20f);
                    float x2 = (float) (cx + Math.cos(a) * s * 0.30f);
                    float y2 = (float) (cy + Math.sin(a) * s * 0.30f);
                    canvas.drawLine(x1, y1, x2, y2, paint);
                }
                break;

            case STOP:
                paint.setStyle(Paint.Style.FILL);
                rect.set(cx - s * 0.16f, cy - s * 0.16f, cx + s * 0.16f, cy + s * 0.16f);
                canvas.drawRoundRect(rect, s * 0.05f, s * 0.05f, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;

            case KEY:
                canvas.drawCircle(cx - s * 0.12f, cy, s * 0.12f, paint);
                canvas.drawLine(cx, cy, cx + s * 0.26f, cy, paint);
                canvas.drawLine(cx + s * 0.14f, cy, cx + s * 0.14f, cy + s * 0.12f, paint);
                canvas.drawLine(cx + s * 0.24f, cy, cx + s * 0.24f, cy + s * 0.10f, paint);
                break;

            case WRENCH:
                canvas.drawLine(cx - s * 0.18f, cy + s * 0.20f,
                        cx + s * 0.16f, cy - s * 0.16f, paint);
                canvas.drawCircle(cx + s * 0.20f, cy - s * 0.20f, s * 0.10f, paint);
                canvas.drawLine(cx - s * 0.20f, cy + s * 0.22f,
                        cx - s * 0.10f, cy + s * 0.12f, paint);
                break;

            case PALETTE:
                canvas.drawCircle(cx, cy, s * 0.24f, paint);
                canvas.drawCircle(cx - s * 0.07f, cy - s * 0.07f, s * 0.03f, paint);
                canvas.drawCircle(cx + s * 0.08f, cy - s * 0.02f, s * 0.03f, paint);
                canvas.drawCircle(cx - s * 0.02f, cy + s * 0.10f, s * 0.03f, paint);
                break;

            case GLOBE:
                canvas.drawCircle(cx, cy, s * 0.24f, paint);
                canvas.drawLine(cx, cy - s * 0.24f, cx, cy + s * 0.24f, paint);
                canvas.drawLine(cx - s * 0.24f, cy, cx + s * 0.24f, cy, paint);
                rect.set(cx - s * 0.12f, cy - s * 0.24f, cx + s * 0.12f, cy + s * 0.24f);
                canvas.drawOval(rect, paint);
                break;

            case INFO:
                canvas.drawCircle(cx, cy, s * 0.24f, paint);
                canvas.drawCircle(cx, cy - s * 0.10f, stroke * 0.6f, paint);
                canvas.drawLine(cx, cy - s * 0.02f, cx, cy + s * 0.14f, paint);
                break;

            case LOCK:
                rect.set(cx - s * 0.18f, cy - s * 0.02f, cx + s * 0.18f, cy + s * 0.24f);
                canvas.drawRoundRect(rect, s * 0.05f, s * 0.05f, paint);
                rect.set(cx - s * 0.11f, cy - s * 0.26f, cx + s * 0.11f, cy + s * 0.04f);
                canvas.drawArc(rect, 180, 180, false, paint);
                break;

            case DOC:
                rect.set(cx - s * 0.17f, cy - s * 0.24f, cx + s * 0.17f, cy + s * 0.24f);
                canvas.drawRoundRect(rect, s * 0.04f, s * 0.04f, paint);
                canvas.drawLine(cx - s * 0.09f, cy - s * 0.10f, cx + s * 0.09f, cy - s * 0.10f, paint);
                canvas.drawLine(cx - s * 0.09f, cy, cx + s * 0.09f, cy, paint);
                canvas.drawLine(cx - s * 0.09f, cy + s * 0.10f, cx + s * 0.02f, cy + s * 0.10f, paint);
                break;

            case WINDOW:
                rect.set(cx - s * 0.24f, cy - s * 0.20f, cx + s * 0.24f, cy + s * 0.20f);
                canvas.drawRoundRect(rect, s * 0.05f, s * 0.05f, paint);
                canvas.drawLine(cx - s * 0.24f, cy - s * 0.08f, cx + s * 0.24f, cy - s * 0.08f, paint);
                break;

            case TERMINAL:
                rect.set(cx - s * 0.24f, cy - s * 0.20f, cx + s * 0.24f, cy + s * 0.20f);
                canvas.drawRoundRect(rect, s * 0.05f, s * 0.05f, paint);
                canvas.drawLine(cx - s * 0.14f, cy - s * 0.05f, cx - s * 0.05f, cy + s * 0.02f, paint);
                canvas.drawLine(cx - s * 0.05f, cy + s * 0.02f, cx - s * 0.14f, cy + s * 0.09f, paint);
                canvas.drawLine(cx, cy + s * 0.09f, cx + s * 0.12f, cy + s * 0.09f, paint);
                break;

            case BAG:
                rect.set(cx - s * 0.22f, cy - s * 0.10f, cx + s * 0.22f, cy + s * 0.24f);
                canvas.drawRoundRect(rect, s * 0.06f, s * 0.06f, paint);
                rect.set(cx - s * 0.10f, cy - s * 0.26f, cx + s * 0.10f, cy - s * 0.02f);
                canvas.drawArc(rect, 180, 180, false, paint);
                break;

            default:
                break;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
