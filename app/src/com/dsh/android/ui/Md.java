package com.dsh.android.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 Markdown 渲染器（零依赖，纯 View 拼装）。
 *
 * 支持：标题、段落、粗体、斜体、删除线、行内代码、链接、围栏代码块、
 * 有序/无序列表、引用、分割线、表格（真 TableLayout，可横向滚动）。
 *
 * 设计要点：不用 WebView——WebView 在离线包里有白屏/闪烁问题，而且无法跟随
 * 应用主题变量。这里全部用原生 View，颜色直接取 Theme。
 */
public final class Md {

    private Md() {}

    /** 把 Markdown 渲染进 host（会先清空 host）。 */
    public static void render(Context ctx, LinearLayout host, String src) {
        host.removeAllViews();
        if (src == null) src = "";
        String[] lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            // 围栏代码块
            if (trimmed.startsWith("```")) {
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]);
                    if (i < lines.length - 1) code.append('\n');
                    i++;
                }
                i++; // 跳过结束围栏
                host.addView(codeBlock(ctx, code.toString()));
                continue;
            }

            // 表格：当前行含 |，且下一行是分隔线
            if (isTableRow(trimmed) && i + 1 < lines.length && isTableDivider(lines[i + 1].trim())) {
                List<String[]> rows = new ArrayList<String[]>();
                rows.add(splitRow(trimmed));
                i += 2;
                while (i < lines.length && isTableRow(lines[i].trim())) {
                    rows.add(splitRow(lines[i].trim()));
                    i++;
                }
                host.addView(table(ctx, rows));
                continue;
            }

            // 空行
            if (trimmed.length() == 0) { i++; continue; }

            // 分割线
            if (isHr(trimmed)) {
                View hr = new View(ctx);
                hr.setBackgroundColor(Theme.border());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Theme.dp(ctx, 1)));
                lp.topMargin = Theme.dp(ctx, 10);
                lp.bottomMargin = Theme.dp(ctx, 10);
                hr.setLayoutParams(lp);
                host.addView(hr);
                i++;
                continue;
            }

            // 标题
            int level = headingLevel(trimmed);
            if (level > 0) {
                String text = trimmed.substring(level).trim();
                host.addView(heading(ctx, text, level));
                i++;
                continue;
            }

            // 引用
            if (trimmed.startsWith(">")) {
                StringBuilder q = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    String t = lines[i].trim().substring(1);
                    if (t.startsWith(" ")) t = t.substring(1);
                    q.append(t).append('\n');
                    i++;
                }
                host.addView(quote(ctx, q.toString().trim()));
                continue;
            }

            // 列表（同类型连续行合并成一个块）
            if (bulletOf(trimmed) != null || orderedOf(trimmed) > 0) {
                boolean ordered = orderedOf(trimmed) > 0;
                LinearLayout box = new LinearLayout(ctx);
                box.setOrientation(LinearLayout.VERTICAL);
                int n = 1;
                while (i < lines.length) {
                    String t = lines[i].trim();
                    if (ordered) {
                        int num = orderedOf(t);
                        if (num <= 0) break;
                        box.addView(listItem(ctx, num + ".", t.substring(t.indexOf('.') + 1).trim()));
                    } else {
                        String b = bulletOf(t);
                        if (b == null) break;
                        box.addView(listItem(ctx, "\u2022", t.substring(b.length()).trim()));
                    }
                    n++;
                    i++;
                }
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                blp.topMargin = Theme.dp(ctx, 4);
                box.setLayoutParams(blp);
                host.addView(box);
                continue;
            }

            // 普通段落：连续非空、非特殊行合并
            StringBuilder para = new StringBuilder();
            while (i < lines.length) {
                String t = lines[i].trim();
                if (t.length() == 0) break;
                if (t.startsWith("```") || headingLevel(t) > 0) break;
                if (t.startsWith(">") || isHr(t)) break;
                if (bulletOf(t) != null || orderedOf(t) > 0) break;
                if (isTableRow(t) && i + 1 < lines.length && isTableDivider(lines[i + 1].trim())) break;
                if (para.length() > 0) para.append('\n');
                para.append(lines[i]);
                i++;
            }
            if (para.length() > 0) host.addView(paragraph(ctx, para.toString()));
            else i++; // 保险：避免死循环
        }
    }

    // ------------------------------------------------------------ 块级元素

    private static TextView paragraph(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(inline(text));
        style(tv, Theme.text(), 15f);
        tv.setLineSpacing(Theme.dp(ctx, 4), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(ctx, 6);
        lp.bottomMargin = Theme.dp(ctx, 2);
        tv.setLayoutParams(lp);
        if (text.indexOf("](") >= 0) tv.setMovementMethod(LinkMovementMethod.getInstance());
        return tv;
    }

    private static TextView heading(Context ctx, String text, int level) {
        TextView tv = new TextView(ctx);
        tv.setText(inline(text));
        float size = level == 1 ? 20f : level == 2 ? 17.5f : level == 3 ? 16f : 15f;
        style(tv, Theme.text(), size);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLineSpacing(Theme.dp(ctx, 4), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(ctx, level <= 2 ? 16 : 12);
        lp.bottomMargin = Theme.dp(ctx, 4);
        tv.setLayoutParams(lp);
        if (text.indexOf("](") >= 0) tv.setMovementMethod(LinkMovementMethod.getInstance());
        return tv;
    }

    /** 代码块：等宽 + 灰底 + 横向滚动，可长按复制。 */
    private static View codeBlock(Context ctx, String code) {
        TextView tv = new TextView(ctx);
        tv.setText(code);
        tv.setTextSize(12.5f);
        tv.setTextColor(Theme.text());
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setHorizontallyScrolling(true);
        tv.setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 10), Theme.dp(ctx, 12), Theme.dp(ctx, 10));
        tv.setBackground(Theme.round(Theme.codeBg(), Theme.R_MD, ctx));

        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(tv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(ctx, 8);
        lp.bottomMargin = Theme.dp(ctx, 8);
        hs.setLayoutParams(lp);
        return hs;
    }

    private static View quote(Context ctx, String text) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);

        View bar = new View(ctx);
        bar.setBackgroundColor(Theme.borderStrong());
        box.addView(bar, new LinearLayout.LayoutParams(Theme.dp(ctx, 3),
                LinearLayout.LayoutParams.MATCH_PARENT));

        TextView tv = new TextView(ctx);
        tv.setText(inline(text));
        style(tv, Theme.text2(), 14.5f);
        tv.setLineSpacing(Theme.dp(ctx, 3), 1f);
        tv.setPadding(Theme.dp(ctx, 10), 0, 0, 0);
        box.addView(tv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(ctx, 8);
        lp.bottomMargin = Theme.dp(ctx, 8);
        box.setLayoutParams(lp);
        return box;
    }

    private static View listItem(Context ctx, String marker, String text) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView m = new TextView(ctx);
        m.setText(marker);
        style(m, Theme.text2(), 15f);
        m.setMinWidth(Theme.dp(ctx, 22));
        m.setPadding(0, 0, Theme.dp(ctx, 4), 0);
        m.setLineSpacing(Theme.dp(ctx, 4), 1f);
        row.addView(m);

        TextView tv = new TextView(ctx);
        tv.setText(inline(text));
        style(tv, Theme.text(), 15f);
        tv.setLineSpacing(Theme.dp(ctx, 4), 1f);
        row.addView(tv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(ctx, 3);
        row.setLayoutParams(lp);
        return row;
    }

    // ------------------------------------------------------------ 表格

    private static View table(Context ctx, List<String[]> rows) {
        int cols = 0;
        for (int i = 0; i < rows.size(); i++) cols = Math.max(cols, rows.get(i).length);
        if (cols == 0) cols = 1;

        // 按内容长度估算列宽（中文按 2 个字符算）。
        // 注意：TableLayout 放在 HorizontalScrollView 里时父宽是 wrap_content，
        // 用 weight 会因为「剩余空间」为 0 而塌成 0 宽，所以这里算固定像素宽。
        float[] colW = new float[cols];
        for (int c = 0; c < cols; c++) colW[c] = 3f;
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            for (int c = 0; c < cols && c < row.length; c++) {
                colW[c] = Math.max(colW[c], Math.min(26f, visualLen(stripMarks(row[c]))));
            }
        }
        int[] widthPx = new int[cols];
        for (int c = 0; c < cols; c++) {
            float px = colW[c] * 7.4f + 22f;   // 每字符约 7.4dp + 内边距
            widthPx[c] = Math.max(Theme.dp(ctx, 56), Theme.dp(ctx, px));
        }

        TableLayout tl = new TableLayout(ctx);
        tl.setStretchAllColumns(false);
        tl.setShrinkAllColumns(false);

        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            TableRow tr = new TableRow(ctx);
            for (int c = 0; c < cols; c++) {
                String cell = c < row.length ? row[c] : "";
                TextView tv = new TextView(ctx);
                tv.setText(inline(cell));
                style(tv, Theme.text(), 13.5f);
                if (r == 0) {
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                    tv.setBackground(Theme.roundOutlined(Theme.surface2(), Theme.R_SM,
                            Theme.border(), ctx));
                } else {
                    tv.setBackground(Theme.roundOutlined(Theme.bg(), Theme.R_SM,
                            Theme.border(), ctx));
                }
                tv.setPadding(Theme.dp(ctx, 9), Theme.dp(ctx, 7), Theme.dp(ctx, 9), Theme.dp(ctx, 7));

                TableRow.LayoutParams clp = new TableRow.LayoutParams(widthPx[c],
                        TableRow.LayoutParams.WRAP_CONTENT);
                clp.setMargins(0, Theme.dp(ctx, 2), Theme.dp(ctx, 4), 0);
                tr.addView(tv, clp);
            }
            tl.addView(tr);
        }

        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(tl, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(ctx, 10);
        lp.bottomMargin = Theme.dp(ctx, 6);
        hs.setLayoutParams(lp);
        return hs;
    }

    private static boolean isTableRow(String t) {
        return t.length() > 0 && t.indexOf('|') >= 0 && t.indexOf('|') < t.length() - 1;
    }

    private static boolean isTableDivider(String t) {
        if (t.length() < 3) return false;
        boolean sawDash = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '-') sawDash = true;
            else if (c == '|' || c == ':' || c == ' ') { /* ok */ }
            else return false;
        }
        return sawDash;
    }

    /** 拆表格行：|a|b| → ["a","b"]；去掉首尾空段。 */
    private static String[] splitRow(String t) {
        String s = t;
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        String[] raw = s.split("\\|", -1);
        String[] out = new String[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = raw[i].trim();
        return out;
    }

    /** 去掉 ** * ` ~~ 等标记，避免把标记符号算进列宽。 */
    private static String stripMarks(String s) {
        return s.replace("**", "").replace("~~", "").replace("`", "")
                .replace("*", "").replace("_", "");
    }

    private static float visualLen(String s) {
        float n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += (c > 0x2E80) ? 2f : 1f;
        }
        // 去掉 ** 之类的标记
        return Math.max(1f, n);
    }

    // ------------------------------------------------------------ 语法识别

    private static boolean isHr(String t) {
        if (t.length() < 3) return false;
        char first = t.charAt(0);
        if (first != '-' && first != '*' && first != '_') return false;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) != first) return false;
        }
        return true;
    }

    private static int headingLevel(String t) {
        int n = 0;
        while (n < t.length() && n < 6 && t.charAt(n) == '#') n++;
        if (n == 0) return 0;
        if (n < t.length() && t.charAt(n) == ' ') return n;
        return 0;
    }

    /** 无序列表标记（"- " / "* " / "+ "），返回标记长度；不是则 null。 */
    private static String bulletOf(String t) {
        if (t.length() < 2) return null;
        char c = t.charAt(0);
        if ((c == '-' || c == '*' || c == '+') && t.charAt(1) == ' ') return t.substring(0, 1);
        return null;
    }

    /** 有序列表 "1. " / "1) "，返回序号；不是则 0。 */
    private static int orderedOf(String t) {
        int i = 0;
        while (i < t.length() && Character.isDigit(t.charAt(i))) i++;
        if (i == 0 || i > 3) return 0;
        if (i < t.length() && (t.charAt(i) == '.' || t.charAt(i) == ')')
                && i + 1 < t.length() && t.charAt(i + 1) == ' ') {
            try { return Integer.parseInt(t.substring(0, i)); } catch (Exception e) { return 0; }
        }
        return 0;
    }

    private static void style(TextView tv, int color, float sp) {
        tv.setTextColor(color);
        tv.setTextSize(sp);
        tv.setIncludeFontPadding(false);
    }

    // ------------------------------------------------------------ 行内格式

    /** 解析行内 Markdown（**粗**、*斜*、`码`、~~删~~、[文](url)）。 */
    public static CharSequence inline(String s) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        build(sb, s);
        return sb;
    }

    private static void build(SpannableStringBuilder sb, String s) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);

            // 反斜杠转义
            if (c == '\\' && i + 1 < n) {
                sb.append(s.charAt(i + 1));
                i += 2;
                continue;
            }

            // 行内代码
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i) {
                    String code = s.substring(i + 1, end);
                    int st = sb.length();
                    sb.append(code);
                    sb.setSpan(new TypefaceSpan("monospace"), st, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(Theme.inlineCodeBg()),
                            st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(0.94f), st, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }

            // 粗体
            if (c == '*' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("**", i + 2);
                if (end > i + 1) {
                    int st = sb.length();
                    build(sb, s.substring(i + 2, end));
                    sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }

            // 删除线
            if (c == '~' && i + 1 < n && s.charAt(i + 1) == '~') {
                int end = s.indexOf("~~", i + 2);
                if (end > i + 1) {
                    int st = sb.length();
                    build(sb, s.substring(i + 2, end));
                    sb.setSpan(new StrikethroughSpan(), st, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }

            // 斜体（单星 / 单下划线）
            if ((c == '*' || c == '_') && (i + 1 < n && s.charAt(i + 1) != ' ')) {
                int end = s.indexOf(c, i + 1);
                if (end > i + 1) {
                    int st = sb.length();
                    build(sb, s.substring(i + 1, end));
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), st, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }

            // 链接 [text](url)
            if (c == '[') {
                int rb = s.indexOf(']', i + 1);
                if (rb > i && rb + 1 < n && s.charAt(rb + 1) == '(') {
                    int rp = s.indexOf(')', rb + 2);
                    if (rp > rb) {
                        final String label = s.substring(i + 1, rb);
                        final String url = s.substring(rb + 2, rp).trim();
                        int st = sb.length();
                        build(sb, label);
                        sb.setSpan(new MdLink(url), st, sb.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = rp + 1;
                        continue;
                    }
                }
            }

            sb.append(c);
            i++;
        }
    }

    /** 可点击链接：交给系统浏览器 / 对应 app 打开。 */
    private static final class MdLink extends ClickableSpan {
        private final String url;
        MdLink(String url) { this.url = url; }

        @Override
        public void onClick(View w) {
            try {
                android.content.Intent intent = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                w.getContext().startActivity(intent);
            } catch (Exception ignored) {}
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setColor(Theme.link());
            ds.setUnderlineText(false);
        }
    }
}
