package com.dsh.android.plugins;

import android.content.Context;

import com.dsh.android.kernel.EventBus;
import com.dsh.android.plugins.js.Esm;
import com.dsh.android.plugins.js.JsHost;
import com.dsh.android.plugins.js.JsPluginStore;
import com.dsh.android.plugins.js.Schema;
import com.dsh.android.plugins.js.Ts;
import com.dsh.android.tools.ToolExecutor;
import com.dsh.android.tools.ToolRuntime;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件管理器：内置插件 + 用户 plugin.json 插件，统一成一份注册表。
 *
 * 生命周期：
 *   reload()  清空所有插件工具 → 按启用状态逐个重新加载（设置页切开关后调它）
 *   setEnabled(id, on)  改持久化状态并 reload，失败返回错误信息
 *
 * 加载失败不抛异常：错误记在该插件的 Info.error 上，界面直接显示，其它插件照常工作。
 */
public final class PluginManager {

    /** 给界面看的插件视图。 */
    public static final class Info {
        public String id;
        public String name;
        public String version;
        public String description;
        public String author;
        public boolean builtin;
        /** builtin / http / js */
        public String kind = "http";
        /** JS 运行时还没就绪，稍后会自动重试。 */
        public boolean pending;
        public boolean enabled;
        public int toolCount;
        /** 加载/解析失败原因，null 表示正常。 */
        public String error;
        public File source;
        public List<String> toolNames = new ArrayList<String>();
    }

    /** 一次 reload 的统计，用于提示。 */
    public static final class Reload {
        public int enabledCount;
        public int toolCount;
        public int newCount;
        public final List<String> errors = new ArrayList<String>();
    }

    private final Context ctx;
    private final EventBus bus;
    private final ToolRuntime tools;
    private final PluginStore store;

    /** 已加载的插件实例，key = id。 */
    private final Map<String, Plugin> loaded = new LinkedHashMap<String, Plugin>();
    /** 全部已知插件（含停用与加载失败的），key = id。 */
    private final Map<String, Info> all = new LinkedHashMap<String, Info>();
    /** 扫描到的声明式插件。 */
    private final Map<String, PluginSpec> specs = new LinkedHashMap<String, PluginSpec>();
    /** 内置插件工厂。 */
    private final List<Plugin> builtinTemplates = new ArrayList<Plugin>();

    public PluginManager(Context ctx, EventBus bus, ToolRuntime tools) {
        this.ctx = ctx;
        this.bus = bus;
        this.tools = tools;
        this.store = new PluginStore(ctx);
        builtinTemplates.add(new A11yPlugin(bus));
        builtinTemplates.add(new FilePlugin(bus));
        builtinTemplates.add(new WebPlugin(bus));
    }

    public PluginStore store() { return store; }

    public ToolRuntime tools() { return tools; }

    /** 重新扫描目录 + 按启用状态重建全部插件工具。 */
    public Reload reload() {
        Reload stat = new Reload();

        // 1) 拆掉旧的一批（含内置）
        for (Plugin p : loaded.values()) {
            try {
                p.dispose();
            } catch (Throwable ignored) {}
            tools.unregisterPlugin(p.meta().id);
        }
        loaded.clear();
        all.clear();
        specs.clear();

        // 2) 内置插件：恒定存在，只看开关
        for (Plugin p : builtinTemplates) {
            PluginMeta m = p.meta();
            Info info = new Info();
            info.id = m.id;
            info.name = m.name;
            info.version = m.version;
            info.description = m.description;
            info.author = m.author;
            info.builtin = true;
            info.kind = "builtin";
            info.enabled = store.isEnabled(m.id);
            for (int i = 0; i < m.declarations.length(); i++) {
                org.json.JSONObject d = m.declarations.optJSONObject(i);
                if (d != null) info.toolNames.add(d.optString("name", ""));
            }
            info.toolCount = info.toolNames.size();
            all.put(info.id, info);
            if (!info.enabled) continue;

            List<String> conflicts = tools.registerFromPlugin(m.id, m.declarations);
            if (!conflicts.isEmpty()) {
                info.error = "工具名冲突：" + join(conflicts);
                info.toolCount = 0;
                stat.errors.add(info.name + "：" + info.error);
                continue;
            }
            loaded.put(info.id, p);
            stat.enabledCount++;
            stat.toolCount += info.toolCount;
        }

        // 3) 用户插件
        PluginStore.Scan scan = store.scan();
        for (String e : scan.errors) {
            stat.errors.add(e);
        }
        for (PluginSpec spec : scan.specs) {
            specs.put(spec.id, spec);
            Info info = new Info();
            info.id = spec.id;
            info.name = spec.name;
            info.version = spec.version;
            info.description = spec.description;
            info.author = spec.author;
            info.builtin = false;
            info.kind = "http";
            info.enabled = store.isEnabled(spec.id);
            info.source = spec.source;
            for (PluginToolSpec t : spec.tools) info.toolNames.add(t.name);
            info.toolCount = spec.tools.size();
            all.put(info.id, info);

            if (store.isNew(spec.id)) {
                stat.newCount++;
                store.markSeen(spec.id);
            }
            if (!info.enabled) continue;

            HttpToolPlugin p = new HttpToolPlugin(spec, bus, tools);
            try {
                p.apply();
            } catch (Throwable t) {
                p = null;
                info.error = "加载异常：" + t.getClass().getSimpleName();
            }
            if (p != null && p.error() != null) {
                info.error = p.error();
            }
            if (info.error != null) {
                tools.unregisterPlugin(spec.id);
                info.toolCount = 0;
                stat.errors.add(info.name + "：" + info.error);
                continue;
            }
            loaded.put(info.id, p);
            stat.enabledCount++;
            stat.toolCount += info.toolCount;
            bus.emit("plugin.loaded", spec.id);
        }

        loadJsPlugins(stat);

        bus.emit("plugins.reloaded", stat);
        return stat;
    }

