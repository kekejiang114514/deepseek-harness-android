package com.dsh.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
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
        if (st == com.dsh.android.a11y.A11yState.READY) return;

        a11yHintHost.addView(divider());
        if (st == com.dsh.android.a11y.A11yState.NOT_LISTED) {
            a11yHintHost.addView(hintRow("系统的无障碍列表里没有本应用的服务。"
                    + "这几乎都是 Android 13 起对「非应用商店安装」的应用施加的受限设置——"
                    + "开关和服务条目会被一起隐藏，所以你找不到可以打开的地方。\n"
                    + "解法：点下面的按钮进「应用信息」，点右上角 ⋮，选「允许受限设置」，"
                    + "再回来就能在无障碍列表里看到它了。"));
            a11yHintHost.addView(actionRow("打开应用信息（解除受限设置）", new View.OnClickListener() {
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
        } else if (st == com.dsh.android.a11y.A11yState.ON_NOT_CONNECTED) {
            a11yHintHost.addView(hintRow("系统里已经打开了这个服务，但当前还没连接上。"
                    + "通常再等一下就好；一直不连的话，把本应用从最近任务里划掉重开一次。"));
        } else {
            a11yHintHost.addView(hintRow("屏幕操作依赖系统的无障碍服务，Android 规定只能由你手动开启一次，"
                    + "应用无法自行授权。点上面那一行会直接跳到本应用的开关页；若开关发灰点不动，"
                    + "点该页右上角 ⋮ →「允许受限设置」后再打开。"));
            a11yHintHost.addView(actionRow("复制 ADB 开启命令", new View.OnClickListener() {
                public void onClick(View v) {
                    String cmd = com.dsh.android.a11y.A11yState.adbCommand(SettingsActivity.this);
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd));
                    toast("已复制，连电脑执行即可开启");
                }
            }));
            a11yHintHost.addView(actionRow("尝试用 root 直接开启", new View.OnClickListener() {
                public void onClick(View v) { tryRootEnable(); }
            }));
        }
    }

    // ------------------------------------------------------------ 布局

    private View build() {
        bound.clear();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg());
        root.addView(topBar());

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(40));

        // ---- 接口与模型 ----
        body.addView(sectionLabel("接口"));
        LinearLayout apiCard = card();

        apiCard.addView(row("接口地址", C.KEY_BASE_URL,
                new Fmt() {
                    public String get() {
                        String v = sp.getString(C.KEY_BASE_URL, C.DEFAULT_BASE_URL);
                        return v == null ? "" : v;
                    }
                },
                new View.OnClickListener() {
                    public void onClick(View v) { editText("接口地址", C.KEY_BASE_URL, C.DEFAULT_BASE_URL, false); }
                }, true));
        apiCard.addView(divider());
        apiCard.addView(row("API Key", C.KEY_API_KEY,
                new Fmt() {
                    public String get() { return maskKey(keyValue()); }
                },
                new View.OnClickListener() {
                    public void onClick(View v) { editText("API Key", C.KEY_API_KEY, "sk-...", false); }
                }, true));
        apiCard.addView(divider());

        modelHost = new LinearLayout(this);
        modelHost.setOrientation(LinearLayout.VERTICAL);
        apiCard.addView(modelHost);
        rebuildModelHost(false);

        if (keyValue().length() > 0) {
            apiCard.addView(divider());
            apiCard.addView(actionRow("重新读取模型列表", new View.OnClickListener() {
                public void onClick(View v) { fetchModels(true); }
            }));
        }
        body.addView(apiCard);

        // ---- 生成参数 ----
        body.addView(sectionLabel("生成参数"));
        LinearLayout genCard = card();
        genCard.addView(segmentRow("推理等级",
                new HintFn() { public String get() { return "等级越高，回答越深入，耗时也越长"; } },
                new ValueIO() {
                    public String get() { return sp.getString(C.KEY_REASONING, C.LEVEL_BALANCED); }
                    public void set(String k) { sp.edit().putString(C.KEY_REASONING, k).apply(); }
                },
                new String[]{C.LEVEL_FAST, C.LEVEL_BALANCED, C.LEVEL_DEEP},
                new String[]{"快速", "标准", "深度"}, null));
        body.addView(genCard);

        // ---- 工具能力 ----
        body.addView(sectionLabel("工具能力"));
        LinearLayout toolCard = card();
        toolCard.addView(scopeRow());
        toolCard.addView(divider());
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
                                    public void run() { applyThemeFade(); }
                                });
                    }
                },
                new String[]{Theme.MODE_SYSTEM, Theme.MODE_LIGHT, Theme.MODE_DARK},
                new String[]{"跟随系统", "浅色", "深色"}, null));
        body.addView(uiCard);

        // ---- 关于 ----
        body.addView(sectionLabel("关于"));
        LinearLayout aboutCard = card();
        aboutCard.addView(row("版本", null, new Fmt() { public String get() { return C.VERSION; } }, null, false));
        aboutCard.addView(divider());
        aboutCard.addView(row("模型来源", null,
                new Fmt() {
                    public String get() {
                        int n = cachedModels().size();
                        return n == 0 ? "未读取" : "接口读取 · " + n + " 个";
                    }
                }, null, false));
        aboutCard.addView(divider());
        aboutCard.addView(row("开发者", null, new Fmt() {
            public String get() {
                return com.dsh.android.kernel.About.NAME
                        + "（" + com.dsh.android.kernel.About.NICK + "）";
            }
        }, new View.OnClickListener() {
            public void onClick(View v) {
                showAuthor();
            }
        }, true));
        aboutCard.addView(divider());
        aboutCard.addView(row("项目仓库", null, new Fmt() {
            public String get() { return com.dsh.android.kernel.About.REPO_NAME; }
        }, new View.OnClickListener() {
            public void onClick(View v) {
                openUrl(com.dsh.android.kernel.About.REPO);
            }
        }, true));
        aboutCard.addView(divider());
        aboutCard.addView(row("GitHub", null, new Fmt() {
            public String get() { return "@" + com.dsh.android.kernel.About.NAME; }
        }, new View.OnClickListener() {
            public void onClick(View v) {
                openUrl(com.dsh.android.kernel.About.GITHUB);
            }
        }, true));
        aboutCard.addView(divider());
        aboutCard.addView(row("X / Twitter", null, new Fmt() {
            public String get() { return com.dsh.android.kernel.About.TWITTER_HANDLE; }
        }, new View.OnClickListener() {
            public void onClick(View v) {
                openUrl(com.dsh.android.kernel.About.TWITTER);
            }
        }, true));
        aboutCard.addView(divider());
        aboutCard.addView(row("Bilibili", null, new Fmt() {
            public String get() { return "673542910"; }
        }, new View.OnClickListener() {
            public void onClick(View v) {
                openUrl(com.dsh.android.kernel.About.BILIBILI);
            }
        }, true));
        aboutCard.addView(divider());
        aboutCard.addView(row("邮箱", null, new Fmt() {
            public String get() { return com.dsh.android.kernel.About.EMAIL; }
        }, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_SENDTO,
                            android.net.Uri.parse("mailto:" + com.dsh.android.kernel.About.EMAIL)));
                } catch (Exception e) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("mail",
                            com.dsh.android.kernel.About.EMAIL));
                    toast("没有邮件应用，已复制地址");
                }
            }
        }, true));
        aboutCard.addView(divider());
        aboutCard.addView(row("检查更新", "update_status", new Fmt() {
            public String get() { return updateStatusText(); }
        }, new View.OnClickListener() {
            public void onClick(View v) { checkUpdate(); }
        }, true));
        aboutCard.addView(divider());
        aboutCard.addView(switchRow("自动检查更新", "每次打开应用时查一次，每天最多一次",
                Updater.autoEnabled(this), new CompoundCallback() {
                    public void onChanged(boolean checked) {
                        Updater.setAutoEnabled(SettingsActivity.this, checked);
                        refreshValue("update_status", true);
                    }
                }));
        body.addView(aboutCard);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

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
            public void onClick(View v) { finish(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(Theme.text());
        bar.addView(title);
        return bar;
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

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
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
    private void applyThemeFade() {
        final View content = findViewById(android.R.id.content);
        content.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
            public void run() {
                setContentView(build());
                final View fresh = findViewById(android.R.id.content);
                fresh.setAlpha(0f);
                fresh.animate().alpha(1f).setDuration(180).start();
            }
        }).start();
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

        new AlertDialog.Builder(this)
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
    private String updateStatusText() {
        if (checking) return "检查中…";
        if (lastResult != null) return lastResult;
        String err = Updater.lastError(this);
        if (err != null && err.length() > 0) return "上次失败";
        String tag = Updater.lastTag(this);
        if (tag != null && tag.length() > 0) return "已是最新 " + tag;
        return Updater.lastCheckedText(this);
    }

    private void checkUpdate() {
        if (checking) return;
        checking = true;
        lastResult = null;
        refreshValue("update_status", true);

        Updater.checkAsync(this, new Updater.Callback() {
            public void onResult(Updater.Release r, String error) {
                checking = false;
                if (error != null) {
                    lastResult = "检查失败";
                    refreshValue("update_status", true);
                    toast("检查更新失败：" + error);
                    return;
                }
                if (r == null) {
                    lastResult = "已是最新";
                    refreshValue("update_status", true);
                    toast("当前已是最新版本 " + C.VERSION);
                    return;
                }
                lastResult = "有新版本 " + r.version;
                refreshValue("update_status", true);
                showUpdateDialog(r);
            }
        });
    }

    /** 有新版本时的对话框：直接给下载入口，不让用户自己去找。 */
    private void showUpdateDialog(final Updater.Release r) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前 ").append(C.VERSION).append("  →  最新 ").append(r.version);
        if (r.apkSize > 0) sb.append("\n安装包 ").append(r.sizeText());

        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 0) {
            // 发布说明可能很长，截前几行，完整内容去发布页看
            String[] lines = notes.split("\n");
            StringBuilder cut = new StringBuilder();
            for (int i = 0; i < lines.length && i < 8; i++) cut.append(lines[i]).append("\n");
            if (lines.length > 8) cut.append("…");
            sb.append("\n\n").append(cut.toString().trim());
        }

        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + r.version)
                .setMessage(sb.toString())
                .setPositiveButton("下载并安装", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (!r.hasApk()) {
                            toast("这个版本没有附带安装包，去发布页看看");
                            openUrl(r.pageUrl);
                            return;
                        }
                        startUpdate(r);
                    }
                })
                .setNeutralButton("发布页", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { openUrl(r.pageUrl); }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 下载 → 完成后直接弹系统安装界面。 */
    private void startUpdate(final Updater.Release r) {
        if (!com.dsh.android.kernel.ApkInstaller.canInstall(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("Android 8 起，应用要安装更新需要你允许「安装未知应用」。"
                            + "点「去设置」打开本应用的开关，回来再点一次更新即可。")
                    .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            try {
                                Intent i = new Intent(android.provider.Settings
                                        .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        android.net.Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Exception e) {
                                toast("无法打开设置，请手动到 设置 → 应用 → 特殊权限 里允许");
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("正在下载更新");
        pd.setMessage("0%");
        pd.setCancelable(true);
        pd.show();

        new Thread(new Runnable() {
            public void run() {
                try {
                    final java.io.File apk = com.dsh.android.kernel.ApkInstaller.download(
                            SettingsActivity.this, r.apkUrl,
                            new com.dsh.android.kernel.ApkInstaller.Progress() {
                                public void onProgress(final long done, final long total) {
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            pd.setMessage((done * 100 / Math.max(1, total)) + "%"
                                                    + "（" + (done / 1024) + "KB / " + (total / 1024) + "KB）");
                                        }
                                    });
                                }
                            });
                    runOnUiThread(new Runnable() {
                        public void run() {
                            dismissQuietly(pd);
                            confirmInstall(apk);
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            dismissQuietly(pd);
                            new AlertDialog.Builder(SettingsActivity.this)
                                    .setTitle("下载失败")
                                    .setMessage(t.getMessage() == null
                                            ? t.getClass().getSimpleName() : t.getMessage())
                                    .setPositiveButton("去发布页", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) {
                                            openUrl(r.pageUrl);
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    });
                }
            }
        }, "apk-download").start();
    }

    private void confirmInstall(final java.io.File apk) {
        long mb = Math.max(1, apk.length() / 1048576);
        new AlertDialog.Builder(this)
                .setTitle("下载完成")
                .setMessage("安装包已下载（约 " + mb + "MB）。\n\n"
                        + "点「立即安装」后系统会弹出安装确认，装完会保留你的设置与对话。")
                .setPositiveButton("立即安装", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        com.dsh.android.kernel.ApkInstaller.install(
                                SettingsActivity.this, apk,
                                new com.dsh.android.kernel.ApkInstaller.Callback() {
                                    public void onDone(final String err) {
                                        if (err != null) {
                                            toast("安装未完成：" + err);
                                        }
                                    }
                                });
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void dismissQuietly(android.app.Dialog d) {
        try {
            if (d != null && d.isShowing()) d.dismiss();
        } catch (Throwable ignored) {}
    }

    /** 点「开发者」时的说明。 */
    private void showAuthor() {
        String text = "名字：" + com.dsh.android.kernel.About.NAME
                + "（" + com.dsh.android.kernel.About.NICK + "）\n"
                + com.dsh.android.kernel.About.TAGLINE + "\n\n"
                + "GitHub     " + com.dsh.android.kernel.About.GITHUB + "\n"
                + "X          " + com.dsh.android.kernel.About.TWITTER + "\n"
                + "Bilibili   " + com.dsh.android.kernel.About.BILIBILI + "\n"
                + "邮箱       " + com.dsh.android.kernel.About.EMAIL + "\n\n"
                + com.dsh.android.kernel.About.FOOTER;

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(Theme.text());
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(8), dp(20), dp(4));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制全部", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("about",
                                "名字：" + com.dsh.android.kernel.About.NAME
                                        + "\nGitHub: " + com.dsh.android.kernel.About.GITHUB
                                        + "\nX: " + com.dsh.android.kernel.About.TWITTER
                                        + "\nBilibili: " + com.dsh.android.kernel.About.BILIBILI
                                        + "\nEmail: " + com.dsh.android.kernel.About.EMAIL));
                        toast("已复制");
                    }
                })
                .show();
    }

    private void openUrl(String url) {
        if (url == null || url.length() == 0) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            toast("没有可用的浏览器");
        }
    }

    /** 检查中标志：避免连点触发多次请求。 */
    private boolean checking = false;
    /** 本次运行期间的检查结果文案。 */
    private String lastResult = null;

    public void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

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
