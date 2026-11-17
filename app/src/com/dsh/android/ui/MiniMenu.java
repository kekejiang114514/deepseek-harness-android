package com.dsh.android.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 右键式小菜单。
 *
 * <p>长按文件夹、长按会话弹出的就是它：贴着被按的那一行出现，宽度跟着文字走，
 * 不像对话框那样铺满整屏。菜单项可以带一个「子菜单」回调，用来做三级菜单
 * （会话 → 移到分组 → 具体分组）。
 */
public final class MiniMenu {

    /** 一项。label 为 null 时画一条分隔线。 */
    public static final class Item {
        final String label;
        final boolean dim;
        final Runnable action;
        final Runnable submenu;

        private Item(String label, boolean dim, Runnable action, Runnable submenu) {
            this.label = label;
            this.dim = dim;
            this.action = action;
            this.submenu = submenu;
        }

        public static Item of(String label, Runnable action) {
            return new Item(label, false, action, null);
        }

        /** 灰色的项（例如当前分组：点了也没意义）。 */
        public static Item dim(String label) {
            return new Item(label, true, null, null);
        }

        /** 点击后打开下一级菜单。 */
        public static Item sub(String label, Runnable openSubmenu) {
            return new Item(label, false, null, openSubmenu);
        }

        public static Item sep() {
            return new Item(null, false, null, null);
        }
    }

    private MiniMenu() {}

    public static PopupWindow show(Activity a, View anchor, String header, List<Item> items) {
        return show(a, anchor, header, items, 0, 0);
    }

    /**
     * @param xoff/yoff 相对 anchor 左上角的偏移，用来把菜单压到手指附近
     */
    public static PopupWindow show(Activity a, View anchor, String header,
                                   List<Item> items, int xoff, int yoff) {
        if (a == null || anchor == null || items == null || items.isEmpty()) return null;

        final Context ctx = a;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
        box.setBackground(Theme.round(Theme.panel(), Theme.R_MD, ctx));
        box.setElevation(dp(ctx, 8));

        if (header != null && header.length() > 0) {
            TextView h = new TextView(ctx);
            h.setText(header);
            h.setTextSize(11);
            h.setTextColor(Theme.caption());
            h.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 4));
            h.setMaxLines(1);
            h.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(h);
        }

        final PopupWindow[] holder = new PopupWindow[1];
        final Activity act = a;
        final View anchorView = anchor;

        for (int i = 0; i < items.size(); i++) {
            final Item it = items.get(i);
            if (it.label == null) {
                View line = new View(ctx);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.5f)));
                lp.setMargins(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
                line.setLayoutParams(lp);
                line.setBackgroundColor(Theme.hoverSolid());
                line.setAlpha(0.5f);
                box.addView(line);
                continue;
            }

            TextView row = new TextView(ctx);
            row.setText(it.label);
            row.setTextSize(13);
            row.setTextColor(it.dim ? Theme.caption() : Theme.text());
            row.setSingleLine(true);
            row.setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9));
            row.setBackground(Theme.round(0x00000000, Theme.R_SM, ctx));
            row.setMinWidth(dp(ctx, 150));
            if (!it.dim) {
                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        PopupWindow p = holder[0];
                        if (p != null) p.dismiss();
                        if (it.action != null) it.action.run();
                        else if (it.submenu != null) it.submenu.run();
                    }
                });
            }
            box.addView(row);
        }

        PopupWindow pop = new PopupWindow(box,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new ColorDrawable(0x00000000));
        pop.setOutsideTouchable(true);
        pop.setElevation(dp(ctx, 8));
        holder[0] = pop;

        // 贴在被按的那一行：左侧对齐并缩进一点，纵向压在行的下沿
        pop.showAsDropDown(anchorView, xoff, yoff, Gravity.START);
        return pop;
    }

    /** 由长按事件拿到手指位置，算出合适的偏移，让菜单出现在指头附近。 */
    public static int[] offsetNear(View anchor, android.view.MotionEvent e) {
        int x = 0;
        int y = 0;
        if (anchor != null && e != null) {
            // 左边留一点缩进，避免菜单贴死屏幕边缘
            x = Math.max(0, Math.min((int) e.getX(), anchor.getWidth() - 8));
            y = -anchor.getHeight() / 2;
        }
        return new int[]{x, y};
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
