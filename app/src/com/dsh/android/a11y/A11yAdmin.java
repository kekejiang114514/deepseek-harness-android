package com.dsh.android.a11y;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 尝试用 root 权限替用户打开无障碍开关。
 *
 * <p>为什么需要这个：无障碍服务无法由应用自授（这是 Android 的安全设计）。
 * 但如果设备已经 root，`settings put secure` 是可行的——这是用户自己能授权的
 * 第三种方式（另外两种是系统设置里手点、以及连电脑跑 ADB）。
 *
 * <p>实现上必须遵守两条：
 * <ul>
 *   <li>不覆盖已有开关：读出当前 enabled_accessibility_services，把本服务**追加**进去</li>
 *   <li>失败要说清原因（没 root / 被拒绝 / 命令报错），不能假装成功</li>
 * </ul>
 * 命令在后台线程执行，调用方负责切线程。
 */
public final class A11yAdmin {

    private A11yAdmin() {}

    /** 设备是否真的有 root（执行 su -c id 看 uid）。 */
    public static boolean hasRoot() {
        String out = exec("id");
        return out != null && out.contains("uid=0");
    }

    /**
     * 用 root 打开本应用的听觉服务。
     * 返回 null 表示成功，否则是给用户看的原因。
     */
    public static String enableWithRoot(Context ctx) {
        String self = new ComponentName(ctx, A11yService.class).flattenToString();
        String current = currentEnabledRaw(ctx);

        String target;
        if (current == null || current.length() == 0) {
            target = self;
        } else if (current.contains(self)
                || current.contains(new ComponentName(ctx, A11yService.class).flattenToShortString())) {
            target = current;          // 已经在里面了
        } else {
            target = current + ":" + self;
        }

        String r1 = exec("settings put secure enabled_accessibility_services '" + target + "'");
        if (r1 == null) return "su 不可用或没有授予权限";
        String r2 = exec("settings put secure accessibility_enabled 1");
        if (r2 == null) return "写入 accessibility_enabled 失败";

        // 校验：读回来看看是否真的写进去了
        String after = currentEnabledRaw(ctx);
        if (after != null && (after.contains(self)
                || after.contains(new ComponentName(ctx, A11yService.class).flattenToShortString()))) {
            return null;
        }
        return "命令执行了，但系统没有接受（可能被设备管理策略锁定）";
    }

    /** 直接把设置值关掉（同样需要 root），用于排错。 */
    public static String disableWithRoot(Context ctx) {
        String self = new ComponentName(ctx, A11yService.class).flattenToString();
        String current = currentEnabledRaw(ctx);
        if (current == null) return "读取当前设置失败";
        String target = current.replace(self, "").replace("::", ":").replaceAll(":$", "");
        exec("settings put secure enabled_accessibility_services '" + target + "'");
        return null;
    }

    /** 诊断信息：贴给开发者就能定位问题。 */
    public static String diagnostics(Context ctx) {
        String self = new ComponentName(ctx, A11yService.class).flattenToString();
        StringBuilder sb = new StringBuilder();
        sb.append("包名: ").append(ctx.getPackageName()).append('\n');
        sb.append("服务组件: ").append(self).append('\n');
        sb.append("系统已列出: ").append(A11yState.listedBySystem(ctx) ? "是" : "否").append('\n');
        sb.append("设置里已启用: ").append(A11yState.enabledInSystem(ctx) ? "是" : "否").append('\n');
        sb.append("服务已连接: ").append(A11yService.isReady() ? "是" : "否").append('\n');
        sb.append("无障碍总开关(Settings): ").append(xmlSafe(safeGet(ctx, "accessibility_enabled"))).append('\n');
        String cur = currentEnabledRaw(ctx);
        sb.append("enabled_accessibility_services:\n").append(cur == null ? "(空)" : cur).append('\n');
        return sb.toString();
    }

    // ---------- 内部 ----------

    private static String currentEnabledRaw(Context ctx) {
        return safeGet(ctx, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    }

    private static String safeGet(Context ctx, String key) {
        try {
            return Settings.Secure.getString(ctx.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String xmlSafe(String s) {
        return s == null ? "(读不到)" : s;
    }

    /** 执行 su -c 命令，返回 stdout；失败返回 null。 */
    private static String exec(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            int code = p.waitFor();
            if (code != 0) return null;
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
