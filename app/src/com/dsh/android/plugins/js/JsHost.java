package com.dsh.android.plugins.js;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DSH 插件的 JS 运行时。
 *
 * <p>为什么用 WebView 而不是内置 JS 引擎：DSH 插件的工具几乎都是
 * {@code async execute()}，需要 Promise / async-await / fetch。系统 WebView 自带完整实现，
 * 不增加安装包体积，也不用带 NDK 交叉编译的原生库。
 *
 * <p>线程模型：WebView 只能在主线程操作，而工具执行在后台线程。所以这里的每个入口
 * 都把 JS 求值 post 到主线程，再用 CountDownLatch 等结果——对调用方表现为同步调用。
 * 所有等待都有超时，插件写死循环不会拖死整个 Agent。
 *
 * <p>限制（明确写出来，不假装支持）：
 * 只实现 {@code apply(ctx){ ctx.tools.register(...) }} 这条路径；
 * {@code ctx.on/ctx.plugin/ctx.service} 在 JS 侧直接抛错，界面会显示出来。
 */
public final class JsHost {

    private static final long BOOT_TIMEOUT = 8000L;
    private static final long DEFAULT_CALL_TIMEOUT = 30000L;

    /** @JavascriptInterface 暴露给 JS 的桥。 */
    public static final class Bridge {
        @JavascriptInterface
        public void done(String token, String payload) {
            JsHost.complete(token, payload);
        }
    }

