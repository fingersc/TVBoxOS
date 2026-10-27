package xyz.doikki.videoplayer.util;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双栈地址族偏好（v99-r83）。
 *
 * <p>存在的原因：同一个域名在 IPv4 / IPv6 两族下可能<b>指向完全不同的后端</b>，
 * 其中一族对某个资源返回 404、另一族正常（实测：移动 OTT 直播地址 IPv4 被 302 到 IPTV 平台
 * 而该频道号在那边不存在；IPv6 走 OTT 自己的 CDN 正常出流）。遇到这种情况必须能"翻族重试"。</p>
 *
 * <p>为什么放在 player 模块：翻族的判定点在 {@code ExoMediaPlayer.onPlayerError()}（能拿到 HTTP 状态码），
 * 而 DNS 排序在 app 模块的 {@code DualStackResolver}。app 依赖 player，反向不成立，
 * 所以<b>状态放这里</b>、player 写、app 读 —— 保证只有一个真相源，不出现两份互相打架的偏好。</p>
 *
 * <p>铁律：</p>
 * <ol>
 *   <li>偏好只是"排序建议"，绝不删除地址 —— 某族优先仍失败时，排在后面的另一族必须还在</li>
 *   <li>必须过期（{@link #TTL_MS}）：IPTV 节点与调度会变，固化久了会连着失败到用户手动处理</li>
 *   <li>任何异常一律等价于"无偏好"</li>
 * </ol>
 */
public final class DualStackPrefs {

    /** 无偏好：走默认顺序（IPv4 优先、AAAA 保留在尾部，见 DualStackResolver）。 */
    public static final int FAMILY_DEFAULT = 0;
    public static final int FAMILY_V4 = 4;
    public static final int FAMILY_V6 = 6;

    /** 2 分钟，与 DualStackResolver 的探测缓存同期：两者错开会造成"排序用旧、偏好用新"的抖动。 */
    private static final long TTL_MS = 2 * 60 * 1000L;
    private static final int MAX = 128;

    private static final ConcurrentHashMap<String, Pref> PREFS = new ConcurrentHashMap<>();

    /**
     * v99-r85：已知<b>不可用</b>的 (host, 族) 组合 —— 实测翻族后 3 秒仍起不来才登记。
     *
     * <p>与 {@link #PREFS} 相反：{@code PREFS} 是"这次该走哪族"的建议，
     * 这里是"走过了，走不通"的事实记录。有了它，翻族失败的代价每个 host 只用付一次，
     * 之后同 host 直接不翻、立刻换线，回到最快路径。见 {@link #isFamilyDead}。</p>
     */
    private static final ConcurrentHashMap<String, Dead> DEAD = new ConcurrentHashMap<>();

    /**
     * 30 分钟。比 {@link #TTL_MS} 长得多：换网络、运营商开通 IPv6、DHCP 重下发都可能让
     * 一条路从死变活，但都比 2 分钟慢，所以不能按偏好那个周期过期。
     */
    private static final long DEAD_TTL_MS = 30 * 60 * 1000L;
    private static final int DEAD_MAX = 64;

    private static final class Pref {
        final int family;
        final long at;

        Pref(int family, long at) {
            this.family = family;
            this.at = at;
        }
    }

    private static final class Dead {
        final long at;

        Dead(long at) {
            this.at = at;
        }
    }

    private DualStackPrefs() {
    }

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
            trim();
        } catch (Throwable ignored) {
        }
    }

    /** 取 host 的首选地址族；无偏好或已过期返回 {@link #FAMILY_DEFAULT}。 */
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

    /** 清除 host 的偏好（例如翻族后仍然失败，说明与地址族无关）。 */
    public static void clear(String host) {
        try {
            if (host != null && !host.isEmpty()) {
                PREFS.remove(host);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 登记 (host, family) 实测不可用（v99-r85）。
     *
     * <p>唯一写入点是播放器的翻族 watchdog：翻过去 3 秒还没就绪，才判定这条路不通。
     * 不靠 DNS、不靠本机接口列表猜 —— 那些判据都可能在"有地址但路由不通"时说谎。</p>
     */
    public static void markFamilyDead(String host, int family) {
        try {
            if (host == null || host.isEmpty()) {
                return;
            }
            if (family != FAMILY_V4 && family != FAMILY_V6) {
                return;
            }
            DEAD.put(deadKey(host, family), new Dead(System.currentTimeMillis()));
            trimDead();
        } catch (Throwable ignored) {
        }
    }

    /** (host, family) 是否已在 {@link #DEAD_TTL_MS} 内被判定不可用；过期或异常一律 false。 */
    public static boolean isFamilyDead(String host, int family) {
        try {
            if (host == null || host.isEmpty()) {
                return false;
            }
            if (family != FAMILY_V4 && family != FAMILY_V6) {
                return false;
            }
            String key = deadKey(host, family);
            Dead d = DEAD.get(key);
            if (d == null) {
                return false;
            }
            if (System.currentTimeMillis() - d.at >= DEAD_TTL_MS) {
                DEAD.remove(key);
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 该族翻身成功时撤销死亡记录，避免"曾经死过"永久压制一条已经恢复的路。 */
    public static void clearFamilyDead(String host, int family) {
        try {
            if (host != null && !host.isEmpty()) {
                DEAD.remove(deadKey(host, family));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String deadKey(String host, int family) {
        return host + "|v" + family;
    }

    private static void trimDead() {
        if (DEAD.size() <= DEAD_MAX) {
            return;
        }
        long cutoff = System.currentTimeMillis() - DEAD_TTL_MS;
        for (Iterator<Map.Entry<String, Dead>> it = DEAD.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().at < cutoff) {
                it.remove();
            }
        }
        if (DEAD.size() <= DEAD_MAX) {
            return;
        }
        // 全是新记录也超限：丢掉最早的一半，宁可多付一次探测也不要无限增长
        int drop = DEAD.size() - DEAD_MAX / 2;
        for (Iterator<Map.Entry<String, Dead>> it = DEAD.entrySet().iterator(); it.hasNext() && drop > 0; drop--) {
            it.next();
            it.remove();
        }
    }

    private static void trim() {
        if (PREFS.size() <= MAX) {
            return;
        }
        long cutoff = System.currentTimeMillis() - TTL_MS;
        for (Iterator<Map.Entry<String, Pref>> it = PREFS.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().at < cutoff) {
                it.remove();
            }
        }
    }
}
