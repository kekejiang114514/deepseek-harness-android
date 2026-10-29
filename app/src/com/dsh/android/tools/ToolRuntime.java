package com.dsh.android.tools;

import android.content.Context;
import android.content.SharedPreferences;

import com.dsh.android.a11y.A11yService;
import com.dsh.android.kernel.C;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具运行时：注册表 + 执行器 + JSON Schema 生成。
 *
 * 作用域规则（文件类工具）：
 *   - 受限模式（workspace）：只能访问 App 私有工作区目录。
 *   - 全局模式（device）：可访问整个设备文件系统（受系统权限限制）。
 * 无障碍类工具受「无障碍开关」总控，随时可关闭。
 */
public final class ToolRuntime {
    private final Map<String, ToolDef> registry = new HashMap<String, ToolDef>();

    /**
     * 对外（模型 API）的工具名映射。
     * OpenAI/DeepSeek 要求 function name 匹配 ^[a-zA-Z0-9_-]+$，
     * 点号非法会直接 400，所以 a11y.tap -> a11y__tap。
     */
    private final Map<String, String> publicNames = new HashMap<String, String>();
    private final Map<String, List<String>> pluginTools = new HashMap<String, List<String>>();
    private final Context ctx;
    private final SharedPreferences prefs;

    public ToolRuntime(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE);
        registerAskTool();
    }

    // ---------- 作用域 ----------

    public File workspaceDir() {
        File f = new File(ctx.getFilesDir(), "workspace");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public boolean isDeviceScope() {
        return C.SCOPE_DEVICE.equals(prefs.getString(C.KEY_FS_SCOPE, C.SCOPE_WORKSPACE));
    }

    public void setDeviceScope(boolean device) {
        prefs.edit().putString(C.KEY_FS_SCOPE,
                device ? C.SCOPE_DEVICE : C.SCOPE_WORKSPACE).apply();
    }

    /** 全局模式需要的存储权限（Android 6+ 运行时授权）。 */
    private boolean hasStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        return ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public boolean isA11yEnabled() {
        return prefs.getBoolean(C.KEY_A11Y_ENABLED, true);
    }

    public void setA11yEnabled(boolean on) {
        prefs.edit().putBoolean(C.KEY_A11Y_ENABLED, on).apply();
    }

    /** 把用户给的路径解析成实际 File，并强制作用域边界。 */
    private File resolve(String p) throws Exception {
        if (p == null) p = "";
        p = p.trim();
        if (p.length() == 0) p = ".";
        if (p.startsWith("~/")) p = p.substring(2);

        if (isDeviceScope()) {
            File f = p.startsWith("/") ? new File(p) : new File("/sdcard", p);
            // 全局模式下若没拿到存储权限，明确报错而不是静默返回空目录
            String ap = f.getAbsolutePath();
            if ((ap.startsWith("/sdcard") || ap.startsWith("/storage"))
                    && !hasStoragePermission()) {
                throw new SecurityException(
                    "全局模式尚未获得存储权限。请到设置 → 工具能力 → 文件作用域重新选择「全局」并允许授权。");
            }
            return f;
        }
        File base = workspaceDir();
        File f = p.startsWith("/") ? new File(p) : new File(base, p);
        String basePath = base.getCanonicalPath();
        String path = f.getCanonicalPath();
        if (!path.equals(basePath) && !path.startsWith(basePath + File.separator)) {
            throw new SecurityException(
                "受限模式：路径超出工作区（" + basePath + "）。如需访问整个设备，请切换到「全局」模式。");
        }
        return f;
    }

    // ---------- 注册 ----------

    /**
     * 注册内置工具：让 AI 能给用户出选择题。
     *
     * <p>工具线程会阻塞等用户点选，所以 Agent 会「停下来等你」，
     * 你选完它拿到结果再继续往下做。
     */
    private void registerAskTool() {
        JSONObject params = new JSONObject();
        try {
            params.put("type", "object");
            JSONObject props = new JSONObject();

            JSONObject q = new JSONObject();
            q.put("type", "string");
            q.put("description", "要问用户的问题，一句话说清（可带简短背景）");
            props.put("question", q);

            JSONObject opt = new JSONObject();
            opt.put("type", "array");
            opt.put("description", "选项列表，2-6 个。每个选项写清做法和主要代价，让用户一眼能选");
            JSONObject item = new JSONObject();
            item.put("type", "string");
            opt.put("items", item);
            props.put("options", opt);

            JSONObject multi = new JSONObject();
            multi.put("type", "boolean");
            multi.put("description", "是否允许多选，默认 false（单选）");
            props.put("multi", multi);

            params.put("properties", props);
            params.put("required", new org.json.JSONArray().put("question").put("options"));
        } catch (Exception ignored) {}

        registry.put("ui.ask", new ToolDef("ui.ask",
                "向用户提出选择题。当有多个可行方案、需要用户拍板，或缺少只有用户才知道的信息时使用。"
                        + "调用前先用一两句话说明情况（可选配一两行简述各方案的差别），"
                        + "这段文字会显示在选项卡片上方；然后立刻调用本工具。"
                        + "调用后不要在本轮再输出别的结论，等用户点完拿到 selected 再继续。",
                params, new ToolExecutor() {
            public String execute(JSONObject args) throws Exception {
                String question = args.optString("question", "");
                org.json.JSONArray arr = args.optJSONArray("options");
                if (arr == null || arr.length() == 0) {
                    return "{\"ok\":false,\"error\":\"options 不能为空\"}";
                }
                String[] options = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) options[i] = arr.optString(i, "");

                boolean multi = args.optBoolean("multi", false);
                String picked = com.dsh.android.ui.AskBridge.ask(question, options, multi, 300);

                JSONObject out = new JSONObject();
                if (picked == null) {
                    out.put("ok", false);
                    out.put("error", "用户未在时限内选择（可能点了停止或超时）");
                    out.put("hint", "不要重复提问。若无必要，请按最稳妥的方案继续，并说明你的假设。");
                } else if (picked.length() == 0) {
                    out.put("ok", false);
                    out.put("error", "用户取消或跳过");
                } else {
                    out.put("ok", true);
                    out.put("selected", picked);
                }
                return out.toString();
            }
        }));
    }

    /**
     * 注册插件声明的工具（走内置执行器，靠 type 字段分发）。
     * 返回冲突信息：非空表示整批都没注册，调用方应把这个插件标记为加载失败。
     * 之所以整批回滚，是因为半个插件在线上比没有插件更难排查。
     */
    public List<String> registerFromPlugin(String pluginId, JSONArray declarations) {
        List<JSONObject> valid = new ArrayList<JSONObject>();
        List<String> conflicts = new ArrayList<String>();
        for (int i = 0; i < declarations.length(); i++) {
            JSONObject d = declarations.optJSONObject(i);
            if (d == null) continue;
            String name = d.optString("name", "");
            if (name.length() == 0) {
                conflicts.add("第 " + (i + 1) + " 个工具缺少 name");
                continue;
            }
            String owner = ownerOf(name);
            if (owner != null && !owner.equals(pluginId)) {
                conflicts.add(name + "（已被 " + owner + " 占用）");
                continue;
            }
            valid.add(d);
        }
        if (!conflicts.isEmpty()) return conflicts;

        List<String> names = new ArrayList<String>();
        for (int i = 0; i < valid.size(); i++) {
            JSONObject d = valid.get(i);
            String name = d.optString("name");
            String type = d.optString("type", "a11y");
            String desc = d.optString("description", name);
            JSONObject params = d.optJSONObject("parameters");
            if (params == null) params = new JSONObject();

            registry.put(name, new ToolDef(name, desc, params, buildExecutor(type)));
            names.add(name);
        }
        pluginTools.put(pluginId, names);
        return conflicts;
    }

    /** 直接注册一个已有执行器的工具（声明式 HTTP 插件用）。返回 false 表示重名被拒。 */
    public boolean registerTool(String pluginId, String name, String description,
                                JSONObject params, ToolExecutor exec) {
        String owner = ownerOf(name);
        if (owner != null && !owner.equals(pluginId)) return false;
        registry.put(name, new ToolDef(name, description,
                params == null ? new JSONObject() : params, exec));
        List<String> names = pluginTools.get(pluginId);
        if (names == null) {
            names = new ArrayList<String>();
            pluginTools.put(pluginId, names);
        }
        if (!names.contains(name)) names.add(name);
        return true;
    }

    /** 这个工具名当前属于哪个插件（内置工具返回 null）。 */
    public String ownerOf(String toolName) {
        for (Map.Entry<String, List<String>> e : pluginTools.entrySet()) {
            if (e.getValue().contains(toolName)) return e.getKey();
        }
        return null;
    }

    /** 已注册的工具总数（界面展示用）。 */
    public int toolCount() {
        return registry.size();
    }

    public void unregisterPlugin(String pluginId) {
        List<String> names = pluginTools.remove(pluginId);
        if (names != null) {
            for (String n : names) registry.remove(n);
        }
    }

    private ToolExecutor buildExecutor(final String type) {
        return new ToolExecutor() {
            public String execute(JSONObject args) throws Exception {
                if (type.startsWith("fs.")) return execFs(type, args);
                if (type.startsWith("a11y")) return execA11y(type, args);
                if ("http".equals(type)) return execHttp(args);
                if ("notify".equals(type)) return execNotify(args);
                if ("app.open".equals(type)) return execOpenApp(args);
                if (type.startsWith("web.")) return execWeb(type, args);
                return "{\"ok\":false,\"error\":\"unhandled type " + type + "\"}";
            }
        };
    }

    // ---------- 文件工具 ----------

    private String execFs(String type, JSONObject a) {
        try {
            if ("fs.list".equals(type)) {
                File dir = resolve(a.optString("path", "."));
                if (!dir.exists()) return err("路径不存在: " + dir.getAbsolutePath());
                if (!dir.isDirectory()) return err("不是目录: " + dir.getAbsolutePath());
                File[] fs = dir.listFiles();
                JSONArray arr = new JSONArray();
                if (fs != null) {
                    for (int i = 0; i < fs.length && i < 500; i++) {
                        File f = fs[i];
                        JSONObject o = new JSONObject();
                        o.put("name", f.getName());
                        o.put("path", f.getAbsolutePath());
                        o.put("dir", f.isDirectory());
                        o.put("size", f.length());
                        arr.put(o);
                    }
                }
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("base", dir.getAbsolutePath());
                out.put("scope", isDeviceScope() ? "device" : "workspace");
                out.put("entries", arr);
                return out.toString();
            }

            if ("fs.read".equals(type)) {
                File f = resolve(a.optString("path", ""));
                if (!f.exists() || !f.isFile()) return err("文件不存在: " + f.getAbsolutePath());
                long max = 256 * 1024;
                if (f.length() > max) return err("文件过大(" + f.length() + "字节)，上限 256KB");
                byte[] data = readFile(f);
                String text = new String(data, "UTF-8");
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("path", f.getAbsolutePath());
                out.put("size", f.length());
                out.put("content", text);
                return out.toString();
            }

            if ("fs.write".equals(type)) {
                File f = resolve(a.optString("path", ""));
                String content = a.optString("content", "");
                boolean append = a.optBoolean("append", false);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(f, append);
                fos.write(content.getBytes("UTF-8"));
                fos.close();
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("path", f.getAbsolutePath());
                out.put("bytes", content.getBytes("UTF-8").length);
                return out.toString();
            }

            if ("fs.mkdir".equals(type)) {
                File f = resolve(a.optString("path", ""));
                boolean ok = f.exists() || f.mkdirs();
                return "{\"ok\":" + ok + ",\"path\":\"" + escJson(f.getAbsolutePath()) + "\"}";
            }

            if ("fs.delete".equals(type)) {
                File f = resolve(a.optString("path", ""));
                if (!f.exists()) return err("不存在: " + f.getAbsolutePath());
                boolean ok = deleteRec(f);
                return "{\"ok\":" + ok + ",\"path\":\"" + escJson(f.getAbsolutePath()) + "\"}";
            }

            if ("fs.info".equals(type)) {
                File f = resolve(a.optString("path", "."));
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("path", f.getAbsolutePath());
                out.put("exists", f.exists());
                out.put("dir", f.isDirectory());
                out.put("size", f.length());
                out.put("readable", f.canRead());
                out.put("writable", f.canWrite());
                out.put("scope", isDeviceScope() ? "device" : "workspace");
                return out.toString();
            }

            return err("未知文件操作: " + type);
        } catch (SecurityException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }

    private static boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        return f.delete();
    }

    private static byte[] readFile(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
        fis.close();
        return bos.toByteArray();
    }

    // ---------- 无障碍工具 ----------

    private String execA11y(String type, JSONObject a) {
        if (!isA11yEnabled()) {
            return "{\"ok\":false,\"error\":\"无障碍操作已在设置中关闭\"}";
        }
        if (!A11yService.isReady()) {
            // 无障碍不能由应用自行授权：借这次调用弹出一次引导（内部有冷却），
            // 同时把原因写清楚，避免模型以为是自己参数错了反复重试。
            com.dsh.android.ui.A11yGuide.requestPrompt();
            try {
                JSONObject o = new JSONObject();
                o.put("ok", false);
                o.put("need_a11y", true);
                o.put("error", "无障碍服务还没开启，屏幕操作暂时不可用");
                o.put("hint", "这不是调用参数的问题。Android 规定无障碍服务必须由用户在系统设置里手动打开，"
                        + "应用无法自行申请。请让用户在设置页点「去开启」，或手动进入"
                        + " 设置 → 无障碍 → 已下载的服务 → DShAndroid 无障碍 打开开关。"
                        + "在用户开启之前不要反复重试屏幕工具，改用其它方式回答。");
                return o.toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"need_a11y\":true,\"error\":\"无障碍服务未开启\"}";
            }
        }
        A11yService svc = A11yService.get();

        if (C.A11Y_DUMP.equals(type)) return "{\"ok\":true,\"pkg\":\"" + escJson(svc.foregroundPackage()) + "\",\"nodes\":" + svc.dump() + "}";
        if (C.A11Y_CLICK.equals(type)) {
            int idx = a.optInt("index", -1);
            if (idx < 0) return err("need index");
            return svc.clickByIndex(idx);
        }
        if ("a11y.longclick".equals(type)) {
            int idx = a.optInt("index", -1);
            if (idx < 0) return err("need index");
            return svc.longClickByIndex(idx);
        }
        if ("a11y.doubleclick".equals(type)) {
            int idx = a.optInt("index", -1);
            if (idx < 0) return err("need index");
            return svc.doubleClickByIndex(idx);
        }
        if ("a11y.tap".equals(type)) {
            return svc.tap(a.optInt("x", 0), a.optInt("y", 0));
        }
        if ("a11y.longpress".equals(type)) {
            return svc.longPress(a.optInt("x", 0), a.optInt("y", 0));
        }
        if ("a11y.swipe".equals(type)) {
            return svc.swipe(a.optInt("x1", 0), a.optInt("y1", 0),
                             a.optInt("x2", 0), a.optInt("y2", 0), a.optInt("duration", 300));
        }
        if ("a11y.pinch".equals(type)) {
            return svc.pinch((float) a.optDouble("scale", 1.0), a.optInt("duration", 400));
        }
        if ("a11y.findtext".equals(type)) {
            String q = a.optString("text", "");
            int idx = svc.findTextIndex(q);
            if (idx < 0) return "{\"ok\":false,\"error\":\"未找到包含该文字的节点\",\"query\":\"" + escJson(q) + "\"}";
            return "{\"ok\":true,\"index\":" + idx + "}";
        }
        if (C.A11Y_SCROLL.equals(type)) {
            int idx = a.optInt("index", -1);
            String dir = a.optString("direction", "forward");
            if (idx >= 0) return svc.scrollNode(idx, dir);
            return svc.scroll(dir);
        }
        if (C.A11Y_TEXT.equals(type)) {
            int idx = a.optInt("index", -1);
            if (idx < 0) return err("need index");
            return svc.setText(idx, a.optString("text", ""));
        }
        if ("a11y.cleartext".equals(type)) {
            int idx = a.optInt("index", -1);
            if (idx < 0) return err("need index");
            return svc.clearText(idx);
        }
        if ("a11y.enter".equals(type)) {
            int idx = a.optInt("index", -1);
            if (idx < 0) return err("need index");
            return svc.pressEnter(idx);
        }
        if (C.A11Y_BACK.equals(type)) return svc.performGlobalBack() ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"back failed\"}";
        if (C.A11Y_HOME.equals(type)) return svc.performGlobalHome() ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"home failed\"}";
        if ("a11y.recents".equals(type)) return svc.performGlobalRecents() ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"recents failed\"}";
        if ("a11y.notifications".equals(type)) return svc.performGlobalNotifications() ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"notifications failed\"}";
        return err("未知无障碍动作: " + type);
    }

    // ---------- 其它 ----------

    private String execOpenApp(JSONObject a) {
        String pkg = a.optString("package", "");
        if (pkg.length() == 0) return err("need package");
        try {
            android.content.Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return err("未安装或无法启动: " + pkg);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return "{\"ok\":true}";
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }

    // ---------- 联网工具 ----------

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private String execWeb(String type, JSONObject a) throws Exception {
        if ("web.search".equals(type)) {
            String q = a.optString("query", "").trim();
            if (q.length() == 0) return err("query 不能为空");
            int count = a.optInt("count", 6);
            if (count < 1) count = 1;
            if (count > 10) count = 10;

            String html = httpGetText("https://cn.bing.com/search?q="
                    + java.net.URLEncoder.encode(q, "UTF-8") + "&count=" + count);
            if (html == null) return err("搜索请求失败，请检查网络");

            JSONArray results = new JSONArray();
            String[] blocks = html.split("<li class=\"b_algo\"");
            for (int i = 1; i < blocks.length && results.length() < count; i++) {
                String b = blocks[i];
                String url = "";
                String title = "";
                Matcher mh = Pattern.compile(
                        "<h2[^>]*>(.*?)</h2>", Pattern.DOTALL).matcher(b);
                if (mh.find()) title = stripTags(mh.group(1));

                Matcher ma = Pattern.compile(
                        "<div class=\"b_algoheader\">\\s*<a[^>]*href=\"([^\"]+)\"",
                        Pattern.DOTALL).matcher(b);
                if (ma.find()) {
                    url = unescapeHtml(ma.group(1));
                } else {
                    Matcher m2 = Pattern.compile(
                            "<a[^>]*href=\"(https?://[^\"]+)\"[^>]*><h2",
                            Pattern.DOTALL).matcher(b);
                    if (m2.find()) url = unescapeHtml(m2.group(1));
                }

                String snippet = "";
                Matcher ms = Pattern.compile(
                        "<p class=\"b_lineclamp[0-9]*\"[^>]*>(.*?)</p>",
                        Pattern.DOTALL).matcher(b);
                if (ms.find()) snippet = stripTags(ms.group(1));

                if (title.length() == 0) continue;
                JSONObject o = new JSONObject();
                o.put("title", title);
                if (url.length() > 0) o.put("url", url);
                o.put("snippet", snippet);
                results.put(o);
            }

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("query", q);
            out.put("engine", "bing");
            out.put("count", results.length());
            out.put("results", results);
            return out.toString();
        }

        if ("web.fetch".equals(type)) {
            String url = a.optString("url", "").trim();
            if (url.length() == 0) return err("url 不能为空");
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return err("url 必须以 http:// 或 https:// 开头");
            }
            String html = httpGetText(url);
            if (html == null) return err("抓取失败，请检查网址或网络");
            String text = htmlToText(html);
            if (text.length() > 8000) text = text.substring(0, 8000) + "\n…（已截断）";
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("url", url);
            out.put("text", text);
            return out.toString();
        }

        return err("unknown web tool: " + type);
    }

    /** 发起 GET 请求并整段读取为文本。 */
    private String httpGetText(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String charset = conn.getContentType();
            boolean utf8 = charset == null || charset.toLowerCase().indexOf("utf-8") >= 0;
            return new String(bos.toByteArray(), utf8 ? "UTF-8" : "GBK");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String stripTags(String s) {
        if (s == null) return "";
        String t = s.replaceAll("<[^>]+>", "");
        return unescapeHtml(t).replaceAll("\\s+", " ").trim();
    }

    /** HTML 正文提取：去掉脚本样式，保留段落换行。 */
    private static String htmlToText(String html) {
        String s = html;
        s = s.replaceAll("(?is)<script.*?</script>", " ");
        s = s.replaceAll("(?is)<style.*?</style>", " ");
        s = s.replaceAll("(?is)<head.*?</head>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|li|h[1-6]|tr)>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = unescapeHtml(s);
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll("\\n\\s*\\n+", "\n");
        return s.trim();
    }

    private static String unescapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&nbsp;", " ").replace("&#183;", "·")
                .replace("&middot;", "·").replace("&hellip;", "…");
    }

    private String execHttp(JSONObject a) throws Exception {
        String url = a.optString("url");
        String method = a.optString("method", "GET");
        String body = a.optString("body", null);
        if (url == null || url.length() == 0) return err("need url");

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        if (body != null && !"GET".equalsIgnoreCase(method)) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String resp = is == null ? "" : readAll(is);
        JSONObject out = new JSONObject();
        out.put("ok", code < 400);
        out.put("status", code);
        out.put("body", resp);
        return out.toString();
    }

    private String execNotify(JSONObject a) {
        String title = a.optString("title", "DShAndroid");
        String text = a.optString("text", "");
        try {
            android.app.Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                b = new android.app.Notification.Builder(ctx, "dsh")
                        .setSmallIcon(android.R.drawable.ic_dialog_info);
            } else {
                b = new android.app.Notification.Builder(ctx)
                        .setSmallIcon(android.R.drawable.ic_dialog_info);
            }
            b.setContentTitle(title).setContentText(text).setAutoCancel(true);
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify((int) (System.currentTimeMillis() & 0xffff), b.build());
            return "{\"ok\":true}";
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }

    private static String err(String m) {
        return "{\"ok\":false,\"error\":\"" + escJson(m) + "\"}";
    }

    static String escJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 生成所有已注册工具的 JSON Schema（OpenAI tools 格式）。 */
    public JSONArray schemas() throws Exception {
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, ToolDef> e : registry.entrySet()) {
            ToolDef d = e.getValue();
            JSONObject o = new JSONObject();
            o.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", safeName(d.name));
            fn.put("description", d.description);
            fn.put("parameters", d.parameters);
            o.put("function", fn);
            arr.put(o);
        }
        return arr;
    }

    /** 执行一次工具调用。 */
    /** 把 a11y.tap 转成 API 允许的 a11y__tap。 */
    private String safeName(String internal) {
        String pub = internal.replace(".", "__");
        publicNames.put(pub, internal);
        return pub;
    }

    /** 把模型返回的 a11y__tap 还原成内部名。 */
    private String internalName(String fromModel) {
        String internal = publicNames.get(fromModel);
        if (internal != null) return internal;
        // 映射表未命中时做一次反推（兼容重启后直接调用）
        return fromModel.replace("__", ".");
    }

    public String call(String name, String argsJson) {
        name = internalName(name);
        ToolDef def = registry.get(name);
        if (def == null) return err("unknown tool: " + name);
        try {
            JSONObject args = argsJson == null || argsJson.length() == 0
                    ? new JSONObject() : new JSONObject(argsJson);
            return def.executor.execute(args);
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }
}
