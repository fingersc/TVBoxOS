package com.github.tvbox.osc.player;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Looper;
import android.util.Pair;

import com.github.tvbox.osc.util.AudioTrackMemory;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.google.android.exoplayer2.C;
import com.orhanobut.hawk.Hawk;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.CueGroup;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
// ⚠️ DefaultAllocator 在 upstream 子包下，不是 com.google.android.exoplayer2 根包：
//    正确路径 = com.google.android.exoplayer2.upstream.DefaultAllocator
//    （曾误写成根包，CI 报 "cannot find symbol ... class DefaultAllocator"）
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.source.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaLoadData;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.IOException;

import java.util.ArrayList;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import xyz.doikki.videoplayer.exo.ExoMediaPlayer;

public class ExoPlayer extends ExoMediaPlayer {

    private static AudioTrackMemory memory;
    private volatile long internalSubtitleDelayUs;
    private OnCuesListener onCuesListener;
    private boolean defaultSubtitleTrackSelected;
    private boolean defaultSubtitleTrackSelectionClosed;

    /**
     * 直播场景的低延迟缓冲水位（毫秒）。
     *
     * <p>点播那套默认值是 15s/50s —— 照搬到直播上，等于先攒十几秒数据才起播，
     * 换台后要等十几秒才出画面，且始终落后直播边缘一大截。IJK 侧早已为直播单独
     * 调过（见 {@code IjkMediaPlayer#setOptions}），Exo 侧此前还是点播参数，两条
     * 链路延迟表现差一个量级。这里对齐直播语义：最大只攒 4 秒，够抗抖动即可。</p>
     */
    private static final int LIVE_MIN_BUFFER_MS = 1_000;
    private static final int LIVE_MAX_BUFFER_MS = 4_000;
    private static final int LIVE_BUFFER_FOR_PLAYBACK_MS = 300;
    private static final int LIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 500;

    /**
     * 点播「起播 / 卡后恢复」水位（毫秒），各内存档共用、恒定不变。
     *
     * <p>这两个值直接决定用户的单次等待时长：起播攒 1.5s 出画面，卡顿后攒 3s 恢复。
     * 用户拍板「宁可偶尔短卡，不接受长缓冲」——所以它们永远不往上加；
     * 频繁短卡的正解是切源（站点质量分已含卡顿折扣，爱卡的站会被自动往后排）。</p>
     */
    private static final int VOD_BUFFER_FOR_PLAYBACK_MS = 1_500;
    private static final int VOD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000;

    // ── 点播缓冲水位按设备内存分档（largeMemoryClass，manifest 开了 largeHeap）──
    //
    // MIN = 时间MAX 的一半：下载囤满 MAX 停下，消耗跌回一半就重新开闸。
    // 好处是把「网络中断恰好落在回差空窗期」能撑住的时长从 MAX/4 提到 MAX/2，
    // 且全程只是后台提前开闸，不产生任何播放等待。
    //
    // 字节上限改为显式设定（不再吃 Exo 默认 ≈137.5MiB 的隐式值，那个值既不是
    // 有意设计、也不与时间水位自洽）：
    //   · 常规码率（≲21Mbps）下时间水位先到，字节上限只是兜底；
    //   · 超高码率源（如 50Mbps，30s 就要 187MB）时间水位到不了，MIN 可能高于
    //     字节上限换算的水位 ⇒「低于 MIN 就下载」恒成立，行为优雅退化为
    //     「有下载机会就一直下」，由 Allocator 的阻塞分配兜底 —— 对这类源
    //     这正是想要的。MIN 不做按码率的动态换算，避免整套路转自定义 LoadControl。
    private static final int VOD_TIER_HIGH_MIN_MS = 30_000;   // = 60s 的一半
    private static final int VOD_TIER_HIGH_MAX_MS = 60_000;   // 字节上限192MB ≈ 25.6Mbps 才先拦
    private static final int VOD_TIER_HIGH_TARGET_BYTES = 192 * 1024 * 1024;
    /** 高档额外保留已播 30s：往回拖进度条秒开（中低档不开，省内存）。 */
    private static final int VOD_TIER_HIGH_BACK_BUFFER_MS = 30_000;

    private static final int VOD_TIER_MID_MIN_MS = 22_500;    // = 45s 的一半（22.5s）
    private static final int VOD_TIER_MID_MAX_MS = 45_000;    // 字节上限128MB ≈ 22.8Mbps
    private static final int VOD_TIER_MID_TARGET_BYTES = 128 * 1024 * 1024;
    private static final int VOD_TIER_MID_BACK_BUFFER_MS = 0;

