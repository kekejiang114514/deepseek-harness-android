package com.dsh.android.ui;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 让 AI 主动向用户提问的桥。
 *
 * <p>工具线程是同步阻塞的（{@link com.dsh.android.tools.ToolExecutor#execute}），
 * 所以这里把「渲染选项卡片」dispatch 到主线程，工具线程用队列等待用户点击。
 * UI 侧实现 {@link Host}，点完调用 {@link Ask#reply(String)} 放行。
 */
public final class AskBridge {

    /** UI 侧实现：在主线程把选择卡渲染出来。 */
    public interface Host {
        void present(Ask ask);
    }

    private static volatile Host host;
    /** 当前挂起的问题（同一时刻只允许一个，避免卡片乱序）。 */
    private static volatile Ask pending;

    public static void install(Host h) { host = h; }

    /** 一次提问。工具线程持有它并等待。 */
    public static final class Ask {
        public final String question;
        public final String[] options;
        public final boolean multi;

        private final ArrayBlockingQueue<String> q = new ArrayBlockingQueue<String>(1);
        private volatile boolean answered = false;
        /** 超时后由 UI 侧把卡片置灰（在主线程执行）。 */
        public volatile Runnable onExpire;
        /** 用户点了停止 / 切走会话时触发，让卡片显示已作废（在主线程执行）。 */
        public volatile Runnable onCancel;

        Ask(String question, String[] options, boolean multi) {
            this.question = question;
            this.options = options;
            this.multi = multi;
        }

        /** 用户点完了。多选用「、」连接。 */
        public void reply(String result) {
            if (answered) return;
            answered = true;
            q.offer(result == null ? "" : result);
        }

        public boolean isAnswered() { return answered; }

        /**
         * 等待用户回答。
         *
         * @return 用户的选择；超时或被取消返回 null
         */
        String await(long seconds) {
            try {
                return q.poll(seconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * 发起一次提问。返回用户的选择，超时/取消返回 null。
     *
     * <p>没有 UI 宿主（例如进程刚起）时立刻返回 null，不阻塞 Agent。
     */
    public static String ask(final String question, final String[] options,
                             final boolean multi, final long timeoutSec) {
        Host h = host;
        if (h == null || options == null || options.length == 0) return null;
        // 同时只允许一个问题挂在界面上，否则卡片与等待方会错位
        if (pending != null && !pending.answered) return null;

        final Ask a = new Ask(question, options, multi);
        pending = a;
        h.present(a);

        String r = a.await(timeoutSec);
        if (pending == a) pending = null;

        if (r == null) {
            // 超时或被打断：告诉 UI 把卡片置灰，避免用户还去点一张没用的卡
            final Runnable hook = a.onExpire;
            if (hook != null) {
                try { hook.run(); } catch (Throwable ignored) {}
            }
            return null;
        }
        return r;
    }

    /** 用户点了「停止」时调用，唤醒还在等待的工具线程（视为取消，不是选择）。 */
    public static void cancelPending() {
        Ask a = pending;
        if (a != null) {
            a.reply("");     // 空串 = 取消，绝不能被当成选中了某个选项
            pending = null;
            // 卡片上的按钮此时已经无效了，不置灰的话用户还会去点
            final Runnable hook = a.onCancel;
            if (hook != null) {
                try { hook.run(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 中断正在等待的选择题（reason 仅用于日志，不当作选项）。 */
    public static void cancelPending(String reason) {
        cancelPending();
    }

    public static boolean hasPending() { return pending != null; }
}
