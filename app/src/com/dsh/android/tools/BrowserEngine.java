package com.dsh.android.tools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
        try {
            com.dsh.android.ui.BrowserActivity.note(page.url);
        } catch (Throwable ignored) {}
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

    /**
     * 在已加载的页面上执行一个动作（点击、输入、查找等）。
     *
     * <p>参考 OpenMinis 的浏览器动作集合，但只保留 WebView 能稳妥做到的几项：
     * 点击、输入、查找元素、读取页面信息、读取 Cookie。
     */
    public static String act(Context ctx, String url, String action, JSONObject params) {
        String js;
        if ("click".equals(action)) {
            String sel = params.optString("selector", "");
            if (sel.length() == 0) return "{\"ok\":false,\"error\":\"需要 selector\"}";
            js = "var el=document.querySelector(" + JSONObject.quote(sel) + ");"
                    + "if(!el) return {found:false};"
                    + "el.scrollIntoView({block:'center'});el.click();"
                    + "return {found:true,tag:el.tagName,text:(el.innerText||'').slice(0,120)};";
        } else if ("type".equals(action)) {
            String sel = params.optString("selector", "");
            String text = params.optString("text", "");
            if (sel.length() == 0) return "{\"ok\":false,\"error\":\"需要 selector\"}";
            js = "var el=document.querySelector(" + JSONObject.quote(sel) + ");"
                    + "if(!el) return {found:false};"
                    + "el.focus();el.value=" + JSONObject.quote(text) + ";"
                    + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                    + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "return {found:true};";
        } else if ("find".equals(action)) {
            String sel = params.optString("selector", "");
            js = "var list=document.querySelectorAll(" + JSONObject.quote(sel) + ");"
                    + "return {count:list.length,items:Array.prototype.slice.call(list,0,30).map(function(e){"
                    + "return {tag:e.tagName,text:(e.innerText||e.value||'').slice(0,120)}})};";
        } else if ("info".equals(action)) {
            js = "return {title:document.title,url:location.href,"
                    + "links:document.querySelectorAll('a').length,"
                    + "textLength:(document.body?document.body.innerText.length:0)};";
        } else {
            return "{\"ok\":false,\"error\":\"不支持的动作: " + action + "\"}";
        }
        return eval(ctx, url, js);
    }

    /** 读取当前页面的 Cookie（用于需要登录态的后续请求）。 */
    public static String cookies(Context ctx, String url) {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String v = cm.getCookie(url);
            JSONObject o = new JSONObject();
            o.put("ok", v != null && v.length() > 0);
            o.put("cookie", v == null ? "" : v);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"读取 Cookie 失败\"}";
        }
    }

    /**
     * 在引擎里渲染页面并截图，保存成 PNG。
     *
     * <p>截的是内置浏览器的渲染结果，不是用户手机屏幕——所以不需要录屏权限，
     * 也不会碰到用户的其它应用。
     */
    public static String screenshot(final Context ctx, final String url) {
        if (!ensure(ctx)) return "{\"ok\":false,\"error\":\"无法创建内置浏览器\"}";
        final CountDownLatch loaded = new CountDownLatch(1);
        final CountDownLatch saved = new CountDownLatch(1);
        final String[] result = new String[1];

        UI.post(new Runnable() {
            public void run() {
                WebView w;
                synchronized (BrowserEngine.class) { w = web; }
                if (w == null) {
                    loaded.countDown();
                    saved.countDown();
                    return;
                }
                w.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(final WebView view, String u) {
                        loaded.countDown();
                        UI.postDelayed(new Runnable() {
                            public void run() {
                                try {
                                    int width = 1080;
                                    int height = 1920;
                                    view.measure(
                                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                                    view.layout(0, 0, width, height);
                                    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                                            width, height, android.graphics.Bitmap.Config.ARGB_8888);
                                    view.draw(new android.graphics.Canvas(bmp));

                                    java.io.File dir = new java.io.File(ctx.getFilesDir(), "shots");
                                    if (!dir.exists()) dir.mkdirs();
                                    java.io.File f = new java.io.File(dir,
                                            "shot-" + System.currentTimeMillis() + ".png");
                                    java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out);
                                    out.flush();
                                    out.close();
                                    bmp.recycle();

                                    JSONObject o = new JSONObject();
                                    o.put("ok", true);
                                    o.put("path", f.getAbsolutePath());
                                    o.put("width", width);
                                    o.put("height", height);
                                    o.put("bytes", f.length());
                                    result[0] = o.toString();
                                } catch (Throwable t) {
                                    result[0] = "{\"ok\":false,\"error\":\"截图失败\"}";
                                } finally {
                                    saved.countDown();
                                }
                            }
                        }, SETTLE_MS);
                    }
                });
                try {
                    w.loadUrl(url);
                } catch (Throwable t) {
                    loaded.countDown();
                    saved.countDown();
                }
            }
        });

        try {
            if (!loaded.await(PAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return "{\"ok\":false,\"error\":\"加载超时\"}";
            if (!saved.await(15000, TimeUnit.MILLISECONDS)) return "{\"ok\":false,\"error\":\"截图超时\"}";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"ok\":false,\"error\":\"被中断\"}";
        }
        return result[0] == null ? "{\"ok\":false,\"error\":\"没有结果\"}" : result[0];
    }

    /**
     * 抓一帧当前渲染画面（给可视化的「电脑」面板用）。
     *
     * <p>在主线程把 WebView 画到 Bitmap 上；没有加载过页面时返回 null。
     */
    public static android.graphics.Bitmap capture(final int width, final int height) {
        final android.graphics.Bitmap[] out = new android.graphics.Bitmap[1];
        final CountDownLatch done = new CountDownLatch(1);
        UI.post(new Runnable() {
            public void run() {
                try {
                    WebView w;
                    synchronized (BrowserEngine.class) { w = web; }
                    if (w != null) {
                        w.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                        w.layout(0, 0, width, height);
                        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                                width, height, android.graphics.Bitmap.Config.ARGB_8888);
                        w.draw(new android.graphics.Canvas(bmp));
                        out[0] = bmp;
                    }
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            done.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    /** 当前页地址（引擎最近加载的那个）。 */
    public static String currentUrl() {
        try {
            return com.dsh.android.ui.BrowserActivity.last();
        } catch (Throwable t) {
            return "";
        }
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
