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
    /** v79：这次扫码期望登录的账号（切换指定账号时设置），null = 不校验 */
    private static volatile String expectedOwner;
    private static final List<Listener> LISTENERS = new ArrayList<>();

    /** 扫码登录状态回调（弹窗/账号页据此更新提示）。 */
    public interface Listener {
        /** 已受理，正在后台校验存储并切换账号。 */
        void onLoginPending();

        /** 登录成功。 */
        void onLoginOk(String name);

        /** 登录失败（校验存储或切换账号失败）。 */
        void onLoginFailed(String message);
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

    private static List<Listener> copyListeners() {
        synchronized (LISTENERS) {
            return new ArrayList<>(LISTENERS);
        }
    }

    private static void notifyLoginOk(String name) {
        for (Listener l : copyListeners()) {
            try {
                l.onLoginOk(name);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void notifyLoginPending() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : copyListeners()) {
                    try {
                        l.onLoginPending();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private static void notifyLoginFailed(final String message) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : copyListeners()) {
                    try {
                        l.onLoginFailed(message);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
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

    /** v79：切换指定账号时，要求手机回传的就是这个账号。 */
    public static void setExpectedOwner(String owner) {
        expectedOwner = owner;
    }

    public static void clearExpectedOwner() {
        expectedOwner = null;
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
     * @return ok / expired / bad / mismatch
     */
    public static String accept(String t, String hexCipher) {
        final long tAccept = System.currentTimeMillis();
        if (!isValid(t)) {
            return "expired";
        }
        final String url;
        final String davUser;
        final String davPass;
        final String owner;
        final String name;
        final byte[] accountKey;
        try {
            byte[] key = Crypto.deriveKey(t, "tvbox-qr-login");
            byte[] cipher = hexToBytes(hexCipher);
            String json = new String(Crypto.decrypt(key, cipher), "UTF-8");
            JSONObject o = new JSONObject(json);
            url = o.optString("url", "");
            davUser = o.optString("davUser", "");
            davPass = o.optString("davPass", "");
            owner = o.optString("owner", "");
            name = o.optString("name", "");
            String keyHex = o.optString("keyHex", "");
            accountKey = hexToBytes(keyHex);
            if (url.length() == 0 || owner.length() == 0 || name.length() == 0
                    || accountKey == null || accountKey.length != 64) {
                return "bad";
            }
            // v79：切换指定账号时校验目标，避免"点了张三却登成手机上的李四"
            if (expectedOwner != null && !expectedOwner.equals(owner)) {
                return "mismatch";
            }
        } catch (Throwable t2) {
            LOG.e("[Sync] qr login failed", t2);
            return "bad";
        }
        // r195：本地校验一过就原子消费 token 并立刻回执；耗时的网盘校验/账号切换放后台。
        // 否则电视端 PROPFIND 可能耗时 10~30s，手机 8s 读超时先报「发送失败」，随后电视又登录成功。
        synchronized (QrLoginBridge.class) {
            if (!isValid(t)) {
                return "expired";
            }
            token = null;
            expectedOwner = null;
        }
        notifyLoginPending();
        new Thread(new Runnable() {
            @Override
            public void run() {
                verifyAndSwitch(url, davUser, davPass, owner, name, accountKey);
            }
        }, "tvbox-qr-verify").start();
        LOG.i("[Sync] qr login accepted user=" + name
                + " (crypto+check " + (System.currentTimeMillis() - tAccept) + "ms)");
        return "ok";
    }

    /** 后台校验存储并切换账号：耗时操作都在这里，不阻塞 /qrlogin 的 HTTP 响应。 */
    private static void verifyAndSwitch(String url, String davUser, String davPass,
                                        final String owner, final String name, final byte[] accountKey) {
        try {
            // v78：本机已配置存储时以本机为准，避免扫码把整台设备的网盘换掉（其它账号会瞬间"找不到"）。
            // 仅当本机还没配置存储时，才采用手机带来的配置（相当于扫码顺手完成存储配置），
            // 且校验通过后才真正写入 —— 避免校验失败却把全局存储配置改掉。
            String localUrl = Hawk.get(HawkConfig.SYNC_WEBDAV_URL, "");
            boolean usePhoneConfig = localUrl == null || localUrl.isEmpty();
            String useUrl = usePhoneConfig ? url : localUrl;
            String useUser = usePhoneConfig ? davUser : Hawk.get(HawkConfig.SYNC_WEBDAV_USER, "");
            String usePass = usePhoneConfig ? davPass : Hawk.get(HawkConfig.SYNC_WEBDAV_PASS, "");
            // 用"生效的存储"确认账号目录存在，避免切到一个不存在的账号上
            WebDavClient dav = new WebDavClient(useUrl, useUser, usePass);
            long tDir = System.currentTimeMillis();
            boolean exists = dav.dirExists(AccountPaths.dir(name));
            LOG.i("[Sync] qr dirExists=" + exists + " (" + (System.currentTimeMillis() - tDir) + "ms)");
            if (!exists) {
                notifyLoginFailed("电视上没有这个账号：请确认电视与本机用的是同一份网盘");
                return;
            }
            if (usePhoneConfig) {
                Hawk.put(HawkConfig.SYNC_WEBDAV_URL, url);
                Hawk.put(HawkConfig.SYNC_WEBDAV_USER, davUser);
                Hawk.put(HawkConfig.SYNC_WEBDAV_PASS, davPass);
            }
        } catch (Throwable t3) {
            LOG.e("[Sync] qr storage check failed", t3);
            String m = t3.getMessage();
            notifyLoginFailed("校验存储失败：" + (m == null || m.isEmpty()
                    ? t3.getClass().getSimpleName() : m));
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                // v76：统一走账号切换编排（保存当前设置快照 / 切库 / 恢复目标快照 / 拉一次）
                final long tSwitch = System.currentTimeMillis();
                // r197：扫码路径不等首轮同步——切换完成即回「已登录」，同步后台继续（false = 不等同步）
                AccountSwitcher.switchTo(owner, name, accountKey, false, new AccountSwitcher.Callback() {
                    @Override
                    public void onDone(boolean ok, String message) {
                        LOG.i("[Sync] qr switch done ok=" + ok + " ("
                                + (System.currentTimeMillis() - tSwitch) + "ms)");
                        if (ok) {
                            notifyLoginOk(name);
                        } else {
                            notifyLoginFailed(message == null || message.isEmpty()
                                    ? "切换账号失败" : message);
                        }
                    }
                });
            }
        });
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
