package com.github.tvbox.osc.player;

import android.content.Context;
import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AudioTrackMemory;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.misc.IMediaFormat;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;
import xyz.doikki.videoplayer.ijk.IjkPlayer;

public class IjkMediaPlayer extends IjkPlayer {

    private IJKCode codec = null;
    protected String currentPlayPath;
    private static AudioTrackMemory memory;
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    private static final String DEFAULT_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/json;q=0.9";

    public IjkMediaPlayer(Context context, IJKCode codec) {
        super(context);
        this.codec = codec;
        memory = AudioTrackMemory.getInstance(context);
    }

    /**
     * 先<b>同步</b>掐掉音频输出，再交给父类异步回收 native 资源。
     *
     * <p><b>为什么必须覆写：</b>父类 {@code IjkPlayer.release()} 是「异步 + 吞异常」的 ——
     * 摘掉各类 listener 之后，它开一个线程去调 native {@code release()}，主线程立刻返回；
     * 那个线程里抛出的任何异常都被 {@code printStackTrace()} 吞掉，上层既收不到通知、
     * 也没有重试的机会。native 那一次释放一旦失败或卡住，播放器就<strong>继续出声</strong>，
     * 而 Java 侧的引用此时已被置空，再也没有人能去停它 —— 只能等进程被杀。</p>
     *
     * <p><b>实测对应现象：</b>「看半小时直播，退出回到首页，声音还在」。
     * 残留的那一路播的是同一个频道同一个 URL，被当前正在播的那一路盖着，
     * 平时听不出来；等退出时当前这路被 release 掉，残留的那路才暴露出来。</p>
     *
     * <p><b>为什么不用父类的 {@code stop()}：</b>它在状态不对时会回调
     * {@code mPlayerEventListener.onError()}，退出 / 换源时那会误触发一次失败重试。
     * 这里直接操作原生播放器，副作用不外溢。</p>
     */
    @Override
    public void release() {
        try {
            if (mMediaPlayer != null) {
                // 同步调用：音频输出当场停掉；之后 native 释放即便失败也不会再有声音
                mMediaPlayer.stop();
            }
        } catch (Throwable ignored) {
            // 状态不对时 native 会抛 IllegalStateException，不影响后续回收
        }
        super.release();
    }

    @Override
    public void setOptions() {
        super.setOptions();
        IJKCode codecTmp = this.codec != null ? this.codec : ApiConfig.get().getCurrentIJKCode();
        LinkedHashMap<String, String> options = codecTmp == null ? null : codecTmp.getOption();
        if (options != null) {
            for (String key : options.keySet()) {
                String value = options.get(key);
                String[] opt = key.split("\\|");
                int category = Integer.parseInt(opt[0].trim());
                String name = opt[1].trim();
                // 原先靠 Long.parseLong 抛异常来区分「数值 / 字符串」选项。
                // 一条配置里绝大部分是字符串（fflags=fastseek 之类），每次起播
                // 都要构造几十个 NumberFormatException（带完整栈回溯），纯浪费。
                // 先做一次廉价的数字判断，只有确实是数字才走 parseLong。
                if (isNumericValue(value)) {
                    mMediaPlayer.setOption(category, name, Long.parseLong(value));
                } else {
                    mMediaPlayer.setOption(category, name, value);
                }
            }
        }
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 30);

        // 设置视频流格式
//        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", tv.danmaku.ijk.media.player.IjkMediaPlayer.SDL_FCC_RV32);

        //开启内置字幕
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", -1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT,"safe",0);

