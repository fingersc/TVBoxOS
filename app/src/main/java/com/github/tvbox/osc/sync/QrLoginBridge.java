package com.github.tvbox.osc.sync;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 扫码登录的中转：电视生成一次性 token 并展示二维码，手机把加密后的
 * 「存储配置 + 账号密码」POST 回来。
 *
 * <p>没有后端，靠同一局域网直连电视内置 HTTP 服务（NanoHTTPD）。
 * 安全：token 8 位随机、5 分钟过期、使用一次即作废；请求体用 token 派生密钥加密。</p>
 */
public final class QrLoginBridge {

    private static final long TTL_MS = 5 * 60 * 1000L;
    private static String token;
    private static long expireAt;
    private static final List<Listener> LISTENERS = new ArrayList<>();

    /** 扫码登录成功回调（弹窗据此自动关闭并提示）。 */
    public interface Listener {
        void onLoginOk(String name);
    }

    public static void addListener(Listener l) {
        if (l == null) {
            return;
        }
        synchronized (LISTENERS) {
            if (!LISTENERS.contains(l)) {
                LISTENERS.add(l);
            }
        }
    }

    public static void removeListener(Listener l) {
        synchronized (LISTENERS) {
            LISTENERS.remove(l);
        }
    }

    private static void notifyLoginOk(String name) {
        List<Listener> copy;
        synchronized (LISTENERS) {
            copy = new ArrayList<>(LISTENERS);
        }
        for (Listener l : copy) {
            try {
                l.onLoginOk(name);
            } catch (Throwable ignored) {
            }
        }
    }

    private QrLoginBridge() {
    }

    /** 生成一次性 token（同时作废旧 token）。 */
    public static synchronized String newToken() {
        byte[] r = new byte[4];
        new SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder();
        for (byte b : r) {
            sb.append(String.format("%02x", b & 0xff));
        }
        token = sb.toString();
        expireAt = System.currentTimeMillis() + TTL_MS;
        return token;
    }

    public static synchronized boolean isValid(String t) {
        return t != null && token != null && t.equals(token) && System.currentTimeMillis() < expireAt;
    }

    /**
     * 处理手机 POST 上来的密文（hex）。
     *
     * <p>传的是「已派生的账号密钥」而不是密码：手机端本来就不保存密码，
     * 密钥正是 AccountContext 保存的登录凭据，电视拿到即可保持登录。</p>
     *
     * @return ok / expired / bad
     */
    public static String accept(String t, String hexCipher) {
        if (!isValid(t)) {
            return "expired";
        }
        try {
            byte[] key = Crypto.deriveKey(t, "tvbox-qr-login");
            byte[] cipher = hexToBytes(hexCipher);
            String json = new String(Crypto.decrypt(key, cipher), "UTF-8");
            JSONObject o = new JSONObject(json);
            final String url = o.optString("url", "");
            final String davUser = o.optString("davUser", "");
            final String davPass = o.optString("davPass", "");
            final String owner = o.optString("owner", "");
            final String name = o.optString("name", "");
            final String keyHex = o.optString("keyHex", "");
            final byte[] accountKey = hexToBytes(keyHex);
            if (url.length() == 0 || owner.length() == 0 || name.length() == 0
                    || accountKey == null || accountKey.length != 64) {
                return "bad";
            }
            Hawk.put(HawkConfig.SYNC_WEBDAV_URL, url);
            Hawk.put(HawkConfig.SYNC_WEBDAV_USER, davUser);
            Hawk.put(HawkConfig.SYNC_WEBDAV_PASS, davPass);
            token = null;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    // v76：统一走账号切换编排（保存当前设置快照 / 切库 / 恢复目标快照 / 拉一次）
                    AccountSwitcher.switchTo(owner, name, accountKey, new AccountSwitcher.Callback() {
                        @Override
                        public void onDone(boolean ok, String message) {
                            if (ok) {
                                notifyLoginOk(name);
                            }
                        }
                    });
                }
            });
            LOG.i("[Sync] qr login accepted user=" + name);
            return "ok";
        } catch (Throwable t2) {
            LOG.e("[Sync] qr login failed", t2);
            return "bad";
        }
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
