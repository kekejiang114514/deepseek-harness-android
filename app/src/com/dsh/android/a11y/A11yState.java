package com.dsh.android.a11y;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

import com.dsh.android.a11y.A11yService;

/**
 * 查询「无障碍服务在系统里到底开没开」。
 *
 * <p>为什么需要单独判断：{@link A11yService#isReady()} 只反映「本进程是否连上了服务」。
 * 用户刚在系统设置里打开开关时，系统要过一会儿才 bind 过来；反过来，服务也可能被系统
 * 杀掉导致 isReady 变 false 但开关还是开着的。这两种情况给用户的提示完全不同：
 * 前者应该让他「等一下/回应用看看」，后者才需要「去开启」。
 *
 * <p>判断依据是 Settings.Secure 里的两个键，读它们不需要任何权限。
 */
public final class A11yState {

    public static final int UNKNOWN = 0;
    /** 系统里没开这个服务。 */
    public static final int OFF = 1;
    /** 系统里开着，但本进程还没连上。 */
    public static final int ON_NOT_CONNECTED = 2;
    /** 已经连上，可用。 */
    public static final int READY = 3;

    private A11yState() {}

    public static int of(Context ctx) {
        if (A11yService.isReady()) return READY;
        return enabledInSystem(ctx) ? ON_NOT_CONNECTED : OFF;
    }

    /** 系统设置里是否勾选了本服务。 */
    public static boolean enabledInSystem(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.length() == 0) return false;
            String full = new ComponentName(ctx, A11yService.class).flattenToString();
            String shortName = new ComponentName(ctx, A11yService.class).flattenToShortString();
            return enabled.contains(full) || enabled.contains(shortName);
        } catch (Throwable t) {
            return false;
        }
    }

    public static String describe(Context ctx) {
        switch (of(ctx)) {
            case READY: return "已连接";
            case ON_NOT_CONNECTED: return "系统已开启 · 尚未连接";
            default: return "未开启 · 点此开启";
        }
    }

    /**
     * 没法在界面里点开关时（企业管控、开关发灰、遥控帮助别人）用的一条命令。
     * 需要连电脑执行，属于纯文本提示，应用本身不会去跑它。
     */
    public static String adbCommand(Context ctx) {
        String full = new ComponentName(ctx, A11yService.class).flattenToString();
        return "adb shell settings put secure enabled_accessibility_services " + full
                + "\nadb shell settings put secure accessibility_enabled 1";
    }
}
