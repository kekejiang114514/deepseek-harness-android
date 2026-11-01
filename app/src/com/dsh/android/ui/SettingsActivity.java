package com.dsh.android.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.android.a11y.A11yService;
import com.dsh.android.kernel.C;
import com.dsh.android.kernel.Updater;
import com.dsh.android.llm.LlmClient;
import com.dsh.android.kernel.EventBus;
import com.dsh.android.plugins.PluginHub;
import com.dsh.android.plugins.PluginManager;
import com.dsh.android.plugins.PluginSample;
import com.dsh.android.plugins.PluginStore;
import com.dsh.android.plugins.PluginSpec;
import com.dsh.android.plugins.PluginToolSpec;
import com.dsh.android.tools.ToolRuntime;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页。
 *
 * 设计要点：
 * 1. 模型列表不预设，一律从 /models 接口读取；没有 API Key 时不出模型选项。
 * 2. 填完 Key（或改接口地址）立即自动拉取，不需要额外点「验证」。
 * 3. 任何选项改动都只更新对应那一行的文字 + 局部动画，不 recreate() 整页。
 */
public final class SettingsActivity extends Activity implements PluginSection.Host {

    private static final String K_MODELS_CACHE = "models_cache";

    private SharedPreferences sp;
    private static final int REQ_STORAGE = 1001;
    private boolean pendingDeviceScope = false;
    private LinearLayout scopeShell;

    /** 模型区容器：内容随「有无 Key / 是否读取中」动态重建。 */
    private LinearLayout modelHost;
    private boolean fetching = false;
    private String fetchError = null;

    private PluginSection pluginSection;

    /** 无障碍未开启时的说明位置（跟随连接状态重建）。 */
    private LinearLayout a11yHintHost;

    /** 插件区：内容随扫描结果动态重建。 */
    private PluginManager pluginMgr;
    private LinearLayout pluginHost;

    /** 已绑定到某个配置项的显示行，改值后只刷新这些行。 */
    private final List<BoundRow> bound = new ArrayList<BoundRow>();

    private interface Fmt { String get(); }

    private static final class BoundRow {
        final String key;
        final TextView view;
        final Fmt fmt;
        BoundRow(String key, TextView view, Fmt fmt) {
            this.key = key; this.view = view; this.fmt = fmt;
        }
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 去掉系统 ActionBar，只保留页面内自绘的返回栏
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.app.ActionBar ab = getActionBar();
        if (ab != null) ab.hide();

