import com.dsh.android.kernel.SecretFormat;

/** 密钥封装格式的纯 JVM 测试：Keystore 在 JVM 里跑不了，但格式必须可测。 */
public final class SecretFormatTest {

    private static int pass = 0, fail = 0;

    private static void ok(boolean cond, String what) {
        if (cond) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    public static void main(String[] a) {
        System.out.println("t1 Base64 往返");
        byte[] src = bytes(0, 1, 2, 250, 251, 252, 253, 254, 255);
        ok(java.util.Arrays.equals(SecretFormat.decode(SecretFormat.encode(src)), src),
                "任意字节往返一致");
        ok(SecretFormat.encode(bytes()).length() == 0, "空数组编码为空串");
        ok(SecretFormat.encode(bytes(65)).equals("QQ=="), "单字节补两个等号");
        ok(SecretFormat.encode(bytes(65, 66)).equals("QUI="), "两字节补一个等号");
        ok(SecretFormat.encode(bytes(65, 66, 67)).equals("QUJD"), "三字节不补");

        System.out.println("t2 封装与拆解");
        byte[] iv = bytes(9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2);
        byte[] ct = bytes(11, 22, 33, 44);
        String wrapped = SecretFormat.wrap(iv, ct);
        ok(SecretFormat.isEncrypted(wrapped), "带前缀");
        byte[][] got = SecretFormat.unwrap(wrapped, 12);
        ok(got != null && java.util.Arrays.equals(got[0], iv), "iv 还原");
        ok(got != null && java.util.Arrays.equals(got[1], ct), "密文还原");

        System.out.println("t3 旧数据与坏数据");
        ok(!SecretFormat.isEncrypted("sk-plain"), "明文不算密文");
        ok(SecretFormat.unwrap("sk-plain", 12) == null, "明文 unwrap 返回 null");
        ok(SecretFormat.unwrap("enc1:", 12) == null, "空密文返回 null");
        ok(SecretFormat.unwrap("enc1:!!!", 12) == null, "非法字符返回 null");
        ok(SecretFormat.unwrap("enc1:AAAA", 12) == null, "长度不足返回 null");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
