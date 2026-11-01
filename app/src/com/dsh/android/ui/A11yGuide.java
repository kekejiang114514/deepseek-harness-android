package com.dsh.android.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dsh.android.a11y.A11yService;

/**
 * 无障碍开启引导。
 *
 * 关键事实：Android 的无障碍服务**不能由 App 自己申请**，必须在系统设置里由用户手动打开。
 * 所以这里做三件事：
 *   1. 跳转直达「本应用的无障碍服务详情页」，而不是丢给用户一长串列表自己找；
 *   2. 明确写出开启步骤，以及 Android 13 起侧载应用的「受限设置」灰开关怎么解；
 *   3. 模型在服务没开时调用屏幕工具，会通过 requestPrompt() 弹一次引导（带冷却，不骚扰）。
 */
public final class A11yGuide {

    /** 由界面层实现：在 UI 线程弹出引导。 */
    public interface Host {
        void showA11yPrompt();
    }

    private static Host host;
    private static long lastPromptAt = 0L;
    /** 两次自动弹窗的最小间隔，避免模型连续试几个工具就弹好几次。 */
    private static final long COOLDOWN_MS = 3 * 60 * 1000L;

    private A11yGuide() {}

    public static void install(Host h) {
        host = h;
    }

    /** 直达本应用无障碍详情页；低版本退化成系统无障碍列表。 */
    public static Intent detailIntent(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 常量名在部分 android.jar 里缺失，直接用系统 action 字符串
            Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            i.putExtra(Intent.EXTRA_COMPONENT_NAME,
                    new ComponentName(c, A11yService.class));
            return i;
        }
        return new Intent("android.settings.ACCESSIBILITY_SETTINGS");
    }

    /** 打开系统设置；返回是否成功跳转。 */
    public static boolean openSettings(Activity a) {
        try {
            a.startActivity(detailIntent(a));
            return true;
        } catch (Throwable t) {
            try {
                a.startActivity(new Intent("android.settings.ACCESSIBILITY_SETTINGS"));
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    /**
     * 工具线程调用：屏幕工具发现服务没开时，请界面弹一次引导。
     * 带冷却，且宿主不在时静默返回。
     */
    public static void requestPrompt() {
        Host h = host;
        if (h == null) return;
        synchronized (A11yGuide.class) {
            long now = System.currentTimeMillis();
            if (now - lastPromptAt < COOLDOWN_MS) return;
            lastPromptAt = now;
        }
        try {
            h.showA11yPrompt();
        } catch (Throwable ignored) {}
    }

    /** 引导弹窗。alreadyTried=true 表示是因为模型调用失败才弹的，措辞更直接。 */
    public static void showDialog(final Activity a, boolean alreadyTried) {
        TextView body = new TextView(a);
        body.setTextSize(14);
        body.setTextColor(Theme.text());
        body.setLineSpacing(dp(a, 4), 1f);
        body.setText(
                "屏幕操作（读屏、点击、滑动、输入）靠系统的无障碍服务实现。"
                + "按 Android 的规定，这项权限只能由你自己在系统设置里打开，应用无法自行申请。\n\n"
                + "开启步骤：\n"
                + "1. 点下面「去开启」，会直接跳到本应用的无障碍页面\n"
                + "2. 打开「DShAndroid 无障碍」开关，系统弹窗里点「允许」\n"
                + "3. 回到这里，状态会自动变成「已连接」\n\n"
                + "如果无障碍列表里根本没有「DShAndroid 无障碍」这一项，或者开关是灰的点不动："
                + "这是 Android 13 起对侧载安装应用的「受限设置」限制。先点下面「应用信息」，"
                + "在该页面右上角 ⋮ 里选「允许受限设置」，再回到无障碍列表打开开关。");
        int pad = dp(a, 20);
        body.setPadding(pad, dp(a, 4), pad, 0);

        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(body);

        new AppDialog.Builder(a)
                .setTitle(alreadyTried ? "需要先开启屏幕操作" : "开启屏幕操作")
                .setView(wrap)
                .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (!openSettings(a)) {
                            Snack.show(a, "无法打开系统设置，请手动进入 设置 → 无障碍");
                        }
                    }
                })
                .setNegativeButton("稍后", null)
                .setNeutralButton("应用信息", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            a.startActivity(com.dsh.android.a11y.A11yState.appDetailsIntent(a));
                            Snack.show(a, "点右上角 ⋮ → 允许受限设置，然后回到无障碍里开启");
                        } catch (Throwable t) {
                            Snack.show(a, "无法打开应用信息页");
                        }
                    }
                })
                .show();
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
