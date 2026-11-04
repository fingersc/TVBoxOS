package com.github.tvbox.osc.util;

import android.os.SystemClock;

/**
 * ★ r163：轻量性能埋点。用于定位「进详情 / 切源」主线程卡顿究竟落在哪一段
 * （onCreate 布局膨胀、预览 Fragment 同步 inflate、日期表构建、落盘、起播 WebView 等）。
 *
 * <p>只在「调试模式」打开时输出一行 {@code [PERF] name=..ms=..}（tag {@code TVBox-perf}）；
 * 调试关闭时 {@link #begin}/{@link #end} 立刻返回，无 map 操作、无字符串拼接，零可感知开销。</p>
 *
 * <p>仅主线程使用（名称-时间用普通 HashMap 记录，加锁保护避免和后台线程互窜）。</p>
 */
public final class PerfTrace {
    private static final String TAG = "TVBox-perf";
    private static final java.util.HashMap<String, Long> START = new java.util.HashMap<>();

    private PerfTrace() {
    }

    public static void begin(String name) {
        if (!LOG.isDebugEnabled()) return;
        synchronized (START) {
            START.put(name, SystemClock.elapsedRealtime());
        }
    }

    public static void end(String name) {
        if (!LOG.isDebugEnabled()) return;
        Long start;
        synchronized (START) {
            start = START.remove(name);
        }
        if (start != null) {
            long ms = SystemClock.elapsedRealtime() - start;
            android.util.Log.i(TAG, name + "=" + ms + "ms");
        }
    }

    /** 直接报告一段已测得耗时（毫秒）。 */
    public static void span(String name, long ms) {
        if (!LOG.isDebugEnabled()) return;
        android.util.Log.i(TAG, name + "=" + ms + "ms");
    }
}
