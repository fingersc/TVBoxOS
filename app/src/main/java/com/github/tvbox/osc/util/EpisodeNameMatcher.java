package com.github.tvbox.osc.util;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剧集/期数名称匹配工具。
 *
 * <p>解决的问题：同一档综艺在不同采集源里命名不一致，例如
 * "20260809期"（按播出日期）与 "第8期"（按期数）。切源时若只用集数号比对，
 * 日期会被解析成八位数 20260809，与第 N 期的 8 完全不相等，导致匹配失败，
 * 调用方回退到裸下标定位，从而把播放进度迁移到错误的集上。</p>
 *
 * <p>策略（保守、宁缺毋滥）：
 * <ol>
 *   <li>同域精确匹配：两侧都能识别且命名域相同 → 比 ordinal；</li>
 *   <li>跨域按序对齐：一侧是日期、另一侧是第 N 期，且同域精确失败时，
 *       用"旧源下标 → 新源同下标"，并以序号单调递增做校验，避免源顺序不一致时错位；</li>
 *   <li>校验不通过一律返回 -1，交由调用方原有兜底逻辑处理，绝不无依据猜测。</li>
 * </ol>
 * </p>
 */
public final class EpisodeNameMatcher {

    /** 命名域：无法识别。 */
    public static final int DOMAIN_UNKNOWN = -1;
    /** 命名域：期/集/话 序号（第8期、第08集、8话）。 */
    public static final int DOMAIN_ORDINAL = 0;
    /** 命名域：播出日期（20260809、2026-08-09）。 */
    public static final int DOMAIN_DATE = 1;

    /** 日期：连续 8 位 YYYYMMDD，前后不能再接数字（避免截取长数字的一部分）。 */
    private static final Pattern DATE_COMPACT =
            Pattern.compile("(?<!\\d)(20\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(?!\\d)");

    /** 日期：带分隔符 2026-08-09 / 2026.08.09 / 2026_08_09。 */
    private static final Pattern DATE_SEPARATED =
            Pattern.compile("(?<!\\d)(20\\d{2})[-._/](0?[1-9]|1[0-2])[-._/](0?[1-9]|[12]\\d|3[01])(?!\\d)");

    /** 期数/集数：可选的"第"，阿拉伯数字，后接 期/集/话/話。 */
    private static final Pattern ORDINAL_ARABIC =
            Pattern.compile("(?:第\\s*)?(\\d{1,4})\\s*(?:期|集|话|話)");

    /** 期数/集数：中文数字 + 期/集/话（第八期、第十二期）。 */
    private static final Pattern ORDINAL_CHINESE =
            Pattern.compile("(?:第\\s*)?([零一二三四五六七八九十百]{1,4})\\s*(?:期|集|话|話)");

    /**
     * 8 位纯数字（日期语义守卫）：形如 {@code 20260411}，无论有无"第"/"期"包裹。
     *
     * <p><b>为什么需要它</b>：综艺/日更节目普遍用播出日期当集名，写法多达三种——
     * {@code 第20260411期}、{@code 第20260411}、{@code 20260411期}。
     * 它们的数字部分都是 <b>8 位</b>，语义是<b>日期</b>而非期数：</p>
     * <ul>
     *   <li>当真·期数处理会撞上 {@link ORDINAL_ARABIC} 的 {@code \d{1,4}}，
     *       被截成 {@code 0404}/{@code 0411} 这类伪期数，与真实"第4期""第11期"错配；</li>
     *   <li>即便侥幸没截断，8 位数值（约两千万）与真实期数（1~几百）量纲完全不同，
     *       {@code score} 永远比不中，导致切源时匹配失败。</li>
     * </ul>
     *
     * <p><b>与 {@link #DATE_COMPACT} 的分工</b>：{@code DATE_COMPACT} 只认<b>合法</b>日期
     * （月份 01-12、日 01-31）。但站点偶有脏数据（如 {@code 第20261399期}，月份 13 非法），
     * 这种 8 位数<b>仍然不该当期数</b>——把它当"第1399期"同样会错配。
     * 因此本守卫只要"8 位连续数字"，不校验日期合法性，作为兜底拦在期数解析之前。</p>
     *
     * <p>注意：必须带 {@code (?<!\d)…(?!\d)} 边界，避免从更长的数字串中截取。</p>
     */
    private static final Pattern EIGHT_DIGITS =
            Pattern.compile("(?<!\\d)(\\d{8})(?!\\d)");

    /**
     * 分集段（上/中/下），出现在名称<b>末尾</b>。
     *
     * <p>为什么必须在"去括号"之前提取：站点会把分集写在括号里
     * （{@code 20260910（中）}），若先剥括号就会丢失分集信息，导致同一天的
     * 上/中/下三段被当成同一集。</p>
     *
     * <p>允许的形式：{@code 上/中/下}、{@code 上集/中集/下集}、{@code 上部/中部/下部}、
     * {@code 一/二/三}，可选包裹中文或英文括号，可有可无前导"第"。</p>
     */
    private static final Pattern PART_TAIL = Pattern.compile(
            "(?:第\\s*)?[（(]?\\s*(上集|中集|下集|上部|中部|下部|上|中|下|一|二|三)\\s*[）)]?\\s*$");

    /**
     * 非正片标记词：这些词说明该条目是"花絮/回顾"类衍生内容，
     * <b>不是当期正片</b>。用于避免"重温经典2"这类伪期数与"第2期"撞分。
     *
     * <p><b>为什么需要它</b>：{@code 重温经典2} 结尾的 {@code 2} 会被
     * {@link #ORDINAL_ARABIC} 解析成序数 2，于是
     * {@code score("第2期", "重温经典2")} 与 {@code score("第2期", "第2期上")}
     * 同为 80 分，{@link #findIndex} 取先出现的下标 → 错配到"重温经典2"。</p>
     *
     * <p>识别到的条目统一降权 {@link #NON_MAIN_PENALTY}，保证正片（80/100）
     * 永远压过非正片。</p>
     */
    /**
     * 非正片词表（用于 {@link #isNonMainFeature} 判定与"同词"前缀提取）。
     */
    private static final Pattern NON_MAIN_FEATURE = Pattern.compile(
            "重温|回顾|往期|经典|花絮|预告|特辑|幕后|彩蛋|先导片|加更|纯享");

    /** 非正片降权分值：使非正片得分显著低于正片的 80，但又高于 0（保留兜底可匹配性）。 */
    public static final int NON_MAIN_PENALTY = 30;

    /** 分集语义值：无分集（等价于"上"）。 */
    public static final int PART_NONE = 0;
    /** 分集语义值：上（含"一"/"上集"/"上部"）。 */
    public static final int PART_UP = 1;
    /** 分集语义值：中（含"二"/"中集"/"中部"）。 */
    public static final int PART_MIDDLE = 2;
    /** 分集语义值：下（含"三"/"下集"/"下部"）。 */
    public static final int PART_DOWN = 3;

    /**
     * 语义归一：把分集值映射到"实际集序号"。
     *
     * <p><b>核心规则</b>：无分集（{@link #PART_NONE}）在语义上等同于"上"。
     * 因为站点若把一天的节目切成多段，第一段就是"上"；若只有一段，
     * 就不带后缀。因此 {@code 20260910} 与 {@code 20260910上} 是同一集。</p>
     *
     * @param part {@link #extractPart} 的返回值
     * @return 归一后的分集序号（1/2/3）；无法识别时返回 {@link #PART_NONE}
     */
    public static int normalizePart(int part) {
        return part == PART_NONE ? PART_UP : part;
    }

    /**
     * 解析结果。ordinal 为域内可比较的序号，domain 标示命名方式。
     * 日期域下 ordinal 用 YYYYMMDD 原值（保留完整信息，便于同域精确比较与单调校验）。
     *
     * <p>part 为同一日期内的分集段（上/中/下），用于区分
     * {@code 20260910上} / {@code 20260910中} / {@code 20260910下}——
     * 它们日期相同但分集不同，必须分别匹配。</p>
     */
    public static final class EpisodeKey {
        public final int ordinal;
        public final int domain;
        public final int part;
        public final String raw;

        EpisodeKey(int ordinal, int domain, String raw) {
            this(ordinal, domain, PART_NONE, raw);
        }

        EpisodeKey(int ordinal, int domain, int part, String raw) {
            this.ordinal = ordinal;
            this.domain = domain;
            this.part = part;
            this.raw = raw;
        }

        public boolean recognizable() {
            return ordinal >= 0;
        }
    }

    private EpisodeNameMatcher() {
    }

    /**
     * 仅提取分集段（上/中/下），不解析日期/期数。
     *
     * <p>独立成方法的原因：分集提取<b>必须在去括号之前</b>，
     * 而 {@link #parse} 内部会先去掉括号内容，两者顺序不能混。</p>
     *
     * @param name 剧集名
     * @return {@link #PART_NONE} / {@link #PART_UP} / {@link #PART_MIDDLE} / {@link #PART_DOWN}
     */
    public static int extractPart(String name) {
        if (TextUtils.isEmpty(name)) {
            return PART_NONE;
        }
        // 只做清晰度去噪，保留括号（分集可能写在括号里）
        String w = name.toLowerCase(Locale.ROOT)
                .replaceAll("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4", "")
                .trim();
        Matcher m = PART_TAIL.matcher(w);
        if (!m.find()) {
            return PART_NONE;
        }
        return partValueOf(m.group(1));
    }

    /** 把分集字面量映射为语义值。 */
    private static int partValueOf(String s) {
        if (TextUtils.isEmpty(s)) {
            return PART_NONE;
        }
        switch (s) {
            case "上":
            case "一":
            case "上集":
            case "上部":
                return PART_UP;
            case "中":
            case "二":
            case "中集":
            case "中部":
                return PART_MIDDLE;
            case "下":
            case "三":
            case "下集":
            case "下部":
                return PART_DOWN;
            default:
                return PART_NONE;
        }
    }

