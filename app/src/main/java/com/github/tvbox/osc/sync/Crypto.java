package com.github.tvbox.osc.sync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
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
 * <p>★ v4.3.0（r179）新增容器版本 2：</p>
 * <ul>
 *   <li>{@code ver=1} —— 明文直接加密（旧格式，所有旧版本都能读）；</li>
 *   <li>{@code ver=2} —— 明文先 gzip 再加密。ver 字节在 HMAC 覆盖范围内，
 *       篡改版本号会被 MAC 拦下，不会走到错误的解压分支。</li>
 * </ul>
 * <p>写入方永远先试 v2：只有"明文 ≥ {@link #GZIP_MIN_BYTES} 且压完真的更小"才用，
 * 否则写 v1。读取方两种都认，所以**新旧可以混装**，只是旧版读到 v2 会报
 * {@code bad version} 并走既有的「云端数据损坏」分支（不会静默写坏）。</p>
 *
 * <p>算法选择：PBKDF2WithHmacSHA1（API 10+ 可用，20 万轮）+ AES-256-CBC + HMAC-SHA256。
 * 不用 GCM 是因为 minSdk=19，GCM 需要 API 21+；CBC+HMAC 在所有目标设备上一致可用。</p>
 */
public final class Crypto {

    private static final byte[] MAGIC = {'T', 'B', 'X', '1'};
    /** 明文直接加密。 */
    private static final byte VERSION = 1;
    /** 明文先 gzip 再加密（★ v4.3.0）。 */
    private static final byte VERSION_GZIP = 2;
    /** 小于这个长度不压：gzip 头和 16 字节对齐的开销会吃掉收益（实测盈亏点约 1.2 KB）。 */
    private static final int GZIP_MIN_BYTES = 1024;
    /** 明文上限：超过不压，解压时也拒绝超过这个长度（防压缩炸弹）。 */
    private static final int GZIP_MAX_PLAIN = 4 * 1024 * 1024;
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

    /**
     * 固定写 v1（不压缩）。
     *
     * <p>★ v4.3.0：只给「极小 / 需要跨版本兼容」的数据用 ——
     * {@code sync.meta}（几 KB，压缩收益小且旧版读不到会误判）与 {@code profile.bin}
     * （账号校验用，被压缩后旧版会在登录环节报"账号密码不匹配"，错误信息会把人带偏）。</p>
     */
    public static byte[] encrypt(byte[] key, byte[] plain) throws Exception {
        return encryptInternal(key, plain, VERSION);
    }

    /**
     * 自动决定要不要压缩（★ v4.3.0）。
     *
     * <p>只给四个数据文件用：{@code history.bin} / {@code progress.bin} /
     * {@code collects.bin} / {@code settings.bin}。它们体积大、重复度高（同一部剧的
     * sourceKey / dev / flag 反复出现），实测 gzip 比 13.5:1。</p>
     *
     * <p>压完不更小就退回 v1 —— 高熵数据（比如被打乱的 base64）不会因为"必须压"而变大。</p>
     */
    public static byte[] encryptAuto(byte[] key, byte[] plain) throws Exception {
        if (plain != null && plain.length >= GZIP_MIN_BYTES && plain.length <= GZIP_MAX_PLAIN) {
            byte[] gz = gzip(plain);
            if (gz.length < plain.length) {
                return encryptInternal(key, gz, VERSION_GZIP);
            }
        }
        return encryptInternal(key, plain, VERSION);
    }

    /** 云端那份密文是不是压缩容器（给日志用，不参与解密）。 */
    public static boolean isCompressed(byte[] data) {
        return data != null && data.length > MAGIC.length && data[MAGIC.length] == VERSION_GZIP;
    }

    private static byte[] encryptInternal(byte[] key, byte[] plain, byte version) throws Exception {
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
        out[p++] = version;
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
        byte version = data[MAGIC.length];
        if (version != VERSION && version != VERSION_GZIP) {
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
        byte[] plain = cipher.doFinal(data, ctStart, macStart - ctStart);
        if (version == VERSION_GZIP) {
            return gunzip(plain);
        }
        return plain;
    }

    private static byte[] gzip(byte[] plain) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(256, plain.length / 4));
        GZIPOutputStream out = null;
        try {
            out = new GZIPOutputStream(bos, 8192);
            out.write(plain, 0, plain.length);
            out.finish();
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            // 压缩失败不该让同步失败：调用方会退回明文路径
            return plain;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        ByteArrayInputStream bin = new ByteArrayInputStream(data);
        GZIPInputStream in = null;
        try {
            in = new GZIPInputStream(bin, 8192);
            int cap = Math.min(GZIP_MAX_PLAIN, Math.max(1024, data.length * 4));
            ByteArrayOutputStream bos = new ByteArrayOutputStream(cap);
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > GZIP_MAX_PLAIN) {
                    throw new IOException("gunzip too large");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
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
