package com.dsh.android.kernel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 长期记忆：一个纯文本文件，人类可读、可直接编辑。
 *
 * <p>不做向量检索——让模型「维护文件」而不是「检索片段」。注入系统提示时
 * 整份读入，因此有体积上限；超了就从最早的条目开始丢。
 */
public final class MemoryStore {

    /** 注入提示词的上限：再大就会明显挤占上下文。 */
    public static final int MAX_BYTES = 12 * 1024;

    private final File file;

    public MemoryStore(File dir) {
        this.file = new File(dir, "memory.md");
    }

    /** 记忆文件路径，设置页展示用。 */
    public File file() {
        return file;
    }

    /** 读取全部记忆；没有文件时返回空串。 */
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

    /**
     * 追加一条记忆。
     *
     * @return 写入后的全量内容；失败返回原内容
     */
    public String append(String text) {
        if (text == null) return read();
        String body = text.trim();
        if (body.length() == 0) return read();

        String cur = read();
        String stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder(cur);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        sb.append("- ").append(stamp).append(' ').append(body).append('\n');
        return write(trim(sb.toString()));
    }

    /** 用给定内容覆盖（设置页手工编辑用）。 */
    public String write(String content) {
        try {
            FileOutputStream out = new FileOutputStream(file, false);
            out.write((content == null ? "" : content).getBytes("UTF-8"));
            out.flush();
            out.close();
        } catch (Throwable ignored) {}
        return read();
    }

    public void clear() {
        try {
            FileOutputStream out = new FileOutputStream(file, false);
            out.write(new byte[0]);
            out.close();
        } catch (Throwable ignored) {}
    }

    /**
     * 超出上限时丢掉最前面的行（最早的记忆）。
     *
     * <p>从末尾往前累加而不是从开头往后截：记忆是按时间追加的，
     * 要保留的是最近的条目。
     */
    public static String trim(String s) {
        if (s == null) return "";
        byte[] raw;
        try {
            raw = s.getBytes("UTF-8");
        } catch (Throwable t) {
            return s;
        }
        if (raw.length <= MAX_BYTES) return s;

        String[] lines = s.split("\n", -1);
        StringBuilder keep = new StringBuilder();
        int bytes = 0;
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            int add;
            try {
                add = (line + "\n").getBytes("UTF-8").length;
            } catch (Throwable t) {
                add = line.length() + 1;
            }
            if (bytes + add > MAX_BYTES) break;
            keep.insert(0, line + "\n");
            bytes += add;
        }
        return keep.toString();
    }
}
