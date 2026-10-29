package com.dsh.android.market;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 插件市场客户端。
 *
 * <p>数据源是 dshmarket.com 背后的开源索引 {@code awesome-dsh-plugin.com/index.json}：
 * 里面是 3900 多个**已发布到 npm** 的 DSH 插件，正好是能一键安装的那部分
 * （纯 GitHub 仓库、需要跑 install.sh 的插件不在其中，那些也没法无感安装）。
 *
 * <p>索引 3MB 左右，不是每次启动都拉：结果缓存到内存与磁盘，刷新由用户显式触发。
 */
public final class MarketClient {

    private static final String INDEX_URL = "https://awesome-dsh-plugin.com/index.json";
    private static final int TIMEOUT_MS = 20000;
    private static final int MAX_BYTES = 12 * 1024 * 1024;

    /** 列表里的一项（只保留界面要用的字段，避免把 3MB 的原始对象全留着）。 */
    public static final class Item {
        public String npm;          // @scope/name
        public String title;        // owner/repo
        public String descZh = "";
        public String descEn = "";
        public String category = "";
        public String version = "";
        public String homepage = "";
        public boolean installed;

        public String description() {
            return descZh != null && descZh.length() > 0 ? descZh : descEn;
        }
    }

    /** 抓取并解析。阻塞调用，放后台线程。 */
    public static List<Item> fetch() throws Exception {
        String raw = download(INDEX_URL);
        return parse(raw);
    }

    public static List<Item> parse(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONArray arr = root.optJSONArray("plugins");
        List<Item> out = new ArrayList<Item>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String npm = o.optString("name", "");
            if (npm.length() == 0) continue;

            Item it = new Item();
            it.npm = npm;
            it.title = o.optString("title", npm);
            JSONObject d = o.optJSONObject("description");
            if (d != null) {
                it.descZh = d.optString("zh", "");
                it.descEn = d.optString("en", "");
            }
            JSONObject md = o.optJSONObject("metadata");
            if (md != null) it.category = md.optString("category", "");
            it.homepage = o.optString("homepage", "");

            JSONArray vers = o.optJSONArray("versions");
            if (vers != null && vers.length() > 0) {
                JSONObject v = vers.optJSONObject(0);
                if (v != null) it.version = v.optString("version", "");
            }
            out.add(it);
        }
        return out;
    }

    // ------------------------------------------------------------ 安装包解析

    /** 从 npm registry 查 tarball 直链。 */
    public static String tarballOf(String npm, String version) throws Exception {
        String enc = npm.startsWith("@") ? npm.replace("/", "%2f").replace("@", "%40") : npm;
        String url = "https://registry.npmjs.org/" + enc
                + (version != null && version.length() > 0 ? "/" + version : "");
        String json = download(url);
        JSONObject o = new JSONObject(json);
        JSONObject dist = o.optJSONObject("dist");
        if (dist == null) {
            // 传了 version 时返回的是版本对象，dist 就在这一层
            throw new Exception("这个包没有可下载的 tarball");
        }
        String t = dist.optString("tarball", "");
        if (t.length() == 0) throw new Exception("tarball 地址为空");
        return t;
    }

    /** 下载二进制（限制大小，避免被超大包拖死）。 */
    public static byte[] downloadBytes(String url, int maxBytes) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "DShAndroid/1.0");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("下载失败 HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) throw new Exception("文件超过 " + (maxBytes / 1024 / 1024) + "MB");
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toByteArray();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------ 内部

    private static String download(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "DShAndroid/1.0");
            c.setRequestProperty("Accept-Encoding", "gzip");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);

            InputStream in = c.getInputStream();
            String enc = c.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip")) {
                in = new GZIPInputStream(in, 8192);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new Exception("响应过大");
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
