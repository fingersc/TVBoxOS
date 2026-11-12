package com.github.tvbox.osc.util;

import android.os.SystemClock;

/**
 * r216：冷启动分段计时。
 *
 * <p>启动优化最怕"改完感觉快了"。这里从一个进程级的零点开始，每次 {@link #mark(String)}
 * 打一行 {@code [boot] +<ms> <tag>}，同一份日志里就能看清每个阶段到底花了多少、
 * 以及哪些是并行掉的。</p>
 *
 * <p>零点取 {@link #mark(String)} 第一次被调用时的 {@link SystemClock#elapsedRealtime()}，
 * 也就是 {@code App.onCreate} 的开头 —— 再往前的进程创建时间在应用层拿不到。</p>
 *
 * <p>只在调试模式（{@link LOG#isDebugEnabled()}）下输出：正式包零日志、零字符串拼接开销。
 * 任何异常都静默，绝不影响启动。</p>
 */
public final class BootTrace {

    private static long origin = -1L;

    private BootTrace() {
    }

    /** 打一个计时点。tag 用短英文/拼音，便于 grep：appStart / ioDone / uiInflated / cfgOk / sortOk。 */
    public static void mark(String tag) {
        try {
            if (!LOG.isDebugEnabled()) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (origin < 0L) {
                origin = now;
            }
            LOG.i("[boot] +" + (now - origin) + "ms " + tag);
        } catch (Throwable ignored) {
        }
    }

    /** 强制打点（不受调试开关限制）。只给"必须留证据"的少数位置用。 */
    public static void markAlways(String tag) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (origin < 0L) {
                origin = now;
            }
            LOG.i("[boot] +" + (now - origin) + "ms " + tag);
        } catch (Throwable ignored) {
        }
    }
}
