package com.dsh.android.plugins;

/**
 * 进程内共享的插件管理器。
 *
 * 为什么要共享：工具注册表只有一份（MainActivity 里的 ToolRuntime），
 * 设置页如果自己新建一套，开关改了也不会影响真正跑模型的那份。
 * 所以主界面装配完把实例放这里，设置页直接复用，切开关当场生效。
 */
public final class PluginHub {

    private static PluginManager current;

    private PluginHub() {}

    public static void install(PluginManager m) {
        current = m;
    }

    public static PluginManager get() {
        return current;
    }
}
