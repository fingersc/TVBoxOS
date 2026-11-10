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
 *
 * <p><b>r201：并发数与预取深度解耦。</b>W 的语义是「同时在飞的连接数」
 * （喂饱码率所需），而<b>预取深度</b>（播放头之前留多少片的缓冲）是另一件事，
 * 由 {@link #targetDepth} 单独决定。二者混为一谈会产生一个反直觉的后果：
 * CDN 越快 ⇒ 单连接吞吐越高 ⇒ {@code W_need} 越小 ⇒ 预取深度越小 ——
 * 于是「越快的源反而越没有缓冲」。对 ffmpeg/IJK 这类严格串行消费切片的播放器
 * （读完第 N 片才开始播，播完才请求 N+1），深度不足就等于「总是在片的下载临界点取片」，
 * 源站一抖就变成一次卡顿。故深度按<b>时间目标</b>（约 {@link #TARGET_BUFFER_SEC} 秒）
 * 折算成片数，再受内存预算约束。</p>
 */
public final class PrefetchController {

    /** r201：预取深度目标（秒）——播放头之前希望已就绪的缓冲时长。 */
    public static final double TARGET_BUFFER_SEC = 30.0;
    /** r201：预取深度下限（片）。 */
    public static final int MIN_DEPTH = 1;
    /** r201：预取深度上限（片）。 */
    public static final int MAX_DEPTH = 8;
    /** r201：单片字节未知时的保守估计（用于按内存预算收敛深度）。 */
    private static final long ASSUMED_SEG_BYTES = 2L * 1024 * 1024;

    /**
     * r201：计算预取深度（片）——与并发窗口 W 解耦。
     *
     * <p>W 回答的是「同时开几条连接才喂得饱码率」；深度回答的是「播放头之前
     * 应该留多少片已就绪的缓冲」。前者随源站速度变化，后者只跟片长与内存预算有关。
     * 二者必须分开，否则源站越快 ⇒ W 越小 ⇒ 缓冲越薄 ⇒ 串行消费切片的播放器
     * （ffmpeg/IJK）永远在片的下载临界点取片。</p>
     *
     * @param bitrateMbps    运行中估算码率（Mbps，未知 0）
     * @param avgSegmentSec  平均片长（秒，未知 0）
     * @param measuredAvgBytes 已下载片的平均字节（0 = 还没测出来）
     * @param memBudgetBytes 允许预取缓冲占用的堆字节
     */
    public static int targetDepth(double bitrateMbps, double avgSegmentSec,
                                  long measuredAvgBytes, long memBudgetBytes) {
        double dur = avgSegmentSec > 0 ? avgSegmentSec : 4.0;
        int byTime = (int) Math.ceil(TARGET_BUFFER_SEC / dur);
        int depth = clamp(byTime, MIN_DEPTH, MAX_DEPTH);
        long segBytes = measuredAvgBytes > 0 ? measuredAvgBytes : 0L;
        if (segBytes <= 0 && bitrateMbps > 0) {
            segBytes = (long) (bitrateMbps * 1e6 / 8.0 * dur);
        }
        if (segBytes <= 0) {
            segBytes = ASSUMED_SEG_BYTES;
        }
        if (memBudgetBytes > 0) {
            int byMem = (int) (memBudgetBytes / segBytes);
            depth = Math.min(depth, Math.max(MIN_DEPTH, byMem));
        }
        return clamp(depth, MIN_DEPTH, MAX_DEPTH);
    }

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
