import com.dsh.android.kernel.Schedules;

/** 定时任务的时间计算与序列化测试（纯 JVM）。 */
public final class SchedulesTest {

    private static int pass = 0, fail = 0;
    private static void ok(boolean c, String w) {
        if (c) { pass++; System.out.println("  PASS  " + w); }
        else { fail++; System.out.println("  FAIL  " + w); }
    }

    /** 构造一个「当地时间的某个时刻」。 */
    private static long at(int y, int mo, int d, int h, int mi) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static void main(String[] a) {
        System.out.println("t1 每天：今天还没到就今天，过了就明天");
        Schedules.Task daily = new Schedules.Task("1", "汇总天气", 9, 0, Schedules.DAILY);
        long now1 = at(2026, 11, 19, 8, 0);
        long n1 = Schedules.nextTrigger(daily, now1);
        ok(n1 == at(2026, 11, 19, 9, 0), "8:00 时下一次是当天 9:00");
        long now2 = at(2026, 11, 19, 10, 0);
        ok(Schedules.nextTrigger(daily, now2) == at(2026, 11, 20, 9, 0), "10:00 时下一次是次日 9:00");

        System.out.println("t2 一次性");
        Schedules.Task once = new Schedules.Task("2", "提醒", 9, 0, Schedules.ONCE);
        ok(Schedules.nextTrigger(once, now1) == at(2026, 11, 19, 9, 0), "未到点照常触发");
        ok(Schedules.nextTrigger(once, now2) == 0, "已过点不再触发");

        System.out.println("t3 工作日跳过周末");
        Schedules.Task wd = new Schedules.Task("3", "站会", 9, 0, Schedules.WEEKDAYS);
        long fridayNight = at(2026, 11, 20, 23, 0);   // 2026-11-20 是周五
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(Schedules.nextTrigger(wd, fridayNight));
        int dow = c.get(java.util.Calendar.DAY_OF_WEEK);
        ok(dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY,
                "下一次落在工作日");

        System.out.println("t4 序列化往返");
        Schedules s = new Schedules();
        s.add(daily);
        s.add(new Schedules.Task("", "无 id 应自动生成", 21, 30, Schedules.WEEKDAYS));
        String raw = s.toJson().toString();
        Schedules back = Schedules.fromJson(raw);
        ok(back.items().size() == 2, "两条都回来了");
        ok(back.items().get(1).hour == 21 && back.items().get(1).minute == 30, "时间保留");
        ok(back.items().get(1).id.length() > 0, "空 id 被补上");
        ok("21:30".equals(back.items().get(1).timeText()), "时间文本格式正确");

        System.out.println("t5 坏数据兜底");
        ok(Schedules.fromJson("{}").items().isEmpty(), "空对象不炸");
        ok(Schedules.fromJson("不是 json").items().isEmpty(), "乱码不炸");
        ok(Schedules.fromJson(null).items().isEmpty(), "null 不炸");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
