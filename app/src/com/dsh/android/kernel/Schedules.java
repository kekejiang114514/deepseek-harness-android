package com.dsh.android.kernel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务模型：一条任务 = 提示词 + 触发时间 + 重复方式。
 *
 * <p>纯 JVM，不碰 AlarmManager —— 时间计算与序列化在这里，
 * 真正的闹钟与执行在 Android 侧，于是这里可以单独测。
 */
public final class Schedules {

    public static final String ONCE = "once";
    public static final String DAILY = "daily";
    public static final String WEEKDAYS = "weekdays";

    /** 一条任务。 */
    public static final class Task {
        public String id = "";
        public String prompt = "";
        public int hour = 9;
        public int minute = 0;
        public String repeat = DAILY;
        public boolean enabled = true;
        public long lastRun = 0;

        public Task() {}

        public Task(String id, String prompt, int hour, int minute, String repeat) {
            this.id = id;
            this.prompt = prompt;
            this.hour = hour;
            this.minute = minute;
            this.repeat = repeat;
        }

        public String timeText() {
            return String.format(java.util.Locale.US, "%02d:%02d", hour, minute);
        }

        public String repeatText() {
            if (ONCE.equals(repeat)) return "一次";
            if (WEEKDAYS.equals(repeat)) return "工作日";
            return "每天";
        }
    }

    private final List<Task> items = new ArrayList<Task>();

    public List<Task> items() { return items; }

    public Task get(int i) {
        return i < 0 || i >= items.size() ? null : items.get(i);
    }

    public Task byId(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) return items.get(i);
        }
        return null;
    }

    public void add(Task t) {
        if (t == null) return;
        if (t.id == null || t.id.length() == 0) {
            t.id = "t" + System.currentTimeMillis() + Integer.toHexString((int) (Math.random() * 0xffff));
        }
        items.add(t);
    }

    public void remove(int i) {
        if (i >= 0 && i < items.size()) items.remove(i);
    }

    /**
     * 下一次触发时间（毫秒）。
     *
     * @param now 当前时间，便于测试
     * @return 0 表示不再触发（一次性任务且今天已经过了该时刻）
     */
    public static long nextTrigger(Task t, long now) {
        if (t == null) return 0;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(java.util.Calendar.HOUR_OF_DAY, t.hour);
        c.set(java.util.Calendar.MINUTE, t.minute);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) {
            if (ONCE.equals(t.repeat)) return 0;
            c.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        if (WEEKDAYS.equals(t.repeat)) {
            // 跳过周六周日
            for (int i = 0; i < 7; i++) {
                int dow = c.get(java.util.Calendar.DAY_OF_WEEK);
                if (dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY) break;
                c.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
        }
        return c.getTimeInMillis();
    }

    public JSONObject toJson() {
        JSONObject root = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < items.size(); i++) {
                Task t = items.get(i);
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("prompt", t.prompt);
                o.put("hour", t.hour);
                o.put("minute", t.minute);
                o.put("repeat", t.repeat);
                o.put("enabled", t.enabled);
                o.put("lastRun", t.lastRun);
                arr.put(o);
            }
            root.put("tasks", arr);
        } catch (Exception ignored) {}
        return root;
    }

    public static Schedules fromJson(String raw) {
        Schedules out = new Schedules();
        if (raw == null || raw.trim().length() == 0) return out;
        try {
            JSONArray arr = new JSONObject(raw).optJSONArray("tasks");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Task t = new Task();
                t.id = o.optString("id", "");
                t.prompt = o.optString("prompt", "");
                t.hour = Math.max(0, Math.min(23, o.optInt("hour", 9)));
                t.minute = Math.max(0, Math.min(59, o.optInt("minute", 0)));
                t.repeat = o.optString("repeat", DAILY);
                t.enabled = o.optBoolean("enabled", true);
                t.lastRun = o.optLong("lastRun", 0);
                out.items.add(t);
            }
        } catch (Exception ignored) {
            return new Schedules();
        }
        return out;
    }
}
