package com.dsh.android.ui;

import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dsh.android.kernel.EventBus;
import com.dsh.android.plugins.PluginHub;
import com.dsh.android.plugins.PluginManager;
import com.dsh.android.plugins.PluginSample;
import com.dsh.android.plugins.PluginStore;
import com.dsh.android.plugins.PluginSpec;
import com.dsh.android.plugins.PluginToolSpec;
import com.dsh.android.tools.ToolRuntime;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 设置页的插件区。
 *
 * 单独拆一个类是为了不让 SettingsActivity 继续膨胀（已经 1000 行了），
 * 这里只依赖 PluginManager / PluginStore 暴露的接口，UI 复用宿主的方法。
 */
final class PluginSection {

    /** 宿主提供的能力。 */
    interface Host {
        PluginManager pluginMgr();
        LinearLayout pluginHost();
        int dp(float v);
        void toast(String s);
        void rebuildPlugins(boolean animate);
    }

    private final SettingsActivity a;
    private final Host host;

    PluginSection(SettingsActivity a, Host host) {
        this.a = a;
        this.host = host;
    }

    // ---------- 渲染 ----------

    void rebuild(boolean animate) {
        LinearLayout box = host.pluginHost();
        if (box == null) return;
        box.removeAllViews();

        PluginManager m = host.pluginMgr();
        List<PluginManager.Info> list = m.list();
        if (list.isEmpty()) {
            TextView t = new TextView(a);
            t.setText("还没有任何插件。点上面的「查看插件格式示例」可以写入一个能直接跑的示例。");
            t.setTextSize(12);
            t.setTextColor(Theme.caption());
            t.setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(14));
            box.addView(t);
            return;
        }

        for (int i = 0; i < list.size(); i++) {
            PluginManager.Info info = list.get(i);
            View div = new View(a);
            div.setBackgroundColor(Theme.border());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, host.dp(1)));
            lp.setMargins(host.dp(16), 0, host.dp(16), 0);
            div.setLayoutParams(lp);
            box.addView(div);
            box.addView(row(info));
        }
        if (animate) {
            box.setAlpha(0f);
            box.animate().alpha(1f).setDuration(160).start();
        }
    }

    private View row(final PluginManager.Info info) {
        final LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(12));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(pressable());

        TextView title = new TextView(a);
        String badge = info.builtin ? "  · 内置" : ("js".equals(info.kind) ? "  · JS" : "");
        title.setText(info.name + "  v" + info.version + badge);
        title.setTextSize(15);
        title.setTextColor(Theme.text());
        col.addView(title);

        TextView sub = new TextView(a);
        sub.setText(subtitle(info));
        sub.setTextSize(12);
        sub.setTextColor(info.error != null ? Theme.warning() : Theme.caption());
        sub.setPadding(0, host.dp(3), 0, 0);
        col.addView(sub);

        col.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { detail(info); }
        });
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Toggle sw = new Toggle(a);
        sw.setChecked(info.enabled);
        sw.setOnChangeListener(new Toggle.OnChange() {
            public void onChanged(boolean checked) {
                String err = host.pluginMgr().setEnabled(info.id, checked);
                host.rebuildPlugins(true);
                if (checked && err != null) host.toast("启用失败：" + err);
            }
        });
        r.addView(sw);
        return r;
    }

    private static String subtitle(PluginManager.Info info) {
        if (info.error != null) return "加载失败：" + info.error;
        if (info.pending) return "等待 JS 运行时就绪…";
        if ("js".equals(info.kind) && info.toolCount == 0) {
            return "没有注册任何工具（只支持 ctx.tools.register 的插件）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(info.toolCount).append(" 个工具");
        if (!info.builtin && info.author != null && info.author.length() > 0) {
            sb.append(" · ").append(info.author);
        }
        if (!info.enabled) sb.insert(0, "已停用 · ");
        return sb.toString();
    }

    private android.graphics.drawable.Drawable pressable() {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            if (tv.resourceId != 0) return a.getResources().getDrawable(tv.resourceId);
        } catch (Throwable ignored) {}
        return null;
    }

    // ---------- 详情 ----------

    private void detail(PluginManager.Info info) {
        StringBuilder sb = new StringBuilder();
        sb.append(info.description == null || info.description.length() == 0
                ? "（没有写描述）" : info.description);
        sb.append("\n\nid：").append(info.id);
        sb.append("\n版本：").append(info.version);
        if (info.author != null && info.author.length() > 0) {
            sb.append("\n作者：").append(info.author);
        }
        if (info.builtin) {
            sb.append("\n来源：内置");
        } else if ("js".equals(info.kind)) {
            sb.append("\n来源：DSH JS 插件（实验性）");
            sb.append("\n支持：export function apply(ctx) + ctx.tools.register");
            sb.append("\n不支持：ctx.on / ctx.plugin / ctx.service、Node 模块");
        } else if (info.source != null) {
            sb.append("\n来源：").append(info.source.getAbsolutePath());
        }
        if (info.error != null) {
            sb.append("\n\n⚠ 加载失败：").append(info.error);
        }
        sb.append("\n\n工具：");
        if (info.toolNames.isEmpty()) {
            sb.append("（无）");
        } else {
            for (int i = 0; i < info.toolNames.size(); i++) {
                sb.append("\n· ").append(info.toolNames.get(i));
            }
        }
        PluginSpec spec = host.pluginMgr().spec(info.id);
        if (spec != null && spec.baseUrl.length() > 0) {
            sb.append("\n\n接口：").append(spec.baseUrl);
        }
        if (spec != null) {
            sb.append("\n请求方式：");
            for (int i = 0; i < spec.tools.size(); i++) {
                PluginToolSpec t = spec.tools.get(i);
                if (i > 0) sb.append("；");
                sb.append(t.method).append(' ').append(t.name);
            }
        }

        TextView body = new TextView(a);
        body.setText(sb.toString());
        body.setTextSize(13);
        body.setTextColor(Theme.text());
        body.setPadding(host.dp(20), host.dp(8), host.dp(20), 0);
        body.setTextIsSelectable(true);

        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(body);

        new AppDialog.Builder(a)
                .setTitle(info.name)
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---------- 示例 ----------

    void showSample() {
        final TextView body = new TextView(a);
        body.setText(PluginSample.CHEATSHEET + "\n\n──────────\n\n" + PluginSample.JSON);
        body.setTextSize(11);
        body.setTextColor(Theme.text());
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(host.dp(16), host.dp(10), host.dp(16), host.dp(10));

        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(body);

        new AppDialog.Builder(a)
                .setTitle("插件格式")
                .setView(sv)
                .setPositiveButton("写入示例到插件目录", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { writeSample(); }
                })
                .setNegativeButton("复制", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) a.getSystemService(
                                        android.content.Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("plugin", PluginSample.JSON));
                        host.toast("示例 JSON 已复制，可以粘到自己的 plugin.json 里改");
                    }
                })
                .show();
    }

    /** 把示例落盘到插件目录，让用户能立刻看到一个能跑的插件。 */
    private void writeSample() {
        PluginStore store = host.pluginMgr().store();
        File dir = store.ensureDirs();
        if (dir == null) {
            host.toast("没有可写的插件目录，请检查存储权限");
            return;
        }
        File target = new File(new File(dir, "示例"), "plugin.json");
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(target);
            try {
                out.write(PluginSample.JSON.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            host.pluginMgr().rescan();
            host.rebuildPlugins(true);
            host.toast("已写入 " + target.getAbsolutePath());
        } catch (Throwable t) {
            host.toast("写入失败：" + t.getMessage());
        }
    }
}
