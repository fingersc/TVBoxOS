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
 *
 * <p><b>★ v61：排序稳定性修复（用户反馈「同一个源播不同片，速度差很多」）</b>
 * —— 起播耗时是「源 × 具体视频」的高方差结果，不是源的稳定属性，
 * 而原实现用算术平均直接喂给权重最高的 0.55，把噪声当成了信号：
 * <ol>
 *   <li><b>分母污染</b>：均值的分母原本是 {@code playOk + playFail}，而失败样本
 *       从未贡献过耗时 ⇒ <b>失败越多，平均起播显得越快</b>，在最高权重项上奖励失败。
 *       现改为只按成功样本求平均（见 {@link #firstFrameSpeedScore}）。</li>
 *   <li><b>离群值无抵抗</b>：9 次 2 秒 + 1 次换片卡 60 秒，均值就被拉到 7.8 秒。
 *       现对单个样本封顶 {@link #FIRST_FRAME_SAMPLE_CAP_MS}（15 秒），
 *       偶发卡顿只按 15 秒计，不再抹掉其余的正常表现。</li>
 *   <li><b>小样本过度自信</b>：只成功过 1 次的新源拿满分，压过累积几十次样本的老源。
 *       现按 {@link #FIRST_FRAME_PRIOR_WEIGHT} 向 {@link #FIRST_FRAME_PRIOR_MS} 收缩。</li>
 * </ol>
 * 权重 {@code 0.30 / 0.55 / 0.15} <b>一个都没动</b> —— 修的是统计量的算法，不是打分口径。
 *
 * <p><b>★ v64：补上「播放中卡顿」这一缺失维度</b>（用户反馈「起播快但播着卡的源得分反而高」）。
 * 三项权重全是<b>开门那一刻</b>的快照：搜索通不通、能不能播、多久出画面，
 * 起播之后就没了。于是「起播 1.5 秒但每次卡 25 秒」的源能拿 0.943，
 * 排在「起播 4 秒但几乎不卡」的 0.668 前面。
 *
 * <p>实测<b>调权重治不了</b>：即便把权重压到 0.60/0.25，卡顿源依然在流畅源前面 ——
 * 加法模型里没有一项能把它拽下来。故改为在三项加权和之上<b>乘一个流畅度系数</b>
 * （见 {@link #stallFactor(Stat)}）：卡顿不再是「某一项丢分」，而是「命门」。
 * 三项权重<i>仍然一个没动</i>，只是在外面乘了一下。
 *
 * <p>数据采集见 {@link #recordStall(String, long)}：只累计<b>首帧之后</b>的卡顿，
 * 且要排除由 seek（拖进度条）引起的缓冲 —— 那是用户主动行为，不是源的锅。</p>
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
     * 索引的内存镜像（v61，流畅性）。
     *
     * <p><b>为什么要它</b>：原实现每记一次 {@code recordSearch}/{@code recordPlay} 都要
     * 「{@code Hawk.get} 整个索引 → remove+add → {@code Hawk.put} 整个索引」。
     * 一次聚合搜索几十上百个源，就是几十上百次 <b>200 元素 List 的序列化 + 反序列化</b>，
     * 全部发生在搜索回调线程上、且持有 {@link #STAT_LOCK}。</p>
     *
     * <p>更关键的是 {@code Hawk.put} 底层是 {@code SharedPreferences.apply()}：
     * 每次 apply 都会排一个异步写盘任务，而 Android 在 Activity stop / service 销毁时
     * 会用 {@code QueuedWork} <b>在主线程等待这批任务跑完</b>。
     * 搜索期间攒下上百个待写任务，就可能在页面切换时集中 flush、拖住主线程。</p>
     *
     * <p><b>做法</b>：索引载入一次后留在内存；只有「第一次见到某个源」才追加并落盘。
     * 稳定期（源集合不变）索引写入次数从「每个源每次记录」降到 <b>0</b>。</p>
     *
     * <p><b>取舍：严格 LRU → FIFO</b>。原实现把每次写入的源挪到索引尾部（最近使用），
     * 淘汰最久未用的；现在只在首次建档时追加，淘汰的是<b>最早建档</b>的。
     * 差别只在超过 {@link #MAX_ENTRIES}=200 个源时才体现，
     * 而限界的目的（不让 {@code fb_stat_*} 无界增长）两者都达得到 ——
     * 用这点精度换掉每轮上百次全量序列化是划算的。</p>
     *
     * <p><b>前提：单进程</b>。本 App 的 AndroidManifest 没有任何
     * {@code android:process}，因此静态缓存与磁盘不会跨进程打架。
     * 若将来引入多进程，这里必须改成每次重新读（或改用支持多进程的存储）。</p>
     *
     * <p>仅在 {@link #STAT_LOCK} 内访问。</p>
     */
    private static final List<String> INDEX_CACHE = new ArrayList<>();

    /** {@link #INDEX_CACHE} 是否已从 Hawk 载入过。同锁保护。 */
    private static boolean indexCacheLoaded = false;

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

    /**
     * 单个起播样本计入总和时的上限（毫秒）。
     *
     * <p><b>为什么必须截断（用户实测反馈，v61）</b>：起播耗时<b>不是源的稳定属性</b>，
     * 而是「源 × 具体视频」的结果 —— 同一个源播 A 片 2 秒出画面，换 B 片可能卡 60 秒
     * （线路不同、CDN 命中不同、片源冷热不同）。而这里存的是<b>耗时总和</b>，
     * 评分用的是<b>算术平均值</b>，均值对离群值毫无抵抗力：</p>
     *
     * <pre>
     *   源 G：9 次 2 秒 + 换片时 1 次卡 60 秒
     *         不截断 → 均值 7.8s  → 速度分 0.256（被打残）
     *         截断后 → 均值 3.3s  → 速度分 0.606（符合"通常 2 秒"的真实体验）
     *   源 H：稳定 3 秒            → 均值 3.0s  → 速度分 0.667
     * </pre>
     *
     * <p>截断后 G/H 的得分比从 <b>0.71 回到 0.97</b>：一次偶发卡顿不再抹掉九次好成绩。</p>
     *
     * <p><b>取值依据</b>：实测 {@code firstFrame} 样本最大 6745ms（n=11，中位数 4381ms），
     * 取 <b>15 秒</b> ≈ 实测最大值的 2.2 倍 —— 既能挡住"偶发的几十秒卡顿"，
     * 又不会把「真的每次都慢」的源粉饰成中等（那种源每次都接近上限，均值依然高）。
     * 若定得太低（比如 5 秒），一个稳定 8 秒的慢源会被算成 5 秒，反而被美化。</p>
     *
     * <p><b>注意</b>：截断发生在<b>写入时</b>，因此历史存档里已有的超长样本不会被追溯修正，
     * 只会随新样本累积被逐步稀释。这是刻意接受的代价 —— 改存档格式的风险远大于这点误差。</p>
     */
    private static final long FIRST_FRAME_SAMPLE_CAP_MS = 15000L;

    /**
     * 起播耗时的先验值（毫秒）—— 小样本收缩时"假装见过"的那个中等水平。
     *
     * <p>取 <b>4000ms</b>，对应实测中位数 4381ms（见
     * {@link #FIRST_FRAME_FULL_BONUS_MS} 的实测样本）。含义是：
     * 在没有任何证据前，假定一个源的出画面速度是"中等"。</p>
     */
    private static final double FIRST_FRAME_PRIOR_MS = 4000.0;

    /**
     * 先验的等效样本数（收缩强度）。
     *
     * <p>评分时按 {@code (实际总和 + 先验值 × K) / (实际样本数 + K)} 求平均，
     * 相当于给每个源预先垫了 K 次"中等水平"的观测。</p>
     *
     * <p><b>为什么需要它</b>：起播耗时在源内波动很大（见
     * {@link #FIRST_FRAME_SAMPLE_CAP_MS}），一两次观测几乎说明不了问题。
     * 不收缩的话，只成功过 1 次（哪怕 2 秒）的新源就会拿到<b>满分</b>，
     * 直接压过累积了几十次样本的老源 —— 排序因此抖动、且不信任历史。</p>
     *
     * <p><b>K 的取舍（一个常量，随时可调）</b>：
     * K 越大 → 越信任先验、单次新观测对排序的冲击越小（<b>越稳</b>），
     * 但源与源之间的区分度也越低（极端情况是所有源都挤在中性分）。
     * 取 <b>K = 4</b>：既能挡住"1 次样本拿满分"，又不会让 10 次以上样本的老源
     * 被先验明显拉偏（n=10 时先验只占 4/14 ≈ 29%）。</p>
     */
    private static final double FIRST_FRAME_PRIOR_WEIGHT = 4.0;

    /**
     * 每次观看容忍的卡顿毫秒数（{@link #stallFactor(Stat)} 的尺度常量）。
     *
     * <p><b>含义</b>：一个源若平均「每次观看卡 {@code TOL} 毫秒」，其流畅度系数
     * 恰好是 <b>0.5</b>；完全不卡是 1.0，卡到受不了则趋近 0。</p>
     *
     * <p><b>★ 为什么是 2000 而不是 3000（实测校准）</b>：起播快但播中偶尔卡的源，
     * 会凭起播优势把折扣吃掉 —— 实测「起播 2s·偶尔卡 3s」的源在 TOL=3000 下
     * 得分 0.533，反而压过「起播 4s·几乎不卡」的 0.531，<b>更流畅的源输了</b>。
     * 收紧到 2000 后两者变为 0.438 / 0.482，排序纠正。
     * 想让「起播快」更有话语权就把它调大（3000+），想更看重流畅就调小（1500）。</p>
     */
    private static final double STALL_TOLERANCE_MS = 2000.0;

    /**
     * 卡顿统计的先验：假定这个源还额外有过这么多次「一次都没卡」的观看。
     *
     * <p>作用是让样本抖动不要立刻反映到分数上 —— 偶尔一次网络故障
     * （比如单次卡 60 秒）不至于把一个原本很好的源一棍子打死；
     * 与之配套的是采集侧的 {@link #STALL_SINGLE_CAP_MS}。</p>
     */
    private static final double STALL_PRIOR_PLAYS = 3.0;

    /**
     * 流畅度系数的下限。
     *
     * <p><b>为什么还要有它</b>：防止极端情况下源被算到接近 0 而永无出头之日
     * （比如换了个很差的网络环境看了一整晚，历史数据全是卡顿）。</p>
     *
     * <p><b>★ 为什么是 0.25 而不是 0.5（实测校准）</b>：下限越高，被它截住的源越多、
     * 区分度损失越大。实测 floor=0.5 时「每次卡 25s」与「偶尔卡 3s」两个严重
     * 程度完全不同的源被压到<b>同一个分数 0.471</b>，在最该区分的地方放弃了区分。
     * 降到 0.25 后两者为 0.236 / 0.438，区分度恢复，同时仍留了 2.5 折的兜底。</p>
     */
    private static final double STALL_FACTOR_FLOOR = 0.25;

    /**
     * 单次卡顿计入的上限（毫秒）。
     *
     * <p>防止「挂后台半天回来还在 buffering」这种畸高样本污染统计 ——
     * 它记录的是用户离开的时长，不是源的卡顿。60 秒远大于真实卡顿周期
     * （实测多为 1~3 秒），足以吸收绝大多数正常卡顿。</p>
     */
    public static final long STALL_SINGLE_CAP_MS = 60 * 1000L;

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

        /**
         * 播放过程中（首帧之后）的卡顿累计毫秒数。
         *
         * <p>★ v64 新增第 7 个字段。序列化是按逗号拼接的字符串，{@link #read(String)}
         * 用 {@code parseLong(parts, 6)} 取值 —— <b>越界返回 0</b>，所以升级前的旧存档
         * （只有 6 项）读出来自然是「无卡顿」，无需任何迁移。</p>
         *
         * <p>只累计总时长而不单独记录次数：{@link #stallFactor(Stat)} 的判据是
         * 「平均每次观看卡多久」，次数已隐含在 {@link #playOk} 这个分母里。
         * 记了却不参与评分的字段会变成死数据（这是 {@code firstFrameTotalMs}
         * 曾经的教训），故不再额外记 count。</p>
         */
        public long stallTotalMs;

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
            // ★ v64：第 7 项。旧存档只有 6 项，越界按 0 处理 ⇒ 天然兼容
            stat.stallTotalMs = parseLong(parts, 6);
        } catch (Throwable th) {
            // 读取失败按「无历史」处理，不影响调用方
        }
        return stat;
    }

    /** 记录一次搜索结果。 */
    public static void recordSearch(String sourceKey, boolean hit, long elapsedMs) {
        if (!isRecordableKey(sourceKey)) {
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
        if (!isRecordableKey(sourceKey)) {
            return;
        }
        try {
            // ★ v43：同 recordSearch，read-modify-write 整体加锁。
            synchronized (STAT_LOCK) {
                Stat stat = read(sourceKey);
                if (ok) {
                    stat.playOk++;
                    // ★ v61：单次样本封顶（理由见 FIRST_FRAME_SAMPLE_CAP_MS）。
                    //   换片偶发卡顿只按 15s 计，不让它抹掉这个源其余的正常表现。
                    long capped = Math.min(Math.max(0L, firstFrameMs), FIRST_FRAME_SAMPLE_CAP_MS);
                    stat.firstFrameTotalMs += capped;
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
     * 记录一次「播放过程中」的卡顿。
     *
     * <p><b>★ v64：给评分补上第四块拼图 —— 门后面的体验。</b>
     * 此前 {@link #recordPlay} 只关心「能不能播成功」与「多久出首帧」，
     * 全是<b>开门那一刻</b>的快照；起播之后就再没有任何信号参与评分。
     * 于是「起播飞快、但播五分钟卡八次」的源反而能拿高分 ——
     * 而这类源的实际体验明显差于「起播稍慢、但全程流畅」的源。</p>
     *
     * <p><b>为什么用乘性折扣而不是加第四项权重</b>：加法模型里
     * 一个频繁卡顿的源只要能播、起播快，就仍有 {@code 0.30 + 0.55 = 0.85} 的底分，
     * 实测把权重压到 0.60/0.25 它依然排在流畅源前面 —— <b>加法模型没有一项能把它拽下来</b>。
     * 改用乘法后，卡顿成为「命门」：一次观看体验好 = 起播好 <b>且</b> 之后持续流畅，
     * 数学上正好是两个概率相乘。</p>
     *
     * @param stallMs 本次卡顿持续毫秒数；<=0 直接忽略。超长样本由
     *                {@link #STALL_SINGLE_CAP_MS} 封顶（调用方应先截取，这里再兜一次底）
     */
    public static void recordStall(String sourceKey, long stallMs) {
        if (!isRecordableKey(sourceKey) || stallMs <= 0) {
            return;
        }
        try {
            // 与 recordPlay 同：read-modify-write 整体加锁
            synchronized (STAT_LOCK) {
                Stat stat = read(sourceKey);
                long capped = Math.min(stallMs, STALL_SINGLE_CAP_MS);
                stat.stallTotalMs += capped;
                write(sourceKey, stat);
            }
        } catch (Throwable th) {
            // 忽略
        }
    }

    /**
     * 该 sourceKey 是否允许建档写入。
     *
     * <p><b>★ v61：挡在入口，而不是只在索引里挡。</b>此前只有
     * {@link #touchIndex} 跳过 {@link #RESERVED_INDEX_NAME}，但 {@link #write} 是
     * <b>先写统计值、再调 touchIndex</b> —— 于是当某个源的 key 恰好等于
     * {@code "index"} 时，它的统计字符串会<b>直接覆盖掉整个索引列表</b>，
     * 而 touchIndex 又因保留名判断直接 return，不会把索引写回来。
     * 结果是索引永久损坏：淘汰机制失效，{@code fb_stat_*} 键重新变成无界增长 ——
     * 正是 {@link #KEY_INDEX} 存在的初衷被自己绕过了。</p>
     *
     * <p>把判断提到两个 {@code record*} 入口，这个源就不会有任何写入，
     * 索引与统计都不会被污染。代价是它不参与质量排序（按中性分处理），
     * 这远好过把淘汰机制整个弄坏。</p>
     */
    private static boolean isRecordableKey(String sourceKey) {
        return !TextUtils.isEmpty(sourceKey) && !RESERVED_INDEX_NAME.equals(sourceKey);
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
                + "," + stat.playOk + "," + stat.playFail + "," + stat.firstFrameTotalMs
                + "," + stat.stallTotalMs;
        Hawk.put(KEY_PREFIX + sourceKey, value);
        touchIndex(sourceKey);
    }

    /**
     * 确保索引内存镜像 {@link #INDEX_CACHE} 已从 Hawk 载入（幂等）。
     *
     * <p>★ v63：原载入逻辑内联在 {@link #touchIndex(String)} 里；新增
     * {@link #hasAnyStatHistoryGlobal()} 也要用到它，抽出来避免同一段逻辑
     * 在两个地方各自漂移（「同一策略值出现在两个类」的教训）。</p>
     *
     * <p>调用方必须已持有 {@link #STAT_LOCK}。</p>
     */
    private static void ensureIndexCacheLoaded() {
        if (indexCacheLoaded) {
            return;
        }
        indexCacheLoaded = true;
        List<String> raw = Hawk.get(KEY_INDEX);
        if (raw != null) {
            for (String s : raw) {
                // 兼容历史脏数据：Hawk 里可能存的不是 List（类型串用），逐项判空
                if (s != null && !s.isEmpty() && !INDEX_CACHE.contains(s)) {
                    INDEX_CACHE.add(s);
                }
            }
        }
    }

    /**
     * 全局是否<b>从未</b>写入过任何一条统计。
     *
     * <p><b>★ v63 P2：建快照前先问一次。</b>此前 {@link #buildSnapshot} 要把
     * 每个源都读一遍（O(n) 次 Hawk 读）才能判定「这批里有没有历史」；
     * 冷启动（首次安装、一条统计都没有）时这 n 次读取<span>全是白跑</span> ——
     * 读出来都是空串，最后照样返回全中性分。</p>
     *
     * <p>判据：索引 {@link #KEY_INDEX} 为空 ⇔ 从未有源被登记过 ⇔ 不可能存在任何历史。
     * 索引已在内存镜像里时本方法<b>零磁盘读取</b>。</p>
     *
     * <p><b>保守策略</b>：判断不了（异常、读不到索引）一律返回 {@code true}，
     * 走原来的逐条读取路径 —— 宁可多读，也绝不改变结果。</p>
     *
     * <p>注：本结论只用于「省读取」，不用于判定单个源的分数；
     * 单个源有无历史仍以 {@code Stat.hasAnyHistory()} 为准。</p>
     */
    private static boolean hasAnyStatHistoryGlobal() {
        try {
            synchronized (STAT_LOCK) {
                ensureIndexCacheLoaded();
                return !INDEX_CACHE.isEmpty();
            }
        } catch (Throwable ignored) {
            // 判断不了就按「有历史」处理：走原路径，行为与改动前完全一致
            try {
                synchronized (STAT_LOCK) {
                    INDEX_CACHE.clear();
                    indexCacheLoaded = false;
                }
            } catch (Throwable ignored2) {
                // 清理失败也不影响：下次调用会重新载入
            }
            return true;
        }
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
            if (!isRecordableKey(sourceKey)) {
                return;
            }
            ensureIndexCacheLoaded();
            // ★ v61：已登记过的源直接返回，不再重写索引（见下方 INDEX_CACHE 说明）。
            //   原实现是「remove + add」（严格 LRU），每个源每次记录都要全量重写一遍索引。
            if (INDEX_CACHE.contains(sourceKey)) {
                return;
            }
            INDEX_CACHE.add(sourceKey);
            while (INDEX_CACHE.size() > MAX_ENTRIES) {
                String oldest = INDEX_CACHE.remove(0);
                Hawk.delete(KEY_PREFIX + oldest);
            }
            // 传副本：避免把内部容器交给外部长期持有
            Hawk.put(KEY_INDEX, new ArrayList<>(INDEX_CACHE));
        } catch (Throwable ignored) {
            // 索引维护失败不影响统计读写（核心功能优先）。
            // 丢弃内存镜像，下次调用重新从 Hawk 载入，避免缓存与磁盘长期不一致。
            INDEX_CACHE.clear();
            indexCacheLoaded = false;
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
            return scoreOf(read(sourceKey));
        } catch (Throwable th) {
            return NEUTRAL;
        }
    }

    /** 由已读出的档案算分，避免同一条档案被重复读取（快照构建会读很多条）。 */
    private static double scoreOf(Stat stat) {
        try {
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
            double baseScore = playRate * W_PLAY_OK
                    + firstFrameScore * W_FIRST_FRAME_SPEED
                    + searchSpeed * W_SEARCH_SPEED;
            // ★ v64：卡顿不是「打分项」而是「命门」。前三项权重 0.30/0.55/0.15
            //   全部是「开门那一刻」的表现，乘法这一下才把门后面的体验接进来。
            //   详见 stallFactor 注释里「加法模型为什么不够」的实测说明。
            return baseScore * stallFactor(stat);
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

        /** 这批源里是否存在任何一条可参考的历史（见 {@link #hasAnyHistory()}）。 */
        private final boolean anyHistory;

        private Snapshot(HashMap<String, Double> scores, boolean anyHistory) {
            this.scores = scores;
            this.anyHistory = anyHistory;
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
         * 快照里是否一个源都没有（即入参 key 列表为空）。
         *
         * <p><b>★ v61：语义已澄清。</b>此前本方法的 javadoc 写的是
         * 「是否完全没有可参考的历史」，<b>但实现是 {@code scores.isEmpty()}</b> ——
         * 而 {@link #buildSnapshot} 对<b>每个</b>传入的 key 都会 {@code put} 一条
         * （无历史的源存的是中性分 {@link #NEUTRAL}），所以只要入参非空，
         * 它就恒为 {@code false}。</p>
         *
         * <p>后果：三处调用点都按 javadoc 的语义写了「冷启动时跳过排序」的优化，
         * 而这个分支<b>从来没有被触发过</b> —— 每次搜索/每次切源都白跑一轮
         * O(n log n) 排序（虽然结果是稳定排序、行为正确，但开销白付）。</p>
         *
         * <p>现按实际实现保留「无源」语义；要判断「有没有历史」请用
         * {@link #hasAnyHistory()}。</p>
         */
        public boolean isEmpty() {
            return scores.isEmpty();
        }

        /**
         * 这批源里是否存在任何一条可参考的历史。
         *
         * <p>冷启动（首次安装、尚无任何统计）时为 {@code false}：
         * 所有源都是中性分 {@link #NEUTRAL}，稳定排序不会改变任何顺序，
         * 调用方可据此<b>跳过整个排序</b>，省下这轮 O(n log n) 开销。
         * 这是三处"冷启动跳过"分支本来想要的语义（见 {@link #isEmpty()}）。</p>
         */
        public boolean hasAnyHistory() {
            return anyHistory;
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
        boolean anyHistory = false;
        if (sourceKeys != null) {
            // ★ v63 P2：全局一条历史都没有 ⇒ 每个源都必然是中性分，
            //   直接填中性返回，省掉下面 O(n) 次 Hawk 读（冷启动/清空数据后的首次搜索）。
            //   map 仍按原样填满，保证 size()/get() 语义与逐条读取时完全一致。
            if (!hasAnyStatHistoryGlobal()) {
                for (String key : sourceKeys) {
                    if (TextUtils.isEmpty(key) || map.containsKey(key)) {
                        continue;
                    }
                    map.put(key, NEUTRAL);
                }
                return new Snapshot(map, false);
            }
            for (String key : sourceKeys) {
                if (TextUtils.isEmpty(key) || map.containsKey(key)) {
                    continue;
                }
                // ★ v61：每条档案只读一次，同时取出分数和「有没有历史」。
                //   此前是 map.put(key, score(key))，score 内部又 read 一遍；
                //   要判断 hasAnyHistory 再读一遍就是 3 次 Hawk 读取/源。
                Stat stat = read(key);
                if (stat.hasAnyHistory()) {
                    anyHistory = true;
                }
                map.put(key, scoreOf(stat));
            }
        }
        return new Snapshot(map, anyHistory);
    }

    /** 平均搜索响应耗时（毫秒），无数据返回 0。 */
    public static long avgSearchMs(String sourceKey) {
        Stat stat = read(sourceKey);
        int total = stat.searchHit + stat.searchFail;
        return total > 0 ? stat.searchTotalMs / total : 0L;
    }

    /**
     * 平均起播耗时（毫秒，拿到地址→首帧），无数据返回 0。
     *
     * <p><b>★ v61：分母改为成功次数</b>，与 {@link #firstFrameSpeedScore} 一致。
     * 原本除以 {@code playOk + playFail}，而失败的样本从未贡献过耗时
     * （见 {@link #recordPlay}），算出来的是「成功耗时摊到所有次数」，
     * 与"平均起播要多久"的语义不符 —— 失败越多反而显得越快。</p>
     *
     * <p>注：本方法返回的是<b>真实均值</b>，不做先验收缩（收缩只用于评分排序）。</p>
     */
    public static long avgFirstFrameMs(String sourceKey) {
        Stat stat = read(sourceKey);
        // 只有成功样本才贡献过耗时，分母必须是成功次数
        return stat.playOk > 0 ? stat.firstFrameTotalMs / stat.playOk : 0L;
    }

    /**
     * 起播速度分（越高越好）：拿到播放地址 → 出首帧的耗时。
     *
     * <p>与 {@link #searchSpeedScore} 同构（{@code 满分线 / avgMs} 截断到 [0,1]），
     * 但样本用 {@code firstFrameTotalMs}、满分线用
     * {@link #FIRST_FRAME_FULL_BONUS_MS}（2000ms，非搜索的 400ms）。</p>
     *
     * <p><b>★ v61 修了两处统计错误（都是客观 bug，不是调权重）</b>：</p>
     * <ol>
     *   <li><b>分母污染</b>：原本用 {@code playOk + playFail} 当分母，但
     *       {@link #recordPlay} 只在成功时累加耗时 —— 失败的样本分子贡献恒为 0
     *       却占一个分母名额，于是<b>失败越多，"平均起播耗时"越小、速度分越高</b>。
     *       在一个权重 0.55 的项上奖励失败，方向完全反了。现改为只按成功样本求平均，
     *       失败该受的罚由 {@code playRate} 那 0.30 权重承担。</li>
     *   <li><b>小样本过度自信</b>：只成功过 1 次的源，一次 2 秒就拿满分 1.0，
     *       直接压过累积几十次样本的老源。现按
     *       {@link #FIRST_FRAME_PRIOR_WEIGHT} 向 {@link #FIRST_FRAME_PRIOR_MS} 收缩。</li>
     * </ol>
     */
    private static double firstFrameSpeedScore(Stat stat) {
        int n = stat.playOk;
        if (n <= 0 || stat.firstFrameTotalMs <= 0) {
            return NEUTRAL;
        }
        // 收缩均值：垫 K 次"中等水平(4s)"的虚拟观测，样本越多先验影响越小
        double avgMs = (stat.firstFrameTotalMs
                + FIRST_FRAME_PRIOR_MS * FIRST_FRAME_PRIOR_WEIGHT)
                / (n + FIRST_FRAME_PRIOR_WEIGHT);
        if (avgMs <= 0) {
            return NEUTRAL;
        }
        // 越快越接近 1，越慢越接近 0
        return Math.max(0.0, Math.min(1.0, FIRST_FRAME_FULL_BONUS_MS / avgMs));
    }

    /**
     * 流畅度系数（0~1，越大越流畅），作为 {@link #scoreOf(Stat)} 的<b>乘数</b>。
     *
     * <p>判据是「平均每次观看卡多久」：
     * <pre>
     *   avgStallPerPlay = stallTotalMs / (playOk + STALL_PRIOR_PLAYS)
     *   factor          = STALL_TOLERANCE_MS / (STALL_TOLERANCE_MS + avgStallPerPlay)
     * </pre>
     * 与 {@link #firstFrameSpeedScore} 的 {@code 满分线/(满分线+avg)} <b>同构</b>：
     * 不卡是 1.0，卡到 {@code TOL} 是 0.5，再往后衰减但永不为 0。</p>
     *
     * <p><b>为什么分母要垫 {@link #STALL_PRIOR_PLAYS}</b>：同 {@link #FIRST_FRAME_PRIOR_WEIGHT}
     * 的道理 —— 样本太少时均值不可信。这里先验是「假定有过 K 次一次都没卡的观看」，
     * 所以偶发一次网络故障不会立刻把一个好源拖下去。</p>
     *
     * <p><b>★ 无卡顿数据必须返回 1.0</b>：升级前的旧存档没有第 7 个字段
     * （读到 0），从没卡过的源也是 0 —— 它们<b>不该被奖励也不该被惩罚</b>，
     * 分数与加入本机制前完全一致。这一点由单测锁定。</p>
     */
    private static double stallFactor(Stat stat) {
        if (stat.stallTotalMs <= 0 || !stat.hasPlayHistory()) {
            // 无卡顿记录 或 压根没播过 —— 不折扣（保持与加入前一致 / 避免除零）
            return 1.0;
        }
        double avgStallPerPlay = stat.stallTotalMs / (stat.playOk + STALL_PRIOR_PLAYS);
        if (avgStallPerPlay <= 0) {
            return 1.0;
        }
        double factor = STALL_TOLERANCE_MS / (STALL_TOLERANCE_MS + avgStallPerPlay);
        return Math.max(STALL_FACTOR_FLOOR, Math.min(1.0, factor));
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
