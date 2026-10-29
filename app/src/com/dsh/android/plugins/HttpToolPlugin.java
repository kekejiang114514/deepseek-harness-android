package com.dsh.android.plugins;

import com.dsh.android.kernel.EventBus;
import com.dsh.android.tools.ToolRuntime;

/**
 * 由 plugin.json 驱动的插件：把声明的每个工具注册成一个 HTTP 调用。
 *
 * 和内置插件的区别：内置插件用固定 type 复用内置执行器；这里的每个工具都自带
 * 请求模板，执行器是 PluginHttpExecutor，所以新增插件不需要改代码。
 */
public final class HttpToolPlugin extends Plugin {

    private final ToolRuntime tools;
    private final PluginSpec spec;
    /** apply() 失败原因；null 表示成功。 */
    private String error;

    public HttpToolPlugin(PluginSpec spec, EventBus bus, ToolRuntime tools) {
        super(new PluginMeta(spec.id, spec.name, spec.version,
                spec.description, spec.author, spec.declarations()), bus);
        this.spec = spec;
        this.tools = tools;
    }

    public PluginSpec spec() { return spec; }

    public String error() { return error; }

    @Override
    public void apply() {
        error = null;
        // 先整体查冲突，避免注册一半再回滚
        for (PluginToolSpec t : spec.tools) {
            String owner = tools.ownerOf(t.name);
            if (owner != null && !owner.equals(spec.id)) {
                error = "工具名 " + t.name + " 已被 " + owner + " 占用";
                return;
            }
        }
        for (int i = 0; i < spec.tools.size(); i++) {
            PluginToolSpec t = spec.tools.get(i);
            tools.registerTool(spec.id, t.name, t.description, t.parameters,
                    new PluginHttpExecutor(spec, t));
        }
    }

    @Override
    public void dispose() {
        tools.unregisterPlugin(spec.id);
    }
}
