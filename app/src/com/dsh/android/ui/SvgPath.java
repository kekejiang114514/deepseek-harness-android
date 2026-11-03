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
                case 'Z':
                    path.close();
                    cx = sx; cy = sy;
                    break;
                default:
                    return path;   // 遇到不认识的指令就停下，宁可少画也不乱画
            }
        }
        return path;
    }

    private static boolean isCommand(char c) {
        return "MmLlHhVvCcSsQqTtZz".indexOf(c) >= 0;
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
