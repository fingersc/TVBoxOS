package xyz.doikki.videoplayer.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双栈<b>排序建议</b>（v99-r83，v87 重做）。
 *
 * <p>存在的原因：同一个域名在 IPv4 / IPv6 两族下可能<b>指向完全不同的后端</b>，
 * 其中一族对某个资源返回 404、另一族正常（实测：移动 OTT 直播地址 IPv4 被 302 到 IPTV 平台
 * 而该频道号在那边不存在；IPv6 走 OTT 自己的 CDN 正常出流）。遇到这种情况必须能"翻族重试"。</p>
 *
 * <p>为什么放在 player 模块：翻族的判定点在 {@code ExoMediaPlayer.onPlayerError()}（能拿到 HTTP 状态码），
 * 而 DNS 排序在 app 模块的 {@code DualStackResolver}。app 依赖 player，反向不成立，
 * 所以<b>状态放这里</b>、player 写、app 读 —— 保证只有一个真相源。</p>
 *
 * <p><b>v87 的核心约定：这里只存"建议先试谁"，不存"谁不能用"。</b></p>
 *
 * <p>r86 曾有两张"死亡表"（{@code DEAD_URL} / {@code DEAD_HOST}），命中就把这条线路直接跳过。
 * 已<b>整体删除</b>，原因是它把"一次没起来"当成了"以后都不行"：</p>
 * <ul>
 *   <li>一条线路的失败会连坐同一台服务器上的其它线路，而实测 11 秒后同一族 95ms 就正常回了；</li>
 *   <li>更根本的是它让"某个 URL 从此不再被真正请求"，而源站/调度/网络都在变，
 *       任何一次误判都会一直错到过期为止。</li>
 * </ul>
 *
 * <p>现在<b>每条 URL 每次都会被真正请求</b>，本类只做两件不影响"能不能用"的事：</p>
 * <ol>
 *   <li>{@link #setPreferFamily}：翻族时让目标族排前面（<b>只排序，不删除任何地址</b>）；</li>
 *   <li>{@link #setPreferAddress}：探测发现"这一族的某个地址确实能连"时，下次优先试它；
 *       探不出结论（{@link #bumpRotation}）就把下一个地址排前 —— 保证多个地址会轮着被试到。</li>
 * </ol>
 *
 * <p>铁律：</p>
 * <ol>
 *   <li>一切记录都只是"排序建议"，<b>绝不排除任何 URL 或地址</b>；建议过期即丢弃</li>
 *   <li>必须过期（{@link #TTL_MS} 等）：IPTV 节点与调度会变，固化久了会连着错</li>
 *   <li>任何异常一律等价于"无建议"</li>
 *   <li><b>（v88）建议必须能被失败推翻，不能只靠时间。</b>时间只是兜底；
 *       一条只能等 TTL 到期的建议，TTL 多长就等于"错误能活多久"</li>
 * </ol>
 *
 * <p><b>v88 的三处加固</b>（都是为了让地址偏好敢放长到 2 小时）：</p>
 * <ol>
 *   <li>{@link #bumpRotation} 顺手清掉地址偏好 —— v87 只写游标不清证据，而排序层是
 *       "地址证据 → 轮转"，游标在有效期内<b>永远轮不到</b>，等于把"轮着敲"变成了空转；</li>
 *   <li>地址偏好绑定写入时的<b>网络指纹</b>（{@link #networkFingerprint}）：换网即作废，
 *       不必等 TTL —— 边缘 IP 按城域网就近分配，换一张网它就可能不通了；</li>
 *   <li>manifest 4xx 时清掉<b>当前族</b>的地址偏好（见 {@code ExoMediaPlayer}）：
 *       "这个地址没有这个资源"是最直接的否证。</li>
 * </ol>
 */
public final class DualStackPrefs {

    /** 无偏好：走默认顺序（IPv4 优先、AAAA 保留在尾部，见 DualStackResolver）。 */
    public static final int FAMILY_DEFAULT = 0;
    public static final int FAMILY_V4 = 4;
    public static final int FAMILY_V6 = 6;

    /**
     * 30 分钟（v89：原来 2 分钟）。
     *
     * <p><b>为什么必须放长</b>：族偏好不只是"先试谁"，它还是<b>整条地址级排序的入场券</b> ——
     * 排序层只有在族偏好存在时才走 {@code pickWithinFamily}（地址证据 → 轮转 → 粘性缓存），
     * 没有就落到"纯 IPv4 优先"的默认分支。所以 2 分钟过期 ≈ 每隔 2 分钟就把攒下的
     * "这个 host 的 v6 能出画"和"哪个地址连得通"一起作废，下次换台又从 v4 裸试一遍。</p>
     *
     * <p><b>为什么敢放长</b>（两条兜底都在 {@code ExoMediaPlayer} 里）：</p>
     * <ol>
     *   <li>连接层失败（IOException / 超时，不是 4xx）会立刻降级族偏好；</li>
     *   <li>403/404 <b>不再</b>降级 —— 那是资源级/边缘级偶发（实测同一频道 25 分钟前在 v6 上 404、
     *       之后在 v6 上 200），拿它降级会误伤后面一整串换台。</li>
     * </ol>
     *
     * <p>与 {@code DualStackResolver.CACHE_TTL_MS} 保持同一个量级：两者错得太远会造成
     * "排序用旧、偏好用新"的抖动。</p>
     */
    private static final long TTL_MS = 30 * 60 * 1000L;
    private static final int MAX = 128;

    /** 族级建议：host → 首选族。翻族时写入，决定哪一族排前面。 */
    private static final ConcurrentHashMap<String, Pref> PREFS = new ConcurrentHashMap<>();

    /**
     * 地址级建议：host + 族 → 一个"实测能连上"的具体地址。
     *
     * <p>实测场景：一个族的 DNS 结果通常有 2 个地址，其中一个是黑洞（不回 SYN）。
     * OkHttp 串行试地址，第一个是黑洞时 3 秒预算全耗在它身上，第二个根本没轮到。
     * 探测拿到"哪个地址活着"之后写这里，下次翻族直接把它排第一 —— 依旧只排序，不排除。</p>
     */
    private static final ConcurrentHashMap<String, AddrPref> ADDR_PREFS = new ConcurrentHashMap<>();
    /**
     * 2 小时（v88，原来 10 分钟）。
     *
     * <p>为什么敢放长：IPTV 边缘 IP 的存活周期本来就是小时到天级，10 分钟常常"刚探到就过期"，
     * 白付一次探测 + 3 秒超时。而地址偏好只决定<b>先试谁</b>，且 v88 起有三条自纠路径
     * （manifest 4xx 降级 / watchdog 到期轮转 / 换网指纹失效），推翻它已经不再依赖时间。</p>
     *
     * <p>为什么仍然要过期：源站调度会挪边缘、设备可能连续几小时挂着不动，
     * 一条永不腐烂的建议等于把"当时的巧合"变成"长期的偏置"。</p>
     */
    private static final long ADDR_TTL_MS = 2 * 60 * 60 * 1000L;
    private static final int ADDR_MAX = 128;

    /** 网络指纹缓存 5 秒：换网后最多 5 秒失效；网卡枚举不必每条 URL 都跑一遍。 */
    private static final long NET_FP_TTL_MS = 5 * 1000L;
    private static volatile String sNetFp;
    private static volatile long sNetFpAt;

    /**
     * 轮转游标：host + 族 → 下次从该族的第几个地址开始试。
     *
     * <p>什么时候用：探测<b>拿不出结论</b>（被防火墙拦住 / 全部不可达 / DNS 没给地址）时，
     * 我们不知道哪个地址好 —— 那就"下次换一个门牌先敲"。这样跨多次尝试必然把所有地址都试到，
     * 且因为探测本身会误判（项目铁律：探测只排序，绝不删除地址），这条兜底必须存在。</p>
     */
    private static final ConcurrentHashMap<String, Rotation> ROTATIONS = new ConcurrentHashMap<>();
    private static final long ROTATION_TTL_MS = 10 * 60 * 1000L;
    private static final int ROTATION_MAX = 128;

    private static final class Pref {
        final int family;
        final long at;

        Pref(int family, long at) {
            this.family = family;
            this.at = at;
        }
    }

    private static final class AddrPref {
        final String ip;
        /** 写入时的网络指纹（{@link #networkFingerprint}）；null = 当时拿不到，等于不做换网校验。 */
        final String net;
        final long at;

        AddrPref(String ip, String net, long at) {
            this.ip = ip;
            this.net = net;
            this.at = at;
        }
    }

    private static final class Rotation {
        final int index;
        final long at;

        Rotation(int index, long at) {
            this.index = index;
            this.at = at;
        }
    }

    private DualStackPrefs() {
    }

    // ------------------------------------------------------------------ 族级建议

    /** 登记 host 的首选地址族；family 传 {@link #FAMILY_DEFAULT} 等于清除。 */
    public static void setPreferFamily(String host, int family) {
        try {
            if (host == null || host.isEmpty()) {
                return;
            }
            if (family != FAMILY_V4 && family != FAMILY_V6) {
                PREFS.remove(host);
                return;
            }
            PREFS.put(host, new Pref(family, System.currentTimeMillis()));
            trim(PREFS, MAX, TTL_MS);
        } catch (Throwable ignored) {
        }
    }

    /** 取 host 的首选地址族；无建议或已过期返回 {@link #FAMILY_DEFAULT}。 */
    public static int preferFamily(String host) {
        try {
            if (host == null || host.isEmpty()) {
                return FAMILY_DEFAULT;
            }
            Pref p = PREFS.get(host);
            if (p == null) {
                return FAMILY_DEFAULT;
            }
            if (System.currentTimeMillis() - p.at >= TTL_MS) {
                PREFS.remove(host);
                return FAMILY_DEFAULT;
            }
            return p.family;
        } catch (Throwable t) {
            return FAMILY_DEFAULT;
        }
    }

    /** 清除 host 的族级建议（不影响任何地址的可用性）。 */
    public static void clear(String host) {
        try {
            if (host != null && !host.isEmpty()) {
                PREFS.remove(host);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 地址级建议

    /**
     * 记下"这个 host 的这一族里，{@code ip} 实测能连上"。
     *
     * <p>唯一写入点：翻族 watchdog 到期后的逐地址探测。它只影响<b>下次先试哪个地址</b>，
     * 不会让任何地址消失 —— 排序层拿不到该地址时会自动回落原顺序。</p>
     *
     * <p>v88：连同<b>当时所在的网络</b>一起记下。边缘 IP 是按城域网就近分配的，
     * 换了网（有线 ↔ 无线、换热点、重拨）这条证据就不再成立，见 {@link #preferAddress}。</p>
     */
    public static void setPreferAddress(String host, int family, String ip) {
        try {
            String key = key(host, family);
            if (key == null || ip == null || ip.isEmpty()) {
                return;
            }
            ADDR_PREFS.put(key, new AddrPref(ip, networkFingerprint(), System.currentTimeMillis()));
            trim(ADDR_PREFS, ADDR_MAX, ADDR_TTL_MS);
        } catch (Throwable ignored) {
        }
    }

    /** 取"优先试哪个地址"的 IP 文本；无建议、已过期、或换过网返回 null。 */
    public static String preferAddress(String host, int family) {
        try {
            String key = key(host, family);
            if (key == null) {
                return null;
            }
            AddrPref p = ADDR_PREFS.get(key);
            if (p == null) {
                return null;
            }
            if (System.currentTimeMillis() - p.at >= ADDR_TTL_MS) {
                ADDR_PREFS.remove(key);
                return null;
            }
            // v88：换过网就作废。任一侧拿不到指纹就不判断 —— 无法判断不等于失效，
            // 否则一次网卡枚举失败会把所有地址证据一起废掉。
            String now = networkFingerprint();
            if (p.net != null && now != null && !p.net.equals(now)) {
                ADDR_PREFS.remove(key);
                return null;
            }
            return p.ip;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 清除地址级建议（翻族成功、或换了地址策略时）。 */
    public static void clearPreferAddress(String host, int family) {
        try {
            String key = key(host, family);
            if (key != null) {
                ADDR_PREFS.remove(key);
            }
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------------- 轮转

    /**
     * 把该 host 该族的轮转游标推进一格，返回<b>新</b>游标。
     *
     * <p>唯一写入点：翻族 watchdog 到期且探测拿不到结论。语义是"下次换个地址先试"，
     * 不是"这个地址不能用"。排序层按 {@code cursor % size} 取起点，越界自动取模。</p>
     *
     * <p><b>v88：顺手清掉该族的地址偏好。</b>排序层是"地址证据 → 轮转"，
     * 只写游标不清证据的话，游标在证据有效期内<b>永远轮不到</b>（v87 的实际漏洞）。
     * 而且语义上本来就该清：走到这里意味着"上次优先的那个地址没让我 3 秒内出画"，
     * 这条建议已经被否证，留着只会让下一次继续先敲同一个没响的门。</p>
     */
    public static int bumpRotation(String host, int family) {
        try {
            String key = key(host, family);
            if (key == null) {
                return 0;
            }
            // 先清证据再写游标：中间任何一步失败都只会退回"无建议"，不会留下压制游标的残留。
            ADDR_PREFS.remove(key);
            Rotation old = ROTATIONS.get(key);
            int next = old == null ? 1 : old.index + 1;
            ROTATIONS.put(key, new Rotation(next, System.currentTimeMillis()));
            trim(ROTATIONS, ROTATION_MAX, ROTATION_TTL_MS);
            return next;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 取轮转游标；无记录或已过期返回 -1（调用方据此保持原顺序）。 */
    public static int rotationIndex(String host, int family) {
        try {
            String key = key(host, family);
            if (key == null) {
                return -1;
            }
            Rotation r = ROTATIONS.get(key);
            if (r == null) {
                return -1;
            }
            if (System.currentTimeMillis() - r.at >= ROTATION_TTL_MS) {
                ROTATIONS.remove(key);
                return -1;
            }
            return r.index;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 翻族成功后清掉轮转游标（已经有"哪个地址好"的证据了，不再需要瞎转）。 */
    public static void clearRotation(String host, int family) {
        try {
            String key = key(host, family);
            if (key != null) {
                ROTATIONS.remove(key);
            }
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------------- 内部

    /**
     * 测试钩子：同包可见，生产路径永不置位。没有它就没法验证"换网即失效"——
     * 真实换网在单测里做不到，而"从不被触发"的分支等于没写。
     */
    static volatile boolean sFpTestOn;
    static volatile String sFpTest;

    /**
     * 当前网络指纹：非回环接口名 + 每个接口的非链路本地 IPv4。
     *
     * <p><b>为什么只取 IPv4</b>：Android 的 IPv6 临时地址（RFC 4941）会自己滚动，
     * 拿它当指纹会误判成"换网了"，把本来有效的地址证据白白废掉；而"接口名 + IPv4"
     * 足以区分有线 ↔ 无线 ↔ 换热点 ↔ 重拨这几种真实变化。</p>
     *
     * <p><b>拿不到一律返回 null</b>（无权限 / 枚举异常 / 一个 IPv4 都没有），
     * 语义是"无法判断"，调用方必须跳过校验 —— 宁可继续用旧建议，也不要因为一次
     * 网卡枚举失败把所有地址证据一起作废。</p>
     */
    static String networkFingerprint() {
        if (sFpTestOn) {
            return sFpTest;
        }
        long now = System.currentTimeMillis();
        if (now - sNetFpAt < NET_FP_TTL_MS) {
            return sNetFp;                      // 含"上次算出来是 null"的缓存，避免高频重算
        }
        String fp = null;
        try {
            StringBuilder sb = new StringBuilder();
            Enumeration<NetworkInterface> it = NetworkInterface.getNetworkInterfaces();
            while (it != null && it.hasMoreElements()) {
                NetworkInterface ni = it.nextElement();
                if (ni == null) {
                    continue;
                }
                try {
                    if (ni.isLoopback() || !ni.isUp()) {
                        continue;
                    }
                    Enumeration<InetAddress> as = ni.getInetAddresses();
                    while (as != null && as.hasMoreElements()) {
                        InetAddress a = as.nextElement();
                        if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) {
                            continue;
                        }
                        sb.append(ni.getName()).append('=').append(a.getHostAddress()).append(';');
                    }
                } catch (Throwable ignored) {
                    // 单个接口读不到就跳过，不影响其它接口
                }
            }
            if (sb.length() > 0) {
                fp = Integer.toHexString(sb.toString().hashCode());
            }
        } catch (Throwable ignored) {
            fp = null;
        }
        sNetFp = fp;
        sNetFpAt = now;
        return fp;
    }

    private static String key(String host, int family) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (family != FAMILY_V4 && family != FAMILY_V6) {
            return null;
        }
        return host + "|v" + family;
    }

    /** 通用裁剪：先删过期的，仍然超限就丢最早的若干条（宁可丢建议，也不无限增长）。 */
    private static <T> void trim(ConcurrentHashMap<String, T> map, int max, long ttl) {
        if (map.size() <= max) {
            return;
        }
        long cutoff = System.currentTimeMillis() - ttl;
        for (Iterator<Map.Entry<String, T>> it = map.entrySet().iterator(); it.hasNext(); ) {
            if (timestamp(it.next().getValue()) < cutoff) {
                it.remove();
            }
        }
        if (map.size() <= max) {
            return;
        }
        int drop = map.size() - max / 2;
        for (Iterator<Map.Entry<String, T>> it = map.entrySet().iterator(); it.hasNext() && drop > 0; drop--) {
            it.next();
            it.remove();
        }
    }

    private static long timestamp(Object v) {
        if (v instanceof Pref) {
            return ((Pref) v).at;
        }
        if (v instanceof AddrPref) {
            return ((AddrPref) v).at;
        }
        if (v instanceof Rotation) {
            return ((Rotation) v).at;
        }
        return 0L;
    }
}
