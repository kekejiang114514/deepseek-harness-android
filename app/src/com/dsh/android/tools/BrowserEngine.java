package com.dsh.android.tools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 内置浏览器：给 AI 用的网页读取能力。
 *
 * <p>与 {@code web.fetch} 的区别：这里是真正的 WebView，会执行页面脚本、携带 Cookie，
 * 单页应用和需要 JS 渲染的站点也能拿到内容；代价是更慢更重。
 *
 * <p>WebView 只能在主线程创建与驱动，因此每个动作都投递到主线程，
 * 工具线程用 {@link CountDownLatch} 等待结果；全程有超时，页面卡住不会拖死对话。
 */
public final class BrowserEngine {

    private static final long PAGE_TIMEOUT_MS = 25000;
    private static final long SETTLE_MS = 900;      // 等脚本渲染
    private static final int MAX_TEXT = 20000;

    private static WebView web;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private BrowserEngine() {}

    public static final class Page {
        public String url = "";
        public String title = "";
        public String text = "";
        public String error;
    }

    /** 打开地址并取正文；阻塞调用，放工具线程。 */
    public static Page read(final Context ctx, String url) {
        final Page page = new Page();
        page.url = url == null ? "" : url;
        if (!ensure(ctx)) {
            page.error = "无法创建内置浏览器";
            return page;
        }

        final CountDownLatch loaded = new CountDownLatch(1);
        final CountDownLatch got = new CountDownLatch(1);
        final String[] holder = new String[1];

        UI.post(new Runnable() {
            public void run() {
                WebView w;
                synchronized (BrowserEngine.class) { w = web; }
                if (w == null) {
                    loaded.countDown();
                    got.countDown();
                    return;
                }
                w.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(final WebView view, String u) {
                        loaded.countDown();
                        UI.postDelayed(new Runnable() {
                            public void run() {
                                view.evaluateJavascript(
                                        "(function(){try{return JSON.stringify({t:document.title||'',"
                                                + "x:(document.body?document.body.innerText:'').slice(0,40000)})}"
                                                + "catch(e){return JSON.stringify({t:'',x:''})}})()",
                                        new ValueCallback<String>() {
                                            public void onReceiveValue(String value) {
                                                holder[0] = value;
                                                got.countDown();
                                            }
                                        });
                            }
                        }, SETTLE_MS);
                    }
                });
                try {
                    w.loadUrl(page.url);
                } catch (Throwable t) {
                    loaded.countDown();
                    got.countDown();
                }
            }
        });

        try {
            if (!loaded.await(PAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                page.error = "页面加载超时";
                return page;
            }
            if (!got.await(8000, TimeUnit.MILLISECONDS)) {
                page.error = "读取页面内容超时";
                return page;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            page.error = "被中断";
            return page;
        }

        try {
            String raw = holder[0];
            if (raw != null && raw.length() > 0 && raw.charAt(0) == '"') {
                // evaluateJavascript 返回的是 JSON 字符串字面量，先解一层
                raw = new org.json.JSONArray("[" + raw + "]").optString(0, "");
            }
            if (raw != null && raw.length() > 0) {
                JSONObject o = new JSONObject(raw);
                page.title = o.optString("t", "");
                String text = o.optString("x", "");
                page.text = text.length() > MAX_TEXT
                        ? text.substring(0, MAX_TEXT) + "\n…（已截断）" : text;
            }
        } catch (Exception ignored) {}

        if (page.text.length() == 0) page.error = "页面没有可读文本";
        return page;
    }

    /** 在指定页面执行一段脚本并返回结果（点按钮、读特定元素）。 */
    public static String eval(final Context ctx, String url, String script) {
        if (!ensure(ctx)) return "{\"ok\":false,\"error\":\"无法创建内置浏览器\"}";
        final CountDownLatch loaded = new CountDownLatch(1);
        final CountDownLatch got = new CountDownLatch(1);
        final String[] result = new String[1];
        final String pageUrl = url;

        UI.post(new Runnable() {
            public void run() {
                WebView w;
                synchronized (BrowserEngine.class) { w = web; }
                if (w == null) {
                    loaded.countDown();
                    got.countDown();
                    return;
                }
                w.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(final WebView view, String u) {
                        loaded.countDown();
                        UI.postDelayed(new Runnable() {
                            public void run() {
                                String js = "(function(){try{return JSON.stringify((function(){"
                                        + script + "})())}"
                                        + "catch(e){return JSON.stringify({error:String(e&&e.message||e)})}})()";
                                view.evaluateJavascript(js, new ValueCallback<String>() {
                                    public void onReceiveValue(String value) {
                                        result[0] = value;
                                        got.countDown();
                                    }
                                });
                            }
                        }, SETTLE_MS);
                    }
                });
                try {
                    w.loadUrl(pageUrl);
                } catch (Throwable t) {
                    loaded.countDown();
                    got.countDown();
                }
            }
        });

        try {
            if (!loaded.await(PAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return "{\"ok\":false,\"error\":\"加载超时\"}";
            if (!got.await(8000, TimeUnit.MILLISECONDS)) return "{\"ok\":false,\"error\":\"执行超时\"}";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"ok\":false,\"error\":\"被中断\"}";
        }
        return result[0] == null ? "{\"ok\":false,\"error\":\"没有返回\"}" : "{\"ok\":true,\"value\":" + result[0] + "}";
    }

    /** 主线程创建 WebView；已存在直接复用。 */
    private static boolean ensure(Context ctx) {
        synchronized (BrowserEngine.class) {
            if (web != null) return true;
        }
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        final Context app = ctx.getApplicationContext();
        UI.post(new Runnable() {
            public void run() {
                try {
                    WebView w = new WebView(app);
                    WebSettings s = w.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setUserAgentString(s.getUserAgentString()
                            + " DShAndroid/" + com.dsh.android.kernel.C.VERSION);
                    synchronized (BrowserEngine.class) { web = w; }
                    ok[0] = true;
                } catch (Throwable t) {
                    ok[0] = false;
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            done.await(6000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }
}
