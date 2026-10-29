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
public final class SettingsActivity extends Activity {

    private static final String K_MODELS_CACHE = "models_cache";

    private SharedPreferences sp;
    private static final int REQ_STORAGE = 1001;
    private boolean pendingDeviceScope = false;
    private LinearLayout scopeShell;

    /** 模型区容器：内容随「有无 Key / 是否读取中」动态重建。 */
    private LinearLayout modelHost;
    private boolean fetching = false;
    private String fetchError = null;

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
        toolCard.addView(row("系统无障碍设置", "__a11y",
                new Fmt() {
                    public String get() {
                        return A11yService.isReady() ? "已连接" : "未连接 · 点击开启";
                    }
                },
                new View.OnClickListener() {
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Exception e) {
                            toast("无法打开系统设置");
                        }
                    }
                }, true));
        body.addView(toolCard);

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

    private int dp(float v) { return Theme.dp(this, v); }

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
                .setPositiveButton("去下载", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        openUrl(r.hasApk() ? r.apkUrl : r.pageUrl);
                    }
                })
                .setNeutralButton("发布页", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { openUrl(r.pageUrl); }
                })
                .setNegativeButton("稍后", null)
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

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