        if(Hawk.get(HawkConfig.PLAYER_IS_LIVE, false)){
            // ★ 缓存水位（毫秒）：原先 300ms 太贴近临界 —— 缓冲区几乎永远处于
            //   "刚够/不够"的边缘，解复用器每个分片周期都把它顶下去再拉上来，
            //   实测 IJK 每秒打出约 14 对 FFP_MSG_BUFFERING_START/END。
            //   放宽到 1000ms 后抖动基本消失（上层不再每秒被折腾二十几次，
            //   loading 也不再闪），代价是直播延迟增加约 0.5~1 秒。
            //   这是「低延迟」与「稳定不抖」的取舍点，要更低延迟就把它调回 300。
            //
            //   但对「分片长 + 每片重建连接」的源，1 秒仍然太薄：实测该类源
            //   （IP:非标准端口的备份线路）一个分片约 10 秒、每片都要重新
            //   WILL_HTTP_OPEN 一次，单片下载只要慢一点就欠载 → 周期性卡顿。
            //   1500ms 是延迟与抗抖的折中；还在卡就调到 3000，代价是延迟再 +1~2 秒。
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 1500);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1);
            // 起播至少攒够 2 帧再出画面。原来是 1 帧（抢起播速度），对慢源等于
            // 刚出画面就没货，紧接着立刻再卡一次。
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 2);
            // ★ 解码线程数：原来是写死 1，注释假定「直播一定走 mediacodec 硬解，
            //   该选项不生效」。但硬解用不用得上取决于设备：没有硬件解码器
            //   （模拟器、部分盒子）、或 mediacodec 不支持该编码、或分辨率超过
            //   硬解能力时，ijk 会**静默**回落到 ffmpeg 软解 —— 这时 threads=1
            //   就是拿单线程去解 1080p，直接表现为卡顿，而上层完全不知情。
            //
            //   判定依据：软解时 ijk 的 vout overlay 是 RV32（ffmpeg 转 RGB 上屏），
            //   硬解时是 AMC overlay。实测日志里是
            //   "SDL_VoutFFmpeg_CreateOverlay(w=1920, h=1080, fmt=RV32)" → 软解。
            //
            //   硬解本来就不走 ffmpeg 解码线程，所以给多线程没有任何副作用。
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", liveDecodeThreads());
        }else{
            // ── 点播：抗抖动优先，但绝不做无限预读 ──
            //
            // 原值 max_cached_duration=3000 + infbuf=0 是从「降低延迟」的思路来的，
            // 但对点播这个目标本身是错的：用户从上次进度续播，几百毫秒的延迟
            // 毫无价值，而只囤 3 秒、且不预读（infbuf=0）意味着源侧一旦抖一下
            // 就立刻欠载 → 表现为「网速够但一卡一卡」。
            //
            // 修正为「有限水位 + 有限预读」，而不是「无限预读」：
            //
            //   · max_cached_duration = 15000（15 秒）
            //     为什么不是 30000：这份缓存落在 IJK 的**堆内存**里（不像 Exo 有
            //     可回收的 Allocator），字节数 ≈ 水位 × 码率。1080p 常见 6~8Mbps，
            //     30 秒就是 22~30MB；遇到高码率 4K 源能到 60MB+。低配盒子的堆本就
            //     紧张，和播放器其它缓冲区一叠加就是 OOM。
            //     15 秒足以吸收绝大多数 CDN 抖动（实测卡顿周期通常在 1~3 秒量级），
            //     再往上加收益迅速衰减、内存风险却线性上升。
            //
            //   · infbuf 保持 0（**已回退原方案里的 infbuf=1**）
            //     infbuf 的语义是"缓冲永不因满而阻塞"，设计场景是直播追帧。
            //     用在点播上，ffmpeg 会把输入一路读到底、不管播放是否跟得上：
            //       - 快源：猛读整片，把带宽和内存一次性吃满，反而拖慢同网其它请求；
            //       - 慢源：读得再猛也还是那么多带宽，对"让数据先到"毫无帮助，
            //               真正的收益来自 max_cached_duration 给的时间窗。
            //     即"无限预读对慢源无用、对快源有害"，故去掉。
            //
            //   注意：直播分支同样必须保持 infbuf=0，否则会不断追不上直播边缘。
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 15000);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 0);
            // 起播至少攒够 2 帧再出画面：慢源下「抢第 1 帧就出画面」会导致
            // 刚出画面立刻又卡，观感比多等一小会儿更差（与直播分支同款处理）。
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 2);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", "2");
        }
