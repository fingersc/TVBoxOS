package com.github.tvbox.osc.util;

import android.util.Log;

import com.github.tvbox.osc.BuildConfig;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class LOG {
    private static final String TAG = "TVBox-runtime";
    private static final int MAX_LOG_LENGTH = 3000;

    /**
     * info 级日志总开关。
     *
     * <p><b>为什么需要：</b>{@link #i(String)} 有上百处调用，其中大量带字符串拼接
     * （本项目计数约 125 处形如 {@code LOG.i("xxx" + var)}）。日志输出没有门控时，
     * release 版每次都会先在调用点完成字符串拼接、再写入 logcat，
     * 这是纯粹的运行时浪费，且在搜索/播放等高频回路上会被反复放大。</p>
     *
     * <p><b>恢复方式（无需改代码、无需重编译）：</b><br/>
     * {@code adb shell setprop log.tag.TVBox-runtime V} 然后重启 App 即可重新打开。
     * 取值在类加载时确定一次，因此修改系统属性后需要重启才生效。</p>
     *
     * <p><b>不受此开关影响：</b>错误级 {@link #e(String)} / {@link #longE(String, String)}
     * 保持始终输出，以便 release 版仍能定位致命问题。</p>
     */
    private static final boolean VERBOSE = BuildConfig.DEBUG || Log.isLoggable(TAG, Log.VERBOSE);

    public static void e(String msg) {
        Log.e(TAG, "" + msg);
    }

    public static void i(String msg) {
        if (!VERBOSE) return;
        Log.i(TAG, "" + msg);
    }

    public static void longI(String prefix, String msg) {
        if (!VERBOSE) return;
        longLog(Log.INFO, prefix, msg);
    }

    public static void longE(String prefix, String msg) {
        longLog(Log.ERROR, prefix, msg);
    }

    private static void longLog(int priority, String prefix, String msg) {
        String text = msg == null ? "null" : msg;
        String title = prefix == null ? "" : prefix;
        int length = text.length();
        if (length <= MAX_LOG_LENGTH) {
            Log.println(priority, TAG, title + text);
            return;
        }
        int count = (length + MAX_LOG_LENGTH - 1) / MAX_LOG_LENGTH;
        for (int i = 0; i < count; i++) {
            int start = i * MAX_LOG_LENGTH;
            int end = Math.min(start + MAX_LOG_LENGTH, length);
            Log.println(priority, TAG, title + "[" + (i + 1) + "/" + count + "] " + text.substring(start, end));
        }
    }
}
