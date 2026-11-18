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

    /** Material You 动态取色（API 31+）读到的强调色；0 表示不用。 */
    private static int dynAccent = 0;
    private static int dynPrimary = 0;
    private static int dynPrimarySoft = 0;

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
        applyDynamicColors(ctx);
        applyDirection(ctx);
    }

    /**
     * Material You：Android 12+ 从系统壁纸取色。
     *
     * <p>只替换强调色一族（强调色、主按钮、主按钮淡底），其余中性色仍用
     * 设计 token —— 全部换成动态色会让自绘界面失去层次。API 31 以下或
     * 取不到颜色时保持原配色。
     */
    private static void applyDynamicColors(Context ctx) {
        dynAccent = 0;
        dynPrimary = 0;
        dynPrimarySoft = 0;
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            boolean on = ctx.getSharedPreferences("dshandroid", Context.MODE_PRIVATE)
                    .getBoolean("dynamic_color", true);
            if (!on) return;
            // 用资源名解析而不是 R 常量：编译用的精简 android.jar 里没有这些字段
            int c1 = sysColor(ctx, "system_accent1_200");
            int c2 = sysColor(ctx, "system_accent1_500");
            int c3 = sysColor(ctx, "system_accent1_50");
            if (c1 == 0 || c2 == 0) return;
            if (dark) {
                dynAccent = c1;          // 深色下用较亮的那档
                dynPrimarySoft = withAlpha(c2, 0x33);
            } else {
                dynAccent = c2;          // 浅色下用中间档
                dynPrimarySoft = withAlpha(c3, 0xFF);
            }
            dynPrimary = c2;
        } catch (Throwable ignored) {
            dynAccent = 0;
            dynPrimary = 0;
            dynPrimarySoft = 0;
        }
    }

    /** 按名字取系统颜色资源；取不到返回 0。 */
    private static int sysColor(Context ctx, String name) {
        try {
            int id = ctx.getResources().getIdentifier(name, "color", "android");
            if (id == 0) return 0;
            return ctx.getColor(id);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    public static boolean isDark() { return dark; }

    /**
     * 按当前语言设置布局方向：阿拉伯语、希伯来语等 RTL 语言会把整个界面镜像。
     * 对 Activity 直接作用在窗口根视图上，这样所有页面一次性生效。
     */
    public static void applyDirection(Context ctx) {
        try {
            boolean rtl = com.dsh.android.kernel.Lang.isRtl(ctx);
            int dir = rtl ? android.view.View.LAYOUT_DIRECTION_RTL
                    : android.view.View.LAYOUT_DIRECTION_LTR;
            if (ctx instanceof android.app.Activity) {
                android.app.Activity a = (android.app.Activity) ctx;
                android.view.Window w = a.getWindow();
                if (w != null) w.getDecorView().setLayoutDirection(dir);
                // 内容根视图单独设一次：部分机型上 decor 的方向不会传给 inflate 出来的层级
                android.view.View content = a.findViewById(android.R.id.content);
                if (content != null) content.setLayoutDirection(dir);
            }
        } catch (Throwable ignored) {}
    }

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
    /** 滚动指示条：深色下要够亮，浅色下不能太扎眼。 */
    public static int scrollbar() {
        return dark ? 0x66FFFFFF : 0x33000000;
    }

    public static int caption() {
        // 这一档常用于 11sp 小字，深色下太暗会读不出来
        return dark ? 0xFF9AA0A6 : 0xFF9BA1A8;
    }

    // ---------- 主色 / 状态 ----------
    /** 主按钮底：浅色近黑，深色近白（brand-primary 反转）。 */
    /** 主色的极浅底（选择题选中态用）。 */
    public static int primarySoft() {
        if (dynPrimarySoft != 0) return dynPrimarySoft;
        return dark ? 0x1FF9FAFB : 0x0F0F1115;
    }

    public static int primary() {
        // 主按钮本来是近黑/近白；开了动态取色则跟随系统强调色
        if (dynPrimary != 0) return dynPrimary;
        return dark ? 0xFFF9FAFB : 0xFF0F1115;
    }
    /** 主按钮上的文字。 */
    public static int onPrimary() { return dark ? 0xFF151517 : 0xFFFFFFFF; }
    public static int link() {
        // 深色底上深蓝读不清，暗色模式用更亮的蓝
        return dark ? 0xFF7FB0FF : 0xFF4176E6;
    }
    public static int linkDark() { return 0xFF561E5E; }
    public static int success() { return 0xFF22C55E; }
    public static int warning() { return 0xFFF59E0B; }
    public static int danger() {
        // 深色底上深红几乎看不见（对比度 ~2.5），暗色模式换亮红
        return dark ? 0xFFFF7B72 : 0xFFD92D20;
    }

    /** 危险色作为背景时（如红色按钮）上面的文字颜色。 */
    public static int onDanger() {
        return dark ? 0xFF2A0B09 : 0xFFFFFFFF;
    }
    /** 强调蓝（深色下更亮）。 */
    public static int accent() {
        if (dynAccent != 0) return dynAccent;
        return dark ? 0xFF8AB4FF : 0xFF4176E6;
    }

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
