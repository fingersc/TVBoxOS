package com.github.tvbox.osc.util;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A′ 方案（本地分片代理 + 跨切片滚动预取）的纯逻辑层。
 *
 * <p>职责：把一条 HLS media playlist 解析成「有序切片列表」，并生成一份把切片行
 * 改写成本地代理地址（{@code /seg?sk=<sessionKey>&i=<序号>}）的新播放列表。
 * 本类<b>不依赖 Android / OkHttp</b>，可在桌面 JVM 上直接单测（见 work\ 下的测试台）。</p>
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

    private M3u8PrefetchPlan() {
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

        boolean sawExtinf = false;
        boolean sawStreamInf = false;
        boolean sawByteRange = false;
        double pendingDuration = -1;
        double bitrateKbps = 0;
        int index = 0;

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
            out.add(localPrefix + index);
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