    private static final int VOD_TIER_LOW_MIN_MS = 15_000;    // = 30s 的一半
    private static final int VOD_TIER_LOW_MAX_MS = 30_000;    // 字节上限80MB ≈ 21.3Mbps
    private static final int VOD_TIER_LOW_TARGET_BYTES = 80 * 1024 * 1024;
    private static final int VOD_TIER_LOW_BACK_BUFFER_MS = 0;

    // ═══════════════════════════════════════════════════════════════════════
    // P0 播放度量（r153）
    //
    // 目的：日志里此前只有「起播/失败」，没有分片级的吞吐与耗时，无法判断一个慢源
    // 到底是「带宽不足（慢速型）」「带宽够但分段请求抖动（抖动型）」还是「解码跟不上」。
    // 这里挂一个 AnalyticsListener，按窗口汇总分片加载耗时与带宽估计，每 10 个分片
    // 打一行 `[seg]`，用于给 P1-5(并行预取) 与 P3(卡后恢复水位) 提供实测依据。
    //
    // 说明：只做观测，不改任何播放行为；日志量按窗口聚合，不会像逐分片那样刷屏。
    // ═══════════════════════════════════════════════════════════════════════
    private static final int SEG_LOG_EVERY = 10;
    private long segLoadStartMs = -1L;
    private int segLoadType = -1;
    private int segLoadBitrate = -1;
    /** 声明码率（bit/s）——HLS 分片的 trackFormat 在 onLoadStarted 常为 null，改由 onDownstreamFormatChanged 取。 */
    private int segDeclaredBitrate = -1;
    private int segWinCount = 0;
    private long segWinSumMs = 0;      // 纯下载耗时（LoadEventInfo.loadDurationMs）
    private long segWinMediaMs = 0;    // 这些分片覆盖的媒体时长（mediaEndTimeMs-mediaStartTimeMs）
    private long segWinMaxMs = 0;
    private long segWinBytes = 0;
    private int segWinSlow2s = 0;
    private long segLastBwEstimate = -1L;
    // 上次下游格式：用于发现分片间的码率/分辨率/编码变化——卡顿常发生在这些切换点
    private int segLastFmtBitrate = -1;
    private int segLastFmtW = -1;
    private int segLastFmtH = -1;
    private String segLastFmtCodecs = null;

    private final AnalyticsListener segAnalyticsListener = new AnalyticsListener() {
        @Override
        public void onLoadStarted(AnalyticsListener.EventTime eventTime,
                                  LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
            segLoadStartMs = eventTime.realtimeMs;
            segLoadType = mediaLoadData == null ? -1 : mediaLoadData.dataType;
            segLoadBitrate = (mediaLoadData != null && mediaLoadData.trackFormat != null)
                    ? mediaLoadData.trackFormat.bitrate : -1;
            if (segLoadBitrate > 0) segDeclaredBitrate = segLoadBitrate;
        }

        @Override
        public void onLoadCompleted(AnalyticsListener.EventTime eventTime,
                                    LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
            long bytes = loadEventInfo == null ? 0L : loadEventInfo.bytesLoaded;
            // 用纯下载耗时，别把“排队等线程/等连接”的时间算进去，否则 actKbps 会被严重低估。
            long durMs = (loadEventInfo != null && loadEventInfo.loadDurationMs > 0)
                    ? loadEventInfo.loadDurationMs
                    : (eventTime.realtimeMs - segLoadStartMs);
            // 该分片覆盖的媒体时长：bytes 是媒体字节、mediaMs 是媒体秒 ⇒ bytes*8/mediaMs 才是真正的“内容码率”。
            long mediaMs = 0L;
            if (mediaLoadData != null
                    && mediaLoadData.mediaStartTimeMs != C.TIME_UNSET
                    && mediaLoadData.mediaEndTimeMs != C.TIME_UNSET) {
                long d = mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs;
                if (d > 0) mediaMs = d;
            }
            recordSegment(durMs, bytes, mediaMs);
        }

        @Override
        public void onLoadError(AnalyticsListener.EventTime eventTime,
                                LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData,
                                IOException error, boolean wasCanceled) {
            LOG.i("[seg] err type=" + segTypeName(segLoadType)
                    + " canceled=" + wasCanceled
                    + " err=" + (error == null ? "null" : error.getClass().getSimpleName()));
        }

        @Override
        public void onDownstreamFormatChanged(AnalyticsListener.EventTime eventTime,
                                              MediaLoadData mediaLoadData) {
            // HLS 分片的 MediaLoadData.trackFormat 在 onLoadStarted/onLoadCompleted 常为 null，
            // 真正带码率/分辨率/编码的是 onDownstreamFormatChanged。
            Format f = mediaLoadData == null ? null : mediaLoadData.trackFormat;
            if (f == null) return;
            if (f.bitrate > 0) segDeclaredBitrate = f.bitrate;
            // 只有格式确实变化时才打一行（分片间码率/分辨率/编码切换是卡顿高发点）
            boolean changed = f.bitrate != segLastFmtBitrate || f.width != segLastFmtW
                    || f.height != segLastFmtH
                    || (f.codecs != null && !f.codecs.equals(segLastFmtCodecs));
            if (changed) {
                segLastFmtBitrate = f.bitrate;
                segLastFmtW = f.width;
                segLastFmtH = f.height;
                segLastFmtCodecs = f.codecs;
                LOG.i("[seg] fmt type=" + segTypeName(mediaLoadData.dataType)
                        + " " + f.width + "x" + f.height
                        + " bitrate=" + (f.bitrate > 0 ? f.bitrate / 1000 : -1) + "kbps"
                        + " codecs=" + f.codecs);
            }
        }

        @Override
        public void onBandwidthEstimate(AnalyticsListener.EventTime eventTime, int totalLoadTimeMs,
                                        long totalBytesLoaded, long bitrateEstimate) {
            segLastBwEstimate = bitrateEstimate;
        }
    };

