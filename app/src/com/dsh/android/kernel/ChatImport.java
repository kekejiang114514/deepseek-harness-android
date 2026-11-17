package com.dsh.android.kernel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 外部聊天记录导入。
 *
 * <p>各家导出的结构都不一样，这里按特征逐种识别，识别不了就明确报错，
 * 不做「猜一个能跑就行」的兜底。支持：
 *
 * <ul>
 *   <li>DShAndroid 自己的导出（schema=1）</li>
 *   <li>DeepSeek 网页版导出：{@code chat_sessions + chat_messages}</li>
 *   <li>ChatGPT 导出：{@code [{title, mapping:{id:{message,parent}}}]}</li>
 *   <li>Claude 导出：{@code [{name, chat_messages:[{sender,text}]}]}</li>
 *   <li>通用：{@code {messages:[{role,content}]}} 或 {@code [{role,content}]} 或 JSONL</li>
 * </ul>
 *
 * <p>纯 JVM，无 android 依赖，便于测试。
 */
public final class ChatImport {

    /** 一条导入进来的会话。 */
    public static final class Imported {
        public String title = "";
        public String group = "";
        public JSONArray messages = new JSONArray();
    }

    /** 一次导入的结果。 */
    public static final class Result {
        public String format = "";
        public final List<Imported> sessions = new ArrayList<Imported>();
        /** 跳过或降级处理的地方，非空说明这份文件不完全干净。 */
        public String warning = "";

        public int messageCount() {
            int n = 0;
            for (int i = 0; i < sessions.size(); i++) n += sessions.get(i).messages.length();
            return n;
        }
    }

