package com.dsh.android.kernel;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 上下文压缩的结构化日志。
 *
 * <p>压缩会改写历史，出问题时最难回答的是「什么时候、为什么、砍掉了多少」。
 * 每次压缩写一行 JSON：触发原因、前后 token、折叠轮数、被截断的工具结果数、
 * 是否压到底仍然超预算。按行追加，超过上限就滚动丢弃最早的行。
 */
public final class CompactionLog {

    /** 保留的最大行数。 */
    public static final int MAX_LINES = 200;

    private final File file;

    public CompactionLog(File dir) {
        this.file = new File(dir, "compaction.log");
    }

    public File file() { return file; }

    /** 写入一条压缩记录。 */
    public String write(String reason, int tokensBefore, int tokensAfter,
                        int foldedTurns, int slimmedResults, boolean overflow) {
        String line;
        try {
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            o.put("reason", reason == null ? "" : reason);
            o.put("before", tokensBefore);
            o.put("after", tokensAfter);
            o.put("folded", foldedTurns);
            o.put("slimmed", slimmedResults);
            o.put("overflow", overflow);
            line = o.toString();
        } catch (Throwable t) {
            return "";
        }
        append(line);
        return line;
    }

    private void append(String line) {
        String all = read();
        String merged = all.length() == 0 ? line + "\n" : all + line + "\n";
        String[] lines = merged.split("\n");
        if (lines.length > MAX_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - MAX_LINES; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            merged = sb.toString();
        }
        try {
            FileOutputStream out = new FileOutputStream(file, false);
            out.write(merged.getBytes("UTF-8"));
            out.flush();
            out.close();
        } catch (Throwable ignored) {}
    }

    public String read() {
        if (!file.exists()) return "";
        try {
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 最近 n 行，供诊断页展示。 */
    public String tail(int n) {
        String all = read();
        if (all.length() == 0) return "";
        String[] lines = all.split("\n");
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.length - n);
        for (int i = from; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
