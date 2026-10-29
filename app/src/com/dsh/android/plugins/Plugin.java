package com.dsh.android.plugins;

import com.dsh.android.kernel.EventBus;

/**
 * 插件基类（对标 Cordis plugin：接收上下文，注册事件/命令/工具）。
 * 一个插件 = 一个 Plugin 实例 + 元数据 PluginMeta。
 */
public abstract class Plugin {
    protected final PluginMeta meta;
    protected final EventBus bus;

    public Plugin(PluginMeta meta, EventBus bus) {
        this.meta = meta;
        this.bus = bus;
    }

    /** 插件启用时调用，用于订阅事件、注册工具。 */
    public abstract void apply();

    /** 插件停用时调用。 */
    public void dispose() {}

    public PluginMeta meta() { return meta; }
}
