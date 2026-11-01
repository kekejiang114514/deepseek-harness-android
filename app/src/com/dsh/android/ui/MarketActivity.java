package com.dsh.android.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.android.market.Installer;
import com.dsh.android.market.MarketClient;
import com.dsh.android.plugins.PluginHub;
import com.dsh.android.plugins.PluginManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件市场：从 dshmarket.com 的索引里挑插件，一键装到本地插件目录。
 *
 * <p>只展示能真正跑的：界面类（ui / theme）插件依赖前端渲染体系，
 * 本版跑不了，默认直接隐藏，不拿「装了没反应」去骗人。
 */
public final class MarketActivity extends Activity {

    /** 缓存：进过一次就不再重复拉 3MB 索引。 */
    private static List<MarketClient.Item> CACHE;

    private static final int SHOW_LIMIT = 60;

    private final List<MarketClient.Item> all = new ArrayList<MarketClient.Item>();
    private final List<MarketClient.Item> shown = new ArrayList<MarketClient.Item>();

    private LinearLayout listBox;
    private TextView status;
    private EditText search;
    private boolean loading;
    private String loadError;
    private int hiddenCount;

    /**
     * 在 Activity 挂载前把语言与布局方向写进 Configuration。
     *
     * <p>只对具体的 View 调 setLayoutDirection 是不够的：文本方向、系统控件、
     * 资源里的 localizable 值都来自 Configuration 的 Locale。阿拉伯语等 RTL 语言
     * 必须在这里换掉 Locale，系统才会把整个界面镜像过来。
     */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        android.content.res.Configuration cfg =
                new android.content.res.Configuration(base.getResources().getConfiguration());
        com.dsh.android.kernel.Lang.applyTo(cfg, base);
        super.attachBaseContext(base.createConfigurationContext(cfg));
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.app.ActionBar ab = getActionBar();
        if (ab != null) ab.hide();
        Theme.init(this);
        setContentView(build());
        if (CACHE != null) {
            adopt(CACHE);
        } else {
            load();
        }
    }

    // ------------------------------------------------------------ 界面

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg());
        root.addView(topBar());

        EditText box = new EditText(this);
        box.setHint("搜索插件名、作者或描述");
        box.setTextSize(14);
        box.setSingleLine(true);
        box.setTextColor(Theme.text());
        box.setHintTextColor(Theme.caption());
        box.setBackground(Theme.round(Theme.surface(), Theme.R_XL, this));
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(16), dp(6), dp(16), dp(6));
        box.setLayoutParams(blp);
        box.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                render(s.toString());
            }
        });
        search = box;
        root.addView(box);

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(Theme.caption());
        status.setPadding(dp(20), dp(4), dp(20), dp(6));
        status.setText("正在读取插件索引…");
        root.addView(status);

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(16), 0, dp(16), dp(40));
        sv.addView(listBox);
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
        title.setText("插件市场");
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Theme.text());
        bar.addView(title);
        return bar;
    }

    // ------------------------------------------------------------ 数据

    private void load() {
        if (loading) return;
        loading = true;
        status.setText("正在读取插件索引（约 3MB）…");
        new Thread(new Runnable() {
            public void run() {
                List<MarketClient.Item> list = null;
                String err = null;
                try {
                    list = MarketClient.fetch();
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final List<MarketClient.Item> fl = list;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        loading = false;
                        if (fl == null) {
                            loadError = fe;
                            status.setText("读取失败：" + fe + "（点这里重试）");
                            status.setOnClickListener(new View.OnClickListener() {
                                public void onClick(View v) { load(); }
                            });
                            return;
                        }
                        CACHE = fl;
                        adopt(fl);
                    }
                });
            }
        }, "market-load").start();
    }

    private void adopt(List<MarketClient.Item> list) {
        all.clear();
        hiddenCount = 0;
        Installer inst = installer();
        for (int i = 0; i < list.size(); i++) {
            MarketClient.Item it = list.get(i);
            if ("ui".equals(it.category) || "theme".equals(it.category)) {
                hiddenCount++;
                continue;
            }
            it.installed = inst != null && inst.isInstalled(it.npm);
            all.add(it);
        }
        render("");
    }

    private void render(String q) {
        if (listBox == null) return;
        listBox.removeAllViews();
        shown.clear();

        String key = q == null ? "" : q.trim().toLowerCase();
        for (int i = 0; i < all.size(); i++) {
            MarketClient.Item it = all.get(i);
            if (key.length() > 0) {
                String hay = (it.npm + " " + it.title + " " + it.description() + " " + it.category)
                        .toLowerCase();
                if (hay.indexOf(key) < 0) continue;
            }
            shown.add(it);
            if (shown.size() >= SHOW_LIMIT) break;
        }

        status.setText("共 " + all.size() + " 个可装插件"
                + (hiddenCount > 0 ? "（已隐藏 " + hiddenCount + " 个界面类）" : "")
                + (shown.size() < all.size() ? " · 显示前 " + shown.size() + " 个" : ""));

        for (int i = 0; i < shown.size(); i++) {
            listBox.addView(row(shown.get(i)));
        }
        if (shown.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有匹配的插件");
            empty.setTextSize(13);
            empty.setTextColor(Theme.caption());
            empty.setPadding(dp(4), dp(20), dp(4), dp(20));
            listBox.addView(empty);
        }
    }

    private View row(final MarketClient.Item it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.round(Theme.surface(), Theme.R_XL, this));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        card.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(it.title);
        name.setTextSize(14);
        name.setTypeface(null, Typeface.BOLD);
        name.setTextColor(Theme.text());
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView action = new TextView(this);
        action.setTextSize(13);
        action.setPadding(dp(12), dp(6), dp(12), dp(6));
        if (it.installed) {
            action.setText("已安装");
            action.setTextColor(Theme.caption());
        } else {
            action.setText("安装");
            action.setTextColor(Theme.link());
            action.setBackground(Theme.round(Theme.primarySoft(), Theme.R_XL, this));
        }
        head.addView(action);
        card.addView(head);

        TextView meta = new TextView(this);
        meta.setText(it.category + " · " + it.npm + (it.version.length() > 0 ? " · v" + it.version : ""));
        meta.setTextSize(11);
        meta.setTextColor(Theme.caption());
        meta.setPadding(0, dp(3), 0, 0);
        card.addView(meta);

        String d = it.description();
        if (d != null && d.length() > 0) {
            TextView desc = new TextView(this);
            desc.setText(d.length() > 160 ? d.substring(0, 160) + "…" : d);
            desc.setTextSize(12);
            desc.setTextColor(Theme.text2());
            desc.setPadding(0, dp(6), 0, 0);
            card.addView(desc);
        }

        if (!it.installed) {
            action.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    install(it, action);
                }
            });
        }
        return card;
    }

    // ------------------------------------------------------------ 安装

    private void install(final MarketClient.Item it, final TextView action) {
        action.setText("安装中…");
        action.setTextColor(Theme.caption());
        action.setOnClickListener(null);
        toast("开始安装 " + it.npm);

        new Thread(new Runnable() {
            public void run() {
                final Installer.Result r = installer().install(it.npm, it.version);
                // 装完立刻重扫，插件列表与模型工具都跟着更新
                final PluginManager pm = PluginHub.get();
                if (r.ok && pm != null) pm.rescan();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (r.ok) {
                            it.installed = true;
                            action.setText("已安装");
                            action.setTextColor(Theme.caption());
                            action.setBackground(null);
                            toast("已安装 " + it.npm + "，可在设置 → 插件里查看");
                        } else {
                            action.setText("重试");
                            action.setTextColor(Theme.link());
                            action.setOnClickListener(new View.OnClickListener() {
                                public void onClick(View v) { install(it, action); }
                            });
                            AppDialog.Builder b = new AppDialog.Builder(MarketActivity.this);
                            b.setTitle("安装失败");
                            b.setMessage(r.error == null ? "未知错误" : r.error);
                            b.setPositiveButton("知道了", null);
                            b.show();
                        }
                    }
                });
            }
        }, "market-install").start();
    }

    // ------------------------------------------------------------ 小工具

    private Installer installer() {
        PluginManager pm = PluginHub.get();
        if (pm == null) return null;
        return new Installer(this, pm.store());
    }

    private int dp(float v) { return Theme.dp(this, v); }

    private void toast(String s) { Snack.show(this, s); }
}
