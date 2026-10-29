package com.dsh.android.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * DSH 设计系统（对齐 packages/client/ui-theme/src/styles/design-platform.css）。
 *
 * 颜色取自官方 design token：
 *   浅色：bg-base #FFFFFF、label-primary rgb(15,17,21)、label-secondary rgb(97,102,107)
 *   深色：bg-base rgb(21,21,23)、bg-layer-1 rgb(35,35,36)、label-primary rgb(249,250,251)
 * 主按钮：浅色=近黑底白字，深色=白底黑字（brand-primary 会反转）。
 * 圆角：xs4 / sm8 / md12 / lg16 / xl20 / panel28（docs/ui-radius.md）。
 */
public final class Theme {

    public static final int R_XS = 4;
    public static final int R_SM = 8;
    public static final int R_MD = 12;
    public static final int R_LG = 16;
    public static final int R_XL = 20;
    public static final int R_PANEL = 28;

    public static final String PREF_THEME = "theme_mode"; // system | light | dark
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    private static boolean dark = false;
    private static boolean inited = false;

    private Theme() {}

    /** 在 Activity.onCreate 里最先调用。 */
    public static void init(Context ctx) {
        String mode = ctx.getSharedPreferences("dshandroid", Context.MODE_PRIVATE)
                .getString(PREF_THEME, MODE_SYSTEM);
        if (MODE_DARK.equals(mode)) {
            dark = true;
        } else if (MODE_LIGHT.equals(mode)) {
            dark = false;
        } else {
            int night = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            dark = night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
        inited = true;
    }

    public static boolean isDark() { return dark; }

    public static void setMode(Context ctx, String mode) {
        SharedPreferences.Editor e = ctx.getSharedPreferences("dshandroid", Context.MODE_PRIVATE).edit();
        e.putString(PREF_THEME, mode).apply();
        init(ctx);
    }

    public static String mode(Context ctx) {
        return ctx.getSharedPreferences("dshandroid", Context.MODE_PRIVATE)
                .getString(PREF_THEME, MODE_SYSTEM);
    }

    // ---------- 背景 ----------
    /** 页面底色。 */
    public static int bg() { return dark ? 0xFF151517 : 0xFFFFFFFF; }
    /** 卡片 / 次级表面。 */
    public static int surface() { return dark ? 0xFF232324 : 0xFFF9FAFB; }
    /** 更浅一层的表面（输入框、条目）。 */
    public static int surface2() { return dark ? 0xFF2C2C2E : 0xFFF1F3F5; }
    /** 悬浮面板（抽屉、弹层）。 */
    public static int panel() { return dark ? 0xFF232324 : 0xFFFFFFFF; }
    /** 悬停 / 选中态。 */
    public static int hover() { return dark ? 0x14FFFFFF : 0x0F263148; }
    /** 实心悬停底。 */
    public static int hoverSolid() { return dark ? 0xFF353638 : 0xFFF1F3F5; }
    /** 遮罩。 */
    public static int scrim() { return dark ? 0x99000000 : 0x66000000; }

    // ---------- 描边 ----------
    public static int border() { return dark ? 0x1FFFFFFF : 0x1A000000; }
    public static int borderStrong() { return dark ? 0x29FFFFFF : 0x1F000000; }

    // ---------- 文字 ----------
    public static int text() { return dark ? 0xFFF9FAFB : 0xFF0F1115; }
    public static int text2() { return dark ? 0xFFCFD3D6 : 0xFF61666B; }
    public static int text3() { return dark ? 0xFFADB2B8 : 0xFF81858C; }
    public static int caption() { return dark ? 0xFF81858C : 0xFFADB2B8; }

    // ---------- 主色 / 状态 ----------
    /** 主按钮底：浅色近黑，深色近白（brand-primary 反转）。 */
    /** 主色的极浅底（选择题选中态用）。 */
    public static int primarySoft() { return dark ? 0x1FF9FAFB : 0x0F0F1115; }

    public static int primary() { return dark ? 0xFFF9FAFB : 0xFF0F1115; }
    /** 主按钮上的文字。 */
    public static int onPrimary() { return dark ? 0xFF151517 : 0xFFFFFFFF; }
    public static int link() { return 0xFF4176E6; }
    public static int linkDark() { return 0xFF561E5E; }
    public static int success() { return 0xFF22C55E; }
    public static int warning() { return 0xFFF59E0B; }
    public static int danger() { return 0xFFEC1313; }
    /** 强调蓝（深色下更亮）。 */
    public static int accent() { return dark ? 0xFF7AAAff : 0xFF4176E6; }

    /** 行内代码底色。 */
    public static int inlineCodeBg() { return dark ? 0x24FFFFFF : 0x14263048; }
    /** 代码块底色。 */
    public static int codeBg() { return dark ? 0xFF2C2C2E : 0xFFF4F5F7; }

    // ---------- 便捷 ----------
    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static android.graphics.drawable.GradientDrawable round(int color, float radiusDp, Context c) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(c, radiusDp));
        return gd;
    }

    public static android.graphics.drawable.GradientDrawable roundOutlined(
            int fill, float radiusDp, int stroke, Context c) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(c, radiusDp));
        gd.setStroke(Math.max(1, dp(c, 1)), stroke);
        return gd;
    }
}
