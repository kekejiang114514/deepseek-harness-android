package com.dsh.android.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.android.a11y.A11yService;
import com.dsh.android.kernel.C;
import com.dsh.android.kernel.EventBus;
import com.dsh.android.kernel.SessionStore;
import com.dsh.android.kernel.Updater;
import com.dsh.android.llm.AgentLoop;
import com.dsh.android.llm.HistoryCompactor;
import com.dsh.android.llm.LlmClient;
import com.dsh.android.plugins.PluginManager;
import com.dsh.android.tools.ToolRuntime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/** 主界面：对话流 + 底部输入区 + 会话抽屉（对齐 DSH 设计语言）。 */
public final class MainActivity extends Activity {

    private EventBus bus;
    private PluginManager plugins;
    private ToolRuntime tools;
    private SessionStore sessions;
    private LlmClient client;
    private AgentLoop loop;

    private LinearLayout messages;
    private ScrollView messageScroll;
    private EditText input;
    private TextView sendBtn;
    private TextView titleView;
    private TextView modelPill;
    private android.widget.PopupWindow modelPopup;
    private LinearLayout attachBar;
    private View scrim;
    private LinearLayout drawer;
    private LinearLayout drawerList;

    private volatile boolean running = false;
    private AiBlock streamingView;    // 当前正在流式输出的气泡
    private StringBuilder streamingBuf = new StringBuilder();
    private String lastThemeMode = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 必须在 setContentView 之前：彻底去掉系统标题栏（ActionBar）
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.app.ActionBar ab = getActionBar();
        if (ab != null) ab.hide();
        installCrashGuard();
        try {
            boot();
        } catch (Throwable t) {
            showFatal(t);
        }
    }

    private void boot() {
        Theme.init(this);
        lastThemeMode = Theme.mode(this);

        bus = new EventBus();
        sessions = new SessionStore(this);
        tools = new ToolRuntime(this);
        // 让 ui.ask 工具能把选择题画到当前对话里
        AskBridge.install(new AskBridge.Host() {
            public void present(final AskBridge.Ask ask) {
                runOnUiThread(new Runnable() {
                    public void run() { presentAsk(ask); }
                });
            }
        });
        // 屏幕工具发现无障碍没开时，回到 UI 线程弹一次「去开启」引导
        A11yGuide.install(new A11yGuide.Host() {
            public void showA11yPrompt() {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!isFinishing()) A11yGuide.showDialog(MainActivity.this, true);
                    }
                });
            }
        });
        plugins = new PluginManager(this, bus, tools);
        // 内置插件 + 用户 plugin.json 插件一起装配（目录可能还没有，reload 会建出来）
        plugins.reload();
        // 设置页要操作同一份注册表，否则切换开关影响不到真正跑模型的那套工具
        com.dsh.android.plugins.PluginHub.install(plugins);
        // JS 插件要等 WebView 就绪才能装载，好了之后重扫一次
        bus.on("plugins.js.ready", new EventBus.Listener() {
            public void onEvent(String name, Object data) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (plugins != null && !running) plugins.reload();
                    }
                });
            }
        });

        buildUi();
        reloadClient();
        renderHistory();
        playSplash();
        reportLastCrash();
        maybeCheckUpdate();
    }

    /**
     * 启动时查一次更新。
     *
     * <p>只有「开着自动检查」且距上次检查超过一天才联网——未鉴权的 GitHub API
     * 有 60 次/小时限流，每次切回应用都查既没必要也容易被限流。
     *
     * <p>同一版本用户点过「稍后」就不再弹，只能去设置页手动检查。
     */
    private void maybeCheckUpdate() {
        if (!Updater.autoEnabled(this)) return;
        if (!Updater.autoDue(this)) return;

        Updater.checkAsync(this, new Updater.Callback() {
            public void onResult(final Updater.Release r, String error) {
                if (r == null) return;                              // 已是最新，安静
                if (Updater.isSkipped(MainActivity.this, r.tag)) return;
                runOnUiThread(new Runnable() {
                    public void run() { showUpdateNotice(r); }
                });
            }
        });
    }

    /** 有新版本时的提示：给下载入口，不逼用户自己去找。 */
    private void showUpdateNotice(final Updater.Release r) {
        if (isFinishing()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("当前 ").append(C.VERSION).append(" → ").append(r.version);
        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 0) {
            String[] lines = notes.split("\n");
            StringBuilder cut = new StringBuilder();
            for (int i = 0; i < lines.length && i < 6; i++) cut.append(lines[i]).append("\n");
            if (lines.length > 6) cut.append("…");
            sb.append("\n\n").append(cut.toString().trim());
        }

        com.dsh.android.ui.UpdateSheet.show(this, r);
    }

    private void openExternal(String url) {
        if (url == null || url.length() == 0) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            addNotice("没有可用的浏览器打开下载页");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        String mode = Theme.mode(this);
        if (lastThemeMode != null && !lastThemeMode.equals(mode)) {
            // 设置页里改过主题：这里重建界面并用淡入遮盖，避免颜色错乱
            lastThemeMode = mode;
            Theme.init(this);
            if (!running) {
                buildUi();
                renderHistory();
                fadeInContent();
            }
            return;
        }
        Theme.init(this);
        reloadClient();
        // 用户可能在设置页改了插件开关，或直接改了 plugin.json：
        // 空闲时重扫一次，保证模型下一轮看到的工具是最新的（生成中不动，避免抽掉正在等的工具）
        if (!running && plugins != null) plugins.reload();
        if (titleView != null) titleView.setText(currentTitle());
        // 设置页可能改过模型/等级，回来刷新胶囊上的字
        refreshModelPill();
    }

    private void fadeInContent() {
        View c = findViewById(android.R.id.content);
        if (c == null) return;
        c.setAlpha(0f);
        c.animate().alpha(1f).setDuration(180).start();
    }

    // ------------------------------------------------------------ 构建界面

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.bg());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // 自绘顶栏：显示当前会话标题（系统 ActionBar 已在 onCreate 里去掉）
        col.addView(buildHeader());
        col.addView(buildModelBar());

        // 消息区
        messageScroll = new ScrollView(this);
        messageScroll.setFillViewport(true);
        messageScroll.setClipToPadding(false);
        messageScroll.setPadding(dp(16), dp(6), dp(16), dp(8));
        // 用户手动上滑离开底部后，流式输出不再把他拽回来
        messageScroll.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                    pinnedToBottom = isNearBottom();
                }
                return false;
            }
        });
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setClipChildren(false);
        messageScroll.addView(messages, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(messageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 输入区（R28 面板）
        LinearLayout composerWrap = new LinearLayout(this);
        // 必须是垂直：附件条在上、输入框在下。
        // （漏掉这行会默认水平排列，附件条一出现就把整条输入框挤出屏幕外）
        composerWrap.setOrientation(LinearLayout.VERTICAL);
        composerWrap.setPadding(dp(12), dp(4), dp(12), dp(12));

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(16), dp(6), dp(6), dp(6));
        composer.setBackground(Theme.roundOutlined(
                Theme.surface(), Theme.R_PANEL, Theme.border(), this));

        TextView attachBtn = new TextView(this);
        attachBtn.setText("\u002B");
        attachBtn.setTextSize(22);
        attachBtn.setGravity(Gravity.CENTER);
        attachBtn.setTextColor(Theme.text2());
        attachBtn.setIncludeFontPadding(false);
        attachBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFile(); }
        });
        LinearLayout.LayoutParams attLp = new LinearLayout.LayoutParams(dp(32), dp(40));
        composer.addView(attachBtn, attLp);

        input = new EditText(this);
        input.setHint("给 DShAndroid 发消息");
        input.setHintTextColor(Theme.caption());
        input.setTextColor(Theme.text());
        input.setTextSize(15);
        input.setBackground(null);
        input.setMinLines(1);
        input.setMaxLines(5);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    onSend();
                    return true;
                }
                return false;
            }
        });
        composer.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendBtn = new TextView(this);
        sendBtn.setText("\u2191");
        sendBtn.setTextSize(17);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setTextColor(Theme.onPrimary());
        sendBtn.setBackground(Theme.round(Theme.primary(), 20f, this));
        sendBtn.setIncludeFontPadding(false);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onSend(); }
        });
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        sendLp.setMargins(dp(6), 0, dp(2), 0);
        composer.addView(sendBtn, sendLp);

        // 已选附件：一排可横向滑动的文件卡片
        attachBar = new LinearLayout(this);
        attachBar.setOrientation(LinearLayout.VERTICAL);
        attachBar.setPadding(dp(4), dp(6), dp(4), 0);
        attachBar.setVisibility(View.GONE);
        composerWrap.addView(attachBar);

        composerWrap.addView(composer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(composerWrap);

        // 悬浮按钮挂在内容之上，保证可点击
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 遮罩
        scrim = new View(this);
        scrim.setBackgroundColor(Theme.scrim());
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); }
        });
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 会话抽屉
        drawer = buildDrawer();
        drawer.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                dp(288), ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.gravity = Gravity.START;
        root.addView(drawer, dlp);

        setContentView(root);
    }

    /** 自绘顶栏：☰ | 会话标题 | ⚙。系统 ActionBar 已隐藏，这里显示"这是哪个对话"。 */
    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Theme.bg());
        // content 区已经在状态栏下方（非全屏主题），这里不再额外留白
        bar.setPadding(dp(4), dp(4), dp(4), dp(2));

        TextView menu = iconBtn("\u2630");
        menu.setTextSize(17);
        menu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openDrawer(); }
        });
        bar.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(52)));

        titleView = new TextView(this);
        titleView.setText(currentTitle());
        titleView.setTextSize(16);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Theme.text());
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView gear = iconBtn("\u2699");
        gear.setTextSize(17);
        gear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        bar.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(52)));

        return bar;
    }

    /** 标题下方的模型/推理胶囊：一行小字 + 向下三角，点开是气泡菜单。 */
    private View buildModelBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setBackgroundColor(Theme.bg());
        row.setPadding(0, 0, 0, dp(6));

        modelPill = new TextView(this);
        modelPill.setTextSize(12);
        modelPill.setTextColor(Theme.text2());
        modelPill.setPadding(dp(12), dp(5), dp(11), dp(5));
        modelPill.setBackground(Theme.round(Theme.panel(), Theme.R_XL, this));
        modelPill.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showModelPopup(v); }
        });
        row.addView(modelPill);
        refreshModelPill();
        return row;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE);
    }

    private String currentModel() {
        String m = prefs().getString(C.KEY_MODEL, "");
        if (m == null || m.length() == 0) m = C.MODEL_CHAT;
        return m;
    }

    private static String levelLabel(String k) {
        return com.dsh.android.ui.ReasoningSlider.labelOf(C.normalizeLevel(k));
    }

    private void refreshModelPill() {
        if (modelPill == null) return;
        String lvl = prefs().getString(C.KEY_REASONING, C.LEVEL_BALANCED);
        modelPill.setText(currentModel() + "  ·  " + levelLabel(lvl) + "   \u25BE");
    }

    /** 气泡式下拉：上面模型列表，下面推理等级。 */
    private void showModelPopup(View anchor) {
        if (modelPopup != null && modelPopup.isShowing()) {
            modelPopup.dismiss();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Theme.round(Theme.panel(), Theme.R_XL, this));
        box.setPadding(dp(6), dp(8), dp(6), dp(8));
        fillPopup(box);

        int width = Math.min(dp(300), getResources().getDisplayMetrics().widthPixels - dp(32));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(box);

        final android.widget.PopupWindow pw = new android.widget.PopupWindow(sv,
                width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(null);
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(8));
        modelPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(4));

        // 第一次打开且缓存为空：后台拉一次模型列表
        if (cachedModels().isEmpty() && prefs().getString(C.KEY_API_KEY, "").length() > 0) {
            fetchModelsIntoPopup();
        }
    }

    private void fillPopup(final LinearLayout box) {
        box.removeAllViews();

        box.addView(popupLabel("模型"));
        List<String> models = cachedModels();
        final String cur = currentModel();
        if (models.isEmpty()) {
            box.addView(popupHint("还没有读取到模型列表"));
            box.addView(popupRow("读取模型列表", false, new View.OnClickListener() {
                public void onClick(View v) { fetchModelsIntoPopup(); }
            }));
        } else {
            for (int i = 0; i < models.size(); i++) {
                final String m = models.get(i);
                box.addView(popupRow(m, m.equals(cur), new View.OnClickListener() {
                    public void onClick(View v) {
                        prefs().edit().putString(C.KEY_MODEL, m).apply();
                        applyChoice();
                    }
                }));
            }
        }

        box.addView(dividerLine());

        String curLevel = C.normalizeLevel(prefs().getString(C.KEY_REASONING, C.LEVEL_MEDIUM));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(6), dp(14), dp(2));

        TextView headLabel = new TextView(this);
        headLabel.setText("推理强度");
        headLabel.setTextSize(11);
        headLabel.setTextColor(Theme.caption());
        head.addView(headLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView headValue = new TextView(this);
        headValue.setText(com.dsh.android.ui.ReasoningSlider.labelOf(curLevel));
        headValue.setTextSize(13);
        headValue.setTypeface(null, Typeface.BOLD);
        headValue.setTextColor(Theme.text());
        head.addView(headValue);
        box.addView(head);

        final com.dsh.android.ui.ReasoningSlider slider =
                new com.dsh.android.ui.ReasoningSlider(this);
        slider.setLevel(curLevel);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(14), dp(4), dp(14), dp(2));
        slider.setLayoutParams(slp);

        final TextView desc = new TextView(this);
        desc.setTextSize(11);
        desc.setTextColor(Theme.text3());
        desc.setPadding(dp(14), dp(2), dp(14), dp(8));
        desc.setText(com.dsh.android.ui.ReasoningSlider.descOf(curLevel)
                + "\u3000输出上限 " + C.maxTokensFor(curLevel) + " tokens");
        box.addView(slider);
        box.addView(desc);

        box.addView(dividerLine());

        LinearLayout scopeHead = new LinearLayout(this);
        scopeHead.setOrientation(LinearLayout.HORIZONTAL);
        scopeHead.setGravity(Gravity.CENTER_VERTICAL);
        scopeHead.setPadding(dp(14), dp(6), dp(14), dp(2));
        TextView scopeLabel = new TextView(this);
        scopeLabel.setText("文件作用域");
        scopeLabel.setTextSize(11);
        scopeLabel.setTextColor(Theme.caption());
        scopeHead.addView(scopeLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView scopeValue = new TextView(this);
        scopeValue.setTextSize(13);
        scopeValue.setTypeface(null, Typeface.BOLD);
        scopeValue.setTextColor(Theme.text());
        scopeHead.addView(scopeValue);
        box.addView(scopeHead);

        final boolean device = prefs().getString(C.KEY_FS_SCOPE, C.SCOPE_WORKSPACE)
                .equals(C.SCOPE_DEVICE);
        scopeValue.setText(device ? "全局" : "工作区");

        box.addView(popupRow("工作区　应用私有目录，最安全", !device, new View.OnClickListener() {
            public void onClick(View v) {
                prefs().edit().putString(C.KEY_FS_SCOPE, C.SCOPE_WORKSPACE).apply();
                applyChoice();
            }
        }));
        box.addView(popupRow("全局　可读写整个存储（需授权）", device, new View.OnClickListener() {
            public void onClick(View v) {
                if (!hasStorage()) {
                    if (modelPopup != null) modelPopup.dismiss();
                    requestStorageThenScope();
                    return;
                }
                prefs().edit().putString(C.KEY_FS_SCOPE, C.SCOPE_DEVICE).apply();
                applyChoice();
            }
        }));

        slider.setOnChangeListener(new com.dsh.android.ui.ReasoningSlider.OnChange() {
            public void onChanged(int index, String key) {
                prefs().edit().putString(C.KEY_REASONING, key).apply();
                reloadClient();
                refreshModelPill();
                headValue.setText(com.dsh.android.ui.ReasoningSlider.labelOf(key));
                desc.setText(com.dsh.android.ui.ReasoningSlider.descOf(key)
                        + "\u3000输出上限 " + C.maxTokensFor(key) + " tokens");
                desc.setAlpha(0f);
                desc.animate().alpha(1f).setDuration(180).start();
            }
        });
    }

    private static final int REQ_SCOPE_STORAGE = 1002;
    private boolean pendingDeviceScope = false;

    private boolean hasStorage() {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageThenScope() {
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        pendingDeviceScope = true;
        requestPermissions(new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, REQ_SCOPE_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_SCOPE_STORAGE) return;
        boolean ok = res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (pendingDeviceScope) {
            pendingDeviceScope = false;
            if (ok) {
                prefs().edit().putString(C.KEY_FS_SCOPE, C.SCOPE_DEVICE).apply();
                Snack.show(this, "已切换到全局作用域");
            } else {
                Snack.show(this, "没有存储权限，保持工作区");
            }
        }
    }

    /** 执行插件命令；返回 false 表示没有匹配的命令（按普通消息发出去）。 */
    private boolean runPluginCommand(String text) {
        String body = text.substring(1).trim();
        if (body.length() == 0) return false;
        int sp = body.indexOf(' ');
        final String name = sp < 0 ? body : body.substring(0, sp);
        final String rest = sp < 0 ? "" : body.substring(sp + 1).trim();

        org.json.JSONArray cmds;
        try {
            cmds = com.dsh.android.plugins.js.JsHost.commands();
        } catch (Throwable t) {
            return false;
        }
        String plugin = null;
        for (int i = 0; i < cmds.length(); i++) {
            org.json.JSONObject c = cmds.optJSONObject(i);
            if (c != null && name.equals(c.optString("name"))) {
                plugin = c.optString("plugin");
                break;
            }
        }
        if (plugin == null) return false;

        messages.addView(makeSystemLine("/" + name));
        final String pid = plugin;
        new Thread(new Runnable() {
            public void run() {
                final String res = com.dsh.android.plugins.js.JsHost.runCommand(
                        pid, name, "{\"text\":" + org.json.JSONObject.quote(rest) + "}");
                String out;
                try {
                    org.json.JSONObject o = new org.json.JSONObject(res);
                    out = o.optBoolean("ok") ? o.optString("value", "") : ("命令失败：" + o.optString("error"));
                } catch (Exception e) {
                    out = res;
                }
                final String show = out.length() == 0 ? "（命令没有输出）" : out;
                runOnUiThread(new Runnable() {
                    public void run() {
                        addAssistantBubble(show);
                        scrollBottom();
                    }
                });
            }
        }, "plugin-cmd").start();
        return true;
    }

    /** 居中的系统提示行，用于标记命令执行。 */
    private View makeSystemLine(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(Theme.caption());
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(8), 0, dp(2));
        messages.addView(t);
        return t;
    }

    private void applyChoice() {
        reloadClient();
        refreshModelPill();
        if (modelPopup != null) modelPopup.dismiss();
    }

    private List<String> cachedModels() {
        List<String> out = new java.util.ArrayList<String>();
        try {
            String raw = prefs().getString("models_cache", "");
            if (raw == null || raw.length() == 0) return out;
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i, ""));
        } catch (Exception ignored) {}
        return out;
    }

    private void fetchModelsIntoPopup() {
        final String base = prefs().getString(C.KEY_BASE_URL, C.DEFAULT_BASE_URL);
        final String key = prefs().getString(C.KEY_API_KEY, "");
        if (key.length() == 0) {
            Snack.show(this, "先在设置里填写 API Key");
            return;
        }
        Snack.show(this, "正在读取模型列表…");
        new Thread(new Runnable() {
            public void run() {
                List<String> got = null;
                String err = null;
                try {
                    got = LlmClient.fetchModels(base, key);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final List<String> fg = got;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fg != null && !fg.isEmpty()) {
                            org.json.JSONArray arr = new org.json.JSONArray();
                            for (int i = 0; i < fg.size(); i++) arr.put(fg.get(i));
                            prefs().edit().putString("models_cache", arr.toString()).apply();
                            // 当前模型不在列表里就落到第一个
                            if (!fg.contains(currentModel())) {
                                prefs().edit().putString(C.KEY_MODEL, fg.get(0)).apply();
                                reloadClient();
                                refreshModelPill();
                            }
                            if (modelPopup != null && modelPopup.isShowing()) {
                                fillPopup((LinearLayout) ((android.widget.ScrollView)
                                        modelPopup.getContentView()).getChildAt(0));
                            }
                        } else {
                            Snack.show(MainActivity.this, "读取失败：" + (fe == null ? "空列表" : fe));
                        }
                    }
                });
            }
        }, "models").start();
    }

    private TextView popupLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(Theme.caption());
        t.setPadding(dp(12), dp(8), dp(12), dp(4));
        return t;
    }

    private TextView popupHint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Theme.text3());
        t.setPadding(dp(12), dp(4), dp(12), dp(6));
        return t;
    }

    private View dividerLine() {
        View v = new View(this);
        v.setBackgroundColor(Theme.border());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.setMargins(dp(10), dp(6), dp(10), dp(4));
        v.setLayoutParams(lp);
        return v;
    }

    private TextView popupRow(String text, boolean selected, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(selected ? text + "   \u2713" : text);
        t.setTextSize(13);
        t.setTextColor(selected ? Theme.link() : Theme.text());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setOnClickListener(click);
        return t;
    }

    private TextView popupRowWithSub(String title, String sub, boolean selected) {
        TextView t = new TextView(this);
        t.setText(title + "\n" + sub);
        t.setTextSize(13);
        t.setTextColor(selected ? Theme.link() : Theme.text());
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        return t;
    }

    /** 每次进程只放一次开屏，转屏/重建不重播。 */
    private static boolean splashDone = false;

    /**
     * 开屏动画：屏幕中间一个应用图标，除此之外什么都没有。
     *
     * <p>图标直接从 PackageManager 取（本工程没有 R 类，走资源 ID 反而不方便），
     * 淡入 + 轻微放大，停一下再整体淡出，然后移除遮罩——期间不拦截点击之外的任何东西。
     */
    private void playSplash() {
        if (splashDone) return;
        splashDone = true;

        final android.view.ViewGroup content = findViewById(android.R.id.content);
        if (content == null) return;

        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Theme.bg());
        overlay.setClickable(true);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
        } catch (Throwable ignored) {}
        int size = dp(96);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
        overlay.addView(icon, lp);

        icon.setAlpha(0f);
        icon.setScaleX(0.88f);
        icon.setScaleY(0.88f);

        content.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        icon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).start();

        overlay.postDelayed(new Runnable() {
            public void run() {
                overlay.animate().alpha(0f).setDuration(260).withEndAction(new Runnable() {
                    public void run() {
                        try {
                            content.removeView(overlay);
                        } catch (Throwable ignored) {}
                    }
                }).start();
            }
        }, 620);
    }

    private LinearLayout buildDrawer() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setBackgroundColor(Theme.panel());
        d.setPadding(dp(12), dp(20), dp(12), dp(12));

        TextView head = new TextView(this);
        head.setText("会话");
        head.setTextSize(13);
        head.setTextColor(Theme.caption());
        head.setPadding(dp(8), 0, 0, dp(10));
        d.addView(head);

        TextView add = new TextView(this);
        add.setText("\uFF0B  新建会话");
        add.setTextSize(15);
        add.setTextColor(Theme.text());
        add.setPadding(dp(12), dp(12), dp(12), dp(12));
        add.setBackground(Theme.round(Theme.surface2(), Theme.R_MD, this));
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { newSession(); }
        });
        d.addView(add);

        ScrollView sv = new ScrollView(this);
        drawerList = new LinearLayout(this);
        drawerList.setOrientation(LinearLayout.VERTICAL);
        drawerList.setPadding(0, dp(10), 0, 0);
        sv.addView(drawerList);
        d.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return d;
    }

    /** 悬浮圆形小按钮（无标题栏时叠在内容上使用）。 */
    private TextView iconBtn(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(17);
        t.setTextColor(Theme.text2());
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ------------------------------------------------------------ 抽屉

    private void openDrawer() {
        refreshDrawer();
        scrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-dp(288));
        drawer.animate().translationX(0).setDuration(180).start();
    }

    private void closeDrawer() {
        drawer.animate().translationX(-dp(288)).setDuration(160)
                .withEndAction(new Runnable() {
                    public void run() { drawer.setVisibility(View.GONE); }
                }).start();
        scrim.setVisibility(View.GONE);
    }

    private void refreshDrawer() {
        drawerList.removeAllViews();
        final List<SessionStore.SessionMeta> list = sessions.listSessions();
        final String cur = sessions.currentId();
        for (int i = 0; i < list.size(); i++) {
            final SessionStore.SessionMeta m = list.get(i);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), dp(11), dp(8), dp(11));
            boolean sel = m.id.equals(cur);
            item.setBackground(Theme.round(sel ? Theme.surface2() : 0x00000000,
                    Theme.R_MD, this));

            TextView t = new TextView(this);
            t.setText(m.title == null || m.title.length() == 0 ? "新对话" : m.title);
            t.setTextSize(14);
            t.setTextColor(sel ? Theme.text() : Theme.text2());
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            item.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView del = new TextView(this);
            del.setText("\u00D7");
            del.setTextSize(15);
            del.setTextColor(Theme.caption());
            del.setPadding(dp(10), 0, dp(6), 0);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { confirmDelete(m); }
            });
            item.addView(del);

            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { switchTo(m.id); }
            });
            drawerList.addView(item);
        }
    }

    private void newSession() {
        if (running) loop.cancel();
        AskBridge.cancelPending();   // 卡片会随界面重建消失，别让工具线程干等
        sessions.newSession("新对话");
        closeDrawer();
        renderHistory();
        updateTitle(currentTitle());
    }

    private void switchTo(String id) {
        if (running) loop.cancel();
        AskBridge.cancelPending();
        sessions.setCurrent(id);
        closeDrawer();
        renderHistory();
        updateTitle(currentTitle());
    }

    private void confirmDelete(final SessionStore.SessionMeta m) {
        new AppDialog.Builder(this)
                .setTitle("删除会话")
                .setMessage("删除后无法恢复，确定吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (m.id.equals(sessions.currentId()) && running) loop.cancel();
                        sessions.deleteSession(m.id);
                        refreshDrawer();
                        renderHistory();
                        updateTitle(currentTitle());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 更新顶栏标题（带淡入，避免生硬跳动）。 */
    private void updateTitle(String t) {
        if (titleView == null) return;
        titleView.setText(t == null || t.length() == 0 ? "新对话" : t);
        titleView.setAlpha(0f);
        titleView.animate().alpha(1f).setDuration(160).start();
    }

    private String currentTitle() {
        String id = sessions.currentId();
        List<SessionStore.SessionMeta> all = sessions.listSessions();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                String t = all.get(i).title;
                return t == null || t.length() == 0 ? "新对话" : t;
            }
        }
        return "DShAndroid";
    }

    // ------------------------------------------------------------ 消息渲染

    private void renderHistory() {
        messages.removeAllViews();
        JSONArray hist = sessions.loadCurrentHistory();
        // 卡片直接从历史里重建：问题和选项在 tool_calls 参数里，
        // 用户的选择在紧跟着的 tool 结果里，一份数据就够
        java.util.Map<Integer, java.util.List<AskLog.Card>> cards = AskLog.byIndex(hist);

        int shown = 0;
        for (int i = 0; i < hist.length(); i++) {
            JSONObject o = hist.optJSONObject(i);
            if (o == null) continue;
            String role = o.optString("role");
            String content = o.optString("content", "");
            if ("user".equals(role)) {
                addUserBubble(content);
                shown++;
            } else if ("assistant".equals(role) && content.length() > 0) {
                addAssistantBubble(content);
                shown++;
            }
            // 这条 assistant 消息里若调用过 ui.ask，卡片补在它下面
            java.util.List<AskLog.Card> here = cards.get(Integer.valueOf(i));
            if (here != null) {
                for (int k = 0; k < here.size(); k++) {
                    addAskReplay(here.get(k));
                    shown++;
                }
            }
        }
        if (shown == 0) addWelcome();
        scrollBottom();
    }

    /** 历史里的选择题：只读回放（选项和答案都来自历史本身）。 */
    private void addAskReplay(AskLog.Card c) {
        if (c == null || c.question.length() == 0 || c.options.length == 0) return;
        messages.addView(buildAskCard(c.question, c.options, c.multi,
                c.answered() ? c.answer : "", null));
    }

    private void addWelcome() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(80), 0, 0);

        TextView logo = new TextView(this);
        logo.setText("\uD83D\uDC0B");
        logo.setTextSize(40);
        logo.setGravity(Gravity.CENTER);
        box.addView(logo);

        TextView h = new TextView(this);
        h.setText("有什么可以帮你？");
        h.setTextSize(20);
        h.setTypeface(null, Typeface.BOLD);
        h.setTextColor(Theme.text());
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, dp(14), 0, 0);
        box.addView(h);

        TextView sub = new TextView(this);
        sub.setText("我可以操作你的手机屏幕、读写文件");
        sub.setTextSize(13);
        sub.setTextColor(Theme.caption());
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, 0);
        box.addView(sub);

        messages.addView(box);
    }

    /** 用户消息：右对齐浅色气泡，R20，最大宽度 82%。 */
    private void addUserBubble(String text) {
        addUserBubble(text, null);
    }

    /** 用户消息：附件卡片在上，正文在下。 */
    private void addUserBubble(String text, java.util.List<Attach> files) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(8), 0, dp(4));

        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        if (files != null) {
            for (int i = 0; i < files.size(); i++) {
                Attach a = files.get(i);
                LinearLayout card = attachMessageCard(a);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.setMargins(0, 0, 0, dp(4));
                col.addView(card, clp);
            }
        }

        if (text != null && text.length() > 0) {
            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextSize(15);
            tv.setTextColor(Theme.text());
            tv.setLineSpacing(dp(3), 1f);
            tv.setPadding(dp(14), dp(10), dp(14), dp(10));
            tv.setBackground(Theme.round(Theme.surface2(), Theme.R_XL, this));
            enableCopyText(tv, text);
            tv.setMaxWidth(maxW);
            col.addView(tv);
        }

        row.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.setMinimumWidth(Math.min(maxW, dp(160)));
        messages.addView(row);
    }

    /** 消息里的附件卡片：比输入区的大一些，带完整信息。 */
    private LinearLayout attachMessageCard(final Attach a) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Theme.round(Theme.surface(), Theme.R_LG, this));
        card.setPadding(dp(10), dp(9), dp(12), dp(9));

        card.addView(typeBadge(a));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(a.name);
        name.setTextSize(13);
        name.setTextColor(Theme.text());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(name);
        TextView meta = new TextView(this);
        meta.setText(humanSize(a.bytes) + " · " + typeLabel(a.name)
                + (a.content.startsWith("[二进制文件") ? " · 二进制" : " · 文本 " + a.content.length() + " 字"));
        meta.setTextSize(11);
        meta.setTextColor(Theme.caption());
        meta.setPadding(0, dp(2), 0, 0);
        texts.addView(meta);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(dp(8), 0, 0, 0);
        card.addView(texts, tlp);

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showAttachPreview(a); }
        });
        return card;
    }

    /** AI 消息：左对齐，按 Markdown 渲染（粗体 / 列表 / 表格 / 代码块）。 */
    private AiBlock addAssistantBubble(String text) {
        AiBlock block = new AiBlock(this);
        block.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        block.setLayoutParams(lp);
        block.setMarkdown(text, true);
        enableCopy(block);
        messages.addView(block);
        return block;
    }

    /**
     * 可复用 Markdown 容器。
     *
     * 流式输出时不能每收到一个 token 就重建整棵 View 树（会明显卡顿），
     * 所以这里做两级处理：内容没变就不重绘；变化时 120ms 节流一次。
     */
    private class AiBlock extends LinearLayout {
        private String src = "";
        private long lastAt = 0;
        private boolean scheduled = false;

        AiBlock(Context c) {
            super(c);
            setOrientation(VERTICAL);
        }

        String text() { return src; }

        void setMarkdown(final String s, boolean done) {
            src = s == null ? "" : s;
            if (done) {
                lastAt = 0;
                renderNow();
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastAt >= 120) {
                lastAt = now;
                renderNow();
            } else if (!scheduled) {
                scheduled = true;
                postDelayed(new Runnable() {
                    public void run() {
                        scheduled = false;
                        lastAt = System.currentTimeMillis();
                        renderNow();
                    }
                }, 120);
            }
        }

        private void renderNow() {
            try {
                Md.render(getContext(), AiBlock.this, src);
            } catch (Throwable t) {
                // 渲染器出问题时退化到纯文本，绝不因为排版挂掉整个对话
                removeAllViews();
                TextView t2 = new TextView(getContext());
                t2.setTextSize(15);
                t2.setTextColor(Theme.text());
                t2.setLineSpacing(dp(4), 1f);
                t2.setTextIsSelectable(true);
                t2.setText(src);
                addView(t2);
            }
        }
    }

    /** 工具调用：一行小字灰卡。 */
    /** 工具调用：可点击展开的折叠卡片（仿 Minis）。 */
    private void addToolLine(String name, boolean okFlag, final String detail) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(7), dp(10), dp(7));
        box.setBackground(Theme.roundOutlined(
                okFlag ? Theme.surface() : 0x1AEC1313,
                Theme.R_SM, okFlag ? Theme.border() : Theme.danger(), this));

        String mark = okFlag ? "\u2713" : "\u2715";
        String label = okFlag ? "完成" : "失败";
        final TextView head = new TextView(this);
        head.setText(mark + " " + friendlyTool(name) + "\u2003" + label);
        head.setTextSize(12);
        head.setTextColor(okFlag ? Theme.text2() : Theme.danger());
        box.addView(head);

        final TextView body = new TextView(this);
        String d = detail == null ? "" : detail.trim();
        if (d.length() > 700) d = d.substring(0, 700) + "\n\u2026";
        body.setText(d);
        body.setTextSize(11);
        body.setTextColor(Theme.text3());
        body.setPadding(0, dp(6), 0, 0);
        body.setVisibility(View.GONE);
        body.setTextIsSelectable(true);
        box.addView(body);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean show = body.getVisibility() != View.VISIBLE;
                body.setVisibility(show ? View.VISIBLE : View.GONE);
                head.setTextColor(show ? Theme.text() : Theme.text2());
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        messages.addView(box, lp);
    }

    /**
     * 居中细灰字提示（不参与对话语义，只做状态说明）。
     * 上下文压缩、无障碍未开启这类「系统层」变化用这个展示。
     */
    private void addNotice(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(Theme.text3());
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dp(12), dp(4), dp(12), dp(4));
        tv.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(8));
        messages.addView(tv, lp);
    }

    private static String friendlyTool(String name) {
        if (name == null) return "工具";
        if (name.startsWith("a11y.")) {
            String a = name.substring(5);
            if (a.equals("dump")) return "读取屏幕";
            if (a.equals("click")) return "点击";
            if (a.equals("settext")) return "输入文字";
            if (a.equals("scroll")) return "滚动";
            if (a.equals("back")) return "返回";
            if (a.equals("home")) return "回到桌面";
            if (a.equals("tap")) return "点击坐标";
            if (a.equals("swipe")) return "滑动";
            if (a.equals("longclick")) return "长按";
            if (a.equals("findtext")) return "查找文字";
            return a;
        }
        if (name.startsWith("fs.")) {
            String a = name.substring(3);
            if (a.equals("list")) return "列目录";
            if (a.equals("read")) return "读文件";
            if (a.equals("write")) return "写文件";
            if (a.equals("mkdir")) return "建目录";
            if (a.equals("delete")) return "删除";
            if (a.equals("info")) return "文件信息";
            return a;
        }
        if (name.startsWith("web.search")) return "联网搜索";
        if (name.startsWith("web.fetch")) return "读取网页";
        if (name.startsWith("notify.")) return "发送通知";
        return name;
    }

    /** 是否「贴底」：用户在底部附近时，新内容才自动跟随滚动。 */
    private boolean pinnedToBottom = true;

    private void scrollBottom() {
        pinnedToBottom = true;
        messageScroll.post(new Runnable() {
            public void run() { messageScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    /** 判断当前是否停在底部附近（容差 24dp）。 */
    private boolean isNearBottom() {
        View child = messageScroll.getChildAt(0);
        if (child == null) return true;
        int max = child.getBottom() - messageScroll.getHeight() + messageScroll.getPaddingBottom();
        return messageScroll.getScrollY() >= max - dp(24);
    }

    /** 流式输出时使用：用户往上翻看历史就不强行拽回底部（仿 Minis）。 */
    private void scrollBottomIfPinned() {
        if (!pinnedToBottom) return;
        messageScroll.post(new Runnable() {
            public void run() { messageScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    // ------------------------------------------------------------ 发送

    private void reloadClient() {
        SharedPreferences sp = getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE);
        String base = sp.getString(C.KEY_BASE_URL, C.DEFAULT_BASE_URL);
        String key = sp.getString(C.KEY_API_KEY, "");
        String model = sp.getString(C.KEY_MODEL, "");
        if (model == null || model.length() == 0) model = C.MODEL_CHAT;
        String level = C.normalizeLevel(sp.getString(C.KEY_REASONING, C.LEVEL_MEDIUM));
        int maxTokens = C.maxTokensFor(level);

        client = new LlmClient(base, key, model, maxTokens);
        loop = new AgentLoop(client, tools, buildSystemPrompt());
    }

    private String buildSystemPrompt() {
        boolean device = tools.isDeviceScope();
        boolean a11y = tools.isA11yEnabled() && A11yService.isReady();
        StringBuilder sb = new StringBuilder();
        sb.append("你是运行在 Android 手机上的 AI 助手 DShAndroid。用简洁准确的中文回答。\n\n");
        sb.append("【文件作用域】当前是")
          .append(device ? "「全局」模式，可访问整个设备文件" : "「工作区」模式，只能访问应用私有工作区")
          .append("。用 fs.* 工具读写；")
          .append(device ? "绝对路径可直接用，/sdcard 是内部存储。\n" : "相对路径基于工作区根。\n");
        sb.append("【屏幕操作】");
        if (a11y) {
            sb.append("无障碍已开启。流程：a11y.dump 读节点树 → a11y.click 按索引操作；")
              .append("坐标操作用 a11y.tap / swipe；输入用 a11y.settext；返回用 a11y.back。\n");
        } else {
            sb.append("无障碍不可用，无法操作界面，请让用户去设置里开启。\n");
        }
        sb.append("工具失败要如实说明，不要编造结果。\n");
        sb.append("【问用户】");
        sb.append("当存在多个都可行的方案、需要用户拍板，或缺少只有用户才知道的信息（偏好、账号、路径、要删哪些）时，")
          .append("调用 ui__ask 给出 2-6 个选项让用户点选，不要自己替用户决定，也不要只在正文里提问等回复。\n")
          .append("调用前先在正文里用一两句话把情况说清（选项卡片会接在这段话下面显示），")
          .append("每个选项写清做法和主要代价，让用户一眼能选。")
          .append("调用后本轮先停下，等工具返回 selected 再继续执行。");
        return sb.toString();
    }

    private void onSend() {
        if (running) {
            loop.cancel();
            // 工具线程可能正卡在「等你选」上，一并放行，否则会干等到超时
            AskBridge.cancelPending("用户已停止");
            Snack.show(this, "正在停止…");
            return;
        }
        final String typed = input.getText().toString().trim();
        // 允许「只发附件」：有附件时文本可以为空
        if (typed.length() == 0 && attaches.isEmpty()) return;

        // 插件命令：以 / 开头，交给注册了该名字的插件处理
        if (typed.startsWith("/") && attaches.isEmpty()) {
            if (runPluginCommand(typed)) {
                input.setText("");
                return;
            }
        }

        if (messages.getChildCount() == 1) messages.removeAllViews();

        final String text = withAttachment(typed);
        // 先快照，后面清空列表还要用来画卡片
        final java.util.List<Attach> sent = new java.util.ArrayList<Attach>(attaches);

        input.setText("");
        clearAttachment();
        addUserBubble(typed, sent);
        scrollBottom();

        // 会话标题：首条消息作为标题
        String id = sessions.currentId();
        List<SessionStore.SessionMeta> all = sessions.listSessions();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                String t = all.get(i).title;
                // "新会话" 是旧版本写入的默认名，一并视为未命名
                if (t == null || t.length() == 0
                        || "新对话".equals(t) || "新会话".equals(t)) {
                    sessions.renameSession(id, shorten(text));
                    updateTitle(currentTitle());
                }
                break;
            }
        }

        final JSONArray hist = sessions.loadCurrentHistory();

        // 不再预建空气泡：等第一段文字到达时才创建，
        // 这样「文字 → 工具 → 卡片 → 文字」能按真实顺序穿插
        streamingBuf.setLength(0);
        streamingView = null;
        scrollBottom();

        loop.setSystemPrompt(buildSystemPrompt());
        running = true;
        setSendMode(true);

        new Thread(new Runnable() {
            public void run() {
                try {
                    loop.run(hist, text, sink());
                    sessions.saveHistory(sessions.currentId(), hist);
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            addAssistantBubble("请求失败：" + t.getMessage());
                            scrollBottom();
                        }
                    });
                } finally {
                    runOnUiThread(new Runnable() {
                        public void run() { finishRun(); }
                    });
                }
            }
        }).start();
    }

    // ---------- 文件上传 ----------

    private static final int REQ_PICK_FILE = 2001;

    /** 打开系统文件选择器（仿 Minis 的上传入口）。 */
    /** 单个附件。一次能选多个文件，所以不能用两个字段硬存。 */
    private static final class Attach {
        final String name;
        final String content;
        final int bytes;

        Attach(String name, String content, int bytes) {
            this.name = name;
            this.content = content;
            this.bytes = bytes;
        }
    }

    private final java.util.ArrayList<Attach> attaches = new java.util.ArrayList<Attach>();

    private static final int MAX_FILES = 8;              // 单次消息最多几个文件
    private static final int PER_FILE_CHARS = 40000;     // 单个文件内联字符上限
    private static final int TOTAL_CHARS = 120000;       // 全部附件合计上限

    private void pickFile() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(it, REQ_PICK_FILE);
        } catch (Exception e) {
            try {
                Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                it.setType("*/*");
                it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(it, "选择文件"), REQ_PICK_FILE);
            } catch (Exception e2) {
                Snack.show(this, "无法打开文件选择器");
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_PICK_FILE || result != RESULT_OK || data == null) return;

        // 多选的结果在 clipData 里；单选才走 getData()。
        // 有些文件管理器多选时两者都给，所以要优先看 clipData 的条目数。
        java.util.ArrayList<android.net.Uri> uris = new java.util.ArrayList<android.net.Uri>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null && clip.getItemCount() > 0) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                android.net.Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        int added = 0;
        String firstError = null;
        for (int i = 0; i < uris.size(); i++) {
            if (attaches.size() >= MAX_FILES) {
                Snack.show(this, "最多附带 " + MAX_FILES + " 个文件");
                break;
            }
            String err = addAttach(uris.get(i));
            if (err == null) added++;
            else if (firstError == null) firstError = err;
        }

        if (added > 0) {
            showAttachment();
            if (added > 1) {
                Snack.show(this, "已添加 " + added + " 个文件");
            }
        } else if (firstError != null) {
            Snack.show(this, firstError);
        }
    }

    /** 读一个 uri 进附件列表。成功返回 null，失败返回给用户看的原因。 */
    private String addAttach(android.net.Uri uri) {
        String name = displayName(uri);
        byte[] raw;
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return "无法打开 " + name;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 2 * 1024 * 1024) {   // 2MB 上限，和之前一致
                    in.close();
                    return name + " 太大（单文件上限 2MB）";
                }
                bos.write(buf, 0, n);
            }
            in.close();
            raw = bos.toByteArray();
        } catch (Exception e) {
            return "读取 " + name + " 失败：" + e.getMessage();
        }

        String content;
        if (looksBinary(raw)) {
            // 二进制没法内联，只留一行描述，告诉模型「有这个文件但看不到内容」
            content = "[二进制文件 " + name + "，" + humanSize(raw.length)
                    + "，无法作为文本内联发送]";
        } else {
            try {
                content = new String(raw, "UTF-8");
            } catch (java.io.UnsupportedEncodingException ue) {
                content = new String(raw);   // 理论上不会发生，兜底不抛
            }
            if (content.length() > PER_FILE_CHARS) {
                content = content.substring(0, PER_FILE_CHARS) + "\n…（单文件过长已截断）";
            }
        }

        int used = attachChars();
        int room = TOTAL_CHARS - used;
        if (room < 200) return "附件内容已达上限，请先移除一些文件";
        if (content.length() > room) {
            content = content.substring(0, room) + "\n…（超出合计上限已截断）";
        }

        attaches.add(new Attach(name, content, raw.length));
        return null;
    }

    private String displayName(android.net.Uri uri) {
        String name = "文件";
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (ni >= 0 && c.moveToFirst()) {
                    String n = c.getString(ni);
                    if (n != null && n.length() > 0) name = n;
                }
                c.close();
            }
        } catch (Exception ignored) {}
        if ("文件".equals(name) && uri.getLastPathSegment() != null) {
            String seg = uri.getLastPathSegment();
            int slash = seg.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < seg.length()) name = seg.substring(slash + 1);
        }
        return name;
    }

    private int attachChars() {
        int n = 0;
        for (int i = 0; i < attaches.size(); i++) n += attaches.get(i).content.length();
        return n;
    }

    /** 简单的二进制判断：含 NUL 字节或大量不可打印字符就当作二进制。 */
    private static boolean looksBinary(byte[] b) {
        int check = Math.min(b.length, 4096);
        int bad = 0;
        for (int i = 0; i < check; i++) {
            int v = b[i] & 0xFF;
            if (v == 0) return true;
            if (v < 9 || (v > 13 && v < 32)) bad++;
        }
        return check > 0 && bad * 100 / check > 5;
    }

    private static String humanSize(int bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / 1048576.0);
    }

    private void showAttachment() {
        if (attachBar == null) return;
        attachBar.removeAllViews();
        if (attaches.isEmpty()) {
            attachBar.setVisibility(View.GONE);
            return;
        }
        attachBar.setVisibility(View.VISIBLE);

        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < attaches.size(); i++) {
            final Attach a = attaches.get(i);
            View chip = attachChip(a, new Runnable() {
                public void run() {
                    attaches.remove(a);
                    showAttachment();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(6), 0);
            chips.addView(chip, lp);
        }
        hsv.addView(chips);
        attachBar.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText(attaches.size() + " 个文件 · 点卡片可预览，✕ 移除");
        hint.setTextSize(11);
        hint.setTextColor(Theme.caption());
        hint.setPadding(dp(6), dp(5), 0, 0);
        attachBar.addView(hint);
    }

    /** 输入区里的一个小卡片：类型徽标 + 文件名 + 大小 + 移除。 */
    private View attachChip(final Attach a, final Runnable onRemove) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(Theme.round(Theme.surface2(), Theme.R_LG, this));
        chip.setPadding(dp(10), dp(7), dp(6), dp(7));

        chip.addView(typeBadge(a));

        TextView name = new TextView(this);
        name.setText(a.name);
        name.setTextSize(12);
        name.setTextColor(Theme.text());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        name.setMaxWidth(dp(130));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(dp(7), 0, dp(6), 0);
        chip.addView(name, nlp);

        TextView size = new TextView(this);
        size.setText(humanSize(a.bytes));
        size.setTextSize(11);
        size.setTextColor(Theme.caption());
        chip.addView(size);

        TextView close = new TextView(this);
        close.setText("\u2715");
        close.setTextSize(13);
        close.setTextColor(Theme.text3());
        close.setPadding(dp(9), dp(2), dp(3), dp(2));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (onRemove != null) onRemove.run();
            }
        });
        chip.addView(close);

        chip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showAttachPreview(a); }
        });
        return chip;
    }

    /** 文件类型徽标：取扩展名，太长就取首字母。 */
    private View typeBadge(Attach a) {
        TextView badge = new TextView(this);
        badge.setText(typeLabel(a.name));
        badge.setTextSize(9);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setTextColor(Theme.onPrimary());
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Theme.round(Theme.primary(), Theme.R_SM, this));
        badge.setPadding(dp(5), dp(3), dp(5), dp(3));
        badge.setMinWidth(dp(26));
        return badge;
    }

    private static String typeLabel(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= (name == null ? 0 : name.length())) return "FILE";
        String ext = name.substring(dot + 1).toUpperCase();
        if (ext.length() > 4) ext = ext.substring(0, 4);
        return ext;
    }

    /** 点卡片看内容：文件信息 + 前若干行预览。 */
    private void showAttachPreview(final Attach a) {
        StringBuilder sb = new StringBuilder();
        sb.append("大小 ").append(humanSize(a.bytes))
          .append(" · 文本 ").append(a.content.length()).append(" 字");
        if (a.content.startsWith("[二进制文件")) {
            sb.append("\n\n这是二进制文件，内容不会内联发送，模型只会知道它的存在。");
        } else {
            sb.append("\n\n");
            String body = a.content;
            int lines = 0;
            int cut = 0;
            while (cut < body.length() && lines < 60) {
                if (body.charAt(cut) == '\n') lines++;
                cut++;
            }
            sb.append(body, 0, cut);
            if (cut < body.length()) sb.append("\n…（预览只显示前 60 行）");
        }

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12);
        tv.setTextColor(Theme.text());
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(8), dp(20), dp(4));
        if (!a.content.startsWith("[二进制文件")) {
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);

        new AppDialog.Builder(this)
                .setTitle(a.name)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNegativeButton("移除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        attaches.remove(a);
                        showAttachment();
                    }
                })
                .show();
    }

    private void clearAttachment() {
        attaches.clear();
        showAttachment();
    }

    /** 把附件内容包成结构化文本，随消息一起发给模型。多个文件各自成块。 */
    private String withAttachment(String text) {
        if (attaches.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        if (text != null && text.trim().length() > 0) {
            sb.append(text.trim()).append("\n\n");
        }
        for (int i = 0; i < attaches.size(); i++) {
            Attach a = attaches.get(i);
            if (attaches.size() > 1) {
                sb.append("【文件 ").append(i + 1).append("/").append(attaches.size())
                  .append("：").append(a.name).append("】\n");
            } else {
                sb.append("【用户上传的文件：").append(a.name).append("】\n");
            }
            sb.append("```\n").append(a.content).append("\n```\n\n");
        }
        return sb.toString().trim();
    }

    private String attachSummary() {
        if (attaches.isEmpty()) return "";
        if (attaches.size() == 1) return "\uD83D\uDCCE " + attaches.get(0).name;
        StringBuilder sb = new StringBuilder("\uD83D\uDCCE " + attaches.size() + " 个文件");
        for (int i = 0; i < attaches.size(); i++) {
            sb.append("\n   ").append(attaches.get(i).name);
        }
        return sb.toString();
    }

    /** 发送按钮在「发送 ↑」与「停止 ■」之间切换（仿 Minis）。 */
    private void setSendMode(boolean stop) {
        if (sendBtn == null) return;
        if (stop) {
            sendBtn.setText("\u25A0");
            sendBtn.setTextSize(14);
            sendBtn.setTextColor(Theme.onPrimary());
            sendBtn.setBackground(Theme.round(Theme.primary(), 20f, this));
        sendBtn.setIncludeFontPadding(false);
        } else {
            sendBtn.setText("\u2191");
            sendBtn.setTextSize(18);
            sendBtn.setTextColor(Theme.onPrimary());
            sendBtn.setBackground(Theme.round(Theme.primary(), 20f, this));
        sendBtn.setIncludeFontPadding(false);
        }
    }

    /** 用户消息长按：仅复制。 */
    private void enableCopyText(TextView tv, final String text) {
        tv.setTextIsSelectable(true);
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", text));
                Snack.show(MainActivity.this, "已复制");
                return true;
            }
        });
    }

    /** 长按 AI 消息：复制 / 重新生成（仿 Minis 的消息操作）。 */
    private void enableCopy(View block) {
        block.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showMessageMenu(v);
                return true;
            }
        });
    }

    private void showMessageMenu(final View v) {
        final String text = v instanceof AiBlock ? ((AiBlock) v).text() : "";

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(4), dp(4), dp(4), dp(4));

        menu.addView(menuItem("复制", new View.OnClickListener() {
            public void onClick(View x) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", text));
                Snack.show(MainActivity.this, "已复制");
            }
        }));
        menu.addView(menuItem("重新生成", new View.OnClickListener() {
            public void onClick(View x) { regen(); }
        }));

        new AppDialog.Builder(this).setView(menu).setCancelable(true).show();
    }

    private TextView menuItem(String text, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(Theme.text());
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        t.setOnClickListener(click);
        return t;
    }

    /** 重新生成最后一条回答：删掉末尾的 assistant 轮次，用上一条用户消息重跑。 */
    private void regen() {
        if (running) {
            Snack.show(this, "正在生成，先停止再重试");
            return;
        }
        JSONArray hist = sessions.loadCurrentHistory();
        // 从尾部去掉 assistant 消息，找到最后一条 user
        int cut = -1;
        for (int i = hist.length() - 1; i >= 0; i--) {
            JSONObject o = hist.optJSONObject(i);
            if (o == null) continue;
            if ("assistant".equals(o.optString("role"))) { cut = i; continue; }
            if ("user".equals(o.optString("role"))) break;
        }
        if (cut < 0) {
            Snack.show(this, "没有可重新生成的回答");
            return;
        }
        String lastUser = "";
        JSONArray kept = new JSONArray();
        for (int i = 0; i < cut; i++) {
            JSONObject o = hist.optJSONObject(i);
            if (o != null) {
                kept.put(o);
                if ("user".equals(o.optString("role"))) lastUser = o.optString("content", "");
            }
        }
        if (lastUser.length() == 0) return;
        sessions.saveHistory(sessions.currentId(), kept);
        renderHistory();
        resend(lastUser, kept);
    }

    /** 用给定文本与历史再跑一轮（重新生成复用）。 */
    private void resend(final String text, final JSONArray hist) {
        streamingBuf.setLength(0);
        streamingView = null;
        scrollBottom();
        running = true;
        setSendMode(true);

        new Thread(new Runnable() {
            public void run() {
                try {
                    loop.run(hist, text, sink());
                    sessions.saveHistory(sessions.currentId(), hist);
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            addAssistantBubble("请求失败：" + t.getMessage());
                        }
                    });
                } finally {
                    runOnUiThread(new Runnable() {
                        public void run() { finishRun(); }
                    });
                }
            }
        }).start();
    }

    /** 收尾：恢复按钮、补最后一次完整渲染。 */
    private void finishRun() {
        running = false;
        setSendMode(false);
        closeTextSegment();
        scrollBottom();
    }

    private AgentLoop.Sink sink() {
        return new AgentLoop.Sink() {
            public void onDelta(final String delta) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        // 上一段在工具调用处已收尾，这里才是新一段的开头
                        if (streamingView == null) {
                            streamingBuf.setLength(0);
                            streamingView = addAssistantBubble("");
                        }
                        streamingBuf.append(delta);
                        streamingView.setMarkdown(streamingBuf.toString(), false);
                        scrollBottomIfPinned();
                    }
                });
            }
            public void onToolCall(final String name, final String args) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        // 先把这段文字定稿，工具行/卡片才会排在它下面
                        closeTextSegment();
                        // ui.ask 的视觉就是那张选项卡片，不用再插一行工具日志
                        if (!"ui.ask".equals(name)) addToolLine(name, true, args);
                        scrollBottomIfPinned();
                    }
                });
            }
            public void onToolResult(final String name, final String result) {
                if (result != null && result.contains("\"ok\":false")) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            closeTextSegment();
                            addToolLine(name, false, result);
                            scrollBottomIfPinned();
                        }
                    });
                }
            }
            public void onError(final String msg) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        closeTextSegment();
                        addAssistantBubble("出错了：" + msg);
                        scrollBottom();
                    }
                });
            }
            @Override
            public void onCompact(final HistoryCompactor.Report r) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        // 压缩点就是新的分段点：后面的文字不该再接在压缩前的气泡上
                        closeTextSegment();
                        addNotice("↻ " + r.describe());
                        scrollBottomIfPinned();
                    }
                });
            }
        };
    }

    /**
     * 结束当前文字段。
     *
     * <p>一次回答里可能「说一段 → 调工具 → 再说一段」。如果不在这里收尾，
     * 后面新增的工具行和选择卡片会排在旧气泡之后，而流式文字还会继续写进
     * 那个旧气泡（位置在卡片上方），读起来就乱了。收尾后下一段会新起一个块。
     */
    private void closeTextSegment() {
        if (streamingView == null) return;
        if (streamingView.text().length() == 0) {
            messages.removeView(streamingView);
        } else {
            streamingView.setMarkdown(streamingView.text(), true);
        }
        streamingView = null;
        streamingBuf.setLength(0);
    }

    /**
     * 渲染 AI 的提问卡片（内联在对话流里，仿选择题）。
     *
     * <p>用户点选后卡片就地变灰、选中项打勾，然后放行后台等待的工具线程。
     * 不做成系统弹窗，这样选完还能在历史里看到当时的选项。
     */
    // ---------- 选择题卡片 ----------
    // 卡片既是「正在等你选」的交互控件，也是历史里的一条记录。
    // 同一套渲染代码服务两种状态：live（可点）和只读（重绘历史时显示当时选了什么）。

    private void presentAsk(final AskBridge.Ask ask) {
        // 先把正在流式的这段文字定稿，卡片才会紧跟在它下面（而不是压到旧气泡上方）
        closeTextSegment();

        final LinearLayout card = buildAskCard(ask.question, ask.options, ask.multi,
                null, ask);
        messages.addView(card);
        scrollBottom();

        // 卡片出现时轻微上浮，提示「这里要你操作」
        card.setAlpha(0f);
        card.setTranslationY(dp(8));
        card.animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    /**
     * 画一张选择题卡片。
     *
     * @param answer 非 null 表示这是历史回放（已定局，只展示不给点）
     * @param live   非 null 表示这一张要能点，选完直接回写给等待中的工具线程
     */
    private LinearLayout buildAskCard(String questionText, String[] options, boolean multi,
                                      String answer, final AskBridge.Ask live) {
        final boolean readonly = live == null;
        final boolean answered = answer != null && answer.length() > 0;

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.roundOutlined(
                Theme.surface2(), Theme.R_PANEL, Theme.border(), this));
        card.setPadding(dp(14), dp(12), dp(14), dp(10));

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(10), 0, dp(12));
        card.setLayoutParams(clp);

        final TextView tag = new TextView(this);
        tag.setTextSize(11);
        if (answered) {
            tag.setText("已选择");
            tag.setTextColor(Theme.primary());
        } else if (readonly) {
            // 历史里没选过的（超时、切走、停止），给个明确说法
            tag.setText("未选择");
            tag.setTextColor(Theme.text3());
        } else {
            tag.setText("需要你确认");
            tag.setTextColor(Theme.text3());
        }
        card.addView(tag);

        TextView question = new TextView(this);
        question.setText(questionText);
        question.setTextSize(15);
        question.setTextColor(Theme.text());
        question.setLineSpacing(dp(3), 1f);
        question.setPadding(0, dp(5), 0, dp(10));
        card.addView(question);

        final List<TextView> rows = new ArrayList<TextView>();
        // 已答时把 answer 拆开（多选用「、」连接）
        final java.util.HashSet<String> picked = new java.util.HashSet<String>();
        if (answered) {
            String[] parts = answer.split("、");
            for (int i = 0; i < parts.length; i++) picked.add(parts[i].trim());
        }

        for (int i = 0; i < options.length; i++) {
            final String opt = options[i];
            final TextView row = new TextView(this);
            row.setText(opt);
            row.setTextSize(14);
            row.setPadding(dp(12), dp(11), dp(12), dp(11));

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(6));
            row.setLayoutParams(rlp);

            boolean sel = picked.contains(opt);
            row.setTag(Boolean.valueOf(sel));
            if (answered || readonly) {
                // 定局状态：整体锁定，选中项高亮
                applyChoiceStyle(row, sel, true);
                row.setClickable(false);
            } else {
                applyChoiceStyle(row, false, false);
                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (live.isAnswered()) {
                            Snack.show(MainActivity.this, "这个选择已经提交过了");
                            return;
                        }
                        if (multi) {
                            // 多选：点一下切换选中态，卡片底部「确定」提交
                            boolean on = Boolean.TRUE.equals(v.getTag());
                            v.setTag(Boolean.valueOf(!on));
                            applyChoiceStyle((TextView) v, !on, false);
                        } else {
                            for (int j = 0; j < rows.size(); j++) {
                                TextView r = rows.get(j);
                                boolean s = r == v;
                                r.setTag(Boolean.valueOf(s));
                                applyChoiceStyle(r, s, true);
                                r.setClickable(false);
                            }
                            live.reply(opt);
                            tag.setText("已选择");
                            tag.setTextColor(Theme.primary());
                        }
                    }
                });
            }
            rows.add(row);
            card.addView(row);
        }

        if (multi && !answered && !readonly) {
            final TextView ok = new TextView(this);
            ok.setText("确定");
            ok.setTextSize(14);
            ok.setGravity(Gravity.CENTER);
            ok.setTextColor(0xFFFFFFFF);
            ok.setPadding(0, dp(11), 0, dp(11));
            ok.setBackground(Theme.round(Theme.primary(), Theme.R_MD, this));
            ok.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (live.isAnswered()) return;
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < rows.size(); j++) {
                        if (Boolean.TRUE.equals(rows.get(j).getTag())) {
                            if (sb.length() > 0) sb.append("、");
                            sb.append(options[j]);
                        }
                    }
                    if (sb.length() == 0) {
                        Snack.show(MainActivity.this, "先选至少一项");
                        return;
                    }
                    for (int j = 0; j < rows.size(); j++) {
                        rows.get(j).setClickable(false);
                        applyChoiceStyle(rows.get(j),
                                Boolean.TRUE.equals(rows.get(j).getTag()), true);
                    }
                    live.reply(sb.toString());
                    tag.setText("已选择");
                    tag.setTextColor(Theme.primary());
                    v.setVisibility(View.GONE);
                }
            });
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            olp.setMargins(0, dp(2), 0, 0);
            ok.setLayoutParams(olp);
            card.addView(ok);
        }

        // 卡片失效后置灰，避免用户去点一张已经没用的卡。
        // 两个来源：超时（工具线程触发）、用户点停止或切走会话（cancelPending 触发）。
        // 两者都可能不在主线程，所以统一 runOnUiThread 再改 View。
        if (!readonly) {
            final Runnable grey = new Runnable() {
                public void run() {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            tag.setText("已跳过 · 本轮不再等待");
                            tag.setTextColor(Theme.text3());
                            for (int j = 0; j < rows.size(); j++) {
                                TextView r = rows.get(j);
                                r.setClickable(false);
                                r.setTextColor(Theme.text3());
                            }
                            card.setAlpha(0.55f);
                        }
                    });
                }
            };
            live.onExpire = new Runnable() {
                public void run() {
                    // 超时分支：还没人回答
                    if (live.isAnswered()) return;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            tag.setText("已超时 · 本轮不再等待");
                            tag.setTextColor(Theme.text3());
                            for (int j = 0; j < rows.size(); j++) {
                                TextView r = rows.get(j);
                                r.setClickable(false);
                                r.setTextColor(Theme.text3());
                            }
                            card.setAlpha(0.55f);
                        }
                    });
                }
            };
            live.onCancel = grey;
        }
        return card;
    }

    /** 选项的选中/未选样式。 */
    private void applyChoiceStyle(TextView row, boolean selected, boolean locked) {
        if (selected) {
            row.setTextColor(Theme.primary());
            row.setBackground(Theme.roundOutlined(
                    Theme.primarySoft(), Theme.R_MD, Theme.primary(), this));
            row.setTypeface(null, Typeface.BOLD);
        } else {
            row.setTextColor(locked ? Theme.text3() : Theme.text());
            row.setBackground(Theme.round(Theme.surface(), Theme.R_MD, this));
            row.setTypeface(null, Typeface.NORMAL);
        }
    }

    private static String shorten(String s) {
        String t = s.replace('\n', ' ').trim();
        return t.length() > 18 ? t.substring(0, 18) + "…" : t;
    }

    // ------------------------------------------------------------ 崩溃兜底

    private void installCrashGuard() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE).edit()
                        .putString("last_crash", traceOf(e)).apply();
                } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private static String traceOf(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private void showFatal(Throwable t) {
        String trace = traceOf(t);
        try {
            getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE).edit()
                .putString("last_crash", trace).apply();
        } catch (Throwable ignored) {}

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(48), dp(16), dp(16));
        box.setBackgroundColor(0xFF151517);

        TextView head = new TextView(this);
        head.setText("启动失败 — 请把下面的内容截图发我");
        head.setTextColor(0xFFFF6B6B);
        head.setTextSize(15);
        head.setTypeface(null, Typeface.BOLD);
        box.addView(head);

        TextView tv = new TextView(this);
        tv.setText(trace);
        tv.setTextSize(10);
        tv.setTextColor(0xFFF4F4F2);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(box);
    }

    private void reportLastCrash() {
        SharedPreferences sp = getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE);
        String last = sp.getString("last_crash", "");
        if (last == null || last.length() == 0) return;
        sp.edit().remove("last_crash").apply();
        String brief = last.length() > 700 ? last.substring(0, 700) + "\n…" : last;
        TextView tv = new TextView(this);
        tv.setText("上次运行出错了（已捕获）：\n" + brief);
        tv.setTextSize(11);
        tv.setTextColor(Theme.danger());
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        tv.setBackground(Theme.round(Theme.surface(), Theme.R_SM, this));
        messages.addView(tv);
    }

    private int dp(float v) { return Theme.dp(this, v); }
}
