package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.orhanobut.hawk.Hawk;

import java.util.HashMap;
import java.util.List;

/**
 * 源质量档案（公共类）。
 *
 * <p>记录每个源在「搜索」与「播放」两个维度上的历史表现，供以下场景复用同一份数据：
 * <ul>
 *   <li>详情页切源：挑探路源 / 排序搜索结果（以播放质量为主）</li>
 *   <li>首页搜索 / 搜索页：结果列表排序（以搜索命中为主，播放质量为辅）</li>
 * </ul>
 *
 * <p><b>权重取向</b>：需求方明确指出「质量与命中率没有直接关系」，
 * 因此 {@link #searchScore(String)} 里命中率权重被刻意压低，
 * 让「响应快」而不是「搜得中」占据更大比重。
 *
 * <p>存储格式（Hawk，key 前缀见 {@link #KEY_PREFIX}）：
 * {@code hitCnt,failCnt,totalMs,playOkCnt,playFailCnt,firstFrameMsSum}。
 * 早期版本只有前三项，读取时做了兼容（缺失字段按 0 处理）。
 */
public class SourceQualityStore {

    private static final String KEY_PREFIX = "fb_stat_";

    /**
     * 统计读改写的全局锁。
     *
     * <p>锁粒度取全局而非「按 sourceKey 分段」：本类的写操作只在
     * 「一次搜索/一次起播结算」时发生（低频），而每次写只是几次 Hawk 读写。
     * 全局锁的争用开销远小于分段锁的实现复杂度，且彻底消除跨 key 的死锁风险。</p>
     */
    private static final Object STAT_LOCK = new Object();

    // ===== 权重配置 =====

    /** 搜索命中率的权重（按要求调低：命中率不代表质量）。 */
    private static final double W_SEARCH_HIT = 0.15;
    /** 响应速度的权重（速度直接决定体验，给高权重）。 */
    private static final double W_SEARCH_SPEED = 0.55;
    /** 播放成功率的权重（能播才是真的好）。 */
    private static final double W_PLAY_OK = 0.30;
    /** 无任何历史数据时的中性分。 */
    private static final double NEUTRAL = 0.5;

    /** 速度奖励上限（毫秒越快越接近该值）。 */
    private static final double SPEED_FULL_BONUS_MS = 400.0;

    private SourceQualityStore() {
    }

    /** 一条源档案快照。 */
    public static class Stat {
        public int searchHit;
        public int searchFail;
        public long searchTotalMs;
        public int playOk;
        public int playFail;
        public long firstFrameTotalMs;

        /** 是否有任何可参考的历史。 */
        public boolean hasSearchHistory() {
            return searchHit + searchFail > 0;
        }

        public boolean hasPlayHistory() {
            return playOk + playFail > 0;
        }

        public boolean hasAnyHistory() {
            return hasSearchHistory() || hasPlayHistory();
        }
    }

    // ==================== 读写 ====================

    /** 读取某源的档案。 */
    public static Stat read(String sourceKey) {
        Stat stat = new Stat();
        if (TextUtils.isEmpty(sourceKey)) {
            return stat;
        }
        try {
            String raw = Hawk.get(KEY_PREFIX + sourceKey, "");
            if (TextUtils.isEmpty(raw)) {
                return stat;
            }
            String[] parts = raw.split(",");
            stat.searchHit = parseInt(parts, 0);
            stat.searchFail = parseInt(parts, 1);
            stat.searchTotalMs = parseLong(parts, 2);
            stat.playOk = parseInt(parts, 3);
            stat.playFail = parseInt(parts, 4);
            stat.firstFrameTotalMs = parseLong(parts, 5);
        } catch (Throwable th) {
            // 读取失败按「无历史」处理，不影响调用方
        }
        return stat;
    }

