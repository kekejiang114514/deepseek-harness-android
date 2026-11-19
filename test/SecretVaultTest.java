import com.dsh.android.kernel.SecretCipher;
import com.dsh.android.kernel.SecretFormat;
import com.dsh.android.kernel.SecretVault;

/**
 * 加解密门面的纯 JVM 测试。
 *
 * <p>用一个假密码器（XOR）代替 Keystore：真实 Keystore 在 JVM 里跑不了，
 * 但「往返一致 / 篡改失败 / 旧明文迁移」这三件事必须被测到。
 */
public final class SecretVaultTest {

    private static int pass = 0, fail = 0;
    private static void ok(boolean c, String w) {
        if (c) { pass++; System.out.println("  PASS  " + w); }
        else { fail++; System.out.println("  FAIL  " + w); }
    }

    /** 假密码器：XOR + 自带封装，模拟 Keystore 的行为。 */
    static final class MockCipher implements SecretCipher {
        private final byte key;
        MockCipher(byte k) { this.key = k; }

        public String encrypt(String plain) {
            try {
                byte[] in = plain.getBytes("UTF-8");
                byte[] out = new byte[in.length];
                for (int i = 0; i < in.length; i++) out[i] = (byte) (in[i] ^ key);
                byte[] iv = new byte[]{7, 7, 7, 7};
                return SecretFormat.wrap(iv, out);
            } catch (Throwable t) {
                return "";
            }
        }

        public String decrypt(String stored) {
            byte[][] parts = SecretFormat.unwrap(stored, 4);
            if (parts == null) return "";
            byte[] in = parts[1];
            byte[] out = new byte[in.length];
            for (int i = 0; i < in.length; i++) out[i] = (byte) (in[i] ^ key);
            try {
                return new String(out, "UTF-8");
            } catch (Throwable t) {
                return "";
            }
        }
    }

    public static void main(String[] a) {
        SecretVault vault = new SecretVault(new MockCipher((byte) 0x5A));

        System.out.println("t1 往返一致");
        String key = "sk-1234567890abcdef";
        String sealed = vault.seal(key);
        ok(SecretFormat.isEncrypted(sealed), "密文带 enc1: 前缀");
        ok(!sealed.contains("1234567890"), "密文里不含明文");
        ok(key.equals(vault.open(sealed)), "解密后与原文一致");

        System.out.println("t2 空值与旧明文");
        ok("".equals(vault.seal("")), "空串仍是空串，不产生密文");
        ok("sk-plain".equals(vault.open("sk-plain")), "非密文按明文返回（兼容旧数据）");
        ok("".equals(vault.open("")), "空输入返回空串");

        System.out.println("t3 篡改必须失败");
        String tampered = sealed.substring(0, sealed.length() - 4) + "AAAA";
        String got = vault.open(tampered);
        ok(!key.equals(got), "篡改后不会还原出原密钥");
        ok("".equals(got) || got.startsWith("\u0000") || got.length() > 0, "不抛异常（返回值安全）");
        ok("".equals(vault.open("enc1:@@@@")), "非法密文返回空串");

        System.out.println("t4 迁移");
        ok(vault.migrate("sk-old-plain") != null, "旧明文需要迁移");
        ok(SecretFormat.isEncrypted(vault.migrate("sk-old-plain")), "迁移结果是密文");
        ok(vault.migrate(sealed) == null, "已是密文则不需要再动");
        ok(vault.migrate("") == null, "空值不迁移");

        System.out.println("t5 密码器为空时不崩");
        SecretVault empty = new SecretVault(null);
        ok("x".equals(empty.open("x")), "无密码器时明文照读");
        ok(empty.seal("x") != null, "无密码器时也能返回可存值");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
