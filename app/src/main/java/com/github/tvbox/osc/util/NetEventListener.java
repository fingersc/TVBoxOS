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

    /**
     * r212：建连耗时上报。
     *
     * <p>详情页的首屏预算（5s）是<b>从进页起算</b>的，而请求要先经过 DNS + TCP + TLS。
     * 实测 Cloudflare 后的采集站 TLS 握手可达 1.8–2.0s、建连 2.2–3.0s（22:18 日志的
     * {@code api.wujinapi.com} / {@code api.zuidapi.com}），留给源站响应的时间只剩
     * 0.9–2.8s —— 本来能通的源会被判超时切走。详情页据此给「慢建连」补足预算。</p>
     *
     * <p>static 是有意的：{@link NetEventListener} 按 client 各建了一个实例，
     * 而详情请求只走默认 client；用 static 才能让详情页不依赖拿到具体实例。</p>
     */
    public interface ConnectCostListener {
        /**
         * @param tag    请求 tag（OkGo 的 {@code .tag(...)}；详情请求是 {@code "detail"}）
         * @param costMs TCP+TLS 建连耗时
         */
        void onConnectCost(String tag, long costMs);
    }

    private static volatile ConnectCostListener connectCostListener;

    /** 注册建连耗时监听（同一时刻只保留一个：详情页是前台单例）。 */
    public static void setConnectCostListener(ConnectCostListener listener) {
        connectCostListener = listener;
    }

    /** 注销；只有当前登记的就是该 listener 时才清，避免误清后来者。 */
    public static void clearConnectCostListener(ConnectCostListener listener) {
        if (listener != null && connectCostListener == listener) {
            connectCostListener = null;
        }
    }

    private static void reportConnectCost(Call call, long costMs) {
        ConnectCostListener l = connectCostListener;
        if (l == null) {
            return;
        }
        try {
            Object tag = call.request().tag();
            if (tag instanceof String) {
                l.onConnectCost((String) tag, costMs);
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== r213：详情请求「建连阶段」进度 ====================

    /**
     * r213：详情请求的建连起点与在途标记。
     *
     * <p><b>为什么还需要它</b>：r212 的慢建连补预算挂在 {@link #connectEnd} 上，
     * 那是「建连<b>成功</b>」的回调。而真正吃光首屏预算的还有另一种形态 ——
     * <b>建连一直不成功</b>（对端丢包/被墙），{@code connectEnd} 永不回调，
     * 补预算逻辑形同虚设。23:32 日志实证：{@code ys.cxkjg.cn} 的详情请求
     * {@code connectFailed … SocketTimeoutException … after 8005ms}，
     * 首屏 5s 预算早已耗光，页面白等 12.45s。</p>
     *
     * <p>所以额外记录「当前有没有一条 tag=detail 的请求正在建连、已建连多久」，
     * 让看门狗到点时能判断「预算是不是被建连吃掉了」。</p>
     */
    private static volatile long detailConnectStartAtMs = 0L;
    private static volatile boolean detailConnectInProgress = false;

    /**
     * r213：tag=detail 的请求当前已建连多久；没有在建连则返回 0。
     *
     * @return 毫秒；0 表示当前没有 detail 请求处于建连阶段
     */
    public static long detailConnectElapsedMs() {
        if (!detailConnectInProgress) {
            return 0L;
        }
        long start = detailConnectStartAtMs;
        if (start <= 0L) {
            return 0L;
        }
        long cost = System.currentTimeMillis() - start;
        return cost > 0L ? cost : 0L;
    }

    private static boolean isDetailCall(Call call) {
        try {
            return "detail".equals(call.request().tag());
        } catch (Throwable ignored) {
            return false;
        }
    }

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
        // r213：记下「详情请求正在建连」的起点，供首屏看门狗判断预算是否被建连吃掉
        if (isDetailCall(call)) {
            detailConnectStartAtMs = now();
            detailConnectInProgress = true;
        }
    }

    @Override
    public void connectEnd(Call call, InetSocketAddress inetSocketAddress, Proxy proxy, Protocol protocol) {
        long cost = now() - mark(call).connectStart;
        if (cost >= SLOW_MS) {
            // v99-r91：地址直接用回调参数，不再借用实例字段（旧版借的那份可能是别的请求的）
            LOG.i("[net] connect " + inetSocketAddress + " " + cost + "ms " + protocol);
        }
        if (isDetailCall(call)) {
            detailConnectInProgress = false;
        }
        // r212：把建连耗时交给详情页（它据此判断「慢建连」要不要补足首屏预算）
        reportConnectCost(call, cost);
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
        // r213：单条路由失败不代表整次建连结束 —— 双栈场景下 OkHttp 会换下一条路由再试，
        //   紧跟着还会有 connectStart，把标记重新置起来。这里先清掉，避免「已失败还显示在建连」。
        if (isDetailCall(call)) {
            detailConnectInProgress = false;
        }
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
        // r213：请求终结一定要把「详情正在建连」标记清掉，否则某条 detail 请求
        //   被取消后标记会永久挂着，之后任何页面都会误判「建连耗时极长」而补预算。
        if (isDetailCall(call)) {
            detailConnectInProgress = false;
        }
        marks.remove(call);
    }

    @Override
    public void callEnd(Call call) {
        if (isDetailCall(call)) {
            detailConnectInProgress = false;
        }
        marks.remove(call);
    }

    @Override
    public void responseHeadersEnd(Call call, Response response) {
    }
}