    private static final Object LOCK = new Object();
    private static WebView web;
    private static boolean booted;
    private static boolean creating;
    private static Runnable onBooted;
    private static String bootFailure;
    private static Context appCtx;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final AtomicInteger TOKENS = new AtomicInteger(1);
    private static final Map<String, Pending> PENDING = new HashMap<String, Pending>();

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String value;
        void finish(String v) {
            value = v;
            latch.countDown();
        }
    }

    private JsHost() {}

    static void complete(String token, String payload) {
        Pending p;
        synchronized (PENDING) {
            p = PENDING.remove(token);
        }
        if (p != null) p.finish(payload);
    }

    // ------------------------------------------------------------ 生命周期

    /** WebView 是否已经就绪（非阻塞）。 */
    public static boolean available() {
        synchronized (LOCK) {
            return web != null && booted;
        }
    }

    public static String failureReason() {
        synchronized (LOCK) {
            return bootFailure;
        }
    }

    /**
     * 异步准备运行时：就绪后回调（回调可能发生在调用线程，也可能在主线程）。
     *
     * <p>刻意不阻塞：PluginManager 在主线程调用，如果这里等 onPageFinished，
     * 主线程会被自己的 post 卡死，最后只能等到超时。所以主线程只负责建 WebView 并开始加载，
     * 真正的「加载完成」通过回调告诉调用方。
     */
    public static void ensureAsync(Context ctx, final Runnable onReady) {
        if (ctx != null) appCtx = ctx.getApplicationContext();

        boolean needCreate = false;
        synchronized (LOCK) {
            if (booted) {
                if (onReady != null) onReady.run();
                return;
            }
            if (bootFailure != null || creating) {
                return;
            }
            creating = true;
            needCreate = true;
        }
        if (!needCreate) return;

        final Context c = appCtx;
        if (c == null) {
            synchronized (LOCK) { bootFailure = "缺少 Context"; creating = false; }
            return;
        }

        Runnable create = new Runnable() {
            public void run() {
                try {
                    WebView w = new WebView(c);
                    w.getSettings().setJavaScriptEnabled(true);
                    w.getSettings().setAllowFileAccess(false);
                    w.getSettings().setAllowContentAccess(false);
                    w.getSettings().setDomStorageEnabled(false);
                    w.addJavascriptInterface(
                            new JsFsBridge(new java.io.File(c.getFilesDir(), "plugin-data")),
                            "DshFs");
                    w.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            view.evaluateJavascript(Boot.load(c), null);
                            Runnable cb;
                            synchronized (LOCK) {
                                booted = true;
                                creating = false;
                                cb = onBooted;
                                onBooted = null;
                            }
                            if (cb != null) cb.run();
                        }
                    });
                    w.addJavascriptInterface(new Bridge(), "DshBridge");
                    synchronized (LOCK) { web = w; }
                    w.loadDataWithBaseURL("https://dsh.local/",
                            "<!doctype html><meta charset=\"utf-8\"><title>dsh</title><body></body>",
                            "text/html", "utf-8", null);
                } catch (Throwable t) {
                    synchronized (LOCK) {
                        bootFailure = "无法初始化 JS 运行时：" + t.getClass().getSimpleName();
                        creating = false;
                    }
                }
            }
        };

        if (onMainThread()) {
            create.run();
            if (onReady != null) {
                synchronized (LOCK) { onBooted = onReady; }
            }
        } else {
            synchronized (LOCK) { onBooted = onReady; }
            UI.post(create);
        }
    }

    /** 后台线程用：等到运行时就绪（主线程上不允许阻塞，直接返回 false）。 */
    public static boolean awaitBoot(long timeoutMs) {
        if (available()) return true;
        if (onMainThread()) return false;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (LOCK) {
                if (booted) return true;
                if (bootFailure != null) return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return available();
    }

    private static boolean onMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    // ------------------------------------------------------------ 求值

    /** 同步求值，返回 JS 字符串结果（未解析成对象）。 */
    private static String evalSync(String js, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        UI.post(new Runnable() {
            public void run() {
                WebView w;
                synchronized (LOCK) { w = web; }
                if (w == null) {
                    latch.countDown();
                    return;
                }
                try {
                    w.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                        public void onReceiveValue(String value) {
                            out[0] = value;
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    out[0] = null;
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out[0];
    }

    /** 把 evaluateJavascript 返回的字面量还原成字符串（它给的是 JSON 字面量）。 */
    private static String unwrap(String literal) {
        if (literal == null || "null".equals(literal)) return null;
        try {
            Object v = new org.json.JSONTokener(literal).nextValue();
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return literal;
        }
    }

    /** JS 字符串字面量（用 Base64 传源码，避免任何转义问题）。 */
    private static String b64(String s) {
        return "\"" + Base64.encodeToString(s.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                Base64.NO_WRAP) + "\"";
    }

    private static String decode(String b64) {
        return new String(Base64.decode(b64, Base64.NO_WRAP),
                java.nio.charset.Charset.forName("UTF-8"));
    }

    // ------------------------------------------------------------ 插件装载

    /**
     * 装载一个插件源码（已做 ESM 改写）。
     * 返回 null 表示成功，否则是错误原因。
     */
    public static String load(String pluginId, String source) {
        if (!awaitBoot(BOOT_TIMEOUT)) return failureReason() == null ? "JS 运行时尚未就绪" : failureReason();

        String js = "(function(){var s=atob(" + b64(source)
                + ");var d=new TextDecoder();var src=d.decode(Uint8Array.from(s,c=>c.charCodeAt(0)));"
                + "return window.__load(" + JSONObject.quote(pluginId) + ",src);})()";
        String raw = unwrap(evalSync(js, BOOT_TIMEOUT));
        if (raw == null) return "JS 装载超时";

        try {
            JSONObject r = new JSONObject(raw);
            if (!r.optBoolean("ok", false)) return r.optString("error", "装载失败");
            return null;
        } catch (Exception e) {
            return "装载结果解析失败";
        }
    }

    /** 插件注册了哪些工具（JSON 数组：name/description/parameters）。 */
    public static JSONArray describe(String pluginId) {
        if (!awaitBoot(5000)) return new JSONArray();
        String raw = unwrap(evalSync("window.__describe(" + JSONObject.quote(pluginId) + ")", 5000));
        if (raw == null) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static void unload(String pluginId) {
        if (!available()) return;
        evalSync("window.__unload(" + JSONObject.quote(pluginId) + ")", 2000);
    }

    // ------------------------------------------------------------ 调用

    /** 执行插件工具；等 JS Promise 完成后返回结果文本（失败也返回 JSON 而不是抛异常）。 */
    public static String call(String pluginId, String tool, JSONObject args, long timeoutMs) {
        if (!awaitBoot(BOOT_TIMEOUT)) {
            return error("JS 运行时不可用：" + (failureReason() == null ? "未就绪" : failureReason()));
        }
        if (timeoutMs <= 0) timeoutMs = DEFAULT_CALL_TIMEOUT;

        String token = "c" + TOKENS.getAndIncrement();
        Pending p = new Pending();
        synchronized (PENDING) {
            PENDING.put(token, p);
        }

        String js = "window.__call(" + JSONObject.quote(pluginId) + ","
                + JSONObject.quote(tool) + ","
                + (args == null ? "{}" : args.toString()) + ")"
                + ".then(function(r){DshBridge.done(" + JSONObject.quote(token) + ",r);})"
                + ".catch(function(e){DshBridge.done(" + JSONObject.quote(token) + ","
                + "JSON.stringify({ok:false,error:String(e&&e.message||e)}));})";

        UI.post(new Runnable() {
            public void run() {
                WebView w;
                synchronized (LOCK) { w = web; }
                if (w == null) return;
                try {
                    w.evaluateJavascript(js, null);
                } catch (Throwable t) {
                    complete(token, "{\"ok\":false,\"error\":\"求值失败\"}");
                }
            }
        });

        try {
            if (!p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                synchronized (PENDING) { PENDING.remove(token); }
                return error("插件执行超时（" + timeoutMs + "ms）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (PENDING) { PENDING.remove(token); }
            return error("被中断");
        }
        String v = p.value;
        return v == null ? error("没有返回结果") : v;
    }

    // ------------------------------------------------------------ 扩展点

    /**
     * 触发事件（不等待结果）。插件用 ctx.on 挂的监听器会在 WebView 里被调用。
     * 事件是旁路，不能因为它失败影响主流程，所以完全吞掉异常。
     */
    public static void emitAsync(final String event, final JSONObject payload) {
        if (!available()) return;
        final String js = "window.__emit(" + JSONObject.quote(event) + ","
                + JSONObject.quote(payload == null ? "{}" : payload.toString()) + ")";
        UI.post(new Runnable() {
            public void run() {
                WebView w;
                synchronized (LOCK) { w = web; }
                if (w == null) return;
                try {
                    w.evaluateJavascript(js, null);
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 所有插件注册的命令。 */
    public static JSONArray commands() {
        if (!awaitBoot(3000)) return new JSONArray();
        String raw = unwrap(evalSync("window.__commands()", 3000));
        if (raw == null) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** 执行一个命令；支持返回 Promise 的异步命令。 */
    public static String runCommand(String pluginId, String name, String argsJson) {
        if (!awaitBoot(BOOT_TIMEOUT)) return "{\"ok\":false,\"error\":\"JS 运行时未就绪\"}";
        final String token = "r" + TOKENS.getAndIncrement();
        final Pending p = new Pending();
        synchronized (PENDING) { PENDING.put(token, p); }

        String js = "Promise.resolve(window.__runCommand("
                + JSONObject.quote(pluginId) + "," + JSONObject.quote(name) + ","
                + JSONObject.quote(argsJson == null ? "{}" : argsJson) + "))"
                + ".then(function(r){DshBridge.done(" + JSONObject.quote(token) + ",r);})"
                + ".catch(function(e){DshBridge.done(" + JSONObject.quote(token) + ","
                + "JSON.stringify({ok:false,error:String(e&&e.message||e)}));})";
        UI.post(new Runnable() {
            public void run() {
                WebView w;
                synchronized (LOCK) { w = web; }
                if (w == null) return;
                try {
                    w.evaluateJavascript(js, null);
                } catch (Throwable t) {
                    complete(token, "{\"ok\":false,\"error\":\"执行失败\"}");
                }
            }
        });
        try {
            if (!p.latch.await(15000, TimeUnit.MILLISECONDS)) {
                synchronized (PENDING) { PENDING.remove(token); }
                return "{\"ok\":false,\"error\":\"命令执行超时\"}";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"ok\":false,\"error\":\"被中断\"}";
        }
        return p.value == null ? "{\"ok\":false,\"error\":\"没有返回\"}" : p.value;
    }

    private static String error(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", msg);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }
}