//        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start", 1);//强制音画同步
    }

    /**
     * 直播软解时的解码线程数。
     *
     * <p>硬解（mediacodec）压根不走 ffmpeg 的解码线程，这个值不起作用；只有软解生效。
     * 所以按「最坏情况 = 1080p 软解」来配，代价为零。</p>
     */
    private static String liveDecodeThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return String.valueOf(cores <= 2 ? 2 : Math.min(4, cores / 2));
    }

    /** 廉价的整数判断，用于避免靠异常来区分选项值类型。 */
    private static boolean isNumericValue(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int start = value.charAt(0) == '-' ? 1 : 0;
        if (start >= value.length()) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static final String ITV_TARGET_DOMAIN = "gslbserv.itv.cmvideo.cn";
    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        try {
            switch (getStreamType(path)) {
                case RTSP_UDP_RTP:
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 1);
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp");
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp");
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 512 * 1000);
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2 * 1000 * 1000);
                    break;

                case CACHE_VIDEO:
                    if (Hawk.get(HawkConfig.IJK_CACHE_PLAY, false)) {
                        String cachePath = FileUtils.getCachePath() + "/ijkcaches/";
                        File cacheFile = new File(cachePath);
                        if (!cacheFile.exists()) cacheFile.mkdirs();
                        String tmpMd5 = MD5.string2MD5(path);
                        String cacheFilePath = cachePath + tmpMd5 + ".file";
                        String cacheMapPath = cachePath + tmpMd5 + ".map";

                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_file_path", cacheFilePath);
                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_map_path", cacheMapPath);
                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "parse_cache_map", 1);
                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "auto_save_map", 1);
                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_max_capacity", 60 * 1024 * 1024);
                        path = "ijkio:cache:ffio:" + path;
                    }
                    break;

                case M3U8:
                    // 直播且是ijk的时候自动自动走代理解决DNS
                    if (Hawk.get(HawkConfig.PLAYER_IS_LIVE, false) ) {
                        URI uri = new URI(path);
                        String host = uri.getHost();
                        if(ITV_TARGET_DOMAIN.equalsIgnoreCase(host))path = ControlManager.get().getAddress(true) + "proxy?go=live&type=m3u8&url="+ URLEncoder.encode(path,"UTF-8");
                    }
                    break;

                default:
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        setDataSourceHeader(headers);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "ijkio,ffio,async,cache,crypto,file,dash,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data");
        currentPlayPath = path;
        super.setDataSource(path, null);
    }

    /**
     * 解析 URL
     */
    private static final int RTSP_UDP_RTP = 1;
    private static final int CACHE_VIDEO = 2;
    private static final int M3U8 = 3;
    private static final int OTHER = 0;

    private int getStreamType(String path) {
        if (TextUtils.isEmpty(path)) {
            return OTHER;
        }
        // 低成本检查 RTSP/UDP/RTP 类型
        String lowerPath = path.toLowerCase();
        if (lowerPath.startsWith("rtsp://") || lowerPath.startsWith("udp://") || lowerPath.startsWith("rtp://")) {
            return RTSP_UDP_RTP;
        }
        String cleanUrl = path.split("\\?")[0];
        if (cleanUrl.endsWith(".m3u8")) {
            return M3U8;
        }
        if (cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".mkv") || cleanUrl.endsWith(".avi")) {
            return CACHE_VIDEO;
        }
        return OTHER;
    }

    private void setDataSourceHeader(Map<String, String> headers) {
        LinkedHashMap<String, String> playHeaders = new LinkedHashMap<>();
        String userAgent = null;
        boolean hasAccept = false;
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                    continue;
                }
                if (ExoMediaSourceHelper.HEADER_FORMAT.equalsIgnoreCase(key)) {
                    continue;
                }
                if ("User-Agent".equalsIgnoreCase(key)) {
                    userAgent = value.trim();
                } else {
                    if ("Accept".equalsIgnoreCase(key)) {
                        hasAccept = true;
                    }
                    playHeaders.put(key, value.trim());
                }
            }
        }
        if (TextUtils.isEmpty(userAgent)) {
            userAgent = DEFAULT_USER_AGENT;
        }
        if (!hasAccept) {
            playHeaders.put("Accept", DEFAULT_ACCEPT);
        }
        if (!TextUtils.isEmpty(userAgent)) {
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", userAgent);
        }
        if (playHeaders.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : playHeaders.entrySet()) {
                sb.append(entry.getKey());
                sb.append(": ");
                sb.append(entry.getValue());
                sb.append("\r\n");
            }
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", sb.toString());
        }
    }

    public TrackInfo getTrackInfo() {
        IjkTrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
        if (trackInfo == null) return null;
        TrackInfo data = new TrackInfo();
        int subtitleSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        int videoSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO);
        int index = 0;
        for (IjkTrackInfo info : trackInfo) {
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                if (isAttachedPicture(info)) {
                    LOG.i("echo-ijk-skip-attached-picture:" + info.getInfoInline());
                    index++;
                    continue;
                }
                TrackInfoBean v = new TrackInfoBean();
                String name = processVideoName(info.getInfoInline());
                String language = getFriendlyLanguage(info.getLanguage(), info.getInfoInline());
                v.language = language;
                v.name = buildDisplayName("视轨", data.getVideo().size() + 1, language, name);
                v.trackId = index;
                v.index = index;
                v.selected = index == videoSelected;
                data.addVideo(v);
            }
            else if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {//音轨信息
                TrackInfoBean a = new TrackInfoBean();
                String name = processAudioName(info.getInfoInline());
                a.language = info.getLanguage();
                if(name.startsWith("aac"))a.language="中文";
                a.name = name;
                String language = getFriendlyLanguage(a.language, info.getInfoInline());
                a.language = language;
                a.name = buildDisplayName("\u97f3\u8f68", data.getAudio().size() + 1, language, name);
                a.trackId = index;
                a.index = index;
                a.selected = index == audioSelected;
                // 如果需要，还可以检查轨道的描述或标题以获取更多信息
                data.addAudio(a);
            }
            else if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {//内置字幕
                if (!isTextSubtitle(info.getInfoInline())) {
                    LOG.i("echo-ijk-skip-bitmap-subtitle:" + info.getInfoInline());
                    index++;
                    continue;
                }
                TrackInfoBean t = new TrackInfoBean();
                t.name = info.getInfoInline();
                t.language = info.getLanguage();
                String language = getFriendlyLanguage(t.language, t.name);
                t.language = language;
                t.name = buildDisplayName("\u5b57\u5e55", data.getSubtitle().size() + 1, language, "");
                t.trackId = index;
                t.index = index;
                t.selected = index == subtitleSelected;
                data.addSubtitle(t);
            }
            index++;
        }
        return data;
    }
    // 处理音轨名称格式
    private String processAudioName(String rawName) {
        if (rawName == null) return "";
        return rawName.replace("AUDIO,", "")
                .replace("N/A,", "")
                .replace(" ", "")
                .replaceAll("^,+|,+$", "")
                .replace(",", " / ");
    }

    private String processVideoName(String rawName) {
        if (rawName == null) return "";
        return rawName.replace("VIDEO,", "")
                .replace("N/A,", "")
                .replace(" ", "")
                .replaceAll("^,+|,+$", "")
                .replace(",", " / ");
    }

    private boolean isAttachedPicture(IjkTrackInfo info) {
        IMediaFormat format = info.getFormat();
        if (format == null) return false;
        String codecName = format.getString(IjkMediaMeta.IJKM_KEY_CODEC_NAME);
        return "mjpeg".equalsIgnoreCase(codecName)
                && format.getInteger(IjkMediaMeta.IJKM_KEY_BITRATE) <= 0
                && format.getInteger(IjkMediaMeta.IJKM_KEY_FPS_NUM) <= 0;
    }

    private boolean isTextSubtitle(String rawName) {
        String value = rawName == null ? "" : rawName.toLowerCase();
        return !value.contains("pgs")
                && !value.contains("hdmv")
                && !value.contains("dvd subtitle")
                && !value.contains("dvd_subtitle")
                && !value.contains("dvb subtitle")
                && !value.contains("dvb_subtitle")
                && !value.contains("xsub")
                && !value.contains("vobsub")
                && !value.contains("bitmap");
    }

    private String getFriendlyLanguage(String language, String rawInfo) {
        String text = ((language == null ? "" : language) + " " + (rawInfo == null ? "" : rawInfo)).toLowerCase();
        if (text.contains("yue") || text.contains("cantonese") || text.contains("\u7ca4") || text.contains("\u5e7f\u4e1c")) {
            return "\u7ca4\u8bed";
        }
        if (text.contains("zh") || text.contains("chi") || text.contains("zho") || text.contains("chs")
                || text.contains("cht") || text.contains("cmn") || text.contains("\u4e2d")
                || text.contains("\u56fd\u8bed") || text.contains("\u666e\u901a\u8bdd")) {
            return "\u56fd\u8bed";
        }
        if (text.contains("en") || text.contains("eng") || text.contains("english") || text.contains("\u82f1")) {
            return "\u82f1\u8bed";
        }
        if (text.contains("ja") || text.contains("jpn") || text.contains("japanese") || text.contains("\u65e5")) {
            return "\u65e5\u8bed";
        }
        if (text.contains("ko") || text.contains("kor") || text.contains("korean") || text.contains("\u97e9")) {
            return "\u97e9\u8bed";
        }
        if (text.contains("tha") || text.contains("thai") || text.contains("th")) {
            return "\u6cf0\u8bed";
        }
        return "";
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

    public void setTrack(int trackIndex) {
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        int subtitleSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        if (trackIndex!=audioSelected && trackIndex!=subtitleSelected){
            mMediaPlayer.selectTrack(trackIndex);
        }
    }
    public void setTrack(int trackIndex,String playKey) {
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        if (trackIndex!=audioSelected){
            if (!playKey.isEmpty()) {
                memory.save(playKey, trackIndex);
            }
            mMediaPlayer.selectTrack(trackIndex);
        }
    }

    public void setOnTimedTextListener(IMediaPlayer.OnTimedTextListener listener) {
        mMediaPlayer.setOnTimedTextListener(listener);
    }

    public void loadDefaultTrack(TrackInfo trackInfo,String playKey) {
        if(trackInfo!=null && trackInfo.getAudio().size()>1){
            Integer trackIndex = memory.ijkLoad(playKey);
            if (trackIndex == -1) {
                int firsIndex=trackInfo.getAudio().get(0).index;
                setTrack(firsIndex);
                return;
            };
            setTrack(trackIndex);
        }
    }
}
