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

    /**
     * 切源专用诊断通道，<b>不受 {@link #VERBOSE} 门控影响，任何构建都输出</b>。
     *
     * <p>切源是一条多节点异步链路（选源 → 详情 → 判空 → 续切 → 起播 → 进度迁移），
     * 出问题只能靠日志反推。上一轮为性能给 info 级加了 release 门控后，
     * 这条链路的日志随之消失、无法排障。故单独开一个始终可见的 tag，
     * 只记切源关键节点，量很小，不影响性能目标。</p>
     *
     * <p>过滤：{@code adb logcat -s TVBox-switch}</p>
     */
    public static void sw(String msg) {
        Log.i("TVBox-switch", "" + msg);
    }

    /**
     * 直播播放链路专用诊断通道，同样<b>不受 {@link #VERBOSE} 门控影响</b>。
     *
     * <p>直播排障最想知道的是「这次到底用的哪个播放器、走的是硬解还是软解」，
     * 而这恰恰只在几处关键节点发生（起播 / 换台 / 自动降级），量很小。
     * 用 {@link #i(String)} 会被 release 门控挡掉、现场查不到，故单独开一档。</p>
     *
     * <p>过滤：{@code adb logcat -s TVBox-live}</p>
     */
    public static void live(String msg) {
        Log.i("TVBox-live", "" + msg);
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
