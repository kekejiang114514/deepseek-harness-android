package com.dsh.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 自绘图标。
 *
 * <p>不用字符：字重、基线、左右留白都由系统字体决定，同一份代码在不同 ROM 上
 * 能差出像素级错位，缺字还会显示成方块。这里只用 Canvas 画线，任何设备一致。
 *
 * <p>测量规则：尊重父布局给的尺寸（EXACTLY 就用给的值），图形按可用区域里最大的
 * 正方形居中绘制——这样即使父布局给的是 32×40，图标也稳稳居中而不是偏上。
 */
public final class IconView extends View {

    public static final int SLASH = 1, PLUS = 2, SEND = 3, TRASH = 4, COMPACT = 5;
    public static final int THINKING = 6, MEMORY = 7, HELP = 8, PLUGIN = 9;
    public static final int BACK = 10, CLOSE = 11, MENU = 12, GEAR = 13, STOP = 14;
    public static final int KEY = 15, WRENCH = 16, PALETTE = 17, GLOBE = 18;
    public static final int INFO = 19, LOCK = 20, DOC = 21, WINDOW = 22;
    public static final int TERMINAL = 23, BAG = 24;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int type;
    private int color = 0xFF000000;
    private final float dpSize;

    public IconView(Context c, int type, float dpSize) {
        super(c);
        this.type = type;
        this.dpSize = dpSize;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
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
        int want = (int) (dpSize * d);
        int w = resolveSize(want, widthSpec);
        int h = resolveSize(want, heightSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        // 取可用区域里最大的正方形，并居中——父布局给 32×40 时图标也不会偏上
        float s = Math.min(w, h) * 0.62f;      // 视觉边长
        float cx = w / 2f;
        float cy = h / 2f;

        paint.setColor(color);
        paint.setStrokeWidth(Math.max(1.5f, s * 0.11f));
        paint.setStyle(Paint.Style.STROKE);

        float r = s / 2f;                       // 半径

        switch (type) {
            case SLASH:
                canvas.drawLine(cx - r * 0.75f, cy + r, cx + r * 0.75f, cy - r, paint);
                break;

            case PLUS:
                canvas.drawLine(cx - r, cy, cx + r, cy, paint);
                canvas.drawLine(cx, cy - r, cx, cy + r, paint);
                break;

            case SEND:
                canvas.drawLine(cx, cy + r, cx, cy - r, paint);
                canvas.drawLine(cx - r * 0.62f, cy - r * 0.28f, cx, cy - r, paint);
                canvas.drawLine(cx + r * 0.62f, cy - r * 0.28f, cx, cy - r, paint);
                break;

            case STOP:
                paint.setStyle(Paint.Style.FILL);
                rect.set(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f);
                canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, paint);
                break;

            case TRASH:
                canvas.drawLine(cx - r * 0.9f, cy - r * 0.62f, cx + r * 0.9f, cy - r * 0.62f, paint);
                canvas.drawLine(cx - r * 0.3f, cy - r * 0.62f, cx - r * 0.22f, cy - r, paint);
                canvas.drawLine(cx + r * 0.3f, cy - r * 0.62f, cx + r * 0.22f, cy - r, paint);
                canvas.drawLine(cx - r * 0.22f, cy - r, cx + r * 0.22f, cy - r, paint);
                rect.set(cx - r * 0.66f, cy - r * 0.5f, cx + r * 0.66f, cy + r);
                canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, paint);
                canvas.drawLine(cx - r * 0.2f, cy - r * 0.2f, cx - r * 0.2f, cy + r * 0.66f, paint);
                canvas.drawLine(cx + r * 0.2f, cy - r * 0.2f, cx + r * 0.2f, cy + r * 0.66f, paint);
                break;

            case COMPACT:
                canvas.drawLine(cx - r, cy - r * 0.85f, cx - r, cy + r * 0.85f, paint);
                canvas.drawLine(cx + r, cy - r * 0.85f, cx + r, cy + r * 0.85f, paint);
                canvas.drawLine(cx - r * 0.62f, cy - r * 0.3f, cx + r * 0.62f, cy - r * 0.3f, paint);
                canvas.drawLine(cx - r * 0.62f, cy + r * 0.3f, cx + r * 0.62f, cy + r * 0.3f, paint);
                canvas.drawLine(cx - r * 0.9f, cy - r * 0.58f, cx - r * 0.62f, cy - r * 0.3f, paint);
                canvas.drawLine(cx + r * 0.9f, cy + r * 0.58f, cx + r * 0.62f, cy + r * 0.3f, paint);
                break;

            case THINKING:
                // 灯泡：圆 + 灯座 + 光线
                canvas.drawCircle(cx, cy - r * 0.14f, r * 0.6f, paint);
                canvas.drawLine(cx - r * 0.24f, cy + r * 0.52f, cx + r * 0.24f, cy + r * 0.52f, paint);
                canvas.drawLine(cx - r * 0.2f, cy + r * 0.78f, cx + r * 0.2f, cy + r * 0.78f, paint);
                break;

            case MEMORY:
                // 卡片 + 两行
                rect.set(cx - r * 0.9f, cy - r * 0.68f, cx + r * 0.9f, cy + r * 0.68f);
                canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, paint);
                canvas.drawLine(cx - r * 0.55f, cy - r * 0.2f, cx + r * 0.55f, cy - r * 0.2f, paint);
                canvas.drawLine(cx - r * 0.55f, cy + r * 0.22f, cx + r * 0.2f, cy + r * 0.22f, paint);
                break;

