package com.dsh.android.kernel;

/**
 * 加解密接口。
 *
 * <p>抽出接口的目的很实际：Keystore 在纯 JVM 里跑不了，
 * 而「往返一致、篡改要失败、旧明文要迁移」这三件事必须能测。
 */
public interface SecretCipher {

    /** 明文 → 可存字符串；空串原样返回。 */
    String encrypt(String plain);

    /** 可存字符串 → 明文；解不开时返回空串（不返回密文，避免把密文当密钥发出去）。 */
    String decrypt(String stored);
}
