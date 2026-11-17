package com.dsh.android.kernel;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话列表的分组分桶。
 *
 * <p>界面按「组」逐段渲染抽屉。这个换算以前直接写在界面循环里，
 * 上界写成 {@code <= groups.size()} 就会在最后一轮越界，
 * 表现为一打开抽屉就闪退。抽出来之后可以被测试固定住。
 */
public final class SessionGroups {

    private SessionGroups() {}

    /** 一个分组段：name 为空串表示「未分组」，sessions 为组内会话。 */
    public static final class Bucket {
        public final String name;
        public final List<SessionMeta> sessions;

        Bucket(String name, List<SessionMeta> sessions) {
            this.name = name;
            this.sessions = sessions;
        }

        public boolean isUngrouped() {
            return name == null || name.length() == 0;
        }
    }

    /**
     * 把会话按分组切开，未分组的永远排在最后。
     *
     * <p>空组也会保留：它们就是空文件夹，必须在列表里可见，否则「新建分组」
     * 看上去像是没生效。会话的 group 指向不存在的组时按未分组处理。
     *
     * @param all    会话列表（按更新时间倒序）
     * @param groups 已存在的分组名
     */
    public static List<Bucket> split(List<SessionMeta> all, List<String> groups) {
        List<Bucket> out = new ArrayList<Bucket>();
        List<SessionMeta> list = all == null
                ? new ArrayList<SessionMeta>()
                : all;
        List<String> names = groups == null ? new ArrayList<String>() : groups;

        List<SessionMeta> rest = new ArrayList<SessionMeta>();
        for (int i = 0; i < list.size(); i++) {
            SessionMeta m = list.get(i);
            String g = m == null || m.group == null ? "" : m.group;
            if (g.length() == 0 || !names.contains(g)) {
                rest.add(m);
            }
        }

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            List<SessionMeta> bucket = new ArrayList<SessionMeta>();
            for (int k = 0; k < list.size(); k++) {
                SessionMeta m = list.get(k);
                if (m != null && name.equals(m.group)) bucket.add(m);
            }
            out.add(new Bucket(name, bucket));
        }

        if (!rest.isEmpty()) out.add(new Bucket("", rest));
        return out;
    }

    /** 是否该显示段标题。只有「全是未分组、且只有一个段」时才不必显示。 */
    public static boolean showHeaders(List<Bucket> buckets) {
        if (buckets == null || buckets.size() == 0) return false;
        if (buckets.size() > 1) return true;
        return !buckets.get(0).isUngrouped();
    }

    /** 是否存在任何命名分组（决定要不要显示折叠/文件夹那一套）。 */
    public static boolean hasNamed(List<Bucket> buckets) {
        if (buckets == null) return false;
        for (int i = 0; i < buckets.size(); i++) {
            if (!buckets.get(i).isUngrouped()) return true;
        }
        return false;
    }
}