            case HELP:
                canvas.drawCircle(cx, cy, r * 0.92f, paint);
                rect.set(cx - r * 0.3f, cy - r * 0.62f, cx + r * 0.3f, cy - r * 0.02f);
                canvas.drawArc(rect, 200, 220, false, paint);
                canvas.drawLine(cx, cy - r * 0.02f, cx, cy + r * 0.2f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy + r * 0.5f, paint.getStrokeWidth() * 0.6f, paint);
                break;

            case PLUGIN:
                // 菱形结
                canvas.drawLine(cx, cy - r, cx + r, cy, paint);
                canvas.drawLine(cx + r, cy, cx, cy + r, paint);
                canvas.drawLine(cx, cy + r, cx - r, cy, paint);
                canvas.drawLine(cx - r, cy, cx, cy - r, paint);
                canvas.drawCircle(cx, cy, r * 0.22f, paint);
                break;

            case BACK:
                canvas.drawLine(cx + r * 0.35f, cy - r * 0.8f, cx - r * 0.35f, cy, paint);
                canvas.drawLine(cx - r * 0.35f, cy, cx + r * 0.35f, cy + r * 0.8f, paint);
                break;

            case CLOSE:
                canvas.drawLine(cx - r * 0.75f, cy - r * 0.75f, cx + r * 0.75f, cy + r * 0.75f, paint);
                canvas.drawLine(cx + r * 0.75f, cy - r * 0.75f, cx - r * 0.75f, cy + r * 0.75f, paint);
                break;

            case MENU:
                canvas.drawLine(cx - r, cy - r * 0.6f, cx + r, cy - r * 0.6f, paint);
                canvas.drawLine(cx - r, cy, cx + r, cy, paint);
                canvas.drawLine(cx - r, cy + r * 0.6f, cx + r, cy + r * 0.6f, paint);
                break;

