package com.dsh.android.kernel;

/**
 * 密钥存取的门面逻辑（纯 JVM）。
 *
 * <p>只管三件事：加密、解密、把旧明文迁移成密文。真正的算法由
 * {@link SecretCipher} 提供，测试里可以注入假实现。
 */
public final class SecretVault {

    private final SecretCipher cipher;

    public SecretVault(SecretCipher cipher) {
        this.cipher = cipher;
    }

    /** 加密；空串保持空串（不要给空密钥生成密文）。 */
    public String seal(String plain) {
        if (plain == null || plain.length() == 0) return "";
        if (cipher == null) return plain;
        String out = cipher.encrypt(plain);
        return out == null || out.length() == 0 ? plain : out;
    }

    /**
     * 解密。
     *
     * <p>不是密文的值按明文返回（旧数据兼容）；是密文但解不开则返回空串——
     * 宁可让用户看到「没有密钥」，也不能把密文当密钥发出去。
     */
    public String open(String stored) {
        if (stored == null || stored.length() == 0) return "";
        if (!SecretFormat.isEncrypted(stored)) return stored;
        if (cipher == null) return "";
        String out = cipher.decrypt(stored);
        return out == null ? "" : out;
    }

    /**
     * 迁移：明文 → 密文；已经是密文的原样返回。
     *
     * @return 应当写回存储的值；不需要改动时返回 null
     */
    public String migrate(String stored) {
        if (stored == null || stored.length() == 0) return null;
        if (SecretFormat.isEncrypted(stored)) return null;
        return seal(stored);
    }
}
