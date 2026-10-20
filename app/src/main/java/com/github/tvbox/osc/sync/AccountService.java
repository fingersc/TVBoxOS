package com.github.tvbox.osc.sync;

import com.google.gson.Gson;

import org.json.JSONObject;

/**
 * 账号校验：目录是否存在 + 密码是否正确 + 首次激活。
 *
 * <p>账号由管理员在网盘根目录下建空文件夹产生；App <b>不会</b>自动创建目录。
 * 目录存在但没有 profile.json 时，视为首次激活（需二次输入密码）。</p>
 */
public final class AccountService {

    public static final int OK = 0;
    public static final int NEED_ACTIVATE = 1;
    public static final int NOT_FOUND = 2;
    public static final int WRONG_PASSWORD = 3;

    private static final Gson GSON = new Gson();

    public static class Result {
        public final int status;
        public final String message;

        Result(int status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    private AccountService() {
    }

    /** 登录前校验：先看目录，再看 profile 的 verify，最后兜底尝试解密 data.bin。 */
    public static Result check(WebDavClient dav, String name, byte[] key) throws Exception {
        if (!dav.dirExists(AccountPaths.dir(name))) {
            return new Result(NOT_FOUND, "账号不存在");
        }
        byte[] profile = dav.get(AccountPaths.profile(name), null);
        if (profile != null && profile.length > 0) {
            String expected = "";
            try {
                expected = new JSONObject(new String(profile, "UTF-8")).optString("verify", "");
            } catch (Throwable ignored) {
            }
            if (expected.length() == 0) {
                return verifyByData(dav, name, key);
            }
            String actual = Crypto.accountVerify(key);
            if (actual.length() > 0 && actual.equalsIgnoreCase(expected)) {
                return new Result(OK, "登录成功");
            }
            return new Result(WRONG_PASSWORD, "密码不正确");
        }
        // 没有 profile：可能还没激活，也可能是残留
        byte[] data = dav.get(AccountPaths.data(name), null);
        if (data == null || data.length == 0) {
            return new Result(NEED_ACTIVATE, "首次使用，请再次输入密码以激活账号");
        }
        return verifyByData(dav, name, key);
    }

    private static Result verifyByData(WebDavClient dav, String name, byte[] key) throws Exception {
        byte[] data = dav.get(AccountPaths.data(name), null);
        if (data == null || data.length == 0) {
            return new Result(NEED_ACTIVATE, "首次使用，请再次输入密码以激活账号");
        }
        try {
            Crypto.decrypt(key, data);
            return new Result(OK, "登录成功");
        } catch (Throwable t) {
            return new Result(WRONG_PASSWORD, "密码不正确");
        }
    }

    /** 首次激活：写 profile.json + 空的数据包。 */
    public static void activate(WebDavClient dav, String name, byte[] key, String deviceId) throws Exception {
        String normalized = AccountPaths.normalize(name);
        JSONObject profile = new JSONObject();
        profile.put("schema", 1);
        profile.put("name", normalized);
        profile.put("createdAt", System.currentTimeMillis());
        profile.put("kdf", "pbkdf2-sha1-200k");
        profile.put("verify", Crypto.accountVerify(key));
        dav.put(AccountPaths.profile(normalized), profile.toString().getBytes("UTF-8"), null);

        SyncPayload empty = new SyncPayload();
        empty.owner = Crypto.userKey(normalized);
        empty.epoch = 0;
        empty.updatedAt = System.currentTimeMillis();
        empty.dev = deviceId;
        byte[] encrypted = Crypto.encrypt(key, GSON.toJson(empty).getBytes("UTF-8"));
        dav.put(AccountPaths.data(normalized), encrypted, null);
    }

    /** 同步前确保 profile 存在（老数据/手动搬移时补写）。 */
    public static void ensureProfile(WebDavClient dav, String name, byte[] key) {
        try {
            if (dav.get(AccountPaths.profile(name), null) != null) {
                return;
            }
            JSONObject profile = new JSONObject();
            profile.put("schema", 1);
            profile.put("name", AccountPaths.normalize(name));
            profile.put("createdAt", System.currentTimeMillis());
            profile.put("kdf", "pbkdf2-sha1-200k");
            profile.put("verify", Crypto.accountVerify(key));
            dav.put(AccountPaths.profile(AccountPaths.normalize(name)),
                    profile.toString().getBytes("UTF-8"), null);
        } catch (Throwable ignored) {
        }
    }
}
