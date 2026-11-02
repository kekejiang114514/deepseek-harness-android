package com.dsh.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 显示器图标：圆角屏幕 + 支架 + 底座。
 *
 * <p>自绘而不是用 emoji 或 ▣ 这类符号：各家 ROM 的字体覆盖不同，
 * 同一个字符在部分手机上会变成豆腐块或尺寸失控。自绘只依赖 Canvas。
 */
public final class MonitorIcon extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public MonitorIcon(Context c) {
        super(c);
        setClickable(true);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float d = getResources().getDisplayMetrics().density;
        setMeasuredDimension((int) (22 * d), (int) (22 * d));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.4f, 1.4f * d));
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Theme.text2());

        float sw = w * 0.78f;
        float sh = h * 0.56f;
        float left = (w - sw) / 2f;
        float top = h * 0.16f;
        rect.set(left, top, left + sw, top + sh);
        canvas.drawRoundRect(rect, 2.4f * d, 2.4f * d, paint);

        canvas.drawLine(w / 2f, top + sh, w / 2f, top + sh + 3 * d, paint);
        paint.setStrokeWidth(Math.max(1.6f, 1.6f * d));
        canvas.drawLine(w * 0.34f, top + sh + 3.4f * d, w * 0.66f, top + sh + 3.4f * d, paint);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
