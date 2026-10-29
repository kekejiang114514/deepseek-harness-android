package com.dsh.android.kernel;

/**
 * 版本号解析与比较。
 *
 * <p>单独成一个类，只依赖 JDK，方便纯 JVM 单测——不牵扯 Android。
 */
public final class Version {

    private Version() {}

    /** "v0.10" / "0.10.2" / "release-1.2" → "0.10" / "0.10.2" / "1.2" */
    public static String normalize(String tag) {
        if (tag == null) return "";
        String s = tag.trim();
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        s = s.substring(i);
        // 去掉 -beta / +build 之类的尾巴
        int j = 0;
        while (j < s.length()) {
            char ch = s.charAt(j);
            if (!Character.isDigit(ch) && ch != '.') break;
            j++;
        }
        s = s.substring(0, j);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * 比较两个版本号，逐段做数值比较。
     *
     * <p>必须按数字比而不是按字符串比：否则 "0.10" 会被判成小于 "0.9"，
     * 每发十个版本就会漏掉一次更新提示。缺的段按 0 补齐，"1.0" == "1.0.0"。
     */
    public static int compare(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        String[] pa = na.length() == 0 ? new String[0] : na.split("\\.");
        String[] pb = nb.length() == 0 ? new String[0] : nb.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? num(pa[i]) : 0;
            int vb = i < pb.length ? num(pb[i]) : 0;
            if (va != vb) return va < vb ? -1 : 1;
        }
        return 0;
    }

    /** 远端是否比本地新（远端解析不出数字时一律视为不新）。 */
    public static boolean isNewer(String remote, String local) {
        if (normalize(remote).length() == 0) return false;
        return compare(remote, local) > 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
