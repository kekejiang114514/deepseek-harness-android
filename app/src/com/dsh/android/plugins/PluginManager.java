package com.dsh.android.plugins;

import android.content.Context;

import com.dsh.android.kernel.EventBus;
import com.dsh.android.tools.ToolRuntime;

import java.util.List;
import java.util.ArrayList;

/**
 * 插件管理器：加载、启用、停用插件，并把插件声明式工具交给 ToolRuntime。
 * 对标 Cordis 的插件注册表。
 */
public final class PluginManager {
    private final EventBus bus;
    private final ToolRuntime tools;
    private final List<Plugin> loaded = new ArrayList<Plugin>();
    private final Context ctx;

    public PluginManager(Context ctx, EventBus bus, ToolRuntime tools) {
        this.ctx = ctx;
        this.bus = bus;
        this.tools = tools;
    }

    /** 加载并启用一个插件。 */
    public boolean load(Plugin p) {
        for (Plugin x : loaded) {
            if (x.meta().id.equals(p.meta().id)) return false;
        }
        loaded.add(p);
        p.apply();
        // 注册该插件声明的工具
        if (p.meta().declarations != null) {
            tools.registerFromPlugin(p.meta().id, p.meta().declarations);
        }
        bus.emit("plugin.loaded", p.meta().id);
        return true;
    }

    /** 停用并移除一个插件。 */
    public boolean unload(String id) {
        for (int i = 0; i < loaded.size(); i++) {
            if (loaded.get(i).meta().id.equals(id)) {
                Plugin p = loaded.remove(i);
                p.dispose();
                tools.unregisterPlugin(id);
                bus.emit("plugin.unloaded", id);
                return true;
            }
        }
        return false;
    }

    public List<Plugin> all() { return new ArrayList<Plugin>(loaded); }

    /** 为每个已加载插件提供 LLM 可见的工具 schema（JSON 数组）。 */
    public org.json.JSONArray toolSchemas() throws Exception {
        return tools.schemas();
    }
}
