package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

/**
 * 当前账号上下文（游客 / 已登录账号）。
 *
 * <p>账号数据物理隔离：每个账号一个独立数据库文件（后缀 = ownerKey），
 * 切换账号 = 关闭旧库 + 换文件名重开，不需要改表结构或做数据迁移。</p>
 *
 * <p>登录态跨重启保持：派生密钥（hex）与账号名写入 Hawk，重启后仍保持登录；
 * 主动「退出登录」或「切换账号」会清除，需要重新输密码或扫码。</p>
 */
public final class AccountContext {

    private static String ownerKey = "";
    private static String displayName = "";
    private static byte[] key;
    private static boolean loaded;

    private AccountContext() {
    }

    /** App 启动时调用一次：恢复登录态并切到对应数据库。 */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            String json = Hawk.get(HawkConfig.SYNC_ACCOUNT, "");
            if (json != null && json.length() > 0) {
                JSONObject o = new JSONObject(json);
                byte[] kb = hexToBytes(o.optString("keyHex", ""));
                if (kb != null && kb.length == 64) {
                    ownerKey = o.optString("owner", "");
                    displayName = o.optString("name", "");
                    key = kb;
                }
            }
        } catch (Throwable ignored) {
        }
        AppDataManager.switchOwner(ownerKey);
    }

    public static String ownerKey() {
        return ownerKey;
    }

    public static String displayName() {
        return displayName;
    }

    public static boolean isGuest() {
        return ownerKey == null || ownerKey.length() == 0;
    }

    public static byte[] key() {
        return key;
    }

    /** 登录（ownerKey 由 {@link Crypto#userKey(String)} 生成）。 */
    public static synchronized void login(String owner, String name, byte[] derivedKey) {
        ownerKey = owner;
        displayName = name;
        key = derivedKey;
        try {
            JSONObject o = new JSONObject();
            o.put("owner", owner);
            o.put("name", name);
            o.put("keyHex", bytesToHex(derivedKey));
            Hawk.put(HawkConfig.SYNC_ACCOUNT, o.toString());
        } catch (Throwable ignored) {
        }
        AppDataManager.switchOwner(ownerKey);
    }

    /** 退出登录：清除账号与密钥，回到游客数据库。 */
    public static synchronized void logout() {
        ownerKey = "";
        displayName = "";
        key = null;
        Hawk.delete(HawkConfig.SYNC_ACCOUNT);
        AppDataManager.switchOwner("");
    }

    static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return null;
        }
        try {
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    static String bytesToHex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