    private void recordSegment(long durMs, long bytes, long mediaMs) {
        if (durMs < 0) return;
        // 只统计媒体分片：manifest/init 不计入窗口，否则会让 [seg] 的 n 与真实分片数错位、
        // 也会污染“每 10 片一行”的节拍。
        if (segLoadType != C.DATA_TYPE_MEDIA) return;
        segWinCount++;
        segWinSumMs += durMs;
        if (bytes > 0) segWinBytes += bytes;
        if (mediaMs > 0) segWinMediaMs += mediaMs;
        if (durMs > segWinMaxMs) segWinMaxMs = durMs;
        if (segLoadType == C.DATA_TYPE_MEDIA && durMs >= 2000) segWinSlow2s++;
        if (segWinCount >= SEG_LOG_EVERY) emitSegLine(false);
    }

    /** 会话结束/切源前把不足一个窗口的尾巴也打出来（预览等短会话只有几片，否则永远看不到 [seg]）。 */
    private void flushSegments() {
        if (segWinCount > 0 || xyz.doikki.videoplayer.exo.HttpSegStats.peekCount() > 0) emitSegLine(true);
    }

    private void emitSegLine(boolean partial) {
        int n = segWinCount;
        // r156：吞吐改用 OkHttpDataSource 记账的「真实 HTTP 字节/耗时」（open→close），
        // 不再用 Exo 的 loadDurationMs —— 后者会把装载空闲算进去，把 250ms 的分片量成 1400ms，
        // 导致 actKbps 偏低、cls=jitter 假阳性。
        long[] dl = xyz.doikki.videoplayer.exo.HttpSegStats.consume();
        int dlN = (int) dl[0];
        long dlBytes = dl[1];
        long dlMs = dl[2];
        long dlMax = dl[3];
        int dlSlow2 = (int) dl[4];
        long avg;
        long max;
        int slow2;
        long dlKbps;
        if (dlN > 0) {
            avg = dlMs / dlN;
            max = dlMax;
            slow2 = dlSlow2;
            dlKbps = (dlMs > 0 && dlBytes > 0) ? dlBytes * 8 / dlMs : -1;
        } else {
            // 兜底（非本 OkHttpDataSource 路径）：退回 Exo 口径，cls 会标 unknown
            avg = n > 0 ? segWinSumMs / n : 0;
            max = segWinMaxMs;
            slow2 = segWinSlow2s;
            dlKbps = (segWinSumMs > 0 && segWinBytes > 0) ? segWinBytes * 8 / segWinSumMs : -1;
        }
        // segKbps：内容码率（bytes*8/覆盖的媒体时长），拿不到按声明码率
        long contentKbps = (segWinMediaMs > 0 && segWinBytes > 0) ? segWinBytes * 8 / segWinMediaMs
                : (segDeclaredBitrate > 0 ? segDeclaredBitrate / 1000 : -1);
        // 分类：slow=真实吞吐撑不住内容码率；jitter=吞吐够但有单段>2s停顿；good=都好
        String cls;
        if (dlN <= 0 || contentKbps <= 0 || dlKbps <= 0) {
            cls = "unknown";
        } else if (dlKbps * 100 < contentKbps * 90) {
            cls = "slow";
        } else if (max >= 2000) {
            cls = "jitter";
        } else {
            cls = "good";
        }
        LOG.i("[seg]" + (partial ? " part" : "")
                + " n=" + (dlN > 0 ? dlN : n) + " segs=" + n
                + " type=" + segTypeName(segLoadType)
                + " cls=" + cls
                + " avg=" + avg + "ms max=" + max + "ms slow2s=" + slow2
                + " segKbps=" + contentKbps
                + " dlKbps=" + dlKbps
                + " bwEstKbps=" + (segLastBwEstimate > 0 ? segLastBwEstimate / 1000 : -1));
        segWinCount = 0;
        segWinSumMs = 0;
        segWinMediaMs = 0;
        segWinMaxMs = 0;
        segWinBytes = 0;
        segWinSlow2s = 0;
    }