    /**
     * 解析名称，返回可比较的 EpisodeKey。
     * 解析顺序：日期 → 期数(阿拉伯) → 期数(中文) → 纯序号兜底(≤4位)。
     */
    public static EpisodeKey parse(String name) {
        if (TextUtils.isEmpty(name)) {
            return new EpisodeKey(-1, DOMAIN_UNKNOWN, "");
        }
        String raw = name;
        String work = name;
        // 0. 分集段必须在去括号之前提取（分集可能写作 "20260910（中）"）
        int part = extractPart(work);

        // 1. 先去噪（与旧实现一致：去掉方括号/圆括号内容、清晰度标记），保证召回率不下降
        work = work.replaceAll("\\[.*?\\]|\\(.*?\\)|（.*?）", "");
        work = work.toLowerCase(Locale.ROOT)
                .replaceAll("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4", "");

        // 2. 日期优先（必须在剥离年份之前，否则 8 位日期会被截断误判）
        Matcher compact = DATE_COMPACT.matcher(work);
        if (compact.find()) {
            return new EpisodeKey(buildDateOrdinal(compact.group(1), compact.group(2), compact.group(3)),
                    DOMAIN_DATE, part, raw);
        }
        Matcher separated = DATE_SEPARATED.matcher(work);
        if (separated.find()) {
            return new EpisodeKey(buildDateOrdinal(separated.group(1), separated.group(2), separated.group(3)),
                    DOMAIN_DATE, part, raw);
        }

        // 2.5 8 位纯数字守卫：{@code 第20260411期 / 第20260411 / 20260411期} 一律按日期语义。
        //     即使日期非法（月份/日越界）也不能跌进期数分支——8 位数字当期数必然错配。
        //     ordinal 原样返回该 8 位数，保持"日期域"的数值语义，供跨域换算使用。
        Matcher eight = EIGHT_DIGITS.matcher(work);
        if (eight.find()) {
            int v = parseIntSafe(eight.group(1));
            if (v > 0) {
                return new EpisodeKey(v, DOMAIN_DATE, part, raw);
            }
        }

        // 3. 期数/集数（阿拉伯数字），优先于纯序号兜底
        Matcher arabic = ORDINAL_ARABIC.matcher(work);
        if (arabic.find()) {
            return new EpisodeKey(parseIntSafe(arabic.group(1)), DOMAIN_ORDINAL, part, raw);
        }

        // 4. 期数/集数（中文数字）
        Matcher chinese = ORDINAL_CHINESE.matcher(work);
        if (chinese.find()) {
            int value = chineseToNumber(chinese.group(1));
            if (value >= 0) {
                return new EpisodeKey(value, DOMAIN_ORDINAL, part, raw);
            }
        }

        // 5. 纯序号兜底：仅当去掉非数字后长度 ≤ 4 才采纳，
        //    避免把残留的 20260809 这类长串当成集数号（这是原 bug 的直接来源）
        String number = work.replaceAll("\\D+", "");
        if (!TextUtils.isEmpty(number) && number.length() <= 4) {
            return new EpisodeKey(parseIntSafe(number), DOMAIN_ORDINAL, part, raw);
        }

        return new EpisodeKey(-1, DOMAIN_UNKNOWN, part, raw);
    }

    /**
     * 兼容旧实现的返回值语义：返回可比较的集数序号，无法识别返回 -1。
     * 注意日期域下返回 YYYYMMDD（不再是被截断的年份）。
     */
    public static int extractOrdinal(String name) {
        return parse(name).ordinal;
    }

    /**
     * 单条比对打分。
     * <ul>
     *   <li>100：忽略大小写后完全同名；</li>
     *   <li>80 ：同域且序号相同，<b>且分集语义相同</b>——即确定是同一集：
     *            {@code 第8期} ↔ {@code 第8集}、{@code 20260910} ↔ {@code 2026-09-10}、
     *            {@code 20260910中} ↔ {@code 20260910中集}（写法不同、语义相同）、
     *            {@code 20260910} ↔ {@code 20260910上}（无分集 ≡ 上）；</li>
     *   <li>0  ：序号相同但<b>分集不同</b>（{@code 20260910中} ↔ {@code 20260910上}），
     *            或跨域，或其它；</li>
     *   <li>70/60：两侧都识别不出序号时的包含式模糊匹配，保留旧行为（用于电影多版本等场景）。</li>
     * </ul>
     *
     * <p><b>分集为什么必须参与判分</b>：综艺常把一天的节目切成"上/中/下"三段，
     * 它们的日期完全相同。若只看日期，三段会得到同样的分数，
     * {@link #findIndex} 只能取第一个 —— 切源时会静默切到"上"。</p>
     *
     * <p>这里不对"分集写法是否完全一致"再做细分：{@code 中} 与 {@code 中集}
     * 经 {@link #normalizePart} 后同为 {@code PART_MIDDLE}，已足以确定是同一集，
     * 再区分写法只会引入没有实际收益的复杂度。</p>
     */
    public static int score(String currentName, String targetName) {
        if (TextUtils.isEmpty(currentName) || TextUtils.isEmpty(targetName)) {
            return 0;
        }
        if (targetName.equalsIgnoreCase(currentName)) {
            return 100;
        }
        EpisodeKey cur = parse(currentName);
        EpisodeKey tgt = parse(targetName);
        if (cur.domain != DOMAIN_UNKNOWN && tgt.domain != DOMAIN_UNKNOWN && cur.domain == tgt.domain) {
            if (cur.ordinal != tgt.ordinal) {
                return 0;
            }
            // 序号相同：再比"分集语义"（无分集 ≡ 上）。语义不同即不同集，直接判 0。
            if (normalizePart(cur.part) != normalizePart(tgt.part)) {
                return 0;
            }
            // 非正片（重温/花絮/预告…）不是当期正片：即使序号撞上也要降权，
            // 避免"重温经典2"与"第2期"同分后由 findIndex 的先到先得规则取错。
            if (isNonMainFeature(currentName) || isNonMainFeature(targetName)) {
                return NON_MAIN_PENALTY;
            }
            return 80;
        }
        // 两侧都无法识别序号时，退回包含式模糊匹配（与旧行为一致）
        if (cur.domain == DOMAIN_UNKNOWN && tgt.domain == DOMAIN_UNKNOWN) {
            String currentLower = currentName.toLowerCase(Locale.ROOT);
            String targetLower = targetName.toLowerCase(Locale.ROOT);
            if (currentName.length() >= 2 && targetLower.contains(currentLower)) {
                return 70;
            }
            if (targetName.length() >= 2 && currentLower.contains(targetLower)) {
                return 60;
            }
        }
        // 跨域或一侧可识别一侧不可识别：单条不判定，交由列表级对齐处理
        return 0;
    }

    /**
     * 判断两个集名是否指向<b>同一集</b>（语义判等，跨域安全）。
     *
     * <p><b>为什么不能用 {@code extractOrdinal(a) == extractOrdinal(b)}</b>：
     * 该比较只对<b>同域</b>成立。跨域时两边的 ordinal 量纲完全不同：</p>
     * <pre>
     *   旧源：第2期上     → extractOrdinal = 2
     *   新源：20260411上  → extractOrdinal = 20260411
     *   2 != 20260411  → 被判为"不是同一集"（错！它们其实是同一集）
     * </pre>
     * <p>切源时若用这个判据做"定位是否可信"的守卫，跨域必然误判为不可信，
     * 于是一次都不迁移播放时间——用户看到"集切对了，时间却回到 0"。</p>
     *
     * @param a 集名 A
     * @param b 集名 B
     * @return true 表示两者指向同一集
     */
    public static boolean sameEpisode(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) {
            return false;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        // 同域判等走 score（含分集/非正片口径）
        if (score(a, b) >= 80) {
            return true;
        }
        // 日期域判等：两边都含日期且日期相同 → 同一集（再比分段）
        int da = dateOf(a);
        int db = dateOf(b);
        if (da > 0 && db > 0) {
            return da == db && normalizePart(extractPart(a)) == normalizePart(extractPart(b));
        }
        return false;
    }

    /**
     * 跨域安全的"定位可信"判定：把期望集名与实际集名对齐，<b>允许跨域</b>。
     *
     * <p>切源时的守卫需要回答："新源定位到的这一集，是不是我想去的那一集？"
     * 若是，才把旧源的播放时间迁过来；若不是，宁可不迁移也不能盖错。
     * 原实现用 {@code extractOrdinal} 直接比数值，跨域恒不相等（见
     * {@link #sameEpisode(String, String)} 的说明），导致迁移被整体跳过。</p>
     *
     * <p><b>判定顺序</b>：</p>
     * <ol>
     *   <li>任一为空 → 无法判定，返回 true（保持旧行为：不阻止迁移）；</li>
     *   <li>{@link #sameEpisode(String, String)} 判等成立 → true；</li>
     *   <li>跨域场景（一侧序号域、一侧日期域）→ <b>返回 true</b>：
     *       两者无法用数值直接比较，但跨域对齐本来就靠联网/日期锚完成，
     *       此处不应再阻止迁移；</li>
     *   <li>同域但序号不同 → false（确实定位错了）。</li>
     * </ol>
     *
     * @param expectedName 期望集名（旧源在播集名）
     * @param actualName   实际集名（新源定位到的集名）
     * @return true 表示可以安全迁移时间
     */
    public static boolean positionTrustedAcrossDomain(String expectedName, String actualName) {
        if (TextUtils.isEmpty(expectedName) || TextUtils.isEmpty(actualName)) {
            return true;   // 信息不足，不阻止迁移（与旧行为一致）
        }
        if (sameEpisode(expectedName, actualName)) {
            return true;
        }
        EpisodeKey e = parse(expectedName);
        EpisodeKey a = parse(actualName);
        if (e.domain == DOMAIN_UNKNOWN || a.domain == DOMAIN_UNKNOWN) {
            return true;   // 无法解析，交由其它守卫
        }
        // 跨域：期数 ↔ 日期。数值不可比，但定位本身是经过跨域换算的，
        // 此处必须放行，否则"切对了集却丢了时间"。
        if (e.domain != a.domain) {
            return true;
        }
        // 同域且序号不同 → 确实定位到了别的集，不迁移
        return false;
    }

    /**
     * 判断名称是否属于"非正片"衍生内容（花絮/回顾/预告等）。
     *
     * <p>口径来自实际源站命名：{@code 重温经典N}、{@code 精彩回顾3}、
     * {@code 第2期加更}、{@code 幕后花絮} 等。这类条目虽然可能带数字后缀，
     * 但语义上不等于当期正片，故不参与正片定位。</p>
     *
     * <p><b>注意</b>：本方法只判"是否含标记词"，不判它在名字里的位置。
     * 因为源站写法不统一（{@code 重温经典2} / {@code 精彩回顾} / {@code 加更}），
     * 位置判断会漏。</p>
     *
     * @param name 待判定的条目名
     * @return true 表示该条目是非正片衍生内容
     */
    public static boolean isNonMainFeature(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        return NON_MAIN_FEATURE.matcher(name).find();
    }

    /**
     * 提取非正片条目的"系列词"，用于判断两条非正片是否属于同一系列。
     *
     * <p><b>为什么需要它</b>：用户正在看「重温经典2」时切源，新源若写
     * 「回顾往期2」，两者期数相同但<b>节目板块可能完全不同</b>——
     * 把前者对到后者是危险的猜测。只有当两者共享同一个标记词
     * （都是"重温"或都是"回顾"）才认为是同一系列，可以互配。</p>
     *
     * <p>实现：按词表顺序，取<b>第一个</b>命中的标记词作为系列词。
     * 「重温经典2」→「重温」；「精彩回顾3」→「回顾」；
     * 「第2期加更」→「加更」。返回 null 表示不是非正片。</p>
     *
     * <p><b>为什么取第一个而不是全部</b>：「重温经典」同时含"重温"和"经典"，
     * 视为"重温"系列即可——只要能区分「重温经典N」与「回顾往期N」就够，
     * 再细分没有实际收益。</p>
     *
     * @param name 条目名
     * @return 系列词；不是非正片时返回 null
     */
    public static String nonMainFeatureToken(String name) {
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        Matcher m = NON_MAIN_FEATURE.matcher(name);
        return m.find() ? m.group() : null;
    }

    /**
     * 判断列表是否由<b>日期域</b>主导（日期条目占比 ≥ 序号条目）。
     *
     * <p>用于反向跨域的前置检查：只有目标源主要是日期式写法时，
     * "期数 → 日期"的反查才有意义。</p>
     *
     * @param names 集名列表
     * @return true 表示日期域主导
     */
    public static boolean isDateDominated(List<String> names) {
        if (names == null || names.isEmpty()) {
            return false;
        }
        int dateCount = 0;
        int ordinalCount = 0;
        for (String name : names) {
            EpisodeKey k = parse(name);
            if (k.domain == DOMAIN_DATE) {
                dateCount++;
            } else if (k.domain == DOMAIN_ORDINAL) {
                ordinalCount++;
            }
        }
        return dateCount > 0 && dateCount >= ordinalCount;
    }

