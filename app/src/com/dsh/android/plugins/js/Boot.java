package com.dsh.android.plugins.js;

/**
 * 在 WebView 里运行的引导脚本：模拟 Cordis 插件宿主的最小面。
 *
 * <p>只实现 DSH 插件最常见的那条路径：
 * {@code export function apply(ctx) { ctx.tools.register(...) }}。
 * 事件订阅、子插件、自定义 service 一律显式抛错，而不是静默失效——
 * 让用户在设置页看到「这个插件用了不支持的能力」，比让它悄悄什么都不做强。
 *
 * <p>脚本本身用 {@code new Function} 包住插件源码，避免污染全局；
 * 异步工具直接靠 Promise，WebView 原生支持 async/await。
 */
public final class Boot {

    private Boot() {}

    /** 引导脚本在 assets 里，避免把两百行 JS 塞进 Java 字符串。 */
    private static volatile String cached;

    public static String load(android.content.Context ctx) {
        if (cached != null) return cached;
        try {
            java.io.InputStream in = ctx.getAssets().open("dsh_boot.js");
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                cached = new String(bos.toByteArray(), "UTF-8");
                return cached;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "(function(){window.__load=function(){return JSON.stringify({ok:false," +
                    "error:'引导脚本缺失'});};})();";
        }
    }
}
