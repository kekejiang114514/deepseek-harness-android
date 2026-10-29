package com.dsh.android.plugins.js;

/**
 * 剥掉 TypeScript 专有语法，让 TS 源码能当普通 JS 跑。
 *
 * <p>不是完整的 TS 编译器——那需要几万行。这里只处理插件里真正会出现的几种写法，
 * 按「不改变运行语义」的原则做文本层替换：
 *
 * <ul>
 *   <li>{@code interface X {...}} / {@code type X = ...} → 删除</li>
 *   <li>{@code function f(ctx: Context)} / {@code (a: string) => } → 去掉 {@code : Type}</li>
 *   <li>{@code x as SomeType} → 去掉 {@code as SomeType}</li>
 *   <li>可选参数 {@code (a?: string)} → {@code (a)}</li>
 * </ul>
 *
 * <p>处理范围刻意限定在参数列表与显式断言上：全局撒网的正则会把三元表达式
 * {@code a ? b : c} 里的冒号当类型标注切掉，那才是真正会毁掉代码的做法。
 */
public final class Ts {

    private Ts() {}

    public static String strip(String src) {
        if (src == null) return "";
        String s = stripInterfacesAndTypes(src);
        s = stripParamTypes(s);
        s = stripAs(s);
        s = stripReturnTypes(s);
        return s;
    }

    // ------------------------------------------------------------ interface / type

    private static String stripInterfacesAndTypes(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (startsWord(s, i, "interface") || startsWord(s, i, "type")) {
                boolean isInterface = startsWord(s, i, "interface");
                int kwEnd = i + (isInterface ? 9 : 4);
                // 跳过名字
                int j = kwEnd;
                while (j < n && Character.isWhitespace(s.charAt(j))) j++;
                int nameStart = j;
                while (j < n && (Character.isJavaIdentifierPart(s.charAt(j)))) j++;
                boolean looksLikeDecl = j > nameStart;
                if (isInterface && looksLikeDecl) {
                    int brace = s.indexOf('{', j);
                    if (brace > 0) {
                        int close = matchBrace(s, brace);
                        if (close > 0) {
                            i = close + 1;
                            continue;
                        }
                    }
                }
                if (!isInterface && looksLikeDecl) {
                    // type X = ...  到行尾分号
                    int eq = s.indexOf('=', j);
                    int semi = s.indexOf(';', j);
                    if (eq > 0 && semi > eq) {
                        i = semi + 1;
                        continue;
                    }
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = s.indexOf(c, i + 1);
                if (end < 0) return -1;
                i = end;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ 参数类型

    /** 找参数列表：function 后面的 (...) 与紧跟 => 的 (...)，逐参数去掉类型标注。 */
    private static String stripParamTypes(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipString(s, i);
                out.append(s, i, Math.min(end, n));
                i = Math.min(end, n);
                continue;
            }
            if (c == '(') {
                int close = matchParen(s, i);
                if (close > i) {
                    boolean isParams = false;
                    // 回溯看是不是 function 的名字后面
                    String before = out.toString().trim();
                    if (before.endsWith("function") || before.matches("(?s).*\\bfunction\\s+[A-Za-z_$][\\w$]*$")) {
                        isParams = true;
                    }
                    // 或者后面紧跟 =>
                    int k = close + 1;
                    while (k < n && Character.isWhitespace(s.charAt(k))) k++;
                    if (k < n && s.startsWith("=>", k)) isParams = true;

                    if (isParams) {
                        out.append('(').append(stripParams(s.substring(i + 1, close))).append(')');
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String stripParams(String inner) {
        if (inner.indexOf(':') < 0) return inner;
        StringBuilder res = new StringBuilder();
        int start = 0;
        int d = 0;
        for (int i = 0; i <= inner.length(); i++) {
            char c = i < inner.length() ? inner.charAt(i) : ',';
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipString(inner, i);
                i = end - 1;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') d++;
            if (c == ')' || c == ']' || c == '}') d--;
            if (c == ',' && d == 0) {
                res.append(cleanParam(inner.substring(start, i)));
                if (i < inner.length()) res.append(',');
                start = i + 1;
            }
        }
        return res.toString();
    }

    private static String cleanParam(String p) {
        int colon = -1;
        int eq = -1;
        int depth = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (depth == 0 && c == ':' && colon < 0) colon = i;
            else if (depth == 0 && c == '=' && colon >= 0 && eq < 0) eq = i;
        }
        if (colon < 0) return p;
        String lead = p.substring(0, p.length() - p.trim().length());
        String head = p.substring(0, colon).replace("?", "").trim();
        if (eq > colon) {
            // 保留默认值，同时补上原来被类型标注占掉的空格：b: number = 3 → b = 3
            String def = p.substring(eq).trim();
            return lead + head + " " + def;
        }
        return lead + head;
    }

    // ------------------------------------------------------------ as / 返回值

    private static String stripAs(String s) {
        // x as Type  —— Type 到 , ) ; 或行尾
        return s.replaceAll("\\s+as\\s+[A-Za-z_$][\\w$.<>\\[\\]|&]*\\s*(?=[,);\\n\\r])", "");
    }

    private static String stripReturnTypes(String s) {
        // function f(...): Type {   → function f(...) {
        return s.replaceAll("(\\))\\s*:\\s*[A-Za-z_$][\\w$.<>\\[\\]|&]*(\\s*\\{)", "$1$2");
    }

    // ------------------------------------------------------------ 工具

    private static boolean startsWord(String s, int i, String w) {
        if (!s.startsWith(w, i)) return false;
        int before = i - 1;
        if (before >= 0) {
            char c = s.charAt(before);
            if (Character.isJavaIdentifierPart(c)) return false;
        }
        int after = i + w.length();
        if (after < s.length() && Character.isJavaIdentifierPart(s.charAt(after))) return false;
        return true;
    }

    private static int skipString(String s, int start) {
        char q = s.charAt(start);
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == q) return i + 1;
        }
        return s.length();
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipString(s, i);
                i = end - 1;
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
