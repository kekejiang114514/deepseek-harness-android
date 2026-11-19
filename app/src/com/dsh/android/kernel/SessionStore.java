package com.dsh.android.kernel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * 多会话存储：每个会话有独立 id + 标题 + 历史。
 * 用 SharedPreferences 存一个 JSON 索引 + 每个会话的完整历史。
 */
public final class SessionStore {
    private final SharedPreferences sp;

    public SessionStore(Context ctx) {
        this.sp = ctx.getSharedPreferences("dsh_sessions", Context.MODE_PRIVATE);
    }


    /** 新建会话，返回 id。 */
    public String newSession(String title) {
        String id = Long.toHexString(System.currentTimeMillis()) + Integer.toHexString((int)(Math.random()*0xffff));
        JSONArray index = loadIndex();
        JSONObject meta = new JSONObject();
        try {
            meta.put("id", id);
            meta.put("title", title == null || title.length() == 0 ? "新对话" : title);
            meta.put("updatedAt", System.currentTimeMillis());
            index.put(meta);
            sp.edit().putString("index", index.toString()).apply();
            // 空历史
            sp.edit().putString("hist_" + id, "[]").apply();
            sp.edit().putString("current", id).apply();
        } catch (Exception ignored) {}
        return id;
    }

    /** 当前会话 id（不存在则新建）。 */
    public String currentId() {
        String cur = sp.getString("current", "");
        if (cur.length() == 0) {
            return newSession("新对话");
        }
        return cur;
    }

    /** 切换当前会话。 */
    public void setCurrent(String id) {
        sp.edit().putString("current", id).apply();
    }

