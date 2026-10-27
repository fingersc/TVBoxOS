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

    private static final class Pref {
        final int family;
        final long at;

        Pref(int family, long at) {
            this.family = family;
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
