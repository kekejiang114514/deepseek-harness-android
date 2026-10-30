package com.dsh.android.kernel;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * 把已安装 APK 里的 AndroidManifest.xml 打出来（只读，用于排障）。
 *
 * <p>为什么要自己写：{@code ServiceInfo.permission} 在本机上读出来是 null，
 * 但包里的字节明明带着 {@code android:permission}。要判断是「包里的属性没挂对」
 * 还是「系统读属性时没匹配上」，只能自己按 Android 的规则解一遍 AXML：
 * 属性最终的身份不是名字，而是 <b>resource map[nameIndex]</b> 里的资源 ID。
 *
 * <p>规则与 AOSP 的 ResXMLTree 一致：属性名在字符串池里的下标 n，
 * 它的资源 ID 就是 resmap[n]；n 越界则视为无名属性（返回 -1）。
 * 如果这里算出来的 ID 不对，系统那边就一定匹配不到。
 */
public final class AxmlDump {

    private AxmlDump() {}

    public static String dumpService(File apk, String className) {
        StringBuilder out = new StringBuilder();
        try {
            byte[] d = readEntry(apk, "AndroidManifest.xml");
            Tree t = new Tree(d);
            out.append("清单字符串池 ").append(t.strings.size())
               .append(" 项，resource map ").append(t.resmap.size()).append(" 项\n");
            out.append("含 <service> 元素的属性：\n");
            boolean found = false;
            for (int i = 0; i < t.elements.size() && !found; i++) {
                Tree.Elem e = t.elements.get(i);
                if (!"service".equals(e.name)) continue;
                boolean mine = false;
                for (Tree.Attr a : e.attrs) {
                    if ("name".equals(a.nameName) && a.resId == 0x01010003
                            && a.value != null && a.value.contains("A11yService")) mine = true;
                }
                if (!mine) continue;
                found = true;
                out.append("<service>\n");
                for (Tree.Attr a : e.attrs) {
                    out.append("  ").append(pad(a.nameName))
                       .append(" resid=").append(hex(a.resId))
                       .append(" type=").append(hex(a.type))
                       .append(" value=").append(a.value).append('\n');
                }
            }
            if (!found) out.append("  （没有找到带 A11yService 的 service 元素）\n");
        } catch (Throwable t) {
            out.append("解析失败：").append(t).append('\n');
        }
        return out.toString();
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder(s == null ? "?" : s);
        while (sb.length() < 22) sb.append(' ');
        return sb.toString();
    }

    private static String hex(int v) {
        return "0x" + Integer.toHexString(v).toUpperCase();
    }

    private static byte[] readEntry(File apk, String name) throws Exception {
        ZipFile zf = new ZipFile(apk);
        try {
            java.util.zip.ZipEntry e = zf.getEntry(name);
            if (e == null) throw new Exception("没有 " + name);
            InputStream in = zf.getInputStream(e);
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } finally {
                in.close();
            }
        } finally {
            zf.close();
        }
    }

    // ------------------------------------------------------------ AXML

    private static final class Tree {
        final List<String> strings = new ArrayList<String>();
        final List<Integer> resmap = new ArrayList<Integer>();
        final List<Elem> elements = new ArrayList<Elem>();

        static final class Attr {
            String nameName;
            int resId;
            int type;
            String value;
        }

        static final class Elem {
            String name;
            final List<Attr> attrs = new ArrayList<Attr>();
        }

        Tree(byte[] d) {
            int off = u16(d, 2);
            int total = u32(d, 4);
            while (off + 8 <= total) {
                int type = u16(d, off);
                int hsize = u16(d, off + 2);
                int size = u32(d, off + 4);
                if (size <= 0) break;

                if (type == 0x0001) {                    // string pool
                    int cnt = u32(d, off + 8);
                    int flags = u32(d, off + 16);
                    boolean utf8 = (flags & 0x100) != 0;
                    int dataStart = off + u32(d, off + 20);
                    int tableStart = off + hsize;
                    for (int i = 0; i < cnt; i++) {
                        int p = dataStart + u32(d, tableStart + 4 * i);
                        strings.add(readString(d, p, utf8));
                    }
                } else if (type == 0x0180) {             // resource map
                    int cnt = (size - hsize) / 4;
                    for (int i = 0; i < cnt; i++) resmap.add(u32(d, off + hsize + 4 * i));
                } else if (type == 0x0102) {             // start element
                    int ext = off + hsize;
                    int nameIdx = u32(d, ext + 4);
                    int attrStart = u16(d, ext + 8);
                    int attrSize = u16(d, ext + 10);
                    int attrCount = u16(d, ext + 12);
                    Elem e = new Elem();
                    e.name = nameIdx < strings.size() ? strings.get(nameIdx) : "?";
                    for (int k = 0; k < attrCount; k++) {
                        int p = ext + attrStart + k * attrSize;
                        int nIdx = u32(d, p + 4);
                        int rawIdx = u32(d, p + 8);
                        int dtype = d[p + 15] & 0xFF;
                        int data = u32(d, p + 16);
                        Attr a = new Attr();
                        a.nameName = nIdx < strings.size() ? strings.get(nIdx) : "?";
                        a.resId = nIdx < resmap.size() ? resmap.get(nIdx) : -1;
                        a.type = dtype;
                        if (dtype == 0x03 && data < strings.size()) a.value = strings.get(data);
                        else if (rawIdx != 0xFFFFFFFF && rawIdx < strings.size()) a.value = strings.get(rawIdx);
                        else a.value = "0x" + Integer.toHexString(data);
                        e.attrs.add(a);
                    }
                    elements.add(e);
                }
                off += size;
            }
        }

        private static String readString(byte[] d, int p, boolean utf8) {
            try {
                if (utf8) {
                    int b = d[p] & 0xFF; p += 1;
                    if ((b & 0x80) != 0) p += 1;
                    int ln = d[p] & 0xFF; p += 1;
                    if ((ln & 0x80) != 0) {
                        ln = ((ln & 0x7f) << 8) | (d[p] & 0xFF);
                        p += 1;
                    }
                    return new String(d, p, ln, "UTF-8");
                }
                int n = u16(d, p); p += 2;
                if ((n & 0x8000) != 0) {
                    n = ((n & 0x7fff) << 16) | u16(d, p);
                    p += 2;
                }
                return new String(d, p, n * 2, "UTF-16LE");
            } catch (Exception e) {
                return "?";
            }
        }

        private static int u16(byte[] d, int o) {
            return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
        }

        private static int u32(byte[] d, int o) {
            return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8)
                    | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
        }
    }
}
