package com.github.tvbox.osc.util;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A′ 方案（本地分片代理 + 跨切片滚动预取）的纯逻辑层。
 *
 * <p>职责：把一条 HLS media playlist 解析成「有序切片列表」，并生成一份把切片行
 * 改写成本地代理地址（{@code /seg.ts?sk=<sessionKey>&i=<序号>}）的新播放列表。
 * 本类<b>不依赖 Android / OkHttp</b>，可在桌面 JVM 上直接单测（见 work\ 下的测试台）。</p>
 *
 * <p>r200：切片行带上了<b>源站真实的容器扩展名</b>（{@code .ts/.m4s/.mp4…}）。
 * 原因：ffmpeg 的 hls demuxer 会检查切片 URL 的<b>路径</b>是否以允许的扩展名结尾，
 * 不满足就报 {@code is not in allowed_segment_extensions} 并直接拒绝打开（连请求都不发）。
 * IJK 当前用的 ffmpeg 4.0 是「按内容探测」，所以旧的无扩展名地址还能播；一旦换成
 * ffmpeg ≥5 的构建就会直接播不了。带上真实扩展名对 Exo/IJK 都无副作用，
 * 同时还能让 Exo 侧 {@code OkHttpDataSource.isMediaUrl()} 重新认出切片、恢复
 * {@code [seg]} 吞吐统计。</p>
 *
 * <p>安全边界（宁可不加速也不弄坏播放）：</p>
 * <ul>
 *   <li>master playlist（含 {@code #EXT-X-STREAM-INF}）不处理——由上层先选最高码率变体；</li>
 *   <li>含 {@code #EXT-X-BYTERANGE} 不处理——切片是同一文件的不同字节区间，按整文件下载会错；</li>
 *   <li>没有 {@code #EXTINF} 或没有任何切片行不处理；</li>
 *   <li>{@code #EXT-X-MAP} / {@code #EXT-X-KEY} 的 URI="..." 仍保留为绝对 CDN 地址，只做绝对化。</li>
 * </ul>
 */
public final class M3u8PrefetchPlan {

    /** 预取窗口硬上限（同时在飞的切片数）。 */
    public static final int HARD_MAX_WINDOW = 4;

    /** 单个切片（一行 media URI）的描述。 */
    public static final class Segment {
        /** 1 基序号，与 {@code /seg?i=} 对应。 */
        public int index;
        /** 绝对 URL。 */
        public String url;
        /** EXTINF 时长（秒），未知为 0。 */
        public double durationSec;
    }

    /** 解析结果。 */
    public static final class Plan {
        /** 是否可做跨切片预取。 */
        public boolean supported;
        /** 不支持时的原因（调试用）。 */
        public String reason = "";
        public final List<Segment> segments = new ArrayList<>();
        /** 改写后的播放列表（切片行指向本地 /seg）。 */
        public String rewritten;
        /** 由 {@code #EXT-X-BITRATE} 得到的码率（Mbps），未知为 0（运行时会按实测补）。 */
        public double bitrateMbps;
    }

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");

    /**
     * fMP4 家族的后缀：这类切片在本地代理路径上统一改写成 {@code .m4s}。
     * 依据 ffmpeg hls.c 的 {@code test_segment()}：mpegts 容器接受
     * {@code ts,m2t,m2ts,mts,mpg,m4s,mpeg,mpegts,html}，mp4/aac 容器在
     * {@code in_fmt->extensions} 之外还额外接受 {@code ts,...,m4s,...,cmfv,cmfa}
     * —— 所以 {@code .ts} 与 {@code .m4s} 对两种真实容器都安全，
     * 而 {@code .mp4} 遇到 TS 内容会被直接拒播。
     */
    private static final Set<String> FMP4_SUFFIX = new HashSet<>(Arrays.asList(
            "m4s", "mp4", "m4v", "m4a", "cmfv", "cmfa", "mov", "3gp", "fmp4", "ismv"));

    private M3u8PrefetchPlan() {
    }

    /**
     * 依据源站切片地址推导本地代理路径上使用的容器扩展名。
     *
     * <p>ffmpeg master 的 hls demuxer 有两道关（{@code libavformat/hls.c} 的
     * {@code test_segment()}）：切片 URL 的<b>路径</b>要落在
     * {@code allowed_segment_extensions} 白名单里，且要和探测出的真实容器相容，
     * 两者按位与才算通过；query 里的字符不算路径，但 ff_match_url_ext 会剥掉
     * query 后再匹配，所以 {@code /seg.ts?sk=..&i=1} 这种写法可以通过。</p>
     *
     * <p>实测（ffmpeg 7.x / master 行为）：{@code .ts} 与 {@code .m4s} 对
     * TS 与 fMP4 两种真实容器都安全；{@code .mp4} 遇到 TS 内容会被直接拒播
     * （{@code detected format mpegts extension none mismatches allowed extensions}）。</p>
     */
    public static String containerExt(String url) {
        return containerExt(url, false);
    }

    /**
     * @param url            源站切片地址
     * @param hasInitSection 该 playlist 是否含 {@code #EXT-X-MAP}（fMP4 的权威标志）
     */
    public static String containerExt(String url, boolean hasInitSection) {
        if (url == null) {
            return hasInitSection ? ".m4s" : ".ts";
        }
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int h = path.indexOf('#');
        if (h >= 0) {
            path = path.substring(0, h);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            // 源站没给后缀：靠 #EXT-X-MAP 判断容器族
            return hasInitSection ? ".m4s" : ".ts";
        }
        String suffix = name.substring(dot + 1).toLowerCase(Locale.US);
        if (FMP4_SUFFIX.contains(suffix)) {
            return ".m4s";
        }
        // 源站后缀不在 fMP4 族，但 playlist 里有 EXT-X-MAP ⇒ 实际是 fMP4，
        // 不能用 .ts（mpegts 容器不接受 .mp4 内容，反之亦然）。
        return hasInitSection ? ".m4s" : ".ts";
    }

    /**
     * @param baseUrl      playlist 的请求 URL（用于把相对切片地址绝对化）
     * @param content      净化后的 playlist 文本（正常已是绝对 URL）
     * @param localPrefix  前缀，形如 {@code /seg?sk=<sk>&i=}，后面直接拼 1 基序号
     */
    public static Plan parse(String baseUrl, String content, String localPrefix) {
        Plan plan = new Plan();
        if (content == null || !content.startsWith("#EXTM3U")) {
            plan.reason = "not a playlist";
            return plan;
        }
        String[] rawLines = content.split("\n", -1);
        List<String> out = new ArrayList<>(rawLines.length + 8);
        // r200：把本地前缀拆成「路径段」和「索引参数段」，好把容器扩展名插在两者之间。
        //   形如 "/seg?sk=SK&i=" → localPath="/seg"，localTail="?sk=SK&i="
        //   最终切片行 = /seg + .ts + ?sk=SK&i=1
        String localPath = localPrefix;
        String localTail = "";
        int q = localPrefix.indexOf('?');
        if (q >= 0) {
            localPath = localPrefix.substring(0, q);
            localTail = localPrefix.substring(q);
        }

        boolean sawExtinf = false;
        boolean sawStreamInf = false;
        boolean sawByteRange = false;
        boolean sawInitSection = false;
        double pendingDuration = -1;
        double bitrateKbps = 0;
        int index = 0;

        // r200：先扫一遍有没有 #EXT-X-MAP —— 它是 fMP4 的权威标志，
        //   用来在源站切片没有可识别后缀时也能选对容器扩展名。
        for (String raw : rawLines) {
            String t = raw.trim();
            if (t.startsWith("#EXT-X-MAP")) {
                sawInitSection = true;
                break;
            }
        }

        for (String raw : rawLines) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                out.add(line);
                continue;
            }
            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                    sawStreamInf = true;
                } else if (trimmed.startsWith("#EXT-X-BYTERANGE")) {
                    sawByteRange = true;
                } else if (trimmed.startsWith("#EXT-X-BITRATE:")) {
                    bitrateKbps = parseDouble(trimmed.substring("#EXT-X-BITRATE:".length()).trim(), 0);
                } else if (trimmed.startsWith("#EXTINF:")) {
                    sawExtinf = true;
                    pendingDuration = parseExtinf(trimmed);
                }
                // 保留标签行，但把 URI="..." 里可能存在的相对地址绝对化（MAP/KEY 等）。
                out.add(absolutizeTagUris(baseUrl, line));
                continue;
            }
            // 媒体切片行
            String abs = absolutize(baseUrl, trimmed);
            if (abs == null) {
                plan.reason = "bad segment uri: " + trimmed;
                return plan;
            }
            index++;
            Segment seg = new Segment();
            seg.index = index;
            seg.url = abs;
            seg.durationSec = pendingDuration > 0 ? pendingDuration : 0;
            plan.segments.add(seg);
            // r200：切片行带上源站真实容器扩展名（/seg.ts?sk=..&i=N），
            //   让新版 ffmpeg 的 hls demuxer（按路径扩展名白名单校验）也能正常打开。
            out.add(localPath + containerExt(abs, sawInitSection) + localTail + index);
            pendingDuration = -1;
        }

        if (sawStreamInf) {
            plan.reason = "master playlist";
            return plan;
        }
        if (sawByteRange) {
            plan.reason = "EXT-X-BYTERANGE unsupported";
            return plan;
        }
        if (!sawExtinf || plan.segments.isEmpty()) {
            plan.reason = "no media segments";
            return plan;
        }
        plan.rewritten = join(out);
        plan.bitrateMbps = bitrateKbps / 1000.0;
        plan.supported = true;
        plan.reason = "ok";
        return plan;
    }

    /** 把一条地址按 baseUrl 绝对化；已是绝对地址原样返回。无法解析返回 null。 */
    public static String absolutize(String baseUrl, String ref) {
        if (ref == null) {
            return null;
        }
        String r = ref.trim();
        if (r.startsWith("http://") || r.startsWith("https://")) {
            return r;
        }
        if (r.startsWith("data:") || r.startsWith("skd:")) {
            return r;
        }
        try {
            URL base = new URL(baseUrl);
            return new URL(base, r).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String absolutizeTagUris(String baseUrl, String line) {
        if (line.indexOf("URI=") < 0) {
            return line;
        }
        Matcher m = URI_ATTR.matcher(line);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String raw = m.group(1);
            String abs = absolutize(baseUrl, raw);
            if (abs == null) {
                abs = raw;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement("URI=\"" + abs + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static double parseExtinf(String tagLine) {
        // #EXTINF:6.006,  或  #EXTINF:6.006
        int colon = tagLine.indexOf(':');
        int comma = tagLine.indexOf(',', colon + 1);
        String v = comma > colon ? tagLine.substring(colon + 1, comma) : tagLine.substring(colon + 1);
        return parseDouble(v.trim(), -1);
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        if (!lines.isEmpty()) {
            sb.append('\n');
        }
        return sb.toString();
    }
}
