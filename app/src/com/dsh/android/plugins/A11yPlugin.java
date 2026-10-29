package com.dsh.android.plugins;

import com.dsh.android.kernel.EventBus;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 内置无障碍插件：读屏、点击、长按、双击、滑动、捏合、输入、系统导航。
 * 全部受「无障碍开关」总控，用户可随时关闭。
 */
public final class A11yPlugin extends Plugin {

    public A11yPlugin(EventBus bus) {
        super(builtinMeta(), bus);
    }

    private static PluginMeta builtinMeta() {
        JSONArray t = new JSONArray();
        try {
            add(t, "a11y.dump", "a11y.dump",
                "读取当前屏幕的无障碍节点树（文本、可点击/可编辑/可滚动、边界坐标）。这是了解当前界面内容的第一步。",
                "{\"type\":\"object\",\"properties\":{}}");

            add(t, "a11y.click", "a11y.click",
                "点击指定索引的节点（索引来自 a11y.dump）。",
                "{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"}},\"required\":[\"index\"]}");

            add(t, "a11y.longclick", "a11y.longclick",
                "长按指定索引的节点。",
                "{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"}},\"required\":[\"index\"]}");

            add(t, "a11y.doubleclick", "a11y.doubleclick",
                "双击指定索引的节点。",
                "{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"}},\"required\":[\"index\"]}");

            add(t, "a11y.tap", "a11y.tap",
                "按屏幕坐标点击（像素）。当节点没有 clickable 时使用。",
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\"]}");

            add(t, "a11y.longpress", "a11y.longpress",
                "按屏幕坐标长按（像素）。",
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\"]}");

            add(t, "a11y.swipe", "a11y.swipe",
                "从(x1,y1)滑动到(x2,y2)，duration 为毫秒。可用于翻页、滑动列表。",
                "{\"type\":\"object\",\"properties\":{\"x1\":{\"type\":\"integer\"},\"y1\":{\"type\":\"integer\"},\"x2\":{\"type\":\"integer\"},\"y2\":{\"type\":\"integer\"},\"duration\":{\"type\":\"integer\"}},\"required\":[\"x1\",\"y1\",\"x2\",\"y2\"]}");

            add(t, "a11y.pinch", "a11y.pinch",
                "屏幕中央双指捏合缩放。scale>1 放大，scale<1 缩小（如看图片、地图）。",
                "{\"type\":\"object\",\"properties\":{\"scale\":{\"type\":\"number\"},\"duration\":{\"type\":\"integer\"}},\"required\":[\"scale\"]}");

            add(t, "a11y.scroll", "a11y.scroll",
                "滚动屏幕。direction: forward(向下) / backward(向上)。可选 index 指定滚动容器。",
                "{\"type\":\"object\",\"properties\":{\"direction\":{\"type\":\"string\",\"enum\":[\"forward\",\"backward\"]},\"index\":{\"type\":\"integer\"}}}");

            add(t, "a11y.findtext", "a11y.findtext",
                "在屏幕上查找包含指定文字的节点，返回其索引（便于后续点击）。",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}");

            add(t, "a11y.settext", "a11y.settext",
                "在指定索引的可编辑字段输入文本（覆盖原内容）。",
                "{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"},\"text\":{\"type\":\"string\"}},\"required\":[\"index\",\"text\"]}");

            add(t, "a11y.cleartext", "a11y.cleartext",
                "清空指定索引输入框的内容。",
                "{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"}},\"required\":[\"index\"]}");

            add(t, "a11y.enter", "a11y.enter",
                "在指定输入框触发回车/发送动作。",
                "{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"}},\"required\":[\"index\"]}");

            add(t, "a11y.back", "a11y.back", "执行系统返回。", "{\"type\":\"object\",\"properties\":{}}");
            add(t, "a11y.home", "a11y.home", "回到系统桌面。", "{\"type\":\"object\",\"properties\":{}}");
            add(t, "a11y.recents", "a11y.recents", "打开最近任务列表。", "{\"type\":\"object\",\"properties\":{}}");
            add(t, "a11y.notifications", "a11y.notifications", "下拉通知栏。", "{\"type\":\"object\",\"properties\":{}}");

            add(t, "app.open", "app.open",
                "按包名启动已安装的 App，例如 com.android.settings。",
                "{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"}},\"required\":[\"package\"]}");

            add(t, "notify", "notify", "发送一条系统通知。",
                "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return new PluginMeta("builtin.a11y", "无障碍操作", "1.1.0",
            "内置：读取屏幕并模拟点击、长按、双击、滑动、捏合、输入与系统导航。", "DShAndroid", t);
    }

    private static void add(JSONArray arr, String name, String type, String desc, String schema) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("type", type);
        o.put("description", desc);
        o.put("parameters", schema == null ? new JSONObject().put("type", "object") : new JSONObject(schema));
        arr.put(o);
    }

    @Override
    public void apply() {}
}
