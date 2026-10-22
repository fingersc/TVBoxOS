package com.github.tvbox.osc.sync;

/**
 * 账号目录约定：账号文件夹直接放在「存储根目录」下。
 *
 * <pre>
 * &lt;存储根目录&gt;/&lt;账号名&gt;/profile.json
 * &lt;存储根目录&gt;/&lt;账号名&gt;/data.bin
 * </pre>
 *
 * <p>账号由管理员在网盘里建空文件夹产生，App 不提供注册入口；
 * 首次登录时若文件夹存在但没有 profile.json，则视为「激活」。</p>
 */
public final class AccountPaths {

    /** 最短密码长度（用户确认：6 位）。 */
    public static final int MIN_PASSWORD = 6;

    private AccountPaths() {
    }

    /** 账号名规范化：去首尾空白、连续空白压成一个空格、统一小写（中文不受影响）。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replaceAll("\\s+", " ");
        return s.toLowerCase();
    }

    /** 账号名是否合法：1~24 字符，且不含路径/文件系统禁用字符。 */
    public static boolean isValidName(String name) {
        if (name == null) {
            return false;
        }
        String n = normalize(name);
        if (n.length() == 0 || n.length() > 24) {
            return false;
        }
        if (n.equals(".") || n.equals("..")) {
            return false;
        }
        return !n.matches(".*[\\\\/:*?\"<>|].*");
    }

    public static String dir(String name) {
        return normalize(name) + "/";
    }

    public static String profile(String name) {
        return normalize(name) + "/profile.json";
    }

    public static String data(String name) {
        return normalize(name) + "/data.bin";
    }

    /**
     * ★ v99-r8：按 scope 分文件后的云端路径。
     *
     * <p>schema 3 起，每个 scope 一个文件（history.bin / progress.bin /
     * collects.bin / settings.bin），各自独立 ETag，只上传脏的那几个。
     * 旧的单文件 {@link #data(String)} 仅用于一次性迁移读取。</p>
     */
    public static String scopeFile(String name, com.github.tvbox.osc.sync.SyncScope scope) {
        return normalize(name) + "/" + scope.fileName;
    }
}
