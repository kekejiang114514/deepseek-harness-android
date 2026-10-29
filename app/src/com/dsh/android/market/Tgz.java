package com.dsh.android.market;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 极简 tar.gz 解包器：只为装 npm 包用，不引第三方库。
 *
 * <p>只认普通文件（typeflag '0' 或 '\\0'）与目录（'5'），
 * 软链、硬链、设备文件一律跳过；PAX/GNU 扩展头直接丢弃。
 *
 * <p>安全约束（这些都是必须的，不能省）：
 * <ul>
 *   <li>路径里出现 {@code ..} 或绝对路径 → 整个包拒绝（zip-slip）</li>
 *   <li>单文件上限 4MB、总解压上限 32MB，防解压炸弹</li>
 *   <li>只写 {@code prefix/} 之下的内容</li>
 * </ul>
 */
public final class Tgz {

    private static final int BLOCK = 512;
    private static final long MAX_FILE = 4L * 1024 * 1024;
    private static final long MAX_TOTAL = 32L * 1024 * 1024;

    public static final class Entry {
        public final String path;
        public final boolean dir;
        public final byte[] data;
        Entry(String path, boolean dir, byte[] data) {
            this.path = path;
            this.dir = dir;
            this.data = data;
        }
    }

    private Tgz() {}

    /** 解出所有条目（路径已去掉 {@code package/} 前缀）。 */
    public static List<Entry> read(InputStream in) throws IOException {
        GZIPInputStream gz = new GZIPInputStream(in, 8192);
        List<Entry> out = new ArrayList<Entry>();
        byte[] header = new byte[BLOCK];
        long total = 0;
        String pendingPaxPath = null;

        while (true) {
            int read = readFully(gz, header, BLOCK);
            if (read < BLOCK) break;
            if (isZero(header)) break;          // 归档结束

            String name = str(header, 0, 100);
            String prefix = str(header, 345, 155);
            long size = octal(header, 124, 12);
            char type = (char) header[156];
            String magic = str(header, 257, 6);

            if (type == 'x' || type == 'g') {
                // PAX 扩展头：读掉内容，记下 path= 覆盖（npm 包里很少见，但别崩）
                byte[] pax = new byte[(int) Math.min(size, MAX_FILE)];
                readFully(gz, pax, pax.length);
                skip(gz, size - pax.length);
                skip(gz, pad(size));
                String p = paxPath(pax);
                if (p != null) pendingPaxPath = p;
                continue;
            }

            if (prefix.length() > 0 && magic.startsWith("ustar")) {
                name = prefix + "/" + name;
            }
            if (pendingPaxPath != null) {
                name = pendingPaxPath;
                pendingPaxPath = null;
            }

            byte[] data = null;
            if (type == '0' || type == 0) {
                if (size > MAX_FILE) throw new IOException("包内文件过大：" + name);
                total += size;
                if (total > MAX_TOTAL) throw new IOException("解压后总大小超限");
                data = new byte[(int) size];
                readFully(gz, data, data.length);
                skip(gz, pad(size));
            } else if (type == '5') {
                skip(gz, pad(size));
            } else {
                skip(gz, size + pad(size));
                continue;                        // 链接等一律跳过
            }

            String clean = stripPackagePrefix(name);
            if (clean == null) continue;         // 包外的路径（极少见）
            if (clean.length() == 0) continue;
            out.add(new Entry(clean, type == '5', data));
        }
        gz.close();
        return out;
    }

    /** 把解出来的条目写到 targetDir。返回写入的文件数。 */
    public static int extract(List<Entry> entries, File targetDir) throws IOException {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("无法创建目录：" + targetDir);
        }
        String root = targetDir.getCanonicalPath();
        int files = 0;
        for (Entry e : entries) {
            if (e.path.indexOf("..") >= 0) throw new IOException("非法路径：" + e.path);
            File f = new File(targetDir, e.path);
            String canonical = f.getCanonicalPath();
            if (!canonical.equals(root) && !canonical.startsWith(root + File.separator)) {
                throw new IOException("路径越界：" + e.path);
            }
            if (e.dir) {
                if (!f.exists()) f.mkdirs();
                continue;
            }
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(e.data);
                out.flush();
            } finally {
                out.close();
            }
            files++;
        }
        return files;
    }

    // ---------- 内部 ----------

    private static String stripPackagePrefix(String name) {
        if (name.startsWith("./")) name = name.substring(2);
        if (name.startsWith("/")) return null;
        if (name.startsWith("package/")) return name.substring("package/".length());
        if ("package".equals(name)) return "";
        // 有些包没有 package/ 前缀，原样保留
        return name;
    }

    private static String paxPath(byte[] pax) {
        String s = new String(pax, java.nio.charset.Charset.forName("UTF-8"));
        int at = s.indexOf(" path=");
        if (at < 0) return null;
        int nl = s.indexOf('\n', at);
        return nl < 0 ? null : s.substring(at + 6, nl).trim();
    }

    private static long pad(long size) {
        long rem = size % BLOCK;
        return rem == 0 ? 0 : BLOCK - rem;
    }

    private static boolean isZero(byte[] b) {
        for (int i = 0; i < b.length; i++) if (b[i] != 0) return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, java.nio.charset.Charset.forName("UTF-8")).trim();
    }

    private static long octal(byte[] b, int off, int len) {
        String s = str(b, off, len).trim();
        if (s.length() == 0) return 0;
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            // 某些 tar 用 256 进制（高位置位），按字节累加
            long v = 0;
            for (int i = off; i < off + len; i++) v = v * 256 + (b[i] & 0xFF);
            return v;
        }
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, got, len - got);
            if (n < 0) return got;
            got += n;
        }
        return got;
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[4096];
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) return;
            left -= r;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