    /**
     * 从原始字节解析：先看是不是 zip，是就逐个解出里面的 json 再解析。
     *
     * <p>DeepSeek 的「导出数据」给的是一个 zip，里面才有聊天记录 json；
     * ChatGPT / Claude 的导出同样是 zip。所以导入的入口必须是字节，
     * 不能先当成文本读——那样二进制会被当 UTF-8 解成乱码，直接报「认不出」。
     */
    public static Result parseBytes(byte[] data) throws Exception {
        if (data == null || data.length == 0) throw new Exception("文件是空的");
        if (!isZip(data)) {
            return parse(stripBom(new String(data, "UTF-8")));
        }
        Result out = new Result();
        out.format = "ZIP";
        java.util.zip.ZipInputStream zin =
                new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data));
        int handled = 0;
        java.util.zip.ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName() == null ? "" : e.getName();
            String lower = name.toLowerCase(java.util.Locale.US);
            if (e.isDirectory()) continue;
            // 只处理可能的文本条目，图片之类的直接跳过
            if (!(lower.endsWith(".json") || lower.endsWith(".jsonl") || lower.endsWith(".txt"))) continue;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = zin.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 64 * 1024 * 1024) break;
            }
            zin.closeEntry();
            String text = stripBom(new String(bo.toByteArray(), "UTF-8")).trim();
            if (text.length() == 0) continue;
            try {
                Result one = parse(text);
                if (one.sessions.isEmpty()) continue;
                out.sessions.addAll(one.sessions);
                if (one.warning.length() > 0) {
                    out.warning = out.warning.length() == 0
                            ? one.warning : out.warning + "；" + one.warning;
                }
                if (one.format.length() > 0 && !"ZIP".equals(one.format)) {
                    out.format = "ZIP · " + one.format;
                }
                handled++;
            } catch (Exception ignored) {
                // 这个条目不是聊天记录，继续看下一个
            }
        }
        zin.close();
        if (handled == 0 || out.sessions.isEmpty()) {
            throw new Exception("zip 里没有找到聊天记录（找过 .json / .jsonl / .txt 条目）");
        }
        return out;
    }

    private static boolean isZip(byte[] d) {
        return d.length > 4 && d[0] == 'P' && d[1] == 'K'
                && (d[2] == 3 || d[2] == 5 || d[2] == 7);
    }

    private static String stripBom(String s) {
        if (s != null && s.length() > 0 && s.charAt(0) == '\uFEFF') return s.substring(1);
        return s == null ? "" : s;
    }

    public static Result parse(String raw) throws Exception {
        if (raw == null) throw new Exception("文件是空的");
        String text = raw.trim();
        if (text.length() == 0) throw new Exception("文件是空的");

        Result r = new Result();
        if (text.startsWith("{") || text.startsWith("[")) {
            Object root = null;
            try {
                // 注意：JSONObject/JSONArray 的字符串构造器只吃第一个完整值，
                // 多行 JSONL 会「看似解析成功」而把后面的行丢掉。所以下面
                // 一旦结构性识别失败，必须回退成按行解析。
                root = text.startsWith("{") ? new JSONObject(text) : new JSONArray(text);
            } catch (Exception ignored) {
                return parseJsonl(text);
            }
            try {
                if (root instanceof JSONObject) return parseObject((JSONObject) root, r);
                return parseArray((JSONArray) root, r);
            } catch (Exception e) {
                Result line = null;
                try {
                    line = parseJsonl(text);
                } catch (Exception ignored) {}
                if (line != null) return line;
                throw e;
            }
        }
        return parseJsonl(text);
    }

    // ------------------------------------------------------------ 各种结构

    private static Result parseObject(JSONObject o, Result r) throws Exception {
        if (o.has("app") && "DShAndroid".equals(o.optString("app"))) return parseOwn(o, r);
        if (o.has("chat_sessions") || o.has("chat_messages")) return parseDeepSeek(o, r);
        if (o.has("conversations")) {
            JSONArray cs = o.optJSONArray("conversations");
            if (cs != null) return parseClaude(cs, r);
        }
        if (o.has("mapping")) {
            JSONArray one = new JSONArray();
            one.put(o);
            return parseChatGpt(one, r);
        }
        if (o.has("messages")) {
            JSONArray ms = o.optJSONArray("messages");
            if (ms == null) throw new Exception("messages 不是数组");
            Imported im = new Imported();
            im.title = o.optString("title", "");
            im.group = o.optString("group", "");
            im.messages = normalizeSimple(ms);
            if (im.messages.length() == 0) throw new Exception("这份文件里没有可导入的消息");
            r.format = "通用 JSON";
            r.sessions.add(im);
            return r;
        }
        if (o.has("sessions")) {
            JSONArray ss = o.optJSONArray("sessions");
            if (ss != null) return parseArray(ss, r);
        }
        throw new Exception("认不出这种 JSON 结构，没有找到聊天消息");
    }

    private static Result parseArray(JSONArray arr, Result r) throws Exception {
        if (arr.length() == 0) throw new Exception("文件里没有内容");
        JSONObject first = arr.optJSONObject(0);
        if (first == null) throw new Exception("数组里不是对象，认不出格式");

        if (first.has("mapping")) return parseChatGpt(arr, r);
        if (first.has("chat_messages") && (first.has("uuid") || first.has("name")))
            return parseClaude(arr, r);
        if (first.has("chat_session_id") || first.has("message_id"))
            return parseDeepSeekArray(arr, r);
        if (first.has("role") || first.has("sender") || first.has("author"))
            return parsePlain(arr, r);
        if (first.has("messages")) {
            Result out = new Result();
            out.format = "通用 JSON";
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                JSONArray ms = s.optJSONArray("messages");
                if (ms == null) continue;
                Imported im = new Imported();
                im.title = s.optString("title", "");
                im.messages = normalizeSimple(ms);
                if (im.messages.length() > 0) out.sessions.add(im);
            }
            if (out.sessions.isEmpty()) throw new Exception("这份文件里没有可导入的消息");
            return out;
        }
        throw new Exception("认不出这种数组结构");
    }

    /** DShAndroid 自己的导出。 */
    private static Result parseOwn(JSONObject o, Result r) throws Exception {
        JSONArray ss = o.optJSONArray("sessions");
        if (ss == null) throw new Exception("导出包里没有 sessions");
        r.format = "DShAndroid 导出";
        for (int i = 0; i < ss.length(); i++) {
            JSONObject s = ss.optJSONObject(i);
            if (s == null) continue;
            Imported im = new Imported();
            im.title = s.optString("title", "");
            im.group = s.optString("group", "");
            JSONArray ms = s.optJSONArray("messages");
            im.messages = ms == null ? new JSONArray() : ms;
            if (im.messages.length() > 0) r.sessions.add(im);
        }
        if (r.sessions.isEmpty()) throw new Exception("导出包里没有消息");
        return r;
    }

    /**
     * DeepSeek 导出：chat_sessions 是会话表，chat_messages 是消息表，
     * 两边用 chat_session_id 关联；消息里有 thinking 时一并带过来。
     */
    private static Result parseDeepSeek(JSONObject o, Result r) throws Exception {
        JSONArray sessions = o.optJSONArray("chat_sessions");
        JSONArray msgs = o.optJSONArray("chat_messages");
        if (msgs == null) throw new Exception("DeepSeek 导出里没有 chat_messages");
        r.format = "DeepSeek 导出";

        Map<String, List<JSONObject>> bySession = new HashMap<String, List<JSONObject>>();
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            String sid = m.optString("chat_session_id", "");
            List<JSONObject> list = bySession.get(sid);
            if (list == null) {
                list = new ArrayList<JSONObject>();
                bySession.put(sid, list);
            }
            list.add(m);
        }

        List<String> order = new ArrayList<String>();
        Map<String, String> titles = new HashMap<String, String>();
        if (sessions != null) {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject s = sessions.optJSONObject(i);
                if (s == null) continue;
                String id = s.optString("id", "");
                order.add(id);
                titles.put(id, s.optString("title", ""));
            }
        }
        // 会话表里没列出的，也要收下
        for (String sid : bySession.keySet()) if (!order.contains(sid)) order.add(sid);

        for (int i = 0; i < order.size(); i++) {
            String sid = order.get(i);
            List<JSONObject> list = bySession.get(sid);
            if (list == null || list.isEmpty()) continue;
            Collections.sort(list, new Comparator<JSONObject>() {
                public int compare(JSONObject a, JSONObject b) {
                    return Long.compare(timeOf(a), timeOf(b));
                }
            });
            Imported im = new Imported();
            im.title = titles.containsKey(sid) ? titles.get(sid) : "";
            JSONArray out = new JSONArray();
            for (int k = 0; k < list.size(); k++) {
                JSONObject m = list.get(k);
                String role = mapRole(m.optString("role", ""));
                String content = textOf(m.opt("content"));
                if (role.length() == 0 || content.length() == 0) continue;
                JSONObject one = new JSONObject();
                one.put("role", role);
                one.put("content", content);
                String think = m.optString("thinking", "");
                if (think.length() == 0) think = m.optString("reasoning_content", "");
                if (think.length() > 0 && "assistant".equals(role)) one.put("reasoning", think);
                out.put(one);
            }
            if (out.length() > 0) {
                im.messages = out;
                r.sessions.add(im);
            }
        }
        if (r.sessions.isEmpty()) throw new Exception("DeepSeek 导出里没有可用的消息");
        return r;
    }

    private static Result parseDeepSeekArray(JSONArray arr, Result r) throws Exception {
        JSONObject wrap = new JSONObject();
        JSONArray msgs = new JSONArray();
        Map<String, String> titles = new HashMap<String, String>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            msgs.put(m);
            String sid = m.optString("chat_session_id", "");
            if (m.has("title")) titles.put(sid, m.optString("title", ""));
        }
        JSONArray sessions = new JSONArray();
        for (Iterator<String> it = titles.keySet().iterator(); it.hasNext(); ) {
            String sid = it.next();
            JSONObject s = new JSONObject();
            s.put("id", sid);
            s.put("title", titles.get(sid));
            sessions.put(s);
        }
        wrap.put("chat_sessions", sessions);
        wrap.put("chat_messages", msgs);
        return parseDeepSeek(wrap, r);
    }

    /**
     * ChatGPT 导出：mapping 是一棵树，每个节点记着 parent 和 message；
     * 顺着 parent 还原成我们的 id/parent，分支因此原样保留。
     */
    private static Result parseChatGpt(JSONArray arr, Result r) throws Exception {
        r.format = "ChatGPT 导出";
        for (int i = 0; i < arr.length(); i++) {
            JSONObject conv = arr.optJSONObject(i);
            if (conv == null) continue;
            JSONObject mapping = conv.optJSONObject("mapping");
            if (mapping == null) continue;
            Imported im = new Imported();
            im.title = conv.optString("title", "");
            JSONArray out = new JSONArray();
            Map<String, String> idMap = new HashMap<String, String>();
            List<String> keys = new ArrayList<String>();
            for (Iterator<String> it = mapping.keys(); it.hasNext(); ) keys.add(it.next());
            // 先分配 id，再建立 parent 关系（顺序不影响父子）
            for (int k = 0; k < keys.size(); k++) {
                JSONObject node = mapping.optJSONObject(keys.get(k));
                if (node == null) continue;
                JSONObject msg = node.optJSONObject("message");
                if (msg == null) continue;
                String role = roleOfChatGpt(msg);
                String content = contentOfChatGpt(msg);
                if (role.length() == 0 || content.length() == 0) continue;
                idMap.put(keys.get(k), Conversation.freshId());
            }
            for (int k = 0; k < keys.size(); k++) {
                JSONObject node = mapping.optJSONObject(keys.get(k));
                if (node == null) continue;
                JSONObject msg = node.optJSONObject("message");
                if (msg == null) continue;
                String role = roleOfChatGpt(msg);
                String content = contentOfChatGpt(msg);
                if (role.length() == 0 || content.length() == 0) continue;
                String myId = idMap.get(keys.get(k));
                String pKey = node.optString("parent", "");
                String parent = idMap.get(pKey);
                JSONObject one = new JSONObject();
                one.put("id", myId);
                one.put("parent", parent == null ? JSONObject.NULL : parent);
                one.put("role", role);
                one.put("content", content);
                out.put(one);
            }
            if (out.length() > 0) {
                im.messages = out;
                r.sessions.add(im);
            }
        }
        if (r.sessions.isEmpty()) throw new Exception("ChatGPT 导出里没有可用的消息");
        return r;
    }

    /** Claude 导出：conversations[].chat_messages[].sender/text。 */
    private static Result parseClaude(JSONArray arr, Result r) throws Exception {
        r.format = "Claude 导出";
        for (int i = 0; i < arr.length(); i++) {
            JSONObject conv = arr.optJSONObject(i);
            if (conv == null) continue;
            JSONArray ms = conv.optJSONArray("chat_messages");
            if (ms == null) continue;
            Imported im = new Imported();
            im.title = conv.optString("name", conv.optString("title", ""));
            JSONArray out = new JSONArray();
            for (int k = 0; k < ms.length(); k++) {
                JSONObject m = ms.optJSONObject(k);
                if (m == null) continue;
                String role = mapRole(m.optString("sender", m.optString("role", "")));
                String content = m.optString("text", "");
                if (content.length() == 0) content = textOf(m.opt("content"));
                if (role.length() == 0 || content.length() == 0) continue;
                JSONObject one = new JSONObject();
                one.put("role", role);
                one.put("content", content);
                out.put(one);
            }
            if (out.length() > 0) {
                im.messages = out;
                r.sessions.add(im);
            }
        }
        if (r.sessions.isEmpty()) throw new Exception("Claude 导出里没有可用的消息");
        return r;
    }

    /** 一层扁平的消息数组，当作单个会话。 */
    private static Result parsePlain(JSONArray arr, Result r) throws Exception {
        JSONArray ms = normalizeSimple(arr);
        if (ms.length() == 0) throw new Exception("没有找到 role/content 形式的消消息");
        Imported im = new Imported();
        im.messages = ms;
        r.format = "通用消息数组";
        r.sessions.add(im);
        return r;
    }

    private static Result parseJsonl(String text) throws Exception {
        Result r = new Result();
        JSONArray ms = new JSONArray();
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            try {
                JSONObject o = new JSONObject(line);
                String role = mapRole(o.optString("role", o.optString("sender", "")));
                String content = o.optString("content", o.optString("text", ""));
                if (role.length() == 0 || content.length() == 0) continue;
                JSONObject one = new JSONObject();
                one.put("role", role);
                one.put("content", content);
                ms.put(one);
            } catch (Exception ignored) {
                // 单行坏掉不影响其余行
            }
        }
        if (ms.length() == 0) throw new Exception("认不出这个文件：既不是 JSON，也不是 JSONL");
        Imported im = new Imported();
        im.messages = ms;
        r.format = "JSONL";
        r.sessions.add(im);
        return r;
    }

    // ------------------------------------------------------------ 小工具

    private static JSONArray normalizeSimple(JSONArray arr) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String role = mapRole(m.optString("role", m.optString("sender", "")));
            String content = m.optString("content", "");
            if (content.length() == 0) content = textOf(m.opt("content"));
            if (content.length() == 0) content = m.optString("text", "");
            if (role.length() == 0 || content.length() == 0) continue;
            try {
                JSONObject one = new JSONObject();
                one.put("role", role);
                one.put("content", content);
                String think = m.optString("reasoning", "");
                if (think.length() > 0 && "assistant".equals(role)) one.put("reasoning", think);
                out.put(one);
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** 把各家的角色名统一成 user / assistant / system / tool。 */
    public static String mapRole(String role) {
        if (role == null) return "";
        String r = role.trim().toLowerCase();
        if (r.equals("user") || r.equals("human") || r.equals("客户")) return "user";
        if (r.equals("assistant") || r.equals("ai") || r.equals("model")
                || r.equals("bot") || r.equals("chatbot")) return "assistant";
        if (r.equals("system")) return "system";
        if (r.equals("tool") || r.equals("function")) return "tool";
        return "";
    }

    private static String roleOfChatGpt(JSONObject msg) {
        JSONObject author = msg.optJSONObject("author");
        if (author == null) return "";
        String role = author.optString("role", "");
        if ("tool".equals(role) || "function".equals(role)) return "";
        return mapRole(role);
    }

    private static String contentOfChatGpt(JSONObject msg) {
        JSONObject content = msg.optJSONObject("content");
        if (content == null) return "";
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) return content.optString("text", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            Object p = parts.opt(i);
            if (p instanceof String) {
                if (sb.length() > 0) sb.append('\n');
                sb.append((String) p);
            }
        }
        return sb.toString().trim();
    }

    /** content 可能是字符串，也可能是 [{type:text,text:...}] 这种分片数组。 */
    private static String textOf(Object content) {
        if (content == null) return "";
        if (content instanceof String) return ((String) content).trim();
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object p = arr.opt(i);
                if (p instanceof String) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append((String) p);
                } else if (p instanceof JSONObject) {
                    String t = ((JSONObject) p).optString("text", "");
                    if (t.length() > 0) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(t);
                    }
                }
            }
            return sb.toString().trim();
        }
        if (content instanceof JSONObject) return ((JSONObject) content).optString("text", "").trim();
        return "";
    }

    private static long timeOf(JSONObject m) {
        long t = m.optLong("inserted_at", 0);
        if (t == 0) t = m.optLong("created_at", 0);
        if (t == 0) t = m.optLong("timestamp", 0);
        return t;
    }
}
