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
import com.dsh.android.kernel.SessionMeta;
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
    /** 节点 id → 这条消息画出来的视图（「选择文本」用它就地进入选择模式） */
    private final java.util.Map<String, View> nodeViews = new java.util.HashMap<String, View>();
    private ScrollView messageScroll;
    private EditText input;
    private FrameLayout sendBtn;
    private IconView sendIcon;
    private TextView titleView;
    /** 运行中的工具胶囊，按工具名排队；onToolResult 只带名字，没有调用 id。 */
    private final java.util.Map<String, java.util.ArrayDeque<ToolPill>> runningPills =
            new java.util.HashMap<String, java.util.ArrayDeque<ToolPill>>();

    private TextView modelPill;
    private android.widget.PopupWindow modelPopup;
    private LinearLayout attachBar;
    private View scrim;
    private LinearLayout drawer;
    private LinearLayout drawerList;

    private volatile boolean running = false;
    /** 本轮是被「暂停」结束的：半截内容还在，可以接着写。 */
    private volatile boolean pausedNow = false;

    private static final int MODE_SEND = 0, MODE_PAUSE = 1, MODE_CONTINUE = 2;

    /** 给「继续」用的隐藏提示：模型由此知道自己被打断过。 */
    private static final String CONTINUE_NOTE =
            "（你上一条回答在输出中途被我打断了。请从中断处接着写，不要重复已经写过的内容，也不要重新开头。）";
    private AiBlock streamingView;    // 当前正在流式输出的气泡
    private StringBuilder streamingBuf = new StringBuilder();
    private String lastThemeMode = null;

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
        // 指令面板预构建：首次点击零成本，避免低端机上弹出一卡
        findViewById(android.R.id.content).postDelayed(new Runnable() {
            public void run() {
                if (!isFinishing() && cmdCard == null) buildCommandCard();
            }
        }, 600);
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

    private void ensureFloatService() {
        boolean bubble = prefs().getBoolean(C.KEY_FLOAT, false)
                && FloatService.canOverlay(this);
        boolean keep = prefs().getBoolean(C.KEY_KEEPALIVE, false);
        if ((bubble || keep) && !FloatService.isRunning()) {
            FloatService.start(this, bubble);
        }
    }

    /** 每次启动只提醒一次「无障碍被系统关掉了」，别反复弹。 */
    private boolean a11yNotified = false;

    /** 长按消息弹出的菜单；点选项后要关掉它 */
    private android.app.Dialog nodeMenu;
    /** 正在朗读的消息节点；再点一次同一行则停止 */
    private String speakingNode;
    /** 右键式小菜单（长按会话 / 分组时出现） */
    private android.widget.PopupWindow lastMenu;

    /**
     * 无障碍自检：连上过、现在却掉了，就给一条可点的提示。
     *
     * <p>不少 ROM 在应用被划掉、或被内存清理时会把无障碍开关一起关掉。
     * 用户不会去翻系统设置，所以这里主动告诉他，并且一点就能重开。
     */
    /** 系统语音识别器；同一时刻只保留一个。 */
    private android.speech.SpeechRecognizer recognizer;

    /**
     * 语音输入。
     *
     * <p>用系统 SpeechRecognizer，不引入第三方 SDK。设备上没有可用的
     * 识别服务时直接说明，不做无声失败。
     */
    private void startVoiceInput() {
        try {
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
                Snack.show(this, "这台设备没有可用的语音识别服务");
                return;
            }
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 9002);
                Snack.show(this, "允许录音后再点一次麦克风");
                return;
            }
            if (recognizer != null) {
                try { recognizer.destroy(); } catch (Throwable ignored) {}
            }
            recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                public void onReadyForSpeech(android.os.Bundle b) {
                    Snack.show(MainActivity.this, "请说话…");
                }
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float v) {}
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() {}
                public void onError(int code) {
                    Snack.show(MainActivity.this, "没听清，再试一次");
                }
                public void onResults(android.os.Bundle b) {
                    String text = firstResult(b);
                    if (text == null || text.length() == 0) return;
                    String cur = input.getText() == null ? "" : input.getText().toString();
                    input.setText(cur.length() == 0 ? text : cur + " " + text);
                    input.setSelection(input.getText().length());
                }
                public void onPartialResults(android.os.Bundle b) {}
                public void onEvent(int t, android.os.Bundle b) {}
            });
            android.content.Intent i = new android.content.Intent(
                    android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE,
                    java.util.Locale.getDefault().toLanguageTag());
            i.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            recognizer.startListening(i);
        } catch (Throwable t) {
            Snack.show(this, "语音输入不可用");
        }
    }

    private String firstResult(android.os.Bundle b) {
        try {
            java.util.ArrayList<String> list =
                    b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
            return list == null || list.isEmpty() ? "" : list.get(0);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Android 13 起通知是运行时权限。拿不到不会崩，但前台服务的常驻通知
     * 与任务完成提示都不会显示，所以首次进入时问一次。
     */
    /**
     * 负面情绪关怀弹窗。
     *
     * <p>命中后 6 小时内不再重复弹：反复提示会变成负担，而不是帮助。
     */
    private void maybeShowCare(String text) {
        try {
            if (text == null || text.trim().length() == 0) return;
            // 只有用户主动选了「一小时内不触发」才静音；默认每次都判断
            if (System.currentTimeMillis() < prefs().getLong("care_muted_until", 0)) return;

            // 关键词命中时立刻弹，不等模型：这种情况没必要多等一次往返
            if (com.dsh.android.kernel.CareCheck.looksDistressed(text)) {
                showCareDialog();
                return;
            }

            // 否则交给模型判断一次（异步，不阻塞发送）
            final String probe = text;
            new Thread(new Runnable() {
                public void run() {
                    boolean risk = false;
                    try {
                        LlmClient c = new LlmClient(MainActivity.this,
                                prefs().getString(C.KEY_BASE_URL, C.DEFAULT_BASE_URL),
                                com.dsh.android.kernel.SecureStore.readKey(prefs()),
                                prefs().getString(C.KEY_MODEL, C.MODEL_CHAT), 64);
                        String answer = c.classify(
                                com.dsh.android.kernel.CrisisClassifier.systemPrompt(),
                                com.dsh.android.kernel.CrisisClassifier.userPrompt(probe));
                        risk = com.dsh.android.kernel.CrisisClassifier.parseResponse(answer);
                    } catch (Throwable ignored) {}
                    if (!risk) return;
                    runOnUiThread(new Runnable() {
                        public void run() { showCareDialog(); }
                    });
                }
            }, "care-check").start();
        } catch (Throwable ignored) {}
    }

    /**
     * 弹出关怀提示。
     *
     * <p>两个出口的含义不同：
     * <ul>
     *   <li>「一小时内不触发报警」——把静音时间推后一小时，期间不再打扰；</li>
     *   <li>「知道了」——只关掉这一个弹窗，不设静音，下次仍会提示。</li>
     * </ul>
     */
    private void showCareDialog() {
        try {
            new AppDialog.Builder(this)
                    .setTitle(com.dsh.android.kernel.CareCheck.careTitle())
                    .setMessage(com.dsh.android.kernel.CareCheck.careText())
                    .setPositiveButton("一小时内不触发报警",
                            new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface d, int w) {
                                    prefs().edit().putLong("care_muted_until",
                                            System.currentTimeMillis() + 3600L * 1000).apply();
                                }
                            })
                    .setNegativeButton("知道了", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    private void requestNotificationPermissionOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (prefs().getBoolean("asked_notif", false)) return;
        prefs().edit().putBoolean("asked_notif", true).apply();
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9001);
        } catch (Throwable ignored) {}
    }

    private void checkA11yHealth() {
        if (running || messages == null || a11yNotified) return;
        final android.content.SharedPreferences sp = getSharedPreferences("dsh", MODE_PRIVATE);
        final int state = com.dsh.android.a11y.A11yState.of(this);
        if (state == com.dsh.android.a11y.A11yState.READY) {
            sp.edit().putBoolean("a11y_was_on", true).apply();
            autoKeepAlive();
            return;
        }
        if (!sp.getBoolean("a11y_was_on", false)) return;   // 从来没开过，不打扰
        a11yNotified = true;

        // 有 root 就先自己修：这类「每次重开都要手动开一遍」的问题，写回设置就能省掉
        if (com.dsh.android.a11y.A11yAdmin.hasRoot()) {
            new Thread(new Runnable() {
                public void run() {
                    final String err = com.dsh.android.a11y.A11yAdmin.enableWithRoot(MainActivity.this);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (err == null) {
                                Snack.show(MainActivity.this, "无障碍已用 root 自动恢复");
                            } else {
                                addIconNotice(IconView.WRENCH, "无障碍没能自动恢复 · 点此手动开启",
                                        manualA11yFix());
                            }
                        }
                    });
                }
            }).start();
            return;
        }

        // 没 root：弹出说明，比聊天里一行小字更容易被看见
        addIconNotice(IconView.WRENCH,
                state == com.dsh.android.a11y.A11yState.ON_NOT_CONNECTED
                        ? "无障碍服务没连上 · 点此重试"
                        : "无障碍被系统关掉了 · 点此重新开启",
                manualA11yFix());
        scrollBottom();
    }

    /**
     * 无障碍连上之后，默认挂一条常驻通知把进程吊住。
     *
     * <p>「每次重开应用无障碍就没了」的根源是进程被回收，服务跟着走。
     * 这个问题不解决，光提示用户去重开开关没有意义。只自动开一次，
     * 用户在 设置 → 悬浮窗 里关掉之后就不再自动打开。
     */
    private void autoKeepAlive() {
        android.content.SharedPreferences p = prefs();
        if (p.contains(C.KEY_KEEPALIVE)) return;      // 用户已经表过态
        p.edit().putBoolean(C.KEY_KEEPALIVE, true).apply();
        if (!FloatService.isRunning()) FloatService.start(this, false);
        Snack.show(this, "已开启后台常驻，用来保住无障碍连接（设置 → 悬浮窗 可关）", 2600);
    }

    private View.OnClickListener manualA11yFix() {
        return new View.OnClickListener() {
            public void onClick(View v) {
                a11yNotified = false;
                com.dsh.android.ui.A11yGuide.showDialog(MainActivity.this, false);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 悬浮窗上的暂停/继续按钮通过它回调到这一页
        com.dsh.android.kernel.RunStatus.setControl(new com.dsh.android.kernel.RunStatus.Control() {
            public void pause() { runOnUiThread(new Runnable() { public void run() { pauseRun(); } }); }
            public void resume() { runOnUiThread(new Runnable() { public void run() { continueRun(); } }); }
            public boolean running() { return running; }
        });
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
        // 悬浮窗开关开着就保证服务在跑（进程被杀后重开应用能自己恢复）
        ensureFloatService();
        requestNotificationPermissionOnce();
        // 旧版本把密钥以明文存过；这里一次性加密写回
        try { com.dsh.android.kernel.SecureStore.migrateLegacy(prefs()); } catch (Throwable ignored) {}
        Speaker.init(this);

        // 从系统设置或其它应用回来：顺手看看无障碍还在不在
        checkA11yHealth();
    }

    /**
     * 返回键：先关抽屉，再退回聊天页，最后才交给系统。
     *
     * <p>抽屉是自绘的（不是 DrawerLayout），系统不知道它开着，
     * 所以必须自己拦一下，否则按返回会直接退出应用，
     * 看起来就像「一下子退了很多层」。
     */
    @Override
    public void onBackPressed() {
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
            return;
        }
        if (scrim != null && scrim.getVisibility() == View.VISIBLE) {
            scrim.setVisibility(View.GONE);
            return;
        }
        if (running) {
            // 正在生成时，返回先停掉生成，而不是把页面退掉
            loop.cancel();
            AskBridge.cancelPending("用户已停止");
            Snack.show(this, "已停止生成，再按一次返回");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        Speaker.stop();
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }
        // 页面销毁后悬浮窗不能再回调过来
        com.dsh.android.kernel.RunStatus.setControl(null);
        super.onDestroy();
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

        taskBar = new TextView(this);
        taskBar.setTextSize(11);
        taskBar.setTextColor(Theme.caption());
        taskBar.setGravity(Gravity.CENTER);
        taskBar.setPadding(0, dp(2), 0, dp(4));
        taskBar.setVisibility(View.GONE);
        col.addView(taskBar);

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
                    syncJumpButton();
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
        composerWrap = new LinearLayout(this);
        // 必须是垂直：附件条在上、输入框在下。
        // （漏掉这行会默认水平排列，附件条一出现就把整条输入框挤出屏幕外）
        composerWrap.setOrientation(LinearLayout.VERTICAL);
        composerWrap.setPadding(dp(12), dp(4), dp(12), dp(12));

        // 卡片内分两行：上面是输入文字（整行宽度），下面是工具与发送。
        // 之前所有按钮和输入框挤在同一行，窄屏上文本框被压得只剩几十 dp，
        // 图标宽度也各不相同（36/32/30），视觉上就是「错位」。
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(dp(14), dp(8), dp(10), dp(8));
        composer.setBackground(Theme.roundOutlined(
                Theme.surface(), Theme.R_PANEL, Theme.border(), this));

        // Computer 面板入口：和输入框同排的小按钮
        // 显示器图标（自绘：emoji 在各家 ROM 上渲染差异太大）
        MonitorIcon computerBtn = new MonitorIcon(this);
        computerBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    ComputerSheet.show(MainActivity.this);
                } catch (Throwable t) {
                    Snack.show(MainActivity.this, "无法打开面板");
                }
            }
        });
        LinearLayout toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.setGravity(Gravity.CENTER_VERTICAL);
        toolRow.setPadding(0, dp(6), 0, 0);

        IconView attachBtn = new IconView(this, IconView.PLUS, 20);
        attachBtn.setColor(Theme.text2());
        attachBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFile(); }
        });


        // 语音输入：系统 SpeechRecognizer，长按说话，结果填进输入框
        IconView micBtn = new IconView(this, IconView.MIC, 20);
        micBtn.setColor(Theme.text2());
        micBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startVoiceInput(); }
        });


        // 指令入口：列出插件注册的命令，点选后填入输入框
        IconView cmdBtn = new IconView(this, IconView.SLASH, 20);
        cmdBtn.setColor(Theme.text2());
        cmdBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showCommandPicker(); }
        });
        IconView imageBtn = new IconView(this, IconView.IMAGE, 20);
        imageBtn.setColor(Theme.text2());
        imageBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickImage(); }
        });

        // 统一尺寸的工具按钮，视觉上不再高低不齐
        int toolSize = dp(34);
        int toolGap = dp(2);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(toolSize, toolSize);
        cLp.setMargins(0, 0, toolGap, 0);
        toolRow.addView(computerBtn, cLp);
        toolRow.addView(attachBtn, new LinearLayout.LayoutParams(toolSize, toolSize));
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(toolSize, toolSize);
        imgLp.setMargins(toolGap, 0, 0, 0);
        toolRow.addView(imageBtn, imgLp);
        LinearLayout.LayoutParams micLp2 = new LinearLayout.LayoutParams(toolSize, toolSize);
        micLp2.setMargins(toolGap, 0, 0, 0);
        toolRow.addView(micBtn, micLp2);
        LinearLayout.LayoutParams cmdLp2 = new LinearLayout.LayoutParams(toolSize, toolSize);
        cmdLp2.setMargins(toolGap, 0, 0, 0);
        toolRow.addView(cmdBtn, cmdLp2);

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
        imageChip = new TextView(this);
        imageChip.setTextSize(11);
        imageChip.setTextColor(Theme.link());
        imageChip.setPadding(dp(2), 0, 0, dp(4));
        imageChip.setVisibility(View.GONE);
        imageChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearImages(); }
        });
        composer.addView(imageChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        composer.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sendBtn = new FrameLayout(this);
        sendBtn.setBackground(Theme.round(Theme.primary(), 20f, this));
        sendIcon = new IconView(this, IconView.SEND, 16);
        sendIcon.setColor(Theme.onPrimary());
        sendBtn.addView(sendIcon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        sendBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (running) pauseRun();
                else if (sendMode == MODE_CONTINUE && input.getText().toString().trim().length() == 0) continueRun();
                else onSend();
            }
        });
        // 长按：彻底停止（不保留继续入口）
        sendBtn.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (!running) return false;
                loop.cancel();
                AskBridge.cancelPending("用户已停止");
                Snack.show(MainActivity.this, "已停止");
                return true;
            }
        });
        // 工具行：左侧图标，中间弹性空白，右侧发送
        View spacer = new View(this);
        toolRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        toolRow.addView(sendBtn, sendLp);
        composer.addView(toolRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        // 用户上滑离开底部后，右下角给一个回去的入口（否则新消息来了看不到）
        jumpBottom = new TextView(this);
        jumpBottom.setText("\u2193");
        jumpBottom.setTextSize(16);
        jumpBottom.setGravity(Gravity.CENTER);
        jumpBottom.setTextColor(Theme.onPrimary());
        jumpBottom.setBackground(Theme.round(Theme.primary(), 999, this));
        jumpBottom.setElevation(dp(6));
        jumpBottom.setVisibility(View.GONE);
        jumpBottom.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                scrollBottom();
            }
        });
        FrameLayout.LayoutParams jlp = new FrameLayout.LayoutParams(dp(38), dp(38));
        jlp.gravity = Gravity.BOTTOM | Gravity.END;
        jlp.setMargins(0, 0, dp(16), dp(96));
        root.addView(jumpBottom, jlp);

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

        IconView menu = new IconView(this, IconView.MENU, 20);
        menu.setColor(Theme.text());
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

        IconView gear = new IconView(this, IconView.GEAR, 20);
        gear.setColor(Theme.text());
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
        if (cachedModels().isEmpty() && com.dsh.android.kernel.SecureStore.readKey(prefs()).length() > 0) {
            fetchModelsIntoPopup();
        }
    }

    private void fillPopup(final LinearLayout box) {
        box.removeAllViews();

        // 模型按「配置」分组列出：点哪个模型就用那套 API 的那个模型，
        // 一步完成「换接口 + 换模型」，不需要先挑配置再挑模型。
        final com.dsh.android.kernel.SessionStore st =
                new com.dsh.android.kernel.SessionStore(this);
        final com.dsh.android.kernel.Profiles ps = st.profiles();
        final String curModel = currentModel();
        final int activeIdx = ps.activeIndex();

        // 模型单独放进一个限高的滚动列表：模型多时只在这里滚动，
        // 弹层其余部分（强度、文件范围）位置不变。
        LinearLayout modelList = new LinearLayout(this);
        modelList.setOrientation(LinearLayout.VERTICAL);

        boolean missingCache = false;
        for (int i = 0; i < ps.items().size(); i++) {
            final int idx = i;
            final com.dsh.android.kernel.Profiles.Profile pf = ps.items().get(i);
            final boolean activeProfile = i == activeIdx;

            TextView head = popupLabel(pf.name == null || pf.name.length() == 0 ? "未命名" : pf.name);
            if (activeProfile) head.setTextColor(Theme.accent());
            modelList.addView(head);

            java.util.List<String> models = cachedModelsFor(pf.baseUrl);
            if (models.isEmpty()) {
                missingCache = true;
                TextView pending = popupHint(activeProfile ? "读取中…" : "点这里读取该接口的模型");
                pending.setTextColor(Theme.link());
                pending.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { fetchModelsFor(idx); }
                });
                modelList.addView(pending);
                continue;
            }
            for (int k = 0; k < models.size(); k++) {
                final String m = models.get(k);
                boolean on = activeProfile && m.equals(curModel);
                modelList.addView(popupRow(m, on, new View.OnClickListener() {
                    public void onClick(View v) {
                        ps.setActive(idx);
                        ps.active().model = m;
                        st.saveProfiles(ps);
                        prefs().edit().putString(C.KEY_MODEL, m).apply();
                        applyChoice();
                    }
                }));
            }
        }

        // 包进限高滚动容器
        ScrollCap modelScroll = new ScrollCap(this);
        modelScroll.setVerticalScrollBarEnabled(true);
        modelScroll.setMaxHeight(Math.max(dp(160),
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.32f)));
        modelScroll.addView(modelList);
        box.addView(modelScroll);

        box.addView(popupRow("管理配置…", false, new View.OnClickListener() {
            public void onClick(View v) {
                if (modelPopup != null) modelPopup.dismiss();
                android.content.Intent it =
                        new android.content.Intent(MainActivity.this, SettingsActivity.class);
                it.putExtra("page", 1);
                startActivity(it);
            }
        }));

        if (missingCache) {
            // 进入弹层时后台把各套接口的模型列表都读一次
            for (int i = 0; i < ps.items().size(); i++) {
                if (cachedModelsFor(ps.items().get(i).baseUrl).isEmpty()) prefetchModels(i);
            }
        }

        box.addView(dividerLine());

        String curLevel = C.normalizeLevel(prefs().getString(C.KEY_REASONING, C.LEVEL_MEDIUM));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(6), dp(14), dp(2));

        TextView headLabel = new TextView(this);
        headLabel.setText(Texts.t(this, "reasoning"));
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
                + "\u3000强度 " + effortLabel(curLevel));
        box.addView(slider);
        box.addView(desc);

        box.addView(dividerLine());

        LinearLayout scopeHead = new LinearLayout(this);
        scopeHead.setOrientation(LinearLayout.HORIZONTAL);
        scopeHead.setGravity(Gravity.CENTER_VERTICAL);
        scopeHead.setPadding(dp(14), dp(6), dp(14), dp(2));
        TextView scopeLabel = new TextView(this);
        scopeLabel.setText(Texts.t(this, "file_scope"));
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
        scopeValue.setText(Texts.t(this, device ? "device_short" : "ws_short"));

        box.addView(popupRow(Texts.t(this, "workspace"), !device, new View.OnClickListener() {
            public void onClick(View v) {
                prefs().edit().putString(C.KEY_FS_SCOPE, C.SCOPE_WORKSPACE).apply();
                applyChoice();
            }
        }));
        box.addView(popupRow(Texts.t(this, "device"), device, new View.OnClickListener() {
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
                        + "\u3000强度 " + effortLabel(key));
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

    /** 内置指令：不依赖插件，随应用提供。 */
    private static final String[][] BUILTIN_CMDS = {
            {"/clear", "清空当前会话的所有消息"},
            {"/compact", "压缩对话历史并生成摘要"},
            {"/thinking", "切换深度思考（推理等级 off ↔ high）"},
            {"/memory", "切换提示词预设是否注入"},
            {"/help", "列出所有可用指令"},
    };

    /** 执行内置指令；返回 false 表示不是内置指令。 */
    private boolean runBuiltinCommand(String text) {
        String body = text.startsWith("/") ? text.substring(1).trim() : text.trim();
        int sp = body.indexOf(' ');
        String name = (sp < 0 ? body : body.substring(0, sp)).toLowerCase();

        if ("clear".equals(name)) {
            doClear();
            return true;
        }
        if ("compact".equals(name)) {
            doCompact();
            return true;
        }
        if ("thinking".equals(name)) {
            String arg = sp < 0 ? "" : body.substring(sp + 1).trim();
            String cur = C.normalizeLevel(prefs().getString(C.KEY_REASONING, C.LEVEL_MEDIUM));
            if (arg.length() == 0) {
                Snack.show(this, "当前 " + cur + "。用法：/thinking 0-5（0 off，5 max）");
                return true;
            }
            int level;
            try {
                level = Integer.parseInt(arg);
            } catch (Exception e) {
                Snack.show(this, "参数要是 0-5 的数字");
                return true;
            }
            if (level < 0 || level > 5) {
                Snack.show(this, "参数超出范围，只接受 0-5");
                return true;
            }
            String next = C.LEVELS[level][0];
            prefs().edit().putString(C.KEY_REASONING, next).apply();
            reloadClient();
            refreshModelPill();
            Snack.show(this, "推理强度：" + next + "（" + C.LEVELS[level][1] + "）");
            return true;
        }
        if ("memory".equals(name)) {
            boolean on = prefs().getBoolean("presets_on", true);
            prefs().edit().putBoolean("presets_on", !on).apply();
            Snack.show(this, on ? "提示词预设：已停用" : "提示词预设：已启用");
            return true;
        }
        if ("help".equals(name)) {
            showCommandPicker();
            return true;
        }
        return false;
    }

    private void doClear() {
        String id = sessions.currentId();
        sessions.saveHistory(id, new JSONArray());
        messages.removeAllViews();
        addWelcome();
        Snack.show(this, "已清空当前会话");
    }

    private void doCompact() {
        JSONArray hist = sessions.loadCurrentHistory();
        if (hist.length() == 0) {
            Snack.show(this, "当前会话还没有内容");
            return;
        }
        String sessId = sessions.currentId();
        if (com.dsh.android.kernel.Conversation.forkCount(sessions.loadHistory(sessId)) > 0) {
            Snack.show(this, "这个会话有分支，暂不压缩：压缩会删掉较早的消息，分支会跟着丢");
            return;
        }
        HistoryCompactor.Report rep = HistoryCompactor.fit(hist, 24000, 14000);
        sessions.saveHistory(sessId, hist);
        closeTextSegment();
        if (rep.changed) {
            addNotice("↻ " + rep.describe());
            Snack.show(this, "已压缩对话历史");
        } else {
            Snack.show(this, "历史还没到需要压缩的长度");
        }
        scrollBottom();
    }

    /** 指令面板：贴着输入框向上弹出。视图只建一次，之后复用；插件命令异步取。 */
    private void showCommandPicker() {
        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        if (cmdPopup != null && cmdPopup.isShowing()) {
            try {
                cmdPopup.dismiss();
            } catch (Throwable ignored) {}
            return;
        }
        if (cmdCard == null) buildCommandCard();
        refreshCommandStates();

        final View anchor = composerWrap;
        anchor.post(new Runnable() {
            public void run() {
                if (isFinishing() || anchor.getWindowToken() == null) return;
                int[] loc = new int[2];
                anchor.getLocationOnScreen(loc);
                int cardH = cmdCardHeight > 0 ? cmdCardHeight : dp(300);
                int y = Math.max(dp(56), loc[1] - cardH - dp(8));
                try {
                    cmdPopup.showAtLocation(anchor, Gravity.TOP | Gravity.START, dp(16), y);
                } catch (Throwable ignored) {
                    // 窗口已不可用（比如刚好退出），放弃这次弹出即可，不能让应用崩
                }
            }
        });

        // 插件命令可能还在 JS 里，异步取一次，回来再补进列表
        new Thread(new Runnable() {
            public void run() {
                final org.json.JSONArray got;
                try {
                    got = com.dsh.android.plugins.js.JsHost.commands();
                } catch (Throwable t) {
                    return;
                }
                if (got.length() == 0) return;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || cmdPluginHost == null) return;
                        fillPluginCommands(got);
                    }
                });
            }
        }, "cmd-list").start();
    }

    /** 构建一次，之后复用，避免每次点击都 inflate 一遍导致掉帧。 */
    private void buildCommandCard() {
        if (cmdCard != null && cmdPopup != null) return;   // 已建过，别重复
        if (cmdPopup != null) {
            try { cmdPopup.dismiss(); } catch (Throwable ignored) {}
            cmdPopup = null;
        }
        cmdCard = new LinearLayout(this);
        LinearLayout card = (LinearLayout) cmdCard;
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.roundOutlined(Theme.panel(), 18, Theme.borderStrong(), this));
        card.setPadding(dp(6), dp(8), dp(6), dp(8));

        cmdStateViews.clear();
        // {图标, 命令, 说明, 是否显示开关状态}
        Object[][] builtin = {
                {Integer.valueOf(IconView.TRASH), "/clear", "清空当前会话的所有消息", "0"},
                {Integer.valueOf(IconView.COMPACT), "/compact", "压缩对话历史为摘要", "0"},
                {Integer.valueOf(IconView.THINKING), "/thinking", "输入 0-5 调整强度", "0"},
                {Integer.valueOf(IconView.BOOKMARK), "/memory", "提示词预设", "1"},
                {Integer.valueOf(IconView.HELP), "/help", "查看全部指令", "0"},
        };
        for (int i = 0; i < builtin.length; i++) {
            View row = cmdRow(((Integer) builtin[i][0]).intValue(), (String) builtin[i][1],
                    (String) builtin[i][2], "1".equals(builtin[i][3]));
            card.addView(row);
        }

        cmdPluginHost = new LinearLayout(this);
        cmdPluginHost.setOrientation(LinearLayout.VERTICAL);
        card.addView(cmdPluginHost);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(card);

        int width = getResources().getDisplayMetrics().widthPixels - dp(32);
        // 建好就量一次：首次弹出时如果等布局完再量，高度还是 0，面板位置会跳
        card.measure(View.MeasureSpec.makeMeasureSpec(width - dp(12), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(420), View.MeasureSpec.AT_MOST));
        cmdCardHeight = Math.min(Math.max(card.getMeasuredHeight(), dp(120)), dp(420));

        cmdPopup = new android.widget.PopupWindow(sv, width, cmdCardHeight, true);
        cmdPopup.setBackgroundDrawable(null);
        cmdPopup.setOutsideTouchable(true);
    }

    /** 开关类指令的状态文字（每次打开时刷新，不重建视图）。 */
    private void refreshCommandStates() {
        String level = C.normalizeLevel(prefs().getString(C.KEY_REASONING, C.LEVEL_MEDIUM));
        int idx = 2;
        for (int i = 0; i < C.LEVELS.length; i++) {
            if (C.LEVELS[i][0].equals(level)) idx = i;
        }
        TextView thinkSub = cmdStateViews.get("/thinking");
        if (thinkSub != null) {
            thinkSub.setText("当前 " + level + "（" + idx + "）· 输入 0-5 调整");
        }

        boolean presets = prefs().getBoolean("presets_on", true);
        setCmdState("/memory", presets ? "已开启 · 点击切换" : "已关闭 · 点击切换", presets);
    }

    private void setCmdState(String name, String text, boolean on) {
        TextView sub = cmdStateViews.get(name);
        if (sub != null) sub.setText(text);
        TextView mark = cmdMarkViews.get(name);
        if (mark != null) {
            mark.setText(on ? "\u2713" : "\u2715");
            mark.setTextColor(on ? Theme.link() : Theme.caption());
        }
    }

    private void fillPluginCommands(org.json.JSONArray cmds) {
        if (cmdPluginHost == null) return;
        cmdPluginHost.removeAllViews();
        for (int i = 0; i < cmds.length(); i++) {
            org.json.JSONObject c = cmds.optJSONObject(i);
            if (c == null) continue;
            String name = c.optString("name", "");
            if (name.length() == 0) continue;
            String desc = c.optString("desc", "");
            String plugin = c.optString("plugin", "");
            String sub = desc.length() > 0 ? desc : "插件指令";
            if (plugin.length() > 0) sub = sub + "　·　" + plugin;
            cmdPluginHost.addView(cmdRow(IconView.PLUGIN, "/" + name, sub, false));
        }
    }

    /**
     * 一行指令：图标 + 名称 + 说明，开关类右侧带状态点。
     *
     * @param toggle 是否是需要显示开关状态的内置指令
     */
    private View cmdRow(int icon, String name, String desc, boolean toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(11), dp(12), dp(11));

        IconView ic = new IconView(this, icon, 18);
        ic.setColor(Theme.text3());
        LinearLayout.LayoutParams iclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iclp.setMargins(0, 0, dp(4), 0);
        row.addView(ic, iclp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(14);
        t.setTextColor(Theme.text());
        col.addView(t);

        TextView st = new TextView(this);
        st.setText(desc);
        st.setTextSize(11);
        st.setTextColor(Theme.caption());
        st.setPadding(0, dp(2), 0, 0);
        col.addView(st);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMargins(dp(8), 0, 0, 0);
        row.addView(col, clp);

        if (!toggle) {
            cmdStateViews.put(name, st);
        }
        if (toggle) {
            TextView mark = new TextView(this);
            mark.setText("\u2713");
            mark.setTextSize(13);
            mark.setTextColor(Theme.link());
            row.addView(mark);
            cmdStateViews.put(name, st);
            cmdMarkViews.put(name, mark);
        }

        if ("/help".equals(name)) {
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (cmdPopup != null) cmdPopup.dismiss();
                    showCommandHelp();
                }
            });
        } else {
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    input.setText(name + " ");
                    input.setSelection(input.getText().length());
                    input.requestFocus();
                    if (cmdPopup != null) cmdPopup.dismiss();
                }
            });
        }
        return row;
    }

    private void showCommandHelp() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < BUILTIN_CMDS.length; i++) {
            sb.append(BUILTIN_CMDS[i][0]).append("\n    ").append(BUILTIN_CMDS[i][1]).append('\n');
        }
        new AppDialog.Builder(this)
                .setTitle("可用指令")
                .setMessage(sb.toString().trim())
                .setPositiveButton("知道了", null)
                .show();
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

    /** 每套接口各自缓存一份模型列表，键是接口地址。 */
    private final java.util.Map<String, java.util.List<String>> modelsByBase =
            new java.util.HashMap<String, java.util.List<String>>();

    private java.util.List<String> cachedModelsFor(String base) {
        java.util.List<String> got = modelsByBase.get(base == null ? "" : base);
        if (got != null) return got;
        // 当前接口沿用它原来的缓存文件，避免重复请求
        String curBase = prefs().getString(C.KEY_BASE_URL, C.DEFAULT_BASE_URL);
        if (base != null && base.equals(curBase)) {
            java.util.List<String> legacy = cachedModels();
            if (!legacy.isEmpty()) {
                modelsByBase.put(base, legacy);
                return legacy;
            }
        }
        return new java.util.ArrayList<String>();
    }

    /** 后台读取某一套接口的模型列表，回来后重绘弹层。 */
    private void prefetchModels(final int idx) {
        final com.dsh.android.kernel.SessionStore st =
                new com.dsh.android.kernel.SessionStore(this);
        final com.dsh.android.kernel.Profiles ps = st.profiles();
        if (idx < 0 || idx >= ps.items().size()) return;
        final String base = ps.items().get(idx).baseUrl;
        final String key = ps.items().get(idx).apiKey;
        if (base == null || base.length() == 0 || key == null || key.length() == 0) return;
        if (modelsByBase.containsKey(base)) return;
        new Thread(new Runnable() {
            public void run() {
                List<String> got = null;
                try {
                    got = LlmClient.fetchModels(base, key);
                } catch (Throwable ignored) {}
                if (got == null || got.isEmpty()) return;
                modelsByBase.put(base, got);
                final String curBase = prefs().getString(C.KEY_BASE_URL, C.DEFAULT_BASE_URL);
                if (base.equals(curBase)) {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (int i = 0; i < got.size(); i++) arr.put(got.get(i));
                    prefs().edit().putString("models_cache", arr.toString()).apply();
                }
                runOnUiThread(new Runnable() {
                    public void run() { refillModelPopup(); }
                });
            }
        }, "models-" + idx).start();
    }

    /** 用户主动点「读取该接口的模型」。 */
    private void fetchModelsFor(final int idx) {
        modelsByBase.remove(new com.dsh.android.kernel.SessionStore(this)
                .profiles().items().get(idx).baseUrl);
        prefetchModels(idx);
        Snack.show(this, "正在读取模型列表…");
    }

    /** 弹层还开着就重绘它。 */
    private void refillModelPopup() {
        if (modelPopup == null || !modelPopup.isShowing()) return;
        View content = modelPopup.getContentView();
        if (!(content instanceof android.widget.ScrollView)) return;
        View child = ((android.widget.ScrollView) content).getChildAt(0);
        if (child instanceof LinearLayout) fillPopup((LinearLayout) child);
    }

    private void fetchModelsIntoPopup() {
        final String base = prefs().getString(C.KEY_BASE_URL, C.DEFAULT_BASE_URL);
        final String key = com.dsh.android.kernel.SecureStore.readKey(prefs());
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

    /** 弹层里的分隔线。 */
    private View popupDivider() {
        View v = new View(this);
        v.setBackgroundColor(Theme.hover());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
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

        // 标题：一行大字 + 一行小字，避免顶部只有一个 13sp 的「会话」显得空
        TextView head = new TextView(this);
        head.setText("对话");
        head.setTextSize(20);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextColor(Theme.text());
        head.setPadding(dp(10), dp(2), 0, 0);
        d.addView(head);

        TextView sub = new TextView(this);
        sub.setText("按分组整理，长按会话可移动");
        sub.setTextSize(11);
        sub.setTextColor(Theme.caption());
        sub.setPadding(dp(10), dp(2), 0, dp(12));
        d.addView(sub);

        // 新建会话 + 新建分组放在同一行：分组入口必须在顶部，
        // 会话一多，底部那个入口要滑到底才看得到。
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout add = new LinearLayout(this);
        add.setOrientation(LinearLayout.HORIZONTAL);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(dp(14), dp(11), dp(14), dp(11));
        add.setBackground(Theme.round(Theme.primarySoft(), Theme.R_MD, this));

        IconView plus = new IconView(this, IconView.PLUS, 14);
        plus.setColor(Theme.accent());
        add.addView(plus, new LinearLayout.LayoutParams(dp(14), dp(14)));

        TextView addText = new TextView(this);
        addText.setText("新建会话");
        addText.setTextSize(14);
        addText.setTextColor(Theme.text());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(8);
        add.addView(addText, tp);

        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { newSession(); }
        });
        topRow.addView(add, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout folderBtn = new FrameLayout(this);
        folderBtn.setPadding(dp(9), dp(11), dp(9), dp(11));
        folderBtn.setPadding(dp(9), dp(11), dp(9), dp(11));
        folderBtn.setBackground(Theme.round(Theme.primarySoft(), Theme.R_MD, this));
        IconView folderIcon = new IconView(this, IconView.FOLDER, 20);
        folderIcon.setColor(Theme.text2());
        folderBtn.addView(folderIcon, new FrameLayout.LayoutParams(dp(24), dp(24)));
        folderBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { promptNewGroup(); }
        });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.leftMargin = dp(8);
        topRow.addView(folderBtn, fp);
        d.addView(topRow);

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
        drawerList.setPadding(dp(4), dp(2), dp(4), dp(20));

        final List<SessionMeta> list = sessions.listSessions();
        final String cur = sessions.currentId();
        final List<String> groups = sessions.listGroups();
        java.util.List<com.dsh.android.kernel.SessionGroups.Bucket> buckets =
                com.dsh.android.kernel.SessionGroups.split(list, groups);
        boolean showHeaders = com.dsh.android.kernel.SessionGroups.showHeaders(buckets);

        for (int bi = 0; bi < buckets.size(); bi++) {
            final com.dsh.android.kernel.SessionGroups.Bucket bucket = buckets.get(bi);
            final String groupName = bucket.name;
            final boolean named = groupName.length() > 0;
            final boolean collapsed = named && collapsedGroups().contains(groupName);

            if (showHeaders) {
                LinearLayout head = new LinearLayout(this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);
                // 组头不铺底色：一排排灰块正是上一版显得笨重的原因
                head.setPadding(dp(8), dp(14), dp(8), dp(6));

                if (named) {
                    IconView fold = new IconView(this,
                            collapsed ? IconView.CHEVRON_RIGHT : IconView.CHEVRON_DOWN, 14);
                    fold.setColor(Theme.caption());
                    head.addView(fold, new LinearLayout.LayoutParams(dp(14), dp(14)));

                    IconView folder = new IconView(this, IconView.FOLDER, 14);
                    folder.setColor(Theme.caption());
                    LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(14), dp(14));
                    fp.leftMargin = dp(6);
                    head.addView(folder, fp);
                }

                TextView name = new TextView(this);
                name.setText(named ? groupName : "未分组");
                name.setTextSize(11);
                name.setTextColor(Theme.caption());
                name.setLetterSpacing(0.04f);
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                np.leftMargin = dp(7);
                head.addView(name, np);

                TextView count = new TextView(this);
                count.setText(String.valueOf(bucket.sessions.size()));
                count.setTextSize(11);
                count.setTextColor(Theme.caption());
                head.addView(count);

                if (named) {
                    head.setBackground(Theme.round(0x00000000, Theme.R_SM, this));
                    head.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { toggleGroup(groupName); }
                    });
                    head.setOnLongClickListener(new View.OnLongClickListener() {
                        public boolean onLongClick(View v) {
                            showFolderMenu(groupName, v);
                            return true;
                        }
                    });
                }
                drawerList.addView(head);
            }
            if (collapsed) continue;

            if (bucket.sessions.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("空文件夹 · 长按会话可移入");
                empty.setTextSize(11);
                empty.setTextColor(Theme.caption());
                empty.setPadding(dp(36), dp(4), dp(8), dp(10));
                drawerList.addView(empty);
            }

            for (int i = 0; i < bucket.sessions.size(); i++) {
                final SessionMeta m = bucket.sessions.get(i);
                final boolean sel = m.id.equals(cur);

                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(dp(10), dp(9), dp(6), dp(9));
                item.setBackground(Theme.round(sel ? Theme.surface2() : 0x00000000,
                        Theme.R_SM, this));

                // 选中项左侧一条强调条，比整块换色更克制
                View bar = new View(this);
                bar.setBackground(Theme.round(sel ? Theme.accent() : 0x00000000, 2, this));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(3), dp(16));
                bp.rightMargin = dp(8);
                item.addView(bar, bp);

                TextView t = new TextView(this);
                t.setText(m.title == null || m.title.length() == 0 ? "新对话" : m.title);
                t.setTextSize(14);
                t.setTextColor(sel ? Theme.text() : Theme.text2());
                t.setMaxLines(1);
                t.setEllipsize(TextUtils.TruncateAt.END);
                item.addView(t, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                IconView del = new IconView(this, IconView.CLOSE, 13);
                del.setColor(Theme.caption());
                del.setAlpha(sel ? 1f : 0.35f);
                del.setPadding(dp(7), dp(7), dp(7), dp(7));
                del.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { confirmDelete(m); }
                });
                item.addView(del, new LinearLayout.LayoutParams(dp(24), dp(24)));

                item.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { switchTo(m.id); }
                });
                item.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        showGroupMenu(m, v);
                        return true;
                    }
                });
                drawerList.addView(item);
            }
        }

        // 底部入口保持轻量，只作补充（顶部已有文件夹按钮）
        TextView add = new TextView(this);
        add.setText("\uFF0B  新建分组");
        add.setTextSize(12);
        add.setTextColor(Theme.caption());
        add.setPadding(dp(12), dp(18), dp(8), dp(10));
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { promptNewGroup(); }
        });
        drawerList.addView(add);
    }

    /** 折叠状态存在 prefs 里，重开应用保持一致。 */
    private java.util.Set<String> collapsedGroups() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        String raw = prefs().getString("collapsed_groups", "");
        if (raw != null && raw.length() > 0) {
            String[] parts = raw.split("\n");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].length() > 0) out.add(parts[i]);
            }
        }
        return out;
    }

    private void toggleGroup(String name) {
        java.util.Set<String> set = collapsedGroups();
        if (!set.remove(name)) set.add(name);
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        prefs().edit().putString("collapsed_groups", sb.toString()).apply();
        refreshDrawer();
    }

    /** 长按分组：右键式小菜单。 */
    private void showFolderMenu(final String group, final View anchor) {
        List<MiniMenu.Item> items = new java.util.ArrayList<MiniMenu.Item>();
        items.add(MiniMenu.Item.of("重命名分组", new Runnable() {
            public void run() {
                promptForName("重命名分组", group, new NameSink() {
                    public void done(String name) {
                        sessions.renameGroup(group, name);
                        refreshDrawer();
                    }
                });
            }
        }));
        items.add(MiniMenu.Item.of("删除分组", new Runnable() {
            public void run() {
                confirmDeleteGroup(group);
            }
        }));
        lastMenu = MiniMenu.show(this, anchor, group, items);
    }

    /** 长按会话：弹出右键式小菜单，「移到分组」再展开三级列表。 */
    private void showGroupMenu(final SessionMeta m, final View anchor) {
        final List<String> groups = sessions.listGroups();
        List<MiniMenu.Item> items = new java.util.ArrayList<MiniMenu.Item>();
        items.add(MiniMenu.Item.of("重命名", new Runnable() {
            public void run() {
                promptForName("重命名会话", m.title, new NameSink() {
                    public void done(String name) {
                        sessions.renameSession(m.id, name);
                        refreshDrawer();
                    }
                });
            }
        }));
        items.add(MiniMenu.Item.sub("移到分组  \u203A", new Runnable() {
            public void run() {
                showMoveToGroup(m, groups, anchor);
            }
        }));
        if (m.group != null && m.group.length() > 0) {
            items.add(MiniMenu.Item.of("移出分组", new Runnable() {
                public void run() {
                    sessions.setGroup(m.id, "");
                    refreshDrawer();
                }
            }));
        }
        items.add(MiniMenu.Item.sep());
        items.add(MiniMenu.Item.of("删除会话", new Runnable() {
            public void run() {
                confirmDelete(m);
            }
        }));
        lastMenu = MiniMenu.show(this, anchor, m.title, items);
    }

    /** 三级菜单：列出所有分组，最后一项是新建分组并移入。 */
    private void showMoveToGroup(final SessionMeta m, List<String> groups, final View anchor) {
        List<MiniMenu.Item> items = new java.util.ArrayList<MiniMenu.Item>();
        for (int i = 0; i < groups.size(); i++) {
            final String g = groups.get(i);
            if (g.equals(m.group)) {
                items.add(MiniMenu.Item.dim(g + "  \u2713"));
            } else {
                items.add(MiniMenu.Item.of(g, new Runnable() {
                    public void run() {
                        sessions.setGroup(m.id, g);
                        refreshDrawer();
                    }
                }));
            }
        }
        if (!items.isEmpty()) items.add(MiniMenu.Item.sep());
        items.add(MiniMenu.Item.of("新建分组并移入…", new Runnable() {
            public void run() {
                promptForName("新建分组", "", new NameSink() {
                    public void done(String name) {
                        sessions.addGroup(name);
                        sessions.setGroup(m.id, name);
                        refreshDrawer();
                    }
                });
            }
        }));
        lastMenu = MiniMenu.show(this, anchor, "移到分组", items);
    }

    private void promptNewGroup() {
        promptForName("新建分组", "", new NameSink() {
            public void done(String name) {
                sessions.addGroup(name);
                refreshDrawer();
            }
        });
    }

    private void promptNewGroupFor(final SessionMeta m) {
        promptForName("新建分组", "", new NameSink() {
            public void done(String name) {
                sessions.addGroup(name);
                sessions.setGroup(m.id, name);
                refreshDrawer();
            }
        });
    }

    private void confirmDeleteGroup(final String group) {
        new AppDialog.Builder(this)
                .setTitle("删除分组「" + group + "」")
                .setMessage("组里的会话会回到「未分组」，不会被删除。")
                .setPositiveButton("删除分组", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        sessions.removeGroup(group);
                        refreshDrawer();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 输入名字的小流程，分组相关的地方共用。 */
    private interface NameSink {
        void done(String name);
    }

    private void promptForName(String title, String preset, final NameSink sink) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(preset);
        et.setSingleLine(true);
        et.setHint("分组名");
        // 不显式设颜色的话，EditText 用系统默认的深色字，深色模式下就是黑底黑字
        et.setTextColor(Theme.text());
        et.setHintTextColor(Theme.caption());
        et.setTextSize(15);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        et.setBackground(Theme.round(Theme.surface2(), Theme.R_LG, this));
        new AppDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = et.getText() == null ? "" : et.getText().toString().trim();
                        if (name.length() == 0) {
                            Snack.show(MainActivity.this, "名字不能为空");
                            return;
                        }
                        sink.done(name);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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

    private void confirmDelete(final SessionMeta m) {
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
        List<SessionMeta> all = sessions.listSessions();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                String t = all.get(i).title;
                if (t == null || t.length() == 0
                        || "新对话".equals(t) || "新会话".equals(t)) {
                    return Texts.t(this, "new_chat");
                }
                return t;
            }
        }
        return "DShAndroid";
    }

    // ------------------------------------------------------------ 消息渲染

    /**
     * 把一条分支路径的增量合并回完整历史，并把 leaf 指到末端。
     *
     * <p>Agent 循环只拿到当前分支的路径，它追加的消息不带 id/parent；
     * 这里补上再并回全量，别的分支原样保留。
     */
    private String commitPath(String sessId, JSONArray all, JSONArray path, int from, String startLeaf) {
        String leaf = com.dsh.android.kernel.Conversation.mergeAppended(all, path, from, startLeaf);
        sessions.saveAll(sessId, all);
        sessions.setLeaf(sessId, leaf);
        return leaf;
    }

    /** 当前会话的完整历史 + 当前分支的起点信息，供各条发送路径复用。 */
    private JSONArray loadAllForTurn() {
        return sessions.loadHistory(sessions.currentId());
    }

    private void renderHistory() {
        messages.removeAllViews();
        JSONArray hist = sessions.loadCurrentHistory();
        // 卡片直接从历史里重建：问题和选项在 tool_calls 参数里，
        // 用户的选择在紧跟着的 tool 结果里，一份数据就够
        java.util.Map<Integer, java.util.List<AskLog.Card>> cards = AskLog.byIndex(hist);

        // 重绘时旧视图会被移除，映射必须重建，否则「选择文本」可能
        // 拿到一个已经不在界面上的 TextView
        nodeViews.clear();
        JSONArray allNodes = sessions.loadHistory(sessions.currentId());

        int shown = 0;
        for (int i = 0; i < hist.length(); i++) {
            JSONObject o = hist.optJSONObject(i);
            if (o == null) continue;
            String nodeId = o.optString("id", "");
            java.util.List<String> sibs = nodeId.length() == 0
                    ? java.util.Collections.<String>emptyList()
                    : com.dsh.android.kernel.Conversation.siblingsOf(allNodes, nodeId);
            int before = messages.getChildCount();
            String role = o.optString("role");
            String content = o.optString("content", "");
            if ("user".equals(role)) {
                // hidden 是给模型的提示（比如「接着写」），不占界面位置
                if (o.optBoolean("hidden", false)) continue;
                addUserBubble(content);
                shown++;
            } else if ("assistant".equals(role) && content.length() > 0) {
                // 存过思考过程就补一个折叠块，默认收起
                String think = o.optString("reasoning", "");
                if (think.length() > 0) {
                    ThinkingBlock tb = new ThinkingBlock(this, think);
                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    tlp.setMargins(dp(2), dp(6), dp(2), dp(2));
                    tb.setLayoutParams(tlp);
                    tb.collapse();
                    messages.addView(tb);
                }
                addAssistantBubble(content);
                if (o.optBoolean("interrupted", false)) {
                    addIconNotice(IconView.PAUSE, "这段回答被暂停过 · 点右下继续");
                }
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
            // 操作行只挂在助手消息下面，而且必须是这条消息真的画出了内容。
            //
            // 之前两个问题都出在这里：
            //  · 用户消息下面也挂一行，于是那一行夹在提问和回答之间，看起来「跑到上面去了」；
            //  · 空消息（只有工具轮）也挂一行，就出现「没有文字的地方有一排按钮」。
            // 用户消息的操作走长按菜单，位置不再跳动。
            boolean rendered = messages.getChildCount() > before;
            if (nodeId.length() > 0) {
                if (rendered) nodeViews.put(nodeId, messages.getChildAt(before));
                if ("assistant".equals(role) && rendered) {
                    addActionRow(nodeId, role);
                }
                // 分叉点在这里：这条消息有多个子节点，才放切换条。
                // 挂在分叉点上而不是挂在各个兄弟上，位置就固定了，
                // 也不会在提问和回答之间来回跳。
                java.util.List<String> kids = com.dsh.android.kernel.Conversation
                        .childrenOf(allNodes, nodeId);
                if (rendered && kids.size() > 1) {
                    String nextOnPath = (i + 1 < hist.length())
                            ? hist.optJSONObject(i + 1).optString("id", "") : "";
                    addForkSwitcher(kids, nextOnPath);
                }
                for (int k = before; k < messages.getChildCount(); k++) {
                    attachBranchMenu(messages.getChildAt(k), nodeId);
                }
            }
        }
        if (shown == 0) addWelcome();
        refreshSendMode();
        scrollBottom();
    }

    /**
     * 助手消息下方的操作行：图标按钮。
     *
     * <p>分支切换不在这里——它挂在分叉点上（见 addForkSwitcher），
     * 否则同一条分支上的每条消息都带一个 1/2，位置自然飘。
     */
    private void addActionRow(final String nodeId, String role) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(2), dp(2), dp(8));

        row.addView(iconButton(IconView.BRANCH, true, new View.OnClickListener() {
            public void onClick(View v) { startBranchFrom(nodeId); }
        }));
        row.addView(iconButton(IconView.COPY, true, new View.OnClickListener() {
            public void onClick(View v) { copyNode(nodeId); }
        }));
        row.addView(iconButton(IconView.REFRESH, true, new View.OnClickListener() {
            public void onClick(View v) { regenerateNode(nodeId); }
        }));
        row.addView(iconButton(IconView.SPEAKER, true, new View.OnClickListener() {
            public void onClick(View v) { speakNode(nodeId); }
        }));
        messages.addView(row);
    }

    /**
     * 分叉点上的切换条：`‹ 1/3 ›`。
     *
     * <p>它只出现在「有多条子分支」的那条消息下面，因此位置固定；
     * 左右切换的是子分支，切过去会落到该分支的末端。
     */
    private void addForkSwitcher(final java.util.List<String> kids, String nextOnPath) {
        final int idx = Math.max(0, kids.indexOf(nextOnPath));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(0), dp(2), dp(8));

        row.addView(iconButton(IconView.CHEVRON_LEFT, idx > 0, new View.OnClickListener() {
            public void onClick(View v) {
                if (idx > 0) jumpBranch(kids.get(idx - 1));
            }
        }));
        TextView label = new TextView(this);
        label.setText((idx + 1) + "/" + kids.size());
        label.setTextSize(11);
        label.setTextColor(Theme.text2());
        label.setPadding(dp(2), 0, dp(2), 0);
        row.addView(label);
        row.addView(iconButton(IconView.CHEVRON_RIGHT, idx < kids.size() - 1,
                new View.OnClickListener() {
                    public void onClick(View v) {
                        if (idx < kids.size() - 1) jumpBranch(kids.get(idx + 1));
                    }
                }));
        messages.addView(row);
    }

    /**
     * 一个带淡色底的图标按钮。
     *
     * <p>底色是必要的：图标是描边路径，万一某台设备上路径没画出来，
     * 至少还看得见一个按钮，而不是整行空白。
     */
    private View iconButton(int icon, boolean enabled, final View.OnClickListener click) {
        FrameLayout box = new FrameLayout(this);
        box.setBackground(Theme.round(enabled ? Theme.surface2() : Theme.surface(),
                Theme.R_SM, this));

        IconView ic = new IconView(this, icon, 16);
        ic.setColor(enabled ? Theme.text2() : Theme.caption());
        ic.setAlpha(enabled ? 1f : 0.5f);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(16), dp(16));
        ip.gravity = Gravity.CENTER;
        box.addView(ic, ip);

        if (enabled) box.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(26));
        lp.rightMargin = dp(6);
        box.setLayoutParams(lp);
        return box;
    }

    /**
     * 选择文本：直接在原文里进入系统选择模式，不弹对话框。
     *
     * <p>做法是把这条消息里的 TextView 变成可选中，再触发一次长按——
     * 系统的选择手柄与复制菜单就出现了，和你长按气泡本身的效果一致。
     */
    private void selectText(String nodeId) {
        View root = nodeViews.get(nodeId);
        final android.widget.TextView tv = findTextView(root);
        if (tv == null) {
            Snack.show(this, "这条消息没有可选择的文字");
            return;
        }
        final CharSequence text = tv.getText();
        final int len = text == null ? 0 : text.length();
        if (len == 0) {
            Snack.show(this, "这条消息没有可选择的文字");
            return;
        }

        tv.setTextIsSelectable(true);
        tv.requestFocus();

        // 选择结束（点复制 / 点空白处）后恢复成不可选，长按菜单才会回来。
        // 关键：这里必须 post 出去延后执行。在 onDestroyActionMode 里同步调用
        // setTextIsSelectable(false) 会在框架销毁 ActionMode 的过程中改动
        // 同一个 TextView 的内部状态，点「复制」或点空白按下去就是直接闪退。
        tv.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
            public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                return true;
            }
            public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                return false;
            }
            public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                return false;
            }
            public void onDestroyActionMode(android.view.ActionMode mode) {
                tv.post(new Runnable() {
                    public void run() {
                        try {
                            tv.setTextIsSelectable(false);
                            tv.setCustomSelectionActionModeCallback(null);
                        } catch (Throwable ignored) {}
                    }
                });
            }
        });

        // 默认整段选中：点「选择文本」时就该看到全选的高亮，
        // 而不是先出现一个光标让人自己拖。
        try {
            selectAll(tv, len);
            tv.performLongClick();          // 让系统把选择工具条弹出来
            final android.widget.TextView fv = tv;
            tv.post(new Runnable() {
                public void run() {
                    // 长按会把选择缩成词，这里再铺满整段
                    selectAll(fv, len);
                }
            });
        } catch (Throwable t) {
            Snack.show(this, "无法进入选择模式");
        }
    }

    /**
     * 把 TextView 的内容整段选中。
     *
     * <p>用 {@link android.text.Selection#setSelection} 而不是
     * {@code TextView.setSelection}：后者在编译用的精简 android.jar 里没有，
     * 而且对非 Spannable 的文本本来就无效，所以先换成 SPANNABLE 再选。
     */
    private void selectAll(android.widget.TextView tv, int len) {
        CharSequence cs = tv.getText();
        if (!(cs instanceof android.text.Spannable)) {
            tv.setText(cs, android.widget.TextView.BufferType.SPANNABLE);
            cs = tv.getText();
        }
        if (cs instanceof android.text.Spannable) {
            android.text.Selection.setSelection((android.text.Spannable) cs, 0, len);
        }
    }

    /** 在视图树里找第一个有文字的 TextView。 */
    private android.widget.TextView findTextView(View v) {
        if (v == null) return null;
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            if (t.getText() != null && t.getText().length() > 0) return t;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.TextView t = findTextView(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    /** 切到某条分支：leaf 移到它的末端后整页重绘。 */
    private void jumpBranch(String targetNodeId) {
        if (running) {
            Snack.show(this, "正在生成，先停止再切换分支");
            return;
        }
        String sessId = sessions.currentId();
        JSONArray all = sessions.loadHistory(sessId);
        String leaf = com.dsh.android.kernel.Conversation.lastDescendant(all, targetNodeId);
        sessions.setLeaf(sessId, leaf);
        renderHistory();
    }

    /**
     * 重新生成某条助手回答。
     *
     * <p>把 leaf 退回这条回答对应的提问，然后**不追加**用户消息直接再生成一次。
     * 之前复用 resend()，结果同一个问题被写进历史两遍，还多出一个用户气泡。
     */
    private void regenerateNode(String nodeId) {
        if (running) {
            Snack.show(this, "正在生成，先停止");
            return;
        }
        final String sessId = sessions.currentId();
        final JSONArray all = sessions.loadHistory(sessId);
        int i = com.dsh.android.kernel.Conversation.indexOf(all, nodeId);
        if (i < 0) return;
        JSONObject node = all.optJSONObject(i);
        String parent = node == null ? "" : node.optString("parent", "");
        if (parent.length() == 0) {
            Snack.show(this, "这条没有对应的提问，无法重新生成");
            return;
        }
        final String startLeaf = parent;
        final JSONArray hist = com.dsh.android.kernel.Conversation.path(all, parent);
        final int pathFrom = hist.length();
        sessions.setLeaf(sessId, parent);
        renderHistory();

        streamingBuf.setLength(0);
        streamingView = null;
        liveThinking = null;
        running = true;
        sendMode = MODE_PAUSE;
        setSendMode(MODE_PAUSE);
        startTaskBar();
        scrollBottom();

        new Thread(new Runnable() {
            public void run() {
                try {
                    loop.run(hist, null, sink(), false, true);
                    commitPath(sessId, all, hist, pathFrom, startLeaf);
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

    /** 长按消息：右键式小菜单。 */
    private void attachBranchMenu(final View v, final String nodeId) {
        if (v == null) return;
        v.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View view) {
                JSONArray all = sessions.loadHistory(sessions.currentId());
                int i = com.dsh.android.kernel.Conversation.indexOf(all, nodeId);
                if (i < 0) return false;
                String role = all.optJSONObject(i).optString("role", "");
                String content = all.optJSONObject(i).optString("content", "");

                List<MiniMenu.Item> items = new java.util.ArrayList<MiniMenu.Item>();
                if (content.length() > 0) {
                    items.add(MiniMenu.Item.of("选择文本", new Runnable() {
                        public void run() { selectText(nodeId); }
                    }));
                    items.add(MiniMenu.Item.of("复制", new Runnable() {
                        public void run() { copyNode(nodeId); }
                    }));
                }
                if ("assistant".equals(role)) {
                    items.add(MiniMenu.Item.of("重新生成", new Runnable() {
                        public void run() { regenerateNode(nodeId); }
                    }));
                    items.add(MiniMenu.Item.of("朗读", new Runnable() {
                        public void run() { speakNode(nodeId); }
                    }));
                }
                items.add(MiniMenu.Item.of("从这里继续（分支）", new Runnable() {
                    public void run() { startBranchFrom(nodeId); }
                }));
                if (items.isEmpty()) return false;
                lastMenu = MiniMenu.show(MainActivity.this, view, null, items);
                return true;
            }
        });
    }

    /**
     * 从这里开一条分支。
     *
     * <p>分支是**独立的会话**：把到这条消息为止的路径复制进新会话再切过去，
     * 原会话一个字节都不动。这样「分支」和「重新生成」就是两件事：
     * 重新生成是同一条问题下的另一个答案（同会话的兄弟节点），
     * 分支是从这里另起一段对话。
     */
    private void startBranchFrom(final String nodeId) {
        if (running) {
            Snack.show(this, "正在生成，先停止再分支");
            return;
        }
        String sessId = sessions.currentId();
        JSONArray all = sessions.loadHistory(sessId);
        if (com.dsh.android.kernel.Conversation.indexOf(all, nodeId) < 0) return;

        JSONArray path = com.dsh.android.kernel.Conversation.path(all, nodeId);
        if (path.length() == 0) return;

        JSONArray copy = new JSONArray();
        for (int k = 0; k < path.length(); k++) {
            JSONObject src = path.optJSONObject(k);
            if (src == null) continue;
            try {
                copy.put(new JSONObject(src.toString()));
            } catch (Exception ignored) {}
        }

        String base = currentTitle();
        if (base == null || base.length() == 0) base = "新对话";
        String id = sessions.importSession(base + " · 分支", "", copy);
        switchTo(id);
        Snack.show(this, "已在新会话中打开这条分支，原会话保持不变");
    }

    private void dismissDialog() {
        try {
            if (nodeMenu != null) nodeMenu.dismiss();
        } catch (Throwable ignored) {}
        nodeMenu = null;
    }

    /** 朗读某条回答：已在该条朗读中则停止。 */
    private void speakNode(String nodeId) {
        if (!Speaker.available()) {
            Snack.show(this, "这台设备没有可用的语音引擎");
            return;
        }
        JSONArray all = sessions.loadHistory(sessions.currentId());
        int i = com.dsh.android.kernel.Conversation.indexOf(all, nodeId);
        if (i < 0) return;
        String content = all.optJSONObject(i).optString("content", "");
        if (content.length() == 0) return;
        if (nodeId.equals(speakingNode)) {
            Speaker.stop();
            speakingNode = null;
            Snack.show(this, "已停止朗读");
            return;
        }
        speakingNode = nodeId;
        Speaker.speak(content);
    }

    private void copyNode(String nodeId) {
        JSONArray all = sessions.loadHistory(sessions.currentId());
        int i = com.dsh.android.kernel.Conversation.indexOf(all, nodeId);
        if (i < 0) return;
        String content = all.optJSONObject(i).optString("content", "");
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", content));
        Snack.show(this, "已复制");
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

        // DeepSeek 标志：深色模式白色、浅色模式黑色（同一张白色剪影 + 染色）
        android.widget.ImageView logo = new android.widget.ImageView(this);
        try {
            java.io.InputStream in = getAssets().open("deepseek_logo.png");
            android.graphics.drawable.Drawable d =
                    android.graphics.drawable.Drawable.createFromStream(in, "deepseek_logo");
            in.close();
            logo.setImageDrawable(d);
        } catch (Throwable t) {
            logo.setVisibility(View.GONE);
        }
        logo.setColorFilter(Theme.isDark() ? 0xFFFFFFFF : 0xFF000000);
        int logoSize = dp(64);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(logoSize, logoSize);
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(logo, llp);

        TextView h = new TextView(this);
        h.setText(Texts.t(this, "welcome_title"));
        h.setTextSize(20);
        h.setTypeface(null, Typeface.BOLD);
        h.setTextColor(Theme.text());
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, dp(14), 0, 0);
        box.addView(h);

        TextView sub = new TextView(this);
        sub.setText(Texts.t(this, "welcome_sub"));
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
    /** 新建一条工具胶囊并登记，等 onToolResult 回来再收尾。 */
    private void addToolPill(String name, String args) {
        ToolPill pill = new ToolPill(this, friendlyTool(name), summaryOf(args));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        pill.setLayoutParams(lp);
        pill.setOnExpandListener(new ToolPill.OnExpand() {
            public void onToggled(ToolPill p) {
                if (pinnedToBottom) {
                    messageScroll.post(new Runnable() {
                        public void run() { messageScroll.fullScroll(View.FOCUS_DOWN); }
                    });
                }
            }
        });
        pill.setAlpha(0f);
        pill.setTranslationY(dp(4));
        messages.addView(pill);
        pill.animate().alpha(1f).translationY(0).setDuration(160).start();

        java.util.ArrayDeque<ToolPill> q = runningPills.get(name);
        if (q == null) {
            q = new java.util.ArrayDeque<ToolPill>();
            runningPills.put(name, q);
        }
        q.addLast(pill);
    }

    private ToolPill takePill(String name) {
        java.util.ArrayDeque<ToolPill> q = runningPills.get(name);
        if (q == null || q.isEmpty()) return null;
        return q.pollFirst();
    }

    /** 参数摘要：只留最关键的短值，避免胶囊被长 JSON 撑开。 */
    private static String summaryOf(String args) {
        if (args == null) return "";
        String a = args.trim();
        if (a.length() == 0) return "";
        try {
            org.json.JSONObject o = new org.json.JSONObject(a);
            String[] prefer = {"path", "url", "query", "name", "cmd", "selector"};
            for (int i = 0; i < prefer.length; i++) {
                String v = o.optString(prefer[i], "");
                if (v.length() > 0) {
                    return v.length() > 36 ? v.substring(0, 36) + "…" : v;
                }
            }
            java.util.Iterator<String> it = o.keys();
            if (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, "");
                if (v.length() > 0) return v.length() > 30 ? v.substring(0, 30) + "…" : v;
            }
        } catch (Exception ignored) {}
        return a.length() > 36 ? a.substring(0, 36) + "…" : a;
    }

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
    /** 带自绘图标的一行提示：符号也走 IconView，不依赖字体里有没有对应的 emoji。 */
    private void addIconNotice(int iconType, String text) {
        addIconNotice(iconType, text, null);
    }

    private void addIconNotice(int iconType, String text, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);

        IconView ic = new IconView(this, iconType, 11);
        ic.setColor(Theme.text3());
        row.addView(ic, new LinearLayout.LayoutParams(dp(11), dp(11)));

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(Theme.text3());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(5);
        row.addView(tv, tlp);

        if (onClick != null) {
            row.setOnClickListener(onClick);
            row.setBackground(Theme.round(Theme.surface2(), 8f, this));
            row.setPadding(dp(10), dp(6), dp(10), dp(6));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(8));
        messages.addView(row, lp);
    }

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
    private TextView jumpBottom;
    private LinearLayout composerWrap;
    private android.widget.PopupWindow cmdPopup;
    private View cmdCard;
    private int cmdCardHeight;
    private LinearLayout cmdPluginHost;
    private final java.util.Map<String, TextView> cmdStateViews = new java.util.HashMap<String, TextView>();
    private final java.util.Map<String, TextView> cmdMarkViews = new java.util.HashMap<String, TextView>();
    /** 任务状态条：本轮跑了多久、调了几个工具。 */
    /** 当前正在流式输出的思考块。 */
    private ThinkingBlock liveThinking;
    /** 最近一次工具调用的开始时间，用于面板里的耗时。 */
    private android.app.Dialog cmdDialog;
    private long toolStartAt;
    private ComputerSheet.Step curStep;

    private TextView taskBar;
    private long runStartMs;
    private int runToolCount;
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running || taskBar == null) return;
            long sec = (System.currentTimeMillis() - runStartMs) / 1000;
            taskBar.setText("运行中 · " + runToolCount + " 个工具 · " + sec + "s");
            ticker.postDelayed(this, 1000);
        }
    };

    /** 同步右下角「回到底部」按钮的显示。 */
    private void syncJumpButton() {
        if (jumpBottom == null) return;
        boolean show = !pinnedToBottom;
        if (show && jumpBottom.getVisibility() != View.VISIBLE) {
            jumpBottom.setVisibility(View.VISIBLE);
            jumpBottom.setAlpha(0f);
            jumpBottom.animate().alpha(1f).setDuration(140).start();
        } else if (!show && jumpBottom.getVisibility() == View.VISIBLE) {
            jumpBottom.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
                public void run() { jumpBottom.setVisibility(View.GONE); }
            }).start();
        }
    }

    private void scrollBottom() {
        pinnedToBottom = true;
        syncJumpButton();
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

    /**
     * 强度档位在界面上的显示名。
     *
     * <p>显示的是真正发给接口的取值，避免界面写着「最大强度」而请求里其实没带参数
     * 这种对不上的情况。
     */
    private static String effortLabel(String level) {
        String l = C.normalizeLevel(level);
        if (C.LEVEL_OFF.equals(l)) return "关闭思考";
        String e = C.reasoningEffortFor(l);
        return e == null ? "默认" : e;
    }

    private void reloadClient() {
        SharedPreferences sp = getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE);
        String base = sp.getString(C.KEY_BASE_URL, C.DEFAULT_BASE_URL);
        String key = com.dsh.android.kernel.SecureStore.readKey(sp);
        String model = sp.getString(C.KEY_MODEL, "");
        if (model == null || model.length() == 0) model = C.MODEL_CHAT;
        String level = C.normalizeLevel(sp.getString(C.KEY_REASONING, C.LEVEL_MEDIUM));
        int maxTokens = C.maxTokensFor(level);

        client = new LlmClient(this, base, key, model, maxTokens);
        // 强度不是「输出多长」，是「想多深」。要真的发给接口才算数。
        client.setReasoningLevel(level);
        com.dsh.android.kernel.UsageStats.addRequest(this);
        loop = new AgentLoop(client, tools, buildSystemPrompt());
        loop.setLogDir(getFilesDir());
    }

    private String buildSystemPrompt() {
        boolean device = tools.isDeviceScope();
        boolean a11y = tools.isA11yEnabled() && A11yService.isReady();
        StringBuilder sb = new StringBuilder();
        // 长期记忆：整份注入，让模型知道跨对话该记住什么
        String mem = new com.dsh.android.kernel.MemoryStore(getFilesDir()).read();
        if (mem.trim().length() > 0) {
            sb.append("以下是你长期记住的事实（来自 memory.md，跨对话有效）：\n");
            sb.append(mem.trim()).append("\n\n");
            sb.append("需要记住新的长期事实时，调用 memory.update 工具写入。\n\n");
        }

        // 跟随用户的语言：写「用中文回答」会让英文提问也回中文
        sb.append("你是运行在 Android 手机上的 AI 助手 DShAndroid。"
                + "用简洁准确的语言回答，并跟随用户当前使用的语言："
                + "用户用英文提问就用英文回答，用中文提问才用中文。\n\n");
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

        // 身份信息：回答「开发者是谁 / 用的什么模型」时用
        try {
            String model = prefs().getString(C.KEY_MODEL, "");
            if (model == null || model.length() == 0) model = C.MODEL_CHAT;
            sb.append("\n\n【身份】你是 DShAndroid，运行在 Android 手机上的 Agent 工具，开发者是「可」。");
            sb.append("当用户问开发者是谁、怎么联系开发者时，回答：开发者是可；");
            sb.append("GitHub 主页 ").append(com.dsh.android.kernel.About.GITHUB);
            sb.append("；项目仓库 ").append(com.dsh.android.kernel.About.REPO);
            sb.append("；问题反馈 ").append(com.dsh.android.kernel.About.ISSUES);
            sb.append("。不要编造其它联系方式。");
            sb.append("当用户问当前用的是什么模型时，回答：").append(model).append("。");
        } catch (Throwable ignored) {}

        // 用户预先写好的背景与环境说明（/memory 可以整体关掉）
        String preset = prefs().getBoolean("presets_on", true)
                ? com.dsh.android.kernel.Presets.activeText(this) : "";
        if (preset.length() > 0) {
            sb.append("\n\n【用户设定的背景】以下是用户事先交代的工作环境与约定，回答时据此进行：\n");
            sb.append(preset);
        }
        return sb.toString();
    }

    /** 暂停：断开请求，保留已经收到的内容，稍后可以接着写。 */
    private void pauseRun() {
        if (!running) return;
        pausedNow = true;
        com.dsh.android.kernel.RunStatus.pause();
        loop.pause();
        // 工具线程可能正卡在「等你选」上，一并放行，否则会干等到超时
        AskBridge.cancelPending("已暂停");
        Snack.show(this, "已暂停，点右下继续");
    }

    /** 历史里最后一条 assistant 是不是被暂停中断的（中间没有新提问就算）。 */
    private static boolean tailInterrupted(JSONArray hist) {
        for (int i = hist.length() - 1; i >= 0; i--) {
            JSONObject o = hist.optJSONObject(i);
            if (o == null) continue;
            String role = o.optString("role");
            if ("assistant".equals(role)) return o.optBoolean("interrupted", false);
            if ("user".equals(role)) return false;   // 后面已经有新提问，不再算「被中断的回答」
        }
        return false;
    }

    /** 打开会话时按历史决定按钮是「发送」还是「继续」。 */
    private void refreshSendMode() {
        if (running || sendBtn == null) return;
        boolean inter = tailInterrupted(sessions.loadCurrentHistory());
        pausedNow = inter;
        setSendMode(inter ? MODE_CONTINUE : MODE_SEND);
    }

    /** 接着上次被暂停的回答继续写。 */
    private void continueRun() {
        if (running) return;
        final String sessId = sessions.currentId();
        final JSONArray allHist = loadAllForTurn();
        final String startLeaf = sessions.leafOf(sessId, allHist);
        final JSONArray hist = com.dsh.android.kernel.Conversation.path(allHist, startLeaf);
        final int pathFrom = hist.length();
        boolean interrupted = tailInterrupted(hist);
        if (!interrupted) {
            setSendMode(MODE_SEND);
            Snack.show(this, "没有可继续的回答");
            return;
        }

        pausedNow = false;
        streamingBuf.setLength(0);
        streamingView = null;
        liveThinking = null;
        scrollBottom();
        loop.setAutoCompact(prefs().getBoolean(C.KEY_AUTO_COMPACT, true));
        loop.setSystemPrompt(buildSystemPrompt());
        running = true;
        com.dsh.android.kernel.RunStatus.begin(com.dsh.android.kernel.RunStatus.THINKING, "");
        setSendMode(MODE_PAUSE);
        startTaskBar();

        new Thread(new Runnable() {
            public void run() {
                try {
                    loop.run(hist, CONTINUE_NOTE, sink(), true);
                    commitPath(sessId, allHist, hist, pathFrom, startLeaf);
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

    private void onSend() {
        if (running) {
            pauseRun();
            return;
        }
        if (sendMode == MODE_CONTINUE && input.getText().toString().trim().length() == 0) {
            continueRun();
            return;
        }
        final String typed = input.getText().toString().trim();
        // 允许「只发附件」：有附件时文本可以为空
        if (typed.length() == 0 && attaches.isEmpty() && images.isEmpty()) return;

        // 以 / 开头：先看内置指令，再看插件注册的指令
        // 负面情绪：给出一次关怀提示（有冷却，不反复打扰）
        maybeShowCare(typed);

        if (typed.startsWith("/") && attaches.isEmpty() && images.isEmpty()) {
            if (runBuiltinCommand(typed)) {
                input.setText("");
                return;
            }
            if (runPluginCommand(typed)) {
                input.setText("");
                return;
            }
            Snack.show(this, "没有这条指令，输入 /help 看可用列表");
            return;
        }

        if (messages.getChildCount() == 1) messages.removeAllViews();

        final String text = withAttachment(typed);
        // 先快照，后面清空列表还要用来画卡片
        final java.util.List<Attach> sent = new java.util.ArrayList<Attach>(attaches);
        // 图片单独快照：它走 content 数组，不参与正文拼装
        final org.json.JSONArray sentImages = new org.json.JSONArray();
        for (int i = 0; i < images.size(); i++) sentImages.put(images.get(i));
        images.clear();
        refreshImageChip();

        input.setText("");
        clearAttachment();
        addUserBubble(typed, sent);
        scrollBottom();

        // 会话标题：首条消息作为标题
        String id = sessions.currentId();
        List<SessionMeta> all = sessions.listSessions();
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

        final String sessId = sessions.currentId();
        final JSONArray allHist = loadAllForTurn();
        final String startLeaf = sessions.leafOf(sessId, allHist);
        final JSONArray hist = com.dsh.android.kernel.Conversation.path(allHist, startLeaf);
        final int pathFrom = hist.length();

        // 不再预建空气泡：等第一段文字到达时才创建，
        // 这样「文字 → 工具 → 卡片 → 文字」能按真实顺序穿插
        streamingBuf.setLength(0);
        streamingView = null;
        liveThinking = null;
        scrollBottom();

        loop.setAutoCompact(prefs().getBoolean(C.KEY_AUTO_COMPACT, true));
        loop.setSystemPrompt(buildSystemPrompt());
        running = true;
        com.dsh.android.kernel.RunStatus.begin(com.dsh.android.kernel.RunStatus.THINKING, "");
        sendMode = MODE_PAUSE; setSendMode(MODE_PAUSE);
        startTaskBar();

        new Thread(new Runnable() {
            public void run() {
                try {
                    loop.run(hist, text, sink(), false, false, sentImages);
                    commitPath(sessId, allHist, hist, pathFrom, startLeaf);
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
    private static final int REQ_PICK_IMAGE = 2002;

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
    /** 待发送的图片（data URL），与附件分开：图片走 content 数组，附件走正文内联 */
    private final java.util.ArrayList<String> images = new java.util.ArrayList<String>();

    private static final int MAX_FILES = 8;              // 单次消息最多几个文件
    private static final int PER_FILE_CHARS = 40000;     // 单个文件内联字符上限
    private static final int TOTAL_CHARS = 120000;       // 全部附件合计上限

    /** 选图：多选，读入后统一压缩再编码。 */
    private void pickImage() {
        try {
            Intent it = new Intent(Intent.ACTION_GET_CONTENT);
            it.setType("image/*");
            it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(it, "选择图片"), REQ_PICK_IMAGE);
        } catch (Throwable t) {
            Snack.show(this, "无法打开图片选择器");
        }
    }

    /**
     * 读入并压缩一张图片。
     *
     * <p>先用 inJustDecodeBounds 拿到原始尺寸，按长边 1568px 计算采样率，
     * 再用 JPEG 质量逐档下降，直到体积不超过 800KB —— 单张原图动辄几 MB，
     * 直接 base64 塞进请求既慢又容易超限。
     */
    private String loadCompressedImage(android.net.Uri uri) {
        try {
            android.graphics.BitmapFactory.Options probe = new android.graphics.BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            java.io.InputStream in0 = getContentResolver().openInputStream(uri);
            android.graphics.BitmapFactory.decodeStream(in0, null, probe);
            if (in0 != null) in0.close();

            int sample = 1;
            int longEdge = Math.max(probe.outWidth, probe.outHeight);
            while (longEdge / sample > 1568) sample *= 2;

            android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
            opt.inSampleSize = sample;
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in, null, opt);
            if (in != null) in.close();
            if (bmp == null) return null;

            // 采样后可能仍偏大，等比缩到长边 1568
            int w = bmp.getWidth(), h = bmp.getHeight();
            int cur = Math.max(w, h);
            if (cur > 1568) {
                float scale = 1568f / cur;
                android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                        bmp, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }

            byte[] out = null;
            for (int q = 85; q >= 45; q -= 10) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, bo);
                out = bo.toByteArray();
                if (out.length <= 800 * 1024) break;
            }
            bmp.recycle();
            if (out == null) return null;

            String b64 = android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
            return "data:image/jpeg;base64," + b64;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把已选的图片从输入框上方的提示里清掉。 */
    private void clearImages() {
        images.clear();
        refreshImageChip();
    }

    private TextView imageChip;

    private void refreshImageChip() {
        if (imageChip == null) return;
        if (images.isEmpty()) {
            imageChip.setVisibility(View.GONE);
            return;
        }
        imageChip.setVisibility(View.VISIBLE);
        imageChip.setText("已选 " + images.size() + " 张图片 · 点此清除");
    }

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

    /** 处理选中的图片：压缩后放进待发送列表。 */
    private void handlePickedImages(Intent data) {
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
        for (int i = 0; i < uris.size() && i < 4; i++) {     // 单次最多 4 张
            String url = loadCompressedImage(uris.get(i));
            if (url != null) {
                images.add(url);
                added++;
            }
        }
        if (added == 0) {
            Snack.show(this, "图片读取失败");
            return;
        }
        refreshImageChip();
        Snack.show(this, "已添加 " + added + " 张图片（已压缩）");
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null) return;

        if (req == REQ_PICK_IMAGE) {
            handlePickedImages(data);
            return;
        }
        if (req != REQ_PICK_FILE) return;

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

    /**
     * 发送按钮三态：发送箭头 / 暂停双杠 / 继续三角，三者都是自绘路径。
     *
     * <p>运行中给的是暂停而不是停止——思考到一半按下不该把这段回答判死刑，
     * 想彻底停下可以长按。
     */
    private void setSendMode(int mode) {
        if (sendBtn == null || sendIcon == null) return;
        switch (mode) {
            case MODE_PAUSE:
                sendIcon.setType(IconView.PAUSE);
                sendIcon.setColor(Theme.onPrimary());
                sendBtn.setBackground(Theme.round(Theme.primary(), 20f, this));
                break;
            case MODE_CONTINUE:
                sendIcon.setType(IconView.PLAY);
                sendIcon.setColor(Theme.onPrimary());
                sendBtn.setBackground(Theme.round(Theme.primary(), 20f, this));
                break;
            default:
                sendIcon.setType(IconView.SEND);
                sendIcon.setColor(Theme.onPrimary());
                sendBtn.setBackground(Theme.round(Theme.primary(), 20f, this));
                break;
        }
        sendMode = mode;
    }

    private int sendMode = MODE_SEND;

    /** 用户消息长按：仅复制。 */
    private void enableCopyText(TextView tv, final String text) {
        tv.setTextIsSelectable(true);
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", text));
                Snack.show(MainActivity.this, Texts.t(MainActivity.this, "copied"));
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

        menu.addView(menuItem(Texts.t(this, "copy"), new View.OnClickListener() {
            public void onClick(View x) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", text));
                Snack.show(MainActivity.this, Texts.t(MainActivity.this, "copied"));
            }
        }));
        menu.addView(menuItem(Texts.t(this, "regen"), new View.OnClickListener() {
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
        // 只把 leaf 往回挪，其它分支不动：重新生成会自然长出一条新分支
        String leaf = kept.length() == 0 ? null
                : kept.optJSONObject(kept.length() - 1).optString("id", null);
        sessions.setLeaf(sessions.currentId(), leaf);
        renderHistory();
        resend(lastUser, kept);
    }

    /** 用给定文本与历史再跑一轮（重新生成复用）。 */
    private void resend(final String text, final JSONArray hist) {
        final String sessId = sessions.currentId();
        final JSONArray allHist = loadAllForTurn();
        final String startLeaf = sessions.leafOf(sessId, allHist);
        final int pathFrom = 0;
        streamingBuf.setLength(0);
        streamingView = null;
        liveThinking = null;
        scrollBottom();
        running = true;
        sendMode = MODE_PAUSE; setSendMode(MODE_PAUSE);
        startTaskBar();

        new Thread(new Runnable() {
            public void run() {
                try {
                    loop.run(hist, text, sink());
                    commitPath(sessId, allHist, hist, pathFrom, startLeaf);
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
        com.dsh.android.kernel.RunStatus.reset();
        // 历史已经落盘，按钮状态按「最后一条回答是不是被打断的」来定：
        // 一个字都没收到就暂停的情况不该留一个点了没用的继续键
        refreshSendMode();
        closeTextSegment();
        stopTaskBar();
        // 流式期间是直接往界面追加气泡的，没有操作行。
        // 收尾重绘一次，这条新回答下面才会出现分支/复制/重新生成。
        try { renderHistory(); } catch (Throwable ignored) {}
        scrollBottom();
    }

    /** 任务状态条：运行中显示工具数与耗时，结束后留 4 秒摘要再淡出。 */
    private void startTaskBar() {
        if (taskBar == null) return;
        runStartMs = System.currentTimeMillis();
        runToolCount = 0;
        taskBar.setText("运行中 · 0 个工具 · 0s");
        taskBar.setAlpha(1f);
        taskBar.setVisibility(View.VISIBLE);
        ticker.removeCallbacks(tick);
        ticker.post(tick);
    }

    private void stopTaskBar() {
        ticker.removeCallbacks(tick);
        if (taskBar == null) return;
        long sec = Math.max(1, (System.currentTimeMillis() - runStartMs) / 1000);
        taskBar.setText("完成 · " + runToolCount + " 个工具 · " + sec + "s");
        taskBar.postDelayed(new Runnable() {
            public void run() {
                if (taskBar == null || running) return;
                taskBar.animate().alpha(0f).setDuration(300).withEndAction(new Runnable() {
                    public void run() {
                        if (taskBar != null) taskBar.setVisibility(View.GONE);
                    }
                }).start();
            }
        }, 4000);
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

            public void onReasoning(final String delta) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (liveThinking == null) {
                            closeTextSegment();
                            liveThinking = new ThinkingBlock(MainActivity.this, "");
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
                            lp.setMargins(dp(2), dp(4), dp(2), dp(6));
                            liveThinking.setLayoutParams(lp);
                            messages.addView(liveThinking);
                        }
                        liveThinking.append(delta);
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
                        runToolCount++;
                        toolStartAt = System.currentTimeMillis();
                        curStep = ("ui.ask".equals(name)) ? null
                                : ComputerSheet.begin(friendlyTool(name), summaryOf(args),
                                        name.startsWith("browser"));
                        scrollBottomIfPinned();
                    }
                });
            }
            public void onToolResult(final String name, final String result) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        boolean ok = result == null || !result.contains("\"ok\":false");
                        ToolPill pill = takePill(name);
                        ComputerSheet.finish(curStep, ok, result,
                                Math.max(0, System.currentTimeMillis() - toolStartAt));
                        curStep = null;
                        if (pill != null) {
                            pill.finish(ok, result);
                        } else if (!ok) {
                            // 没找到对应胶囊（比如历史回放），退回文字提示
                            closeTextSegment();
                            addToolLine(name, false, result);
                        }
                        scrollBottomIfPinned();
                    }
                });
            }
            @Override
            public void onPaused() {
                runOnUiThread(new Runnable() {
                    public void run() {
                        pausedNow = true;
                        closeTextSegment();
                        addIconNotice(IconView.PAUSE, "已暂停 · 点右下继续");
                        scrollBottom();
                    }
                });
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

    /** 让用户自己写答案，代替点选项。 */
    private void askCustomAnswer(final AskBridge.Ask live, final TextView tag) {
        final EditText et = new EditText(this);
        et.setSingleLine(false);
        et.setMinLines(2);
        et.setTextSize(14);
        et.setTextColor(Theme.text());
        et.setHintTextColor(Theme.caption());
        et.setHint("写下你的回答");
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        et.setBackground(Theme.round(Theme.surface2(), Theme.R_MD, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(8), dp(16), dp(4));
        et.setLayoutParams(lp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(et);

        new AppDialog.Builder(this)
                .setTitle("自己填写")
                .setView(box)
                .setPositiveButton("提交", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String text = et.getText().toString().trim();
                        if (text.length() == 0) return;
                        live.reply(text);
                        tag.setText("已选择");
                        tag.setTextColor(Theme.primary());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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

        // 自定义填写：选项之外的答案
        boolean answerIsOption = false;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(answer)) { answerIsOption = true; break; }
        }
        if (!readonly && !answered) {
            TextView custom = new TextView(this);
            custom.setText("\u270E 自己填写…");
            custom.setTextSize(14);
            custom.setPadding(dp(12), dp(11), dp(12), dp(11));
            LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp2.setMargins(0, 0, 0, dp(6));
            custom.setLayoutParams(clp2);
            custom.setBackground(Theme.roundOutlined(Theme.surface(), Theme.R_MD,
                    Theme.border(), this));
            custom.setTextColor(Theme.text2());
            custom.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (live.isAnswered()) {
                        Snack.show(MainActivity.this, "这个选择已经提交过了");
                        return;
                    }
                    askCustomAnswer(live, tag);
                }
            });
            card.addView(custom);
        } else if (answered && !answerIsOption && answer.length() > 0) {
            TextView custom = new TextView(this);
            custom.setText(answer);
            custom.setTextSize(14);
            custom.setPadding(dp(12), dp(11), dp(12), dp(11));
            applyChoiceStyle(custom, true, true);
            card.addView(custom);
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