    /** 加载某会话的完整历史（所有分支）。旧数据会在这里被迁移成带 id/parent 的线性链。 */
    public JSONArray loadHistory(String id) {
        String s = sp.getString("hist_" + id, "[]");
        JSONArray arr;
        try {
            arr = new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
        Conversation.migrate(arr);
        return arr;
    }

    /** 当前分支末端；没有记录时取最后一条。 */
    public String leafOf(String id, JSONArray all) {
        String leaf = sp.getString("leaf_" + id, "");
        if (leaf.length() == 0) return Conversation.lastId(all);
        return Conversation.indexOf(all, leaf) < 0 ? Conversation.lastId(all) : leaf;
    }

    /** 切换当前分支末端（分支跳转 / 从某条消息继续写时调用）。 */
    public void setLeaf(String id, String leaf) {
        sp.edit().putString("leaf_" + id, leaf == null ? "" : leaf).apply();
    }

    /** 当前分支的消息序列（界面显示与发给模型都用它）。 */
    public JSONArray loadPath(String id) {
        JSONArray all = loadHistory(id);
        return Conversation.path(all, leafOf(id, all));
    }

    public JSONArray loadCurrentHistory() {
        return loadPath(currentId());
    }

    /** 保存某会话历史（并更新标题为第一条用户消息前 20 字）。 */
    public void saveHistory(String id, JSONArray history) {
        Conversation.migrate(history);
        sp.edit().putString("hist_" + id, history.toString()).apply();
        sp.edit().putString("leaf_" + id, Conversation.lastId(history)).apply();
        // 更新标题 + 时间
        String title = deriveTitle(history);
        updateMeta(id, title, System.currentTimeMillis());
    }

    private String deriveTitle(JSONArray history) {
        for (int i = 0; i < history.length(); i++) {
            JSONObject m = history.optJSONObject(i);
            if (m != null && "user".equals(m.optString("role"))) {
                String c = m.optString("content", "");
                String line = c.length() > 20 ? c.substring(0, 20) + "…" : c;
                return line.length() == 0 ? "新对话" : line;
            }
        }
        return "新对话";
    }

    private void updateMeta(String id, String title, long updatedAt) {
        JSONArray index = loadIndex();
        try {
            for (int i = 0; i < index.length(); i++) {
                JSONObject m = index.optJSONObject(i);
                if (m != null && id.equals(m.optString("id"))) {
                    m.put("title", title);
                    m.put("updatedAt", updatedAt);
                    break;
                }
            }
            sp.edit().putString("index", index.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** 删除会话。 */
    /** 重命名会话（自动标题时用）。 */
    public void renameSession(String id, String title) {
        if (id == null || title == null || title.length() == 0) return;
        JSONArray idx = loadIndex();
        try {
            for (int i = 0; i < idx.length(); i++) {
                JSONObject o = idx.getJSONObject(i);
                if (id.equals(o.optString("id"))) {
                    o.put("title", title);
                    o.put("updatedAt", System.currentTimeMillis());
                    break;
                }
            }
            sp.edit().putString("index", idx.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void deleteSession(String id) {
        sp.edit().remove("hist_" + id).apply();
        JSONArray index = loadIndex();
        JSONArray next = new JSONArray();
        try {
            for (int i = 0; i < index.length(); i++) {
                JSONObject m = index.optJSONObject(i);
                if (m != null && !id.equals(m.optString("id"))) {
                    next.put(m);
                }
            }
            sp.edit().putString("index", next.toString()).apply();
        } catch (Exception ignored) {}
        // 如果删的是当前，切换到第一个或新建
        if (id.equals(sp.getString("current", ""))) {
            if (next.length() > 0) {
                try {
                    sp.edit().putString("current", next.optJSONObject(0).optString("id")).apply();
                } catch (Exception ignored) {}
            } else {
                newSession("新对话");
            }
        }
    }

    /** 会话列表（按更新时间倒序）。 */
    public List<SessionMeta> listSessions() {
        JSONArray index = loadIndex();
        List<SessionMeta> list = new ArrayList<SessionMeta>();
        for (int i = 0; i < index.length(); i++) {
            JSONObject m = index.optJSONObject(i);
            if (m == null) continue;
            list.add(new SessionMeta(m.optString("id"), m.optString("title", "新对话"),
                    m.optLong("updatedAt", 0), m.optString("group", "")));
        }
        // 倒序
        java.util.Collections.sort(list, new java.util.Comparator<SessionMeta>() {
            public int compare(SessionMeta a, SessionMeta b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return list;
    }

    private JSONArray loadIndex() {
        String s = sp.getString("index", "[]");
        try {
            return new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // ---------------------------------------------------------------- 分组

    /** 所有分组名（按创建顺序）。 */
    public List<String> listGroups() {
        List<String> out = new ArrayList<String>();
        String s = sp.getString("groups", "[]");
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                String g = arr.optString(i, "");
                if (g.length() > 0) out.add(g);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 新建分组（重名则忽略）。 */
    public void addGroup(String name) {
        if (name == null) return;
        name = name.trim();
        if (name.length() == 0) return;
        List<String> cur = listGroups();
        if (cur.contains(name)) return;
        cur.add(name);
        saveGroups(cur);
    }

    /** 删除分组：组内会话回到「未分组」，不会被删。 */
    public void removeGroup(String name) {
        if (name == null) return;
        List<String> cur = listGroups();
        cur.remove(name);
        saveGroups(cur);
        JSONArray idx = loadIndex();
        try {
            for (int i = 0; i < idx.length(); i++) {
                JSONObject m = idx.getJSONObject(i);
                if (name.equals(m.optString("group", ""))) m.put("group", "");
            }
            sp.edit().putString("index", idx.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** 把会话移到某个分组；传空串表示移出分组。 */
    public void setGroup(String id, String group) {
        JSONArray idx = loadIndex();
        try {
            for (int i = 0; i < idx.length(); i++) {
                JSONObject m = idx.getJSONObject(i);
                if (id.equals(m.optString("id"))) {
                    m.put("group", group == null ? "" : group);
                    break;
                }
            }
            sp.edit().putString("index", idx.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void saveGroups(List<String> groups) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < groups.size(); i++) arr.put(groups.get(i));
        sp.edit().putString("groups", arr.toString()).apply();
    }

    // ---------------------------------------------------------------- 导入导出

    /** 导出全部会话（含分支、分组）为一个 JSON 包。 */
    public JSONObject exportAll() {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "DShAndroid");
            root.put("schema", 1);
            root.put("exportedAt", System.currentTimeMillis());
            JSONArray gs = new JSONArray();
            List<String> groups = listGroups();
            for (int i = 0; i < groups.size(); i++) gs.put(groups.get(i));
            root.put("groups", gs);
            JSONArray arr = new JSONArray();
            List<SessionMeta> list = listSessions();
            for (int i = 0; i < list.size(); i++) {
                SessionMeta m = list.get(i);
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("title", m.title);
                o.put("group", m.group);
                o.put("updatedAt", m.updatedAt);
                JSONArray all = loadHistory(m.id);
                o.put("leaf", leafOf(m.id, all));
                o.put("messages", all);
                arr.put(o);
            }
            root.put("sessions", arr);
        } catch (Exception ignored) {}
        return root;
    }

    /**
     * 导入一个会话（消息已含 role/content，可含 reasoning）。
     * 每次都新建 id，避免与已有会话撞车。
     *
     * @return 新会话 id
     */
    public String importSession(String title, String group, JSONArray messages) {
        String id = Long.toHexString(System.currentTimeMillis()) + Integer.toHexString((int) (Math.random() * 0xffff));
        JSONArray arr = messages == null ? new JSONArray() : messages;
        Conversation.migrate(arr);
        sp.edit().putString("hist_" + id, arr.toString()).apply();
        sp.edit().putString("leaf_" + id, Conversation.lastId(arr)).apply();
        JSONArray index = loadIndex();
        try {
            JSONObject meta = new JSONObject();
            meta.put("id", id);
            meta.put("title", title == null || title.trim().length() == 0 ? deriveTitle(arr) : title.trim());
            meta.put("updatedAt", System.currentTimeMillis());
            meta.put("group", group == null ? "" : group);
            index.put(meta);
            sp.edit().putString("index", index.toString()).apply();
        } catch (Exception ignored) {}
        return id;
    }

    /** 删除所有会话与分组，并新建一个空会话。 */
    public void deleteAll() {
        JSONArray idx = loadIndex();
        SharedPreferences.Editor e = sp.edit();
        for (int i = 0; i < idx.length(); i++) {
            JSONObject m = idx.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id");
            e.remove("hist_" + id);
            e.remove("leaf_" + id);
        }
        e.remove("index").remove("groups").remove("current").commit();
        newSession("新对话");
    }

    /** 整份写回，但不改 leaf（分支合并后由调用方决定 leaf）。 */
    public void saveAll(String id, JSONArray all) {
        sp.edit().putString("hist_" + id, all.toString()).apply();
        updateMeta(id, deriveTitle(all), System.currentTimeMillis());
    }

    /** 重命名分组：组内会话一起跟着改名。 */
    public void renameGroup(String from, String to) {
        if (from == null || to == null) return;
        String name = to.trim();
        if (name.length() == 0 || from.equals(name)) return;
        List<String> cur = listGroups();
        int at = cur.indexOf(from);
        if (at < 0) return;
        if (cur.contains(name)) return;      // 已有同名组，拒绝
        cur.set(at, name);
        saveGroups(cur);
        JSONArray idx = loadIndex();
        try {
            for (int i = 0; i < idx.length(); i++) {
                JSONObject m = idx.getJSONObject(i);
                if (from.equals(m.optString("group", ""))) m.put("group", name);
            }
            sp.edit().putString("index", idx.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ API 配置档案

    /** 读取配置档案；首次运行时用旧的单份设置迁移出「默认」档案。 */
    public Profiles profiles() {
        Profiles p = Profiles.fromJson(sp.getString("profiles", ""));
        // 档案里的密钥同样是密文，读出来先解密
        for (int i = 0; i < p.items().size(); i++) {
            Profiles.Profile pf = p.items().get(i);
            pf.apiKey = SecureStore.open(pf.apiKey);
        }
        if (p.items().isEmpty()) {
            p.migrateLegacy(sp.getString(com.dsh.android.kernel.C.KEY_BASE_URL, ""),
                    com.dsh.android.kernel.SecureStore.readKey(sp),
                    sp.getString(com.dsh.android.kernel.C.KEY_MODEL, ""),
                    sp.getString(com.dsh.android.kernel.C.KEY_REASONING, "medium"),
                    com.dsh.android.kernel.C.DEFAULT_BASE_URL);
            saveProfiles(p);
        }
        return p;
    }

    /** 保存档案列表，并把当前档案的取值同步回旧的单份设置键。 */
    public void saveProfiles(Profiles p) {
        if (p == null) return;
        Profiles.Profile cur = p.active();
        SharedPreferences.Editor e = sp.edit();

        // 落盘前把每份档案的密钥加密；内存里始终是明文，避免重复加密
        Profiles out = Profiles.fromJson(p.toJson().toString());
        for (int i = 0; i < out.items().size(); i++) {
            Profiles.Profile pf = out.items().get(i);
            pf.apiKey = SecureStore.seal(pf.apiKey);
        }
        e.putString("profiles", out.toJson().toString());
        if (cur != null) {
            e.putString(com.dsh.android.kernel.C.KEY_BASE_URL, cur.baseUrl);
            com.dsh.android.kernel.SecureStore.putKey(e, cur.apiKey);
            e.putString(com.dsh.android.kernel.C.KEY_MODEL, cur.model);
            e.putString(com.dsh.android.kernel.C.KEY_REASONING, cur.level);
        }
        e.apply();
    }
}
