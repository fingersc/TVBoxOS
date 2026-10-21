package com.github.tvbox.osc.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 双栈并行优选（v97）。
 *
 * <p>对同一 host:port 的 A / AAAA 地址<b>并行</b>发起 TCP 握手，取实测最快可达者，
 * 并按"预计速度"重排 DNS 结果 —— 让 OkHttp 第一个就试对的地址。</p>
 *
 * <p>要解决的问题：本机/路由器没有可用 IPv6 时，DNS 仍会返回 AAAA，
 * OkHttp 按序先试它，那条连接要挂满 connectTimeout 才回退 IPv4（实测 20.8 秒黑洞）。</p>
 *
 * <p>三条铁律（改这段代码前必读）：</p>
 * <ol>
 *   <li>探测只<b>排序</b>，绝不删除地址 —— 防火墙拦了探测端口但真实请求能通时，原地址必须仍可用</li>
 *   <li>探测失败/任何异常一律回退原顺序，调用方无感知</li>
 *   <li>本地地址（127.* / ::1）与 IP 字面量不探测（没有"选族"的余地）</li>
 * </ol>
 */
public final class DualStackResolver {

    private static final int PROBE_TIMEOUT_MS = 1200;          // 单地址探测上限
    private static final long CACHE_TTL_MS = 2 * 60 * 1000L;   // 缓存 2 分钟（IPTV 节点会变，别固化太久）
    private static final int CACHE_MAX = 128;                  // 缓存上限，防无限增长

    /** host:port → 上次最快可达（或真实连接观测到成功）的地址 */
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private DualStackResolver() {
    }

    /** 探测/排序结果 */
    public static final class Result {
        public InetAddress address;   // 最快可达地址（null 表示全部失败）
        public long probeMs;          // 探测耗时
        public boolean viaV6;         // 是否 IPv6
        public boolean fromCache;     // 是否来自缓存（零探测开销）
        public int totalAddrs;        // 该 host 解析出的地址总数（>1 说明是 CDN 多节点）
    }

    private static final class Entry {
        InetAddress address;
        long at;
        int total;        // 该 host 解析出的地址总数（-1 = 未知）
    }

    // ------------------------------------------------------------------ 探测

    /**
     * 并行探测 host:port，返回最快可达者。
     *
     * <p>IP 字面量 / 本地地址 / 单栈 / 异常一律返回 null（调用方回退原顺序）。</p>
     */
    public static Result probeFastest(String host, int port) {
        try {
            if (host == null || host.isEmpty() || port <= 0) return null;
            if (isLocal(host) || isIpLiteral(host)) return null;
            String key = host + ":" + port;

            Entry cached = CACHE.get(key);
            if (cached != null && now() - cached.at < CACHE_TTL_MS) {
                Result r = new Result();
                r.address = cached.address;
                r.fromCache = true;
                r.viaV6 = cached.address instanceof Inet6Address;
                r.totalAddrs = cached.total;
                return r;
            }

            List<InetAddress> v4 = new ArrayList<>();
            List<InetAddress> v6 = new ArrayList<>();
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a instanceof Inet4Address) v4.add(a);
                else if (a instanceof Inet6Address) v6.add(a);
            }
            if (v4.isEmpty() || v6.isEmpty()) return null;   // 单栈无需优选
            int total = v4.size() + v6.size();