    /** 记录一次搜索结果。 */
    public static void recordSearch(String sourceKey, boolean hit, long elapsedMs) {
        if (TextUtils.isEmpty(sourceKey)) {
            return;
        }
        try {
            // ★ v43：read-modify-write 必须整体加锁。
            //   此前是「读 → 改 → 写」三步无锁，而搜索是多线程并发（单批 6 路），
            //   详情页探路的 recordSearch 与播放页的 recordPlay 完全可能同时落到
            //   同一个源上 → 后写覆盖先写，统计静默丢失（让质量分越用越不准）。
            synchronized (STAT_LOCK) {
                Stat stat = read(sourceKey);
                if (hit) {
                    stat.searchHit++;
                } else {
                    stat.searchFail++;
                }
                stat.searchTotalMs += Math.max(0L, elapsedMs);
                write(sourceKey, stat);
            }
        } catch (Throwable th) {
            // 忽略：统计失败不应影响主流程
        }
    }

    /**
     * 记录一次播放结果。
     *
     * @param ok           是否成功起播（出了首帧）
     * @param firstFrameMs 起播耗时（拿到地址到首帧）
     */
    public static void recordPlay(String sourceKey, boolean ok, long firstFrameMs) {
        if (TextUtils.isEmpty(sourceKey)) {
            return;
        }
        try {
            // ★ v43：同 recordSearch，read-modify-write 整体加锁。
            synchronized (STAT_LOCK) {
                Stat stat = read(sourceKey);
                if (ok) {
                    stat.playOk++;
                    stat.firstFrameTotalMs += Math.max(0L, firstFrameMs);
                } else {
                    stat.playFail++;
                }
                write(sourceKey, stat);
            }
        } catch (Throwable th) {
            // 忽略
        }
    }

    private static void write(String sourceKey, Stat stat) {
        String value = stat.searchHit + "," + stat.searchFail + "," + stat.searchTotalMs
                + "," + stat.playOk + "," + stat.playFail + "," + stat.firstFrameTotalMs;
        Hawk.put(KEY_PREFIX + sourceKey, value);
    }

    // ==================== 评分 ====================

    /**
     * 通用质量分（越高越好）。
     *
     * <p>组成：播放成功率 × 0.30 + 搜索速度 × 0.55 + 搜索命中率 × 0.15。
     * 命中率被刻意压低 —— 需求方认为「搜得到」不等于「质量好」。
     */
    public static double score(String sourceKey) {
        try {
            Stat stat = read(sourceKey);
            if (!stat.hasAnyHistory()) {
                return NEUTRAL;
            }
            double playRate = NEUTRAL;
            if (stat.hasPlayHistory()) {
                playRate = (double) stat.playOk / (stat.playOk + stat.playFail);
            }
            double hitRate = NEUTRAL;
            if (stat.hasSearchHistory()) {
                hitRate = (double) stat.searchHit / (stat.searchHit + stat.searchFail);
            }
            double speedScore = searchSpeedScore(stat);
            return playRate * W_PLAY_OK + speedScore * W_SEARCH_SPEED + hitRate * W_SEARCH_HIT;
        } catch (Throwable th) {
            return NEUTRAL;
        }
    }

    /**
     * 搜索结果排序用分。
     *
     * <p>与 {@link #score(String)} 的差别：搜索场景下「有没有片」本身就是核心诉求，
     * 所以命中率权重适当回升；但仍低于速度权重，避免"能搜到但很慢"的源排前面。
     */
    public static double searchScore(String sourceKey) {
        try {
            Stat stat = read(sourceKey);
            if (!stat.hasAnyHistory()) {
                return NEUTRAL;
            }
            double playRate = NEUTRAL;
            if (stat.hasPlayHistory()) {
                playRate = (double) stat.playOk / (stat.playOk + stat.playFail);
            }
            double hitRate = NEUTRAL;
            if (stat.hasSearchHistory()) {
                hitRate = (double) stat.searchHit / (stat.searchHit + stat.searchFail);
            }
            double speedScore = searchSpeedScore(stat);
            // 搜索场景：命中 0.35 / 速度 0.45 / 播放 0.20
            return hitRate * 0.35 + speedScore * 0.45 + playRate * 0.20;
        } catch (Throwable th) {
            return NEUTRAL;
        }
    }

