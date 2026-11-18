package com.dsh.android.kernel;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * API Key 的本地加密。
 *
 * <p>用的是系统 Keystore：密钥材料由系统保管、不可导出，应用只能调用它
 * 做加解密。所以即使把偏好文件拷走，里面也只有密文。
 *
 * <p>没有引入任何第三方库，全部走 framework API。
 */
public final class SecureStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "dsh_api_key_v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private SecureStore() {}

    /** 明文 → 可存字符串；失败时原样返回（宁可存明文，也不能丢配置）。 */
    public static String encrypt(String plain) {
        if (plain == null || plain.length() == 0) return "";
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key());
            return SecretFormat.wrap(c.getIV(), c.doFinal(plain.getBytes("UTF-8")));
        } catch (Throwable t) {
            return plain;
        }
    }

    /** 密文 → 明文；非密文（旧数据）原样返回。 */
    public static String decrypt(String stored) {
        if (!SecretFormat.isEncrypted(stored)) return stored == null ? "" : stored;
        byte[][] parts = SecretFormat.unwrap(stored, IV_LEN);
        if (parts == null) return "";
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, parts[0]));
            return new String(c.doFinal(parts[1]), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 读 API Key（自动解密）。 */
    public static String readKey(SharedPreferences sp) {
        if (sp == null) return "";
        return decrypt(sp.getString(com.dsh.android.kernel.C.KEY_API_KEY, ""));
    }

    /** 把明文加密后放进 editor；调用方负责 commit / apply。 */
    public static void putKey(SharedPreferences.Editor e, String plain) {
        if (e == null) return;
        e.putString(com.dsh.android.kernel.C.KEY_API_KEY, encrypt(plain == null ? "" : plain));
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        java.security.Key existing = ks.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
