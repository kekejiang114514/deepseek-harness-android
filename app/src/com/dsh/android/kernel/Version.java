package com.dsh.android.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本号解析与比较。
 *
 * <p>只依赖 JDK，方便纯 JVM 单测——不牵扯 Android。
 *
 * <p>支持三种写法混比："v1.0"、"1.0.0"、"1.0 beta 1"、"v1.0-beta1"、"2.0.1+build7"。
 * 规则：
 * <ul>
 *   <li>数字段按数值比，"0.10" &gt; "0.9"，缺的段补 0（"1.0" == "1.0.0"）</li>
 *   <li>数字相同再比阶段：release &gt; rc &gt; beta &gt; alpha</li>
 *   <li>阶段相同再比阶段序号："1.0 beta 2" &gt; "1.0 beta 1"</li>
 * </ul>
 * 这样发过 beta 之后，正式版仍会被判成「有新版本」，不会漏提示。
 */
public final class Version {

    /** 阶段等级：数字大的更新。 */
    private static final int RANK_ALPHA = 1;
    private static final int RANK_BETA = 2;
    private static final int RANK_RC = 3;
    private static final int RANK_FINAL = 4;

    private static final Pattern TOKEN = Pattern.compile("[a-z]+|[0-9]+");

    private Version() {}

    /** "v0.10" / "0.10.2" / "release-1.2" / "1.0 beta 1" → 数字段部分 */
    public static String normalize(String tag) {
        if (tag == null) return "";
        String s = tag.trim();
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        s = s.substring(i);
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
     * 比较两个版本号：&lt;0 表示 a 旧，0 表示相同，&gt;0 表示 a 新。
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

        // 数字段一致，比预发布阶段
        int ra = rank(a);
        int rb = rank(b);
        if (ra != rb) return ra < rb ? -1 : 1;

        int sa = stageNum(a);
        int sb = stageNum(b);
        if (sa != sb) return sa < sb ? -1 : 1;
        return 0;
    }

    /** 远端是否比本地新（远端解析不出数字时一律视为不新）。 */
    public static boolean isNewer(String remote, String local) {
        if (normalize(remote).length() == 0) return false;
        return compare(remote, local) > 0;
    }

    /** 是否是预发布版本（beta/rc/alpha 之类）。 */
    public static boolean isPrerelease(String tag) {
        return rank(tag) < RANK_FINAL;
    }

    /** 阶段等级：alpha=1, beta=2, rc=3, 正式=4，认不出的按正式处理。 */
    static int rank(String tag) {
        List<String> tokens = tailTokens(tag);
        for (String t : tokens) {
            if ("alpha".equals(t) || "a".equals(t) || "preview".equals(t) || "dev".equals(t)) {
                return RANK_ALPHA;
            }
            if ("beta".equals(t) || "b".equals(t)) return RANK_BETA;
            if ("rc".equals(t) || "pre".equals(t)) return RANK_RC;
        }
        return RANK_FINAL;
    }

    /** 阶段序号："1.0 beta 3" → 3；没有序号按 0。 */
    static int stageNum(String tag) {
        List<String> tokens = tailTokens(tag);
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            boolean isStage = "alpha".equals(t) || "a".equals(t) || "beta".equals(t)
                    || "b".equals(t) || "rc".equals(t) || "pre".equals(t)
                    || "preview".equals(t) || "dev".equals(t);
            if (!isStage) continue;
            if (i + 1 < tokens.size() && isNumber(tokens.get(i + 1))) {
                return num(tokens.get(i + 1));
            }
            return 0;
        }
        return 0;
    }

    /** 数字段之后的所有字母/数字片段，小写。"v1.0-beta1+build7" → [beta, 1, build, 7] */
    private static List<String> tailTokens(String tag) {
        List<String> out = new ArrayList<String>();
        if (tag == null) return out;
        String base = normalize(tag);
        int at = tag.indexOf(base);
        String rest = at < 0 ? tag : tag.substring(at + base.length());
        Matcher m = TOKEN.matcher(rest.toLowerCase());
        while (m.find()) out.add(m.group());
        return out;
    }

    private static boolean isNumber(String s) {
        if (s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