    private static String segTypeName(int t) {
        switch (t) {
            case C.DATA_TYPE_MANIFEST:
                return "manifest";
            case C.DATA_TYPE_MEDIA:
                return "media";
            case C.DATA_TYPE_MEDIA_INITIALIZATION:
                return "init";
            default:
                return "other";
        }
    }

    public ExoPlayer(Context context) {
        super(context);
        setLoadControl(buildLoadControl(context, Hawk.get(HawkConfig.PLAYER_IS_LIVE, false)));
        setRenderersFactory(buildRenderersFactory(context));
        memory = AudioTrackMemory.getInstance(context);
    }

    /**
     * 调试模式联动：切换 ExoPlayer 内部日志（播放内核、HLS/DASH、数据源等）的输出级别。
     *
     * <p>开 = VERBOSE 全量；关 = ASSERT（android Log 体系里最高优先级，
     * Exo 的任何常规日志都到不了该级 ⇒ 等效静默）。</p>
     */
    public static void setExoLogEnabled(boolean enabled) {
        com.google.android.exoplayer2.util.Log.setLogLevel(
                enabled ? android.util.Log.VERBOSE : android.util.Log.ASSERT);
    }

    /**
     * 直播用低延迟水位，点播用按设备内存分档的抗抖动水位。
     *
     * <p>{@code setPrioritizeTimeOverSizeThresholds(true)} 是按「已缓冲时长」而非
     * 「字节数」判断水位：高码率片源若按字节判断会过早触发「缓冲已满」而停止下载，
     * 按时间判断才能跑满时间水位。注意该标志只豁免「低于 MIN 就下载」这一个分支 ——
     * MIN~MAX 区间仍受字节上限约束，字节上限经 {@link DefaultAllocator} 的阻塞分配
     * 兜底，不会失控。</p>
     */
    private DefaultLoadControl buildLoadControl(Context context, boolean isLive) {
        DefaultLoadControl.Builder builder = new DefaultLoadControl.Builder();
        if (isLive) {
            builder.setBufferDurationsMs(
                    LIVE_MIN_BUFFER_MS,
                    LIVE_MAX_BUFFER_MS,
                    LIVE_BUFFER_FOR_PLAYBACK_MS,
                    LIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS);
            builder.setPrioritizeTimeOverSizeThresholds(true);
        } else {
            // largeHeap=true（见 AndroidManifest），用 largeMemoryClass 才是真实可用堆
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            int memClass = am != null ? am.getLargeMemoryClass() : 128;
            int minMs;
            int maxMs;
            int targetBytes;
            int backBufferMs;
            if (memClass >= 256) {
                minMs = VOD_TIER_HIGH_MIN_MS;
                maxMs = VOD_TIER_HIGH_MAX_MS;
                targetBytes = VOD_TIER_HIGH_TARGET_BYTES;
                backBufferMs = VOD_TIER_HIGH_BACK_BUFFER_MS;
            } else if (memClass >= 128) {
                minMs = VOD_TIER_MID_MIN_MS;
                maxMs = VOD_TIER_MID_MAX_MS;
                targetBytes = VOD_TIER_MID_TARGET_BYTES;
                backBufferMs = VOD_TIER_MID_BACK_BUFFER_MS;
            } else {
                minMs = VOD_TIER_LOW_MIN_MS;
                maxMs = VOD_TIER_LOW_MAX_MS;
                targetBytes = VOD_TIER_LOW_TARGET_BYTES;
                backBufferMs = VOD_TIER_LOW_BACK_BUFFER_MS;
            }
            LOG.i("[Buffer] vod tier memClass=" + memClass + " min=" + minMs
                    + " max=" + maxMs + " bytes=" + targetBytes
                    + " back=" + backBufferMs);
            builder.setBufferDurationsMs(
                    minMs,
                    maxMs,
                    VOD_BUFFER_FOR_PLAYBACK_MS,
                    VOD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS);
            // 显式字节上限：经 onTracksSelected 覆写并传导给 Allocator.setTargetBufferSize，
            // 不再依赖 Exo 按轨道类型拼出来的隐式默认值
            builder.setTargetBufferBytes(targetBytes);
            builder.setPrioritizeTimeOverSizeThresholds(true);
            // 回看缓冲：高档保留已播 30s，往回拖进度条秒开；retain=true 让回退
            // 位置按关键帧保留（默认 false 只保留到上一个读点，回看命中率低）。
            // 中低档 backBufferMs=0，行为与默认一致（不留回看数据）。
            builder.setBackBuffer(backBufferMs, backBufferMs > 0);
            builder.setAllocator(new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE));
        }
        return builder.build();
    }

    @Override
    public void initPlayer() {
        super.initPlayer();
        // P0：分片级吞吐/耗时观测（仅日志，不改播放行为）
        try {
            if (mInternalPlayer != null) {
                mInternalPlayer.addAnalyticsListener(segAnalyticsListener);
            }
        } catch (Throwable th) {
            LOG.i("[seg] addAnalyticsListener skip: " + th.getClass().getSimpleName());
        }
        mInternalPlayer.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                loadDefaultSubtitleTrackBeforeReady();
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    defaultSubtitleTrackSelectionClosed = true;
                } else if (playbackState == Player.STATE_ENDED) {
                    flushSegments();
                }
            }

            @Override
            public void onCues(CueGroup cueGroup) {
                OnCuesListener listener = onCuesListener;
                if (listener != null) {
                    listener.onCues(cueGroup.cues);
                }
            }
        });
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        defaultSubtitleTrackSelected = false;
        defaultSubtitleTrackSelectionClosed = false;
        // P0：新一轮起播，先把上一个源不足一个窗口的度量打出来，再清空
        flushSegments();
        segLoadStartMs = -1L;
        segLoadType = -1;
        segLoadBitrate = -1;
        segDeclaredBitrate = -1;
        segWinCount = 0;
        segWinSumMs = 0;
        segWinMediaMs = 0;
        segWinMaxMs = 0;
        segWinBytes = 0;
        segWinSlow2s = 0;
        xyz.doikki.videoplayer.exo.HttpSegStats.reset();
        segLastBwEstimate = -1L;
        segLastFmtBitrate = -1;
        segLastFmtW = -1;
        segLastFmtH = -1;
        segLastFmtCodecs = null;
        super.setDataSource(path, headers);
    }

    private RenderersFactory buildRenderersFactory(Context context) {
        DefaultRenderersFactory factory = new SubtitleOffsetRenderersFactory(context, new SubtitleDelayProvider() {
            @Override
            public long getDelayUs() {
                return internalSubtitleDelayUs;
            }
        })
                .setEnableDecoderFallback(true)
                // ★ 扩展渲染器模式：PREFER → ON。
                //
                // 工程里带的是 exoplayer-ffmpeg-extension（**软解**扩展）。
                // PREFER 的语义是"扩展解码器优先于 MediaCodec"，也就是让 ffmpeg 软解
                // 排在硬解前面 —— 只要扩展里能解的格式（如 AAC/FLAC 音频、部分视频）
                // 就永远轮不到 MediaCodec，与「优先硬解」的目标正好相反，直播高码率
                // 频道还会因此明显吃 CPU。
                //
                // ON 才是「硬解优先」：MediaCodec 能解就用 MediaCodec，解不了
                // （设备缺对应 codec、或安全解码受限）才回落到扩展。
                // 配合上面的 setEnableDecoderFallback(true)，硬解失败仍有软解兜底，
                // 不会出现"既没硬解也没软解"的黑屏。
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        try {
            Method method = DefaultRenderersFactory.class.getMethod("forceDisableMediaCodecAsynchronousQueueing");
            method.invoke(factory);
        } catch (Throwable th) {
            LOG.i("echo-exo-disable-async-codec-queue-skip:" + th.getClass().getSimpleName());
        }
        return factory;
    }

    public TrackInfo getTrackInfo() {
        TrackInfo data = new TrackInfo();
        MappingTrackSelector.MappedTrackInfo mappedInfo = trackSelector.getCurrentMappedTrackInfo();
        if (mappedInfo == null) return data;

        for (int rendererIndex = 0; rendererIndex < mappedInfo.getRendererCount(); rendererIndex++) {
            int type = mappedInfo.getRendererType(rendererIndex);
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_TEXT) continue;

            TrackGroupArray groups = mappedInfo.getTrackGroups(rendererIndex);
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                TrackGroup group = groups.get(groupIndex);
                for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                    Format fmt = group.getFormat(trackIndex);
                    if (type == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(fmt)) continue;
                    String language = getLanguage(fmt);
                    String detail = type == C.TRACK_TYPE_VIDEO ? getVideoName(fmt) : getName(fmt);
                    TrackInfoBean bean = new TrackInfoBean();
                    bean.language = language;
                    bean.name = buildDisplayName(type == C.TRACK_TYPE_AUDIO ? "\u97f3\u8f68" : type == C.TRACK_TYPE_VIDEO ? "\u89c6\u8f68" : "\u5b57\u5e55",
                            type == C.TRACK_TYPE_AUDIO ? data.getAudio().size() + 1 : type == C.TRACK_TYPE_VIDEO ? data.getVideo().size() + 1 : data.getSubtitle().size() + 1,
                            language, detail);
                    bean.renderId = rendererIndex;
                    bean.trackGroupId = groupIndex;
                    bean.trackId = trackIndex;
                    bean.groupIndex = groupIndex;
                    bean.index = trackIndex;
                    bean.selected = isCurrentTrackSelected(fmt, type);
                    bean.bitmapSubtitle = type == C.TRACK_TYPE_TEXT && isBitmapSubtitle(fmt);

                    if (type == C.TRACK_TYPE_AUDIO) {
                        data.addAudio(bean);
                    } else if (type == C.TRACK_TYPE_VIDEO) {
                        data.addVideo(bean);
                    } else {
                        data.addSubtitle(bean);
                    }
                }
            }
        }
        return data;
    }

    public void setTrack(int groupIndex, int trackIndex, String playKey) {
        MappingTrackSelector.MappedTrackInfo mappedInfo = trackSelector.getCurrentMappedTrackInfo();
        setTrack(findAudioRendererIndex(mappedInfo), groupIndex, trackIndex, playKey);
    }

    public void setTrack(TrackInfoBean track, String playKey) {
        if (track == null) return;
        setTrack(track.renderId, track.trackGroupId, track.trackId, playKey);
    }

    private void setTrack(int rendererIndex, int groupIndex, int trackIndex, String playKey) {
        try {
            MappingTrackSelector.MappedTrackInfo mappedInfo = trackSelector.getCurrentMappedTrackInfo();
            if (mappedInfo == null) {
                LOG.i("echo-setTrack: MappedTrackInfo is null");
                return;
            }
            if (rendererIndex == C.INDEX_UNSET || rendererIndex < 0 || rendererIndex >= mappedInfo.getRendererCount()) {
                LOG.i("echo-setTrack: No renderer found");
                return;
            }

            TrackGroupArray groups = mappedInfo.getTrackGroups(rendererIndex);
            if (!isTrackIndexValid(groups, groupIndex, trackIndex)) {
                LOG.i("echo-setTrack: Invalid track index - group:" + groupIndex + ", track:" + trackIndex);
                return;
            }
            DefaultTrackSelector.SelectionOverride override =
                    new DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex);
            DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
            builder.setRendererDisabled(rendererIndex, false);
            builder.clearSelectionOverrides(rendererIndex);
            builder.setSelectionOverride(rendererIndex, groups, override);
            trackSelector.setParameters(builder.build());

            if (mappedInfo.getRendererType(rendererIndex) == C.TRACK_TYPE_AUDIO && !playKey.isEmpty()) {
                memory.save(playKey, groupIndex, trackIndex);
            }
        } catch (Exception e) {
            LOG.i("echo-setTrack error: " + e.getMessage());
        }
    }

    public void loadDefaultTrack(String playKey) {
        Pair<Integer, Integer> pair = memory.exoLoad(playKey);
        if (pair == null) return;

        MappingTrackSelector.MappedTrackInfo mappedInfo = trackSelector.getCurrentMappedTrackInfo();
        if (mappedInfo == null) return;

        int audioRendererIndex = findAudioRendererIndex(mappedInfo);
        if (audioRendererIndex == C.INDEX_UNSET) return;

        setTrack(audioRendererIndex, pair.first, pair.second, "");
    }

    public void loadDefaultSubtitleTrack() {
        if (defaultSubtitleTrackSelected) return;
        TrackInfo trackInfo = getTrackInfo();
        List<TrackInfoBean> subtitles = trackInfo.getSubtitle();
        if (subtitles.isEmpty()) return;

        defaultSubtitleTrackSelected = true;
        TrackInfoBean target = subtitles.get(0);
        for (TrackInfoBean subtitle : subtitles) {
            if ("国语".equals(subtitle.language)) {
                target = subtitle;
                break;
            }
        }
        setTrack(target, "");
    }

    private void loadDefaultSubtitleTrackBeforeReady() {
        if (defaultSubtitleTrackSelectionClosed) return;
        loadDefaultSubtitleTrack();
    }

    public void setOnCuesListener(OnCuesListener listener) {
        onCuesListener = listener;
    }

    public void setInternalSubtitleDelay(int milliseconds) {
        internalSubtitleDelayUs = milliseconds * 1000L;
    }

    private int findAudioRendererIndex(MappingTrackSelector.MappedTrackInfo mappedInfo) {
        if (mappedInfo == null) return C.INDEX_UNSET;
        for (int i = 0; i < mappedInfo.getRendererCount(); i++) {
            if (mappedInfo.getRendererType(i) == C.TRACK_TYPE_AUDIO) {
                return i;
            }
        }
        return C.INDEX_UNSET;
    }

    private boolean isTrackIndexValid(TrackGroupArray groups, int groupIndex, int trackIndex) {
        if (groupIndex < 0 || groupIndex >= groups.length) return false;
        TrackGroup group = groups.get(groupIndex);
        return trackIndex >= 0 && trackIndex < group.length;
    }

    private boolean isBitmapSubtitle(Format format) {
        if (format == null || format.sampleMimeType == null) return false;
        String mimeType = format.sampleMimeType.toLowerCase();
        return mimeType.contains("pgs") || mimeType.contains("dvb") || mimeType.contains("vobsub");
    }

    private boolean isUndeclaredClosedCaptionTrack(Format format) {
        if (format == null || format.accessibilityChannel != Format.NO_VALUE) return false;
        return MimeTypes.APPLICATION_CEA608.equals(format.sampleMimeType)
                || MimeTypes.APPLICATION_CEA708.equals(format.sampleMimeType);
    }

    private boolean isCurrentTrackSelected(Format format, int trackType) {
        if (mInternalPlayer == null) return false;
        Tracks tracks = mInternalPlayer.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != trackType || !group.isSelected()) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i) && isSameFormat(format, group.getTrackFormat(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSameFormat(Format a, Format b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.id != null && b.id != null && a.id.equals(b.id)) return true;
        return a.equals(b);
    }

    private static final Map<String, String> LANG_MAP = new HashMap<>();

    static {
        LANG_MAP.put("zh", "\u56fd\u8bed");
        LANG_MAP.put("zh-cn", "\u56fd\u8bed");
        LANG_MAP.put("cmn", "\u56fd\u8bed");
        LANG_MAP.put("chi", "\u56fd\u8bed");
        LANG_MAP.put("zho", "\u56fd\u8bed");
        LANG_MAP.put("chs", "\u56fd\u8bed");
        LANG_MAP.put("yue", "\u7ca4\u8bed");
        LANG_MAP.put("zh-hk", "\u7ca4\u8bed");
        LANG_MAP.put("zh-yue", "\u7ca4\u8bed");
        LANG_MAP.put("en", "\u82f1\u8bed");
        LANG_MAP.put("en-us", "\u82f1\u8bed");
        LANG_MAP.put("eng", "\u82f1\u8bed");
        LANG_MAP.put("ja", "\u65e5\u8bed");
        LANG_MAP.put("jpn", "\u65e5\u8bed");
        LANG_MAP.put("ko", "\u97e9\u8bed");
        LANG_MAP.put("kor", "\u97e9\u8bed");
        LANG_MAP.put("th", "\u6cf0\u8bed");
        LANG_MAP.put("tha", "\u6cf0\u8bed");
    }

    private String getLanguage(Format fmt) {
        String language = matchLanguage(fmt.language);
        if (!language.isEmpty()) {
            return language;
        }
        return matchLanguage((fmt.label == null ? "" : fmt.label) + " "
                + (fmt.id == null ? "" : fmt.id) + " "
                + (fmt.codecs == null ? "" : fmt.codecs));
    }

    private String matchLanguage(String text) {
        if (text == null) return "";
        String value = text.toLowerCase();
        String mapped = LANG_MAP.get(value);
        if (mapped != null) return mapped;
        if (value.contains("yue") || value.contains("cantonese") || value.contains("\u7ca4") || value.contains("\u5e7f\u4e1c")) {
            return "\u7ca4\u8bed";
        }
        if (value.contains("zh") || value.contains("chi") || value.contains("zho") || value.contains("chs")
                || value.contains("cht") || value.contains("cmn") || value.contains("\u4e2d")
                || value.contains("\u56fd\u8bed") || value.contains("\u666e\u901a\u8bdd")) {
            return "\u56fd\u8bed";
        }
        if (value.contains("en") || value.contains("eng") || value.contains("english") || value.contains("\u82f1")) {
            return "\u82f1\u8bed";
        }
        if (value.contains("ja") || value.contains("jpn") || value.contains("japanese") || value.contains("\u65e5")) {
            return "\u65e5\u8bed";
        }
        if (value.contains("ko") || value.contains("kor") || value.contains("korean") || value.contains("\u97e9")) {
            return "\u97e9\u8bed";
        }
        if (value.contains("tha") || value.contains("thai") || value.contains("th")) {
            return "\u6cf0\u8bed";
        }
        return "";
    }

    private String getName(Format fmt) {
        String channelLabel;
        if (fmt.channelCount <= 0) {
            channelLabel = "";
        } else if (fmt.channelCount == 1) {
            channelLabel = "\u5355\u58f0\u9053";
        } else if (fmt.channelCount == 2) {
            channelLabel = "\u7acb\u4f53\u58f0";
        } else {
            channelLabel = fmt.channelCount + " \u58f0\u9053";
        }

        String codec = "";
        if (fmt.codecs != null && !fmt.codecs.isEmpty()) {
            codec = fmt.codecs.toUpperCase();
        }
        if (fmt.sampleMimeType != null && fmt.sampleMimeType.contains("/")) {
            String mime = fmt.sampleMimeType.substring(fmt.sampleMimeType.indexOf('/') + 1);
            if (codec.isEmpty()) {
                codec = mime.toUpperCase();
            }
        }
        StringBuilder builder = new StringBuilder();
        appendPart(builder, fmt.label);
        appendPart(builder, codec);
        appendPart(builder, channelLabel);
        return builder.toString();
    }

    private String getVideoName(Format fmt) {
        StringBuilder builder = new StringBuilder();
        appendPart(builder, fmt.label);
        if (fmt.width > 0 && fmt.height > 0) {
            appendPart(builder, fmt.width + "x" + fmt.height);
        }
        if (fmt.codecs != null && !fmt.codecs.isEmpty()) {
            appendPart(builder, fmt.codecs.toUpperCase());
        } else if (fmt.sampleMimeType != null && fmt.sampleMimeType.contains("/")) {
            appendPart(builder, fmt.sampleMimeType.substring(fmt.sampleMimeType.indexOf('/') + 1).toUpperCase());
        }
        return builder.toString();
    }

    private String buildDisplayName(String prefix, int number, String language, String detail) {
        StringBuilder builder = new StringBuilder(prefix).append(number);
        if (language != null && !language.isEmpty()) {
            builder.append(" - ").append(language);
        }
        if (detail != null && !detail.isEmpty()) {
            builder.append(" ").append(detail);
        }
        return builder.toString();
    }

    private void appendPart(StringBuilder builder, String value) {
        if (value == null) return;
        String part = value.trim();
        if (part.isEmpty() || "und".equalsIgnoreCase(part) || "\u672a\u77e5".equals(part)) return;
        if (builder.length() > 0) {
            builder.append(" / ");
        }
        builder.append(part);
    }

    public interface OnCuesListener {
        void onCues(List<Cue> cues);
    }

    private interface SubtitleDelayProvider {
        long getDelayUs();
    }

    private static final class SubtitleOffsetRenderersFactory extends DefaultRenderersFactory {
        private final SubtitleDelayProvider subtitleDelayProvider;

        SubtitleOffsetRenderersFactory(Context context, SubtitleDelayProvider subtitleDelayProvider) {
            super(context);
            this.subtitleDelayProvider = subtitleDelayProvider;
        }

        @Override
        protected void buildTextRenderers(Context context, TextOutput output, Looper outputLooper,
                                          int extensionRendererMode, ArrayList<Renderer> out) {
            int firstRendererIndex = out.size();
            super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out);
            for (int i = firstRendererIndex; i < out.size(); i++) {
                Renderer renderer = out.get(i);
                out.set(i, (Renderer) Proxy.newProxyInstance(Renderer.class.getClassLoader(),
                        new Class<?>[]{Renderer.class},
                        new SubtitleOffsetRendererHandler(renderer, subtitleDelayProvider)));
            }
        }
    }

    private static final class SubtitleOffsetRendererHandler implements InvocationHandler {
        private final Renderer renderer;
        private final SubtitleDelayProvider subtitleDelayProvider;

        SubtitleOffsetRendererHandler(Renderer renderer, SubtitleDelayProvider subtitleDelayProvider) {
            this.renderer = renderer;
            this.subtitleDelayProvider = subtitleDelayProvider;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object[] invokeArgs = args;
            if ("render".equals(method.getName()) && args != null && args.length > 0
                    && args[0] instanceof Long) {
                invokeArgs = args.clone();
                long positionUs = (Long) invokeArgs[0];
                invokeArgs[0] = Math.max(0, positionUs - subtitleDelayProvider.getDelayUs());
            }
            try {
                return method.invoke(renderer, invokeArgs);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