        Theme.init(this);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Theme.bg()));
        sp = getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE);
        pluginMgr = PluginHub.get();
        pluginSection = new PluginSection(this, this);
        setContentView(build());

        // 已有 Key 但列表为空（比如旧版本升级上来）：进页面静默读一次
        if (keyValue().length() > 0 && cachedModels().isEmpty()) {
            fetchModels(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshValue("__a11y", false);
        refreshA11yHint();
    }

    /** 无障碍没开时在开关行下面常驻一段说明，开了就收起来。 */
    private void refreshA11yHint() {
        if (a11yHintHost == null) return;
        a11yHintHost.removeAllViews();

        int st = com.dsh.android.a11y.A11yState.of(this);

        if (st == com.dsh.android.a11y.A11yState.READY) {
            a11yHintHost.addView(divider());
            a11yHintHost.addView(hintRow("无障碍服务已连接，屏幕操作可用。"));
        } else if (st == com.dsh.android.a11y.A11yState.NOT_LISTED) {
            a11yHintHost.addView(divider());
            a11yHintHost.addView(hintRow("系统没有列出本应用的无障碍服务，所以无障碍列表里找不到可以打开的开关。"
                    + "下面几个入口按顺序试，最后一项会生成一份逐项检查结果。"));
        } else if (st == com.dsh.android.a11y.A11yState.ON_NOT_CONNECTED) {
            a11yHintHost.addView(divider());
            a11yHintHost.addView(hintRow("系统里已经打开了这个服务，但当前还没连接上。"
                    + "通常再等一下就好；一直不连的话，把本应用从最近任务里划掉重开一次。"));
        } else {
            a11yHintHost.addView(divider());
            a11yHintHost.addView(hintRow("屏幕操作依赖系统的无障碍服务，需要你在系统设置里开启一次。"
                    + "点上面那一行会直接跳到本应用的开关页。"));
        }

        // 下面这些在所有未连接状态下都提供，顺序＝建议尝试的顺序
        if (st != com.dsh.android.a11y.A11yState.READY) {
            a11yHintHost.addView(actionRow("打开无障碍设置", new View.OnClickListener() {
                public void onClick(View v) {
                    if (!A11yGuide.openSettings(SettingsActivity.this)) {
                        toast("无法打开系统设置，请手动进入 设置 → 无障碍");
                    }
                }
            }));
            a11yHintHost.addView(actionRow("打开应用信息（部分系统需先解除限制）", new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        startActivity(com.dsh.android.a11y.A11yState.appDetailsIntent(SettingsActivity.this));
                    } catch (Exception e) {
                        toast("无法打开应用信息页");
                    }
                }
            }));
            a11yHintHost.addView(actionRow("尝试用 root 直接开启", new View.OnClickListener() {
                public void onClick(View v) { tryRootEnable(); }
            }));
        }

        a11yHintHost.addView(actionRow("复制无障碍诊断信息", new View.OnClickListener() {
            public void onClick(View v) {
                String d = com.dsh.android.a11y.A11yState.selfTest(SettingsActivity.this)
                        + "\n----\n"
                        + com.dsh.android.a11y.A11yAdmin.diagnostics(SettingsActivity.this);
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("a11y", d));
                toast("已复制，粘贴发给开发者即可定位问题");
            }
        }));
    }

    // ------------------------------------------------------------ 布局

    /** 0 = 根菜单，其余为子页。 */
    private int page = 0;
    private boolean keyVisible = false;

    /** 外壳只建一次，切页时只换内容，避免整窗重建导致闪一下。 */
    private TextView titleView;
    private LinearLayout bodyBox;

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg());
        root.addView(topBar());

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        bodyBox = new LinearLayout(this);
        bodyBox.setOrientation(LinearLayout.VERTICAL);
        bodyBox.setPadding(dp(16), dp(4), dp(16), dp(40));
        sv.addView(bodyBox);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        fillBody();
        return root;
    }

    /** 只重建内容区，标题栏与窗口都不动。 */
    private void fillBody() {
        bound.clear();
        if (bodyBox == null) return;
        bodyBox.removeAllViews();
        if (page == 0) pageRoot(bodyBox);
        else if (page == 1) pageApi(bodyBox);
        else if (page == 2) pageTools(bodyBox);
        else if (page == 3) pagePlugins(bodyBox);
        else if (page == 4) pageAppearance(bodyBox);
        else if (page == 6) pageLicense(bodyBox);
        else if (page == 7) pageLanguage(bodyBox);
        else pageAbout(bodyBox);
    }

    /** 根菜单：五个分组入口。 */
    private void pageRoot(LinearLayout body) {
        body.addView(sectionLabel("设置"));
        LinearLayout c = card();
        c.addView(menuRow("接口与密钥", "接口地址、API Key、模型", 1));
        c.addView(divider());
        c.addView(menuRow("工具能力", "屏幕操作", 2));
        c.addView(divider());
        c.addView(menuRow("插件", "本地插件、插件市场", 3));
        c.addView(divider());
        c.addView(menuRow("外观", "主题", 4));
        c.addView(divider());
        c.addView(menuRow("语言", "界面语言与文字方向", 7));
        c.addView(divider());
        c.addView(menuRow("关于", "版本、更新、作者、开源许可", 5));
        body.addView(c);
    }

    private View menuRow(final String title, String sub, final int target) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        r.setBackground(pressable());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(Theme.text());
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12);
        s.setTextColor(Theme.caption());
        s.setPadding(0, dp(3), 0, 0);
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("\u203A");
        arrow.setTextSize(18);
        arrow.setTextColor(Theme.text3());
        r.addView(arrow);

        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { gotoPage(target); }
        });
        return r;
    }

    private void gotoPage(int target) {
        page = target;
        if (titleView != null) titleView.setText(pageTitle());
        fillBody();
        bodyBox.setAlpha(0f);
        bodyBox.setTranslationX(dp(10));
        bodyBox.animate().alpha(1f).translationX(0).setDuration(170).start();
    }

    // ------------------------------------------------------------ 接口与密钥

    private void pageApi(LinearLayout body) {
        body.addView(sectionLabel("接口"));
        LinearLayout apiCard = card();

        apiCard.addView(fieldLabel("接口地址"));
        final EditText etBase = fieldInput(sp.getString(C.KEY_BASE_URL, ""), false, C.DEFAULT_BASE_URL);
        apiCard.addView(etBase);

        apiCard.addView(divider());
        apiCard.addView(fieldLabel("API Key"));
        final EditText etKey = fieldInput(sp.getString(C.KEY_API_KEY, ""), true, "sk-...");
        apiCard.addView(etKey);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        btnRow.setPadding(dp(14), 0, dp(14), dp(12));

        final TextView toggle = new TextView(this);
        toggle.setText(keyVisible ? "隐藏" : "显示");
        toggle.setTextSize(12);
        toggle.setTextColor(Theme.link());
        toggle.setPadding(dp(2), dp(8), dp(14), dp(8));
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                keyVisible = !keyVisible;
                etKey.setInputType(InputType.TYPE_CLASS_TEXT
                        | (keyVisible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                      : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                toggle.setText(keyVisible ? "隐藏" : "显示");
            }
        });
        btnRow.addView(toggle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView save = new TextView(this);
        save.setText("保存");
        save.setTextSize(13);
        save.setTextColor(Theme.onPrimary());
        save.setPadding(dp(22), dp(8), dp(22), dp(8));
        save.setBackground(Theme.round(Theme.primary(), Theme.R_LG, this));
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String base = etBase.getText().toString().trim();
                String key = etKey.getText().toString().trim();
                sp.edit().putString(C.KEY_BASE_URL,
                        base.length() == 0 ? C.DEFAULT_BASE_URL : base).apply();
                sp.edit().putString(C.KEY_API_KEY, key).apply();
                toast("已保存");
                if (key.length() > 0) fetchModels(false);
            }
        });
        btnRow.addView(save);
        apiCard.addView(btnRow);
        body.addView(apiCard);

        body.addView(sectionLabel("模型"));
        LinearLayout modelCard = card();
        modelHost = new LinearLayout(this);
        modelHost.setOrientation(LinearLayout.VERTICAL);
        modelCard.addView(modelHost);
        modelCard.addView(divider());
        modelCard.addView(actionRow("重新读取模型列表", new View.OnClickListener() {
            public void onClick(View v) { fetchModels(true); }
        }));
        body.addView(modelCard);
        rebuildModelHost(false);

        body.addView(hintRow("模型与推理强度在聊天页标题下方切换。"));
    }

    private TextView fieldLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(Theme.caption());
        t.setPadding(dp(14), dp(12), dp(14), dp(2));
        return t;
    }

    private EditText fieldInput(String value, boolean secret, String hint) {
        EditText et = new EditText(this);
        et.setText(value);
        et.setHint(hint);
        et.setTextSize(14);
        et.setSingleLine(true);
        et.setTextColor(Theme.text());
        et.setHintTextColor(Theme.caption());
        et.setPadding(dp(14), dp(11), dp(14), dp(11));
        et.setBackground(Theme.round(Theme.surface2(), Theme.R_LG, this));
        et.setInputType(InputType.TYPE_CLASS_TEXT
                | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                          : InputType.TYPE_TEXT_VARIATION_URI));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(14), dp(4), dp(14), dp(6));
        et.setLayoutParams(lp);
        return et;
    }


    // ------------------------------------------------------------ 更新

    private TextView updateStatusView;
    /** 检查中标志：避免连点触发多次请求。 */
    private boolean checking = false;

    private void setUpdateStatus(String text) {
        if (updateStatusView != null) updateStatusView.setText(text);
    }

    // ------------------------------------------------------------ 工具能力

    private void pageTools(LinearLayout body) {
        // ---- 工具能力 ----
        body.addView(sectionLabel("工具能力"));
        LinearLayout toolCard = card();
        toolCard.addView(switchRow("屏幕操作（无障碍）", "允许 Agent 读取和操作屏幕",
                sp.getBoolean(C.KEY_A11Y_ENABLED, true),
                new CompoundCallback() {
                    public void onChanged(boolean checked) {
                        sp.edit().putBoolean(C.KEY_A11Y_ENABLED, checked).apply();
                    }
                }));
        toolCard.addView(divider());
        toolCard.addView(row("开启屏幕操作", "__a11y",
                new Fmt() {
                    public String get() {
                        return com.dsh.android.a11y.A11yState.describe(SettingsActivity.this);
                    }
                },
                new View.OnClickListener() {
                    public void onClick(View v) {
                        int st = com.dsh.android.a11y.A11yState.of(SettingsActivity.this);
                        if (st == com.dsh.android.a11y.A11yState.NOT_LISTED) {
                            // 服务没被系统列出：无障碍页里根本没有这个开关，先去解除受限设置
                            A11yGuide.showDialog(SettingsActivity.this, false);
                            return;
                        }
                        if (!A11yGuide.openSettings(SettingsActivity.this)) {
                            toast("无法打开系统设置，请手动进入 设置 → 无障碍");
                        }
                    }
                }, true));
        a11yHintHost = new LinearLayout(this);
        a11yHintHost.setOrientation(LinearLayout.VERTICAL);
        toolCard.addView(a11yHintHost);
        body.addView(toolCard);
    }

    // ------------------------------------------------------------ 插件

    private void pagePlugins(LinearLayout body) {
        // ---- 插件 ----
        body.addView(sectionLabel("插件"));
        LinearLayout pluginCard = card();
        pluginCard.addView(hintRow("把 plugin.json 放进下面这个目录，工具就会出现在对话里。"
                + "一个插件一个文件夹，或者直接放单个 .json 文件都可以。"));
        pluginCard.addView(divider());
        pluginCard.addView(actionRow("插件目录 · 点击复制路径", new View.OnClickListener() {
            public void onClick(View v) {
                String p = pluginMgr().store().primaryPath();
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("path", p));
                toast("已复制：" + p);
            }
        }));
        pluginCard.addView(divider());
        pluginCard.addView(actionRow("重新扫描插件", new View.OnClickListener() {
            public void onClick(View v) { rescanPlugins(); }
        }));
        pluginCard.addView(divider());
        pluginCard.addView(actionRow("打开插件市场", new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(SettingsActivity.this, MarketActivity.class));
                } catch (Exception e) {
                    toast("无法打开插件市场");
                }
            }
        }));
        pluginCard.addView(divider());
        pluginCard.addView(actionRow("查看插件格式示例", new View.OnClickListener() {
            public void onClick(View v) { showPluginSample(); }
        }));
        pluginHost = new LinearLayout(this);
        pluginHost.setOrientation(LinearLayout.VERTICAL);
        pluginCard.addView(pluginHost);
        body.addView(pluginCard);
        pluginSection.rebuild(false);
        pluginSection.rebuild(false);
    }

    // ------------------------------------------------------------ 外观

    private void pageAppearance(LinearLayout body) {
        // ---- 外观 ----
        body.addView(sectionLabel("外观"));
        LinearLayout uiCard = card();
        uiCard.addView(segmentRow("主题",
                new HintFn() {
                    public String get() { return "跟随系统时随系统深色模式自动切换"; }
                },
                new ValueIO() {
                    public String get() { return Theme.mode(SettingsActivity.this); }
                    public void set(String k) {
                        Theme.setMode(SettingsActivity.this, k);
                        // 主题要重绘所有颜色：等这次点击画完再整体淡入淡出重建
                        SettingsActivity.this.findViewById(android.R.id.content)
                                .post(new Runnable() {
                                    public void run() { applyThemeReveal(); }
                                });
                    }
                },
                new String[]{Theme.MODE_SYSTEM, Theme.MODE_LIGHT, Theme.MODE_DARK},
                new String[]{"跟随系统", "浅色", "深色"}, null));
        body.addView(uiCard);
    }

    private void checkUpdate() {
        if (checking) return;
        checking = true;
        setUpdateStatus("正在检查…");
        Updater.checkAsync(this, new Updater.Callback() {
            public void onResult(Updater.Release r, String error) {
                checking = false;
                setUpdateStatus(error != null ? ("检查失败：" + error)
                        : (r == null ? "已是最新版本" : "有新版本：" + r.version));
                if (error != null) {
                    toast(error);
                } else if (r != null) {
                    showUpdate(r);
                } else {
                    toast("已是最新版本");
                }
            }
        });
    }

    /** 更新提示统一走公共卡片。 */
    private void showUpdate(final Updater.Release r) {
        UpdateSheet.show(this, r);
    }

    private void open(String url) {
        if (url == null || url.length() == 0) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            toast("没有可用的浏览器");
        }
    }

    // ------------------------------------------------------------ 关于

    private void pageAbout(LinearLayout body) {
        // ---- 版本 ----
        body.addView(sectionLabel("版本"));
        LinearLayout verCard = card();
        verCard.addView(row("当前版本", null, new Fmt() {
            public String get() { return C.VERSION; }
        }, null, false));
        body.addView(verCard);

        // ---- 更新 ----
        body.addView(sectionLabel("更新"));
        LinearLayout upCard = card();
        updateStatusView = new TextView(this);
        updateStatusView.setTextSize(13);
        updateStatusView.setTextColor(Theme.text());
        updateStatusView.setPadding(dp(16), dp(14), dp(16), dp(14));
        updateStatusView.setText(Updater.lastCheckedText(this));
        upCard.addView(updateStatusView);
        upCard.addView(divider());
        upCard.addView(actionRow("检查更新", new View.OnClickListener() {
            public void onClick(View v) { checkUpdate(); }
        }));
        upCard.addView(divider());
        upCard.addView(switchRow("自动检查更新", "每次打开应用时查一次，每天最多一次",
                Updater.autoEnabled(this), new CompoundCallback() {
                    public void onChanged(boolean checked) {
                        Updater.setAutoEnabled(SettingsActivity.this, checked);
                    }
                }));
        upCard.addView(divider());
        upCard.addView(switchRow("接收 Beta 版本", "开启后，测试版本也会提示更新",
                Updater.betaEnabled(this), new CompoundCallback() {
                    public void onChanged(boolean checked) {
                        Updater.setBetaEnabled(SettingsActivity.this, checked);
                    }
                }));
        body.addView(upCard);

        // ---- 开源 ----
        body.addView(sectionLabel("开源"));
        LinearLayout osCard = card();
        osCard.addView(hintRow("本项目以 MIT 协议开源，构建脚本随源码一起提供，可以自己跑一遍再签名。"));
        osCard.addView(divider());
        osCard.addView(actionRow("源码仓库", new View.OnClickListener() {
            public void onClick(View v) { open(com.dsh.android.kernel.About.REPO); }
        }));
        osCard.addView(divider());
        osCard.addView(actionRow("开源许可", new View.OnClickListener() {
            public void onClick(View v) { gotoPage(6); }
        }));
        body.addView(osCard);

        // ---- 作者 ----
        body.addView(sectionLabel("作者"));
        LinearLayout auCard = card();
        auCard.addView(hintRow("问题反馈与建议请走仓库 issue，能最快被看到。"));
        auCard.addView(divider());
        auCard.addView(actionRow("GitHub", new View.OnClickListener() {
            public void onClick(View v) { open(com.dsh.android.kernel.About.GITHUB); }
        }));
        auCard.addView(divider());
        auCard.addView(actionRow("提交 issue", new View.OnClickListener() {
            public void onClick(View v) { open(com.dsh.android.kernel.About.ISSUES); }
        }));
        body.addView(auCard);
    }

    // ------------------------------------------------------------ 语言

    private void pageLanguage(LinearLayout body) {
        java.util.List<com.dsh.android.kernel.Lang.Item> items = com.dsh.android.kernel.Lang.all();
        String sel = com.dsh.android.kernel.Lang.selected(this);
        String sys = com.dsh.android.kernel.Lang.systemTag(this);

        body.addView(sectionLabel("界面语言"));
        LinearLayout c = card();
        c.addView(langRow(null, "跟随系统", systemLabel(sys), sel == null
                || com.dsh.android.kernel.Lang.SYSTEM.equals(sel)));
        body.addView(c);

        // 已完整翻译
        body.addView(sectionLabel("已完整翻译"));
        LinearLayout full = card();
        boolean firstFull = true;
        for (int i = 0; i < items.size(); i++) {
            com.dsh.android.kernel.Lang.Item it = items.get(i);
            if (it.level != com.dsh.android.kernel.Lang.SUPPORTED) continue;
            if (!firstFull) full.addView(divider());
            firstFull = false;
            full.addView(langRow(it.code, it.native_ + "　" + it.zh,
                    it.rtl ? "从右到左" : "", it.code.equals(sel)));
        }
        body.addView(full);

        // 部分翻译
        body.addView(sectionLabel("部分翻译"));
        LinearLayout part = card();
        boolean firstPart = true;
        for (int i = 0; i < items.size(); i++) {
            com.dsh.android.kernel.Lang.Item it = items.get(i);
            if (it.level != com.dsh.android.kernel.Lang.PARTIAL) continue;
            if (!firstPart) part.addView(divider());
            firstPart = false;
            part.addView(langRow(it.code, it.native_ + "　" + it.zh,
                    it.rtl ? "从右到左" : "", it.code.equals(sel)));
        }
        body.addView(part);

        // 其余语言
        body.addView(sectionLabel("其他语言（界面为英文）"));
        LinearLayout rest = card();
        boolean firstRest = true;
        for (int i = 0; i < items.size(); i++) {
            com.dsh.android.kernel.Lang.Item it = items.get(i);
            if (it.level != com.dsh.android.kernel.Lang.FALLBACK) continue;
            if (!firstRest) rest.addView(divider());
            firstRest = false;
            rest.addView(langRow(it.code, it.native_ + "　" + it.zh,
                    it.rtl ? "从右到左" : "", it.code.equals(sel)));
        }
        body.addView(rest);

        body.addView(hintRow(Texts.t(this, "lang_note")));
    }

    private String systemLabel(String tag) {
        com.dsh.android.kernel.Lang.Item it = com.dsh.android.kernel.Lang.byCode(tag);
        if (it != null) return it.native_ + "　" + it.zh;
        return tag;
    }

    /** 一行语言；code 为 null 表示「跟随系统」。 */
    private View langRow(final String code, String title, String sub, boolean selected) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        r.setBackground(pressable());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(selected ? Theme.link() : Theme.text());
        col.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(11);
            s.setTextColor(Theme.caption());
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (selected) {
            TextView check = new TextView(this);
            check.setText("\u2713");
            check.setTextSize(15);
            check.setTextColor(Theme.link());
            r.addView(check);
        }

        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                com.dsh.android.kernel.Lang.setSelected(SettingsActivity.this,
                        code == null ? com.dsh.android.kernel.Lang.SYSTEM : code);
                Theme.init(SettingsActivity.this);      // 重新判定深浅色与布局方向
                setContentView(build());                // 语言、方向都变了，整页重建
            }
        });
        return r;
    }

    // ------------------------------------------------------------ 开源许可

    private void pageLicense(LinearLayout body) {
        body.addView(sectionLabel("MIT License"));
        LinearLayout c = card();
        TextView t = new TextView(this);
        t.setText(LICENSE);
        t.setTextSize(11.5f);
        t.setTextColor(Theme.text2());
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setLineSpacing(dp(3), 1f);
        t.setTextIsSelectable(true);
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.addView(t);
        body.addView(c);

        body.addView(sectionLabel("第三方组件"));
        LinearLayout d = card();
        d.addView(hintRow("org.json —— JSON 解析，仅用于桌面单元测试"));
        d.addView(divider());
        d.addView(hintRow("Android SDK 与系统 WebView —— 由设备系统提供，随系统许可"));
        body.addView(d);

        body.addView(sectionLabel("参考与致谢"));
        LinearLayout r = card();
        r.addView(hintRow("以下项目只参考了设计与算法思路，本仓库不包含它们的源代码。"));
        r.addView(divider());
        TextView a1 = attribution(
                "DeepSeek Harness（MIT）",
                "Agent 循环的并发工具调度上限、请求失败重试、上下文溢出后的压缩重试，"
                        + "按其公开实现（packages/core/agent-loop）的思路用 Java 重写。");
        r.addView(a1);
        r.addView(divider());
        TextView a2 = attribution(
                "dsh-effort-switcher",
                "推理强度滑块的视觉参数（轨道与滑块尺寸、渐变配色、过渡时长）参考该项目；"
                        + "该项目未附许可证文件。");
        r.addView(a2);
        body.addView(r);
    }

    private TextView attribution(String title, String body) {
        TextView t = new TextView(this);
        t.setText(title + "\n" + body);
        t.setTextSize(12);
        t.setTextColor(Theme.text2());
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        return t;
    }

    private static final String LICENSE =
            "MIT License\n\n"
            + "Copyright (c) 2026 DShAndroid\n\n"
            + "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
            + "of this software and associated documentation files (the \"Software\"), to deal\n"
            + "in the Software without restriction, including without limitation the rights\n"
            + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
            + "copies of the Software, and to permit persons to whom the Software is\n"
            + "furnished to do so, subject to the following conditions:\n\n"
            + "The above copyright notice and this permission notice shall be included in all\n"
            + "copies or substantial portions of the Software.\n\n"
            + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
            + "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
            + "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
            + "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
            + "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
            + "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n"
            + "SOFTWARE.\n";

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(10), dp(16), dp(10));
        bar.setBackgroundColor(Theme.bg());

        TextView back = new TextView(this);
        back.setText("\u2039");
        back.setTextSize(26);
        back.setTextColor(Theme.text());
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (page != 0) {
                    gotoPage(0);
                } else {
                    finish();
                }
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        titleView = new TextView(this);
        titleView.setText(pageTitle());
        titleView.setTextSize(17);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setTextColor(Theme.text());
        bar.addView(titleView);
        return bar;
    }

    private String pageTitle() {
        if (page == 1) return "接口与密钥";
        if (page == 2) return "工具能力";
        if (page == 3) return "插件";
        if (page == 4) return "外观";
        if (page == 5) return "关于";
        if (page == 6) return "开源许可";
        if (page == 7) return "语言";
        return "设置";
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Theme.caption());
        t.setPadding(dp(4), dp(18), dp(4), dp(8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Theme.round(Theme.surface(), Theme.R_XL, this));
        return c;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Theme.border());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.setMargins(dp(16), 0, dp(16), 0);
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * 通用行：标题 + 右侧值。
     * prefKey 非空时把这一行的 value 注册进去，之后 refreshValue(prefKey) 只更新这一行。
     */
    private View row(String title, String prefKey, Fmt fmt,
                     View.OnClickListener click, boolean arrow) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        if (click != null) {
            r.setClickable(true);
            r.setBackground(pressable());
            r.setOnClickListener(click);
        }

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(Theme.text());
        r.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setTextSize(14);
        v.setTextColor(Theme.text3());
        v.setMaxLines(1);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        r.addView(v);

        if (arrow) {
            TextView a = new TextView(this);
            a.setText("\u203A");
            a.setTextSize(16);
            a.setTextColor(Theme.caption());
            a.setPadding(dp(8), 0, 0, 0);
            r.addView(a);
        }

        if (fmt != null) {
            String s = fmt.get();
            v.setText(s == null ? "" : s);
            if (prefKey != null) bound.add(new BoundRow(prefKey, v, fmt));
        }
        return r;
    }

    /** 纯动作行（左对齐，类似列表按钮）。 */
    private View actionRow(String text, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(Theme.link());
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setBackground(pressable());
        t.setOnClickListener(click);
        return t;
    }

    private View switchRow(String title, String sub, boolean checked, final CompoundCallback cb) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(Theme.text());
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12);
        s.setTextColor(Theme.caption());
        s.setPadding(0, dp(3), 0, 0);
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Toggle sw = new Toggle(this);
        sw.setChecked(checked);
        sw.setOnChangeListener(new Toggle.OnChange() {
            public void onChanged(boolean isChecked) {
                cb.onChanged(isChecked);
            }
        });
        r.addView(sw);
        return r;
    }

    private interface CompoundCallback { void onChanged(boolean checked); }

    // ------------------------------------------------------------ 统一分段器

    /** 分段器取值读写。 */
    private interface ValueIO {
        String get();
        void set(String k);
    }

    /** 选择守卫：返回 false 表示本次不生效，UI 自动回滚。 */
    private interface SegmentGuard {
        boolean onPick(String key);
    }

    private interface HintFn { String get(); }

    /**
     * 设置页统一的「标题 + 说明 + 分段器」行。
     * 推理等级、文件作用域、主题都走这里，保证视觉与交互一致：
     * 点一下只重绘这一行，不做整页重建。
     */
    private View segmentRow(String title, final HintFn hintFn,
                            final ValueIO io, final String[] keys, final String[] labels,
                            final SegmentGuard guard) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(Theme.text());
        col.addView(t);

        final TextView hint = new TextView(this);
        hint.setTextSize(11);
        hint.setTextColor(Theme.text3());
        hint.setPadding(0, dp(4), 0, dp(10));
        hint.setText(hintFn == null ? "" : hintFn.get());
        col.addView(hint);

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(Theme.round(Theme.surface2(), 12f, this));
        int p = dp(3);
        seg.setPadding(p, p, p, p);

        final List<TextView> chips = new ArrayList<TextView>();
        final HintFn hf = hintFn;
        for (int i = 0; i < keys.length; i++) {
            final TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTag(keys[i]);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(13);
            b.setPadding(0, dp(9), 0, dp(9));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String k = (String) v.getTag();
                    String cur = io.get();
                    if (k.equals(cur)) return;
                    if (guard != null && !guard.onPick(k)) {
                        // 守卫拒绝：回滚到当前值，并刷新说明
                        applySegment(chips, keys, io.get());
                        if (hf != null) hint.setText(hf.get());
                        return;
                    }
                    io.set(k);
                    applySegment(chips, keys, k);
                    bump(v);
                    if (hf != null) hint.setText(hf.get());
                }
            });
            chips.add(b);
            seg.addView(b, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        applySegment(chips, keys, io.get());
        col.addView(seg);
        return col;
    }

    private void applySegment(List<TextView> chips, String[] keys, String cur) {
        for (int i = 0; i < chips.size(); i++) {
            TextView b = chips.get(i);
            boolean on = keys[i].equals(cur);
            b.setTextColor(on ? Theme.onPrimary() : Theme.text2());
            b.setBackground(on ? Theme.round(Theme.primary(), 9f, this) : null);
        }
    }

    /** 行按压反馈：按下时轻微加深，松开恢复。 */
    private android.graphics.drawable.StateListDrawable pressable() {
        android.graphics.drawable.StateListDrawable s =
                new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed},
                Theme.round(Theme.hoverSolid(), Theme.R_XL, this));
        s.addState(new int[]{}, Theme.round(0x00000000, Theme.R_XL, this));
        return s;
    }

    /** 轻量"按下"反馈。 */
    private void bump(View v) {
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
    }

    // ------------------------------------------------------------ 模型区（接口驱动）

    private void rebuildModelHost(boolean animate) {
        if (modelHost == null) return;
        modelHost.removeAllViews();

        String key = keyValue();
        List<String> models = cachedModels();

        if (key.length() == 0) {
            // 没有 Key：不给任何模型选项，只提示
            modelHost.addView(hintRow("填写 API Key 后自动读取该账号可用的模型"));
        } else if (fetching) {
            modelHost.addView(hintRow("正在读取模型列表\u2026"));
        } else if (!models.isEmpty()) {
            // 没选过模型（或旧值失效）就先落到第一个，保证主界面有值可用
            if (currentModel().length() == 0) {
                sp.edit().putString(C.KEY_MODEL, models.get(0)).apply();
            }
            modelHost.addView(modelDropRow(models));
        } else {
            modelHost.addView(hintRow(fetchError == null
                    ? "尚未读取到模型" : "读取失败：" + fetchError));
        }

        if (animate) {
            modelHost.setAlpha(0f);
            modelHost.setTranslationY(-dp(3));
            modelHost.animate().alpha(1f).translationY(0).setDuration(180).start();
        }
    }

    private View hintRow(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Theme.text3());
        t.setPadding(dp(16), dp(12), dp(16), dp(14));
        return t;
    }

    /** 模型下拉入口：一行「模型 | 当前值 ⌄」。 */
    private View modelDropRow(final List<String> models) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(13), dp(16), dp(13));

        TextView t = new TextView(this);
        t.setText("模型");
        t.setTextSize(15);
        t.setTextColor(Theme.text());
        r.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView val = new TextView(this);
        val.setText(currentModel());
        val.setTextSize(14);
        val.setTextColor(Theme.text());
        val.setMaxLines(1);
        val.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        r.addView(val);

        final TextView caret = new TextView(this);
        caret.setText("\u2304");
        caret.setTextSize(14);
        caret.setTextColor(Theme.caption());
        caret.setPadding(dp(6), 0, 0, 0);
        r.addView(caret);

        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showModelDropdown(r, val, caret, models);
            }
        });
        return r;
    }

    /** 下拉浮层：从锚点展开，带 scale + alpha 动画。 */
    private void showModelDropdown(View anchor, final TextView val,
                                   final TextView caret, List<String> models) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Theme.roundOutlined(Theme.panel(), Theme.R_LG, Theme.border(), this));
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));

        // 列表可能很长：外面套一层 ScrollView，超出 320dp 就能滚
        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final PopupWindow pw = new PopupWindow(this);
        pw.setContentView(scroller);
        pw.setWidth(Math.max(anchor.getWidth(), dp(200)));
        pw.setHeight(Math.min(models.size() * dp(46) + dp(12), dp(320)));
        pw.setBackgroundDrawable(new ColorDrawable(0x00000000));
        pw.setOutsideTouchable(true);
        pw.setFocusable(true);
        if (Build.VERSION.SDK_INT >= 21) pw.setElevation(dp(8));

        final String cur = currentModel();
        for (int i = 0; i < models.size(); i++) {
            final String id = models.get(i);
            boolean on = id.equals(cur);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), dp(12), dp(12), dp(12));
            if (on) item.setBackground(Theme.round(Theme.surface2(), Theme.R_MD, this));

            TextView name = new TextView(this);
            name.setText(id);
            name.setTextSize(13);
            name.setTextColor(on ? Theme.text() : Theme.text2());
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            item.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (on) {
                TextView mk = new TextView(this);
                mk.setText("\u2713");
                mk.setTextSize(13);
                mk.setTextColor(Theme.text());
                mk.setPadding(dp(8), 0, 0, 0);
                item.addView(mk);
            }

            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    sp.edit().putString(C.KEY_MODEL, id).apply();
                    pw.dismiss();
                    val.setText(id);
                    val.setAlpha(0f);
                    val.animate().alpha(1f).setDuration(160).start();
                    caret.animate().rotation(0f).setDuration(120).start();
                }
            });
            panel.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        }

        caret.animate().rotation(180f).setDuration(160).start();
        scroller.setAlpha(0f);
        pw.showAsDropDown(anchor, 0, dp(2));
        scroller.animate().alpha(1f).setDuration(150).start();

        pw.setOnDismissListener(new PopupWindow.OnDismissListener() {
            public void onDismiss() {
                caret.animate().rotation(0f).setDuration(120).start();
            }
        });
    }

    /** 从配置接口读取模型列表（后台线程）。 */
    private void fetchModels(final boolean byUser) {
        final String key = keyValue();
        if (key.length() == 0) {
            if (byUser) toast("请先填写 API Key");
            rebuildModelHost(true);
            return;
        }
        if (fetching) return;

        fetching = true;
        fetchError = null;
        rebuildModelHost(true);

        final String base = sp.getString(C.KEY_BASE_URL, C.DEFAULT_BASE_URL);
        new Thread(new Runnable() {
            public void run() {
                List<String> got = null;
                String err = null;
                try {
                    got = LlmClient.fetchModels(base, key);
                } catch (Exception e) {
                    err = e.getMessage();
                    if (err == null || err.length() == 0) err = e.toString();
                }
                final List<String> fm = got;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        fetching = false;
                        if (isFinishing()) return;
                        if (fm != null && fm.size() > 0) {
                            saveModels(fm);
                            String cur = currentModel();
                            if (!fm.contains(cur)) {
                                sp.edit().putString(C.KEY_MODEL, fm.get(0)).apply();
                            }
                            fetchError = null;
                            if (byUser) toast("读取到 " + fm.size() + " 个模型");
                        } else {
                            fetchError = fe == null ? "接口没有返回模型" : fe;
                            if (byUser) toast("读取失败");
                        }
                        rebuildModelHost(true);
                        refreshValue("__a11y", false);
                    }
                });
            }
        }).start();
    }

    private List<String> cachedModels() {
        List<String> list = new ArrayList<String>();
        String cached = sp.getString(K_MODELS_CACHE, "");
        if (cached == null || cached.length() == 0) return list;
        try {
            JSONArray arr = new JSONArray(cached);
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "");
                if (id != null && id.length() > 0) list.add(id);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void saveModels(List<String> models) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < models.size(); i++) arr.put(models.get(i));
        sp.edit().putString(K_MODELS_CACHE, arr.toString()).apply();
    }

    /** 当前模型名（无则空串，界面自己兜底）。 */
    private String currentModel() {
        String m = sp.getString(C.KEY_MODEL, "");
        return m == null ? "" : m;
    }

    private String keyValue() {
        String k = sp.getString(C.KEY_API_KEY, "");
        return k == null ? "" : k.trim();
    }

    // ------------------------------------------------------------ 局部刷新

    /** 只重绘与某个配置项绑定的行，可带轻微上浮淡入。 */
    private void refreshValue(String key, boolean animate) {
        for (int i = 0; i < bound.size(); i++) {
            BoundRow r = bound.get(i);
            if (r.key == null || !r.key.equals(key)) continue;
            String s = r.fmt.get();
            r.view.setText(s == null ? "" : s);
            if (animate) {
                r.view.setAlpha(0f);
                r.view.setTranslationY(-dp(4));
                r.view.animate().alpha(1f).translationY(0).setDuration(180).start();
            }
        }
    }

    // ------------------------------------------------------------ 交互

    // ------------------------------------------------------------ 文件作用域

    /**
     * 作用域行：外壳固定，内部内容可原地重建。
     * 这样权限结果回来后只重绘这一块，不会整页闪。
     */
    private View scopeRow() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        scopeShell = shell;
        fillScope(shell);
        return shell;
    }

    private void fillScope(LinearLayout shell) {
        shell.addView(segmentRow("文件作用域",
                new HintFn() { public String get() { return scopeHintText(); } },
                new ValueIO() {
                    public String get() { return sp.getString(C.KEY_FS_SCOPE, C.SCOPE_WORKSPACE); }
                    public void set(String k) {
                        sp.edit().putString(C.KEY_FS_SCOPE, k).apply();
                    }
                },
                new String[]{C.SCOPE_WORKSPACE, C.SCOPE_DEVICE},
                new String[]{"工作区", "全局"},
                new SegmentGuard() {
                    public boolean onPick(String k) {
                        if (C.SCOPE_DEVICE.equals(k) && !hasStorage()) {
                            // 先拿权限，授权后再真正切换（见 onRequestPermissionsResult）
                            pendingDeviceScope = true;
                            requestStorage();
                            return false;
                        }
                        return true;
                    }
                }));
    }

    private void rebuildScopeRow() {
        if (scopeShell == null) return;
        scopeShell.removeAllViews();
        fillScope(scopeShell);
        scopeShell.setAlpha(0f);
        scopeShell.animate().alpha(1f).setDuration(160).start();
    }

    private String scopeHintText() {
        if (C.SCOPE_DEVICE.equals(sp.getString(C.KEY_FS_SCOPE, C.SCOPE_WORKSPACE))) {
            return hasStorage()
                    ? "可读写整个设备：/sdcard、/storage 及系统路径"
                    : "未获得存储权限，实际仍只能读写工作区";
        }
        return "仅限应用私有目录 workspace，越界路径会被直接拒绝";
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_STORAGE) return;
        boolean ok = res != null && res.length > 0;
        if (res != null) {
            for (int i = 0; i < res.length; i++) {
                if (res[i] != android.content.pm.PackageManager.PERMISSION_GRANTED) ok = false;
            }
        }
        if (ok && pendingDeviceScope) {
            pendingDeviceScope = false;
            sp.edit().putString(C.KEY_FS_SCOPE, C.SCOPE_DEVICE).apply();
            toast("已切换到全局模式");
            rebuildScopeRow();
        } else {
            pendingDeviceScope = false;
            sp.edit().putString(C.KEY_FS_SCOPE, C.SCOPE_WORKSPACE).apply();
            if (!ok) toast("未获得存储权限，已保持工作区模式");
            rebuildScopeRow();
        }
    }

    /** 换主题需要重绘全部颜色：用淡出→重建→淡入遮盖，避免白闪。 */
    /**
     * 切换主题时用圆形扩散过渡：先截下当前画面，重建新主题后再把旧画面从左上角
     * 收成一个圆（半径由整屏收到 0），视觉上新主题就是从那个角呈半圆扩散开的。
     */
    private void applyThemeReveal() {
        final View content = findViewById(android.R.id.content);
        int w = content == null ? 0 : content.getWidth();
        int h = content == null ? 0 : content.getHeight();
        if (w <= 0 || h <= 0) {
            setContentView(build());
            return;
        }

        android.graphics.Bitmap shot = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888);
        content.draw(new android.graphics.Canvas(shot));

        Theme.init(this);
        setContentView(build());

        final android.view.ViewGroup host = findViewById(android.R.id.content);
        if (host == null) return;

        final android.widget.ImageView overlay = new android.widget.ImageView(this);
        overlay.setImageBitmap(shot);
        overlay.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        host.addView(overlay, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        host.post(new Runnable() {
            public void run() {
                int w2 = host.getWidth();
                int h2 = host.getHeight();
                float maxR = (float) Math.hypot(w2, h2);
                android.animation.Animator a = android.view.ViewAnimationUtils
                        .createCircularReveal(overlay, 0, 0, maxR, 0f);
                a.setDuration(420);
                a.setInterpolator(new android.view.animation.DecelerateInterpolator());
                a.addListener(new android.animation.AnimatorListenerAdapter() {
                    public void onAnimationEnd(android.animation.Animator anim) {
                        try {
                            host.removeView(overlay);
                        } catch (Throwable ignored) {}
                    }
                });
                a.start();
            }
        });
    }

    private void editText(final String label, final String key,
                          final String hint, final boolean numeric) {
        final EditText et = new EditText(this);
        et.setText(sp.getString(key, ""));
        et.setHint(hint);
        et.setTextColor(Theme.text());
        et.setHintTextColor(Theme.caption());
        et.setSingleLine(true);
        if (numeric) et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        new AppDialog.Builder(this)
                .setTitle(label)
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String v = et.getText().toString().trim();
                        if (v.length() == 0) sp.edit().remove(key).apply();
                        else sp.edit().putString(key, v).apply();

                        boolean refetch = C.KEY_API_KEY.equals(key) || C.KEY_BASE_URL.equals(key);
                        if (refetch) {
                            // Key / 地址变了，旧模型列表作废
                            sp.edit().remove(K_MODELS_CACHE).apply();
                            fetchError = null;
                            refreshValue(key, true);
                            rebuildModelHost(true);
                            fetchModels(true);   // 填完立即读取，带结果提示
                        } else {
                            refreshValue(key, true);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, REQ_STORAGE);
    }

    // ------------------------------------------------------------ 工具


    private static String maskKey(String k) {
        if (k == null || k.length() == 0) return "未设置";
        if (k.length() <= 8) return "\u2022\u2022\u2022\u2022";
        return k.substring(0, 4) + "\u2022\u2022\u2022\u2022" + k.substring(k.length() - 4);
    }

    public int dp(float v) { return Theme.dp(this, v); }

    // ------------------------------------------------------------ 检查更新

    /** 检查更新行的右侧文案：检查中 → 本次结果 → 上次检查时间。 */

    private void openUrl(String url) {
        if (url == null || url.length() == 0) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            toast("没有可用的浏览器");
        }
    }

    /** 本次运行期间的检查结果文案。 */
    private String lastResult = null;

    public void toast(String s) { Snack.show(this, s); }

    /** 在后台线程尝试用 root 开启，回来刷新界面。 */
    private void tryRootEnable() {
        toast("正在尝试…");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String err;
                if (!com.dsh.android.a11y.A11yAdmin.hasRoot()) {
                    err = "设备没有 root，这条路走不通。请用系统设置或 ADB 打开。";
                } else {
                    err = com.dsh.android.a11y.A11yAdmin.enableWithRoot(app);
                }
                final String fe = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        refreshValue("__a11y", true);
                        refreshA11yHint();
                        toast(fe == null ? "已尝试开启，请回到应用看看状态" : fe);
                    }
                });
            }
        }, "a11y-root").start();
    }

    // ------------------------------------------------------------ 插件区

    /** 拿插件管理器：正常情况下主界面已装好，异常情况自己兜一个。 */
    public PluginManager pluginMgr() {
        if (pluginMgr == null) {
            pluginMgr = PluginHub.get();
            if (pluginMgr == null) {
                // 极端情况（直接从设置页冷启动等）：自己建一套，至少能看能改
                pluginMgr = new PluginManager(this, new EventBus(), new ToolRuntime(this));
                pluginMgr.reload();
            }
        }
        return pluginMgr;
    }

    public LinearLayout pluginHost() { return pluginHost; }

    public void rebuildPlugins(boolean animate) {
        if (pluginSection != null) pluginSection.rebuild(animate);
    }

    private void rescanPlugins() {
        PluginManager.Reload r = pluginMgr().rescan();
        rebuildPlugins(false);
        StringBuilder sb = new StringBuilder();
        sb.append("已加载 ").append(r.enabledCount).append(" 个插件 · ")
          .append(r.toolCount).append(" 个工具");
        if (r.newCount > 0) sb.append("（新发现 ").append(r.newCount).append(" 个）");
        if (!r.errors.isEmpty()) sb.append(" · ").append(r.errors.size()).append(" 个有问题");
        toast(sb.toString());
    }

    private void showPluginSample() {
        pluginSection.showSample();
    }
}
