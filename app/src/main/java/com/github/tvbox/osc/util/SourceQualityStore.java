package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.orhanobut.hawk.Hawk;

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
            Stat stat = read(sourceKey);
            if (hit) {
                stat.searchHit++;
            } else {
                stat.searchFail++;
            }
            stat.searchTotalMs += Math.max(0L, elapsedMs);
            write(sourceKey, stat);
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
            Stat stat = read(sourceKey);
            if (ok) {
                stat.playOk++;
                stat.firstFrameTotalMs += Math.max(0L, firstFrameMs);
            } else {
                stat.playFail++;
            }
            write(sourceKey, stat);
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
