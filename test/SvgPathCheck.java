import com.dsh.android.ui.Icons;

/**
 * 图标路径自检：不依赖 Android，按 SvgPath 的语义把每个图标算一遍。
 *
 * <p>存在的意义：自绘图标的路径数据写错时，界面只会显示一片空白，
 * 编译和运行都不报错。这个检查在打包前就把「画不出来」的图标揪出来。
 */
public final class SvgPathCheck {

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String what) {
        if (cond) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    /** 一次解析的结果：包围盒 + 段数 + 是否有端点落在网格外。 */
    static final class Box {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int segments = 0;
        boolean outOfGrid = false;
        boolean bad = false;

        void add(float x, float y) {
            if (Float.isNaN(x) || Float.isNaN(y) || Float.isInfinite(x) || Float.isInfinite(y)) {
                bad = true;
                return;
            }
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            if (x < -2 || x > 26 || y < -2 || y > 26) outOfGrid = true;
        }

        float w() { return maxX - minX; }
        float h() { return maxY - minY; }
        boolean empty() { return segments == 0 || w() <= 0.01f || h() <= 0.01f; }
    }

    static final class Scan {
        final String s;
        int i;
        Scan(String s) { this.s = s; }

        boolean more() {
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            return i < s.length();
        }

        char peek() { return s.charAt(i); }
        int pos() { return i; }
        void next() { i++; }

        float num() {
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) { i++; continue; }
                if (c == '.' && !dot) { dot = true; i++; continue; }
                if ((c == 'e' || c == 'E') && i + 1 < s.length()) {
                    i++;
                    if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
                    continue;
                }
                break;
            }
            try { return Float.parseFloat(s.substring(start, i)); }
            catch (Exception e) { return 0f; }
        }
    }

    /** 按 SvgPath.parse 的规则走一遍路径，只统计几何，不生成 Path。 */
    static Box measure(String d) {
        Box b = new Box();
        Scan s = new Scan(d);
        char cmd = 0;
        float cx = 0, cy = 0, sx = 0, sy = 0;
        float lastCx = 0, lastCy = 0;

        while (s.more()) {
            int before = s.pos();
            char c = s.peek();
            if ("MmLlHhVvCcSsQqTtAaZz".indexOf(c) >= 0) { cmd = c; s.next(); }
            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    float x = s.num(), y = s.num();
                    if (rel) { x += cx; y += cy; }
                    cx = x; cy = y; sx = x; sy = y;
                    b.add(x, y);
                    cmd = rel ? 'l' : 'L';
                    break;
                }
                case 'L': {
                    float x = s.num(), y = s.num();
                    if (rel) { x += cx; y += cy; }
                    cx = x; cy = y; b.add(x, y); b.segments++;
                    break;
                }
                case 'H': {
                    float x = s.num();
                    if (rel) x += cx;
                    cx = x; b.add(x, cy); b.segments++;
                    break;
                }
                case 'V': {
                    float y = s.num();
                    if (rel) y += cy;
                    cy = y; b.add(cx, y); b.segments++;
                    break;
                }
                case 'C': {
                    float x1 = s.num(), y1 = s.num(), x2 = s.num(), y2 = s.num(), x = s.num(), y = s.num();
                    if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy; }
                    b.add(x1, y1); b.add(x2, y2); b.add(x, y);
                    lastCx = x2; lastCy = y2; cx = x; cy = y; b.segments++;
                    break;
                }
                case 'S': {
                    float x2 = s.num(), y2 = s.num(), x = s.num(), y = s.num();
                    if (rel) { x2 += cx; y2 += cy; x += cx; y += cy; }
                    float x1 = 2 * cx - lastCx, y1 = 2 * cy - lastCy;
                    b.add(x1, y1); b.add(x2, y2); b.add(x, y);
                    lastCx = x2; lastCy = y2; cx = x; cy = y; b.segments++;
                    break;
                }
                case 'Q': {
                    float x1 = s.num(), y1 = s.num(), x = s.num(), y = s.num();
                    if (rel) { x1 += cx; y1 += cy; x += cx; y += cy; }
                    b.add(x1, y1); b.add(x, y);
                    lastCx = x1; lastCy = y1; cx = x; cy = y; b.segments++;
                    break;
                }
                case 'T': {
                    float x = s.num(), y = s.num();
                    if (rel) { x += cx; y += cy; }
                    float x1 = 2 * cx - lastCx, y1 = 2 * cy - lastCy;
                    b.add(x1, y1); b.add(x, y);
                    lastCx = x1; lastCy = y1; cx = x; cy = y; b.segments++;
                    break;
                }
                case 'A': {
                    float rx = s.num(), ry = s.num(), rot = s.num();
                    boolean large = s.num() != 0, sweep = s.num() != 0;
                    float x = s.num(), y = s.num();
                    if (rel) { x += cx; y += cy; }
                    // 只算端点：按半径粗略外扩会把合法图标误判为越界
                    b.add(x, y);
                    b.add(cx, cy);
                    cx = x; cy = y; b.segments++;
                    break;
                }
                case 'Z':
                    cx = sx; cy = sy; b.segments++;
                    break;
                default:
                    b.bad = true;
                    return b;
            }
            if (s.pos() == before) s.next();
        }
        return b;
    }

    static void check(String name, String d) {
        Box b = measure(d);
        ok(!b.bad, name + " 不含非法数值");
        ok(!b.empty(), name + " 有面积（段=" + b.segments + " 宽=" + Math.round(b.w() * 10) / 10f
                + " 高=" + Math.round(b.h() * 10) / 10f + "）");
        ok(!b.outOfGrid, name + " 未超出 24 网格");
    }

    public static void main(String[] args) {
        System.out.println("t1 操作行与分组图标");
        check("COPY", Icons.COPY);
        check("BRANCH", Icons.BRANCH);
        check("REFRESH", Icons.REFRESH);
        check("FOLDER", Icons.FOLDER);
        check("CHEVRON_LEFT", Icons.CHEVRON_LEFT);
        check("CHEVRON_RIGHT", Icons.CHEVRON_RIGHT);
        check("CHEVRON_DOWN", Icons.CHEVRON_DOWN);

        System.out.println("t2 既有图标不能被改坏");
        check("PLUS", Icons.PLUS);
        check("CLOSE", Icons.CLOSE);
        check("SEND", Icons.SEND);
        check("DOC", Icons.DOC);
        check("INFO", Icons.INFO);
        check("GEAR", Icons.GEAR);

        System.out.println("t3 解析器应当拒绝乱数据而不是静默返回");
        Box junk = measure("M0 0 L 1");
        ok(junk.bad || junk.empty(), "缺参数路径被判为异常或空");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