    /**
     * 取列表中<b>第一个</b>日期域条目的日期串（YYYYMMDD）。
     *
     * <p>用途：反向跨域扫描需要一个锚点日期。列表首个日期必然属于本季节目，
     * 从它出发前后扫描最容易命中目标期数。</p>
     *
     * @param names 集名列表
     * @return 日期串；没有日期域条目时返回空串
     */
    public static String firstDate(List<String> names) {
        if (names == null) {
            return "";
        }
        // ★ 优先取「正片」日期。
        //   原实现只取首条含日期的条目；但目标源列表常以「回顾特辑 / 先导片」
        //   开头（如量子资源首条 `20260401回顾特辑`）。若拿它做锚点，
        //   反向扫描的步进基准会整体偏移，甚至因星期几错开而彻底查不到。
        //   因此先扫一遍取非特辑的正片日期，取不到时再回退原行为。
        String firstNonFeature = "";
        for (String name : names) {
            EpisodeKey k = parse(name);
            if (k.domain == DOMAIN_DATE && k.ordinal > 0) {
                if (firstNonFeature.isEmpty() && !isNonMainFeature(name)) {
                    firstNonFeature = String.valueOf(k.ordinal);
                }
            }
        }
        if (!firstNonFeature.isEmpty()) {
            return firstNonFeature;
        }
        for (String name : names) {
            EpisodeKey k = parse(name);
            if (k.domain == DOMAIN_DATE && k.ordinal > 0) {
                return String.valueOf(k.ordinal);
            }
        }
        return "";
    }

    /**
     * <b>抽取集名中的 8 位播出日期</b>（YYYYMMDD），没有则返回 -1。
     *
     * <p><b>为什么需要它（而不是用 {@link #parse} 的 domain）</b>：
     * 站点存在 {@code 第20260404期上} 这种<b>"期数式里嵌了日期"</b>的写法。
     * 由于 {@link #parse} 采用<b>日期优先</b>策略，这类条目会被判为
     * {@link #DOMAIN_DATE}，于是 {@link #findIndexByEpisode} 里
     * {@code key.domain != DOMAIN_ORDINAL} 的判据会把它<b>整条跳过</b>：
     * 反向换算好不容易查到日期 {@code D}，却在目标源里<b>永远找不到落点</b>。</p>
     *
     * <p>本方法只看"这条名字里有没有合法日期"，与 domain 判定解耦，
     * 因此 {@code 20260404上}、{@code 第20260404期}、{@code 第20260404期上}
     * 三种写法都能被统一识别为 {@code 20260404}——
     * 这正是跨命名风格落位的基础。</p>
     *
     * @param name 集名
     * @return YYYYMMDD 整数；无日期返回 -1
     */
    public static int dateOf(String name) {
        if (TextUtils.isEmpty(name)) {
            return -1;
        }
        Matcher compact = DATE_COMPACT.matcher(name);
        if (compact.find()) {
            return buildDateOrdinal(compact.group(1), compact.group(2), compact.group(3));
        }
        Matcher separated = DATE_SEPARATED.matcher(name);
        if (separated.find()) {
            return buildDateOrdinal(separated.group(1), separated.group(2), separated.group(3));
        }
        return -1;
    }

