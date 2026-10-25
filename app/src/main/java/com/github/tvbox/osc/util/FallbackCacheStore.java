package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
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
 *
 * <p>★ v99-r59：盒子里除了候选，还存一份<b>已问源集合</b>（{@link FallbackCacheBox#askedKeys}）——
 * 它是「这部片的全部可搜源都已经问过一遍」的唯一凭据。放在同一个 Hawk 记录里而不是另开 key，
 * 是为了让 TTL / LRU 淘汰 / {@link #clearFor} / {@link #gcOrphan} 全都自动继承，
 * 不会出现「候选池删了、问过的标记还在」这种各过各的寿命。</p>
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

    /**
     * Hawk 里存的精简缓存（只留 sourceKey/id/name，避免序列化整个 Movie.Video）。
     *
     * <p>★ v99-r59：字段改成<b>非 final</b>。原来三个字段全是 {@code final} 且没有无参构造器，
     * Gson 只能靠 Unsafe 分配实例再反射写字段 —— 这条路在 ART 上对 final 实例字段并不保证成立，
     * 一旦不成立就是「读回来永远是 null」而且没有任何报错。改非 final 后与仓库里其它
     * Hawk 持久化类的写法一致，消掉这类静默失败。</p>
     */
    public static class FallbackCacheBox {
        public List<String[]> entries;

        /**
         * ★ v99-r59：本部片已经问过哪些源（{@code sourceKey} 集合，含问失败的）。
         *
         * <p><b>为什么必须持久化</b>：r58 的「跑完不重跑」判据挂在实例字段上，
         * 而每次进详情页都是<b>新的 Activity 实例</b>（真机日志：4 部片 4 次 create/destroy），
         * 实例标志恒为 false ⇒ 那条判据从上线起一次都没生效过。</p>
         *
         * <p><b>为什么不能改用「候选数是否达到上限 40」判</b>：池子小恰恰说明源站确实没资源，
         * 那种片每次都重跑 101 个源是纯浪费；而池子满 40 是<b>上限截断</b>、不等于问完了。
         * 两个方向上都会判错，所以只能记「问过谁」。</p>
         *
         * <p><b>为什么存集合不存游标下标</b>：{@code sourceOrder} 按站点质量动态排序，
         * 两次进来顺序会漂，存下标会漏掉「上次排在后面、这次排在前面」的源。</p>
         */
        public List<String> askedKeys;

        public long savedAt;

        /**
         * ★ v99-r66：落盘时「择优折叠」所依据的基准资源年份（0 = 未折叠 / 基准无年份）。
         *
         * <p>折叠的前提是「用当时那份 ref 判对了哪条是同片」。若下次进片时 ref 换了
         * （最坏情况：上次是从<b>错片源</b>进来的，整池被收敛到了错片），这份折叠结果就是错的，
         * 而候选已经只留了一条、无法回退 ⇒ 只能把整条记录判为失效、重新攒池。
         * 记一个 year 就是为了让这个判断成立：同一部片的 year 稳定，错片往往年份不同
         * （真机《人世间》：正片 2022 / 错片 2024），比 pic 可靠得多（各站海报 URL 常不同）。</p>
         */
        public int refYear;

        /**
         * ★ v99-r67：校验这批候选时所用基准资源的<b>演员表</b>（截前 60 字，可为空）。
         *
         * <p>换片判定的辅助信号：`refYear` 相同或缺失时，若两边演员交叉不上
         * （{@code metaRelated}），说明用户换看的是「另一部同名片」，这条记录应当作废重来。
         * 各站演员表完整度差异极大，所以用「交叉」判定而不是全同。</p>
         */
        public String refActor;

        public FallbackCacheBox(List<String[]> entries, List<String> askedKeys, long savedAt,
                                int refYear, String refActor) {
            this.entries = entries;
            this.askedKeys = askedKeys;
            this.savedAt = savedAt;
            this.refYear = refYear;
            this.refActor = refActor;
        }
    }

    /**
     * ★ v99-r69：这里接收的是<b>桶键</b>（形如 {@code 片名|y2020|d李路}），不再自己算 familyKey。
     *
     * <p>同名不同片（如《隐秘而伟大》国产版 2020 与韩国版 2013）此前共用一份池 —— 换到另一部片时
     * 27/29 个站被判年份硬矛盾、池子被剔到只剩 2 条（真机实证）。分桶后每部片各占一份，互不影响。
     * 桶键由调用方（DetailActivity）按「片名 + 年份 + 导演首名」构造，本类只负责加前缀 ——
     * 若这里再跑一次 familyKey，可能把 {@code |} 或年份吞掉，导致不同桶碰撞。</p>
     */
    public static String poolKeyOf(String bucket) {
        return bucket == null ? "" : bucket.trim();
    }

    /**
     * ★ v99-r69：删除某个片名下的<b>所有桶</b>。
     *
     * <p>历史记录是<b>合并</b>的（{@code removeSameNameVodRecords} 按片名删），而池是<b>分桶</b>的
     * （可能 2 个甚至 3 个版本）⇒ 删历史时必须前缀匹配全部清掉，否则会留下无人认领的池，
     * 下次进片又被恢复出来。</p>
     */
    public static void clearForFamily(String title) {
        String base = ShowTitleKey.familyKey(title == null ? "" : title.trim());
        if (TextUtils.isEmpty(base)) {
            return;
        }
        try {
            List<String> raw = Hawk.get(INDEX);
            if (raw == null) {
                return;
            }
            List<String> index = new ArrayList<String>(raw);
            Iterator<String> it = index.iterator();
            List<String> hit = new ArrayList<>();
            while (it.hasNext()) {
                String k = it.next();
                if (k != null && (k.equals(base) || k.startsWith(base + "|"))) {
                    hit.add(k);
                    it.remove();
                }
            }
            for (String k : hit) {
                Hawk.delete(PREFIX + k);
            }
            if (!hit.isEmpty()) {
                Hawk.put(INDEX, index);
                LOG.i("FallbackCacheStore.clearForFamily " + base + " buckets=" + hit.size());
            }
        } catch (Throwable th) {
            LOG.e("FallbackCacheStore.clearForFamily fail: " + th);
        }
    }

    /**
     * 写入一部片子的候选池 + 已问源集合，并把它的 key 顶到索引末尾
     * （超出 {@link #MAX_ENTRIES} 淘汰最旧的）。
     *
     * <p>★ v99-r59：{@code slim} 可以为空、{@code askedKeys} 不可以同时为空。
     * 「全部问完却零候选」的片（源站确实没资源）也必须落一条记录，
     * 否则下次进页读不回已问集合，又会从头跑一遍 101 个源。</p>
     *
     * <p>★ v99-r66/r67：{@code refYear} 是「这批候选在哪一年的基准下校验过」（0 = 未校验）；
     * {@code refActor} 是同一次校验所用的基准演员表（换片判定的辅助信号）。</p>
     */
    public static void put(String title, List<String[]> slim, List<String> askedKeys,
                           int refYear, String refActor) {
        String poolKey = poolKeyOf(title);
        if (TextUtils.isEmpty(poolKey)) {
            return;
        }
        boolean hasEntries = slim != null && !slim.isEmpty();
        boolean hasAsked = askedKeys != null && !askedKeys.isEmpty();
        if (!hasEntries && !hasAsked) {
            return;
        }
        try {
            // ★ v99-r61：Hawk.put 返回 false 表示没写进去（SharedPreferences commit 失败 /
            //   磁盘满等），而 r59 的「跑完不重跑」完全建立在「问过的标记真的落盘了」之上。
            //   不检查返回值的话，写失败的表现是「下次进片照样重跑 101 个源」——
            //   一个没有任何报错的静默故障，只能靠猜。这里把它变成可见。
            boolean written = Hawk.put(PREFIX + poolKey, new FallbackCacheBox(
                    hasEntries ? slim : new ArrayList<String[]>(),
                    hasAsked ? askedKeys : new ArrayList<String>(),
                    System.currentTimeMillis(),
                    refYear,
                    refActor == null ? "" : refActor));
            if (!written) {
                LOG.e("FallbackCacheStore.put reject: " + poolKey
                        + " entries=" + (hasEntries ? slim.size() : 0)
                        + " asked=" + (hasAsked ? askedKeys.size() : 0));
                return;
            }
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
