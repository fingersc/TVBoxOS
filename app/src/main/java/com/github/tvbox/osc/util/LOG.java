package com.github.tvbox.osc.util;

import android.util.Log;

import xyz.doikki.videoplayer.util.PlayLog;

/**
 * 应用日志统一出口。<b>全量受「调试模式」开关（{@link HawkConfig#DEBUG_OPEN}）控制</b>：
 * 关 = 所有通道零输出；开 = 全通道输出。
 *
 * <p>v99-r89：player 模块的 {@link PlayLog} 由本类 {@link #setDebugEnabled} 单向同步，
 * 所以「调试模式」是唯一真相源 —— 不要再往 player 模块里直接写 {@code android.util.Log}。</p>
 *
 * <p>开关状态由 {@link #setDebugEnabled(boolean)} 运行时设置：
 * App 启动时按 Hawk 里的调试模式初始化（每次启动会重置为关，避免忘记关着影响日常），
 * 「关于」弹窗里切换开关时即时更新，无需重启。</p>
 *
 * <p>不受影响：进程崩溃时的堆栈由系统 uncaught handler 打印，不走本类。</p>
 *
 * @author pj567
 * @date :2020/12/18
 */
public class LOG {
    private static final String TAG = "TVBox-runtime";
    private static final int MAX_LOG_LENGTH = 3000;

    private static volatile boolean debug = false;

    public static void setDebugEnabled(boolean enabled) {
        debug = enabled;
        // v99-r89：player 模块不能反向依赖 app，那边的日志出口（PlayLog）需要这里单向同步。
        // 不同步的话，翻族/探测那批日志会绕过调试模式一直刷 —— 它们过去正是这么干的。
        try {
            PlayLog.setDebugEnabled(enabled);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isDebugEnabled() {
        return debug;
    }

    public static void d(String msg) {
        if (!debug) return;
        Log.d(TAG, "" + msg);
    }

    public static void d(String msg, Throwable tr) {
        if (!debug) return;
        Log.d(TAG, "" + msg, tr);
    }

    public static void i(String msg) {
        if (!debug) return;
        Log.i(TAG, "" + msg);
    }

    public static void w(String msg) {
        if (!debug) return;
        Log.w(TAG, "" + msg);
    }

    public static void e(String msg) {
        if (!debug) return;
        Log.e(TAG, "" + msg);
    }

    public static void e(String msg, Throwable tr) {
        if (!debug) return;
        Log.e(TAG, "" + msg, tr);
    }

    public static void longI(String prefix, String msg) {
        if (!debug) return;
        longLog(Log.INFO, prefix, msg);
    }

    public static void longE(String prefix, String msg) {
        if (!debug) return;
        longLog(Log.ERROR, prefix, msg);
    }

    /**
     * 切源专用诊断通道，与其它通道一样<b>受调试模式开关控制</b>。
     *
     * <p>切源是一条多节点异步链路（选源 → 详情 → 判空 → 续切 → 起播 → 进度迁移），
     * 出问题只能靠日志反推。排障步骤：设置 → 关于 → 打开调试模式，然后复现问题，
     * {@code adb logcat -s TVBox-switch} 即可看到全链路。</p>
     */
    public static void sw(String msg) {
        if (!debug) return;
        Log.i("TVBox-switch", "" + msg);
    }

    /**
     * 直播播放链路专用诊断通道，同样<b>受调试模式开关控制</b>。
     *
     * <p>直播排障最想知道的是「这次到底用的哪个播放器、走的是硬解还是软解」，
     * 而这恰恰只在几处关键节点发生（起播 / 换台 / 自动降级）。
     * 过滤：{@code adb logcat -s TVBox-live}。</p>
     */
    public static void live(String msg) {
        if (!debug) return;
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
