package com.dsh.android.plugins.js;

/**
 * 把 DSH 插件的 ESM 源码转成可以直接 eval 的普通脚本。
 *
 * <p>为什么需要：DSH 插件是 TypeScript/ESM 模块（`export function apply`、
 * `import { defineTool } from '@deepseek-ai/dsh-tools'`），而我们要把它塞进
 * WebView 里当普通脚本执行。完整实现一个模块加载器不现实，这里只做语法层面的等价改写：
 *
 * <ul>
 *   <li>{@code export function f} / {@code export const x} / {@code export class C}
 *       → 去掉 {@code export}</li>
 *   <li>{@code export default X} → {@code var __default = X}</li>
 *   <li>{@code import {a, b} from 'm'} → {@code var {a, b} = __mod('m')}</li>
 *   <li>{@code import x from 'm'} → {@code var x = __mod('m')}</li>
 *   <li>{@code import * as ns from 'm'} → {@code var ns = __mod('m')}</li>
 *   <li>整行 {@code import 'm'} / {@code import type ...} → 删掉</li>
 * </ul>
 *
 * <p>只处理行首/行内的模块关键字，不解析字符串内部；改写在字符串模板里出现
 * `export ` 这种极端情况会误伤，但正常插件不会这么写。
 */
public final class Esm {

    private Esm() {}

    /** module 名 → 我们在 bootstrap 里提供的替身对象。 */
    public static String transform(String source) {
        if (source == null) return "";
        StringBuilder out = new StringBuilder(source.length() + 64);
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            String indent = line.substring(0, line.length() - line.trim().length());

            // import type {...} from '...' —— TS 类型专用，整行丢弃
            if (t.startsWith("import type ") || t.startsWith("import type{")) {
                out.append(indent).append(";");
                if (i < lines.length - 1) out.append('\n');
                continue;
            }

            String rewritten = rewriteImport(line);
            if (rewritten == null) {
                // 纯副作用 import 'x'：直接删掉，替身模块不需要副作用
                out.append(indent).append(";");
                if (i < lines.length - 1) out.append('\n');
                continue;
            }
            out.append(rewriteExport(rewritten));
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    /** 处理 import，返回改写后的行；纯副作用 import 返回 null。 */
    private static String rewriteImport(String line) {
        String t = line.trim();
        if (!t.startsWith("import ") && !t.equals("import")) return line;

        int from = t.indexOf(" from ");
        if (from < 0) return null;   // import './x' 这样的纯副作用导入

        String head = t.substring("import ".length(), from).trim();
        int q1 = t.indexOf('\'', from);
        int q2 = t.lastIndexOf('\'');
        if (q1 < 0 || q2 <= q1) {
            q1 = t.indexOf('"', from);
            q2 = t.lastIndexOf('"');
            if (q1 < 0 || q2 <= q1) return line;
        }
        String mod = t.substring(q1 + 1, q2);
        String indent = line.substring(0, line.length() - line.trim().length());

        String decl;
        if (head.startsWith("{") && head.endsWith("}")) {
            String inner = head.substring(1, head.length() - 1).trim();
            StringBuilder sb = new StringBuilder();
            String[] parts = inner.split(",");
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.length() == 0) continue;
                if (sb.length() > 0) sb.append(", ");
                // import {a as b} 保留别名
                sb.append(p);
            }
            if (sb.length() == 0) {
                decl = "var __unused = __mod('" + mod + "')";
            } else {
                decl = "var { " + sb + " } = __mod('" + mod + "')";
            }
        } else if (head.startsWith("* as ")) {
            decl = "var " + head.substring(5).trim() + " = __mod('" + mod + "')";
        } else if (head.length() > 0) {
            decl = "var " + head + " = __mod('" + mod + "')";
        } else {
            return null;
        }
        return indent + decl + ";";
    }

    /** 去掉 export 关键字，保留声明本身。 */
    private static String rewriteExport(String line) {
        String t = line.trim();
        if (!t.startsWith("export ")) return line;
        String indent = line.substring(0, line.length() - line.trim().length());
        String rest = t.substring("export ".length()).trim();

        if (rest.startsWith("default ")) {
            return line.replace("export default ", "var __default = ");
        }
        if (rest.startsWith("default")) {
            // export default 后面换行的情况：罕见，按变量处理
            return line.replace("export default", "var __default =");
        }
        if (rest.startsWith("{")) {
            // export { a, b } —— 重新导出，直接忽略（依赖方用不到）
            return indent + ";";
        }
        return line.replaceFirst("export\\s+", "");
    }

    /**
     * 源码里是否引用了 Android 上做不到的模块（子进程、原始套接字等）。
     * fs/path/os/util/url/crypto 有桥接实现，不算在内。
     */
    public static String firstUnsupportedImport(String source) {
        if (source == null) return null;
        String[] lines = source.split("\n");
        for (String raw : lines) {
            String t = raw.trim();
            String mod = null;
            int q1 = t.indexOf('\'');
            int q2 = t.lastIndexOf('\'');
            if (q1 >= 0 && q2 > q1 && (t.startsWith("import ") || t.startsWith("require("))) {
                mod = t.substring(q1 + 1, q2);
            } else {
                int d1 = t.indexOf('"');
                int d2 = t.lastIndexOf('"');
                if (d1 >= 0 && d2 > d1 && (t.startsWith("import ") || t.startsWith("require("))) {
                    mod = t.substring(d1 + 1, d2);
                }
            }
            if (mod == null) continue;
            if (mod.startsWith("node:")) mod = mod.substring(5);
            for (String b : BANNED) {
                if (b.equals(mod)) return mod;
            }
        }
        return null;
    }

    /** Android 上做不到的模块。 */
    private static final String[] BANNED = {
            "child_process", "net", "dgram", "worker_threads",
            "cluster", "vm", "http", "https", "tls", "dns"
    };
}
