package com.dsh.android.kernel;

/**
 * 会话列表里的一条元数据。
 *
 * <p>单独成类是有意为之：界面和分组逻辑都要用它，而真正的存储
 * （{@link SessionStore}）依赖 SharedPreferences，只能在设备上跑。
 * 拆开之后分组这类纯逻辑才能被单元测试覆盖。
 */
public final class SessionMeta {
    public final String id;
    public final String title;
    public final long updatedAt;
    /** 所属分组；空串表示未分组。 */
    public final String group;

    public SessionMeta(String id, String title, long updatedAt, String group) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.updatedAt = updatedAt;
        this.group = group == null ? "" : group;
    }
}
