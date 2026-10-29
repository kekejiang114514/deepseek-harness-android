package com.dsh.android.kernel;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 从 GitHub 的 releases 数组里挑出版本最高的那一条。
 *
 * <p>单独成类的原因：这段逻辑有过一次真实事故——原来读 {@code /releases/latest}，
 * 而 GitHub 会跳过标了 prerelease 的版本，于是发了 1.0-beta1 之后 latest 仍指向 0.9，
 * 已经装了 0.9 的人反而看不到更新。换成读列表自己挑，就必须能单测这段挑选规则。
 * 纯 JSON 运算，不碰 Android，能直接跑在桌面 JDK 上。
 */
public final class ReleasePicker {

    private ReleasePicker() {}

    /**
     * 返回版本最高的那条的下标；没有可用记录时返回 -1。
     * 跳过 draft、跳过没有 tag_name 的条目。
     */
    public static int bestIndex(JSONArray releases) {
        if (releases == null) return -1;
        int best = -1;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject o = releases.optJSONObject(i);
            if (o == null) continue;
            if (o.optBoolean("draft", false)) continue;
            String tag = o.optString("tag_name", "").trim();
            if (tag.length() == 0) continue;
            if (Version.normalize(tag).length() == 0) continue;
            if (best < 0) {
                best = i;
                continue;
            }
            String bestTag = releases.optJSONObject(best).optString("tag_name", "");
            if (Version.compare(tag, bestTag) > 0) best = i;
        }
        return best;
    }

    /** 直接取出选中的那条。 */
    public static JSONObject best(JSONArray releases) {
        int i = bestIndex(releases);
        return i < 0 ? null : releases.optJSONObject(i);
    }
}
