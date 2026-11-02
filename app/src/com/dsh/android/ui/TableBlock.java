package com.dsh.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.List;

/**
 * Markdown 表格：整体一个网格，表头有底色，行列都有分隔线。
 *
 * <p>之前每个单元格是独立的圆角框，视觉上像一排标签而不是表格。
 * 这里改成一张连续的网格：外框一个圆角矩形，表头填色，行间与列间用细线分隔，
 * 列宽按内容测量后取整，超出屏幕交给外层横向滚动。
 */
public final class TableBlock extends View {

    private final List<String[]> rows;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final int pad;
    private final int rowH;
    private final int[] colW;
    private final int totalW;
    private final int totalH;

    public TableBlock(Context ctx, List<String[]> data) {
        super(ctx);
        this.rows = data;
        float d = ctx.getResources().getDisplayMetrics().density;
        pad = (int) (10 * d);
        rowH = (int) (34 * d);

        textPaint.setTextSize(13 * d);
        linePaint.setColor(Theme.border());
        linePaint.setStrokeWidth(Math.max(1f, d));
        headPaint.setColor(Theme.surface2());
        bgPaint.setColor(Theme.bg());

        int cols = 1;
        for (int i = 0; i < rows.size(); i++) cols = Math.max(cols, rows.get(i).length);
        colW = new int[cols];
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            for (int c = 0; c < cols && c < row.length; c++) {
                boolean head = i == 0;
                textPaint.setFakeBoldText(head);
                int w = (int) textPaint.measureText(row[c] == null ? "" : row[c]) + pad * 2;
                colW[c] = Math.max(colW[c], Math.max((int) (64 * d), w));
            }
        }
        textPaint.setFakeBoldText(false);

        int w = 0;
        for (int c = 0; c < cols; c++) w += colW[c];
        totalW = Math.max(w, (int) (120 * d));
        totalH = rowH * Math.max(1, rows.size());
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(totalW, totalH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = 10 * getResources().getDisplayMetrics().density;

        // 底色 + 外框
        rect.set(0, 0, totalW, totalH);
        bgPaint.setColor(Theme.bg());
        canvas.drawRoundRect(rect, r, r, bgPaint);

        // 表头底色（顶部圆角单独靠裁剪实现）
        if (rows.size() > 0) {
            canvas.save();
            canvas.clipRect(0, 0, totalW, rowH);
            canvas.drawRoundRect(rect, r, r, headPaint);
            canvas.restore();
        }

        // 行分隔线
        for (int i = 1; i < rows.size(); i++) {
            float y = i * rowH;
            canvas.drawLine(r, y, totalW - r, y, linePaint);
        }

        // 列分隔线
        float x = 0;
        for (int c = 0; c < colW.length - 1; c++) {
            x += colW[c];
            canvas.drawLine(x, 0, x, totalH, linePaint);
        }

        // 外框描边
        Paint border = linePaint;
        border.setStyle(Paint.Style.STROKE);
        canvas.drawRoundRect(rect, r, r, border);
        border.setStyle(Paint.Style.FILL);

        // 文本
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = (rowH - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        x = 0;
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            float left = 0;
            for (int c = 0; c < colW.length; c++) {
                String cell = c < row.length ? row[c] : "";
                if (cell == null) cell = "";
                boolean head = i == 0;
                textPaint.setFakeBoldText(head);
                textPaint.setColor(head ? Theme.text() : Theme.text2());
                canvas.save();
                canvas.clipRect(left, i * rowH, left + colW[c], (i + 1) * rowH);
                canvas.drawText(cell, left + pad, i * rowH + baseline, textPaint);
                canvas.restore();
                left += colW[c];
            }
        }
        textPaint.setFakeBoldText(false);
    }
}