    /** DSH 风格的 JS 插件：靠系统 WebView 执行，支持 async/await。 */
    private void loadJsPlugins(Reload stat) {
        JsPluginStore jsStore = new JsPluginStore(ctx);
        java.util.List<JsPluginStore.JsPlugin> list;
        try {
            list = jsStore.scan();
        } catch (Throwable t) {
            stat.errors.add("扫描 JS 插件失败：" + t.getClass().getSimpleName());
            return;
        }
        if (list.isEmpty()) return;

        // WebView 初始化是异步的；没好之前先把插件列出来标为「等待」，就绪后靠事件触发重载
        final boolean jsReady = JsHost.available();
        if (!jsReady) {
            JsHost.ensureAsync(ctx, new Runnable() {
                public void run() {
                    bus.emit("plugins.js.ready", null);
                }
            });
        }
        for (int i = 0; i < list.size(); i++) {
            final JsPluginStore.JsPlugin jp = list.get(i);
            final String id = "js." + jp.id;

            Info info = new Info();
            info.id = id;
            info.name = jp.name;
            info.version = jp.version;
            info.description = jp.description;
            info.author = "";
            info.builtin = false;
            info.kind = "js";
            info.source = jp.entry;
            info.enabled = store.isEnabled(id);
            all.put(id, info);

            if (jp.error != null) {
                info.error = jp.error;
                stat.errors.add(info.name + "：" + jp.error);
                continue;
            }
            if (store.isNew(id)) {
                stat.newCount++;
                store.markSeen(id);
            }
            if (!info.enabled) continue;

            if (!jsReady) {
                info.pending = true;
                continue;
            }

            String err = null;
            try {
                String src = JsPluginStore.sourceOf(jp);
                String transformed = Esm.transform(Ts.strip(src));
                err = JsHost.load(id, transformed);
                if (err == null) {
                    org.json.JSONArray tools = JsHost.describe(id);
                    for (int k = 0; k < tools.length(); k++) {
                        org.json.JSONObject t = tools.optJSONObject(k);
                        if (t == null) continue;
                        final String toolName = t.optString("name", "");
                        if (toolName.length() == 0) continue;
                        org.json.JSONObject schema = Schema.toJsonSchema(t.optJSONObject("parameters"));
                        final String pid = id;
                        tools_runtime_register(pid, toolName, t.optString("description", toolName),
                                schema, new ToolExecutor() {
                                    public String execute(org.json.JSONObject args) throws Exception {
                                        return JsHost.call(pid, toolName, args, 30000);
                                    }
                                });
                        info.toolNames.add(toolName);
                    }
                    info.toolCount = info.toolNames.size();
                }
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + "：" + t.getMessage();
            }
            if (err != null) {
                info.error = err;
                info.toolCount = 0;
                tools.unregisterPlugin(id);
                JsHost.unload(id);
                stat.errors.add(info.name + "：" + err);
                continue;
            }
            stat.enabledCount++;
            stat.toolCount += info.toolCount;
        }
    }

    private void tools_runtime_register(String pluginId, String name, String desc,
                                        org.json.JSONObject schema, ToolExecutor exec) {
        tools.registerTool(pluginId, name, desc, schema, exec);
    }

    /** 开关一个插件；返回 null 表示成功。 */
    public String setEnabled(String id, boolean on) {
        Info info = all.get(id);
        if (info == null) return "没有 id 为 " + id + " 的插件";
        store.setEnabled(id, on);
        Reload r = reload();
        if (!on) return null;
        if (r.errors.isEmpty()) return null;
        Info after = all.get(id);
        return after == null ? null : after.error;
    }

    /** 重新读一遍文件（用户改了 plugin.json 之后）。 */
    public Reload rescan() {
        return reload();
    }

    public List<Info> list() {
        return new ArrayList<Info>(all.values());
    }

    public Info info(String id) {
        return all.get(id);
    }

    public PluginSpec spec(String id) {
        return specs.get(id);
    }

    public int loadedCount() {
        return loaded.size();
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append("；");
            sb.append(l.get(i));
        }
        return sb.toString();
    }
}