    /**
     * 用「目标源自己的日期序列」把日期换算成本源口径的序号，再落位。
     *
     * <p><b>为什么需要它（关键修复）</b>：联网查到的期数来自第三站点
     * （{@code zyshow.net} 的 {@code /dl/{slug}/v/{date}.html} 页面标题），
     * 该站点的「第N期」按<b>自然周</b>编号；而 app 侧源（jisu / 360zy 等）
     * 的「第N期」按<b>播出次数</b>编号。两者量纲不同，直接套用必然错配。</p>
     *
     * <pre>
     *   实测（哈哈哈哈哈第六季）：
     *   日期        播出序号   站点周序号   源内落点
     *   ─────────────────────────────────────────────
     *   20260404       1          1       第1期上
     *   20260405       2          1       第1期下
     *   20260406       3          2       第2期上
     *   20260409       4          2       第3期上
     *   20260411       5          2       第5期上   ← 站点说"第2期"，实为第5期
     * </pre>
     *
     * <p>用户观测到的现象正是这个错配：在 {@code 第20260411期} 切源，
     * 落到 {@code 第2期上}——因为站点把 20260411 归入第 2 个自然周。</p>
     *
     * <p><b>本方法的做法</b>：不理会站点的绝对期数，改为<b>用目标源自身的
     * 日期序列做锚</b>——列出目标源里所有<i>带日期的条目</i>，按日期升序排，
     * 求出 {@code date} 在这条序列里的秩（0 起），再取目标源里第
     * {@code rank} 个<b>序号域</b>条目。这样一来：
     * <ul>
     *   <li>不依赖任何外部服务；</li>
     *   <li>口径由目标源自己定义，天然一致；</li>
     *   <li>目标源混入特辑/回顾时，只有"带日期的正片"参与排名，
     *       非正片不会挤占名次。</li>
     * </ul>
     *
     * @param date        已知播出日期（YYYYMMDD）
     * @param names       目标源集名列表
     * @param currentName 当前在播集名（保持正片/非正片口径），可为 null
     * @return 命中下标；无法建立锚定关系时返回 -1
     */
    public static int findIndexByDateAnchor(String date, List<String> names, String currentName) {
        if (TextUtils.isEmpty(date) || names == null || names.isEmpty()) {
            return -1;
        }
        int target;
        try {
            target = Integer.parseInt(date.trim());
        } catch (Throwable t) {
            return -1;
        }
        // 第一优先：目标源里直接存在该日期的条目 → 直接落位（最可靠）
        int exact = findIndexByDate(date, names, currentName);
        if (exact >= 0) {
            return exact;
        }
        // 否则：按"目标源自己带日期的正片序列"求秩，映射到期数序号
        final boolean curIsNonMain = isNonMainFeature(currentName);
        final String curToken = curIsNonMain ? nonMainFeatureToken(currentName) : null;

        // 收集目标源中所有"带日期的正片"的日期，升序
        List<Integer> dated = new ArrayList<>();
        for (String n : names) {
            if (TextUtils.isEmpty(n)) continue;
            int d = dateOf(n);
            if (d <= 0) continue;
            if (isNonMainFeature(n)) continue;   // 非正片不参与排名
            dated.add(d);
        }
        if (dated.size() < 2) {
            return -1;   // 锚点太少，无法建立可信映射
        }
        java.util.Collections.sort(dated);
        // 去重后求秩
        List<Integer> uniq = new ArrayList<>();
        for (int d : dated) {
            if (uniq.isEmpty() || uniq.get(uniq.size() - 1) != d) uniq.add(d);
        }
        int rank = uniq.indexOf(target);
        if (rank < 0) {
            // date 不在目标源的日期序列里：用"≤date 的条数"近似秩，避免越界
            rank = 0;
            for (int d : uniq) {
                if (d <= target) rank++;
            }
            if (rank <= 0) return -1;
            rank -= 1;
        }
        // 在目标源里取第 rank 个"序号域且为正片"的条目
        int seen = 0;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (TextUtils.isEmpty(n)) continue;
            EpisodeKey key = parse(n);
            if (key.domain != DOMAIN_ORDINAL || key.ordinal <= 0) continue;
            if (isNonMainFeature(n)) continue;    // 正片口径：跳过特辑/回顾
            if (seen == rank) {
                // 口径一致性：当前是非正片时，此处按位置映射到正片是合理的
                // （目标源没有对应非正片时的最佳近似），因此不做额外限制。
                return i;
            }
            seen++;
        }
        // 序号域条目不足 rank+1 条 → 越界，返回最后一条
        if (seen > 0) {
            for (int i = names.size() - 1; i >= 0; i--) {
                String n = names.get(i);
                if (TextUtils.isEmpty(n)) continue;
                EpisodeKey key = parse(n);
                if (key.domain == DOMAIN_ORDINAL && key.ordinal > 0 && !isNonMainFeature(n)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 同日多段对齐：跨天分段的期，按「日期顺序 + 分集后缀」精确落位。
     *
     * <p><b>场景（实测）</b>：一个「期」在日期式源里可能<b>跨越两天</b>——
     * 综艺把一期的上/下两段分两天播出：</p>
     * <pre>
     *   当前源（期数式）        目标源（日期式）
     *   ──────────────────────────────────────────
     *   第2期上          ←→      20260411上
     *   第2期下          ←→      20260412下
     * </pre>
     *
     * <p><b>为什么原有的 {@link #findIndexByDate} 不够</b>：它只接受<b>单个</b>日期，
     * 且分集口径只在该日期内部比较。反查若先扫到 {@code 20260412}，
     * 就只能在 {@code 20260412} 内挑分段，于是"第2期上"落到 {@code 20260412上}（错），
     * 而正确答案是 {@code 20260411上}。</p>
     *
     * <p><b>本方法的做法</b>：接收<b>属于同一期的全部日期</b>（升序），
     * 再按以下优先级落位：</p>
     * <ol>
     *   <li><b>按分段对齐</b>：把当前名的分段（上/中/下）映射到日期序列的对应位置。
     *       一段分两天时 上→第1天、下→第2天；三段时 上→第1天、中→第2天、下→第3天。</li>
     *   <li>若该日期在目标源里没有匹配分段的条目，退而求其次取该日期任意正片条目。</li>
     *   <li>仍无果则按日期顺序返回第一个能在目标源中找到的日期。</li>
     * </ol>
     *
     * @param dates       属于同一期的日期列表（升序，YYYYMMDD）
     * @param names       目标源集名列表
     * @param currentName 当前在播集名（提供分段信息），可为 null
     * @return 命中下标；未命中返回 -1
     */
    public static int findIndexByDates(java.util.List<String> dates, List<String> names, String currentName) {
        if (dates == null || dates.isEmpty() || names == null || names.isEmpty()) {
            return -1;
        }
        // 日期去重升序，保证下标稳定
        java.util.List<String> sorted = new java.util.ArrayList<>();
        for (String d : dates) {
            if (!TextUtils.isEmpty(d) && !sorted.contains(d)) {
                sorted.add(d);
            }
        }
        if (sorted.isEmpty()) {
            return -1;
        }
        java.util.Collections.sort(sorted);

        final int wantPart = normalizePart(extractPart(currentName));
        final boolean curKnown = !TextUtils.isEmpty(currentName);
        final boolean curIsNonMain = curKnown && isNonMainFeature(currentName);
        final String curToken = curIsNonMain ? nonMainFeatureToken(currentName) : null;

        // ① 分段对齐：把分段映射到日期序号
        //    一段(上)→第1天；两段(上/下)→上=第1天、下=第2天；
        //    三段(上/中/下)→依次对应第1/2/3天。
        int daySlot = -1;
        if (curKnown) {
            if (wantPart == PART_UP) {
                daySlot = 0;
            } else if (wantPart == PART_MIDDLE) {
                daySlot = 1;
            } else if (wantPart == PART_DOWN) {
                // 下段：若只有 2 天 → 第2天；若有 3 天及以上 → 最后一天
                daySlot = sorted.size() >= 3 ? sorted.size() - 1 : Math.min(1, sorted.size() - 1);
            }
        }
        if (daySlot >= 0 && daySlot < sorted.size()) {
            int hit = pickInDate(sorted.get(daySlot), names, currentName, wantPart,
                    curKnown, curIsNonMain, curToken);
            if (hit >= 0) {
                return hit;
            }
        }
        // ②★ 分段兜底：期望的那一天在目标列表里不存在时（典型：站点说一期跨两天，
        //   但源侧把这两天压缩成同一天的上/下），不能直接取首个命中。
        //   否则"第N期下"会退化到"第N期上"的位置。
        //   做法：在目标列表里实际存在的日期中，按分段顺序定位（下段取该日期的最后一段）。
        if (curKnown && daySlot > 0) {
            // 按升序依次尝试实际存在的日期，找到最后一个"能落位且分段不是上段"的
            int lastResort = -1;
            for (String d : sorted) {
                // 该日期在目标列表里是否真实存在
                boolean exists = false;
                for (String n : names) {
                    if (!TextUtils.isEmpty(n) && dateOf(n) == parseIntSafe(d)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    continue;
                }
                int hit = pickInDate(d, names, currentName, wantPart,
                        curKnown, curIsNonMain, curToken);
                if (hit >= 0) {
                    return hit;
                }
                // 记下该日期的最后一个合法下标作为最后兜底
                if (lastResort < 0) {
                    lastResort = findIndexByDate(d, names, currentName);
                }
            }
            if (lastResort >= 0) {
                return lastResort;
            }
        }
        // ② 按日期顺序，逐个日期尝试（口径放宽：接受该日期的任意正片）
        for (String d : sorted) {
            int hit = findIndexByDate(d, names, currentName);
            if (hit >= 0) {
                return hit;
            }
        }
        // ③ 最后：忽略口径，只按日期取首个匹配
        for (String d : sorted) {
            int hit = findIndexByDate(d, names, null);
            if (hit >= 0) {
                return hit;
            }
        }
        return -1;
    }

    /**
     * 在<b>指定日期</b>的条目里，按分段口径挑一个下标。
     *
     * @return 下标；无匹配返回 -1
     */
    private static int pickInDate(String date, List<String> names, String currentName, int wantPart,
                                  boolean curKnown, boolean curIsNonMain, String curToken) {
        int target;
        try {
            target = Integer.parseInt(date.trim());
        } catch (Throwable t) {
            return -1;
        }
        int fallback = -1;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (TextUtils.isEmpty(n) || dateOf(n) != target) {
                continue;
            }
            boolean nonMain = isNonMainFeature(n);
            if (curKnown) {
                if (curIsNonMain) {
                    // 当前是非正片：优先同词非正片
                    if (nonMain && curToken != null && curToken.equals(nonMainFeatureToken(n))) {
                        return i;
                    }
                } else {
                    // 当前是正片：跳过一切非正片
                    if (nonMain) {
                        continue;
                    }
                }
            } else if (nonMain) {
                // 口径未知：优先正片
                if (fallback < 0) {
                    fallback = i;
                }
                continue;
            }
            // 分段一致性：无后缀 ≡ 上
            if (normalizePart(extractPart(n)) == wantPart) {
                return i;
            }
            if (fallback < 0) {
                fallback = i;
            }
        }
        return fallback;
    }

    /**
     * 离线"正片秩对齐"：跨域（期数 ↔ 日期）直连失败时的本地兜底（第2-c层）。
     *
     * <p><b>为什么需要它</b>：跨域换算的权威层是直连站点（{@link EpisodeOnlineResolver}），
     * 但站点可能被墙/超时/节目不在 slug 表内。此前直连失败后只能裸用旧源下标兜底，
     * 而两源列表构成不同（目标源常混入特辑/花絮），裸下标会静默切到错误条目——
     * 实测案例：旧源「第5期上」（下标27）切到目标源下标27 的「20260508泳池特辑」，
     * 而正确答案是「20260502上」（第5期）。</p>
     *
     * <p><b>核心观察（真实采集源数据验证）</b>：综艺列表里正片按"期"成簇出现，
     * 簇与簇之间必然隔着非正片条目（加更/特辑/花絮/回顾/预告），或隔着超过
     * {@link #CLUSTER_GAP_DAYS} 天的播出间隔（同期的上/下分段通常相邻 0~2 天）：</p>
     * <pre>
     *   dytt 源（日期式）                  期簇
     *   ────────────────────────────────────────
     *   20260401回顾特辑                  （非正片）
     *   20260404上 / 20260405下           簇0 = 第1期
     *   20260406加更 / 特辑…              （非正片，分隔回）
     *   20260411上 / 20260412下           簇1 = 第2期
     *   …
     *   20260502上 / 20260503下           簇4 = 第5期  ←「第5期上」落这里
     * </pre>
     *
     * <p>因此：<b>期数式列表的第 N 期 ↔ 日期式列表的第 N 个正片簇（0 起秩 N-1）</b>。
     * 簇序是"列表内相对次序"，与日期绝对数值无关——即使源站把年份整体错标
     * （真实案例：把 20260502 写成 20250502），簇序依然正确，按日期值比对反而必败。</p>
     *
     * <p><b>两个方向</b>：</p>
     * <ul>
     *   <li>期数式当前集 → 日期式目标：期号即秩，直接取目标第 (N-1) 个正片簇，
     *       再按分段（上/中/下）选簇内条目；</li>
     *   <li>日期式当前集 → 期数式目标：用旧源列表自身的正片簇求当前集的簇秩
     *       （要求当前条目本身是簇内正片），簇秩 + 1 即期号，复用
     *       {@link #findIndexByEpisode} 落位。</li>
     * </ul>
     *
     * <p><b>宁缺毋滥</b>：当前集是非正片、无法解析、或秩关系建立不起来时一律返回 -1，
     * 交由调用方原有兜底，绝不无依据猜测。</p>
     *
     * @param currentName  当前在播集名（旧源）
     * @param sourceIndex  当前集在旧源列表中的下标
     * @param sourceNames  旧源剧集名列表（必须是旧源的列表，不能传新源已加载后的列表）
     * @param targetNames  新源剧集名列表
     * @return 新源下标；无法可靠对齐返回 -1
     */
    public static int alignByMainFeatureRank(String currentName, int sourceIndex,
                                             List<String> sourceNames, List<String> targetNames) {
        if (TextUtils.isEmpty(currentName) || sourceNames == null || targetNames == null
                || sourceNames.isEmpty() || targetNames.isEmpty()) {
            return -1;
        }
        if (sourceIndex < 0 || sourceIndex >= sourceNames.size()) {
            return -1;
        }
        // 非正片（加更/花絮/特辑…）不参与秩对齐：它们与"期"没有稳定的对应关系
        if (isNonMainFeature(currentName)) {
            return -1;
        }
        EpisodeKey cur = parse(currentName);
        if (cur.domain == DOMAIN_ORDINAL && cur.ordinal > 0) {
            final int rank = cur.ordinal - 1;
            final int wantPart = normalizePart(extractPart(currentName));
            // ① 簇法：期号即秩（第N期 = 目标列表第 N-1 个正片簇）
            List<List<Integer>> clusters = buildMainFeatureClusters(targetNames);
            if (rank < clusters.size()) {
                long expectedDay = expectedDayOf(targetNames, clusters, rank);
                int picked = pickMainFeatureEntry(targetNames, clusters.get(rank), wantPart, expectedDay);
                // 落点日期应贴近"首簇 + 7×rank 天"的周更快照；偏差过大说明簇序被
                // 伪正片（特辑写成裸期数等）污染，不可采信
                boolean trustworthy = picked >= 0;
                if (trustworthy && expectedDay >= 0) {
                    int d = dateOf(targetNames.get(picked));
                    if (d > 0 && Math.abs(dayNumberOf(d) - expectedDay) > CLUSTER_TOLERANCE_DAYS) {
                        trustworthy = false;
                    }
                }
                if (trustworthy) {
                    return picked;
                }
            }
            // ② 外推法：首播日 + 7×(N-1) 天 = 期望播出日，按日期直接落位。
            //    覆盖簇法失效的源（无任何非正片分隔、或簇被污染）。
            return findIndexByExpectedDay(targetNames, cur.ordinal, wantPart);
        }
        if (cur.domain == DOMAIN_DATE && cur.ordinal > 0) {
            final long curDay = dayNumberOf(cur.ordinal);
            final int wantPart = normalizePart(extractPart(currentName));
            // ① 簇法：用旧源列表自身的正片簇求当前集的期簇序。
            // 注意簇序只依赖列表内的相对次序，与日期数值无关，
            // 因此源站年份整体错标（2025… vs 实际 2026…）不影响结果。
            List<List<Integer>> clusters = buildMainFeatureClusters(sourceNames);
            int rank = mainFeatureClusterRank(sourceNames, sourceIndex);
            if (rank >= 0) {
                long expectedDay = expectedDayOf(sourceNames, clusters, rank);
                boolean trustworthy = true;
                if (expectedDay >= 0) {
                    int d = dateOf(sourceNames.get(sourceIndex));
                    if (d > 0 && Math.abs(dayNumberOf(d) - expectedDay) > CLUSTER_TOLERANCE_DAYS) {
                        trustworthy = false;
                    }
                }
                if (trustworthy) {
                    return findIndexByEpisode(currentName, rank + 1, targetNames);
                }
            }
            // ② 外推法：期号 ≈ (当前日期 − 首播日) / 7 周 + 1。
            //    覆盖旧源列表没有非正片分隔导致簇无法切分的情况。
            int firstDate = firstMainDate(sourceNames);
            if (firstDate > 0 && curDay > 0) {
                long diff = curDay - dayNumberOf(firstDate);
                if (diff >= 0) {
                    int episode = (int) Math.round(diff / 7.0) + 1;
                    // 周更快照校验：当前日期应贴近"首播日 + 7×(期号-1) 天"
                    if (Math.abs(diff - 7L * (episode - 1)) <= CLUSTER_TOLERANCE_DAYS) {
                        int byEpisode = findIndexByEpisode(currentName, episode, targetNames);
                        if (byEpisode >= 0) {
                            return byEpisode;
                        }
                    }
                }
            }
            return -1;
        }
        return -1;
    }

    /**
     * 取列表中第一个"带日期的正片"的日期（YYYYMMDD）。
     * 用于"首播日 + 7×(N-1)"外推；视为本季第 1 期的播出日。
     */
    private static int firstMainDate(List<String> names) {
        if (names == null) {
            return -1;
        }
        for (String n : names) {
            if (TextUtils.isEmpty(n) || isNonMainFeature(n)) {
                continue;
            }
            int d = dateOf(n);
            if (d > 0) {
                return d;
            }
        }
        return -1;
    }

    /**
     * 按"期望播出日"落位：首播日 + 7×(N-1) 天为第 N 期的期望播出日，
     * 在目标列表里找日期最接近（±{@link #CLUSTER_TOLERANCE_DAYS} 天）、
     * 同分段、且为正片的条目。
     *
     * <p>为什么需要它：目标源把集名写成"第YYYYMMDD期"式或纯日期式时，
     * 列表内可能没有任何非正片条目可以充当"期"分隔回（如实测 feifan 源，
     * 全列表连成一片，簇法失效），此时只能靠周更快照外推。</p>
     */
    private static int findIndexByExpectedDay(List<String> names, int ordinal, int wantPart) {
        if (names == null || names.isEmpty() || ordinal <= 0) {
            return -1;
        }
        int firstDate = firstMainDate(names);
        if (firstDate <= 0) {
            return -1;
        }
        long expected = dayNumberOf(firstDate) + 7L * (ordinal - 1);
        int best = -1;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (TextUtils.isEmpty(n) || isNonMainFeature(n)) {
                continue;
            }
            if (normalizePart(extractPart(n)) != wantPart) {
                continue;
            }
            int d = dateOf(n);
            if (d <= 0) {
                continue;
            }
            long dist = Math.abs(dayNumberOf(d) - expected);
            if (dist <= CLUSTER_TOLERANCE_DAYS && dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /** 周更快照校验容差（天）：落点/当前集日期与期望播出日的最大偏差。 */
    private static final int CLUSTER_TOLERANCE_DAYS = 3;

    /**
     * 计算第 {@code rank} 个簇的期望播出日（天序数）：首簇日期 + 每周 7 天 × rank。
     * 首簇没有可解析日期时返回 -1（表示无法建立周更快照，调用方跳过校验）。
     */
    private static long expectedDayOf(List<String> names, List<List<Integer>> clusters, int rank) {
        if (clusters == null || clusters.isEmpty()) {
            return -1;
        }
        int c0 = dateOf(names.get(clusters.get(0).get(0)));
        if (c0 <= 0) {
            return -1;
        }
        return dayNumberOf(c0) + 7L * rank;
    }

    /**
     * 把 YYYYMMDD 转成"天序数"（可做日期加减与比较）。
     *
     * <p><b>为什么不能用 YYYYMMDD 整数直接加减</b>：20260404 + 28 = 20260432，
     * 跨月即失真。日期运算必须走日历。</p>
     */
    private static long dayNumberOf(int yyyymmdd) {
        if (yyyymmdd <= 0) {
            return -1;
        }
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.clear();
            c.set(yyyymmdd / 10000, (yyyymmdd % 10000) / 100 - 1, yyyymmdd % 100);
            return c.getTimeInMillis() / 86400000L;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 簇序校验：第 {@code rank} 个簇的起始日期应接近"首簇日期 + 每周 7 天 × rank"（±3 天）。
     *
     * <p><b>为什么需要它</b>：簇切分依赖"非正片条目/日期间隔"这两类分隔回信号。
     * 有的源会把特辑也写成裸期数（如 {@code 第20260410期}，实为接力合唱特辑），
     * 这类伪正片会凭空多出簇、把秩整体推后（实测 360zy 源：第5期会被推到
     * {@code 第20260430期}）。周更综艺的"期起点"几乎严格每周一次，
     * 用首簇日期外推即可识别这类污染：外推偏差超过 ±3 天说明簇序不可信，
     * 宁可放弃（返回 -1 交回旧兜底），也不给出错误落点。</p>
     *
     * <p>首簇或目标簇没有可解析日期（如期数式列表）时跳过校验（放行）——
     * 此时没有更可靠的本地依据。</p>
     */
    /**
     * 在指定正片簇内挑出落点条目。
     *
     * <p>挑选顺序：先按分段口径（上/中/下）筛选；簇内没有该分段时退回整簇；
     * 再在候选里选日期最接近期望播出日（首簇日期 + 7×rank 天）的一条——
     * 这一步能对抗"特辑被写成裸期数"造成的簇污染：污染条目虽混进了簇，
     * 但它的日期偏离期望播出日，会被同簇里日期正确的真正片压过
     * （实测 360zy 源：簇内 [0430伪, 0501伪, 0502上, 0503下]，期望 0502，
     * 正确选中 0502上）。</p>
     *
     * @param names       目标源剧集名列表
     * @param cluster     簇内条目下标（升序）
     * @param wantPart    归一化后的分段
     * @param expectedDay 期望播出日的天序数；不可知时传 -1（退回簇内位置选择）
     * @return 条目下标；簇为空返回 -1
     */
    private static int pickMainFeatureEntry(List<String> names, List<Integer> cluster,
                                            int wantPart, long expectedDay) {
        if (cluster == null || cluster.isEmpty()) {
            return -1;
        }
        List<Integer> matching = new ArrayList<>();
        for (int i : cluster) {
            if (normalizePart(extractPart(names.get(i))) == wantPart) {
                matching.add(i);
            }
        }
        if (matching.isEmpty()) {
            matching = cluster;
        }
        if (expectedDay < 0 || matching.size() == 1) {
            // 分段位置选择：上→第一条，下→最后一条，中→三段及以上取第二条
            if (wantPart == PART_DOWN) {
                return matching.get(matching.size() - 1);
            }
            if (wantPart == PART_MIDDLE && matching.size() >= 3) {
                return matching.get(1);
            }
            return matching.get(0);
        }
        int best = matching.get(0);
        long bestDist = Long.MAX_VALUE;
        for (int i : matching) {
            int d = dateOf(names.get(i));
            long dist = d > 0 ? Math.abs(dayNumberOf(d) - expectedDay) : Long.MAX_VALUE;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /**
     * 正片簇切分阈值（天）：相邻两个正片条目的日期差超过该值即视为新的一期。
     *
     * <p>同期的上/下分段一般相邻 0~2 天（同日或次日播出）；
     * 相邻两期之间至少隔一周（≥5 天）。取 3 天为界，两侧都有足够余量。</p>
     */
    private static final int CLUSTER_GAP_DAYS = 3;

    /**
     * 把剧集列表切分成"正片簇"：每个簇是一段连续同期的正片条目。
     *
     * <p>开新簇的两个条件（满足其一）：</p>
     * <ol>
     *   <li>与上一个正片之间隔着非正片条目（加更/特辑/花絮…）——这是最主要的分隔回信号；</li>
     *   <li>与上一个正片的日期差超过 {@link #CLUSTER_GAP_DAYS} 天——兜底覆盖
     *       "列表里完全没有非正片条目"的源（此时只有日期间隔可用）。</li>
     * </ol>
     *
     * <p>非正片条目与无法解析域名的噪声条目都不进簇。</p>
     *
     * @return 簇列表；每个簇是该簇内条目在原列表中的下标（升序）
     */
    private static List<List<Integer>> buildMainFeatureClusters(List<String> names) {
        List<List<Integer>> clusters = new ArrayList<>();
        if (names == null || names.isEmpty()) {
            return clusters;
        }
        boolean lastWasMain = false;
        int lastMainDate = -1;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (TextUtils.isEmpty(n) || isNonMainFeature(n)) {
                lastWasMain = false;
                continue;
            }
            int d = dateOf(n);
            // 日期差必须走日历天序数：YYYYMMDD 整数相减跨月即失真（0429→0502 差 73）
            boolean gapBoundary = d > 0 && lastMainDate > 0
                    && Math.abs(dayNumberOf(d) - dayNumberOf(lastMainDate)) > CLUSTER_GAP_DAYS;
            boolean boundary = !lastWasMain || gapBoundary;
            if (boundary || clusters.isEmpty()) {
                clusters.add(new ArrayList<Integer>());
            }
            clusters.get(clusters.size() - 1).add(i);
            lastWasMain = true;
            if (d > 0) {
                lastMainDate = d;
            }
        }
        return clusters;
    }

    /**
     * 求旧源列表中某条目所属的"正片簇"秩（0 起）。
     *
     * @param names 旧源剧集名列表
     * @param index 目标条目下标（必须是正片，否则返回 -1）
     * @return 簇秩；条目是非正片/不在任何簇内时返回 -1
     */
    private static int mainFeatureClusterRank(List<String> names, int index) {
        if (names == null || index < 0 || index >= names.size()) {
            return -1;
        }
        if (isNonMainFeature(names.get(index))) {
            return -1;
        }
        List<List<Integer>> clusters = buildMainFeatureClusters(names);
        for (int c = 0; c < clusters.size(); c++) {
            if (clusters.get(c).contains(index)) {
                return c;
            }
        }
        return -1;
    }

    /**
     * 裸下标兜底的"正片保护"：当前集是正片、而兜底下标落在非正片条目
     * （特辑/花絮/加更/回顾…）上时，就近回退到正片条目。
     *
     * <p><b>为什么需要它</b>：所有内容级匹配都失败后，调用方只能用旧源下标兜底。
     * 两源列表构成不同时该下标会指向任意条目——实测切到了「20260508泳池特辑」。
     * 正片切到非正片几乎必然是错的（特辑是衍生内容，不是当期正片）；
     * 而非正片当前集（用户确实在看加更/花絮）落到同性质条目反而合理，故不干预。</p>
     *
     * <p><b>就近方向</b>：优先向前（更早的正片）——加更/花絮/特辑在列表里总是
     * 排在它们所属那期正片的后面，向前找更可能命中同一期的正片。</p>
     *
     * @param currentName   当前在播集名
     * @param fallbackIndex 兜底下标（会被钳位到合法范围）
     * @param names         目标源剧集名列表
     * @return 修正后的下标；无法修正时返回钳位后的原值
     */
    public static int sanitizeMainFeatureFallback(String currentName, int fallbackIndex, List<String> names) {
        if (names == null || names.isEmpty()) {
            return Math.max(0, fallbackIndex);
        }
        int clamped = Math.max(0, Math.min(fallbackIndex, names.size() - 1));
        if (TextUtils.isEmpty(currentName) || isNonMainFeature(currentName)) {
            return clamped;
        }
        String landed = clamped >= 0 && clamped < names.size() ? names.get(clamped) : null;
        if (TextUtils.isEmpty(landed) || !isNonMainFeature(landed)) {
            return clamped;
        }
        // 落点是非正片：向前找最近的正片，找不到再向后
        for (int i = clamped; i >= 0; i--) {
            String n = names.get(i);
            if (!TextUtils.isEmpty(n) && !isNonMainFeature(n)) {
                return i;
            }
        }
        for (int i = clamped + 1; i < names.size(); i++) {
            String n = names.get(i);
            if (!TextUtils.isEmpty(n) && !isNonMainFeature(n)) {
                return i;
            }
        }
        return clamped;
    }

    /**
     * 按日期定位目标列表下标：找首个日期等于 {@code date} 的条目。
     *
     * <p>用于<b>反向跨域</b>的落位：反查得到日期 {@code D} 后，
     * 目标源本身是日期式（没有期数），因此不能再用
     * {@link #findIndexByEpisode} 匹配，必须按日期找。</p>
     * <p>内部委托 {@link #findIndexByDate(String, List, String)}，
     * {@code currentName} 传 {@code null} 表示"口径未知"，
     * 保持旧行为：同日期优先正片，只有非正片时返回该非正片。</p>
     *
     * @param date  YYYYMMDD
     * @param names 目标源集名列表
     * @return 命中下标；未命中返回 -1
     */
    public static int findIndexByDate(String date, List<String> names) {
        return findIndexByDate(date, names, null);
    }

    /**
     * 按日期定位目标列表下标，<b>并保持正片/非正片口径一致</b>。
     *
     * <p><b>为什么必须带口径</b>：各源对"哪一天是正片"的判断并不一致。
     * 例如同一天在 dytt 是 {@code 20260410接力合唱特辑}（非正片），
     * 在 360zy 却是正片 {@code 第20260410期}。
     * 若只按日期落位，用户在看<b>正片</b>时会切到<b>特辑</b>，
     * 表现为"播放记忆丢失"。</p>
     *
     * <p><b>落位规则</b>：</p>
     * <ul>
     *   <li>{@code currentName} 是<b>正片</b>：只接受非正片以外的条目；
     *       该日期只有非正片时返回 <b>-1</b>（交由调用方保守落地，保持原集不切换）；</li>
     *   <li>{@code currentName} 是<b>非正片</b>：优先取<b>同词</b>非正片
     *       （{@code 加更} ↔ {@code 加更}）；找不到才降级到改日期的任意条目；</li>
     *   <li>{@code currentName} 为 null/空（口径未知）：同日期优先正片，
     *       只有非正片时返回首个非正片（兼容旧行为）。</li>
     * </ul>
     * <p>同日期多条（上/中/下）时一律返回<b>第一条</b>，
     * 与"无后缀 ≡ 上"的既有归一一致。</p>
     *
     * @param date        YYYYMMDD
     * @param names       目标源集名列表
     * @param currentName 当前在播集名（用于确定正片/非正片口径），可为 null
     * @return 命中下标；未命中或口径不符返回 -1
     */
    public static int findIndexByDate(String date, List<String> names, String currentName) {
        if (TextUtils.isEmpty(date) || names == null || names.isEmpty()) {
            return -1;
        }
        int target;
        try {
            target = Integer.parseInt(date.trim());
        } catch (Throwable t) {
            return -1;
        }

        final boolean curKnown = !TextUtils.isEmpty(currentName);
        final boolean curIsNonMain = curKnown && isNonMainFeature(currentName);
        final String curToken = curIsNonMain ? nonMainFeatureToken(currentName) : null;

        int nonMainFallback = -1;      // 口径未知时的兜底
        int sameTokenFallback = -1;    // 非正片的"同词"命中
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (TextUtils.isEmpty(name)) {
                continue;
            }
            // 用 dateOf 而非 parse().domain：兼容「第YYYYMMDD期」这类写法
            if (dateOf(name) != target) {
                continue;
            }
            boolean nonMain = isNonMainFeature(name);

            if (!curKnown) {
                // 口径未知：优先正片，退而取首个非正片
                if (!nonMain) {
                    return i;
                }
                if (nonMainFallback < 0) {
                    nonMainFallback = i;
                }
                continue;
            }

            if (!curIsNonMain) {
                // 当前是正片：只认正片
                if (!nonMain) {
                    return i;
                }
                continue;
            }

            // 当前是非正片：优先同词
            if (nonMain && curToken != null && curToken.equals(nonMainFeatureToken(name))) {
                return i;
            }
            if (sameTokenFallback < 0) {
                sameTokenFallback = i;
            }
        }
        if (!curKnown) {
            return nonMainFallback;
        }
        // 非正片：同词优先，找不到才降级到该日期的任意条目
        return curIsNonMain ? sameTokenFallback : -1;
    }

    /**
     * 列表级查找：在当前集与新源列表中定位匹配项。
     *
     * @param currentName 当前正在播放的集名（旧源）
     * @param targetList  新源的剧集列表，元素需为 {@link com.github.tvbox.osc.bean.VodInfo.VodSeries}
     *                    的 name 字符串（调用方自行映射）
     * @return 匹配到的下标；无法可靠匹配时返回 -1
     */
    public static int findIndex(String currentName, List<String> targetNames) {
        if (targetNames == null || targetNames.isEmpty()) {
            return -1;
        }
        if (targetNames.size() == 1) {
            return 0;
        }
        if (TextUtils.isEmpty(currentName)) {
            return -1;
        }
        // 第一轮：同域精确匹配
        int matched = -1;
        int bestScore = 0;
        for (int i = 0; i < targetNames.size(); i++) {
            int s = score(currentName, targetNames.get(i));
            if (s > bestScore) {
                bestScore = s;
                matched = i;
            }
        }
        return matched;
    }

    /**
     * 把「期数」映射到目标列表的下标。
     *
     * <p><b>为什么不能用 {@link #findIndex} 代替</b>：综艺列表里常有
     * 「重温经典1」「回顾往期2」这类非正片条目，它们的尾部数字会被
     * {@link #parse} 当成期数，于是 {@code score("第2期", "重温经典2")}
     * 与 {@code score("第2期", "第2期上")} <b>同为 80 分</b>，
     * {@code findIndex} 取靠前者，就把「第2期」错配到「重温经典2」。</p>
     *
     * <p>实测（线上日志）：跨域联网拿到权威期数 2 后，本该落到 {@code 第2期上}，
     * 却因上述平局落到 {@code 重温经典2}，用户看到"切源后集数完全不相干"。</p>
     *
     * <h3>匹配口径：解析式，而非字符串前缀</h3>
     *
     * <p><b>为什么不能用 {@code startsWith("第N期")}</b>：那是字面量口径，会把
     * 同义不同写法的正片全部漏掉。源站对同一期有多种写法，字面量匹配只认第一种：</p>
     * <pre>
     *   权威期数=2   目标列表              startsWith("第2期")    解析式(本方法)
     *   ────────────────────────────────────────────────────────────────────
     *   第2期上                           命中                  命中
     *   第02期上    （前导零，老站常见）    漏                    命中
     *   第002期上   （3 位零填充）          漏                    命中
     *   02期上      （无"第"字）           漏                    命中
     *   第2集       （用"集"字）           漏                    命中
     *   第二期上    （中文数字）           漏                    命中
     * </pre>
     *
     * <p>漏判的后果不是错配而是返回 <b>-1</b>：调用方会降级到 L3 {@code alignByOrder}
     * 兜底，等于绕过了联网权威数据，退化成旧行为——用户看到的错配可能因此复现。</p>
     *
     * <p>因此本方法复用 {@link #parse}，与 {@link #score} 用<b>同一套</b>解析逻辑：
     * 只要该条目属于序号域（{@link #DOMAIN_ORDINAL}）且 ordinal 等于权威期数，即为候选。
     * 写法差异（前导零 / 中文数字 / 期与集）由 {@code parse} 统一归一。</p>
     *
     * <h3>为什么不会重新踩「重温经典2」的坑（当前是正片时）</h3>
     *
     * <p>关键在于额外要求该条目是<b>正片</b>：用 {@link #isNonMainFeature} 排除
     * 非正片标记词。{@code 重温经典2} 的 ordinal 确实也是 2，会被解析命中，
     * 但它含"重温 / 经典"标记词，在此被过滤掉。
     * 这正是本方法与 {@code score} 的分工：{@code score} 只比数值，
     * 本方法额外加"必须是正片"这道语义约束。</p>
     *
     * <h3>当前正在看的本身就是非正片时</h3>
     *
     * <p>用户可能正在看「重温经典2」这类非正片。此时若仍按"只找正片"的口径，
     * 就会一头扎进正片区，把回顾内容对到当期正片上——同样是错。</p>
     *
     * <p>因此本方法<b>按当前条目的性质分流</b>：</p>
     * <table border="1">
     *   <tr><th>当前名</th><th>匹配口径</th><th>例子</th></tr>
     *   <tr><td>正片（不含标记词）</td><td>只在<b>正片</b>里找同序号</td>
     *       <td>{@code 第2期} → {@code 第02期上}</td></tr>
     *   <tr><td>非正片（含标记词）</td><td>只在<b>同一系列词</b>的非正片里找同序号</td>
     *       <td>{@code 重温经典2} → {@code 重温经典2}；
     *           不会对到 {@code 回顾往期2}（不同系列）</td></tr>
     * </table>
     *
     * <p>"同一系列"由 {@link #nonMainFeatureToken} 判断：共享同一个标记词
     * （都是"重温"或都是"回顾"）才互配。</p>
     *
     * <p><b>为什么不同系列不互配</b>：{@code 重温经典2} 与 {@code 回顾往期2}
     * 期数都是 2 纯属巧合，它们可能是完全不同的节目板块。宁可返回 -1
     * 交给 L3 按位置兜底，也不做这种没有依据的跨板块猜测。</p>
     *
     * <p><b>日期域条目直接跳过</b>：权威期数是序数（第N期），若目标条目是日期名
     * （{@code 20260412下}），其 ordinal 是 YYYYMMDD 这样的一大串数字，两者量纲不同，
     * 数值比较没有意义，必须排除。</p>
     *
     * <p>同一期数有多条（如"第2期上 / 下 / 加更"）时，返回<b>第一条</b>符合口径的条目——
     * 与"无后缀 ≡ 上"的既有归一一致（上段是当天首段）。</p>
     *
     * @param currentName 当前正在播放的集名（旧源）；用于判断走正片还是非正片口径
     * @param episode     期数（1 起）
     * @param targetNames 目标源集名列表
     * @return 命中的下标；目标列表里没有该期数时返回 -1
     */
    public static int findIndexByEpisode(String currentName, int episode, List<String> targetNames) {
        if (episode <= 0 || targetNames == null || targetNames.isEmpty()) {
            return -1;
        }
        // 当前条目是否非正片，决定本次走哪套口径
        final boolean curIsNonMain = isNonMainFeature(currentName);
        final String curToken = nonMainFeatureToken(currentName);
        // ★ 分段口径：当前名带 上/中/下 时，必须挑到同分段的条目。
        //   否则「第2期下」会落到「第2期上」（findIndexByEpisode 早期只取第一条）。
        final boolean curHasPart = extractPart(currentName) != PART_NONE;
        final int wantPart = normalizePart(extractPart(currentName));
        // 分段不一致时的候选（用于兜底：目标源确实没有该分段时，仍给一个近似落点）
        int partMismatchFallback = -1;
        for (int i = 0; i < targetNames.size(); i++) {
            String name = targetNames.get(i);
            if (TextUtils.isEmpty(name)) {
                continue;
            }
            EpisodeKey key = parse(name);
            // 必须是序号域，且序号等于权威期数（日期域量纲不同，排除）
            if (key.domain != DOMAIN_ORDINAL || key.ordinal != episode) {
                continue;
            }
            if (curIsNonMain) {
                // 当前是非正片：只在同一系列词的非正片里找
                // （'重温经典2' 找 '重温经典2' ✅；不找 '回顾往期2' ❌）
                if (curToken == null || !curToken.equals(nonMainFeatureToken(name))) {
                    continue;
                }
            } else {
                // 当前是正片：只在正片里找，跳过一切非正片条目
                if (isNonMainFeature(name)) {
                    continue;
                }
            }
            // 分段一致 → 直接命中
            if (!curHasPart || normalizePart(key.part) == wantPart) {
                return i;
            }
            // 分段不一致 → 记下首个候选作为兜底
            if (partMismatchFallback < 0) {
                partMismatchFallback = i;
            }
        }
        // 目标源没有同分段的条目时，退回首个候选（保持旧行为，避免整体失配）
        return partMismatchFallback;
    }

    /**
     * 兼容重载：不知道当前集名时退化为"只在正片里找"。
     *
     * <p>保留此重载是为了不破坏既有调用方与测试。
     * 新代码应优先使用带 {@code currentName} 的三参版本，
     * 这样用户在看非正片时也能正确匹配。</p>
     *
     * @param episode     期数（1 起）
     * @param targetNames 目标源集名列表
     * @return 命中的下标；未命中返回 -1
     */
    public static int findIndexByEpisode(int episode, List<String> targetNames) {
        return findIndexByEpisode(null, episode, targetNames);
    }

    /**
     * 同日分段对齐：新旧两源把同一天切成相同段数时，按"分集后缀身份"定位。
     *
     * <p><b>为什么 {@link #findIndex} 不够</b>：{@code findIndex} 是纯名字匹配。
     * 站点对同一天的切分写法不一致（一段写后缀、另一段不写）时它会失手：</p>
     * <pre>
     *   旧源: [20260910上, 20260910中, 20260910下]  当前=20260910中
     *   新源: [20260910上, 20260910  , 20260910下]  ← 中间那段没写后缀
     * </pre>
     * <p>{@code score(20260910中, 20260910)} 因分集不同得 0 分，{@code findIndex}
     * 返回 -1（跳过该源）。但新源那段其实就对应旧源的"中"，只是没写后缀。</p>
     *
     * <p><b>对齐算法</b>（前提：两侧同日总集数相等，否则直接放弃）：</p>
     * <ol>
     *   <li><b>两侧当天各只有一条</b> → 直接判定为同一集（当天不可能有第二种可能）。
     *       这一步专治 {@code 第20260405期} ↔ {@code 20260405下} 这类
     *       "无后缀单条" vs "带后缀单条" —— 纯名字匹配会因"无后缀≡上"的归一化误判为不等；</li>
     *   <li><b>旧源单条 → 新源多段</b> → 取新源的<b>"上"段</b>。
     *       旧源一天只出一条，说明它没把这一天拆开，那一条即当天的首段（上）；</li>
     *   <li><b>旧源多段 → 新源单条</b> → 仅当旧源当前段是<b>"上"</b>时命中那一条，
     *       否则放弃（"中/下"在新源里没有落点）；</li>
     *   <li><b>求旧源当前集的"实际后缀"</b>（{@link #resolvePart}）：
     *       有后缀就用它本身；<b>无后缀</b>时用同日其它条目已用的后缀反推；</li>
     *   <li><b>在新源同日组内按序查找</b>：
     *       先找<b>同后缀</b>条目 → 命中即用；
     *       找不到 → 退而选择<b>无后缀</b>条目 → 命中即用；
     *       都没有 → 放弃（返回 -1，由上层跳过该源）。</li>
     * </ol>
     *
     * <p><b>关于"无后缀 ≡ 上"这条归一</b>：它的适用范围是<b>"该源当天只有一条"</b>。
     * 若一条源确实把某天切成多段，其"无后缀"条目才需要用 {@link #resolvePart}
     * 结合同日其它后缀反推（见规则2反推表）。两种情形互不冲突：</p>
     * <ul>
     *   <li>该源当天 <b>=1 条</b> → 无后缀就是"上"（该源没切分，那一条即首段）；</li>
     *   <li>该源当天 <b>≥2 条</b> → 无后缀按同日已用后缀反推（可能是上/中/下）。</li>
     * </ul>
     *
     * <p><b>仅剩一种拒绝情形</b>：两侧段数不等且都 ≥2（如 3 段 vs 2 段）——
     * 后缀身份对不上，拒绝猜测。</p>
     *
     * <p><b>为什么用"后缀身份"而不是"组内位置"</b>：位置对齐需要组内顺序稳定，
     * 但同日可能出现多条语义相同的条目（如新源 {@code [20260910, 20260910上]} ——
     * 无后缀与"上"语义重叠），此时"第 N 位"完全由物理顺序决定，两侧互不相干，
     * 结果会随列表顺序漂移。按后缀身份匹配则与物理顺序无关。</p>
     *
     * <p><b>防护</b>：新源同日组出现 {@code >= 2} 条无后缀时直接放弃 ——
     * 多个"无后缀"候选择一不可，宁可跳过也不猜。</p>
     *
     * @param currentName  当前在播集名（旧源）
     * @param sourceIndex  当前集在旧源列表中的下标
     * @param sourceNames  旧源剧集名列表
     * @param targetNames  新源剧集名列表
     * @return 对齐后的新源下标；不具备对齐条件时返回 -1
     */
    public static int alignByGroupPosition(String currentName, int sourceIndex,
                                           List<String> sourceNames, List<String> targetNames) {
        if (sourceNames == null || targetNames == null
                || sourceNames.isEmpty() || targetNames.isEmpty()) {
            return -1;
        }
        if (sourceIndex < 0 || sourceIndex >= sourceNames.size()) {
            return -1;
        }
        EpisodeKey current = parse(currentName);
        if (current.domain != DOMAIN_DATE) {
            // 同日分段只存在于日期域；期数域（第N期）没有这个语义
            return -1;
        }

        // --- 旧源：与当前集同日的分组 ---
        List<Integer> sourceGroup = sameDayGroup(sourceNames, current.ordinal);
        if (sourceGroup.isEmpty()) {
            return -1;
        }

        // --- 新源：与当前集同日的分组 ---
        List<Integer> targetGroup = sameDayGroup(targetNames, current.ordinal);
        if (targetGroup.isEmpty()) {
            // 新源没有当天的条目 —— 是真的缺集，交给上层跳过
            return -1;
        }

        // 两侧都只有一条：同一天各一条，不存在第二种可能，必为同一集。
        // 这一步必须显式处理，因为纯名字匹配会被"无后缀≡上"的归一化挡住：
        //   旧源 第20260405期（无后缀） vs 新源 20260405下 → score 得 0 → findIndex 返回 -1
        // 但两侧当天都只有一条，语义上就是同一集。
        if (sourceGroup.size() == 1 && targetGroup.size() == 1) {
            return targetGroup.get(0);
        }

        // 旧源当天只有一条（且无后缀，否则上面 1v1 已处理）、新源当天切成多段：
        // 旧源那条覆盖了当天的全部内容，其语义位置就是"上"—— 取新源的"上"段。
        // 依据："无后缀 ≡ 上" 这一归一在"该源当天只有一条"时仍然成立：
        // 一天只出一条，说明该源没有把这一天拆开，那一条即当天的首段（上）。
        if (sourceGroup.size() == 1) {
            for (int i : targetGroup) {
                if (extractPart(targetNames.get(i)) == PART_UP) {
                    return i;
                }
            }
            // 新源多段但没写"上"（如只有 中+下）：无从安放，放弃
            return -1;
        }
        // 旧源多段、新源单条：只有"上"能落到新源那一条上。
        // 旧源"上" 与 新源无后缀 语义相同（无后缀 ≡ 上）；
        // 而"中/下"在新源里没有对应落点（新源没把那天拆开）—— 放弃，交给上层。
        if (targetGroup.size() < 2) {
            int want0 = resolvePart(sourceNames, sourceIndex);
            return want0 == PART_UP ? targetGroup.get(0) : -1;
        }
        if (targetGroup.size() != sourceGroup.size()) {
            // 切分粒度不同（如旧源 3 段、新源 2 段）：后缀身份对不上，拒绝猜测
            return -1;
        }

        // --- 防护：新源同日出现多条无后缀 → 候选择一不可，放弃 ---
        int bareCount = 0;
        for (int i : targetGroup) {
            if (extractPart(targetNames.get(i)) == PART_NONE) {
                bareCount++;
            }
        }
        if (bareCount >= 2) {
            return -1;
        }

        // --- 规则2：求旧源当前集的实际后缀 ---
        int want = resolvePart(sourceNames, sourceIndex);

        // --- 规则1：先找同后缀 ---
        for (int i : targetGroup) {
            if (extractPart(targetNames.get(i)) == want) {
                return i;
            }
        }
        // --- 规则1 后半：找不到同后缀，退而选择无后缀条目 ---
        for (int i : targetGroup) {
            if (extractPart(targetNames.get(i)) == PART_NONE) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 求旧源某条目在其"同日期组"内的<b>实际分集后缀</b>（规则2）。
     *
     * <p><b>为什么需要反推</b>：旧源可能把某一段写成裸日期（不带后缀），
     * 例如 {@code [20260910, 20260910下]}。此时不能笼统假定"无后缀 = 上"——
     * 要结合同日其它条目<b>已经占用</b>的后缀来推断（同一天的分段后缀互不重复）。</p>
     *
     * <p><b>推断规则</b>：</p>
     * <ul>
     *   <li>自身有后缀 → 直接用；</li>
     *   <li>同日共 <b>2</b> 条：另一条是"上" → 本条为<b>下</b>；否则（另一条是中/下）
     *       → 本条为<b>上</b>。现实里两段切分只会是"上/下"，不会出现"上/中"或"中/下"，
     *       因此 2 条场景下无后缀那条必是"上"或"下"两端之一，不会是"中"；</li>
     *   <li>同日 <b>3</b> 条及以上：取已用后缀集合的补集（唯一）；
     *       例如 {@code [无, 中, 下]} → 缺"上" → 本条为"上"。</li>
     * </ul>
     *
     * @param names 旧源剧集名列表
     * @param index 目标条目下标
     * @return 推断出的分集后缀（{@link #PART_UP}/{@link #PART_MIDDLE}/{@link #PART_DOWN}）
     */
    public static int resolvePart(List<String> names, int index) {
        if (names == null || index < 0 || index >= names.size()) {
            return PART_UP;
        }
        int self = extractPart(names.get(index));
        if (self != PART_NONE) {
            return self;
        }
        EpisodeKey key = parse(names.get(index));
        if (key.domain != DOMAIN_DATE) {
            return PART_UP;
        }
        List<Integer> group = sameDayGroup(names, key.ordinal);
        boolean[] used = new boolean[]{false, false, false, false};
        for (int i : group) {
            if (i == index) {
                continue;
            }
            int p = extractPart(names.get(i));
            if (p >= PART_UP && p <= PART_DOWN) {
                used[p] = true;
            }
        }
        if (group.size() == 2) {
            // 两段切分：另一条是"上"→ 本条"下"；否则（中/下）→ 本条"上"
            return used[PART_UP] ? PART_DOWN : PART_UP;
        }
        // 三条及以上：补集唯一
        for (int p = PART_UP; p <= PART_DOWN; p++) {
            if (!used[p]) {
                return p;
            }
        }
        return PART_UP;
    }

    /** 收集与指定日期（ordinal）同日的所有条目下标；不含则返回空列表。 */
    private static List<Integer> sameDayGroup(List<String> names, int ordinal) {
        List<Integer> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        for (int i = 0; i < names.size(); i++) {
            EpisodeKey k = parse(names.get(i));
            if (k.domain == DOMAIN_DATE && k.ordinal == ordinal) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * 跨域按序对齐：当一侧为日期域、另一侧为期数域时，用"旧源下标 → 新源同下标"定位，
     * 并要求新源列表序号严格单调递增（防止源内顺序错乱导致对齐不可信）。
     *
     * @param sourceIndex    当前集在旧源列表中的下标
     * @param sourceNames    旧源剧集名列表
     * @param targetNames    新源剧集名列表
     * @return 对齐后的新源下标；不具备对齐条件时返回 -1
     */
    public static int alignByOrder(int sourceIndex, List<String> sourceNames, List<String> targetNames) {
        return alignByOrder(sourceIndex, sourceNames, targetNames, true);
    }

    /**
     * 按序对齐（可控制是否允许跨域）。
     *
     * <p><b>为什么需要 {@code allowCrossDomain}</b>：跨域（日期 ↔ 期数）时"同下标即同一期"
     * 只在两源列表<b>构成完全一致</b>时才成立。实际采集源常混入预告/花絮/特别篇，
     * 下标会静默错位。因此默认链路在跨域时<b>不</b>采用按序对齐的猜测结果，
     * 而是交给 {@link EpisodeOnlineResolver} 用站点权威数据换算；按序对齐仅保留
     * 作为同域场景（两源命名方式相同）的兜底。</p>
     *
     * <p><b>同域也要做规模校验</b>：即使两侧命名方式相同，"同下标即同一集"仍要求
     * 两源列表<b>可识别的集条目数一致</b>。反例（真实采集源）：</p>
     * <pre>
     *   旧源 feifan 23 条: [第20260404期 … 第20260508期]
     *   新源 dytt   24 条: [20260401回顾特辑, 20260404上 … 20260503下]
     * </pre>
     * <p>新源在<b>开头混入了一条"回顾特辑"</b>，此后每条都比旧源多偏移一格。
     * 此时按序对齐对下标 12 会给出 {@code 20260419下}，而正确答案是
     * {@code 20260418上} —— 静默错一集。因此条目数不等时直接放弃，
     * 让上层走权威解析，而不是给出一个"看起来很合理"的错答案。</p>
     *
     * <p>比的是"可识别条目数"而非列表长度：新源常混入 {@code 下期预告} 这类
     * 无法识别域名的噪声条目，它们本就不参与集数对应，计入长度会造成无谓误判。</p>
     *
     * @param allowCrossDomain false 表示只允许同域对齐（跨域直接返回 -1）
     */
    public static int alignByOrder(int sourceIndex, List<String> sourceNames, List<String> targetNames,
                                  boolean allowCrossDomain) {
        if (sourceNames == null || targetNames == null || sourceNames.isEmpty() || targetNames.isEmpty()) {
            return -1;
        }
        if (sourceIndex < 0 || sourceIndex >= sourceNames.size()) {
            return -1;
        }
        EpisodeKey current = parse(sourceNames.get(sourceIndex));
        EpisodeKey targetProbe = parse(targetNames.get(Math.min(sourceIndex, targetNames.size() - 1)));
        if (current.domain == DOMAIN_UNKNOWN || targetProbe.domain == DOMAIN_UNKNOWN) {
            return -1;
        }
        if (current.domain != targetProbe.domain) {
            // 跨域：按序对齐本身不可能给出可靠答案（两源列表构成未必一致），
            // 只有显式允许时才退回这个猜测。
            if (!allowCrossDomain) {
                return -1;
            }
        }
        // 规模校验：两侧"可识别的集条目数"必须一致，否则"同下标即同一集"的前提不成立。
        // 反例（真实采集源）：旧源 feifan 23 条、新源 dytt 24 条，新源开头多一条
        // "回顾特辑"，此后每条都比旧源偏移一格 —— 按下标对齐会静默错一集。
        //
        // 之所以比"可识别条目数"而不是"列表长度"：新源常混入预告/花絮这类
        // 无法识别域名的噪声条目（"下期预告"→DOMAIN_UNKNOWN），它们本就不参与
        // 集数对应关系，计入长度会造成无谓的误判。
        //
        // 旧源可识别条目为 0（整个列表都无法解析）时不拦——此时没有基准可比，
        // 按下标是唯一可用依据。
        int srcCount = recognizableCount(sourceNames);
        int tgtCount = recognizableCount(targetNames);
        if (srcCount > 0 && srcCount != tgtCount) {
            return -1;
        }
        if (!isMonotonicAscending(targetNames)) {
            // 新源顺序无法确认单调递增，按序对齐不可信
            return -1;
        }
        // 两源期数排列顺序一致时，同下标即为同一期
        return sourceIndex < targetNames.size() ? sourceIndex : -1;
    }

    /** 统计列表中域名可识别（日期域或序号域）的条目数。 */
    private static int recognizableCount(List<String> names) {
        if (names == null) {
            return 0;
        }
        int count = 0;
        for (String name : names) {
            EpisodeKey k = parse(name);
            if (k.domain == DOMAIN_DATE || k.domain == DOMAIN_ORDINAL) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断是否处于"日期 ↔ 期数"跨域场景——即本地无法仅凭命名确定对应关系、
     * 需要外部信息（离线字典或在线查询）才能换算的情况。
     *
     * <p>这是在线查询的**启用闸门**：只有返回 true 时才值得发起网络请求。</p>
     *
     * <p>判定逻辑（两侧域必须都能识别，且互不相同）：
     * <ul>
     *   <li>当前集是日期域，目标列表"多数"是序号域 → 需要换算（日期→期数）</li>
     *   <li>当前集是序号域，目标列表"多数"是日期域 → 需要换算（期数→日期）</li>
     *   <li>同域（都是日期或都是期数）→ 本地匹配能力内，<b>不</b>触发在线查询</li>
     * </ul>
     * </p>
     *
     * @param currentName 当前在播集名
     * @param targetNames 新源剧集名列表
     * @return true 表示处于跨域场景，本地无法确定对应关系
     */
    public static boolean needsCrossDomainResolve(String currentName, List<String> targetNames) {
        EpisodeKey current = parse(currentName);
        if (current.domain == DOMAIN_UNKNOWN) {
            return false;
        }
        if (targetNames == null || targetNames.size() < 2) {
            return false;
        }
        int dateCount = 0;
        int ordinalCount = 0;
        for (String name : targetNames) {
            EpisodeKey k = parse(name);
            if (k.domain == DOMAIN_DATE) {
                dateCount++;
            } else if (k.domain == DOMAIN_ORDINAL) {
                ordinalCount++;
            }
        }
        int recognized = dateCount + ordinalCount;
        if (recognized == 0) {
            return false;
        }
        // 目标列表的"主导域"：占比更高者
        int targetDomain = dateCount >= ordinalCount ? DOMAIN_DATE : DOMAIN_ORDINAL;
        // 仅当两侧域不同，才是真正的跨域场景
        return current.domain != targetDomain;
    }

    /**
     * 校验列表中所有可识别序号的条目是否严格单调递增（忽略无法识别的条目）。 */
    public static boolean isMonotonicAscending(List<String> names) {
        if (names == null || names.isEmpty()) {
            return false;
        }
        int last = -1;
        int recognized = 0;
        for (String name : names) {
            EpisodeKey key = parse(name);
            if (!key.recognizable()) {
                continue;
            }
            if (recognized > 0 && key.ordinal <= last) {
                return false;
            }
            last = key.ordinal;
            recognized++;
        }
        return recognized >= 2;
    }

    /**
     * 综合定位（切源场景的推荐入口）。优先级从高到低：
     *
     * <ol>
     *   <li><b>同日分段对齐</b>（{@link #alignByGroupPosition}）——
     *       仅当"旧源当前集属于同日期多条目组、且新源同日组条数相同且 ≥2"时适用。
     *       这种情况必须最优先，因为同一天的多个段<b>日期完全相同</b>，
     *       纯名字匹配无法区分它们，也无法处理两源后缀写法不一致；</li>
     *   <li>同域精确匹配（{@link #findIndex}）；</li>
     *   <li>跨域按序对齐（{@link #alignByOrder}）。</li>
     * </ol>
     *
     * <p><b>为什么同日分段对齐要排在名字匹配之前</b>：考虑</p>
     * <pre>
     *   旧源: [20260910上, 20260910中, 20260910下]  当前=20260910中
     *   新源: [20260910上, 20260910  , 20260910下]
     * </pre>
     * <p>新源中间那段没写后缀，{@code score(20260910中, 20260910)} 得 0 分，
     * {@code findIndex} 直接返回 -1（跳过该源）—— 但它其实就是"中"段。
     * 同日分段对齐按后缀身份能找到它。因此只要分段对齐给出<b>可信</b>答案
     * （同日组条数一致、≥2、无多条无后缀歧义），就采用它。</p>
     *
     * <p>当分段对齐不可信时（条数不等、新源无当日条目、当前集不在多段组内、
     * 新源同日出现多条无后缀），才回退到名字匹配 —— 此时名字匹配是唯一可靠依据。</p>
     *
     * @param currentName    当前在播集名
     * @param sourceIndex    当前集在旧源列表中的下标
     * @param sourceNames    旧源剧集名列表
     * @param targetNames    新源剧集名列表
     * @return 新源下标；无法可靠匹配返回 -1
     */
    public static int locate(String currentName, int sourceIndex, List<String> sourceNames, List<String> targetNames) {
        if (targetNames == null || targetNames.isEmpty()) {
            return -1;
        }
        if (targetNames.size() == 1) {
            return 0;
        }
        // 1) 同日分段：按后缀身份对齐，优先。典型场景——旧源 [20260910上,中,下]、
        //    新源 [20260910上, 20260910, 20260910下]，两条列表日期完全相同，纯名字无法区分。
        int byPart = alignByGroupPosition(currentName, sourceIndex, sourceNames, targetNames);
        if (byPart >= 0) {
            return byPart;
        }
        // 2) 名字可可靠区分时，用名字匹配
        int exact = findIndex(currentName, targetNames);
        if (exact >= 0) {
            return exact;
        }
        // 3) 跨域（日期 ↔ 期数）兜底
        return alignByOrder(sourceIndex, sourceNames, targetNames);
    }

    /** 把剧集名列表转为纯名称列表，便于调用方复用 locate/alignByOrder。 */
    public static List<String> names(java.util.Collection<VodSeriesName> series) {
        List<String> list = new ArrayList<>();
        if (series == null) {
            return list;
        }
        for (VodSeriesName s : series) {
            list.add(s == null || s.getName() == null ? "" : s.getName());
        }
        return list;
    }

    /** 让调用方（VodSeries 等）无需为转换额外写循环。 */
    public interface VodSeriesName {
        String getName();
    }

    private static int buildDateOrdinal(String y, String m, String d) {
        try {
            int year = Integer.parseInt(y);
            int month = Integer.parseInt(m);
            int day = Integer.parseInt(d);
            return year * 10000 + month * 100 + day;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 简易中文数字转阿拉伯数字，覆盖"一"~"九十九"及"一百"以内常见写法：
     * 八→8、十八→18、二十→20、二十一→21、一百→100。
     */
    static int chineseToNumber(String cn) {
        if (TextUtils.isEmpty(cn)) {
            return -1;
        }
        final String digits = "零一二三四五六七八九";
        int total = 0;   // 已结算部分
        int number = 0;  // 当前数字
        for (int i = 0; i < cn.length(); i++) {
            char c = cn.charAt(i);
            int digit = digits.indexOf(c);
            if (digit >= 0) {
                number = digit;
            } else if (c == '十') {
                total += (number == 0 ? 1 : number) * 10;
                number = 0;
            } else if (c == '百') {
                total += (number == 0 ? 1 : number) * 100;
                number = 0;
            } else {
                return -1;
            }
        }
        total += number;
        return total > 0 ? total : -1;
    }
}
