package com.dsh.android.kernel;

/**
 * 密钥在偏好里的封装格式：前缀 + Base64(iv || 密文)。
 *
 * <p>自带一份 Base64 实现，不依赖 android.util.Base64，
 * 于是这个类可以在纯 JVM 测试里跑（Keystore 部分在 {@link SecureStore}）。
 */
public final class SecretFormat {

    /** 密文前缀；没有这个前缀的值一律当明文处理（兼容旧数据）。 */
    public static final String PREFIX = "enc1:";

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private SecretFormat() {}

    /** 把 iv 与密文拼成一个可存字符串。 */
    public static String wrap(byte[] iv, byte[] cipherText) {
        if (iv == null || cipherText == null) return "";
        byte[] all = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(cipherText, 0, all, iv.length, cipherText.length);
        return PREFIX + encode(all);
    }

    /** 拆出 iv 与密文；格式不对返回 null。 */
    public static byte[][] unwrap(String stored, int ivLen) {
        if (!isEncrypted(stored)) return null;
        try {
            byte[] all = decode(stored.substring(PREFIX.length()));
            if (all == null || all.length <= ivLen) return null;
            byte[] iv = new byte[ivLen];
            byte[] ct = new byte[all.length - ivLen];
            System.arraycopy(all, 0, iv, 0, ivLen);
            System.arraycopy(all, ivLen, ct, 0, ct.length);
            return new byte[][]{iv, ct};
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    // ---------------------------------------------------------- Base64

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(ALPHABET.charAt((n >> 18) & 63));
            sb.append(ALPHABET.charAt((n >> 12) & 63));
            sb.append(ALPHABET.charAt((n >> 6) & 63));
            sb.append(ALPHABET.charAt(n & 63));
            i += 3;
        }
        int left = data.length - i;
        if (left == 1) {
            int n = (data[i] & 0xFF) << 16;
            sb.append(ALPHABET.charAt((n >> 18) & 63));
            sb.append(ALPHABET.charAt((n >> 12) & 63));
            sb.append("==");
        } else if (left == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(ALPHABET.charAt((n >> 18) & 63));
            sb.append(ALPHABET.charAt((n >> 12) & 63));
            sb.append(ALPHABET.charAt((n >> 6) & 63));
            sb.append('=');
        }
        return sb.toString();
    }

    public static byte[] decode(String s) {
        if (s == null) return null;
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue;
            if (ALPHABET.indexOf(c) < 0) return null;
            clean.append(c);
        }
        int len = clean.length();
        if (len % 4 == 1) return null;
        int outLen = len * 3 / 4;
        byte[] out = new byte[outLen];
        int oi = 0, buf = 0, bits = 0;
        for (int i = 0; i < len; i++) {
            buf = (buf << 6) | ALPHABET.indexOf(clean.charAt(i));
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[oi++] = (byte) ((buf >> bits) & 0xFF);
            }
        }
        if (oi == out.length) return out;
        byte[] trimmed = new byte[oi];
        System.arraycopy(out, 0, trimmed, 0, oi);
        return trimmed;
    }
}
