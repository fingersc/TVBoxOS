package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 源质量档案（公共类）。
 *
 * <p>记录每个源在「搜索」与「播放」两个维度上的历史表现，供以下场景复用同一份数据：
 * <ul>
 *   <li>详情页切源：挑探路源 / 排序搜索结果</li>
 *   <li>首页搜索 / 搜索页：结果列表排序</li>
 * </ul>
 *
 * <p><b>★ 权重口径已统一为「播放优先」（用户决策）</b>：
 * 全流程只用 {@link #score(String)} 一套权重 ——
 * <b>播放成功率 0.30 + 起播速度 0.55 + 搜索速度 0.15</b>。
 *
 * <p><b>为什么起播速度拿最高权重</b>：真正决定观看体验的是「点进去多久出画面」，
 * 而该指标（{@code firstFrameTotalMs}）此前<b>一直被采集却从未参与评分</b>；
 * 占着 0.55 的「搜索速度」只反映搜索接口快慢，与能否秒开是两件事。
 * 详见 {@link #score(String)} 与 {@link #FIRST_FRAME_FULL_BONUS_MS}。</p>
 *
 * <p><b>历史问题（已修 P1-2）</b>：此前搜索页走 {@link #searchScore(String)}
 * （命中 0.35 / 速度 0.45 / 播放 0.20），详情页走 {@link #score(String)}，
 * 两套权重口径完全不同 → <b>同一部片，搜索列表排第 1 的源，
 * 进详情页切源可能排第 5</b>，用户感知为「排序不稳定」。
 * 现 {@code searchScore} 已改为 {@link #score(String)} 的薄转发，
 * 搜索页与切源页<b>排序完全一致</b>。
 *
 * <p>存储格式（Hawk，key 前缀见 {@link #KEY_PREFIX}）：
 * {@code hitCnt,failCnt,totalMs,playOkCnt,playFailCnt,firstFrameMsSum}。
 * 早期版本只有前三项，读取时做了兼容（缺失字段按 0 处理）。
 *
 * <p><b>键规模上限（P1-9-b）</b>：统计键不再无界增长 ——
 * 另有 {@link #KEY_INDEX} 维护写入顺序，超出 {@link #MAX_ENTRIES} 个源即淘汰最旧的。
 * 详见 {@link #KEY_INDEX} 与 {@link #touchIndex(String)}。
 */
public class SourceQualityStore {

    private static final String KEY_PREFIX = "fb_stat_";

    /**
     * 写入顺序索引的 Hawk 键（存 {@code List<String>}，元素为 sourceKey 原文）。
     *
     * <p><b>★ P1-9-b：为什么必须限界。</b>此前本类是「只写不删」——
     * 每个 sourceKey 一个 Hawk key（{@link #KEY_PREFIX} + key），
     * <b>没有索引、没有上限、也没有删除接口</b>。而 {@link #recordSearch} 与
     * {@link #recordPlay} 对<b>任何来源</b>的 sourceKey 都会建档
     * （<b>搜索失败也会写</b>：{@code recordSearch(key, hit=false, ...)} 同样落盘）。
     * 于是只要用户的源配置轮换过、或历史搜索命中过的源集合不断扩大，
     * 旧源的 {@code fb_stat_*} 就会<b>永久残留</b>，Hawk 键数只增不减。</p>
     *
     * <p>量级上它比 P1-9-a（{@code AudioTrackMemory}，每集一个键）小 2~3 个数量级 ——
     * 键粒度是「每源」而非「每集」，所以定为 P2。但「只增不减」的性质一样：
     * Hawk 底层是 {@code SharedPreferences}，<b>App 启动时全量载入内存</b>，
     * 无界增长同样会抬高启动耗时与常驻内存。既然本仓库已有验证过的淘汰范式，
     * 就没有理由留这个开口。</p>
     *
     * <p><b>做法</b>：照抄本项目 {@code DetailActivity.trimFallbackCacheIndex}
     * 与 {@code AudioTrackMemory.touchIndex} 已验证的「写入顺序索引 + 上限淘汰」——
     * 每次写入把 sourceKey 挪到索引尾部（最近使用），
     * 超出 {@link #MAX_ENTRIES} 就删最旧的若干个并 {@code Hawk.delete} 其统计键。</p>
     */
    private static final String KEY_INDEX = "fb_stat_index";

    /**
     * 统计键上限（sourceKey 个数）。
     *
     * <p>取值依据：键粒度是<b>每源一个</b>，而非每集。一份源配置通常几十个源，
     * 用户长期换源累积也很难突破 200 —— 取 {@link #MAX_ENTRIES} = 200 既覆盖
     * 「近期用过的所有源」，又把 Hawk 规模钉死在常量级。</p>
     *
     * <p>与 {@code DetailActivity.FALLBACK_CACHE_MAX_ENTRIES = 40}（每部片一条）
     * 的差异是刻意的：片的数量远大于源的数量，源不需要那么紧的上限。</p>
     */
    private static final int MAX_ENTRIES = 200;

    /**
     * 索引里被 {@link #KEY_INDEX} 占用的名字。
     *
     * <p>{@link #KEY_INDEX} 的字面值是 {@code KEY_PREFIX + "index"}，
     * 理论上若某个真实源 key 恰好等于 {@code "index"}，
     * 它的统计键就会与索引键<b>同键</b>（统计值会被覆盖成索引列表，反之亦然）。
     * 实测源 key 来自配置 JSON 的 {@code sites[].key}（如 {@code push_agent}、
     * {@code csp_* } 等短标识），几乎不可能叫 {@code index}；
     * 但这里仍显式挡一道：维护索引时<b>跳过</b>该名字，
     * 宁可这个源不参与淘汰统计，也不能让索引被污染。</p>
     */
    private static final String RESERVED_INDEX_NAME = "index";

    /**
     * 统计读改写的全局锁。
     *
     * <p>锁粒度取全局而非「按 sourceKey 分段」：本类的写操作只在
     * 「一次搜索/一次起播结算」时发生（低频），而每次写只是几次 Hawk 读写。
     * 全局锁的争用开销远小于分段锁的实现复杂度，且彻底消除跨 key 的死锁风险。</p>
     */
    private static final Object STAT_LOCK = new Object();

    // ===== 权重配置（全套统一，播放优先）=====
    // ★ P1-2：此前这些常量只被 score() 使用，searchScore() 另有一套硬编码魔法数字。
    //   现已统一 —— searchScore() 转发到 score()，两处共用下面这组常量。
    //
    // ★ P1-2b（用户决策，2026-10-06）：把「搜索速度」从权重最高的 0.55
    //   降为 0.15，腾出的 0.55 给「起播速度」—— 理由：
    //   搜索接口快 ≠ 点进去能秒开，真正影响观看体验的是「拿到地址到出首帧」的耗时。
    //   而该耗时（firstFrameTotalMs）此前一直被采集却从未参与评分（权重 0）。
    //
    //   新口径：播放成功率 0.30 + 起播速度 0.55 + 搜索速度 0.15。

    /** 搜索响应速度的权重（已从 0.55 降至 0.15：搜索快不代表能秒开）。 */
    private static final double W_SEARCH_SPEED = 0.15;
    /** 起播速度的权重（拿到地址→首帧。直接决定「点进去多久出画面」，给最高权重）。 */
    private static final double W_FIRST_FRAME_SPEED = 0.55;
    /** 播放成功率的权重（能播才是真的好）。 */
    private static final double W_PLAY_OK = 0.30;
    /*
     * ★ P1-2b：原 `W_SEARCH_HIT = 0.15`（搜索命中率权重）已删除。
     *
     * 新口径要把 0.55 给「起播速度」，三档权重被 0.30 + 0.55 + 0.15 占满，
     * 命中率因此退出综合分（权重 0）。留一个恒为 0 的常量只会误导 ——
     * 与本项目 P2-6（`CLUSTER_TOLERANCE_DAYS`）是同一类「死常量」陷阱。
     *
     * 命中率数据仍照常采集（`searchHit/searchFail`），随时可重新启用；
     * 若将来要把它塞回评分，需从其他项匀出权重（三者之和必须为 1）。
     */
    /** 无任何历史数据时的中性分。 */
    private static final double NEUTRAL = 0.5;

    /** 搜索速度奖励上限（毫秒越快越接近该值）。 */
    private static final double SPEED_FULL_BONUS_MS = 400.0;

    /**
     * 起播速度奖励上限（毫秒）。
     *
     * <p><b>取值依据（实测，非拍脑袋）</b>：从 logcat 抓到的真实
     * {@code firstFrame} 样本为 <b>1868 / 2179 / 2221 / 3176 / 4381 / 4696 /
     * 5354 / 5552 / 5703 / 6291 / 6745 ms</b>（n=11，中位数 4381ms）。
     * 故取 <b>2000ms</b> 作为满分线（约等于实测最快值），
     * 让「2 秒内出画面」的源拿满分，6 秒以上的源得分压到 0.3 左右，区分度合理。</p>
     *
     * <p>注意：这与 {@link #SPEED_FULL_BONUS_MS}（400ms，搜索场景）<b>刻意不同</b> ——
     * 起播要建连+拉流+解码，耗时天然比搜索大一个量级，沿用搜索的满分线会让
     * 所有源都挤在低分区、失去区分度。</p>
     */
    private static final double FIRST_FRAME_FULL_BONUS_MS = 2000.0;

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

    /**
     * 落盘一条源档案，并维护写入顺序索引。
     *
     * <p><b>调用约定</b>：必须已持有 {@link #STAT_LOCK}（现仅
     * {@link #recordSearch}/{@link #recordPlay} 在锁内调用）。
     * 索引的「读-改-写」与统计值的写入在同一把锁下，
     * 因此不会出现两个线程各自读到旧索引、互相覆盖导致某个 sourceKey
     * 从索引里消失（那会让它的统计键逃过淘汰、变成孤儿键）。</p>
     */
    private static void write(String sourceKey, Stat stat) {
        String value = stat.searchHit + "," + stat.searchFail + "," + stat.searchTotalMs
                + "," + stat.playOk + "," + stat.playFail + "," + stat.firstFrameTotalMs;
        Hawk.put(KEY_PREFIX + sourceKey, value);
        touchIndex(sourceKey);
    }

    /**
     * 把 {@code sourceKey} 标记为「最近使用」，并淘汰超限的最旧键。
     *
     * <p>索引以 {@code List<String>}（即 {@code Hawk.get(KEY_INDEX)}）存放，
     * 尾部为最新。超出 {@link #MAX_ENTRIES} 时从头部弹出最旧的 sourceKey，
     * 并 {@code Hawk.delete(KEY_PREFIX + oldest)} —— <b>必须删统计键本身</b>，
     * 否则索引缩小了、键还在，等于没做限界（与 {@code AudioTrackMemory}
     * 「淘汰要连子键一起删」是同一个坑）。</p>
     *
     * <p>本方法整体 {@code try/catch}：索引维护属于「附加卫生」，
     * 任何异常都不能影响统计主流程（统计本身才是排序的依据）。</p>
     */
    private static void touchIndex(String sourceKey) {
        try {
            if (TextUtils.isEmpty(sourceKey)) {
                return;
            }
            // 防同键冲突：真实源 key 恰好叫 "index" 时不参与索引维护（见 RESERVED_INDEX_NAME）
            if (RESERVED_INDEX_NAME.equals(sourceKey)) {
                return;
            }
            List<String> index = new ArrayList<>();
            List<String> raw = Hawk.get(KEY_INDEX);
            if (raw != null) {
                for (String s : raw) {
                    // 兼容历史脏数据：Hawk 里可能存的不是 List（类型串用），逐项判空
                    if (s != null && !s.isEmpty() && !index.contains(s)) {
                        index.add(s);
                    }
                }
            }
            index.remove(sourceKey);
            index.add(sourceKey);
            while (index.size() > MAX_ENTRIES) {
                String oldest = index.remove(0);
                Hawk.delete(KEY_PREFIX + oldest);
            }
            Hawk.put(KEY_INDEX, index);
        } catch (Throwable ignored) {
            // 索引维护失败不影响统计读写（核心功能优先）
        }
    }

    // ==================== 评分 ====================

    /**
     * 通用质量分（越高越好）。
     *
     * <p><b>★ P1-2b 新口径（用户决策）：播放成功率 × 0.30 + 起播速度 × 0.55
     * + 搜索速度 × 0.15。</b></p>
     *
     * <p><b>为什么这么改</b>：原口径把<b>搜索速度</b>给了最高权重 0.55，但它衡量的是
     * 「搜索接口响应快不快」，与「点进去多久出画面」是两件事。真正决定观看体验的是
     * <b>起播速度</b>（拿到播放地址 → 首帧），而该指标此前一直被采集
     * （{@code firstFrameTotalMs}）却<b>从未参与评分</b>。本次把它提到最高权重，
     * 搜索速度退居 0.15。</p>
     *
     * <p><b>与「播放优先」的一致性</b>：三档权重全部围绕播放体验 ——
     * 能不能播（0.30）+ 多快能播（0.55），搜索维度仅以 0.15 作为辅助信号。</p>
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
            // ★ P1-2b：起播速度（拿到地址→首帧）作为主速度项。
            double firstFrameScore = firstFrameSpeedScore(stat);
            // 搜索速度退居辅助项。
            double searchSpeed = searchSpeedScore(stat);
            return playRate * W_PLAY_OK
                    + firstFrameScore * W_FIRST_FRAME_SPEED
                    + searchSpeed * W_SEARCH_SPEED;
        } catch (Throwable th) {
            return NEUTRAL;
        }
    }

    /**
     * 搜索场景的质量分。
     *
     * <p><b>★ P1-2：已统一为「播放优先」口径，本方法现在是 {@link #score(String)} 的薄转发。</b>
     *
     * <p>此前它有一套独立的硬编码权重（命中 0.35 / 速度 0.45 / 播放 0.20），
     * 与 {@link #score} 完全不同，导致搜索列表与切源列表对同一部片给出<b>不同排序</b> ——
     * 用户在搜索列表看到的第 1 名，进详情页点切源却排到第 5，感知为「排序不稳定」。
     *
     * <p>经用户决策：<b>全流程播放优先</b>，两者共用 {@link #score} 一套权重。
     * 保留本方法是为了不改动 6 处既有调用点（3 个 Activity），语义上已与 {@code score} 等价。
     *
     * @deprecated 与 {@link #score(String)} 完全等价，新代码请直接调用 {@code score}。
     */
    @Deprecated
    public static double searchScore(String sourceKey) {
        return score(sourceKey);
    }

    // ==================== 批量快照 ====================

    /**
     * 一组源的质量分快照。
     *
     * <p><b>为什么要它</b>：排序比较器会被调用 O(n log n) 次，若在比较器里直接调
     * {@link #score(String)}，每次都会读一遍 Hawk，
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
     * 为一批源建立分数快照。
     *
     * <p>切源选站与搜索排序共用本方法 —— ★ P1-2 起两者权重已统一为「播放优先」，
     * 不再需要按场景区分。</p>
     *
     * @param sourceKeys 参与排序的源 key
     */
    public static Snapshot snapshot(List<String> sourceKeys) {
        return buildSnapshot(sourceKeys);
    }

    /**
     * 为一批源建立分数快照（搜索排序用）。
     *
     * @deprecated ★ P1-2：权重已统一为「播放优先」，本方法现在与
     *             {@link #snapshot(List)} 完全等价。保留仅为不改动既有调用点。
     */
    @Deprecated
    public static Snapshot snapshotForSearch(List<String> sourceKeys) {
        return buildSnapshot(sourceKeys);
    }

    private static Snapshot buildSnapshot(List<String> sourceKeys) {
        HashMap<String, Double> map = new HashMap<>();
        if (sourceKeys != null) {
            for (String key : sourceKeys) {
                if (TextUtils.isEmpty(key) || map.containsKey(key)) {
                    continue;
                }
                map.put(key, score(key));
            }
        }
        return new Snapshot(map);
    }

    /** 平均搜索响应耗时（毫秒），无数据返回 0。 */
    public static long avgSearchMs(String sourceKey) {
        Stat stat = read(sourceKey);
        int total = stat.searchHit + stat.searchFail;
        return total > 0 ? stat.searchTotalMs / total : 0L;
    }

    /** 平均起播耗时（毫秒，拿到地址→首帧），无数据返回 0。 */
    public static long avgFirstFrameMs(String sourceKey) {
        Stat stat = read(sourceKey);
        int total = stat.playOk + stat.playFail;
        return total > 0 ? stat.firstFrameTotalMs / total : 0L;
    }

    /**
     * 起播速度分（越高越好）：拿到播放地址 → 出首帧的耗时。
     *
     * <p>与 {@link #searchSpeedScore} 同构（{@code 满分线 / avgMs} 截断到 [0,1]），
     * 但样本用 {@code firstFrameTotalMs}、满分线用
     * {@link #FIRST_FRAME_FULL_BONUS_MS}（2000ms，非搜索的 400ms）。</p>
     */
    private static double firstFrameSpeedScore(Stat stat) {
        int total = stat.playOk + stat.playFail;
        if (total <= 0 || stat.firstFrameTotalMs <= 0) {
            return NEUTRAL;
        }
        double avgMs = (double) stat.firstFrameTotalMs / total;
        if (avgMs <= 0) {
            return NEUTRAL;
        }
        // 越快越接近 1，越慢越接近 0
        return Math.max(0.0, Math.min(1.0, FIRST_FRAME_FULL_BONUS_MS / avgMs));
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
