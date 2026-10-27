package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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
import xyz.doikki.videoplayer.util.DualStackProbe;
import xyz.doikki.videoplayer.util.PlayLog;
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
    /** v99-r87：翻族的目标族，用于 watchdog 与成功清账。 */
    private int mFlipTargetFamily = DualStackPrefs.FAMILY_DEFAULT;
    /**
     * v99-r85：本次翻族尝试已被 watchdog 放弃（3 秒没就绪）。
     *
     * <p>置位后本次播放不再翻族、也不再重复上抛 onError —— watchdog 已经交回上层一次，
     * 换线会重新 setDataSource 把这个标志清掉。<b>它只影响这一次尝试，不产生任何"以后别试"的记录。</b></p>
     */
    private boolean mFlipAbandoned;
    /**
     * v99-r85：翻族之后确实就绪过（这条路是真的通的）。
     *
     * <p>v87 起它只用于<b>日志措辞</b>：区分"翻过去就再没起来"与"翻过去通过、之后才断"。
     * 两种情况现在都不写记录 —— 前者可能只是线路没了，后者本就与地址族无关。</p>
     */
    private boolean mFlipVerified;
    /**
     * v99-r85：翻族重试的起播预算（v87 起只是"这一次尝试"的时限，不是判决）。
     *
     * <p>为什么单给翻族一个短预算：翻族是"已知这条线路有问题"之后的二次尝试，
     * 两族都正常时首帧实测都在 1 秒内（同机日志 0.26~0.64 秒）；3 秒仍不出画面，
     * 说明这条路的首选地址多半是 SYN 黑洞，继续等 8 秒 connectTimeout 纯属白等。
     * 到点只放弃本次尝试，下一次照样会重新翻（没有禁用记录）。</p>
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
        PlayLog.i("echo-setDataSource:"+path);
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
        PlayLog.e("echo-Exo player error: " + currentPlayPath, error);
        if (mFlipAbandoned) {
            // v99-r85：watchdog 已判定翻族失败并把错误交回上层了，
            // 这里吞掉 Exo 迟到的回调，否则会重复触发一次换线
            PlayLog.i("echo-Exo error after flip watchdog, ignored: " + currentPlayPath);
            return;
        }
        // v99-r83：403/404 先翻地址族重试一次 —— 同一域名的 IPv4/IPv6 可能落在完全不同后端
        if (retryWithFlippedFamily(error)) {
            return;
        }
        if (retryAsHls(error)) {
            return;
        }
        // v99-r89：连接层失败（根本没拿到响应）才是"这一族的路不通"的证据。
        // 反过来，403/404 说明服务器活着、只是这个资源不在它这儿 —— 那是信道级/边缘级的，
        // 实测同一频道 25 分钟前在 v6 上 404、之后在 v6 上 200，拿它降级族偏好会误伤一整串。
        if (isTransportError(error) && dropFamilyOnTransportError()) {
            return;
        }
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onError();
        }
    }

    /**
     * 连接层失败时降级该 host 的族偏好（v99-r89）。
     *
     * <p>为什么单独抽出来：降级与否只看"是不是连接层失败"，这是族级信号；
     * 而 4xx 是资源级信号（见 {@link #isTransportError} 的注释）。</p>
     *
     * <p>为什么保留了还要交回上层：降级只影响<b>下一次</b>先试哪个族，
     * 本次播放已经失败，必须让上层换线 —— 所以返回 false 继续往下走。</p>
     */
    private boolean dropFamilyOnTransportError() {
        try {
            String host = hostOfPath(currentPlayPath);
            if (host.isEmpty()) {
                return false;
            }
            if (DualStackPrefs.preferFamily(host) == DualStackPrefs.FAMILY_DEFAULT) {
                return false;                        // 本来就没偏好，不用记日志
            }
            DualStackPrefs.clear(host);              // 只降级族偏好，地址证据由探测/轮转各自纠
            PlayLog.i("echo-Exo transport failure => drop family pref (next time default order) host=" + host);
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 是否"连接层失败"：异常链里是 {@link java.io.IOException} 但<b>不是</b> HTTP 状态码异常。
     *
     * <p>{@code InvalidResponseCodeException} 本身也继承 IOException，所以必须先判它 ——
     * 否则 404 会被当成连接层失败，族偏好又会被一次偶发 404 打掉。</p>
     */
    private boolean isTransportError(Throwable error) {
        Throwable t = error;
        for (int guard = 0; t != null && guard < 8; guard++) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                return false;
            }
            if (t instanceof java.io.IOException) {
                return true;
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
     * 403/404 时翻到另一个地址族重试一次（v99-r83，v85 重做判据，v87 去掉全部禁用记录）。
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
     * <p><b>v87 删掉了 r86 的两张死亡表</b>（资源级 / 路径级）。它们命中就把这条线路整段跳过，
     * 等于让某个 URL 从此不再被真正请求 —— 而实测里"3 秒没起来"可能只是撞上黑洞地址或网络慢，
     * 11 秒后同一族 95ms 就正常回了。现在<b>每次都真的翻</b>：坏线路每次多付 3 秒，
     * 换来的是任何一次误判都不会长期挡住一条线路。</p>
     *
     * <p>现在只剩两条判据，且都不涉及"记住谁不行"：</p>
     * <ol>
     *   <li><b>本机网络</b>（{@link #hasLocalNetwork}，毫秒级、纯本地）：这台设备连该族的全局地址
     *       都没有，翻过去物理上不可能成功 —— 这是"本机没有这扇门"，不是"这门坏过"；</li>
     *   <li><b>watchdog</b>（{@link #armFlipWatchdog}）：真翻了就以 3 秒为限，到点只放弃<b>这一次
     *       尝试</b>并交回上层换线，不写任何"以后别试"的记录。</li>
     * </ol>
     *
     * <p>失败信息只用于<b>改进下一次的尝试顺序</b>：探测到目标族里哪个地址活着，下次就优先试它；
     * 探不出结论就轮转，保证多个地址会被逐个试到（见 {@link DualStackPrefs}）。</p>
     *
     * <p>只重试一次：{@code mFlippedFamily} 由 {@link #setDataSource} 重置。两族都失败就交回上层换线，
     * 并且清掉族偏好 —— 说明与地址族无关，别让它继续影响该 host 的其它请求。</p>
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
            // 两族都 403/404 ⇒ 与地址族无关（多半是资源本身没了）。
            // v87：不记任何"这条 URL 别再用"的记录 —— 源站随时会修、源会更新、调度会变。
            // v88：清掉翻过去那族的地址偏好（这个地址确实没给出资源）。
            // v89：**不再清族偏好**。两族都 404 是资源级结论，和"哪个族通常更好"无关；
            //      清掉只会把上一族攒下的知识一起丢掉，害得后面每一个换台都从默认族重新试探。
            //      这里不是"跳过" —— 每条 URL 依然会依次被两族真实请求一遍。
            DualStackPrefs.clearPreferAddress(host, mFlipTargetFamily);
            PlayLog.i("echo-Exo flip-family exhausted: " + host
                    + " code=" + code + (mFlipVerified ? " (family proven ok)" : "")
                    + " => switch source, no record kept " + currentPlayPath);
            return false;
        }
        int target = DualStackPrefs.preferFamily(host) == DualStackPrefs.FAMILY_V6
                ? DualStackPrefs.FAMILY_V4 : DualStackPrefs.FAMILY_V6;
        // v88：这次 4xx 是**当前族**回的（target 是另一族），说明当前族里排在首位的那个地址
        // 拿不出这个资源。不清掉它，它会一直排第一，直到 2 小时的 TTL 到期。
        // 清的是"建议"不是地址：它仍留在 DNS 顺序里，该试的时候照样会试到。
        DualStackPrefs.clearPreferAddress(host,
                target == DualStackPrefs.FAMILY_V6 ? DualStackPrefs.FAMILY_V4 : DualStackPrefs.FAMILY_V6);
        if (!hasLocalNetwork(target)) {
            // 这台设备连该族的全局地址都没有：翻过去物理上不可能成功（不是"这门坏过"，是"本机没有这门"）
            PlayLog.i("echo-Exo flip skipped: no local v" + target + " " + host);
            return false;
        }
        mFlippedFamily = true;
        mFlipTargetFamily = target;
        DualStackPrefs.setPreferFamily(host, target);
        mMediaSourceHelper.evictConnections();           // 不清连接池会复用旧族连接，翻族形同虚设
        PlayLog.i("echo-Exo retry flipped family: " + host
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
     * 翻族重试的起播 watchdog（v99-r85，v87 改为纯"本次尝试时限"）。
     *
     * <p>翻过去之后只有两种结局：就绪（{@link #onFlipBootOk} 取消它），或者 3 秒内什么都没发生 ——
     * 后者多半是目标族的 SYN 黑洞，靠 Exo 自己的 {@code connectTimeout}(8s) 或上层 15 秒起播预算都太慢。</p>
     *
     * <p>到点后<b>只放弃这一次尝试</b>：主动交回上层换线，并置 {@link #mFlipAbandoned}
     * 让 Exo 迟到的 {@code onPlayerError} 被吞掉（否则一次失败会触发两轮换线）。</p>
     *
     * <p><b>不写任何"这段路径不能用了"的记录。</b>失败只用来改进下一次的顺序：后台探测该族每个地址的
     * TCP 可达性，探到活着的就下次优先试它；探不出结论（防火墙拦探测端口 / 全部不可达 / DNS 没给地址）
     * 就推进轮转游标，下次换一个地址先试。这样跨多次尝试必然把所有地址都试到，
     * 而且因为探测本身会误判，它<b>只影响顺序</b>，绝不影响任何地址的可用性。</p>
     *
     * <p>v88 补充：{@code bumpRotation} 会顺手清掉该族的地址偏好 —— 否则排序层
     * "地址证据 → 轮转"的优先级会让游标在证据有效期内永远轮不到，"换个门牌先敲"就成了空转。</p>
     *
     * <p><b>v89 关键修正：这里不再 {@code clear(host)}。</b>原来那行是整条链路里破坏力最大的一处 ——
     * 族偏好一被清空，排序层就落到"纯 IPv4 优先"的默认分支，地址偏好 / 轮转 / 粘性缓存
     * <b>三级全部不再参与</b>，等于一次 3 秒超时把后面一整串换台都打回原点（实测日志：一次超时之后
     * 连续多个台都在 v4 起点撞 404，其中一台还挂在 8.5 秒的 connectTimeout 上）。
     * 3 秒没出画只说明"这次落到的地址/边缘不行"，不说明"这一族不行" ——
     * 那由探测/轮转去纠正地址级，由 {@link #isTransportError} 的判据去纠正族级。</p>
     */
    private void armFlipWatchdog(final String host, final int targetFamily) {
        final int token = ++mFlipToken;
        final int port = DualStackProbe.portOf(currentPlayPath);
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
                PlayLog.i("echo-Exo flip attempt timeout: v" + targetFamily
                        + " no frame in " + FLIP_BOOT_WATCHDOG_MS + "ms => switch source, no record kept"
                        + " host=" + host);
                // 换线不等探测：探测在后台跑，只用来决定"下次先试哪个地址"。
                if (mPlayerEventListener != null) {
                    mPlayerEventListener.onError();
                }
                DualStackProbe.probeAsync(host, port, targetFamily, new DualStackProbe.Callback() {
                    @Override
                    public void onResult(DualStackProbe.Result r) {
                        if (r == null) {
                            return;
                        }
                        String action;
                        if (r.hasReachable()) {
                            // 有硬证据：这族有地址活着，下次直接优先它（只排序，不排除其它地址）
                            DualStackPrefs.setPreferAddress(host, targetFamily, r.firstReachableIp);
                            DualStackPrefs.clearRotation(host, targetFamily);
                            action = "=> prefer " + r.firstReachableIp + " next time";
                        } else {
                            // 探测没结论：不能据此判断"这族死了"，只能下次换个地址先试
                            int next = DualStackPrefs.bumpRotation(host, targetFamily);
                            action = "=> no conclusion, rotate to index " + next;
                        }
                        PlayLog.i("echo-Exo flip probe: v" + targetFamily
                                + " reachable=" + r.reachable + "/" + r.total
                                + " " + action + " host=" + host);
                    }
                });
            }
        }, FLIP_BOOT_WATCHDOG_MS);
    }

    /** 翻族后的这次起播真的就绪了：撤销 watchdog、清掉轮转游标。 */
    private void onFlipBootOk() {
        if (!mFlippedFamily || mFlipAbandoned || mFlipVerified) {
            return;
        }
        mFlipVerified = true;
        mFlipToken++;                                    // 作废 watchdog
        String host = hostOfPath(currentPlayPath);
        if (!host.isEmpty()) {
            // 成功是"这条顺序对"的最强证据：保留地址级建议（下次还用这个地址），只清轮转游标
            DualStackPrefs.clearRotation(host, mFlipTargetFamily);
            PlayLog.i("echo-Exo flip family ok: v" + mFlipTargetFamily + " " + host);
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
        PlayLog.i("echo-Exo retry as HLS: " + currentPlayPath);
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
