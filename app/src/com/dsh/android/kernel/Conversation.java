package com.dsh.android.kernel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话消息树：每条消息带上 {@code id} 和 {@code parent}，会话记住当前 {@code leaf}。
 *
 * <p>这样就能像 Git 一样从一个旧消息继续往下写，同时保留原来的分支——
 * 老版本存的是扁平数组，没有 id/parent，载入时 {@link #migrate} 会把它们
 * 串成一条直线，行为与以前完全一致。
 *
 * <p>所有方法都不依赖 android，便于单元测试。
 */
public final class Conversation {

    /** 迁移：给缺少 id/parent 的旧数据补上，使其成为一条线性链。 */
    public static void migrate(JSONArray all) {
        if (all == null) return;
        String prev = null;
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "");
            if (id.length() == 0) {
                id = newId(i);
                try { m.put("id", id); } catch (Exception ignored) {}
            }
            // 只在缺 parent 时补：已有 parent 的节点是分支，不能改
            if (!m.has("parent")) {
                try { m.put("parent", prev == null ? JSONObject.NULL : prev); } catch (Exception ignored) {}
            }
            prev = id;
        }
    }

    private static String newId(int seq) {
        return "m" + Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString((int) (Math.random() * 0xffff), 36)
                + Integer.toString(seq, 36);
    }

    /** 给新消息分配一个 id。 */
    public static String freshId() {
        return "m" + Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString((int) (Math.random() * 0xffff), 36);
    }

    /** 按 id 找下标，找不到返回 -1。 */
    public static int indexOf(JSONArray all, String id) {
        if (all == null || id == null) return -1;
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m != null && id.equals(m.optString("id"))) return i;
        }
        return -1;
    }

    /** 最后一条消息的 id（旧会话没有 leaf 时用它）。 */
    public static String lastId(JSONArray all) {
        if (all == null) return null;
        for (int i = all.length() - 1; i >= 0; i--) {
            JSONObject m = all.optJSONObject(i);
            if (m != null) return m.optString("id", null);
        }
        return null;
    }

    /**
     * 从 leaf 往上走到根，再反转，得到当前要显示 / 要发给模型的那条路径。
     *
     * <p>leaf 为空或找不到时退回全部消息（兼容尚未迁移的数据）。
     */
    public static JSONArray path(JSONArray all, String leaf) {
        JSONArray out = new JSONArray();
        if (all == null) return out;
        if (leaf == null || leaf.length() == 0) {
            for (int i = 0; i < all.length(); i++) {
                JSONObject m = all.optJSONObject(i);
                if (m != null) out.put(m);
            }
            return out;
        }
        Map<String, JSONObject> byId = new HashMap<String, JSONObject>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m != null) byId.put(m.optString("id"), m);
        }
        if (!byId.containsKey(leaf)) return path(all, null);
        List<JSONObject> chain = new ArrayList<JSONObject>();
        String cur = leaf;
        int guard = 0;
        while (cur != null && byId.containsKey(cur) && guard++ < 100000) {
            JSONObject m = byId.get(cur);
            chain.add(m);
            String p = m.optString("parent", "");
            cur = p.length() == 0 ? null : p;
        }
        for (int i = chain.size() - 1; i >= 0; i--) out.put(chain.get(i));
        return out;
    }

    /** 某节点的所有直接子节点（按写入顺序）。 */
    public static List<String> childrenOf(JSONArray all, String parentId) {
        List<String> out = new ArrayList<String>();
        if (all == null) return out;
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m == null) continue;
            String p = m.optString("parent", "");
            boolean same = parentId == null || parentId.length() == 0
                    ? p.length() == 0
                    : parentId.equals(p);
            if (same) out.add(m.optString("id"));
        }
        return out;
    }

    /** 与某节点同级的所有节点（同一父节点下的兄弟，含自己）。 */
    public static List<String> siblingsOf(JSONArray all, String nodeId) {
        int idx = indexOf(all, nodeId);
        if (idx < 0) return new ArrayList<String>();
        JSONObject m = all.optJSONObject(idx);
        String p = m.optString("parent", "");
        return childrenOf(all, p);
    }

    /** 沿着「最后一个子节点」一路往下，得到该分支当前末端。 */
    public static String lastDescendant(JSONArray all, String nodeId) {
        String cur = nodeId;
        int guard = 0;
        while (cur != null && guard++ < 100000) {
            List<String> kids = childrenOf(all, cur);
            if (kids.isEmpty()) return cur;
            cur = kids.get(kids.size() - 1);
        }
        return nodeId;
    }

    /** 该节点是否是某个分支点（有多个子节点）。 */
    public static boolean isFork(JSONArray all, String nodeId) {
        return childrenOf(all, nodeId).size() > 1;
    }

    /**
     * 把 Agent 循环新写进路径的消息合并回完整历史。
     *
     * <p>循环只拿到当前路径，追加的消息没有 id/parent；这里给它们补上并接到
     * {@code parentId} 后面，其余分支原样保留。
     *
     * @param all      完整历史（会被追加）
     * @param path     循环跑完后的路径
     * @param from     路径里从第几条开始是本次新增的
     * @param parentId 本次新增消息的父节点（发起时所在的 leaf）
     * @return 最后一个新节点的 id，作为新的 leaf
     */
    public static String mergeAppended(JSONArray all, JSONArray path, int from, String parentId) {
        String parent = parentId;
        if (path == null) return parent;
        for (int i = Math.max(0, from); i < path.length(); i++) {
            JSONObject m = path.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "");
            if (id.length() == 0) {
                id = freshId();
                try { m.put("id", id); } catch (Exception ignored) {}
            }
            if (!m.has("parent") || m.optString("parent", "").length() == 0) {
                try { m.put("parent", parent == null ? JSONObject.NULL : parent); } catch (Exception ignored) {}
            }
            if (indexOf(all, id) < 0) all.put(m);
            parent = id;
        }
        return parent;
    }

    /** 统计分支点数量（界面用：提示这个会话有多个分支）。 */
    public static int forkCount(JSONArray all) {
        int n = 0;
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m == null) continue;
            String p = m.optString("parent", "");
            Integer c = counts.get(p);
            counts.put(p, c == null ? 1 : c + 1);
        }
        for (Integer c : counts.values()) if (c != null && c > 1) n++;
        return n;
    }
}