    // ==================== 批量快照 ====================

    /**
     * 一组源的质量分快照。
     *
     * <p><b>为什么要它</b>：排序比较器会被调用 O(n log n) 次，若在比较器里直接调
     * {@link #score(String)} / {@link #searchScore(String)}，每次都会读一遍 Hawk，
     * 100 个源会产生上千次读取（详情页同步执行时会明显卡顿）；而且排序过程中若有并发写入，
     * 同一个源两次比较可能得到不同分数，违反 {@code Comparator} 传递性契约。
     *
     * <p><b>做法</b>：排序前先按源逐个读一次并缓存分数，之后的比较全部走内存。
     * 读取次数从 O(n log n) 降到 O(n)，且比较结果在本次排序内恒定。
     */
    public static class Snapshot {

        /** 仅供本类填充，外部勿直接修改。 */
        private final HashMap<String, Double> scores;

        private Snapshot(HashMap<String, Double> scores) {
            this.scores = scores;
        }

        /** 取某源的分数；未收录（含空 key）时返回中性分。 */
        public double get(String sourceKey) {
            if (TextUtils.isEmpty(sourceKey)) {
                return NEUTRAL;
            }
            Double v = scores.get(sourceKey);
            return v == null ? NEUTRAL : v;
        }

        /** 本次快照里的源数量。 */
        public int size() {
            return scores.size();
        }

        /**
         * 快照里是否「完全没有可参考的历史」。
         *
         * <p>冷启动（首次安装、尚无任何统计）时所有源都是中性分，
         * 排序不会改变任何顺序，调用方可据此**跳过整个排序**，省下这轮开销。
         */
        public boolean isEmpty() {
            return scores.isEmpty();
        }
    }

    /**
     * 为一批源建立分数快照（切源选站用）。
     *
     * @param sourceKeys 参与排序的源 key
     */
    public static Snapshot snapshot(List<String> sourceKeys) {
        return buildSnapshot(sourceKeys, false);
    }

    /** 为一批源建立分数快照（搜索排序用）。 */
    public static Snapshot snapshotForSearch(List<String> sourceKeys) {
        return buildSnapshot(sourceKeys, true);
    }

    private static Snapshot buildSnapshot(List<String> sourceKeys, boolean forSearch) {
        HashMap<String, Double> map = new HashMap<>();
        if (sourceKeys != null) {
            for (String key : sourceKeys) {
                if (TextUtils.isEmpty(key) || map.containsKey(key)) {
                    continue;
                }
                map.put(key, forSearch ? searchScore(key) : score(key));
            }
        }
        return new Snapshot(map);
    }

    /** 平均响应耗时（毫秒），无数据返回 0。 */
    public static long avgSearchMs(String sourceKey) {
        Stat stat = read(sourceKey);
        int total = stat.searchHit + stat.searchFail;
        return total > 0 ? stat.searchTotalMs / total : 0L;
    }

    private static double searchSpeedScore(Stat stat) {
        int total = stat.searchHit + stat.searchFail;
        if (total <= 0 || stat.searchTotalMs <= 0) {
            return NEUTRAL;
        }
        double avgMs = (double) stat.searchTotalMs / total;
        if (avgMs <= 0) {
            return NEUTRAL;
        }
        // 越快越接近 1，越慢越接近 0
        return Math.max(0.0, Math.min(1.0, SPEED_FULL_BONUS_MS / avgMs));
    }

    // ==================== 工具 ====================

    private static int parseInt(String[] parts, int idx) {
        if (parts == null || idx >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[idx].trim());
        } catch (Throwable th) {
            return 0;
        }
    }

    private static long parseLong(String[] parts, int idx) {
        if (parts == null || idx >= parts.length) {
            return 0L;
        }
        try {
            return Long.parseLong(parts[idx].trim());
        } catch (Throwable th) {
            return 0L;
        }
    }
}
