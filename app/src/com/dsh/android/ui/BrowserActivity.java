package com.dsh.android.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.dsh.android.kernel.C;

/**
 * 可见的内置浏览器：AI 读过的页面在这里渲染出来，可以自己接着看、接着点。
 *
 * <p>和 {@code BrowserEngine} 共用同一个进程的 Cookie 存储，所以在登录态上是一致的：
 * AI 打开的页面，你在这里能看到同样的状态。
 */
public final class BrowserActivity extends Activity {

    /** 最近一次被 AI 打开的地址，供入口直接续看。 */
    private static volatile String lastUrl = "";

    public static void note(String url) {
        if (url != null && url.startsWith("http")) lastUrl = url;
    }

    public static String last() {
        return lastUrl;
    }

    private WebView web;
    private EditText address;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.app.ActionBar ab = getActionBar();
        if (ab != null) ab.hide();
        Theme.init(this);
        getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(Theme.bg()));
        setContentView(build());

        String start = getIntent() == null ? null : getIntent().getStringExtra("url");
        if (start == null || start.length() == 0) start = lastUrl;
        if (start != null && start.length() > 0) {
            address.setText(start);
            web.loadUrl(start);
        }
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg());

        // 顶栏：‹ 地址 刷新
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Theme.dp(this, 8), Theme.dp(this, 8),
                Theme.dp(this, 8), Theme.dp(this, 8));

        TextView back = new TextView(this);
        back.setText("\u2039");
        back.setTextSize(24);
        back.setTextColor(Theme.text());
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                Theme.dp(this, 36), Theme.dp(this, 36)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(12);
        address.setTextColor(Theme.text());
        address.setHintTextColor(Theme.caption());
        address.setHint("https://");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setPadding(Theme.dp(this, 12), Theme.dp(this, 8),
                Theme.dp(this, 12), Theme.dp(this, 8));
        address.setBackground(Theme.round(Theme.surface2(), 10, this));
        address.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                String u = v.getText().toString().trim();
                if (u.length() > 0) {
                    if (!u.startsWith("http")) u = "https://" + u;
                    web.loadUrl(u);
                }
                return true;
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        alp.setMargins(Theme.dp(this, 6), 0, Theme.dp(this, 6), 0);
        bar.addView(address, alp);

        TextView reload = new TextView(this);
        reload.setText("\u21BB");
        reload.setTextSize(18);
        reload.setTextColor(Theme.text2());
        reload.setGravity(Gravity.CENTER);
        reload.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { web.reload(); }
        });
        bar.addView(reload, new LinearLayout.LayoutParams(
                Theme.dp(this, 36), Theme.dp(this, 36)));
        root.addView(bar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 2)));

        FrameLayout host = new FrameLayout(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " DShAndroid/" + C.VERSION);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                address.setText(url);
                note(url);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
                progress.setProgress(p);
            }
        });
        host.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(host, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    /** 打开浏览器，可选带一个地址。 */
    public static void open(Context c, String url) {
        try {
            Intent i = new Intent(c, BrowserActivity.class);
            if (url != null && url.length() > 0) i.putExtra("url", url);
            c.startActivity(i);
        } catch (Throwable ignored) {}
    }
}
