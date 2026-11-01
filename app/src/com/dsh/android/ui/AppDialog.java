package com.dsh.android.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 应用内统一的对话框，替代系统 AlertDialog。
 *
 * <p>系统对话框在深色主题下是另一套配色（白色卡片、Material 按钮），
 * 和整个界面格格不入；而且按钮排版、圆角、间距都没法控制。这里用
 * {@link Dialog} + 自绘卡片实现，接口刻意做成和 {@code AppDialog.Builder}
 * 一致，替换时只需要改类名。
 *
 * <p>按钮规则：positive 用主色实心胶囊，negative / neutral 用文字按钮，
 * 从右往左排列；点任意按钮先执行回调再关闭。
 */
public final class AppDialog {

    private AppDialog() {}

    /** 与 AppDialog.Builder 同形的构造器。 */
    public static final class Builder {
        private final Context ctx;
        private CharSequence title;
        private CharSequence message;
        private View content;
        private CharSequence positiveText, negativeText, neutralText;
        private DialogInterface.OnClickListener positiveClick, negativeClick, neutralClick;
        private DialogInterface.OnDismissListener dismissListener;
        private boolean cancelable = true;

        public Builder(Context c) {
            this.ctx = c;
        }

        public Builder setTitle(CharSequence t) {
            this.title = t;
            return this;
        }

        public Builder setMessage(CharSequence m) {
            this.message = m;
            return this;
        }

        public Builder setView(View v) {
            this.content = v;
            return this;
        }

        public Builder setPositiveButton(CharSequence t, DialogInterface.OnClickListener l) {
            this.positiveText = t;
            this.positiveClick = l;
            return this;
        }

        public Builder setNegativeButton(CharSequence t, DialogInterface.OnClickListener l) {
            this.negativeText = t;
            this.negativeClick = l;
            return this;
        }

        public Builder setNeutralButton(CharSequence t, DialogInterface.OnClickListener l) {
            this.neutralText = t;
            this.neutralClick = l;
            return this;
        }

        public Builder setOnDismissListener(DialogInterface.OnDismissListener l) {
            this.dismissListener = l;
            return this;
        }

        public Builder setCancelable(boolean b) {
            this.cancelable = b;
            return this;
        }

        public Dialog show() {
            final Dialog dlg = new Dialog(ctx);
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
            }
            dlg.setCancelable(cancelable);

            int pad = dp(20);
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Theme.round(Theme.panel(), 22, ctx));
            card.setPadding(pad, pad, pad, dp(12));

            if (title != null && title.length() > 0) {
                TextView t = new TextView(ctx);
                t.setText(title);
                t.setTextSize(17);
                t.setTypeface(null, Typeface.BOLD);
                t.setTextColor(Theme.text());
                card.addView(t);
            }

            if (message != null && message.length() > 0) {
                TextView m = new TextView(ctx);
                m.setText(message);
                m.setTextSize(13);
                m.setTextColor(Theme.text2());
                m.setLineSpacing(dp(3), 1f);
                m.setPadding(0, dp(10), 0, 0);
                m.setTextIsSelectable(true);

                ScrollView sv = new ScrollView(ctx);
                sv.setVerticalScrollBarEnabled(false);
                sv.addView(m);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
                lp.setMargins(0, 0, 0, dp(6));
                card.addView(sv, lp);
            }

            if (content != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
                lp.setMargins(0, dp(10), 0, dp(6));
                card.addView(content, lp);
            }

            LinearLayout btns = new LinearLayout(ctx);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            btns.setPadding(0, dp(10), 0, 0);

            if (neutralText != null) btns.addView(textButton(dlg, neutralText, neutralClick));
            if (negativeText != null) btns.addView(textButton(dlg, negativeText, negativeClick));
            if (positiveText != null) btns.addView(primaryButton(dlg, positiveText, positiveClick));
            card.addView(btns);

            dlg.setContentView(card);
            WindowManager.LayoutParams wlp = dlg.getWindow() == null
                    ? null : dlg.getWindow().getAttributes();
            if (wlp != null) {
                wlp.width = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.88f);
                wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                dlg.getWindow().setAttributes(wlp);
            }

            if (dismissListener != null) dlg.setOnDismissListener(dismissListener);
            dlg.show();
            return dlg;
        }

        private TextView textButton(final Dialog dlg, CharSequence text,
                                    final DialogInterface.OnClickListener click) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextSize(14);
            t.setTextColor(Theme.text2());
            t.setPadding(dp(14), dp(10), dp(14), dp(10));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (click != null) click.onClick(dlg, -1);
                    dlg.dismiss();
                }
            });
            return t;
        }

        private TextView primaryButton(final Dialog dlg, CharSequence text,
                                       final DialogInterface.OnClickListener click) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextSize(14);
            t.setTextColor(Theme.onPrimary());
            t.setPadding(dp(18), dp(10), dp(18), dp(10));
            t.setBackground(Theme.round(Theme.primary(), 20, ctx));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (click != null) click.onClick(dlg, -1);
                    dlg.dismiss();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(6), 0, 0, 0);
            t.setLayoutParams(lp);
            return t;
        }

        private int dp(float v) {
            return Theme.dp(ctx, v);
        }
    }
}
