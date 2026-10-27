package xyz.doikki.videoplayer.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 地址族"逐地址"连通性探测（v99-r86，v87 改为只服务排序与取证）。
 *
 * <p>为什么需要它：一个域名的一族通常有<b>多个地址</b>（实测 {@code ott.fj.chinamobile.com}
 * 有 2 个 A + 2 个 AAAA）。OkHttp 是<b>串行</b>试地址的，每个地址的 {@code connectTimeout} 是
 * 8 秒；而翻族 watchdog 只给 3 秒 —— 于是"第一个地址是黑洞、第二个地址好好的"这种情况，
 * 在 watchdog 眼里和"整族不通"完全一样，都会被放弃。</p>
 *
 * <p><b>v87 的定位（重要）</b>：探测结果只做两件事 ——</p>
 * <ol>
 *   <li><b>排序建议</b>：探到哪个地址活着，下次翻族就把它排前面
 *       （{@link DualStackPrefs#setPreferAddress}）；</li>
 *   <li><b>取证日志</b>：一条 {@code reachable=x/y} 就能回答"这族到底是全死还是只死了首选"。</li>
 * </ol>
 *
 * <p><b>绝不用于"要不要翻族"的判定</b>，也绝不写任何"这个地址/URL 不能用"的记录。
 * 两个理由：① 探测成功只证明 TCP 握手通，不证明这一族有内容；② 防火墙可能拦掉探测端口
 * 而真实请求能通 —— 拿探测当开关会把一条本来能救的线路判死。探不出结论时改用
 * {@link DualStackPrefs#bumpRotation} 轮转，让下次换一个地址先试，保证地址会被逐个试到。</p>
 */
public final class DualStackProbe {

    /** 单地址握手上限。IPTV 内网实测 14ms，800ms 是 50 倍余量，同时能塞进 3 秒 watchdog 的窗口。 */
    public static final int DEFAULT_TIMEOUT_MS = 800;

    /** 单次探测最多并行几个地址（域名一族通常 2~4 个，上限防止异常 DNS 把线程打爆）。 */
    private static final int MAX_PARALLEL = 4;

    /** 探测结论：探到几个、几个活着、第一个活着的 IP。 */
    public static final class Result {
        /** 该族的地址总数；0 表示拿不到地址（探测无结论）。 */
        public final int total;
        /** 能连上的地址数。 */
        public final int reachable;
        /** 第一个能连上的地址的 IP 文本；{@code null} 表示一个都没有。 */
        public final String firstReachableIp;

        Result(int total, int reachable, String firstReachableIp) {
            this.total = total;
            this.reachable = reachable;
            this.firstReachableIp = firstReachableIp;
        }

        /** 有明确证据"至少一个地址活着"。 */
        public boolean hasReachable() {
            return reachable > 0 && firstReachableIp != null && !firstReachableIp.isEmpty();
        }
    }

    public interface Callback {
        void onResult(Result result);
    }

    private DualStackProbe() {
    }

    /**
     * 异步探测 host 在某个地址族下各地址的 TCP 可达性。<b>绝不阻塞调用线程</b>
     * （调用点在播放器 watchdog 里，那里还要立刻换线）。
     *
     * <p>回调在后台线程，调用方自行切线程。</p>
     */
    public static void probeAsync(final String host, final int port,
                                  final int family, final Callback callback) {
        if (host == null || host.isEmpty() || callback == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Result r;
                try {
                    List<InetAddress> addrList = ofFamily(InetAddress.getAllByName(host), family);
                    r = probeList(addrList, port, DEFAULT_TIMEOUT_MS);
                } catch (Throwable ignored) {
                    // 拿不到地址 ⇒ total=0（无结论）。注意不能报成"全不通"，那会误导成硬证据
                    r = new Result(0, 0, null);
                }
                try {
                    callback.onResult(r);
                } catch (Throwable ignored) {
                }
            }
        }, "dstack-probe");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable ignored) {
        }
    }

    /** 从 DNS 结果里挑出指定族的地址（纯函数，便于在真 JVM 里断言）。 */
    public static List<InetAddress> ofFamily(InetAddress[] all, int family) {
        List<InetAddress> out = new ArrayList<>();
        if (all == null) {
            return out;
        }
        for (InetAddress a : all) {
            if (a == null) {
                continue;
            }
            if (family == DualStackPrefs.FAMILY_V4 && a instanceof Inet4Address) {
                out.add(a);
            } else if (family == DualStackPrefs.FAMILY_V6 && a instanceof Inet6Address) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * 并发探测一组地址，返回结论（总数 / 可达数 / 第一个可达地址）。
     *
     * <p>并行而不是串行：串行最坏是 {@code n × timeout}，会拖过下一次换台的时机，
     * 导致本该排到前面的地址，在需要它的那一刻还没探出来。</p>
     */
    static Result probeList(List<InetAddress> addrList, final int port, final int timeoutMs) {
        if (addrList == null || addrList.isEmpty()) {
            return new Result(0, 0, null);
        }
        int total = addrList.size();
        int n = Math.min(total, MAX_PARALLEL);
        final boolean[] ok = new boolean[n];
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final InetAddress addr = addrList.get(i);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    ok[idx] = reachable(addr, port, timeoutMs);
                }
            }, "dstack-probe-" + i);
            t.setDaemon(true);
            threads[i] = t;
            try {
                t.start();
            } catch (Throwable ignored) {
            }
        }
        for (int i = 0; i < n; i++) {
            if (threads[i] == null) {
                continue;
            }
            try {
                threads[i].join(timeoutMs + 200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int count = 0;
        String firstIp = null;
        for (int i = 0; i < n; i++) {
            if (ok[i]) {
                count++;
                if (firstIp == null) {
                    firstIp = ipText(addrList.get(i));
                }
            }
        }
        return new Result(total, count, firstIp);
    }

    /** 单个地址的 TCP 握手是否成功（纯逻辑，可在本机用 ServerSocket 真实断言）。 */
    public static boolean reachable(InetAddress addr, int port, int timeoutMs) {
        if (addr == null || port <= 0 || port > 65535) {
            return false;
        }
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(addr, port), Math.max(1, timeoutMs));
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 地址的 IP 文本（不带方括号；与 {@link DualStackPrefs} 里存的键保持同一形式）。 */
    static String ipText(InetAddress a) {
        if (a == null) {
            return null;
        }
        try {
            return a.getHostAddress();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 URL 里取端口（纯函数）。拿不到就用协议默认值。
     *
     * <p>不抛异常是硬要求：这里跑在失败路径上，不能因为一个畸形 URL 再抛一个异常出去。</p>
     */
    public static int portOf(String url) {
        try {
            if (url == null) {
                return 80;
            }
            int schemeEnd = url.indexOf("://");
            boolean https = false;
            String rest;
            if (schemeEnd > 0) {
                https = url.substring(0, schemeEnd).equalsIgnoreCase("https");
                rest = url.substring(schemeEnd + 3);
            } else {
                rest = url;
            }
            int end = rest.length();
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                end = slash;
            }
            int query = rest.indexOf('?');
            if (query >= 0 && query < end) {
                end = query;
            }
            String authority = rest.substring(0, end);
            int colon;
            if (authority.startsWith("[")) {
                // IPv6 字面量：[::1]:8080
                int close = authority.indexOf(']');
                colon = close >= 0 ? authority.indexOf(':', close) : -1;
            } else {
                colon = authority.lastIndexOf(':');
            }
            if (colon > 0 && colon < authority.length() - 1) {
                int p = Integer.parseInt(authority.substring(colon + 1).trim());
                if (p > 0 && p <= 65535) {
                    return p;
                }
            }
            return https ? 443 : 80;
        } catch (Throwable t) {
            return 80;
        }
    }
}
