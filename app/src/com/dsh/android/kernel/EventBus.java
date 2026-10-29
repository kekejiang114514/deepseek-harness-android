package com.dsh.android.kernel;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * 微内核事件总线（对标 Cordis 的事件驱动模型）。
 * 插件用 on() 订阅事件，用 emit() 发布事件。
 * 为保证线程安全，事件分发在调用线程同步执行（UI 层自行 post 到主线程）。
 */
public final class EventBus {
    public interface Listener {
        void onEvent(String name, Object data);
    }

    private final Map<String, List<Listener>> listeners = new HashMap<String, List<Listener>>();

    public void on(String name, Listener l) {
        synchronized (listeners) {
            List<Listener> list = listeners.get(name);
            if (list == null) {
                list = new ArrayList<Listener>();
                listeners.put(name, list);
            }
            list.add(l);
        }
    }

    public void off(String name, Listener l) {
        synchronized (listeners) {
            List<Listener> list = listeners.get(name);
            if (list != null) {
                list.remove(l);
            }
        }
    }

    public void emit(String name, Object data) {
        List<Listener> snapshot;
        synchronized (listeners) {
            List<Listener> list = listeners.get(name);
            if (list == null || list.isEmpty()) return;
            snapshot = new ArrayList<Listener>(list);
        }
        for (Listener l : snapshot) {
            try {
                l.onEvent(name, data);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
}
