package com.dsh.android.tools;

/**
 * 读取整机 CPU 与内存占用（给「内置虚拟机」面板底部用）。
 *
 * <p>CPU 需要两次采样做差：{@code /proc/stat} 里是累计刻度，
 * 直接取单次数值没有意义。内存读 {@code /proc/meminfo} 的 MemTotal 与 MemAvailable。
 *
 * <p>都是只读的系统文件，不需要任何权限。
 */
public final class ResMonitor {

    private static long lastTotal = -1;
    private static long lastIdle = -1;

    private ResMonitor() {}

    /** 返回 "CPU 12%  MEM 1.2/5.6 GB"；读不到时返回空串。 */
    public static String sample() {
        int cpu = cpuPercent();
        String mem = memText();
        if (cpu < 0 && mem.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        if (cpu >= 0) sb.append("CPU ").append(cpu).append('%');
        if (mem.length() > 0) {
            if (sb.length() > 0) sb.append("   ");
            sb.append("MEM ").append(mem);
        }
        return sb.toString();
    }

    private static int cpuPercent() {
        String line = firstLine("/proc/stat");
        if (line == null || !line.startsWith("cpu ")) return -1;
        String[] p = line.trim().split("\\s+");
        if (p.length < 5) return -1;
        long total = 0;
        for (int i = 1; i < p.length && i < 9; i++) {
            try {
                total += Long.parseLong(p[i]);
            } catch (Exception ignored) {}
        }
        long idle = 0;
        try {
            idle = Long.parseLong(p[4]);
        } catch (Exception ignored) {}

        if (lastTotal < 0) {
            lastTotal = total;
            lastIdle = idle;
            return -1;   // 第一次没有可比对的基准
        }
        long dt = total - lastTotal;
        long di = idle - lastIdle;
        lastTotal = total;
        lastIdle = idle;
        if (dt <= 0) return -1;
        int pct = (int) (100 * (dt - di) / dt);
        return Math.max(0, Math.min(100, pct));
    }

    private static String memText() {
        String total = null;
        String avail = null;
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/meminfo"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) total = line.substring(9).trim();
                else if (line.startsWith("MemAvailable:")) avail = line.substring(13).trim();
                if (total != null && avail != null) break;
            }
            br.close();
        } catch (Exception e) {
            return "";
        }
        if (total == null) return "";
        long t = kb(total);
        long a = avail == null ? 0 : kb(avail);
        if (t <= 0) return "";
        double usedGb = (t - a) / 1048576.0;
        double totalGb = t / 1048576.0;
        return String.format(java.util.Locale.US, "%.1f/%.1f GB", usedGb, totalGb);
    }

    private static long kb(String s) {
        try {
            int sp = s.indexOf(' ');
            String num = sp > 0 ? s.substring(0, sp) : s;
            return Long.parseLong(num.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String firstLine(String path) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(path));
            String line = br.readLine();
            br.close();
            return line;
        } catch (Exception e) {
            return null;
        }
    }
}
