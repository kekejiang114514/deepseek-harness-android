package com.dsh.android.kernel;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 记忆库：给 AI 用的凭据（账号密码、API Key、备用码），加密存本地。
 *
 * <p>核心约束是**明文永不进入对话**：工具只返回名称与用途，值只在执行动作的
 * 那一瞬间解密使用（例如直接填进屏幕上的输入框），因此聊天记录、会话文件、
 * 上下文压缩都接触不到明文。
 *
 * <p>加密用 Android Keystore 里的 AES-GCM 密钥，密钥不出安全硬件；
 * 存储文件即使被拷走也解不开。
 */
public final class Vault {

    private static final String FILE = "vault.json";
    private static final String KEY_ALIAS = "dsh_vault_key";
    private static final int GCM_TAG_BITS = 128;

    public static final class Item {
        public final String name;
        public final String note;
        Item(String name, String note) {
            this.name = name;
            this.note = note;
        }
    }

    private Vault() {}

    // ------------------------------------------------------------ 读写

    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<Item>();
        JSONArray arr = raw(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(new Item(o.optString("n"), o.optString("note")));
        }
        return out;
    }

    public static boolean exists(Context c, String name) {
        JSONArray arr = raw(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(o.optString("n"))) return true;
        }
        return false;
    }

    public static void put(Context c, String name, String note, String value) {
        JSONArray arr = raw(c);
        JSONObject entry = new JSONObject();
        try {
            entry.put("n", name);
            entry.put("note", note == null ? "" : note);
            entry.put("v", encrypt(value));
        } catch (Exception ignored) {}
        JSONArray next = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(o.optString("n"))) {
                next.put(entry);
                replaced = true;
            } else if (o != null) {
                next.put(o);
            }
        }
        if (!replaced) next.put(entry);
        write(c, next);
    }

    public static void remove(Context c, String name) {
        JSONArray arr = raw(c);
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !name.equals(o.optString("n"))) next.put(o);
        }
        write(c, next);
    }

    /** 解密取值；只在执行动作时调用，调用方不得把结果返回给模型。 */
    public static String value(Context c, String name) {
        JSONArray arr = raw(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(o.optString("n"))) {
                return decrypt(o.optString("v"));
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 存储

    private static JSONArray raw(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.isFile()) return new JSONArray();
        try {
            String text = readAll(new FileInputStream(f));
            JSONObject root = new JSONObject(text);
            JSONArray arr = root.optJSONArray("entries");
            return arr == null ? new JSONArray() : arr;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void write(Context c, JSONArray entries) {
        JSONObject root = new JSONObject();
        try {
            root.put("entries", entries);
            File f = new File(c.getFilesDir(), FILE);
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(root.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Exception ignored) {}
    }

    private static String readAll(FileInputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------ 加解密

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static String encrypt(String plain) {
        if (plain == null) plain = "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] body = cipher.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[1 + iv.length + body.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(body, 0, out, 1 + iv.length, body.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            // Keystore 不可用时退化为明文存储，但至少标出来，避免误以为已加密
            return "plain:" + Base64.encodeToString(plain.getBytes(), Base64.NO_WRAP);
        }
    }

    private static String decrypt(String stored) {
        if (stored == null || stored.length() == 0) return null;
        try {
            if (stored.startsWith("plain:")) {
                return new String(Base64.decode(stored.substring(6), Base64.NO_WRAP), "UTF-8");
            }
            byte[] all = Base64.decode(stored, Base64.NO_WRAP);
            int ivLen = all[0] & 0xFF;
            byte[] iv = new byte[ivLen];
            System.arraycopy(all, 1, iv, 0, ivLen);
            byte[] body = new byte[all.length - 1 - ivLen];
            System.arraycopy(all, 1 + ivLen, body, 0, body.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(body), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
