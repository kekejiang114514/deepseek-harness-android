package com.dsh.android.kernel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * API 配置档案：一组「地址 + 密钥 + 模型 + 强度」。
 *
 * <p>同一台设备上常要来回切几套：官方接口、中转站、本地服务、公司内网。
 * 以前只有一份地址和密钥，换一次就得重新输入。这里把它们收成档案列表，
 * 当前使用的那份同步写回旧的单份设置键，其余代码不必改。
 *
 * <p>纯 JVM 类，不依赖 Android，逻辑可单测。
 */
public final class Profiles {

    /** 一份配置。 */
    public static final class Profile {
        public String name = "";
        public String baseUrl = "";
        public String apiKey = "";
        public String model = "";
        public String level = "medium";

        public Profile() {}

        public Profile(String name, String baseUrl, String apiKey, String model, String level) {
            this.name = name == null ? "" : name;
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.model = model == null ? "" : model;
            this.level = level == null ? "medium" : level;
        }

        public Profile copy() {
            return new Profile(name, baseUrl, apiKey, model, level);
        }
    }

    private final List<Profile> items = new ArrayList<Profile>();
    private int active = 0;

    public List<Profile> items() { return items; }

    public int activeIndex() { return active; }

    public Profile active() {
        if (items.isEmpty()) return null;
        if (active < 0 || active >= items.size()) active = 0;
        return items.get(active);
    }

    public void setActive(int i) {
        if (i >= 0 && i < items.size()) active = i;
    }

    public Profile get(int i) {
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    // ------------------------------------------------------------ 增删改

    /** 新增一份并设为当前；返回它的下标。 */
    public int add(Profile p) {
        items.add(p == null ? new Profile() : p);
        active = items.size() - 1;
        return active;
    }

    /** 删除；当前项被删时改用相邻的一份。 */
    public void remove(int i) {
        if (i < 0 || i >= items.size()) return;
        items.remove(i);
        if (items.isEmpty()) {
            active = 0;
            return;
        }
        if (active > i) active--;
        if (active >= items.size()) active = items.size() - 1;
    }

    /** 覆盖某一项。 */
    public void update(int i, Profile p) {
        if (i < 0 || i >= items.size() || p == null) return;
        items.set(i, p.copy());
    }

    /** 名字是否重复（空名字不算重复）。 */
    public boolean nameTaken(String name, int exceptIndex) {
        if (name == null || name.trim().length() == 0) return false;
        String n = name.trim();
        for (int i = 0; i < items.size(); i++) {
            if (i == exceptIndex) continue;
            if (n.equals(items.get(i).name)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 序列化

    public JSONObject toJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("active", active);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < items.size(); i++) {
                Profile p = items.get(i);
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                o.put("baseUrl", p.baseUrl);
                o.put("apiKey", p.apiKey);
                o.put("model", p.model);
                o.put("level", p.level);
                arr.put(o);
            }
            root.put("profiles", arr);
        } catch (Exception ignored) {}
        return root;
    }

    /** 从 JSON 还原；数据损坏时返回空集合，由调用方兜底。 */
    public static Profiles fromJson(String raw) {
        Profiles out = new Profiles();
        if (raw == null || raw.trim().length() == 0) return out;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("profiles");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    out.items.add(new Profile(
                            o.optString("name", ""),
                            o.optString("baseUrl", ""),
                            o.optString("apiKey", ""),
                            o.optString("model", ""),
                            o.optString("level", "medium")));
                }
            }
            out.active = Math.max(0, Math.min(root.optInt("active", 0), Math.max(0, out.items.size() - 1)));
        } catch (Exception ignored) {
            return new Profiles();
        }
        return out;
    }

    /**
     * 旧版只有一份地址/密钥：迁移成第一份档案。
     *
     * @return 是否发生了迁移（原本没有任何档案）
     */
    public boolean migrateLegacy(String baseUrl, String apiKey, String model, String level,
                                 String defaultBaseUrl) {
        if (!items.isEmpty()) return false;
        String base = baseUrl == null || baseUrl.trim().length() == 0
                ? (defaultBaseUrl == null ? "" : defaultBaseUrl) : baseUrl;
        items.add(new Profile("默认", base,
                apiKey == null ? "" : apiKey,
                model == null ? "" : model,
                level == null ? "medium" : level));
        active = 0;
        return true;
    }
}
