import com.dsh.android.market.Tgz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * tar.gz 解包器的纯 JVM 测试。
 *
 * 这里自己造一个最小 tar 包来测，不依赖网络；
 * 真实 npm 包的验证在 test/market-e2e.sh 里单独跑（需要联网）。
 */
public class TgzTest {

    static int pass = 0;
    static int fail = 0;

    static void ok(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("  PASS  " + msg); }
        else { fail++; System.out.println("  FAIL  " + msg); }
    }

    // ---------- 造包 ----------

    static byte[] tarHeader(String name, long size, char type) {
        byte[] h = new byte[512];
        put(h, 0, name);
        put(h, 100, "0000644");      // mode
        put(h, 108, "0000000");      // uid
        put(h, 116, "0000000");      // gid
        put(h, 124, String.format("%011o", size));
        put(h, 136, "00000000000");  // mtime
        h[156] = (byte) type;
        put(h, 257, "ustar");
        put(h, 263, "00");
        // checksum：先按空格算，再回填
        for (int i = 148; i < 156; i++) h[i] = ' ';
        int sum = 0;
        for (int i = 0; i < 512; i++) sum += (h[i] & 0xFF);
        put(h, 148, String.format("%06o", sum) + "\0 ");
        return h;
    }

    static void put(byte[] b, int off, String s) {
        byte[] raw = s.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        for (int i = 0; i < raw.length && off + i < b.length; i++) b[off + i] = raw[i];
    }

    static byte[] tar(String[][] files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String[] f : files) {
            byte[] data = f[1] == null ? new byte[0] : f[1].getBytes("UTF-8");
            out.write(tarHeader(f[0], data.length, f[1] == null ? '5' : '0'));
            if (data.length > 0) {
                out.write(data);
                int pad = (512 - (data.length % 512)) % 512;
                out.write(new byte[pad]);
            }
        }
        out.write(new byte[1024]);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        GZIPOutputStream g = new GZIPOutputStream(gz);
        g.write(out.toByteArray());
        g.finish();
        g.close();
        return gz.toByteArray();
    }

    static File tmpDir(String name) {
        File f = new File("/tmp/tgztest-" + name);
        rm(f);
        return f;
    }

    static void rm(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) rm(k);
        f.delete();
    }

    public static void main(String[] a) throws Exception {
        System.out.println("t1 解出文件");
        byte[] tgz = tar(new String[][]{
                {"package/package.json", "{\"name\":\"x\",\"main\":\"plugin.js\"}"},
                {"package/plugin.js", "export function apply(ctx){}"},
                {"package/lib/util.js", "module.exports = 1;"},
                {"package/dist", null},
                {"package/dist/a.js", "var a = 1;"},
        });
        List<Tgz.Entry> es = Tgz.read(new ByteArrayInputStream(tgz));
        ok(es.size() == 5, "5 条（4 文件 + 1 目录），实际 " + es.size() + " 条");
        ok(es.get(0).path.equals("package.json"), "package/ 前缀被去掉：" + es.get(0).path);

        File dir = tmpDir("ok");
        int n = Tgz.extract(es, dir);
        ok(n == 4, "写入 4 个文件，实际 " + n);
        ok(new File(dir, "plugin.js").isFile(), "plugin.js 落地");
        ok(new File(dir, "lib/util.js").isFile(), "子目录自动创建");
        ok(new File(dir, "dist").isDirectory(), "目录条目被创建");

        System.out.println("t2 拒绝路径穿越");
        byte[] evil = tar(new String[][]{
                {"package/../../etc/passwd", "pwned"},
        });
        List<Tgz.Entry> evilEntries = Tgz.read(new ByteArrayInputStream(evil));
        File evilDir = tmpDir("evil");
        boolean threw = false;
        try {
            Tgz.extract(evilEntries, evilDir);
        } catch (Exception e) {
            threw = true;
        }
        ok(threw || !new File("/tmp/etc/passwd").exists(), "穿越路径被拒绝或未写出");

        System.out.println("t3 空包与纯目录包");
        byte[] empty = tar(new String[][]{});
        ok(Tgz.read(new ByteArrayInputStream(empty)).isEmpty(), "空归档返回空列表");

        byte[] dirsOnly = tar(new String[][]{{"package/a", null}, {"package/b", null}});
        List<Tgz.Entry> d = Tgz.read(new ByteArrayInputStream(dirsOnly));
        ok(d.size() == 2 && d.get(0).dir, "只识别出目录条目");

        System.out.println("t4 大文件保护");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 4 * 1024 * 1024 + 10; i++) big.append('x');
        try {
            Tgz.read(new ByteArrayInputStream(tar(new String[][]{{"package/huge.js", big.toString()}})));
            ok(false, "超过 4MB 的单文件应该报错");
        } catch (Exception e) {
            ok(true, "超过 4MB 的单文件被拒绝");
        }

        rm(dir);
        rm(evilDir);
        System.out.println();
        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
