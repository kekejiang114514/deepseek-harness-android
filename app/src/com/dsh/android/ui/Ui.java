package com.dsh.android.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 关于页共用的几个小控件。
 *
 * 设置页那套行/卡片是 SettingsActivity 的私有方法，这里抽一份最小集合，
 * 给「关于软件」「关于作者」两个页面用，保证视觉一致又不用互相依赖。
 */
final class Ui {

    interface SwitchCallback {
        void onChanged(boolean checked);
    }

    private Ui() {}

    /** 顶栏：‹ + 标题 */
    static View topBar(final Activity a, String title) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 8), dp(a, 10), dp(a, 16), dp(a, 10));
        bar.setBackgroundColor(Theme.bg());

        TextView back = new TextView(a);
        back.setText("\u2039");
        back.setTextSize(26);
        back.setTextColor(Theme.text());
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { a.finish(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)));

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(17);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(Theme.text());
        bar.addView(t);
        return bar;
    }

    static TextView sectionLabel(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Theme.caption());
        t.setPadding(dp(c, 4), dp(c, 18), dp(c, 4), dp(c, 8));
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(Theme.round(Theme.surface(), Theme.R_XL, c));
        return v;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Theme.border());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.setMargins(dp(c, 16), 0, dp(c, 16), 0);
        v.setLayoutParams(lp);
        return v;
    }

    /** 左标题右值的一行（不可点）。 */
    static TextView line(Context c, String title, String value) {
        LinearLayout row = null;
        TextView t = new TextView(c);
        t.setText(title + "    " + value);
        t.setTextSize(14);
        t.setTextColor(Theme.text());
        t.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        return t;
    }

    /** 可点击的一行（链接色）。 */
    static TextView action(Context c, String text, View.OnClickListener click) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(Theme.link());
        t.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        t.setOnClickListener(click);
        return t;
    }

    /** 说明文字。 */
    static TextView hint(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Theme.text3());
        t.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 14));
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    static View switchRow(Context c, String title, String sub, boolean checked,
                          final SwitchCallback cb) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(Theme.text());
        col.addView(t);
        TextView s = new TextView(c);
        s.setText(sub);
        s.setTextSize(12);
        s.setTextColor(Theme.caption());
        s.setPadding(0, dp(c, 3), 0, 0);
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(c);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                cb.onChanged(v);
            }
        });
        r.addView(sw);
        return r;
    }

    static int dp(Context c, float v) {
        return Theme.dp(c, v);
    }
}
