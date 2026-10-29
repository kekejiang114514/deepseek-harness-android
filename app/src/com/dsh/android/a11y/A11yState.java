package com.dsh.android.a11y;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

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
    /**
     * 系统根本没有列出本应用的无障碍服务。
     *
     * <p>最常见的原因是 Android 13 起对「侧载安装」的应用默认施加的受限设置：
     * 无障碍开关会连同整个服务条目一起被隐藏，用户在无障碍列表里根本找不到它，
     * 也就无从授权。解法是到「应用信息 → 右上角 ⋮ → 允许受限设置」。
     */
    public static final int NOT_LISTED = 4;
    /** 系统里没开这个服务。 */
    public static final int OFF = 1;
    /** 系统里开着，但本进程还没连上。 */
    public static final int ON_NOT_CONNECTED = 2;
    /** 已经连上，可用。 */
    public static final int READY = 3;

    private A11yState() {}

    public static int of(Context ctx) {
        if (A11yService.isReady()) return READY;
        if (enabledInSystem(ctx)) return ON_NOT_CONNECTED;
        // 已启用列表里没有，再看系统是不是压根没列出这个服务
        return listedBySystem(ctx) ? OFF : NOT_LISTED;
    }

    /**
     * 系统是否把本应用的无障碍服务列了出来。
     *
     * <p>这一判断是「受限设置」唯一可行的检测方式：被限制的应用，其服务不会出现在
     * {@link AccessibilityManager#getInstalledAccessibilityServiceList()} 里，
     * 而安装包自身是完好的（manifest 与配置 XML 都正确）。
     */
    public static boolean listedBySystem(Context ctx) {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return true;     // 判断不了时按「已列出」处理，别误报
            List<AccessibilityServiceInfo> list = am.getInstalledAccessibilityServiceList();
            if (list == null) return true;
            String pkg = ctx.getPackageName();
            for (int i = 0; i < list.size(); i++) {
                AccessibilityServiceInfo info = list.get(i);
                if (info == null || info.getResolveInfo() == null
                        || info.getResolveInfo().serviceInfo == null) continue;
                if (pkg.equals(info.getResolveInfo().serviceInfo.packageName)) return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 逐项自检：把「系统为什么看不到这个服务」的每一环结果都打出来。
     *
     * 顺序就是系统的判断顺序：组件在不在 → 有没有 intent-filter →
     * meta-data 能不能解析成 XML → 无障碍管理器有没有列出它。
     * 任一步失败，前面的环节都不用再猜。
     */
    public static String selfTest(Context ctx) {
        StringBuilder sb = new StringBuilder();
        ComponentName cn = new ComponentName(ctx, A11yService.class);

        sb.append("包名：").append(ctx.getPackageName()).append('\n');
        sb.append("组件：").append(cn.flattenToString()).append('\n');

        // 1. 组件是否在包里
        try {
            android.content.pm.ServiceInfo si = ctx.getPackageManager().getServiceInfo(
                    cn, android.content.pm.PackageManager.GET_META_DATA);
            sb.append("[1] 组件声明：存在\n");
            sb.append("    exported=").append(si.exported)
              .append(" enabled=").append(si.enabled)
              .append(" permission=").append(si.permission).append('\n');

            // 2. intent-filter
            android.content.pm.ComponentInfo ci = si;
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.Intent probe = new android.content.Intent(
                    "android.accessibilityservice.AccessibilityService");
            java.util.List<android.content.pm.ResolveInfo> hits = pm.queryIntentServices(
                    probe, android.content.pm.PackageManager.GET_META_DATA);
            boolean inQuery = false;
            if (hits != null) {
                for (int i = 0; i < hits.size(); i++) {
                    android.content.pm.ServiceInfo x = hits.get(i).serviceInfo;
                    if (x != null && ctx.getPackageName().equals(x.packageName)) inQuery = true;
                }
            }
            sb.append("[2] intent-filter 查询：").append(inQuery ? "命中" : "未命中").append('\n');

            // 3. meta-data 能否解析成 XML（这一步失败，系统会直接忽略整个服务）
            if (si.metaData == null) {
                sb.append("[3] meta-data：缺失\n");
            } else {
                Object raw = si.metaData.get("android.accessibilityservice");
                sb.append("[3] meta-data 值：").append(raw).append('\n');
                try {
                    android.content.res.XmlResourceParser xrp =
                            si.loadXmlMetaData(pm, "android.accessibilityservice");
                    sb.append("    解析 XML：").append(xrp == null ? "失败（系统会跳过本服务）" : "成功").append('\n');
                    if (xrp != null) xrp.close();
                } catch (Throwable t) {
                    sb.append("    解析 XML 抛异常：").append(t).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("[1] 组件声明：读取失败 — ").append(t).append('\n');
        }

        // 4. 无障碍管理器是否列出
        sb.append("[4] 系统无障碍列表：")
          .append(listedBySystem(ctx) ? "已列出" : "未列出").append('\n');
        sb.append("[5] 设置里已启用：").append(enabledInSystem(ctx) ? "是" : "否").append('\n');
        sb.append("[6] 服务已连接：").append(A11yService.isReady() ? "是" : "否").append('\n');
        return sb.toString();
    }

    /** 「应用信息」页：允许受限设置就在这里的右上角菜单里。 */
    public static Intent appDetailsIntent(Context ctx) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + ctx.getPackageName()));
        return i;
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
            case NOT_LISTED: return "系统未列出此服务 · 点此处理";
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