            final Result[] best = new Result[1];
            final Object lock = new Object();
            probe(v4.get(0), port, best, lock);
            probe(v6.get(0), port, best, lock);
            synchronized (lock) {
                lock.wait(PROBE_TIMEOUT_MS + 200L);          // 等最快者（超时兜底）
            }
            synchronized (lock) {
                if (best[0] == null) return null;
                best[0].totalAddrs = total;
                CACHE.put(key, entry(best[0].address, total));
                trim();
                LOG.i("[net] pick " + key + " " + best[0].address.getHostAddress()
                        + " " + best[0].probeMs + "ms v6=" + best[0].viaV6);
                return best[0];
            }
        } catch (Throwable t) {
            return null;                                     // 铁律 2
        }
    }

    private static void probe(final InetAddress addr, final int port,
                              final Result[] best, final Object lock) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                long t0 = now();
                Socket s = null;
                boolean ok = false;
                try {
                    s = new Socket();
                    s.connect(new InetSocketAddress(addr, port), PROBE_TIMEOUT_MS);
                    ok = true;
                } catch (Throwable ignored) {
                } finally {
                    if (s != null) {
                        try {
                            s.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (!ok) return;
                synchronized (lock) {
                    long ms = now() - t0;
                    if (best[0] == null || ms < best[0].probeMs) {
                        Result r = new Result();
                        r.address = addr;
                        r.probeMs = ms;
                        r.viaV6 = addr instanceof Inet6Address;
                        best[0] = r;
                    }
                    lock.notifyAll();
                }
            }
        });
    }

    // ------------------------------------------------------------------ 排序

    /**
     * 按"预计速度"重排 DNS 结果。<b>不删除任何地址。</b>
     *
     * <ul>
     *   <li>缓存命中（含真实连接观测）⇒ 该地址排最前</li>
     *   <li>未命中 ⇒ IPv4 优先，AAAA 保留在尾部（配合 8s connect 仍有兜底价值）</li>
     *   <li>纯 IPv6 / 单地址 ⇒ 原序返回</li>
     * </ul>
     */
    public static List<InetAddress> order(List<InetAddress> all, String host, int port) {
        if (all == null || all.size() <= 1) return all;
        try {
            Entry cached = CACHE.get(host + ":" + port);
            InetAddress first = null;
            if (cached != null && now() - cached.at < CACHE_TTL_MS) first = cached.address;
            List<InetAddress> out = new ArrayList<>(all.size());
            if (first != null) {
                out.add(first);
                for (InetAddress a : all) {
                    if (!a.equals(first)) out.add(a);
                }
                return out;
            }
            for (InetAddress a : all) if (a instanceof Inet4Address) out.add(a);
            for (InetAddress a : all) if (!(a instanceof Inet4Address)) out.add(a);
            return out.isEmpty() ? all : out;
        } catch (Throwable t) {
            return all;                                       // 铁律 2
        }
    }

    /**
     * 作废某个 host:port 的探测缓存（由 NetEventListener.connectFailed 调用）。
     *
     * <p>IPTV 的 CDN 节点会变：固化一个 IP 后它可能很快失效，
     * 这时必须立刻丢掉缓存，让下次重新解析/重新探测，否则会连着失败到缓存过期。</p>
     */
    public static void invalidate(String host, int port) {
        try {
            if (host == null || host.isEmpty() || port <= 0) return;
            CACHE.remove(host + ":" + port);
        } catch (Throwable ignored) {
        }
    }

    /** 真实连接成功时记录，供 {@link #order} 做粘性排序（由 NetEventListener.connectEnd 调用）。 */
    public static void recordSuccess(String host, int port, InetAddress addr) {
        try {
            if (host == null || host.isEmpty() || addr == null || port <= 0) return;
            // recordSuccess 来自真实连接，地址总数未知（-1）⇒ 调用方按"可固化"处理，
            // 但 connectFailed 会立即作废，所以不会长期固化在一个坏 IP 上
            CACHE.put(host + ":" + port, entry(addr, -1));
            trim();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ URL 工具

    /** 取 host；IPv6 字面量会去掉方括号。非法返回 ""。 */
    public static String hostOf(String url) {
        try {
            okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(url);
            return u == null ? "" : u.host();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 取端口；URL 未写端口时返回协议默认端口（http=80 / https=443）。 */
    public static int portOf(String url) {
        try {
            okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(url);
            return u == null ? 0 : u.port();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 用选定地址重建 URL（IPv6 会自动补方括号）；失败返回原 URL。 */
    public static String replaceHost(String url, InetAddress addr) {
        try {
            okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(url);
            if (u == null || addr == null) return url;
            String host = addr.getHostAddress();
            if (host == null || host.isEmpty()) return url;
            if (addr instanceof Inet6Address) host = "[" + host + "]";
            return u.newBuilder().host(host).build().toString();
        } catch (Throwable t) {
            return url;
        }
    }

    // ------------------------------------------------------------------ 内部

    private static Entry entry(InetAddress a, int total) {
        Entry e = new Entry();
        e.address = a;
        e.at = now();
        e.total = total;
        return e;
    }

    private static void trim() {
        if (CACHE.size() <= CACHE_MAX) return;
        long cutoff = now() - CACHE_TTL_MS;
        for (java.util.Iterator<java.util.Map.Entry<String, Entry>> it = CACHE.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().at < cutoff) it.remove();
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static boolean isLocal(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host)
                || host.startsWith("127.") || "0.0.0.0".equals(host);
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }
}
