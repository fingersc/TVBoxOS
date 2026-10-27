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

import xyz.doikki.videoplayer.util.DualStackPrefs;

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
 *
 * <p>v99-r83 两处修正（详见 {@link #order} 与 {@link #cachedFirst}）：</p>
 * <ul>
 *   <li><b>端口不再硬编码</b>：{@link okhttp3.Dns#lookup} 的签名里<b>根本没有端口</b>，
 *       之前调用方传死 443，而写缓存用的是真实端口（直播流多为 80），key 永不相等
 *       ⇒ 粘性排序从未生效、探测结论也从未被用上，实际恒走"IPv4 排前"分支。
 *       现在调用方传 0 表示"端口未知"，并按 host 级兜底检索。</li>
 *   <li><b>尊重明确的地址族偏好</b>：播放器层遇到 403/404 翻族重试时登记的偏好优先级最高
 *       （见 {@link DualStackPrefs}），压过粘性缓存 —— 否则翻族会被缓存里的"旧族最快"顶回去。</li>
 * </ul>
 */
public final class DualStackResolver {

    private static final int PROBE_TIMEOUT_MS = 1200;          // 单地址探测上限
    /**
     * 30 分钟（v89：原来 2 分钟）。
     *
     * <p>缓存里存的是<b>真实连接成功过的地址</b>（{@link #recordSuccess}，由
     * {@code NetEventListener.connectEnd} 在 OkHttp 真正建连成功后写入），这是所有证据里最硬的一条：
     * 实测日志里 {@code via=cache} 的那几台全部 349~529ms 出画，且落在"能出流的那个边缘"上
     * （同一 host 的 v6 有 4~5 个边缘，个别边缘对某些频道回 404）。</p>
     *
     * <p>2 分钟意味着"看完一个台再换回来就忘了"，等于每次都要重新赌一次边缘；
     * 放长到 30 分钟让这个证据真正发挥作用。</p>
     *
     * <p>失败自纠仍在：{@code connectFailed} 会 {@link #invalidate}，探测/轮转也会改写顺序，
     * 而且这条缓存只决定<b>先试谁</b>，不会让任何地址消失。</p>
     */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    private static final int CACHE_MAX = 128;                  // 缓存上限，防无限增长

    /** host:port → 上次最快可达（或真实连接观测到成功）的地址 */
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    /** host → 该 host 最近一次参与排序的地址（与端口无关，供端口未知时兜底） */
    private static final ConcurrentHashMap<String, Entry> HOST_LATEST = new ConcurrentHashMap<>();
    /**
     * v99-r84 取证：host → 上一次记下的排序结论，只在结论变化时打日志。
     *
     * <p>HLS 播放时每个分片请求都会走一次 DNS，无条件打日志会把 buffer 刷满，
     * 真正有用的"翻族后到底选了谁"反而被埋掉。这里按 host 去重。</p>
     */
    private static final ConcurrentHashMap<String, String> LOGGED_ORDER = new ConcurrentHashMap<>();
    /** 每族最多探几个地址（v99-r90）：同族两个地址能力可以完全不同，只探第一个会误判。 */
    private static final int MAX_PROBE_PER_FAMILY = 2;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

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
        int port;         // 写入时的端口（0 = 未知）
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
            // v99-r90：每族探前 2 个。实测同一域名的两个 IPv4 节点能力完全互补
            //   （39.135.49.25 只开 6610、112.50.244.106 只开 6060），只探第一个等于把
            //   "第二个能通"直接判死 —— 这正是那次 8.6 秒的来源。
            probeFirst(v4, port, best, lock);
            probeFirst(v6, port, best, lock);
            synchronized (lock) {
                lock.wait(PROBE_TIMEOUT_MS + 200L);          // 等最快者（超时兜底）
            }
            synchronized (lock) {
                if (best[0] == null) return null;
                best[0].totalAddrs = total;
                put(host, port, best[0].address, total);
                LOG.i("[net] pick " + key + " " + best[0].address.getHostAddress()
                        + " " + best[0].probeMs + "ms v6=" + best[0].viaV6);
                return best[0];
            }
        } catch (Throwable t) {
            return null;                                     // 铁律 2
        }
    }

    /** 把该族前 {@link #MAX_PROBE_PER_FAMILY} 个地址都丢进线程池并行探。 */
    private static void probeFirst(List<InetAddress> family, int port,
                                   final Result[] best, final Object lock) {
        int n = Math.min(family.size(), MAX_PROBE_PER_FAMILY);
        for (int i = 0; i < n; i++) {
            probe(family.get(i), port, best, lock);
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
     * <p>优先级（v99-r87）：</p>
     * <ol>
     *   <li>显式地址族偏好（{@link DualStackPrefs}，播放器 403/404 翻族时登记）⇒ 该族全部排前；
     *       该族<b>内部</b>再按"探测证据 → 轮转 → 粘性缓存"挑第一个（见 {@link #pickWithinFamily}）</li>
     *   <li>v99-r90 <b>并行探测最快者</b>（{@link #probeFastest}）⇒ 该地址排最前。
     *       这是 Happy Eyeballs 的等价物：OkHttp 3.12 没有 fastFallback，只会按我们给的顺序
     *       串行连接，第一个是死端口就要吃满 8s；并行探一下就能把"能通但排后面"的地址提上来</li>
     *   <li>粘性缓存（真实连接观测）⇒ 该地址排最前。<b>v99-r91：必须仍在本次 DNS 名单内</b></li>
     *   <li>都没有 ⇒ IPv4 优先，AAAA 保留在尾部（配合 8s connect 仍有兜底价值）</li>
     *   <li>纯 IPv6 / 单地址 ⇒ 原序返回</li>
     * </ol>
     *
     * <p><b>任何一级都只是排序</b>：v87 起这里不再有"某条路径已被判死、丢弃偏好"的分支 ——
     * 记录层已改成只存"建议先试谁"，是否能用一律由真实请求决定。</p>
     *
     * @param port 真实端口；<b>未知时传 0</b>（{@link okhttp3.Dns#lookup} 拿不到端口）。
     *             0 时按 host 级最近记录兜底，不会退化成"永远 IPv4 优先"。
     */
    public static List<InetAddress> order(List<InetAddress> all, String host, int port) {
        if (all == null || all.size() <= 1) return all;
        try {
            List<InetAddress> out = new ArrayList<>(all.size());
            // v99-r90：Dns.lookup 的签名里没有端口，用播放器 setDataSource 时记下的端口提示补上。
            // 拿不到端口就跳过并行探测（探不了），退回原来的排序策略。
            int probePort = probePortOf(host, port);

            // 1) 显式族偏好最优先：翻族重试必须真的换族，不能被下面的粘性缓存顶回去
            int pref = DualStackPrefs.preferFamily(host);
            if (pref == DualStackPrefs.FAMILY_V4 || pref == DualStackPrefs.FAMILY_V6) {
                InetAddress first = pickWithinFamily(all, host, pref, port);
                if (first != null) out.add(first);
                for (InetAddress a : all) {
                    if (familyOf(a) == pref && !out.contains(a)) out.add(a);
                }
                if (!out.isEmpty()) {
                    for (InetAddress a : all) {
                        if (!out.contains(a)) out.add(a);
                    }
                    // v99-r84 取证：翻族/族偏好生效时，把最终首选地址记一条。
                    //   排查"翻了族还是连不上"时，这一行能直接区分：
                    //   ① DNS 没按偏好排（order 逻辑问题）；② 排了但那条路本身不通（网络问题）。
                    //   v99-r87 增加 via=，直接看出这个"第一"是怎么来的（地址证据 / 轮转 / 粘性缓存）。
                    // v99-r90：修正 via= 的语义。原来"既没有地址证据也没有游标"也会被标成 cache，
                    // 但那种情况可能是 pickWithinFamily 什么都没给出（回落该族 DNS 原序）——
                    // 排查时按 via=cache 去理解会得到错误结论。
                    boolean hasAddrPref = DualStackPrefs.preferAddress(host, pref) != null;
                    boolean hasRotate = DualStackPrefs.rotationIndex(host, pref) >= 0;
                    boolean hasCache = cachedFirst(host, port, all) != null;
                    // v99-r91：cache-miss = 缓存里有记录，但那个地址已经不在本次 DNS 应答里
                    //   （典型是 302 目标边缘的地址被记到了原 host 名下）。见到这两个词就能区分
                    //   "没有证据"和"有证据但被否掉"，不必再猜。
                    String why = hasAddrPref ? "addr-pref"
                            : (hasRotate ? "rotate"
                            : (hasCache ? "cache"
                            : (hasCacheRecord(host, port) ? "cache-miss" : "none")));
                    logOrderOnce(host, "pref=v" + pref + " via=" + why
                            + " first=" + addrText(out.get(0)) + " total=" + out.size());
                    return out;                                  // 另一族仍在尾部（铁律 1）
                }
                out.clear();                                     // 该族一个地址都没有 ⇒ 落回默认
                logOrderOnce(host, "pref=v" + pref + " no-such-family->default");
            }

            // 2) v99-r90 并行探测最快者 —— Happy Eyeballs 的等价物。
            //    必须排在默认的"IPv4 优先"之前：OkHttp 3.12.11 没有 fastFallback，它完全按
            //    我们返回的顺序串行试，第一个是死端口就要吃满 8s connectTimeout；
            //    而实测同一域名的 IPv4 节点可能只开 6610、不开我们请求的 6060。
            //    探测本身并行（线程池，单地址 1.2s 上限），有一个先通就立刻返回。
            if (probePort > 0) {
                Result fast = probeFastest(host, probePort);
                if (fast != null && fast.address != null && all.contains(fast.address)) {
                    out.add(fast.address);
                    for (InetAddress a : all) {
                        if (!a.equals(fast.address)) out.add(a);
                    }
                    logOrderOnce(host, "via=probe first=" + addrText(fast.address)
                            + " " + fast.probeMs + "ms v6=" + fast.viaV6
                            + (fast.fromCache ? " cached" : "") + " total=" + out.size());
                    return out;
                }
            }

            // 3) 粘性缓存（v99-r91 起校验名单，见 cachedFirst）
            InetAddress first = cachedFirst(host, port, all);
            if (first != null) {
                out.add(first);
                for (InetAddress a : all) {
                    if (!a.equals(first)) out.add(a);
                }
                return out;
            }

            // 4) 默认：IPv4 优先（探测不可用时的最后兜底）
            for (InetAddress a : all) if (a instanceof Inet4Address) out.add(a);
            for (InetAddress a : all) if (!(a instanceof Inet4Address)) out.add(a);
            return out.isEmpty() ? all : out;
        } catch (Throwable t) {
            return all;                                       // 铁律 2
        }
    }

    /**
     * 探测用的端口：调用方给了就用（真实端口），没给就查播放器记下的端口提示（v99-r90）。
     *
     * <p>抽成独立方法是为了能被断言：{@code Dns.lookup} 传进来的永远是 0，
     * 真正的端口只可能来自 {@code DualStackPrefs.notePort}。返回 0 表示"探不了"，调用方跳过探测。</p>
     */
    static int probePortOf(String host, int port) {
        try {
            if (port > 0 && port <= 65535) {
                return port;
            }
            return DualStackPrefs.portOf(host);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 取该 host 的粘性地址。
     *
     * <p>v99-r83：端口未知（port<=0）时走 host 级记录；<b>即使调用方给了端口也允许回落</b> ——
     * 这样即便将来再有人写死一个端口，也不会像 r83 之前那样永久失效。</p>
     */
    /**
     * 在已选定的地址族内部挑"先试哪一个"（v99-r87）。<b>只排序，不排除任何地址。</b>
     *
     * <p>为什么需要：一个族的 DNS 结果通常有 2 个地址，OkHttp 串行试，第一个是 SYN 黑洞时
     * 3 秒预算全耗在它身上，第二个永远轮不到（实测盒子日志：首选 {@code 2409:...::20a} 是黑洞，
     * 同族的 {@code 2409:8087:3408:20:1040::206} 从未被请求过）。</p>
     *
     * <p>三级来源，前面没用上才看后面：</p>
     * <ol>
     *   <li><b>探测证据</b>：翻族失败后探测到"这个地址真能连"，下次优先它 —— 最硬的依据；</li>
     *   <li><b>轮转</b>：探不出结论（防火墙拦了探测端口 / 全部不可达 / DNS 没给地址）时，
     *       换个地址先试。跨多次尝试必然把同族所有地址都试到，<b>这是"每条路都被走过"的兜底</b>；</li>
     *   <li><b>粘性缓存</b>：真实连接成功过的地址（{@link #cachedFirst}）。</li>
     * </ol>
     *
     * <p>返回 null 表示这一族没有可用线索，调用方按 DNS 原序排（不会丢地址）。</p>
     */
    private static InetAddress pickWithinFamily(List<InetAddress> all, String host, int family, int port) {
        // ① 探测证据
        String wantIp = DualStackPrefs.preferAddress(host, family);
        if (wantIp != null && !wantIp.isEmpty()) {
            for (InetAddress a : all) {
                if (familyOf(a) == family && wantIp.equals(a.getHostAddress())) return a;
            }
            // 记录里的地址已经不在 DNS 结果里（边缘换了）⇒ 建议自然失效，继续往下走
        }
        // ② 轮转兜底
        int rot = DualStackPrefs.rotationIndex(host, family);
        if (rot >= 0) {
            List<InetAddress> fam = new ArrayList<>();
            for (InetAddress a : all) {
                if (familyOf(a) == family) fam.add(a);
            }
            if (!fam.isEmpty()) return fam.get(rot % fam.size());
        }
        // ③ 粘性缓存（v99-r91：必须仍在本次 DNS 名单内，理由见 cachedFirst）
        InetAddress first = cachedFirst(host, port, all);
        if (first != null && familyOf(first) == family) return first;
        return null;
    }

    /**
     * 取"上次真实连接成功过的地址"，且<b>必须仍在本次 DNS 名单 {@code all} 里</b>。
     *
     * <p>v99-r91 修正：此前不看 {@code all}，直接把缓存地址排第一，结果会被重定向污染 ——
     * {@link #recordSuccess} 记的是 {@code call.request().url().host()}（<b>重定向前</b>的 host）
     * 配上<b>实际连上的</b>地址，所以 302 目标的 CDN 边缘 IP 会被记在原 host 名下、
     * 端口还是原 host 的端口。实测日志里出现过
     * {@code order ott.fj.chinamobile.com ... first=[2409:8087:3410:20:110:3000:0:2a] total=5}——
     * ott 的 DNS 只有 4 个地址，那个 v6 是 <b>zterr</b> 的，且它的 80 端口是 SYN 黑洞，
     * 于是白白烧掉 3 秒 watchdog。</p>
     *
     * <p>加了这道校验后，只会把"这台 host 自己解析出来的地址"提到最前，
     * 别人的边缘 IP 再也不会被拿去敲本 host 的端口。缓存记录本身<b>不删除</b>
     * （DNS 每次应答可能只给部分节点，留着不影响任何判断）。</p>
     */
    private static InetAddress cachedFirst(String host, int port, List<InetAddress> all) {
        if (host == null || host.isEmpty()) return null;
        if (port > 0) {
            Entry byPort = CACHE.get(host + ":" + port);
            if (byPort != null && now() - byPort.at < CACHE_TTL_MS
                    && inAnswer(all, byPort.address)) {
                return byPort.address;
            }
        }
        Entry byHost = HOST_LATEST.get(host);
        if (byHost != null && now() - byHost.at < CACHE_TTL_MS
                && inAnswer(all, byHost.address)) {
            return byHost.address;
        }
        return null;
    }

    /** 缓存里到底有没有这条记录（不管它是否还在 DNS 名单里）——只用于日志区分 cache / cache-miss。 */
    private static boolean hasCacheRecord(String host, int port) {
        if (host == null || host.isEmpty()) return false;
        if (port > 0) {
            Entry byPort = CACHE.get(host + ":" + port);
            if (byPort != null && now() - byPort.at < CACHE_TTL_MS) return true;
        }
        Entry byHost = HOST_LATEST.get(host);
        return byHost != null && now() - byHost.at < CACHE_TTL_MS;
    }

    /**
     * 该地址是否出现在本次 DNS 应答里。
     *
     * <p>先比对象（{@link InetAddress#equals} 比字节），再比文本 —— 同一个 IP 若来自不同来源的
     * {@link InetAddress} 实例，IPv6 的作用域 id 可能不同导致 {@code equals} 为 false，
     * 但文本相同就是同一个地址。</p>
     */
    static boolean inAnswer(List<InetAddress> all, InetAddress a) {
        if (all == null || a == null || all.isEmpty()) return false;
        String ip = a.getHostAddress();
        for (InetAddress x : all) {
            if (x == null) continue;
            if (x.equals(a)) return true;
            if (ip != null && ip.equals(x.getHostAddress())) return true;
        }
        return false;
    }

    private static int familyOf(InetAddress a) {
        if (a instanceof Inet4Address) return DualStackPrefs.FAMILY_V4;
        if (a instanceof Inet6Address) return DualStackPrefs.FAMILY_V6;
        return DualStackPrefs.FAMILY_DEFAULT;
    }

    /** 地址文本（IPv6 带方括号，便于和 URL 里的写法对照）。 */
    private static String addrText(InetAddress a) {
        if (a == null) return "null";
        String s = a.getHostAddress();
        return a instanceof Inet6Address ? "[" + s + "]" : s;
    }

    /**
     * v99-r84 取证日志：同一个 host 的排序结论不变就不重复打。
     *
     * <p>只在"有明确族偏好"的路径上调用 —— 也就是翻族之后。
     * 平时（IPv4 优先的默认分支）不打，避免每条 HLS 分片都刷一行。</p>
     */
    private static void logOrderOnce(String host, String what) {
        try {
            String prev = LOGGED_ORDER.put(host, what);
            if (!what.equals(prev)) {
                LOG.i("[net] order " + host + " " + what);
            }
            if (LOGGED_ORDER.size() > 256) {
                LOGGED_ORDER.clear();                            // 极简上限，防无界增长
            }
        } catch (Throwable ignored) {
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
            // host 级兜底记录指向的就是这个端口时才清，避免别的端口把它带走
            Entry e = HOST_LATEST.get(host);
            if (e != null && (e.port == port || e.port <= 0)) HOST_LATEST.remove(host);
        } catch (Throwable ignored) {
        }
    }

    /** 真实连接成功时记录，供 {@link #order} 做粘性排序（由 NetEventListener.connectEnd 调用）。 */
    public static void recordSuccess(String host, int port, InetAddress addr) {
        try {
            if (host == null || host.isEmpty() || addr == null || port <= 0) return;
            // recordSuccess 来自真实连接，地址总数未知（-1）⇒ 调用方按"可固化"处理，
            // 但 connectFailed 会立即作废，所以不会长期固化在一个坏 IP 上
            put(host, port, addr, -1);
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

    private static void put(String host, int port, InetAddress addr, int total) {
        CACHE.put(host + ":" + port, entry(addr, total, port));
        HOST_LATEST.put(host, entry(addr, total, port));
        trim();
    }

    private static Entry entry(InetAddress a, int total, int port) {
        Entry e = new Entry();
        e.address = a;
        e.at = now();
        e.total = total;
        e.port = port;
        return e;
    }

    private static void trim() {
        if (CACHE.size() <= CACHE_MAX) return;
        long cutoff = now() - CACHE_TTL_MS;
        for (java.util.Iterator<java.util.Map.Entry<String, Entry>> it = CACHE.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().at < cutoff) it.remove();
        }
        for (java.util.Iterator<java.util.Map.Entry<String, Entry>> it = HOST_LATEST.entrySet().iterator(); it.hasNext(); ) {
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
