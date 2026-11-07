package com.github.tvbox.osc.util;

/**
 * A′ 方案的自适应并发控制器（纯逻辑，便于单测）。
 *
 * <p>连接数 = 同时在飞的切片数 = 预取窗口宽度 W。实测结论：跨切片并发能稳定提速
 * 2–4×；同一文件多 Range 并发不可靠，故这里只按「窗口宽度」调度，从不拆单片。</p>
 *
 * <p>策略（稳定优先、降快升慢）：</p>
 * <ul>
 *   <li>需求 {@code demand = 码率 × 播放倍速 × 安全系数}，倍速越高系数越大
 *       （1.30 / 1.40 / 1.50），因为倍速下缓冲墙钟余量被压缩；</li>
 *   <li>{@code W_need = ceil(demand / 单连接吞吐)}，再夹在 [1, 4]；</li>
 *   <li>一次最多升一档（升档要慢）；降档立即生效；</li>
 *   <li>近期错误多 → 直接收缩到 1/2；疑似按 IP 限速（4 路聚合收益 < 1.15×）→ 上限 2。</li>
 * </ul>
 */
public final class PrefetchController {

    /** 目标码率的安全系数（按播放倍速分档）。 */
    public static double marginForSpeed(float speed) {
        double s = speed > 0 ? speed : 1.0;
        if (s <= 1.0) {
            return 1.30;
        }
        if (s <= 2.0) {
            return 1.40;
        }
        return 1.50;
    }

    /** 目标吞吐（Mbps）。bitrateMbps<=0 时返回 0（未知）。 */
    public static double demandMbps(double bitrateMbps, float speed) {
        if (bitrateMbps <= 0) {
            return 0;
        }
        return bitrateMbps * marginForSpeed(speed) * (speed > 0 ? speed : 1.0);
    }

    /** 单连接吞吐换算：bytes/ms → Mbps。 */
    public static double mbpsFromBytesPerMs(double bytesPerMs) {
        return bytesPerMs * 0.008;
    }

    /**
     * 计算下一档窗口宽度。
     *
     * @param bitrateMbps      当前估算码率（Mbps，未知 0）
     * @param speed            播放倍速
     * @param perConnBytesPerMs 单连接吞吐 EWMA（bytes/ms，未知 0）
     * @param currentWindow    当前窗口
     * @param recentErrors     最近 10 次请求中的错误数（0..10）
     * @param perIpSuspect     是否疑似按 IP 限速（4 路聚合收益不足）
     */
    public static int targetWindow(double bitrateMbps, float speed, double perConnBytesPerMs,
                                   int currentWindow, int recentErrors, boolean perIpSuspect) {
        int cur = clamp(currentWindow, 1, M3u8PrefetchPlan.HARD_MAX_WINDOW);
        if (perConnBytesPerMs <= 0) {
            // 还没有测量数据：保守 2 路起（起播探针）
            return clamp(cur <= 0 ? 2 : cur, 1, M3u8PrefetchPlan.HARD_MAX_WINDOW);
        }
        double demand = demandMbps(bitrateMbps, speed);
        double perConn = mbpsFromBytesPerMs(perConnBytesPerMs);
        int target;
        if (demand <= 0 || perConn <= 0.01) {
            // 码率未知：维持现状（不超过 2，避免无依据地堆连接）
            target = Math.min(Math.max(cur, 1), 2);
        } else {
            target = (int) Math.ceil(demand / perConn);
        }
        target = clamp(target, 1, M3u8PrefetchPlan.HARD_MAX_WINDOW);

        if (recentErrors >= 2) {
            target = 1;
        } else if (recentErrors >= 1) {
            target = Math.min(target, 2);
        }
        if (perIpSuspect) {
            target = Math.min(target, 2);
        }
        // 升档要慢：一次只 +1；降档立即
        if (target > cur) {
            return cur + 1;
        }
        return target;
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
