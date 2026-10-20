package com.github.tvbox.osc.sync;

import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 同步数据加密（端到端，密钥只由「用户名 + 密码」派生，云端不存任何密码/哈希）。
 *
 * <p>格式：{@code "TBX1" | ver(1) | iv(16) | ciphertext | hmac-sha256(32)}，
 * 采用 Encrypt-then-MAC，任何篡改都会在解密前被发现。</p>
 *
 * <p>算法选择：PBKDF2WithHmacSHA1（API 10+ 可用，20 万轮）+ AES-256-CBC + HMAC-SHA256。
 * 不用 GCM 是因为 minSdk=19，GCM 需要 API 21+；CBC+HMAC 在所有目标设备上一致可用。</p>
 */
public final class Crypto {

    private static final byte[] MAGIC = {'T', 'B', 'X', '1'};
    private static final byte VERSION = 1;
    private static final int ITERATIONS = 200000;
    private static final int KEY_BYTES = 64;
    private static final int IV_BYTES = 16;
    private static final int MAC_BYTES = 32;

    private Crypto() {
    }

    /** 用户名 → 账号目录名（不可反推账号）。 */
    public static String userKey(String username) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("tvbox-sync-user:" + normalize(username)).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    /** 用户名 + 密码 → 64 字节密钥（前 32 加密、后 32 做 MAC）。 */
    public static byte[] deriveKey(String username, String password) throws Exception {
        String salt = "tvbox-sync:" + userKey(username);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes("UTF-8"),
                ITERATIONS, KEY_BYTES * 8);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        return factory.generateSecret(spec).getEncoded();
    }

    public static byte[] encrypt(byte[] key, byte[] plain) throws Exception {
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);
        byte[] encKey = slice(key, 0, 32);
        byte[] macKey = slice(key, 32, 32);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plain);

        byte[] out = new byte[MAGIC.length + 1 + IV_BYTES + ct.length + MAC_BYTES];
        int p = 0;
        System.arraycopy(MAGIC, 0, out, p, MAGIC.length);
        p += MAGIC.length;
        out[p++] = VERSION;
        System.arraycopy(iv, 0, out, p, IV_BYTES);
        p += IV_BYTES;
        System.arraycopy(ct, 0, out, p, ct.length);
        p += ct.length;
        byte[] mac = hmac(macKey, out, 0, p);
        System.arraycopy(mac, 0, out, p, MAC_BYTES);
        return out;
    }

    public static byte[] decrypt(byte[] key, byte[] data) throws Exception {
        if (data == null || data.length < MAGIC.length + 1 + IV_BYTES + MAC_BYTES) {
            throw new IllegalArgumentException("bad data");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                throw new IllegalArgumentException("bad magic");
            }
        }
        if (data[MAGIC.length] != VERSION) {
            throw new IllegalArgumentException("bad version");
        }
        int ivStart = MAGIC.length + 1;
        int ctStart = ivStart + IV_BYTES;
        int macStart = data.length - MAC_BYTES;
        if (macStart <= ctStart) {
            throw new IllegalArgumentException("bad length");
        }
        byte[] macKey = slice(key, 32, 32);
        byte[] expect = hmac(macKey, data, 0, macStart);
        if (!MessageDigest.isEqual(expect, slice(data, macStart, MAC_BYTES))) {
            throw new SecurityException("bad mac");
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(slice(key, 0, 32), "AES"),
                new IvParameterSpec(slice(data, ivStart, IV_BYTES)));
        return cipher.doFinal(data, ctStart, macStart - ctStart);
    }

    /** 账号校验值：用于在登录前判断密码是否正确（写进 profile.json，可公开）。 */
    public static String accountVerify(byte[] key) {
        try {
            byte[] mac = hmac(slice(key, 32, 32), "tvbox-account-verify".getBytes("UTF-8"), 0,
                    "tvbox-account-verify".getBytes("UTF-8").length);
            return toHex(mac);
        } catch (Throwable t) {
            return "";
        }
    }

    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] hmac(byte[] key, byte[] data, int off, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(data, off, len);
        return mac.doFinal();
    }

    private static byte[] slice(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }
}
