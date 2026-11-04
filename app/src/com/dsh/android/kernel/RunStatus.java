package com.dsh.android.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 当前这一轮的运行状态：给悬浮窗这类「不在聊天页里」的地方看。
 *
 * <p>为什么不直接复用 {@link EventBus}：那是插件侧的实例，跟着 PluginManager 走，
 * 而悬浮窗服务要活到应用退出之后，不能依赖任何一个 Activity 或插件对象。
 * 这里全部是静态字段，谁都能读，改完通知订阅者。
 */
public final class RunStatus {

    public interface Listener { void onStatusChanged(); }

    public static final String IDLE = "idle";
    /** 模型正在思考/生成 */
    public static final String THINKING = "thinking";
    /** 正在执行工具 */
    public static final String TOOL = "tool";
    /** 用户暂停了 */
    public static final String PAUSED = "paused";

    private static volatile String phase = IDLE;
    private static volatile String detail = "";
    private static volatile int toolCount = 0;
    private static volatile long startedAt = 0L;

    /** 最近几次工具调用，给悬浮窗列出来；满了从头挤掉。 */
    private static final int HISTORY = 6;
    private static final List<String> recent = new ArrayList<String>();

    private static final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<Listener>();

    private RunStatus() {}

    public static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyAll_() {
        for (Listener l : listeners) {
            try {
                l.onStatusChanged();
            } catch (Throwable ignored) {}
        }
    }

    public static String phase() { return phase; }
    public static String detail() { return detail; }
    public static int toolCount() { return toolCount; }
    public static long startedAt() { return startedAt; }

    /** 最近一次工具调用的描述，最新在前。 */
    public static synchronized List<String> recent() {
        return new ArrayList<String>(recent);
    }

    public static void begin(String ph, String det) {
        phase = ph;
        detail = det == null ? "" : det;
        if (startedAt == 0L) startedAt = System.currentTimeMillis();
        notifyAll_();
    }

    /** 工具开始：phase 切到 TOOL，记一笔。 */
    public static synchronized void toolStart(String name) {
        phase = TOOL;
        detail = name;
        toolCount++;
        recent.add(0, name);
        while (recent.size() > HISTORY) recent.remove(recent.size() - 1);
        notifyAll_();
    }

    public static void toolEnd(String name, boolean ok) {
        // 结果只影响列表里那一条，不动 phase：下一步模型接着说话会自己覆盖
        notifyAll_();
    }

    public static void pause() {
        phase = PAUSED;
        detail = "";
        notifyAll_();
    }

    public static synchronized void reset() {
        phase = IDLE;
        detail = "";
        toolCount = 0;
        startedAt = 0L;
        recent.clear();
        notifyAll_();
    }
}
