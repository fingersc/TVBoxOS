package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.DefaultAnalyticsCollector;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.EventLogger;
import com.google.android.exoplayer2.video.VideoSize;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoViewManager;
import xyz.doikki.videoplayer.util.DualStackPrefs;
import xyz.doikki.videoplayer.util.PlayerUtils;

public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

    protected Context mAppContext;
    protected SimpleExoPlayer mInternalPlayer;
    protected MediaSource mMediaSource;
    protected ExoMediaSourceHelper mMediaSourceHelper;

    private PlaybackParameters mSpeedPlaybackParameters;

    private boolean mIsPreparing;

    private LoadControl mLoadControl;
    private RenderersFactory mRenderersFactory;
    protected TrackSelector mTrackSelector;

    protected DefaultTrackSelector trackSelector;

    protected String currentPlayPath;
    protected Map<String, String> currentHeaders;
    private boolean mRetriedAsHls;
    /** v99-r83：本次播放已为重试翻过一次地址族（每次 setDataSource 重置）。 */
    private boolean mFlippedFamily;
    /** v99-r85：翻族的目标族，用于 watchdog 记账与成功清账。 */
    private int mFlipTargetFamily = DualStackPrefs.FAMILY_DEFAULT;
    /**
     * v99-r85：翻族已被 watchdog 判死（3 秒没就绪）。
     *
     * <p>置位后本次播放不再翻族、也不再重复上抛 onError —— watchdog 已经交回上层一次，
     * 换线会重新 setDataSource 把这个标志清掉。</p>
     */
    private boolean mFlipAbandoned;
    /**
     * v99-r85：翻族之后确实就绪过（这条路是真的通的）。
     *
     * <p>用来区分"翻过去又 404"的两种含义：没验证过 ⇒ 这族没有该资源，该记账；
     * 验证过 ⇒ 这条路本来是通的，只是资源没了/中途断了，<b>不能</b>把族判死。</p>
     */
    private boolean mFlipVerified;
    /**
     * v99-r85：翻族重试的起播预算。
     *
     * <p>为什么单给翻族一个短预算：翻族是"已知这条线路有问题"之后的二次尝试，
     * 两族都正常时首帧实测都在 1 秒内（同机日志 0.26~0.64 秒）；3 秒仍不出画面，
     * 说明这条路（多半是 IPv6 黑洞）不通，继续等 8 秒 connectTimeout 纯属白等。</p>
     */
    private static final long FLIP_BOOT_WATCHDOG_MS = 3000L;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    /** 翻族轮次：setDataSource / 成功 / release 时自增，让旧的 watchdog 自动失效。 */
    private int mFlipToken;

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
    }

    @Override
    public void initPlayer() {
        if (mLoadControl == null) {
            mLoadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 30_000, 1_000, 3_000)
                    .setTargetBufferBytes(32 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .setBackBuffer(0, false)
                    .build();
        }
        mInternalPlayer = new SimpleExoPlayer.Builder(
                mAppContext,
                mRenderersFactory == null ? mRenderersFactory = new DefaultRenderersFactory(mAppContext).setEnableDecoderFallback(true)  // 启用解码器回退，避免硬件加速问题
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER) : mRenderersFactory,
                mTrackSelector == null ? mTrackSelector = new DefaultTrackSelector(mAppContext) : mTrackSelector,
                new DefaultMediaSourceFactory(mAppContext),
                mLoadControl,
                DefaultBandwidthMeter.getSingletonInstance(mAppContext),
                new DefaultAnalyticsCollector(Clock.DEFAULT))
                .build();
        setOptions();

        // 播放器日志（当开启日志且 mTrackSelector 为 MappingTrackSelector 时）
        if (VideoViewManager.getConfig().mIsEnableLog && mTrackSelector instanceof MappingTrackSelector) {
            mInternalPlayer.addAnalyticsListener(new EventLogger((MappingTrackSelector) mTrackSelector, "ExoPlayer"));
        }
        if(trackSelector == null)trackSelector=(DefaultTrackSelector)mTrackSelector;
        mInternalPlayer.addListener(this);
    }

    public void setTrackSelector(TrackSelector trackSelector) {
        mTrackSelector = trackSelector;
    }

    public void setRenderersFactory(RenderersFactory renderersFactory) {
        mRenderersFactory = renderersFactory;
    }

    public void setLoadControl(LoadControl loadControl) {
        mLoadControl = loadControl;
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        Log.i("Tvbox-runtime","echo-setDataSource:"+path);
        currentPlayPath = path;
        currentHeaders = copyHeaders(headers);
        mRetriedAsHls = false;
        mFlippedFamily = false;
        mFlipAbandoned = false;
        mFlipVerified = false;
        mFlipTargetFamily = DualStackPrefs.FAMILY_DEFAULT;
        mFlipToken++;                                    // 作废上一轮还没触发的 watchdog
        mMediaSource = mMediaSourceHelper.getMediaSource(path, copyHeaders(currentHeaders));
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        // 不支持AssetFileDescriptor方式
    }

    @Override
    public void start() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.stop();
    }

    @Override
    public void prepareAsync() {
        if (mInternalPlayer == null)
            return;
        if (mMediaSource == null) return;
        if (mSpeedPlaybackParameters != null) {
            mInternalPlayer.setPlaybackParameters(mSpeedPlaybackParameters);
        }
        mIsPreparing = true;
        mInternalPlayer.setMediaSource(mMediaSource, getStartPosition());
        markStartPositionApplied();
        mInternalPlayer.prepare();
    }

    @Override
    public void reset() {
        if (mInternalPlayer != null) {
            mInternalPlayer.stop();
            mInternalPlayer.clearMediaItems();
            mIsPreparing = false;
            mRetriedAsHls = false;
            mFlipToken++;                                // 作废翻族 watchdog
        }
    }

    @Override
    public boolean keepRenderViewOnReset() {
        return true;
    }

    @Override
    public boolean isPlaying() {
        if (mInternalPlayer == null)
            return false;
        int state = mInternalPlayer.getPlaybackState();
        switch (state) {
            case Player.STATE_BUFFERING:
            case Player.STATE_READY:
                return mInternalPlayer.getPlayWhenReady();
            case Player.STATE_IDLE:
            case Player.STATE_ENDED:
            default:
                return false;
        }
    }

    @Override
    public void seekTo(long time) {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.seekTo(time);
    }

    @Override
    public void release() {
        if (mInternalPlayer != null) {
            mInternalPlayer.removeListener(this);
            mInternalPlayer.release();
            mInternalPlayer = null;
        }
        mIsPreparing = false;
        mSpeedPlaybackParameters = null;
        mFlipToken++;                                    // 作废翻族 watchdog（回调里会判 player 已空）
        mFlipAbandoned = false;
    }

    @Override
    public long getCurrentPosition() {
        if (mInternalPlayer == null)
            return 0;
        return mInternalPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        if (mInternalPlayer == null)
            return 0;
        return mInternalPlayer.getDuration();
    }

    @Override
    public int getBufferedPercentage() {
        return mInternalPlayer == null ? 0 : mInternalPlayer.getBufferedPercentage();
    }

    @Override
    public void setSurface(Surface surface) {
        if (mInternalPlayer != null) {
            mInternalPlayer.setVideoSurface(surface);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (holder == null)
            setSurface(null);
        else
            setSurface(holder.getSurface());
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (mInternalPlayer != null)
            mInternalPlayer.setVolume((leftVolume + rightVolume) / 2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mInternalPlayer != null)
            mInternalPlayer.setRepeatMode(isLooping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
    }

    @Override
    public void setOptions() {
        //准备好就开始播放
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void setSpeed(float speed) {
        PlaybackParameters playbackParameters = new PlaybackParameters(speed);
        mSpeedPlaybackParameters = playbackParameters;
        if (mInternalPlayer != null) {
            mInternalPlayer.setPlaybackParameters(playbackParameters);
        }
    }

    @Override
    public float getSpeed() {
        if (mSpeedPlaybackParameters != null) {
            return mSpeedPlaybackParameters.speed;
        }
        return 1f;
    }

    @Override
    public long getTcpSpeed() {
        return PlayerUtils.getNetSpeed(mAppContext);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        // v99-r85：就绪即证明"翻族这条路通"，必须在 listener 判空之前处理，
        // 否则上层没注册监听时翻族的记账就永远停不下来。
        if (playbackState == Player.STATE_READY) {
            onFlipBootOk();
        }
        if (mPlayerEventListener == null) return;
        if (mIsPreparing) {
            if (playbackState == Player.STATE_READY) {
                mPlayerEventListener.onPrepared();
                mPlayerEventListener.onInfo(MEDIA_INFO_RENDERING_START, 0);
                mIsPreparing = false;
            }
            return;
        }
        switch (playbackState) {
            case Player.STATE_BUFFERING:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, getBufferedPercentage());
                break;
            case Player.STATE_READY:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, getBufferedPercentage());
                break;
            case Player.STATE_ENDED:
                mPlayerEventListener.onCompletion();
                break;
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        Log.e("Tvbox-runtime", "echo-Exo player error: " + currentPlayPath, error);
        if (mFlipAbandoned) {
            // v99-r85：watchdog 已判定翻族失败并把错误交回上层了，
            // 这里吞掉 Exo 迟到的回调，否则会重复触发一次换线
            Log.i("Tvbox-runtime", "echo-Exo error after flip watchdog, ignored: " + currentPlayPath);
            return;
        }
        // v99-r83：403/404 先翻地址族重试一次 —— 同一域名的 IPv4/IPv6 可能落在完全不同后端
        if (retryWithFlippedFamily(error)) {
            return;
        }
        if (retryAsHls(error)) {
            return;
        }
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onError();
        }
    }

    /**
     * 403/404 时翻到另一个地址族重试一次（v99-r83，v99-r85 重做判据）。
     *
     * <p>实测场景：移动 OTT 直播地址 {@code ott.fj.chinamobile.com/PLTV/.../index.m3u8}，
     * IPv6 走 OTT 自己的 CDN 正常出流；IPv4 被 302 到 IPTV 平台，而该频道号在那边不存在 → 404。</p>
     *
     * <p><b>v85 为什么删掉原来的 {@code hasFamily}</b>：它用<b>系统 DNS</b>
     * {@code InetAddress.getAllByName} 判断"目标族有没有地址"，而真实请求走的是
     * {@code CustomDns → DnsOverHttps}。<b>两套 DNS 结论可以完全不一致</b> ⇒ 判据失效，
     * 实测真机上翻族一次都没执行（日志零 {@code retry flipped family}）。</p>
     *
     * <p>更关键的是：<b>"有地址"根本不是"能连通"</b>。设备有 IPv6 地址但路由不通时，
     * DNS 照样返回 AAAA（见 DualStackResolver 里记的 20.8 秒黑洞），拿地址判断必然放行。</p>
     *
     * <p>所以 v85 换成<b>两层实测判据 + 一层时间兜底</b>：</p>
     * <ol>
     *   <li><b>本机网络</b>（{@link #hasLocalNetwork}，毫秒级、纯本地）：连该族的全局地址都没有，
     *       翻过去必然失败，直接不翻 —— 单栈设备零代价；</li>
     *   <li><b>死族记录</b>（{@link DualStackPrefs#isFamilyDead}）：这个 host 的该族 30 分钟内
     *       实测死过，不再重复付代价；</li>
     *   <li><b>watchdog</b>（{@link #armFlipWatchdog}）：真翻了就以 3 秒为限，
     *       不出画面才判死并记账 —— 这是唯一可信的判据，因为它是"试出来的"。</li>
     * </ol>
     *
     * <p>只重试一次：{@code mFlippedFamily} 由 {@link #setDataSource} 重置。两族都失败就交回上层换线，
     * 并且清掉偏好 —— 说明与地址族无关，别让它继续影响该 host 的其它请求。</p>
     */
    private boolean retryWithFlippedFamily(PlaybackException error) {
        if (mInternalPlayer == null || currentPlayPath == null) {
            return false;
        }
        String host = hostOfPath(currentPlayPath);
        if (host.isEmpty()) {
            return false;
        }
        int code = httpStatusOf(error);
        if (code != 403 && code != 404) {
            return false;
        }
        if (mFlippedFamily) {
            if (mFlipVerified) {
                // 翻族后确实就绪过，说明这条路是通的：这次失败与地址族无关，别把族判死
                DualStackPrefs.clear(host);
                Log.i("Tvbox-runtime", "echo-Exo flip-family later error: " + host
                        + " code=" + code + " (family proven ok, not marked dead)");
                return false;
            }
            // 翻过去之后又 403/404 ⇒ 目标族这条路确实没有这个资源，记账，别再试
            DualStackPrefs.clear(host);
            DualStackPrefs.markFamilyDead(host, mFlipTargetFamily);
            Log.i("Tvbox-runtime", "echo-Exo flip-family exhausted: " + host
                    + " code=" + code + " dead=v" + mFlipTargetFamily);
            return false;
        }
        int target = DualStackPrefs.preferFamily(host) == DualStackPrefs.FAMILY_V6
                ? DualStackPrefs.FAMILY_V4 : DualStackPrefs.FAMILY_V6;
        if (!hasLocalNetwork(target)) {
            // 本机这一族连地址都没有，翻过去 100% 失败，别浪费一轮超时
            Log.i("Tvbox-runtime", "echo-Exo flip skipped: no local v" + target + " " + host);
            return false;
        }
        if (DualStackPrefs.isFamilyDead(host, target)) {
            Log.i("Tvbox-runtime", "echo-Exo flip skipped: v" + target
                    + " known dead " + host);
            return false;
        }
        mFlippedFamily = true;
        mFlipTargetFamily = target;
        DualStackPrefs.setPreferFamily(host, target);
        mMediaSourceHelper.evictConnections();           // 不清连接池会复用旧族连接，翻族形同虚设
        Log.i("Tvbox-runtime", "echo-Exo retry flipped family: " + host
                + " code=" + code + " -> v" + target + " " + currentPlayPath);
        mMediaSource = mMediaSourceHelper.getMediaSource(currentPlayPath, copyHeaders(currentHeaders));
        mIsPreparing = true;
        mInternalPlayer.setMediaSource(mMediaSource, getStartPosition());
        markStartPositionApplied();
        mInternalPlayer.prepare();
        mInternalPlayer.setPlayWhenReady(true);
        armFlipWatchdog(host, target);
        return true;
    }

    /**
     * 翻族重试的起播 watchdog（v99-r85）。
     *
     * <p>翻过去之后只有两种结局：就绪（{@link #onFlipBootOk} 取消并清账），
     * 或者 3 秒内什么都没发生 —— 后者多半是目标族 SYN 黑洞，靠 Exo 自己的
     * {@code connectTimeout}(8s) 或上层 15 秒起播预算兜底都太慢。</p>
     *
     * <p>超时后：判该族死 → 清偏好 → 主动把错误交回上层换线。
     * 同时置 {@link #mFlipAbandoned}，让 Exo 迟到的 {@code onPlayerError} 被吞掉，
     * 避免同一次失败触发两轮换线。</p>
     */
    private void armFlipWatchdog(final String host, final int targetFamily) {
        final int token = ++mFlipToken;
        sMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token != mFlipToken) {
                    return;                              // 已切台 / 已就绪 / 已释放
                }
                if (mInternalPlayer == null || mFlipAbandoned) {
                    return;
                }
                mFlipAbandoned = true;
                DualStackPrefs.clear(host);
                DualStackPrefs.markFamilyDead(host, targetFamily);
                Log.i("Tvbox-runtime", "echo-Exo flip watchdog fired: v" + targetFamily
                        + " unreachable in " + FLIP_BOOT_WATCHDOG_MS + "ms, host=" + host);
                if (mPlayerEventListener != null) {
                    mPlayerEventListener.onError();
                }
            }
        }, FLIP_BOOT_WATCHDOG_MS);
    }

    /** 翻族后的这次起播真的就绪了：撤销 watchdog、清掉死亡记录。 */
    private void onFlipBootOk() {
        if (!mFlippedFamily || mFlipAbandoned || mFlipVerified) {
            return;
        }
        mFlipVerified = true;
        mFlipToken++;                                    // 作废 watchdog
        String host = hostOfPath(currentPlayPath);
        if (!host.isEmpty()) {
            DualStackPrefs.clearFamilyDead(host, mFlipTargetFamily);
            Log.i("Tvbox-runtime", "echo-Exo flip family ok: v" + mFlipTargetFamily + " " + host);
        }
    }

    /** 从异常链里取 HTTP 状态码；拿不到返回 0。 */
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

    /**
     * 本机是否有该族的<b>可路由</b>地址（v99-r85 替代 hasFamily）。
     *
     * <p>只看本机接口，不发包、不走 DNS，毫秒级返回，可以安全地在主线程调用。
     * 判据是"这一族到底有没有网"，不是"DNS 说这个域名有没有这个族的地址"。</p>
     *
     * <p>IPv6 特意排除 link-local（{@code fe80::/10}）：那种地址只在本链路内有效，
     * 连不到公网 CDN，留着它会让判据永远为真、翻族永远白翻。</p>
     *
     * <p>任何异常一律 false（等价于"没有这一族"），退化成"不翻、直接换线"，
     * 也就是 r85 之前的耗时，不会更糟。</p>
     */
    private static boolean hasLocalNetwork(int family) {
        try {
            List<InetAddress> addrs = new ArrayList<>();
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            if (nifs == null) {
                return false;
            }
            while (nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                if (nif == null || !nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    if (ia != null && ia.getAddress() != null) {
                        addrs.add(ia.getAddress());
                    }
                }
            }
            return containsFamily(addrs, family);
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 判据本体（v99-r85 抽出来单独存在的原因：它必须能被真 JVM 直接验证）。
     *
     * <p>抽出来之前这段逻辑只能在"真实网卡列表"上跑，而开发机、CI 机、盒子三者的网卡
     * 完全不同 —— 等于永远测不到"没有该族网络"这条分支。抽成纯函数后可以用构造出来的
     * 地址列表逐条断言：回环不算、IPv6 的 link-local 不算、其它算。</p>
     */
    static boolean containsFamily(List<InetAddress> addrs, int family) {
        if (addrs == null) {
            return false;
        }
        for (InetAddress addr : addrs) {
            if (addr == null || addr.isLoopbackAddress()) {
                continue;
            }
            if (family == DualStackPrefs.FAMILY_V4 && addr instanceof Inet4Address) {
                return true;
            }
            if (family == DualStackPrefs.FAMILY_V6 && addr instanceof Inet6Address
                    && !addr.isLinkLocalAddress()) {
                return true;
            }
        }
        return false;
    }

    /** 取 URL 的 host；非法或 IP 字面量返回 ""（IP 字面量没有"选族"余地）。 */
    private String hostOfPath(String path) {
        try {
            String host = Uri.parse(path).getHost();
            if (host == null) {
                return "";
            }
            if (host.indexOf(':') >= 0) {
                return "";                               // IPv6 字面量
            }
            if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                return "";                               // IPv4 字面量
            }
            return host;
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean retryAsHls(PlaybackException error) {
        if (mRetriedAsHls || mInternalPlayer == null || currentPlayPath == null) {
            return false;
        }
        if (!isParsingError(error)) {
            return false;
        }
        mRetriedAsHls = true;
        Log.i("Tvbox-runtime", "echo-Exo retry as HLS: " + currentPlayPath);
        mMediaSource = mMediaSourceHelper.getHlsMediaSource(currentPlayPath, copyHeaders(currentHeaders));
        mIsPreparing = true;
        mInternalPlayer.setMediaSource(mMediaSource, getStartPosition());
        markStartPositionApplied();
        mInternalPlayer.prepare();
        mInternalPlayer.setPlayWhenReady(true);
        return true;
    }

    private boolean isParsingError(PlaybackException error) {
        int errorCode = error.errorCode;
        return errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
    }

    private Map<String, String> copyHeaders(Map<String, String> headers) {
        return headers == null ? null : new HashMap<>(headers);
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onVideoSizeChanged(videoSize.width, videoSize.height);
            if (videoSize.unappliedRotationDegrees > 0) {
                mPlayerEventListener.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, videoSize.unappliedRotationDegrees);
            }
        }
    }
}
