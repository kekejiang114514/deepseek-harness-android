package com.dsh.android.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * 有最大高度限制的 ScrollView。
 *
 * <p>模型列表可能很长，整块弹层跟着无限变高会超出屏幕。
 * 这里给它一个上限：内容少时按内容高度，内容多时才出现滚动。
 */
public class ScrollCap extends ScrollView {

    private int maxHeight = 0;

    public ScrollCap(Context c) {
        super(c);
    }

    public ScrollCap(Context c, AttributeSet a) {
        super(c, a);
    }

    /** 设置最大高度（像素）；0 表示不限制。 */
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
}
