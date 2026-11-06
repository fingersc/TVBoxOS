package com.github.tvbox.osc.sync;

import com.orhanobut.hawk.Hawk;

/**
 * 可信时钟：本机墙钟 + 从 WebDAV 响应 {@code Date} 头学到的偏移。
 *
 * <p><b>为什么需要</b>：模拟器等设备的系统时钟可能滞后/冻结/回拨，直接用
 * {@code System.currentTimeMillis()} 做排序与墓碑，会让该设备的新数据永远排在后面而输掉，
 * 甚至把删除墓碑判成过期。</p>
 *
 * <p>做法：每个 HTTP 响应都取服务端 {@code Date} 头（RFC 7231，秒级精度），
 * 计算 {@code 服务端时间 - 本机时间} 作为偏移量并持久化。之后
 * {@link #nowPhysical()} = 本机时间 + 偏移量 ≈ 真实 UTC 时间。</p>
 *
 * <p>局限：{@code Date} 只有秒级精度，且拿到的是"服务器处理请求时"的时间；
 * 但它对"某台设备时钟长期偏慢/冻结"这类问题足够有效，且不依赖任何自建服务端。
 * 只作为 HLC 的物理分量，单调性最终由 {@link HlcClock} 保证。</p>
 */
public final class TrustedClock {

    /** 偏移量持久化键（全局；偏移量是"本机 vs 服务器"的差，与账号无关）。 */
    private static final String KEY_OFFSET = "sync_clock_offset_ms";

    /** 超过 ±24h 的"偏移"基本是服务器时间异常或抓到了错误头，丢弃。 */
    private static final long MAX_ABS_OFFSET = 24L * 3600 * 1000;

    /** 与当前偏移相差超过 2s 才采纳新样本（过滤 Date 秒级抖动）。 */
    private static final long ADOPT_THRESHOLD_MS = 2000L;

    /**
     * v4.2：偏差超过 10 分钟时直接吸附到服务端时间，不再做 50% 平滑。
     *
     * <p>模拟器/平板可能一开机就偏几个小时（实测 -6h17m）。50% 平滑要十几轮
     * 才收敛，这期间 HLC 仍然错得离谱，会制造大量“看起来更新/更旧”的假象。
     * 大偏差一次到底；2s–10min 的小偏差仍平滑，避免网络抖动带偏。</p>
     */
    private static final long SNAP_THRESHOLD_MS = 10L * 60 * 1000;

    private static volatile long sOffset = Long.MIN_VALUE;

    private TrustedClock() {
    }

    private static long offset() {
        long o = sOffset;
        if (o == Long.MIN_VALUE) {
            synchronized (TrustedClock.class) {
                o = sOffset;
                if (o == Long.MIN_VALUE) {
                    try {
                        o = Hawk.get(KEY_OFFSET, 0L);
                    } catch (Throwable ignored) {
                        o = 0L;
                    }
                    sOffset = o;
                }
            }
        }
        return o;
    }

    /** 当前可信物理时间（毫秒 UTC 估计）。 */
    public static long nowPhysical() {
        return System.currentTimeMillis() + offset();
    }

    public static long offsetMs() {
        return offset();
    }

    /**
     * 用服务端 {@code Date} 头（毫秒 UTC）更新偏移。
     *
     * <p>取 50% 平滑，避免一次异常 Date 把偏移拉飞；连续多次真实偏差会在几轮内收敛。</p>
     */
    public static synchronized void observeServerDate(long serverEpochMs) {
        if (serverEpochMs <= 0) {
            return;
        }
        long candidate = serverEpochMs - System.currentTimeMillis();
        if (candidate > MAX_ABS_OFFSET || candidate < -MAX_ABS_OFFSET) {
            return;
        }
        long cur = offset();
        long delta = candidate - cur;
        if (Math.abs(delta) > ADOPT_THRESHOLD_MS) {
            long next;
            if (Math.abs(delta) > SNAP_THRESHOLD_MS) {
                // v4.2：设备时钟整机偏几小时，平滑收敛太慢，直接吸附。
                next = candidate;
                android.util.Log.i("TVBox-rt", "[clock] offset snap cur=" + cur
                        + " candidate=" + candidate + " delta=" + delta);
            } else {
                next = cur + delta / 2;
            }
            sOffset = next;
            try {
                Hawk.put(KEY_OFFSET, next);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 账号切换/测试用：下次读取重新从 Hawk 加载。 */
    public static synchronized void reset() {
        sOffset = Long.MIN_VALUE;
    }
}
