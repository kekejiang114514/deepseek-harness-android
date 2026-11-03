package com.dsh.android.ui;

import android.graphics.Path;

/**
 * 极简 SVG path 解析器：只支持图标里实际会用到的那几种指令。
 *
 * <p>支持 M/L/H/V/C/S/Q/T/Z（大小写即绝对/相对）。不支持 A（圆弧）与 E（科学计数法）以外的写法，
 * 因为 Material 图标的路径数据只用到上面这些。
 */
public final class SvgPath {

    private SvgPath() {}

    public static Path parse(String d) {
        Path path = new Path();
        Scanner s = new Scanner(d);
        char cmd = 0;
        float cx = 0, cy = 0;        // 当前点
        float sx = 0, sy = 0;        // 子路径起点
        float lastCx = 0, lastCy = 0; // 上一个控制点（S/T 用）

        while (s.hasMore()) {
            int before = s.pos();
            char c = s.peek();
            if (isCommand(c)) {
                cmd = c;
                s.next();
            }
            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    float x = s.number(), y = s.number();
                    if (rel) { x += cx; y += cy; }
                    path.moveTo(x, y);
                    cx = x; cy = y; sx = x; sy = y;
                    // 后续隐式坐标按 L 处理
                    cmd = rel ? 'l' : 'L';
                    break;
                }
                case 'L': {
                    float x = s.number(), y = s.number();
                    if (rel) { x += cx; y += cy; }
                    path.lineTo(x, y);
                    cx = x; cy = y;
                    break;
                }
                case 'H': {
                    float x = s.number();
                    if (rel) x += cx;
                    path.lineTo(x, cy);
                    cx = x;
                    break;
                }
                case 'V': {
                    float y = s.number();
                    if (rel) y += cy;
                    path.lineTo(cx, y);
                    cy = y;
                    break;
                }
                case 'C': {
                    float x1 = s.number(), y1 = s.number();
                    float x2 = s.number(), y2 = s.number();
                    float x = s.number(), y = s.number();
                    if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy; }
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lastCx = x2; lastCy = y2;
                    cx = x; cy = y;
                    break;
                }
                case 'S': {
                    float x2 = s.number(), y2 = s.number();
                    float x = s.number(), y = s.number();
                    if (rel) { x2 += cx; y2 += cy; x += cx; y += cy; }
                    float x1 = 2 * cx - lastCx, y1 = 2 * cy - lastCy;
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lastCx = x2; lastCy = y2;
                    cx = x; cy = y;
                    break;
                }
                case 'Q': {
                    float x1 = s.number(), y1 = s.number();
                    float x = s.number(), y = s.number();
                    if (rel) { x1 += cx; y1 += cy; x += cx; y += cy; }
                    path.quadTo(x1, y1, x, y);
                    lastCx = x1; lastCy = y1;
                    cx = x; cy = y;
                    break;
                }
                case 'T': {
                    float x = s.number(), y = s.number();
                    if (rel) { x += cx; y += cy; }
                    float x1 = 2 * cx - lastCx, y1 = 2 * cy - lastCy;
                    path.quadTo(x1, y1, x, y);
                    lastCx = x1; lastCy = y1;
                    cx = x; cy = y;
                    break;
                }
                case 'A': {
                    float rx = s.number(), ry = s.number();
                    float rot = s.number();
                    boolean large = s.number() != 0;
                    boolean sweep = s.number() != 0;
                    float x = s.number(), y = s.number();
                    if (rel) { x += cx; y += cy; }
                    arcTo(path, rx, ry, rot, large, sweep, cx, cy, x, y);
                    cx = x; cy = y;
                    break;
                }
                case 'Z':
                    path.close();
                    cx = sx; cy = sy;
                    break;
                default:
                    return path;   // 遇到不认识的指令就停下，宁可少画也不乱画
            }
            // 兜底：这一轮没吃进任何字符，强制前进，否则渲染线程会死锁在这里
            if (s.pos() == before) s.next();
        }
        return path;
    }

    /** SVG 圆弧转三次贝塞尔（分成不超过 90° 的若干段）。 */
    private static void arcTo(Path p, float rx, float ry, float rot,
                              boolean largeArc, boolean sweep,
                              float x1, float y1, float x2, float y2) {
        if (rx == 0 || ry == 0 || (x1 == x2 && y1 == y2)) {
            p.lineTo(x2, y2);
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double phi = Math.toRadians(rot % 360);
        double cosPhi = Math.cos(phi), sinPhi = Math.sin(phi);

        double dx = (x1 - x2) / 2.0, dy = (y1 - y2) / 2.0;
        double x1p = cosPhi * dx + sinPhi * dy;
        double y1p = -sinPhi * dx + cosPhi * dy;

        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            double k = Math.sqrt(lambda);
            rx *= k;
            ry *= k;
        }

        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = ((largeArc != sweep) ? 1 : -1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * (rx * y1p / ry);
        double cyp = coef * (-ry * x1p / rx);
        double cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0;
        double cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0;

        double theta1 = Math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx);
        double theta2 = Math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx);
        double dTheta = theta2 - theta1;
        if (sweep && dTheta < 0) dTheta += 2 * Math.PI;
        if (!sweep && dTheta > 0) dTheta -= 2 * Math.PI;

        int segs = (int) Math.ceil(Math.abs(dTheta) / (Math.PI / 2));
        if (segs < 1) segs = 1;
        double delta = dTheta / segs;
        double t = 4.0 / 3.0 * Math.tan(delta / 4);

        for (int i = 0; i < segs; i++) {
            double a1 = theta1 + i * delta;
            double a2 = a1 + delta;
            double c1 = Math.cos(a1), s1 = Math.sin(a1);
            double c2 = Math.cos(a2), s2 = Math.sin(a2);

            double e1x = cosPhi * rx * c1 - sinPhi * ry * s1 + cx;
            double e1y = sinPhi * rx * c1 + cosPhi * ry * s1 + cy;
            double e2x = cosPhi * rx * c2 - sinPhi * ry * s2 + cx;
            double e2y = sinPhi * rx * c2 + cosPhi * ry * s2 + cy;

            double d1x = -rx * s1, d1y = ry * c1;
            double d2x = -rx * s2, d2y = ry * c2;

            double cp1x = e1x + t * (cosPhi * d1x - sinPhi * d1y);
            double cp1y = e1y + t * (sinPhi * d1x + cosPhi * d1y);
            double cp2x = e2x - t * (cosPhi * d2x - sinPhi * d2y);
            double cp2y = e2y - t * (sinPhi * d2x + cosPhi * d2y);

            p.cubicTo((float) cp1x, (float) cp1y, (float) cp2x, (float) cp2y,
                    (float) e2x, (float) e2y);
        }
    }

    private static boolean isCommand(char c) {
        return "MmLlHhVvCcSsQqTtAaZz".indexOf(c) >= 0;
    }

    /** 只做一件事：把字符串里的数字逐个读出来。 */
    private static final class Scanner {
        private final String s;
        private int i;

        Scanner(String s) { this.s = s; }

        boolean hasMore() {
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            return i < s.length();
        }

        char peek() { return s.charAt(i); }

        int pos() { return i; }

        void next() { i++; }

        float number() {
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
            try {
                return Float.parseFloat(s.substring(start, i));
            } catch (Exception e) {
                return 0f;
            }
        }
    }
}
