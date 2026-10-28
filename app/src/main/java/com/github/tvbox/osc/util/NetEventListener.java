package com.github.tvbox.osc.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 网络分段计时（v87）。
 *
 * <p>用于区分「首次请求慢」到底慢在 DNS、TCP 建连、TLS 还是等响应头：</p>
 * <ul>
 *   <li>{@code [net] dns host=... 20120ms} → DNS 解析慢</li>
 *   <li>{@code [net] connect 1.2.3.4:80 15003ms} → TCP 建连慢/被丢包</li>
 *   <li>{@code [net] connectFailed ...} → 某条路由（常是 IPv6）失败后重试</li>
 *   <li>{@code [net] ttfb host=... 20500ms} → 连上了但服务器迟迟不回</li>
 * </ul>
 *
 * <p>只在单步超过 300ms 时打印，日常不刷屏；日志受调试模式门控。</p>
 *
 * <p><b>v99-r91：计时状态改成「每个 Call 一份」。</b>
 * 这个 Listener 注册在 OkHttpClient.Builder 上是<b>单实例</b>，被所有并发请求共用。
 * 原来把 {@code connectStart}/{@code dnsStart}/… 存成实例字段，并发时后一个请求会把前一个的起点覆盖掉，
 * 日志里的耗时于是张冠李戴 —— 实测出现过 {@code connectFailed … after 3776ms}，
 * 而那次连接实际只挂了 3.0 秒（watchdog 到期被 cancel），差出来的 776ms 是别的请求的计时。
 * 排查"到底哪一段慢"时，这种假数字比没有更坏，所以按 {@link Call} 分开存。</p>
 */
public final class NetEventListener extends EventListener {

    private static final long SLOW_MS = 300L;
    /** 被 cancel 的 Call 不会回调 callEnd，兜底上限，防止计时表无界增长。 */
    private static final int MARKS_MAX = 512;

    /** 一个 Call 的分段计时。字段语义与旧的实例字段一一对应，只是不再跨请求共享。 */
    private static final class Mark {
        long dnsStart;
        long connectStart;
        long tlsStart;
        long requestStart;
        String tlsHost = "";
    }

    private final ConcurrentHashMap<Call, Mark> marks = new ConcurrentHashMap<>();

    private static long now() {
        return System.currentTimeMillis();
    }

    private Mark mark(Call call) {
        Mark m = marks.get(call);
        if (m != null) {
            return m;
        }
        m = new Mark();
        Mark prev = marks.putIfAbsent(call, m);
        if (prev != null) {
            return prev;
        }
        if (marks.size() > MARKS_MAX) {
            // 极简上限：漏删只影响日志，不做精细清理
            marks.clear();
            marks.put(call, m);
        }
        return m;
    }

    @Override
    public void dnsStart(Call call, String domainName) {
        mark(call).dnsStart = now();
    }

    @Override
    public void dnsEnd(Call call, String domainName, List<java.net.InetAddress> inetAddressList) {
        long cost = now() - mark(call).dnsStart;
        if (cost >= SLOW_MS || isImageCall(call)) {
            StringBuilder sb = new StringBuilder();
            for (java.net.InetAddress a : inetAddressList) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(a.getHostAddress());
            }
            LOG.i("[net] dns " + domainName + " " + cost + "ms -> " + sb);
            LOG.i("[net] dns-list " + domainName + " " + sb);
        }
    }

    @Override
    public void connectStart(Call call, InetSocketAddress inetSocketAddress, Proxy proxy) {
        mark(call).connectStart = now();
    }

    @Override
    public void connectEnd(Call call, InetSocketAddress inetSocketAddress, Proxy proxy, Protocol protocol) {
        long cost = now() - mark(call).connectStart;
        if (cost >= SLOW_MS) {
            // v99-r91：地址直接用回调参数，不再借用实例字段（旧版借的那份可能是别的请求的）
            LOG.i("[net] connect " + inetSocketAddress + " " + cost + "ms " + protocol);
        }
        // v97：连接成功即证明该地址/该族可用，回填给双栈优选做粘性排序
        try {
            DualStackResolver.recordSuccess(call.request().url().host(),
                    call.request().url().port(), inetSocketAddress.getAddress());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void connectFailed(Call call, InetSocketAddress inetSocketAddress, Proxy proxy,
                              Protocol protocol, IOException ioe) {
        long cost = now() - mark(call).connectStart;
        LOG.i("[net] connectFailed " + inetSocketAddress + " after " + cost + "ms " + ioe);
        // v97-r2：连不上就作废该 host 的探测缓存，下次重新解析/重新探测（IPTV CDN 节点会变）
        // v99-r98：图片/封面请求失败不在此列 —— 它走的是另一套 DNS，且失败多为"坏 IP"，
        //          不应把播放器对同一 host 记录下来的"好节点"一起作废。
        if (!isImageCall(call)) {
            try {
                DualStackResolver.invalidate(call.request().url().host(), call.request().url().port());
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void secureConnectStart(Call call) {
        Mark m = mark(call);
        m.tlsStart = now();
        m.tlsHost = call.request().url().host();
    }

    @Override
    public void secureConnectEnd(Call call, Handshake handshake) {
        Mark m = mark(call);
        long cost = now() - m.tlsStart;
        if (cost >= SLOW_MS) {
            LOG.i("[net] tls " + m.tlsHost + " " + cost + "ms");
        }
    }

    @Override
    public void requestHeadersStart(Call call) {
        mark(call).requestStart = now();
    }

    @Override
    public void responseHeadersStart(Call call) {
        long cost = now() - mark(call).requestStart;
        if (cost >= 1000L) {
            Request req = call.request();
            LOG.i("[net] ttfb " + req.url().host() + " " + cost + "ms");
        }
    }

    /** 图片/封面请求（由 OkHttpStreamFetcher 打的 tag）—— 失败时不要污染播放器的 host 节点缓存。 */
    private static boolean isImageCall(Call call) {
        try {
            return com.github.catvod.net.OkHttp.IMAGE_TAG.equals(call.request().tag());
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void callFailed(Call call, IOException ioe) {
        LOG.i("[net] callFailed " + call.request().url().host() + " " + ioe);
        marks.remove(call);
    }

    @Override
    public void callEnd(Call call) {
        marks.remove(call);
    }

    @Override
    public void responseHeadersEnd(Call call, Response response) {
    }
}
