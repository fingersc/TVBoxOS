package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

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

    /**
     * 首次激活：只写 profile.json。
     *
     * <p>★ v99-r81：不再写空的 data.bin —— 否则另一台设备首次登录时，
     * {@link SyncEngine} 的 migrateLegacy 会把这个空 data.bin 当「旧格式」迁移，
     * 用空包 PUT 覆盖 4 个 scope 文件（激活设备刚上传的真实数据被清掉），
     * 并把空文件的 ETag 存进本机 ⇒ 当次同步全部 304，一台数据都拉不到。
     * 新账号云端只有 profile.json ⇒ migrateLegacy 读不到 data.bin 自然跳过。</p>
     *
     * @param deviceId 保留参数（调用方已传）；data.bin 取消后暂无用途
     */
    public static void activate(WebDavClient dav, String name, byte[] key, String deviceId) throws Exception {
        String normalized = AccountPaths.normalize(name);
        JSONObject profile = new JSONObject();
        profile.put("schema", 1);
        profile.put("name", normalized);
        profile.put("createdAt", System.currentTimeMillis());
        profile.put("kdf", "pbkdf2-sha1-200k");
        profile.put("verify", Crypto.accountVerify(key));
        dav.put(AccountPaths.profile(normalized), profile.toString().getBytes("UTF-8"), null);
    }

    /** 同步前确保 profile 存在（老数据/手动搬移时补写）。 */
    public static void ensureProfile(WebDavClient dav, String name, byte[] key) {
        String normalized = AccountPaths.normalize(name);
        String cacheKey = HawkConfig.PROFILE_OK_PREFIX + normalized;
        // v78：本机已确认过 profile 存在就不再每次 GET（省 1 个请求）
        if (Hawk.get(cacheKey, false)) {
            return;
        }
        try {
            if (dav.get(AccountPaths.profile(normalized), null) != null) {
                Hawk.put(cacheKey, true);
                return;
            }
            JSONObject profile = new JSONObject();
            profile.put("schema", 1);
            profile.put("name", AccountPaths.normalize(name));
            profile.put("createdAt", System.currentTimeMillis());
            profile.put("kdf", "pbkdf2-sha1-200k");
            profile.put("verify", Crypto.accountVerify(key));
            dav.put(AccountPaths.profile(normalized),
                    profile.toString().getBytes("UTF-8"), null);
            Hawk.put(cacheKey, true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * ★ v99-r14：只用 {@code profile.json} 复核密码是否正确。
     *
     * <p><b>为什么需要这个独立通道：</b></p>
     *
     * <ul>
     *   <li>{@code profile.json} 是<b>明文 JSON</b>，其中的 {@code verify} 字段是
     *       {@link Crypto#accountVerify(byte[])}（由密钥派生的 HMAC）的 hex。
     *       校验方式就是「本地算一遍、和明文里的字符串比」，<b>完全不解密</b>。</li>
     *   <li>它由 {@link #activate} 在首次激活时写一次，之后<b>永不再上传</b>
     *       （{@link #ensureProfile} 只在文件缺失时才补写）——
     *       所以它是一条稳定、独立于数据文件的密码校验通道。</li>
     *   <li>相比之下，4 个 scope 文件（progress/history/collect/settings {@code .bin}）
     *       才是 {@link Crypto#encrypt} 加密的，它们解密失败<b>不能</b>推断密码错误。</li>
     * </ul>
     *
     * <p><b>用途：</b>同步时某个 scope 文件解密失败，先用本方法复核 ——
     * 复核通过说明密钥是对的，那失败原因只能是<b>云端文件损坏</b>（服务端部分写入 /
     * 磁盘故障 / 被人手动改过），应当用本机数据重传修复；
     * 复核失败才是真的密钥不匹配（账号被管理员重置、用别的密码登了同名目录）。</p>
     *
     * @return true = 密码/密钥正确；false = 不匹配，或 profile 缺失/不可读（无法证明正确）
     */
    public static boolean verifyPassword(WebDavClient dav, String name, byte[] key) {
        if (dav == null || key == null || key.length == 0) {
            return false;
        }
        try {
            byte[] profile = dav.get(AccountPaths.profile(name), null);
            if (profile == null || profile.length == 0) {
                // 没有 profile ⇒ 无法证明密钥正确，按「不匹配」处理（调用方不会去覆盖云端）
                return false;
            }
            String expected = new JSONObject(new String(profile, "UTF-8")).optString("verify", "");
            if (expected.length() == 0) {
                return false;
            }
            String actual = Crypto.accountVerify(key);
            return actual.length() > 0 && actual.equalsIgnoreCase(expected);
        } catch (Throwable t) {
            // 读 profile 本身就失败（网络/认证）⇒ 同样无法证明正确
            return false;
        }
    }
}