            case GEAR:
                canvas.drawCircle(cx, cy, r * 0.44f, paint);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    float x1 = (float) (cx + Math.cos(a) * r * 0.66f);
                    float y1 = (float) (cy + Math.sin(a) * r * 0.66f);
                    float x2 = (float) (cx + Math.cos(a) * r * 0.95f);
                    float y2 = (float) (cy + Math.sin(a) * r * 0.95f);
                    canvas.drawLine(x1, y1, x2, y2, paint);
                }
                break;

            case KEY:
                canvas.drawCircle(cx - r * 0.5f, cy, r * 0.42f, paint);
                canvas.drawLine(cx - r * 0.08f, cy, cx + r * 0.92f, cy, paint);
                canvas.drawLine(cx + r * 0.5f, cy, cx + r * 0.5f, cy + r * 0.34f, paint);
                canvas.drawLine(cx + r * 0.85f, cy, cx + r * 0.85f, cy + r * 0.34f, paint);
                break;

            case WRENCH:
                canvas.drawLine(cx - r * 0.7f, cy + r * 0.7f, cx + r * 0.35f, cy - r * 0.35f, paint);
                rect.set(cx + r * 0.1f, cy - r * 0.95f, cx + r * 0.95f, cy - r * 0.1f);
                canvas.drawArc(rect, 200, 140, false, paint);
                canvas.drawLine(cx - r * 0.7f, cy + r * 0.7f, cx - r * 0.95f, cy + r * 0.95f, paint);
                break;

            case PALETTE:
                canvas.drawCircle(cx, cy, r * 0.92f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx - r * 0.38f, cy - r * 0.3f, paint.getStrokeWidth() * 0.55f, paint);
                canvas.drawCircle(cx + r * 0.05f, cy - r * 0.5f, paint.getStrokeWidth() * 0.55f, paint);
                canvas.drawCircle(cx + r * 0.42f, cy - r * 0.05f, paint.getStrokeWidth() * 0.55f, paint);
                canvas.drawCircle(cx - r * 0.2f, cy + r * 0.42f, paint.getStrokeWidth() * 0.55f, paint);
                break;

            case GLOBE:
                canvas.drawCircle(cx, cy, r * 0.92f, paint);
                canvas.drawLine(cx - r * 0.92f, cy, cx + r * 0.92f, cy, paint);
                rect.set(cx - r * 0.45f, cy - r * 0.92f, cx + r * 0.45f, cy + r * 0.92f);
                canvas.drawOval(rect, paint);
                canvas.drawLine(cx, cy - r * 0.92f, cx, cy + r * 0.92f, paint);
                break;

            case INFO:
                canvas.drawCircle(cx, cy, r * 0.92f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy - r * 0.42f, paint.getStrokeWidth() * 0.6f, paint);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(cx, cy - r * 0.1f, cx, cy + r * 0.52f, paint);
                break;

            case LOCK:
                rect.set(cx - r * 0.72f, cy - r * 0.05f, cx + r * 0.72f, cy + r * 0.85f);
                canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, paint);
                rect.set(cx - r * 0.42f, cy - r * 0.85f, cx + r * 0.42f, cy - r * 0.05f);
                canvas.drawArc(rect, 180, 180, false, paint);
                break;

            case DOC:
                rect.set(cx - r * 0.68f, cy - r * 0.92f, cx + r * 0.68f, cy + r * 0.92f);
                canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, paint);
                canvas.drawLine(cx - r * 0.36f, cy - r * 0.42f, cx + r * 0.36f, cy - r * 0.42f, paint);
                canvas.drawLine(cx - r * 0.36f, cy - r * 0.02f, cx + r * 0.36f, cy - r * 0.02f, paint);
                canvas.drawLine(cx - r * 0.36f, cy + r * 0.38f, cx, cy + r * 0.38f, paint);
                break;

            case WINDOW:
                rect.set(cx - r * 0.92f, cy - r * 0.72f, cx + r * 0.92f, cy + r * 0.72f);
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, paint);
                canvas.drawLine(cx - r * 0.92f, cy - r * 0.28f, cx + r * 0.92f, cy - r * 0.28f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx - r * 0.6f, cy - r * 0.5f, paint.getStrokeWidth() * 0.45f, paint);
                break;

            case TERMINAL:
                rect.set(cx - r * 0.92f, cy - r * 0.72f, cx + r * 0.92f, cy + r * 0.72f);
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, paint);
                canvas.drawLine(cx - r * 0.5f, cy - r * 0.16f, cx - r * 0.16f, cy + r * 0.14f, paint);
                canvas.drawLine(cx - r * 0.16f, cy + r * 0.14f, cx - r * 0.5f, cy + r * 0.44f, paint);
                canvas.drawLine(cx + r * 0.02f, cy + r * 0.44f, cx + r * 0.5f, cy + r * 0.44f, paint);
                break;

            case BAG:
                rect.set(cx - r * 0.8f, cy - r * 0.2f, cx + r * 0.8f, cy + r * 0.85f);
                canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, paint);
                rect.set(cx - r * 0.38f, cy - r * 0.85f, cx + r * 0.38f, cy - r * 0.05f);
                canvas.drawArc(rect, 180, 180, false, paint);
                break;

            default:
                break;
        }
    }
}
