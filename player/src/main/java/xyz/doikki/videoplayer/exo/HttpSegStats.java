package xyz.doikki.videoplayer.exo;

/**
 * 分片下载时延/字节的真实记账（由 {@link OkHttpDataSource} 在每次 HTTP 请求 open→close 时写入）。
 *
 * <p>为什么需要它：Exo 的 {@code LoadEventInfo.loadDurationMs} 在不同设备/HLS 场景下会把
 * 「装载器空闲等待」也算进单次加载耗时，导致 {@code [seg]} 的 avg/actKbps 严重失真
 * （实测单分片真实 HTTP 仅 ~250ms，但 loadDurationMs 汇总出 ~1400ms）。这里改在数据源层
 * 直接量真实 HTTP 字节与耗时，作为吞吐的唯一可信口径。</p>
 *
 * <p>只统计媒体分片（.ts/.m4s/.mp4 等），不含 manifest/init；只做观测。</p>
 */
public final class HttpSegStats {

    private static long n;       // 完成的分片请求数
    private static long bytes;   // 累计字节
    private static long msSum;   // 累计耗时（ms）
    private static long maxMs;   // 单次最长耗时
    private static long slow2s;  // 单次 >=2000ms 的次数

    private HttpSegStats() {
    }

    public static synchronized void record(long byteCount, long elapsedMs) {
        if (elapsedMs < 0 || byteCount <= 0) {
            return;
        }
        n++;
        bytes += byteCount;
        msSum += elapsedMs;
        if (elapsedMs > maxMs) {
            maxMs = elapsedMs;
        }
        if (elapsedMs >= 2000) {
            slow2s++;
        }
    }

    /** 取出并清零当前窗口：[n, bytes, msSum, maxMs, slow2s]。 */
    public static synchronized long[] consume() {
        long[] r = {n, bytes, msSum, maxMs, slow2s};
        n = 0;
        bytes = 0;
        msSum = 0;
        maxMs = 0;
        slow2s = 0;
        return r;
    }

    public static synchronized void reset() {
        n = 0;
        bytes = 0;
        msSum = 0;
        maxMs = 0;
        slow2s = 0;
    }

    public static synchronized int peekCount() {
        return (int) n;
    }
}
