package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 切源候选池的<b>持久化仓储</b>（Hawk）。
 *
 * <p>★ v99-r58 从 {@code DetailActivity} 里抽出来，原因有两个：</p>
 * <ol>
 *   <li><b>依赖方向</b>：r58 要求「删除历史 ⇒ 连带删掉该片的候选池」，而删历史的入口在
 *       {@code RoomDataManger}（数据层）。若方法留在 Activity 里，就会形成
 *       「数据层 → UI 层」的反向依赖；放进 util 后两边都只依赖它。</li>
 *   <li><b>空间治理</b>：r58 让候选池趋向"问满全部源"，池子会明显变大。TTL / LRU /
 *       孤儿回收这套账必须集中在一处，否则很容易出现「历史早就删了，池子还在占地方」。</li>
 * </ol>
 *
 * <p>每条 key 存一个 {@link FallbackCacheBox}（只留 sourceKey/id/name 三元组，
 * 不序列化整个 {@code Movie.Video}，一条约 40 字节）。</p>
 */
public final class FallbackCacheStore {

    private FallbackCacheStore() {
    }

    /** 候选池条目的 key 前缀（后面接 {@link ShowTitleKey#familyKey}）。 */
    public static final String PREFIX = "fb_cache_";
    /** 写入顺序索引（LRU 淘汰用）。 */
    public static final String INDEX = "fb_cache_index";
    /** 最多保留多少部片子的候选池。 */
    public static final int MAX_ENTRIES = 40;
    /** 候选池有效期：超过就算过期，下 restore 时删除。 */
    public static final long TTL_MS = 24 * 60 * 60 * 1000L;

    /** Hawk 里存的精简缓存（只留 sourceKey/id/name，避免序列化整个 Movie.Video）。 */
    public static class FallbackCacheBox {
        public final List<String[]> entries;
        public final long savedAt;

        public FallbackCacheBox(List<String[]> entries, long savedAt) {
            this.entries = entries;
            this.savedAt = savedAt;
        }
    }

    public static String poolKeyOf(String title) {
        return ShowTitleKey.familyKey(title == null ? "" : title.trim());
    }

    /**
     * 写入一部片子的候选池，并把它的 key 顶到索引末尾（超出 {@link #MAX_ENTRIES} 淘汰最旧的）。
     */
    public static void put(String title, List<String[]> slim) {
        String poolKey = poolKeyOf(title);
        if (TextUtils.isEmpty(poolKey) || slim == null || slim.isEmpty()) {
            return;
        }
        try {
            Hawk.put(PREFIX + poolKey, new FallbackCacheBox(slim, System.currentTimeMillis()));
            touchIndex(poolKey);
        } catch (Throwable th) {
            LOG.e("FallbackCacheStore.put fail: " + th);
        }
    }

    public static FallbackCacheBox get(String title) {
        String poolKey = poolKeyOf(title);
        if (TextUtils.isEmpty(poolKey)) {
            return null;
        }
        try {
            return Hawk.get(PREFIX + poolKey);
        } catch (Throwable th) {
            LOG.e("FallbackCacheStore.get fail: " + th);
            return null;
        }
    }

    /** 维护写入顺序索引（LRU）。 */
    private static void touchIndex(String poolKey) {
        try {
            List<String> raw = Hawk.get(INDEX);
            List<String> index = raw == null ? new ArrayList<String>() : new ArrayList<String>(raw);
            index.remove(poolKey);
            index.add(poolKey);
            while (index.size() > MAX_ENTRIES) {
                String oldest = index.remove(0);
                Hawk.delete(PREFIX + oldest);
            }
            Hawk.put(INDEX, index);
        } catch (Throwable th) {
            LOG.e("FallbackCacheStore.touchIndex fail: " + th);
        }
    }

