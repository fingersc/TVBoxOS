package com.github.tvbox.osc.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;

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
 */
public final class NetEventListener extends EventListener {

    private static final long SLOW_MS = 300L;

    private long dnsStart;
    private long connectStart;
    private long tlsStart;
    private long requestStart;
    private String connectAddr = "";
    private String tlsHost = "";

    private static long now() {
        return System.currentTimeMillis();
    }

    @Override
    public void dnsStart(Call call, String domainName) {
        dnsStart = now();
    }

    @Override
    public void dnsEnd(Call call, String domainName, List<java.net.InetAddress> inetAddressList) {
        long cost = now() - dnsStart;
        if (cost >= SLOW_MS) {
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
        connectStart = now();
        connectAddr = inetSocketAddress.toString();
    }

    @Override
    public void connectEnd(Call call, InetSocketAddress inetSocketAddress, Proxy proxy, Protocol protocol) {
        long cost = now() - connectStart;
        if (cost >= SLOW_MS) {
            LOG.i("[net] connect " + connectAddr + " " + cost + "ms " + protocol);
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
        long cost = now() - connectStart;
        LOG.i("[net] connectFailed " + inetSocketAddress + " after " + cost + "ms " + ioe);
        // v97-r2：连不上就作废该 host 的探测缓存，下次重新解析/重新探测（IPTV CDN 节点会变）
        try {
            DualStackResolver.invalidate(call.request().url().host(), call.request().url().port());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void secureConnectStart(Call call) {
        tlsStart = now();
        tlsHost = call.request().url().host();
    }

    @Override
    public void secureConnectEnd(Call call, Handshake handshake) {
        long cost = now() - tlsStart;
        if (cost >= SLOW_MS) {
            LOG.i("[net] tls " + tlsHost + " " + cost + "ms");
        }
    }

    @Override
    public void requestHeadersStart(Call call) {
        requestStart = now();
    }

    @Override
    public void responseHeadersStart(Call call) {
        long cost = now() - requestStart;
        if (cost >= 1000L) {
            Request req = call.request();
            LOG.i("[net] ttfb " + req.url().host() + " " + cost + "ms");
        }
    }

    @Override
    public void callFailed(Call call, IOException ioe) {
        LOG.i("[net] callFailed " + call.request().url().host() + " " + ioe);
    }

    @Override
    public void callEnd(Call call) {
    }

    @Override
    public void responseHeadersEnd(Call call, Response response) {
    }
}
