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

    /** 会话元数据（列表项）。 */
    public static final class SessionMeta {
        public final String id;
        public final String title;
        public final long updatedAt;
        public SessionMeta(String id, String title, long updatedAt) {
            this.id = id; this.title = title; this.updatedAt = updatedAt;
        }
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

    /** 加载某会话历史。 */
    public JSONArray loadHistory(String id) {
        String s = sp.getString("hist_" + id, "[]");
        try {
            return new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public JSONArray loadCurrentHistory() {
        return loadHistory(currentId());
    }

    /** 保存某会话历史（并更新标题为第一条用户消息前 20 字）。 */
    public void saveHistory(String id, JSONArray history) {
        sp.edit().putString("hist_" + id, history.toString()).apply();
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
                    m.optLong("updatedAt", 0)));
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
}