    /**
     * ★ v99-r58：删除某部片子的候选池（历史记录被删除时调用）。
     *
     * <p><b>必须连索引一起删</b>：只删数据键不删索引，下次 {@link #touchIndex} 会拿着一份
     * 「记载着已删除片名」的索引做淘汰，淘汰到的键早就不存在了 —— 白删一次不说，
     * 索引还会长期停在满容量。</p>
     */
    public static void clearFor(String title) {
        try {
            String poolKey = poolKeyOf(title);
            if (TextUtils.isEmpty(poolKey)) {
                return;
            }
            Hawk.delete(PREFIX + poolKey);
            List<String> raw = Hawk.get(INDEX);
            if (raw != null) {
                List<String> index = new ArrayList<String>(raw);
                if (index.remove(poolKey)) {
                    Hawk.put(INDEX, index);
                }
            }
            LOG.sw("[FB] poolClearFor key=" + poolKey);
        } catch (Throwable th) {
            LOG.e("FallbackCacheStore.clearFor fail: " + th);
        }
    }

    /**
     * 清空全部候选池（设置页「清空缓存」调用）。它是<b>纯缓存</b>：清掉后重新搜一遍即可。
     */
    public static void clearAll() {
        try {
            List<String> raw = Hawk.get(INDEX);
            if (raw != null) {
                for (String key : raw) {
                    if (key != null && !key.isEmpty()) {
                        Hawk.delete(PREFIX + key);
                    }
                }
            }
            Hawk.delete(INDEX);
        } catch (Throwable th) {
            LOG.e("FallbackCacheStore.clearAll fail: " + th);
        }
    }

    /**
     * ★ v99-r58：回收<b>孤儿池</b> —— 历史里已经没有这部片，池子却还占着 Hawk 的位置。
     *
     * <p><b>为什么不只靠删除时的精确删</b>：历史被删的路径远不止用户手动删 ——
     * 档位调小后的自动裁剪、同步落地时的 compact / dedupe / 合并删除，
     * 都会在用户无感知的情况下删掉历史行。这些路径不可能逐个通知候选池，
     * 只有事后按「幸存名单」回收才收得干净。</p>
     *
     * @param alivePoolKeys 历史里现存影片的 {@link ShowTitleKey#familyKey} 集合
     * @return 被回收的部数
     */
    public static int gcOrphan(Set<String> alivePoolKeys) {
        if (alivePoolKeys == null) {
            return 0;
        }
        try {
            List<String> raw = Hawk.get(INDEX);
            if (raw == null || raw.isEmpty()) {
                return 0;
            }
            List<String> keep = new ArrayList<String>();
            List<String> dropped = new ArrayList<String>();
            for (String key : raw) {
                if (key == null || key.isEmpty()) {
                    continue;
                }
                if (alivePoolKeys.contains(key)) {
                    keep.add(key);
                } else {
                    dropped.add(key);
                    Hawk.delete(PREFIX + key);
                }
            }
            if (!dropped.isEmpty()) {
                Hawk.put(INDEX, keep);
                LOG.sw("[FB] poolGc dropped=" + dropped.size() + " kept=" + keep.size());
            }
            return dropped.size();
        } catch (Throwable th) {
            LOG.e("FallbackCacheStore.gcOrphan fail: " + th);
            return 0;
        }
    }

    /**
     * 取历史全片的 familyKey 集合（供 {@link #gcOrphan} 用）。
     *
     * <p>历史行里<b>没有独立的片名字段</b>，只有 {@code dataJson}（VodInfo 序列化），
     * 所以必须反序列化才能拿到 name —— 这也是孤儿回收只能定期跑、不能挂在每次删除上的原因。</p>
     */
    public static Set<String> aliveKeysFrom(List<String> titles) {
        Set<String> keys = new HashSet<String>();
        if (titles != null) {
            for (String t : titles) {
                String k = poolKeyOf(t);
                if (!TextUtils.isEmpty(k)) {
                    keys.add(k);
                }
            }
        }
        return keys;
    }

    /** 是否已在同一进程内跑过今天的回收（避免每次进首页都扫一遍）。 */
    private static String lastGcDay = "";

    public static boolean todayGcDone() {
        String today = String.valueOf(System.currentTimeMillis() / (24 * 60 * 60 * 1000L));
        if (today.equals(lastGcDay)) {
            return true;
        }
        lastGcDay = today;
        return false;
    }

}
