package xyz.doikki.videoplayer.exo;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy;

import java.io.IOException;

/**
 * 自定义HLS错误处理策略：媒体切片失败时快速重试、尽量跳过坏切片继续播放。
 *
 * <p>注意本策略<b>只应对分片</b>。清单（manifest）是整条流的入口，清单拿不到就没有
 * "跳到下一片"这回事，重试再多轮也不可能变好。</p>
 *
 * <p>v99-r84 修正：原实现把 {@code exception instanceof IOException} 一律当成"坏切片"，
 * 于是 <b>manifest 的 404 也被改成 500ms 重试</b>，覆盖了父类
 * {@link DefaultLoadErrorHandlingPolicy} 对 4xx 的"不重试"规则。后果是实测中的连锁反应：</p>
 * <ol>
 *   <li>清单 404（54ms 就返回）被当成可重试错误；</li>
 *   <li>重试恰好撞上一条连不通的地址（TCP 握手黑洞，8s）；</li>
 *   <li>几轮下来 15s 起播预算耗尽，{@code onPlayerError} 始终没有回调；</li>
 *   <li>上层为 403/404 准备的翻族重试（{@link ExoMediaPlayer#retryWithFlippedFamily}）
 *       因此一次都没机会执行，只能靠超时盲换线。</li>
 * </ol>
 *
 * <p>现在 manifest 的 4xx 直接返回 {@link C#TIME_UNSET}（不重试），错误<b>立刻</b>上报到
 * {@code onPlayerError}，翻族/换线才来得及在起播预算内完成。分片行为保持不变。</p>
 */
public class HlsErrorHandlingPolicy extends DefaultLoadErrorHandlingPolicy {

    // P1-2（r153）：分片失败快速放弃，把等待时间让给「重试另一个分片 / 自动切源」。
    //   原来 3 次 × 500ms：单个卡死分片最坏要拖 ~30s 才跳过（配合 30s readTimeout 更久）。
    //   改成 2 次 × 300ms：坏分片更快被判死并跳过，整段卡顿明显缩短。
    //   注意：这里只收紧「次数/间隔」，不改 manifest 4xx 不重试的既有规则（见 getRetryDelayMsFor）。
    private static final int MAX_RETRIES = 2;  // 最多重试2次
    private static final long RETRY_DELAY_MS = 300;  // 重试延迟300毫秒

    public HlsErrorHandlingPolicy() {
        super();
    }

    @Override
    public long getRetryDelayMsFor(@NonNull LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo) {
        // v99-r84：清单的 4xx 不重试 —— 立刻让 onPlayerError 看到真实状态码。
        //   4xx 是"服务端明确拒绝"，重试不会改变结果（403 防盗链、404 频道不存在都在此列）；
        //   尤其是 404，它正是"这条地址在当前的地址族下没有内容"的信号，
        //   越早交出去，翻族重试越有机会在起播预算内跑完。
        //   5xx / 连接类 IOException 仍按父类规则有限重试（服务端抖动有救）。
        if (isManifest(loadErrorInfo)) {
            int code = httpStatusOf(loadErrorInfo.exception);
            if (code >= 400 && code < 500) {
                return C.TIME_UNSET;
            }
        }
        // P1-2 补（r154）：清单遇到 TLS/连接被重置（SSLHandshakeException / Connection reset /
        //   Connection closed by peer 等）说明这条地址基本是死链（境外被 reset / 服务端拒绝），
        //   重试只会再耗一轮 3~4s 握手、然后照样失败。直接判死不重试，把「翻族重试 / 换线」
        //   尽早交给上层（实测 play.modujx10.com 单源每次省约 11s）。
        if (isManifest(loadErrorInfo) && isConnectionReset(loadErrorInfo.exception)) {
            return C.TIME_UNSET;
        }
        // 如果是HLS切片错误，使用较短的重试延迟
        if (isChunkError(loadErrorInfo)) {
            return RETRY_DELAY_MS;
        }
        return super.getRetryDelayMsFor(loadErrorInfo);
    }

    @Override
    public int getMinimumLoadableRetryCount(int dataType) {
        // 对于HLS切片，限制重试次数，避免无限重试
        if (dataType == C.DATA_TYPE_MEDIA) {
            return MAX_RETRIES;
        }
        return super.getMinimumLoadableRetryCount(dataType);
    }

    @Override
    public LoadErrorHandlingPolicy.FallbackSelection getFallbackSelectionFor(
            @NonNull FallbackOptions fallbackOptions,
            @NonNull LoadErrorInfo loadErrorInfo) {

        // 对于HLS切片加载错误，不使用fallback，而是直接跳过
        if (isChunkError(loadErrorInfo)) {
            // 返回null表示不使用fallback，ExoPlayer会跳过这个切片继续播放
            return null;
        }

        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo);
    }

    /**
     * 这次失败是不是"清单"的失败。
     *
     * <p>用 {@code mediaLoadData.dataType} 判断（ExoPlayer 在加载 playlist 时会标成
     * {@link C#DATA_TYPE_MANIFEST}）。该字段理论上可能为 null，取不到就按"不是清单"处理，
     * 退回改动前的行为，绝不让策略本身抛异常。</p>
     */
    private boolean isManifest(@NonNull LoadErrorInfo loadErrorInfo) {
        try {
            return loadErrorInfo.mediaLoadData != null
                    && loadErrorInfo.mediaLoadData.dataType == C.DATA_TYPE_MANIFEST;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从异常链里取 HTTP 状态码；拿不到返回 0（视为"不是 4xx"）。 */
    private int httpStatusOf(Throwable error) {
        Throwable t = error;
        for (int guard = 0; t != null && guard < 8; guard++) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
            }
            Throwable cause = t.getCause();
            if (cause == t) {
                break;
            }
            t = cause;
        }
        return 0;
    }

    /** 异常链里是否有「TLS 握手被拒 / 连接被对端重置或关闭」这类基本不可恢复的错误。 */
    private boolean isConnectionReset(Throwable error) {
        Throwable t = error;
        for (int guard = 0; t != null && guard < 8; guard++) {
            if (t instanceof javax.net.ssl.SSLException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("connection reset")
                        || lower.contains("connection closed by peer")
                        || lower.contains("connection abort")
                        || lower.contains("broken pipe")
                        || lower.contains("unexpected end of stream")) {
                    return true;
                }
            }
            Throwable cause = t.getCause();
            if (cause == t) {
                break;
            }
            t = cause;
        }
        return false;
    }

    /**
     * 判断是否是切片错误
     */
    private boolean isChunkError(@NonNull LoadErrorInfo loadErrorInfo) {
        // 检查是否是IO异常（网络错误、404等）
        Throwable error = loadErrorInfo.exception;
        return error instanceof IOException;
    }
}
