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
    public static final int TERMINAL = 23, BAG = 24, PAUSE = 25, PLAY = 26;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int type;
    /** 默认跟随主题次文本色：深色模式浅、浅色模式深，不写死黑色。 */
    private int color = Theme.text2();
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

    /** 路径按类型缓存，避免每帧重新解析。 */
    private static final java.util.Map<Integer, android.graphics.Path> PATHS =
            new java.util.HashMap<Integer, android.graphics.Path>();

    private static android.graphics.Path pathOf(int type) {
        android.graphics.Path p = PATHS.get(Integer.valueOf(type));
        if (p != null) return p;
        String d;
        switch (type) {
            case SLASH: d = Icons.SLASH; break;
            case PLUS: d = Icons.PLUS; break;
            case SEND: d = Icons.SEND; break;
            case TRASH: d = Icons.TRASH; break;
            case COMPACT: d = Icons.COMPACT; break;
            case THINKING: d = Icons.THINKING; break;
            case MEMORY: d = Icons.MEMORY; break;
            case HELP: d = Icons.HELP; break;
            case PLUGIN: d = Icons.PLUGIN; break;
            case BACK: d = Icons.BACK; break;
            case CLOSE: d = Icons.CLOSE; break;
            case MENU: d = Icons.MENU; break;
            case GEAR: d = Icons.GEAR; break;
            case STOP: d = Icons.STOP; break;
            case KEY: d = Icons.KEY; break;
            case WRENCH: d = Icons.WRENCH; break;
            case PALETTE: d = Icons.PALETTE; break;
            case GLOBE: d = Icons.GLOBE; break;
            case INFO: d = Icons.INFO; break;
            case LOCK: d = Icons.LOCK; break;
            case DOC: d = Icons.DOC; break;
            case WINDOW: d = Icons.WINDOW; break;
            case TERMINAL: d = Icons.TERMINAL; break;
            case PAUSE: d = Icons.PAUSE; break;
            case PLAY: d = Icons.PLAY; break;
            case BAG: d = Icons.BAG; break;
            default: d = Icons.HELP; break;
        }
        p = SvgPath.parse(d);
        PATHS.put(Integer.valueOf(type), p);
        return p;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        // 图形尺寸以 dpSize 为上限：点击区域可以由布局给得更大，图标本身不该跟着变大
        float want = dpSize * getResources().getDisplayMetrics().density;
        float size = Math.min(Math.min(w, h), want);
        if (size <= 0) return;

        float scale = size / 24f;
        canvas.save();
        canvas.translate((w - size) / 2f, (h - size) / 2f);
        canvas.scale(scale, scale);

        // 描边绘制：线宽固定在 24 网格里的 2，缩放后与 monitor 图标完全一致
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        canvas.drawPath(pathOf(type), paint);

        canvas.restore();
    }
}
