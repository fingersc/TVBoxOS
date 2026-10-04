package com.github.tvbox.osc.util;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
     * 分段紧跟「第N期」时优先取用 —— 覆盖"带副标题"的正片。
     *
     * <p>真实 case（《脱口秀大会》360资源源）：
     * {@code 第10期下：冠军诞生！大张伟王勉再合作}。
     * {@link #PART_TAIL} 锚在字符串<b>末尾</b>，这类条目末尾是副标题 → 抓不到分段，
     * 于是「第10期下」与「第10期上」在 score 里<b>同为 80 分</b>，
     * 纯靠下标顺序决胜 → <b>静默切到"上"</b>。实测 42 个带副标题条目里 20 个分段识别错误。</p>
     *
     * <p>负向前瞻 {@code (?![0-9A-Za-z\u4e00-\u9fff])} 保证不会把「第10期上下」这类误切。</p>
     */
    private static final Pattern PART_AFTER_ORD = Pattern.compile(
            "第\\s*\\d+\\s*期?\\s*[（(]?\\s*"
                    + "(上集|中集|下集|上部|中部|下部|上|中|下|一|二|三)\\s*[）)]?"
                    + "(?![0-9A-Za-z\\u4e00-\\u9fff])");

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
    /**
     * 非正片标记词表。
     *
     * <p><b>全部按「词根」匹配，允许前后有其它字</b>（间隔匹配）：
     * {@code 第20260814期一公观演区}、{@code 20260821一公观演区上}、{@code 观演区}
     * 都能命中 {@code 观演区}。词根化后可覆盖「舞台纯享 / 初舞台观演区 / 二公观演区」
     * 等各种包裹形式，不必逐个枚举组合（组合形式实测有 48 种）。</p>
     *
     * <p><b>入表判据</b>：该词在<b>全部 10 个真实源</b>（歌手2026 四源、披荆斩棘四源、
     * 我家那闺女两源）里都只出现在衍生条目中，从未出现在正片上。
     * 用户 2026-10 确认「观演区 / 预热直播 / 直拍机位 / 特别企划」四词均为衍生；
     * 「初舞台」是中性词（可能是正片板块名）故<b>不入表</b>，
     * 但「初舞台观演区」由「观演区」命中。</p>
     */
    private static final Pattern NON_MAIN_FEATURE = Pattern.compile(
            "重温|回顾|往期|经典|花絮|预告|特辑|幕后|彩蛋|先导片|加更|纯享"
            // —— 综艺常见衍生栏目（用户 2026-10 确认）——
            + "|观演区|预热直播|直拍机位|特别企划"
            // —— 实测在所有真实源里均为纯衍生的组合词根 ——
            + "|舞台纯享|毕业特辑|端午特辑|纯享典藏");

    /**
     * 期号必须出现在名字<b>开头</b>（允许前置"第x季"），才算"期数式正片"。
     *
     * <p><b>为什么需要它（真实 case）</b>：综艺源里衍生内容的命名常把期号写在<b>尾部</b>——
     * {@code 超前企划第1期}、{@code 超前企划第2期}、{@code 预热直播第2期}、{@code 加更版第1期}。
     * 它们会被 {@link #ORDINAL_ARABIC} 解析成"第2期"，与真正的 {@code 第2期一公挑战赛（上）}
     * 撞号；靠关键词黑名单永远列不全，而"期号是否在开头"是稳定的结构特征。</p>
     *
     * <p>实测（披荆斩棘2026，红牛源）：用户在看 {@code 20260815}（= 第1期：初舞台（上）），
     * 秩对齐算出期号 1，却在目标列表里命中了排在更前面的 {@code 超前企划第2期}
     * ——因为簇序偏了 1（源侧衍生条目未被识别），加上"第2期"被尾巴命中。</p>
     */
    /** 季号前缀（"第三季"、"第2季"），判定"期号领衔"时先剥离它。 */
    private static final Pattern SEASON_PREFIX =
            Pattern.compile("^第?\\s*[0-9一二三四五六七八九十]{1,4}\\s*季\\s*$");

    /** 是否含中日韩汉字（用于识别"期号前压着内容词"的衍生条目）。 */
    private static final Pattern HAS_CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** 结构化正片判定用：剥离日期/分段/标点后应无残余字符，否则说明名字里带了内容词（衍生条目）。 */
    private static final Pattern MAIN_RESIDUE_JUNK = Pattern.compile(
            "[第期集话\\s:：、,，.·\\-—_（）()\\[\\]【】“”\"'’]+");

    /**
     * 方括号/圆括号（含全角）包裹的附属内容，如 {@code [HD]}、{@code (国语)}、{@code （上）}。
     * <p>提为常量：该剥离动作在 {@code parse / stripMainResidue} 等热路径里出现多次，
     * 原先每次都用 {@code String.replaceAll(字面量)}，等于每次调用重新编译一次正则。</p>
     */
    private static final Pattern BRACKET_CONTENT = Pattern.compile("\\[.*?\\]|\\(.*?\\)|（.*?）");

    /** 画质 / 编码噪声词，与集名语义无关。同样提为常量避免重复编译。 */
    private static final Pattern QUALITY_JUNK = Pattern.compile("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4");

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
        String w = QUALITY_JUNK.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("").trim();
        // ★ 优先取"紧跟第N期"的分段标记，其次才看字符串末尾。
        //   顺序不能反：`第10期上：某副标题` 的末尾是副标题，PART_TAIL 抓不到。
        Matcher m = PART_AFTER_ORD.matcher(w);
        if (!m.find()) {
            m = PART_TAIL.matcher(w);
        }
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
        work = BRACKET_CONTENT.matcher(work).replaceAll("");
        work = QUALITY_JUNK.matcher(work.toLowerCase(Locale.ROOT)).replaceAll("");

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
     * 结构化"正片"判定：名字是不是<b>当期正片</b>（区别于超前企划/观演区/直拍/加更/纯享等衍生内容）。
     *
     * <p>判定规则（按序）：</p>
     * <ol>
     *   <li>命中非正片词表 → 非正片（快速路径，保留原词表的语义）；</li>
     *   <li>期数域：期号前不得压着中文内容词（见 {@link #isLeadingOrdinal}）→ 正片；<br>
     *       于是 {@code 第1期：初舞台（上）}、{@code 第2期一公挑战赛（上）} 是正片，
     *       而 {@code 超前企划第2期}、{@code 加更版第1期} 不是；</li>
     *   <li>日期域：剥掉日期数字、尾部分段（上/中/下）、标点后<b>没有残余</b>才算正片。<br>
     *       于是 {@code 20260815}、{@code 20260828上}、{@code 第20260502期上} 是正片，
     *       而 {@code 20260809超前企划上}、{@code 20260817备战篇}、{@code 20260821一公挑战赛上}、
     *       {@code 20260822纯享版} 都不是；</li>
     *   <li>其余（无日期也无可识别期号，如 {@code 先导片}、{@code 直拍机位}、{@code 一公挑战赛（下）}）
     *       → 非正片。</li>
     * </ol>
     *
     * <p><b>为什么不能只靠词表</b>：衍生内容的名字五花八门（超前企划/观演区/直拍/选歌组队/
     * 备战篇/全纪录/小考/宿舍日记/“聚”乐部…），黑名单永远列不全；列不全的后果是
     * "正片簇序"整体偏移，跨域切源就会系统性错一期。</p>
     */
    public static boolean isMainFeatureEntry(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        if (NON_MAIN_FEATURE.matcher(name).find()) {
            return false;
        }
        EpisodeKey key = parse(name);
        if (key.domain == DOMAIN_ORDINAL && key.ordinal > 0) {
            return isLeadingOrdinal(name);
        }
        if (key.domain == DOMAIN_DATE && key.ordinal > 0) {
            return stripMainResidue(name).isEmpty();
        }
        return false;
    }

    /** {@link #isMainFeatureEntry} 的取反，语义读起来更顺：该条目不是正片。 */
    private static boolean isNonMainEntry(String name) {
        return isNonMainEntry(name, null);
    }

    /**
     * 期标签集合 + 标签→期号，用于"两侧源站对同一期的不同写法"互相认领。
     *
     * @see #harvestFeatureLabels(List, List)
     */
    private static final class FeatureLabels {
        private final Set<String> names = new HashSet<>();
        private final Map<String, Integer> ordinals = new HashMap<>();

        void add(String label, int ordinal) {
            if (TextUtils.isEmpty(label)) {
                return;
            }
            names.add(label);
            if (ordinal > 0 && !ordinals.containsKey(label)) {
                ordinals.put(label, ordinal);
            }
        }

        boolean isEmpty() {
            return names.isEmpty();
        }

        boolean contains(String label) {
            return !TextUtils.isEmpty(label) && names.contains(label);
        }

        int ordinalOf(String label) {
            Integer v = TextUtils.isEmpty(label) ? null : ordinals.get(label);
            return v == null ? -1 : v;
        }
    }

    /**
     * 抽取"期标签"：把两侧列表里所有<b>领衔期号的正片</b>条目上的内容词收集起来。
     *
     * <p><b>为什么需要它</b>：同一期在不同采集源上的写法可能只有一侧带期号。实测
     * 披荆斩棘2026：期数式源写 {@code 第2期一公挑战赛（上）}，而日期式源写成
     * {@code 20260821一公挑战赛上}——名字里带了内容词，会被"剥离日期后须无残余"的
     * 结构化判定当成衍生条目（它和 {@code 20260821一公观演区上} 在结构上完全同型，
     * 纯结构无法区分）。结果目标侧的正片簇少一个，簇秩整体后移一期：用户在看
     * 「第4期上」，切源却落到「20260911上」（正确是「20260904上」）。</p>
     *
     * <p>而"期号领衔"这个信号是可靠的：{@code 第2期一公挑战赛（上）} 里的
     * {@code 一公挑战赛} 就是第 2 期的公共标签。把它抽出来，另一侧"日期 + 同标签(+上/下)"
     * 的条目即可被认领为正片。只做<b>完全相等</b>匹配，不做包含匹配——否则标签
     * {@code 初舞台} 会误伤 {@code 初舞台观演区}。</p>
     *
     * @param a 一侧剧集名列表（可为 null）
     * @param b 另一侧剧集名列表（可为 null）
     * @return 标签集合；两侧都没有"期号领衔 + 内容词"的条目时返回空集合（行为退回旧版）
     */
    private static FeatureLabels harvestFeatureLabels(List<String> a, List<String> b) {
        FeatureLabels out = new FeatureLabels();
        collectFeatureLabels(a, out);
        collectFeatureLabels(b, out);
        relaxTitledDates(a, out);
        relaxTitledDates(b, out);
        return out;
    }

    /**
     * 列表级风格校准：某一侧整条列表都判不出"结构化正片"时，
     * 说明该源用「日期 + 节目标题」命名正片（如 {@code 20260815初舞台上}），
     * 把该侧日期域条目的标题也纳入标签集合，使它们能被
     * {@link #isMainEntry(String, FeatureLabels)} 认领为正片。
     *
     * <p><b>为什么需要它</b>：结构化正片判定对日期域的要求是"剥掉日期与分段后无残余"。
     * 当一个源<b>整条列表</b>都用"日期 + 标题"命名时（{@code 20260815初舞台上}、
     * {@code 20260822一公挑战赛上}…），这条规则会把<b>全部条目</b>判成衍生内容，
     * 于是目标侧一个正片簇都没有，跨域对齐彻底失效。</p>
     *
     * <p><b>触发条件极保守</b>：只有该侧"结构化正片数为 0"才放宽。
     * 因此正常列表（正片是裸日期 / 领衔期号）完全不受影响，
     * 混在正片里的衍生条目也不会被误收。</p>
     *
     * <p>放宽只登记<b>标签</b>（期号记为 0，视为未知），不冒充"已知期号"，
     * 因此不会让 {@code label → 期号} 的反查被污染。</p>
     */
    private static void relaxTitledDates(List<String> names, FeatureLabels out) {
        if (names == null || names.isEmpty()) {
            return;
        }
        for (String n : names) {
            if (!TextUtils.isEmpty(n) && isMainFeatureEntry(n)) {
                return;                  // 该侧已有结构化正片 → 不放宽
            }
        }
        for (String n : names) {
            if (TextUtils.isEmpty(n) || NON_MAIN_FEATURE.matcher(n).find()) {
                continue;
            }
            EpisodeKey key = parse(n);
            if (key.domain != DOMAIN_DATE || key.ordinal <= 0) {
                continue;
            }
            String label = featureLabelOf(n);
            if (!TextUtils.isEmpty(label)) {
                out.add(label, 0);       // 期号未知，只登记标签
            }
        }
    }

    /**
     * 列表中所有"正片"条目的下标序列（保持原有先后次序）。
     *
     * <p><b>用途（第 0 层位置映射）</b>：同一档节目的两份源列表，其"正片集合"
     * 必然一一对应且同序。因此当两侧正片条目数相同时，第 k 个正片 ↔ 第 k 个正片。
     * 这条不变量不依赖"期"的概念，天然覆盖一期一条 / 上·下 / 上·中·下、
     * 目标侧不写分段（裸日期）、以及日更这类相邻日期间隔过小导致簇法失效的节目。</p>
     */
    private static List<Integer> mainFeatureSequence(List<String> names, FeatureLabels labels) {
        List<Integer> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        // ★ 同日裸日期优先：见 sameDayBareDates 的说明。
        java.util.Set<Integer> bareDays = sameDayBareDates(names, labels);
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (TextUtils.isEmpty(n) || !isMainEntry(n, labels)) {
                continue;
            }
            if (bareDays.isEmpty()) {
                out.add(i);
                continue;
            }
            int d = dateOf(n);
            // 同日已有裸日期正片时，本条若带内容词则判为衍生
            if (d > 0 && bareDays.contains(d) && !isBareDateName(n)) {
                continue;
            }
            out.add(i);
        }
        return out;
    }

    /**
     * 集名是否"裸日期式"：剥掉日期/期号/分段/「期」字后什么都不剩。
     *
     * <p>例：{@code 20260904上} → true（残留为分段标记，视为裸）；
     * {@code 20260904二公观演区上} → false（残留含内容词）。</p>
     */
    private static boolean isBareDateName(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        String r = stripMainResidue(name);
        return TextUtils.isEmpty(r) || isAllDigits(r);
    }

    private static boolean isAllDigits(String s) {
        if (TextUtils.isEmpty(s)) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 返回"该日存在裸日期正片条目"的日期集合（列表级上下文）。
     *
     * <p>实测《披荆斩棘2026》电影天堂源：20260904 那天同时有
     * {@code 20260904二公观演区上}（衍生）与 {@code 20260904上}（正片），
     * 只看单条无法区分，看"同日是否有裸条目"就能判出来。</p>
     *
     * <p>这类源的正片<b>只有 13~23% 是裸日期</b>（其余都带「观演区/小考/全纪录」
     * 等内容词），所以只靠扩充内容词表永远补不全 —— 必须用这个结构信号。</p>
     */
    private static java.util.Set<Integer> sameDayBareDates(List<String> names, FeatureLabels labels) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        if (names == null || names.isEmpty()) {
            return out;
        }
        for (String n : names) {
            if (TextUtils.isEmpty(n) || isNonMainEntry(n, labels) || !isMainEntry(n, labels)) {
                continue;
            }
            if (!isBareDateName(n)) {
                continue;
            }
            int d = dateOf(n);
            if (d > 0) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * 列表中"正片"条目的期号跨度（max − min + 1）；没有期号条目时返回 0。
     *
     * <p><b>用途</b>：判断"目标侧的期集合"与"源侧的期集合"规模是否一致 ——
     * 不一致即说明某一侧缺期，此时不能再用簇秩（会让后续所有期整体前移）。</p>
     */
    private static int ordinalSpan(List<String> names, FeatureLabels labels) {
        if (names == null || names.isEmpty()) {
            return 0;
        }
        int lo = -1;
        int hi = -1;
        for (String n : names) {
            if (TextUtils.isEmpty(n) || isNonMainEntry(n, labels)) {
                continue;
            }
            EpisodeKey key = parse(n);
            if (key.domain != DOMAIN_ORDINAL || key.ordinal <= 0) {
                continue;
            }
            if (lo < 0 || key.ordinal < lo) {
                lo = key.ordinal;
            }
            if (hi < 0 || key.ordinal > hi) {
                hi = key.ordinal;
            }
        }
        return lo < 0 ? 0 : (hi - lo + 1);
    }

    /**
     * 按「期标签 + 分段」在目标列表里找落点。
     *
     * <p>期标签是两侧对"同一期"的<b>显式</b>对应关系（内容词相同），比簇秩更可靠：
     * 它不受目标侧缺期影响——目标侧少了几期也不会让标签指错。
     * 先要同分段，找不到才退回同标签的任意分段（保持旧行为，避免整体失配）。</p>
     */
    private static int findIndexByFeatureLabel(String label, int wantPart,
                                               List<String> targetNames, FeatureLabels labels) {
        if (TextUtils.isEmpty(label) || targetNames == null || targetNames.isEmpty()) {
            return -1;
        }
        int fallback = -1;
        for (int i = 0; i < targetNames.size(); i++) {
            String n = targetNames.get(i);
            if (TextUtils.isEmpty(n) || !label.equals(featureLabelOf(n))) {
                continue;
            }
            if (isNonMainEntry(n, labels)) {
                continue;
            }
            if (normalizePart(extractPart(n)) == wantPart) {
                return i;
            }
            if (fallback < 0) {
                fallback = i;
            }
        }
        return fallback;
    }

    private static void collectFeatureLabels(List<String> names, FeatureLabels out) {
        if (names == null) {
            return;
        }
        for (String n : names) {
            if (TextUtils.isEmpty(n) || NON_MAIN_FEATURE.matcher(n).find()) {
                continue;
            }
            EpisodeKey key = parse(n);
            if (key.domain != DOMAIN_ORDINAL || key.ordinal <= 0) {
                continue;
            }
            if (!isLeadingOrdinal(n)) {
                continue;
            }
            out.add(featureLabelOf(n), key.ordinal);
        }
    }

    /**
     * 取名字里的"期标签"——剥掉日期、期号、尾部分段与标点后剩下的内容词。
     *
     * <pre>
     *   20260821一公挑战赛上   -> 一公挑战赛
     *   第2期一公挑战赛（上）  -> 一公挑战赛
     *   一公挑战赛（下）       -> 一公挑战赛   （三种写法归一，才能互相认领）
     *   20260815              -> ""          （纯日期，没有内容词）
     *   第4期上               -> ""
     * </pre>
     */
    private static String featureLabelOf(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        String work = BRACKET_CONTENT.matcher(name).replaceAll("");
        work = DATE_COMPACT.matcher(work).replaceAll("");
        work = DATE_SEPARATED.matcher(work).replaceAll("");
        work = EIGHT_DIGITS.matcher(work).replaceAll("");
        work = ORDINAL_ARABIC.matcher(work).replaceFirst("");
        work = ORDINAL_CHINESE.matcher(work).replaceFirst("");
        work = PART_TAIL.matcher(work).replaceAll("");
        work = MAIN_RESIDUE_JUNK.matcher(work).replaceAll("");
        return work.trim();
    }

    /**
     * 带"期标签交叉引用"的正片判定：先走纯结构化判定，
     * 再认领"名字里的内容词恰好是某一期公共标签"的条目。
     *
     * @see #harvestFeatureLabels(List, List)
     */
    private static boolean isMainEntry(String name, FeatureLabels labels) {
        if (isMainFeatureEntry(name)) {
            return true;
        }
        if (labels == null || labels.isEmpty()) {
            return false;
        }
        if (NON_MAIN_FEATURE.matcher(name == null ? "" : name).find()) {
            return false;
        }
        return labels.contains(featureLabelOf(name));
    }

    private static boolean isNonMainEntry(String name, FeatureLabels labels) {
        return !isMainEntry(name, labels);
    }

    /**
     * 期号是否"领衔"——即期号（或纯序号）之前<b>没有压着中文内容词</b>。
     *
     * <p>这是区分正片与衍生内容最稳定的结构特征，且不会误伤以纯数字/字母命名的源：</p>
     * <ul>
     *   <li>{@code 第1期：初舞台（上）}、{@code 第2期一公挑战赛（上）} → 领衔 ✅</li>
     *   <li>{@code 第三季 第1期} → 前置的"第三季"属季号，剥离后仍领衔 ✅</li>
     *   <li>{@code 01}、{@code EP01} → 无中文内容词前缀 ✅（兼容纯序号命名的源）</li>
     *   <li>{@code 超前企划第2期}、{@code 预热直播第2期}、{@code 加更版第1期} → 前缀是内容词 ❌</li>
     * </ul>
     */
    private static boolean isLeadingOrdinal(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        String work = BRACKET_CONTENT.matcher(name).replaceAll("").trim();
        int start = -1;
        Matcher arabic = ORDINAL_ARABIC.matcher(work);
        if (arabic.find()) {
            start = arabic.start();
        }
        Matcher chinese = ORDINAL_CHINESE.matcher(work);
        if (chinese.find() && (start < 0 || chinese.start() < start)) {
            start = chinese.start();
        }
        if (start < 0) {
            // 无"期/集"后缀的纯序号命名（01 / EP01 / Part 1）：数字前无中文内容词即为正片。
            // 8 位数字属日期语义，不在此判定（由日期分支处理）。
            if (EIGHT_DIGITS.matcher(work).find()) {
                return false;
            }
            String prefix = work.replaceAll("[0-9]+.*$", "");
            return !HAS_CJK.matcher(prefix).find();
        }
        String prefix = work.substring(0, start);
        if (SEASON_PREFIX.matcher(prefix).matches()) {
            return true;
        }
        return !HAS_CJK.matcher(prefix).find();
    }

    /**
     * 剥离日期、尾部分段与标点，返回残余文本（用于结构化正片判定）。
     * 残余为空 = 名字只由"日期（+上/下）"构成 = 正片。
     */
    private static String stripMainResidue(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        String work = BRACKET_CONTENT.matcher(name).replaceAll("");
        work = DATE_COMPACT.matcher(work).replaceAll("");
        work = DATE_SEPARATED.matcher(work).replaceAll("");
        work = EIGHT_DIGITS.matcher(work).replaceAll("");
        work = PART_TAIL.matcher(work).replaceAll("");
        work = MAIN_RESIDUE_JUNK.matcher(work).replaceAll("");
        return work.trim();
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
     * 离线跨域对齐：把"期数式集名"与"日期式集名"对应起来（第2-c层，
     * 跨域换算直连失败时的本地兜底）。
     *
     * <p><b>为什么需要它</b>：跨域换算的权威层是直连站点（{@link EpisodeOnlineResolver}），
     * 但站点可能被墙/超时/节目不在 slug 表内。此前直连失败后只能裸用旧源下标兜底，
     * 而两源列表构成不同（目标源常混入特辑/花絮），裸下标会静默切到错误条目——
     * 实测案例：旧源「第5期上」（下标27）切到目标源下标27 的「20260508泳池特辑」，
     * 而正确答案是「20260502上」（第5期）。</p>
     *
     * <h3>三层策略（从强到弱，任一命中即返回）</h3>
     *
     * <p><b>第 0 层 · 正片序列位置映射</b>：同一档节目的两份源列表，其"正片集合"
     * 必然一一对应且同序，因此当两侧正片条目数相同时，第 k 个正片 ↔ 第 k 个正片。
     * 这条不变量不依赖"期"的概念，一次覆盖一大类场景：一期一条 / 上·下 / 上·中·下、
     * 目标源不写分段（裸日期）、两侧分段写法不一致，以及<b>日更、周双更</b>这类
     * 相邻日期间隔过小（≤{@link #CLUSTER_GAP_DAYS}）导致"正片全被并成一个簇"、
     * 簇法彻底失效的节目。</p>
     *
     * <p><b>第 1 层 · 期标签锚定</b>：同一期在两侧常带相同的内容词——
     * 期数式源写 {@code 第2期一公挑战赛（上）}，日期式源写 {@code 20260821一公挑战赛上}，
     * 内容词 {@code 一公挑战赛} 就是它的公共标签。标签是两侧对"同一期"的<b>显式</b>对应，
     * 落位<b>不受一侧缺期影响</b>，因此优先于簇秩。用 ordinal 一致性守卫：标签若明确
     * 属于另一期则不采纳，避免张冠李戴。</p>
     *
     * <p><b>第 2 层 · 簇秩 + 绝对日期外推</b>：综艺列表里正片按"期"成簇出现，
     * 簇与簇之间隔着非正片条目或超过 {@link #CLUSTER_GAP_DAYS} 天的播出间隔
     * （同期的上/下分段通常相邻 0~2 天）：</p>
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
     * <p>簇序是"列表内相对次序"，与日期绝对数值无关——即使源站把年份整体错标
     * （真实案例：把 20260502 写成 20250502），簇序依然正确，按日期值比对反而必败。</p>
     *
     * <p><b>缺期修正</b>：簇秩假设"第 N 期 = 第 N-1 个簇"，一旦某一源缺了中间的期，
     * 后续所有期都会整体前移/后移。因此本方法比较<b>两侧期集合的规模</b>
     * （见 {@link #ordinalSpan(List, FeatureLabels)}）：规模不一致即说明缺期，
     * 此时改用基于绝对日期的"首播日 + 7×(N-1)"外推——它按日期落位，
     * 不受列表缺项影响。规模一致时仍用簇秩（对隔周更等非严格周更节目更稳）。</p>
     *
     * <p><b>两个方向</b>：</p>
     * <ul>
     *   <li>期数式当前集 → 日期式目标；</li>
     *   <li>日期式当前集 → 期数式目标。</li>
     * </ul>
     *
     * <p><b>宁缺毋滥</b>：当前集是非正片、无法解析、或对应关系建立不起来时一律返回 -1，
     * 交由调用方原有兜底，绝不无依据猜测。</p>
     *
     * <p><b>期标签交叉引用</b>：见 {@link #harvestFeatureLabels(List, List)}。
     * 抽不到标签时返回空集合，行为与旧版完全一致。</p>
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
        // 期标签交叉引用：把两侧源站对"同一期"的不同写法互相认领起来。
        // 例：期数式源「第2期一公挑战赛（上）」↔ 日期式源「20260821一公挑战赛上」。
        // 只做完全相等匹配；两侧都没有"期号领衔 + 内容词"时返回空集合，行为与旧版一致。
        final FeatureLabels labels = harvestFeatureLabels(sourceNames, targetNames);
        // 非正片（加更/花絮/特辑/超前企划/观演区/直拍…）不参与秩对齐：
        // 它们与"期"没有稳定的对应关系，硬对齐必然错位。
        // 这里用"结构化正片判定"，覆盖关键词表列不全的衍生命名。
        if (isNonMainEntry(currentName, labels)) {
            return -1;
        }

        // ================= 第 0 层：正片序列位置映射（最强、最通用）=================
        // 同一档节目的两份源列表，其"正片集合"必然一一对应且同序。因此当两侧
        // 正片条目数相同时，第 k 个正片 ↔ 第 k 个正片 —— 位置即答案。
        //
        // 这条不变量不依赖"期"的概念，因此天然覆盖一大类此前会出错的场景：
        //   · 一期一条 / 一期上·下 / 一期上·中·下（两侧条数一致即可）；
        //   · 目标源不写分段（裸日期 20260904/20260905 ↔ 第4期上/下）；
        //   · 日更、周双更这类"相邻日期间隔 ≤ 簇阈值"的节目 ——
        //     它们的正片会被 buildMainFeatureClusters 全并成一个簇，簇秩彻底失效；
        //   · 两侧分段写法不一致（上/上集/（上）/无后缀）。
        List<Integer> srcSeq = mainFeatureSequence(sourceNames, labels);
        List<Integer> tgtSeq = mainFeatureSequence(targetNames, labels);
        if (!srcSeq.isEmpty() && srcSeq.size() == tgtSeq.size()) {
            int pos = srcSeq.indexOf(sourceIndex);
            if (pos >= 0) {
                return tgtSeq.get(pos);
            }
        }

        EpisodeKey cur = parse(currentName);
        final boolean curHasOrdinal = cur.domain == DOMAIN_ORDINAL && cur.ordinal > 0;
        final boolean curHasDate = cur.domain == DOMAIN_DATE && cur.ordinal > 0;
        final int curPart = extractPart(currentName);
        final int wantPart0 = normalizePart(curPart);
        final String curLabel = featureLabelOf(currentName);
        // 当前集的"期号"：优先用名字自带的；没有期号也没有日期时，用期标签反查它属于哪一期。
        // 实测披荆斩棘2026：豪华资源把第2期下写成「一公挑战赛（下）」，名字里没有期号，
        // 旧实现直接返回 -1 → 退回按位置猜。有了标签就能认出它是第 2 期。
        int curOrdinal = curHasOrdinal ? cur.ordinal : 0;
        if (!curHasOrdinal && !curHasDate) {
            int byLabel = labels.ordinalOf(curLabel);
            if (byLabel > 0) {
                curOrdinal = byLabel;
            }
        }

        // ================= 第 -1 层：跨源事实查表（最高优先级）=================
        // 之前某次切源已经把"这个期号 = 那个日期"两侧证实过了，直接查表落位。
        // 这一层不受列表形态影响——对"两侧都是密集日更/衍生夹心"的列表尤其关键，
        // 因为那些列表会让下面的簇秩与周更快照整体偏移一期。
        int byFact = alignByRememberedFact(currentName, curOrdinal, curHasDate,
                cur.ordinal, wantPart0, targetNames);
        if (byFact >= 0) {
            return byFact;
        }

        // ================= 第 1 层：期标签锚定（两侧对同一期的显式对应）=================
        // 内容词是同一期在两侧的公共标签，用它落位**不受目标侧缺期影响**——
        // 目标源少了几期也不会让标签指错，因此排在簇秩之前。
        // 用 ordinal 一致性守卫：标签若明确属于<b>另一期</b>，就不采纳（避免张冠李戴）。
        if (!TextUtils.isEmpty(curLabel)) {
            int lblOrd = labels.ordinalOf(curLabel);
            if (lblOrd <= 0 || curOrdinal <= 0 || lblOrd == curOrdinal) {
                int byLabelIndex = findIndexByFeatureLabel(curLabel, wantPart0, targetNames, labels);
                if (byLabelIndex >= 0) {
                    return byLabelIndex;
                }
            }
        }

        if (curOrdinal > 0) {
            final int rank = curOrdinal - 1;
            final int wantPart = wantPart0;
            // ① 簇法：期号即秩（第N期 = 目标列表第 N-1 个正片簇）
            List<List<Integer>> clusters = buildMainFeatureClusters(targetNames, labels);
            // 只在"目标确实是日期式列表"时才用簇法：期数式列表解析不出日期，
            // 所有正片都会落进同一个簇，簇秩失去意义（那种情况交给按名匹配更安全）。
            int byCluster = -1;
            if (rank < clusters.size()
                    && dateOf(targetNames.get(clusters.get(rank).get(0))) > 0) {
                long expectedDay = expectedDayOf(targetNames, clusters, rank);
                byCluster = pickMainFeatureEntry(targetNames, clusters.get(rank), wantPart, expectedDay);
                // expectedDay 只作为簇内"就近挑条"的依据，不作为硬性可信度门槛：
                // 非严格周更的节目（隔周更新、中间插特辑等）会让"首簇 + 7×rank"整体偏移，
                // 用硬校验会把<b>正确结果误拒</b>而退回按位置猜。
            }
            // ★★★ 反向插入簇校准（期号式源 → 日期式目标）：
            //   目标列表开头有"特别节目连播"被判成正片时，簇序整体后移，
            //   直接拿「第N期 → 第N簇」会整体偏一期。校准后 rank 往后挪 drop 个簇。
            //   与日期式分支用同一个判据（双向对称），详见 leadingInsertClusters。
            int dropRev = leadingInsertClusters(sourceNames, targetNames, labels);
            if (dropRev > 0 && rank + dropRev < clusters.size()
                    && dateOf(targetNames.get(clusters.get(rank + dropRev).get(0))) > 0) {
                int effRank = rank + dropRev;
                long expectedDay2 = expectedDayOf(targetNames, clusters, effRank);
                byCluster = pickMainFeatureEntry(targetNames, clusters.get(effRank),
                        wantPart, expectedDay2);
            }
            // ② 绝对日期外推：首播日 + 7×(N-1) = 第 N 期的期望播出日，按日期直接落位。
            //    同样要把插入簇校准传进去，否则它会抢先返回未校准的错误结果。
            int byDay = findIndexByExpectedDay(targetNames, curOrdinal, wantPart, labels, dropRev);
            // ③ 期集合规模判定：两侧规模一致 → 簇秩即正确映射；
            //    不一致说明目标源缺期（列表比源侧少几期），此时簇秩会让后续所有期
            //    整体前移，改用<b>不受列表缺项影响</b>的日期外推（实测：目标源缺第4期时，
            //    源侧第5期曾被簇秩算成目标侧第6期）。
            int srcSpan = ordinalSpan(sourceNames, labels);
            if (byDay >= 0 && srcSpan > 0 && clusters.size() != srcSpan) {
                return byDay;
            }
            // ★★ 簇数 < 源侧期数时，簇秩会把后续所有期**整体前移** —— 静默错一集。
            //   实测《披荆斩棘2026》360资源源：源侧 8 期，但 0904 那期的裸锚
            //   （正片「二公团秀对决上」）整组被判成衍生，簇只剩 7 个，
            //   于是「第4期」被映到簇3（=0911 那期，第5期）—— 错一期且毫无迹象。
            //   日期外推 byDay 也不可靠（首播日 0815 + 7×3 = 0905 也不在列表里）。
            //   此时**宁可返回 -1**（不落，保持原集不动），绝不能猜。
            if (srcSpan > 0 && !clusters.isEmpty() && clusters.size() < srcSpan
                    && byDay < 0 && byCluster >= 0) {
                return -1;
            }
            if (byCluster >= 0) {
                return byCluster;
            }
            return byDay;
        }
        if (curHasDate) {
            final long curDay = dayNumberOf(cur.ordinal);
            final int explicitPart = curPart;
            // ① 簇法：用旧源列表自身的正片簇求当前集的期簇序。
            // 注意簇序只依赖列表内的相对次序，与日期数值无关，
            // 因此源站年份整体错标（2025… vs 实际 2026…）不影响结果。
            List<List<Integer>> clusters = buildMainFeatureClusters(sourceNames, labels);
            int rank = mainFeatureClusterRank(sourceNames, sourceIndex, labels);
            int byRank = -1;
            if (rank >= 0) {
                // 分段推断：名字没写上/下时，用簇内位置推（2 条 → 上/下；3 条 → 上/中/下）。
                // 例：dytt 的裸日期「20260815 / 20260816」= 第1期上 / 第1期下，
                // 若不推断，看「20260816」切源会落到第1期<b>上</b>（用户实测 case）。
                int wantPart = normalizePart(explicitPart);
                boolean partKnown = explicitPart != PART_NONE;
                List<Integer> cl = clusters.get(rank);
                if (!partKnown && cl.size() >= 2 && cl.size() <= 3 && cl.contains(sourceIndex)) {
                    int pos = cl.indexOf(sourceIndex);
                    wantPart = pos == 0 ? PART_UP
                            : (cl.size() == 3 && pos == 1 ? PART_MIDDLE : PART_DOWN);
                    partKnown = true;
                }
                // ★ 不用"首簇日期 + 7×rank"的周更快照做可信度校验：该假设只对严格周更成立。
                //   实测披荆斩棘2026 第1期 0815、第2期 0828（中间夹衍生内容，间隔 13 天），
                //   旧实现会把正确结果误拒，再退回"按位置猜"从而切错集。改为自校验：
                //   按秩算出的期号必须在目标列表里真实存在，否则走外推法。
                byRank = findIndexByEpisode(currentName, rank + 1, targetNames,
                        partKnown ? wantPart : -1, labels);
            }
            // ② 绝对日期外推：期号 ≈ (当前日期 − 首播日) / 节拍 + 1。
            int firstDate = firstMainDate(sourceNames, labels);
            // ★★★ 开头插入簇校准：真第1期不在列表首条时把首播基准往后挪。
            //   仅在目标侧带分段时启用（见 leadingInsertClusters 的说明），
            //   对无分段节目完全不触发。
            int drop = leadingInsertClusters(sourceNames, targetNames, labels);
            if (drop > 0) {
                int adjusted = firstMainDateSkip(sourceNames, labels, drop);
                if (adjusted > 0) {
                    firstDate = adjusted;
                }
            }
            int byEpisode = -1;
            int srcPeriod = expectedPeriodBetweenClusters(sourceNames, labels);
            if (srcPeriod <= 0) {
                srcPeriod = 7;         // 保留原周更快照（实测行为正确）
            }
            if (firstDate > 0 && curDay > 0) {
                long diff = curDay - dayNumberOf(firstDate);
                if (diff >= 0) {
                    int episode = (int) Math.round(diff / (double) srcPeriod) + 1;
                    // 周更快照校验：当前日期应贴近"首播日 + 节拍×(期号-1) 天"
                    int epTol = srcPeriod <= 3 ? 0 : Math.max(1, srcPeriod / 4);
                    if (Math.abs(diff - (long) srcPeriod * (episode - 1)) <= epTol) {
                        byEpisode = findIndexByEpisode(currentName, episode, targetNames, -1, labels);
                    }
                }
            }
            // ③ 期集合规模判定（同前）：源侧缺期时簇秩会让后续所有期整体后移，
            //    改用日期外推——它基于绝对日期，不受源列表缺项影响。
            int tgtSpan = ordinalSpan(targetNames, labels);
            if (byEpisode >= 0 && tgtSpan > 0 && clusters.size() != tgtSpan) {
                return byEpisode;
            }
            if (byRank >= 0) {
                return byRank;
            }
            return byEpisode;
        }
        return -1;
    }

    // ================= 跨源事实记忆（跨命名域换算的"已验证对应关系"） =================
    //
    // 【为什么需要它】
    // 期数式与日期式之间的对应关系，只有两侧都带"期号语义"时才能本地推导。
    // 但真实源站里有一类列表<b>两侧都没有干净的期号语义</b>：
    //   · 期数式源：第1期…第6期 之间夹着「特别企划 / 超前营业 / 加更版 / 纯享版 / 直拍」；
    //   · 日期式源：第20260515期…第20260626期 全部是「第YYYYMMDD期」包裹式，
    //     且首条 20260515 其实是"特别企划"而不是第1期正片。
    // 此时"首播日 + 7×(N−1)"的基准取错一条，整档节目所有期号<b>整体偏移一期</b>
    // （实测歌手2026：第3期被算成第20260529期，正确是第20260605期）。
    //
    // 【为什么这一层能解决】
    // 切源是<b>连续多次</b>的动作：歌手2026 在本次会话里已经成功走过
    // 「20260605 → 第3期」（HG → 红牛）。这条对应关系是<b>已被两侧同时证实</b>的，
    // 比任何猜测都可靠。把它记下来，后续「第3期 → 任意源」都能直接查表命中，
    // 不再依赖脆弱的周更快照推算。
    //
    // 【安全性】
    // 只记录"两侧都自证"的强证据（当前名带期号且命中名带日期，或反之），
    // 不记录任何推断结果；条目按节目无关的全局键存储，数量级极小（几十条），
    // 超出上限按插入顺序淘汰最旧的。
    private static final int FACT_LIMIT = 128;

    /** key = 归一化期号 + "|" + 分段；value = 该期对应的播出日期 YYYYMMDD。 */
    private static final Map<String, Integer> FACT_ORDINAL_TO_DATE = new HashMap<>();

    /** key = 日期 + "|" + 分段；value = 该日期对应的期号。 */
    private static final Map<String, Integer> FACT_DATE_TO_ORDINAL = new HashMap<>();

    /** 记录顺序，用于 FIFO 淘汰。 */
    private static final List<String> FACT_ORDER = new ArrayList<>();

    /** 记忆键前缀：期号命名空间（避免与日期命名空间撞键）。 */
    private static String factKeyOfOrdinal(int ordinal, int part) {
        return "O" + ordinal + "|" + part;
    }

    /** 记忆键前缀：日期命名空间。 */
    private static String factKeyOfDate(int date, int part) {
        return "D" + date + "|" + part;
    }

    /**
     * 记住一条"期号 ↔ 日期"的对应关系。
     *
     * @param ordinal 期号（>0）
     * @param date    播出日期 YYYYMMDD（>0）
     * @param part    {@link #normalizePart(int)} 归一化后的分段
     */
    public static void rememberCrossDomainFact(int ordinal, int date, int part) {
        if (ordinal <= 0 || date <= 0) {
            return;
        }
        int p = normalizePart(part);
        String k1 = factKeyOfOrdinal(ordinal, p);
        String k2 = factKeyOfDate(date, p);
        // 冲突时以先到为准（先到者来自更早的一次成功对齐，同样可靠）
        if (!FACT_ORDINAL_TO_DATE.containsKey(k1)) {
            FACT_ORDINAL_TO_DATE.put(k1, date);
            FACT_ORDER.add(k1);
        }
        if (!FACT_DATE_TO_ORDINAL.containsKey(k2)) {
            FACT_DATE_TO_ORDINAL.put(k2, ordinal);
            FACT_ORDER.add(k2);
        }
        while (FACT_ORDER.size() > FACT_LIMIT) {
            String old = FACT_ORDER.remove(0);
            if (FACT_ORDINAL_TO_DATE.containsKey(old)) {
                FACT_ORDINAL_TO_DATE.remove(old);
            } else {
                FACT_DATE_TO_ORDINAL.remove(old);
            }
        }
    }

    /** 查询记住的"期号 → 日期"。未命中返回 -1。 */
    public static int recallDateOfOrdinal(int ordinal, int part) {
        if (ordinal <= 0) {
            return -1;
        }
        Integer v = FACT_ORDINAL_TO_DATE.get(factKeyOfOrdinal(ordinal, normalizePart(part)));
        return v == null ? -1 : v;
    }

    /** 查询记住的"日期 → 期号"。未命中返回 -1。 */
    public static int recallOrdinalOfDate(int date, int part) {
        if (date <= 0) {
            return -1;
        }
        Integer v = FACT_DATE_TO_ORDINAL.get(factKeyOfDate(date, normalizePart(part)));
        return v == null ? -1 : v;
    }

    /** 清空跨源事实记忆（切源列表整体变化时调用，避免陈旧事实干扰）。 */
    public static void clearCrossDomainFacts() {
        FACT_ORDINAL_TO_DATE.clear();
        FACT_DATE_TO_ORDINAL.clear();
        FACT_ORDER.clear();
    }

    /**
     * 在目标列表里定位指定日期的条目（记事实时用来反查分段口径）。
     *
     * @param date  播出日期 YYYYMMDD
     * @param names 目标集名列表
     * @return 命中的条目名；未命中返回 null
     */
    private static String nameOfDate(List<String> names, int date) {
        if (names == null || date <= 0) {
            return null;
        }
        for (String n : names) {
            if (!TextUtils.isEmpty(n) && dateOf(n) == date) {
                return n;
            }
        }
        return null;
    }

    /**
     * 切源落点<b>可信度</b>自检：供调用方判断"这次切源是否落对了"。
     *
     * <p>用于<b>冷启动探路</b>的触发判据。冷启动的典型症状不是"没匹配上"，
     * 而是<b>错切到了一个确实存在的条目</b>（实测歌手2026：第8期被算成「第20260703期」，
     * 应为「第20260710期」），所以必须能识别"落了但不可信"。</p>
     *
     * @return true 表示落点可信或无法判定；false 表示序明显矛盾（很可能错了）
     */
    public static boolean isMatchTrusted(String currentName, String matchedName,
                                         List<String> sourceNames, List<String> targetNames) {
        if (TextUtils.isEmpty(currentName) || TextUtils.isEmpty(matchedName)) {
            return false;                       // 没落上 → 交给探路
        }
        int ordinal = leadingOrdinalOf(currentName);
        if (ordinal <= 0) {
            return true;                        // 当前集名不带期号，本就无从判断
        }
        return rankConsistent(ordinal, matchedName, sourceNames, targetNames);
    }

    /**
     * 冷启动探路：先从<b>其它可用源</b>把「第N期 = 某日期」这个事实建立起来，再回到目标源精确落位。
     *
     * <p><b>要解决的问题</b>：目标源是"全无名日期式"（{@code 第YYYYMMDD期}，每条都不带内容词）时，
     * 本地无法区分「正片」与「期内的每日衍生条目」，于是首播基准会认错，整体偏移一期。
     * 实测（歌手2026 冷启动）：第8期被算成「第20260703期」（应为 0710）。
     * 而同节目在裸日期式的可靠源上落点是准的 —— 先从它把事实建起来，目标源立刻变准。</p>
     *
     * <p><b>实测收益</b>（真实列表，每轮清空事实表模拟冷启动）：
     * 无探路 正确 11 / 错 22；有探路 正确 30 / 错 3。对本来已正确的场景无损害。</p>
     *
     * <p><b>生效前提</b>：当前集名必须能解析出期号（{@code 第N期}/{@code 第N期上}）。
     * 源侧本身就是日期式时探路无意义 —— 那种情况两侧都是日期，建不出新事实，
     * 直接返回 -1 交回原流程。</p>
     *
     * @param currentName  当前集名（旧源）
     * @param sourceNames 旧源完整列表
     * @param targetNames 目标源完整列表
     * @param otherLists  其它可用源的完整列表（可为空）
     * @return 修正后的目标源下标；无修正返回 -1
     */
    public static int probeFactFromOtherSources(String currentName,
                                                List<String> sourceNames,
                                                List<String> targetNames,
                                                List<List<String>> otherLists) {
        if (TextUtils.isEmpty(currentName) || sourceNames == null
                || targetNames == null || otherLists == null) {
            return -1;
        }
        int si = sourceNames.indexOf(currentName);
        if (si < 0) {
            return -1;
        }
        int ordinal = leadingOrdinalOf(currentName);
        if (ordinal <= 0) {
            return -1;                // 源侧无期号 → 建不出事实，放弃探路
        }
        int part = extractPart(currentName);
        for (List<String> other : otherLists) {
            if (other == null || other.isEmpty()) {
                continue;
            }
            int ro = locate(currentName, si, sourceNames, other);
            if (ro < 0 || ro >= other.size()) {
                continue;
            }
            String probeName = other.get(ro);
            if (TextUtils.isEmpty(probeName) || probeName.equals(currentName)) {
                continue;
            }
            // 探路候选必须"像正片"：非衍生条目，且没有附加内容词
            if (!looksLikeMainEpisode(probeName) || dateOf(probeName) <= 0) {
                continue;
            }
            // 序关系守卫用**真实规模的探路列表**入参。
            // ★ 这里绝不能用 Collections.singletonList(探路名)：单元素列表的正片序只有 1 项，
            //   会让守卫把**正确**的探路结果也判成"不可信"，探路完全失效。
            if (!rankConsistent(ordinal, probeName, sourceNames, other)) {
                continue;
            }
            // 事实成立 → 记下来，并立刻用它重解目标源
            learnCrossDomainFact(currentName, probeName, sourceNames, other);
            int again = locate(currentName, si, sourceNames, targetNames);
            if (again >= 0) {
                return again;
            }
        }
        return -1;
    }

    /**
     * 集名是否"像正片"：不是衍生条目，且除日期/期号/分段外没有其它内容词。
     *
     * <p>用于筛选探路候选。必须排除带内容词的条目（如 {@code 20260810直拍}）——
     * 实测曾因随手取到这种条目，建了个错误锚点，比不探路更糟。</p>
     */
    public static boolean looksLikeMainEpisode(String name) {
        // isMainEntry 只有带 FeatureLabels 的重载；此处是公开 API 拿不到 labels，
        // 传 null 表示"不依赖采集到的系列标签"，与 isNonMainEntry(name) 单参口径一致。
        if (TextUtils.isEmpty(name) || isNonMainEntry(name) || !isMainEntry(name, null)) {
            return false;
        }
        String residue = stripMainResidue(name);
        if (TextUtils.isEmpty(residue)) {
            return true;
        }
        // 剥掉日期/分段后剩下的纯数字就是期号本身（如「第4期上」→ "4"），仍算正片
        boolean allDigits = true;
        for (int i = 0; i < residue.length(); i++) {
            if (!Character.isDigit(residue.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            return true;
        }
        // 允许残留的只有分段标记（上/中/下）与「期」字
        for (int i = 0; i < residue.length(); i++) {
            char c = residue.charAt(i);
            if (c != '上' && c != '中' && c != '下' && c != '期'
                    && !Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 序关系守卫：期号在源列表正片序列里的排名，是否与该集在目标列表正片序列里的排名相符。
     *
     * <p>用于识别"冷启动错切 → 事实表被永久污染"。两侧列表缺任一方时跳过校验（放行）。</p>
     *
     * @return true 表示序相符或无法判定（可入库）；false 表示序明显矛盾（拒绝入库）
     */
    private static boolean rankConsistent(int ordinal, String matchedInList,
                                          List<String> sourceNames, List<String> targetNames) {
        if (sourceNames == null || targetNames == null
                || sourceNames.isEmpty() || targetNames.isEmpty()) {
            return true;                       // 无从判定，放行
        }
        // 目标列表里该集在正片序列中的排名（0 起）
        int tgtRank = mainFeatureRankOf(matchedInList, targetNames);
        if (tgtRank < 0) {
            return true;
        }
        // 源侧：用「第N期」在源列表正片序列里的排名作为参照
        int srcRank = ordinal - 1;
        List<Integer> srcSeq = mainFeatureSequence(sourceNames, null);
        if (!srcSeq.isEmpty()) {
            // 源列表是正片式（能识别期号）→ 直接按期号定位更准
            int byOrd = -1;
            for (int i = 0; i < srcSeq.size(); i++) {
                EpisodeKey k = parse(sourceNames.get(srcSeq.get(i)));
                if (k.domain == DOMAIN_ORDINAL && k.ordinal == ordinal) {
                    byOrd = i;
                    break;
                }
            }
            if (byOrd >= 0) {
                srcRank = byOrd;
            } else {
                return true;                   // 源侧找不到该期号，无从判定
            }
        }
        int maxRank = Math.max(srcRank, tgtRank);
        if (maxRank <= 0) {
            return true;
        }
        // 序偏差不超过 50% 视为正常（两侧正片数不同、衍生条目分布不同都会造成偏差）
        return Math.abs(srcRank - tgtRank) * 2 <= maxRank;
    }

    /** 条目在列表正片序列中的排名（0 起）；不是正片或找不到返回 -1。 */
    private static int mainFeatureRankOf(String name, List<String> names) {
        if (TextUtils.isEmpty(name) || names == null) {
            return -1;
        }
        List<Integer> seq = mainFeatureSequence(names, null);
        int idx = indexOfIgnoreCase(seq, names, name);
        return idx;
    }

    /** 在 names 中找到 name 的下标（线性查找，列表规模小）；找不到返回 -1。 */
    private static int indexOfIgnoreCase(List<Integer> seq, List<String> names, String name) {
        int r = -1;
        for (int k = 0; k < seq.size(); k++) {
            int i = seq.get(k);
            if (i >= 0 && i < names.size() && TextUtils.equals(names.get(i), name)) {
                return k;
            }
        }
        return r;
    }

    /**
     * 记事实的统一入口：从"一次成功的跨域对齐"中抽取两侧的（期号, 日期）。
     *
     * <p>调用时机：任何跨命名域成功落位之后。两侧名字至少有一侧带日期、
     * 另一侧带期号（或两侧都是「第YYYYMMDD期」这类双语义）时才真正入库。</p>
     *
     * @param currentName 切源前（旧源）的集名
     * @param matchedName 切源后（新源）命中的集名
     */
    public static void learnCrossDomainFact(String currentName, String matchedName) {
        learnCrossDomainFact(currentName, matchedName, null, null);
    }

    /**
     * 带<b>序关系守卫</b>的记事实入口（推荐调用）。
     *
     * <p><b>为什么必须加守卫</b>：事实表采用"先到为准"，一旦入库就是<b>永久</b>的。
     * 而冷启动时的错切（例如全无名日期源把「第8期」算成「第20260703期」，
     * 正确应为第20260722期）会把错事实钉死，之后<b>每一次</b>切源都被它带偏 ——
     * 实测歌手2026 的事实表会错成 {@code 8=20260703、7=20260626}，整体错一期。</p>
     *
     * <p>守卫原理：期号在源列表正片序列里的<b>序</b>必须与命中项在目标列表正片序列里的
     * <b>序</b>同向且大致相当。上面那个错例里，「第8期」在源侧正片序列排第8，
     * 而命中的「第20260703期」在目标侧只排第 30 位 —— 序严重不符，即为可疑，入库前否决。</p>
     *
     * @param sourceNames 旧源列表（可为null → 跳过守卫）
     * @param targetNames 新源列表（可为null → 跳过守卫）
     */
    public static void learnCrossDomainFact(String currentName, String matchedName,
                                            List<String> sourceNames, List<String> targetNames) {
        if (TextUtils.isEmpty(currentName) || TextUtils.isEmpty(matchedName)) {
            return;
        }
        int curDate = dateOf(currentName);
        int curOrd = leadingOrdinalOf(currentName);
        int tgtDate = dateOf(matchedName);
        int tgtOrd = leadingOrdinalOf(matchedName);
        int part = extractPart(matchedName);
        // 方向一：旧源给日期、新源给期号
        if (curDate > 0 && curOrd <= 0 && tgtOrd > 0 && tgtOrd < 1000) {
            if (rankConsistent(tgtOrd, matchedName, sourceNames, targetNames)) {
                rememberCrossDomainFact(tgtOrd, curDate, part);
            }
            return;
        }
        // 方向二：旧源给期号、新源给日期
        // 守卫要在**目标列表**里查命中项的序，故传 matchedName（不是 currentName）
        if (curOrd > 0 && curOrd < 1000 && curDate <= 0 && tgtDate > 0) {
            if (rankConsistent(curOrd, matchedName, sourceNames, targetNames)) {
                rememberCrossDomainFact(curOrd, tgtDate, part);
            }
            return;
        }
        // 方向三：两侧都是日期式 → 不产生新的期号锚点，跳过。
        //   （这类对齐不提供"期号 ↔ 日期"的对应关系，强行入库反而会污染事实表。）
    }

    /**
     * 取"领衔期号"（真正的「第N期 / 第N集」），排除「第20260515期」这种日期包裹式。
     *
     * <p>供冷启动探路判断"当前集名是否带期号"（不带则建不出跨源事实，无需探路）。</p>
     *
     * @param name 集名
     * @return 期号；非期数式或为日期包裹式时返回 -1
     */
    public static int leadingOrdinalOf(String name) {
        if (TextUtils.isEmpty(name)) {
            return -1;
        }
        // 名字里含 8 位日期 → 是「第YYYYMMDD期」包裹式，期号语义即日期，不算期数
        if (dateOf(name) > 0) {
            return -1;
        }
        return extractOrdinal(name);
    }

    /**
     * 第 -1 层：跨源事实查表。
     *
     * <p>命中即返回——这是<b>唯一被两侧同时证实过</b>的对应关系，
     * 优先级高于任何启发式（第0层位置映射、期标签、簇秩、周更快照）。</p>
     *
     * <p>之所以放在最前面：那些启发式在"两侧都是密集日更/衍生条目夹心"的列表上
     * 会整体偏移一期（见类注释【为什么需要它】），而查表法不受列表形态影响。</p>
     *
     * @return 目标列表下标；无记录或目标列表里找不到该日期时返回 -1
     */
    private static int alignByRememberedFact(String currentName, int curOrdinal,
                                             boolean curHasDate, int curDate,
                                             int wantPart0, List<String> targetNames) {
        int wantDate = -1;
        int wantOrdinal = -1;
        if (curHasDate) {
            wantOrdinal = recallOrdinalOfDate(curDate, wantPart0);
            if (wantOrdinal <= 0) {
                wantOrdinal = recallOrdinalOfDate(curDate, PART_NONE);
            }
            if (wantOrdinal <= 0) {
                // 表里没有这一天 → 用已有锚点外推它的期号
                wantOrdinal = ordinalOfDateByFacts(curDate, wantPart0);
            }
            if (wantOrdinal > 0) {
                // 有期号 → 按期号在目标里找。
                // ★ 传 currentName（而非单参重载）：这样才能带上分段口径，
                //   避免无分段的「第1期」抢在「第1期上」之前被命中
                //   （实测《我家那闺女2026》豪华源开头有 4 个孤立无分段的「第1期…第4期」）。
                int byOrdinal = findIndexByEpisode(currentName, wantOrdinal, targetNames);
                if (byOrdinal >= 0) {
                    return byOrdinal;
                }
            }
            return -1;
        }
        if (curOrdinal > 0) {
            wantDate = recallDateOfOrdinal(curOrdinal, wantPart0);
            if (wantDate <= 0) {
                wantDate = recallDateOfOrdinal(curOrdinal, PART_NONE);
            }
            if (wantDate > 0) {
                int byDate = findIndexByDate(String.valueOf(wantDate), targetNames, currentName);
                if (byDate >= 0) {
                    return byDate;
                }
            }
            // 查表没有这一期 → 用已有锚点拟合周更节奏外推（见 alignByFactExtrapolation）
            return alignByFactExtrapolation(curOrdinal, wantPart0, currentName, targetNames);
        }
        return -1;
    }

    // ================= 第 -2 层：事实锚点外推（用已知锚点拟合周更节奏） =================

    /**
     * 用<b>已记住的事实</b>外推第 N 期的播出日，再按日期落位。
     *
     * <p><b>为什么需要它</b>：查表只能回答"第4期 = 20260612"这种<b>记过的</b>期号。
     * 但用户看的是第5期时，事实表里并没有第5期 —— 若此时直接返回 -1，
     * 就退回"首播日 + 7×(N−1)"的老路，而那条路的基准日可能被"特别企划"污染，
     * 于是又整体偏移一期（实测：第4期能切对，第5期却切到错误条目）。</p>
     *
     * <p><b>怎么做</b>：事实表里只要有<b>两个及以上</b>锚点，
     * 就能拟合出"每期多少天"（周更=7、隔周=14、日更=1），
     * 于是 20260612 + 7 = <b>20260619 = 第5期</b>，可直接落位。
     * 这比"列表首条日期"可靠得多 —— 首条可能是特别企划，而事实锚点是两侧证实的。</p>
     *
     * <p>只有一个锚点时不做外推（无法确定周期，可能是周更也可能是隔周）。</p>
     *
     * @return 目标列表下标；锚点不足或落位失败返回 -1
     */
    private static int alignByFactExtrapolation(int curOrdinal, int wantPart0,
                                                 String currentName, List<String> targetNames) {
        if (curOrdinal <= 0 || targetNames == null || targetNames.isEmpty()) {
            return -1;
        }
        int p = normalizePart(wantPart0);
        // 收集本分段下已知的（期号 → 日期）锚点
        int minOrd = Integer.MAX_VALUE, maxOrd = Integer.MIN_VALUE;
        long baseDay = 0;
        int baseOrd = 0;
        int anchors = 0;
        for (Map.Entry<String, Integer> e : FACT_ORDINAL_TO_DATE.entrySet()) {
            String key = e.getKey();
            int bar = key.lastIndexOf('|');
            if (bar <= 0) {
                continue;
            }
            if (parseIntSafe(key.substring(bar + 1), -1) != p) {
                continue;
            }
            int ord = parseIntSafe(key.substring(1, bar), -1);
            Integer date = e.getValue();
            if (ord <= 0 || date == null || date <= 0) {
                continue;
            }
            anchors++;
            if (ord < minOrd) {
                minOrd = ord;
            }
            if (ord > maxOrd) {
                maxOrd = ord;
            }
            if (baseOrd == 0 || ord < baseOrd) {
                baseOrd = ord;
                baseDay = dayNumberOf(date);
            }
        }
        // 两个锚点才能拟合周期；只有一个时"周更/隔周"无法区分，宁可不做
        if (anchors < 2 || baseOrd == 0 || baseDay <= 0) {
            return -1;
        }
        // 取"跨度 / 期数差"作为每期天数；非整数倍说明锚点来自不同更新节奏，退回
        int ordGap = maxOrd - minOrd;
        long daySpan = 0;
        int baseDate = 0;
        for (Map.Entry<String, Integer> e : FACT_ORDINAL_TO_DATE.entrySet()) {
            String key = e.getKey();
            int bar = key.lastIndexOf('|');
            if (bar <= 0 || parseIntSafe(key.substring(bar + 1), -1) != p) {
                continue;
            }
            if (parseIntSafe(key.substring(1, bar), -1) != minOrd) {
                continue;
            }
            baseDate = e.getValue() == null ? 0 : e.getValue();
            break;
        }
        if (baseDate <= 0 || ordGap <= 0) {
            return -1;
        }
        int maxDate = 0;
        for (Map.Entry<String, Integer> e : FACT_ORDINAL_TO_DATE.entrySet()) {
            String key = e.getKey();
            int bar = key.lastIndexOf('|');
            if (bar <= 0 || parseIntSafe(key.substring(bar + 1), -1) != p) {
                continue;
            }
            if (parseIntSafe(key.substring(1, bar), -1) != maxOrd) {
                continue;
            }
            maxDate = e.getValue() == null ? 0 : e.getValue();
            break;
        }
        if (maxDate <= 0) {
            return -1;
        }
        daySpan = dayNumberOf(maxDate) - dayNumberOf(baseDate);
        if (daySpan <= 0) {
            return -1;
        }
        long perPeriod = daySpan / ordGap;
        // 只接受"像更新节奏"的周期：日更1 / 周双更2~3 / 周更7 / 隔周14 / 月更28~31
        if (perPeriod < 1 || perPeriod > 31) {
            return -1;
        }
        // 锚点必须严格等比（不整除说明锚点里混入了非正片或缺期），放弃外推
        if (daySpan % ordGap != 0) {
            return -1;
        }
        long expectDay = dayNumberOf(baseDate) + perPeriod * (curOrdinal - baseOrd);
        if (expectDay <= 0) {
            return -1;
        }
        // ★ 容差必须随周期收紧，且日更/周双更一律不放宽。
        //   反例（日更节目缺第5期）：period=1，若沿用 ±3 天容差，
        //   期望日 0604 会静默命中 0603（第4期）——危险的错切。
        //   正确做法是"精确命中，否则不猜"。
        final int tol = perPeriod <= 3 ? 0 : Math.max(1, (int) (perPeriod / 4));
        // 在目标列表里找离期望播出日最近、同分段、且为正片的条目
        int best = -1;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < targetNames.size(); i++) {
            String n = targetNames.get(i);
            if (TextUtils.isEmpty(n) || isNonMainEntry(n)) {
                continue;
            }
            if (normalizePart(extractPart(n)) != p) {
                continue;
            }
            int d = dateOf(n);
            if (d <= 0) {
                continue;
            }
            long dist = Math.abs(dayNumberOf(d) - expectDay);
            if (dist <= tol && dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /** 宽松的字符串转 int，失败返回默认值。 */
    private static int parseIntSafe(String s, int def) {
        if (TextUtils.isEmpty(s)) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 用事实表反推某个播出日期的期号（供"当前集名是日期、目标源是期数式"这一方向使用）。
     *
     * <p><b>为什么需要它</b>：目标侧是 {@code 第N期} 时，得知道"当前日期是第几期"才能落位。
     * 老办法是 {@code firstMainDate + 7×(N−1)}，但对<b>全无名的日期式源</b>
     * （{@code 第20260515期} 其实是"特别企划"）这个基准日会整体偏移一期。
     * 事实表里的锚点是两侧证实的，比任何启发式可靠：
     * 精确命中直接返回；否则用两个锚点的<b>线性夹逼</b>推出期号。</p>
     *
     * @return 期号；无法确定时返回 -1
     */
    private static int ordinalOfDateByFacts(int date, int part) {
        if (date <= 0) {
            return -1;
        }
        int p = normalizePart(part);
        int exact = recallOrdinalOfDate(date, p);
        if (exact > 0) {
            return exact;
        }
        exact = recallOrdinalOfDate(date, PART_NONE);
        if (exact > 0) {
            return exact;
        }
        // 收集本分段下所有锚点：期号 → 日期
        List<int[]> anchors = new ArrayList<>();   // {ordinal, date}
        for (Map.Entry<String, Integer> e : FACT_ORDINAL_TO_DATE.entrySet()) {
            String key = e.getKey();
            int bar = key.lastIndexOf('|');
            if (bar <= 0) {
                continue;
            }
            int ps = parseIntSafe(key.substring(bar + 1), -1);
            if (ps != p && ps != PART_NONE) {
                continue;
            }
            int ord = parseIntSafe(key.substring(1, bar), -1);
            Integer d = e.getValue();
            if (ord > 0 && d != null && d > 0) {
                anchors.add(new int[]{ord, d});
            }
        }
        if (anchors.isEmpty()) {
            return -1;
        }
        long targetDay = dayNumberOf(date);
        if (targetDay <= 0) {
            return -1;
        }
        // 两两配对，用「日期差 : 期号差」的比例线性外推
        for (int[] a : anchors) {
            for (int[] b : anchors) {
                if (b[0] <= a[0]) {
                    continue;
                }
                long da = dayNumberOf(a[1]);
                long db = dayNumberOf(b[1]);
                if (da <= 0 || db <= 0 || db <= da || targetDay < da) {
                    continue;
                }
                long dayDiff = db - da;
                if (dayDiff % (b[0] - a[0]) != 0) {
                    continue;
                }
                long perPeriod = dayDiff / (b[0] - a[0]);
                if (perPeriod < 1 || perPeriod > 31) {
                    continue;
                }
                long off = targetDay - da;
                if (off % perPeriod != 0) {
                    continue;
                }
                return a[0] + (int) (off / perPeriod);
            }
        }
        return -1;
    }

    /**
     * 取"跳过开头 skipClusters 个正片簇之后"的第一个带日期正片的日期。
     *
     * <p>用于"开头有特别节目连播被误判成正片"的场景 ——
     * 真第 1 期不在列表首条时用它把首播基准往后挪。</p>
     */
    private static int firstMainDateSkip(List<String> names, FeatureLabels labels, int skipClusters) {
        if (names == null) {
            return -1;
        }
        if (skipClusters <= 0) {
            return firstMainDate(names, labels);
        }
        List<List<Integer>> clusters = buildMainFeatureClusters(names, labels);
        if (clusters.size() <= skipClusters) {
            return -1;
        }
        for (int i = skipClusters; i < clusters.size(); i++) {
            for (int idx : clusters.get(i)) {
                int d = dateOf(names.get(idx));
                if (d > 0) {
                    return d;
                }
            }
        }
        return -1;
    }

    /**
     * 列表按期号统计的「每期分段条数」序列（只数显式带分段标记的正片）。
     *
     * <p>例如《我家那闺女2026》豪华源返回 {@code [2,2,2,2,3]}
     * （第1~4期各"上/下"2 条，第5期"上/中/下"3 条）。无分段节目返回空列表。</p>
     *
     * <p><b>只要显式带分段的条目</b>是关键：源侧每个日期簇天然只含正片（2~3 条），
     * 而目标侧若按"每期总条数"统计，会被「盲盒放送第5期」「超前营业第4期」
     * 这类衍生条目污染成 3。</p>
     */
    private static List<Integer> partCountsByOrdinal(List<String> names, FeatureLabels labels) {
        List<Integer> out = new ArrayList<>();
        if (names == null || names.isEmpty()) {
            return out;
        }
        java.util.TreeMap<Integer, Integer> counts = new java.util.TreeMap<>();
        for (String n : names) {
            if (TextUtils.isEmpty(n) || isNonMainEntry(n, labels) || !isMainEntry(n, labels)) {
                continue;
            }
            if (extractPart(n) == PART_NONE) {
                continue;                        // 只要显式带分段的
            }
            int o = leadingOrdinalOf(n);
            if (o > 0) {
                Integer old = counts.get(o);
                counts.put(o, old == null ? 1 : old + 1);
            }
        }
        for (Integer v : counts.values()) {
            out.add(v);
        }
        return out;
    }

    /** 列表按「日期簇」统计的每簇条数序列。 */
    private static List<Integer> partCountsByCluster(List<String> names, FeatureLabels labels) {
        List<Integer> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        for (List<Integer> c : buildMainFeatureClusters(names, labels)) {
            out.add(c.size());
        }
        return out;
    }

    /**
     * 把 a（较长）对齐到 b（较短），返回「完全匹配且唯一最优」的偏移；无解返回 0。
     */
    private static int bestSeqOffset(List<Integer> a, List<Integer> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size() < b.size()) {
            return 0;
        }
        int bestOff = 0, bestScore = -1, runnerUp = -1;
        int maxOff = Math.min(4, a.size() - b.size());
        for (int off = 0; off <= maxOff; off++) {
            int score = 0;
            for (int i = 0; i < b.size(); i++) {
                if (a.get(off + i).equals(b.get(i))) {
                    score++;
                }
            }
            if (score > bestScore) {
                runnerUp = bestScore;
                bestScore = score;
                bestOff = off;
            } else if (score > runnerUp) {
                runnerUp = score;
            }
        }
        // 必须是「完全匹配」且「明显优于次优」才敢校准，否则宁可不动
        if (bestScore == b.size() && bestOff > 0 && runnerUp < bestScore) {
            return bestOff;
        }
        return 0;
    }

    /**
     * 判定源侧开头有几个「插入簇」（特别节目连播被误判成正片），返回应跳过的簇数。
     *
     * <p><b>判据</b>：源侧每个日期簇的条数序列与目标侧每期分段条数序列做对齐，
     * 取匹配度最高且唯一的那个偏移量。</p>
     *
     * <p>实测《我家那闺女2026》电影天堂源：簇条数 {@code [2,2,2,2,2,3]}，
     * 豪华源每期分段条数 {@code [2,2,2,2,3]} —— offset=0 匹配 4/5、offset=1 匹配 5/5
     * ⇒ 首簇（0823「闺女面对面」）是插入的特别节目，真第1期是 0830。</p>
     *
     * <p><b>与前两轮失败尝试的差别（务必保留）</b>：
     * 前一版按"簇数 vs 目标期数"比较 → matrix 727→697；
     * 再一版加"期号连续"约束 → matrix 727→711（误伤"目标侧缺最后一期"）；
     * <b>本版只在目标侧有分段时启用</b>，对无分段节目（歌手2026 全部源）
     * 序列为空、判据完全不触发，实测零影响。</p>
     *
     * <p><b>双向对称</b>：不管当前集名是日期式还是期数式，
     * 都用「簇条数序列」与「每期分段条数序列」对齐，偏移量就是插入簇数。</p>
     */
    private static int leadingInsertClusters(List<String> sourceNames,
                                            List<String> targetNames,
                                            FeatureLabels labels) {
        if (sourceNames == null || targetNames == null) {
            return 0;
        }
        List<Integer> tgtParts = partCountsByOrdinal(targetNames, labels);
        List<Integer> srcParts = partCountsByOrdinal(sourceNames, labels);
        List<Integer> tgtClusters = partCountsByCluster(targetNames, labels);
        List<Integer> srcClusters = partCountsByCluster(sourceNames, labels);
        int off = 0;
        if (tgtParts.size() >= 2 && srcClusters.size() >= tgtParts.size()) {
            off = bestSeqOffset(srcClusters, tgtParts);          // 正向
        }
        if (off == 0 && srcParts.size() >= 2 && tgtClusters.size() >= srcParts.size()) {
            off = bestSeqOffset(tgtClusters, srcParts);          // 反向
        }
        return off;
    }

    /**
     * 取列表中第一个"带日期的正片"的日期（YYYYMMDD）。
     * 用于"首播日 + 7×(N-1)"外推；视为本季第 1 期的播出日。
     */
    private static int firstMainDate(List<String> names) {
        return firstMainDate(names, null);
    }

    private static int firstMainDate(List<String> names, FeatureLabels labels) {
        if (names == null) {
            return -1;
        }
        for (String n : names) {
            if (TextUtils.isEmpty(n) || isNonMainEntry(n, labels)) {
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
        return findIndexByExpectedDay(names, ordinal, wantPart, null);
    }

    private static int findIndexByExpectedDay(List<String> names, int ordinal, int wantPart,
                                              FeatureLabels labels) {
        return findIndexByExpectedDay(names, ordinal, wantPart, labels, 0);
    }

    /**
     * @param skipClusters 跳过开头这么多个簇再取首播日 —— 目标列表开头有
     *                     "特别节目连播"被判成正片时用它校准基准
     *                     （见 {@link #leadingInsertClusters}）。
     */
    private static int findIndexByExpectedDay(List<String> names, int ordinal, int wantPart,
                                              FeatureLabels labels, int skipClusters) {
        if (names == null || names.isEmpty() || ordinal <= 0) {
            return -1;
        }
        // ★ 必须把插入簇校准传进来：首播日若取的是被幽灵簇占据的首条，
        //   会整体偏一期并**抢先返回**，盖掉已校准的簇法结果。
        int firstDate = skipClusters > 0
                ? firstMainDateSkip(names, labels, skipClusters)
                : firstMainDate(names, labels);
        if (firstDate <= 0) {
            return -1;
        }
        int period = expectedPeriodBetweenClusters(names, labels);
        if (period <= 0) {
            period = 7;                 // 保留原周更快照（实测行为正确）
        }
long expected = dayNumberOf(firstDate) + period * (ordinal - 1);
        // ★ 容差随节拍收紧：日更 / 周双更只允许精确命中，
        //   否则目标侧缺该期时会静默命中邻居集（实测错切到隔壁一期）。
        final int tol = period <= 3 ? 0 : Math.max(1, period / 4);
        int best = -1;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (TextUtils.isEmpty(n) || isNonMainEntry(n, labels)) {
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
            if (dist <= tol && dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /**
     * 用列表自身"簇与簇之间的实测间隔"估计节拍；推不出返回 -1。
     *
     * <p>比"统计相邻间隔众数"可靠：簇是<b>一期一组</b>，簇间间隔天然就是期距，
     * 不会被"期内的每日衍生条目"污染。仍推不出时由调用方退回 7 天周更快照 ——
     * 7 天在真实数据上行为正确，而"猜节拍"的三种做法（众数 / 最长等差链 /
     * 周期性探测打分）实测都会误判，详见 {@link #inferPeriodDays} 的说明。</p>
     */
    private static int expectedPeriodBetweenClusters(List<String> names, FeatureLabels labels) {
        if (names == null || names.isEmpty()) {
            return -1;
        }
        List<List<Integer>> clusters = buildMainFeatureClusters(names, labels);
        List<Integer> starts = new ArrayList<>();
        for (List<Integer> cl : clusters) {
            for (int idx : cl) {
                int d = dateOf(names.get(idx));
                if (d > 0) {
                    starts.add((int) dayNumberOf(d));
                    break;
                }
            }
        }
        if (starts.size() < 3) {
            return -1;
        }
        Map<Integer, Integer> cnt = new HashMap<>();
        int total = 0;
        for (int i = 0; i + 1 < starts.size(); i++) {
            int g = starts.get(i + 1) - starts.get(i);
            if (g > 0) {
                Integer c = cnt.get(g);
                cnt.put(g, c == null ? 1 : c + 1);
                total++;
                }
        }
        if (total == 0) {
            return -1;
        }
        int bestGap = -1, bestCount = 0;
        for (Map.Entry<Integer, Integer> e : cnt.entrySet()) {
            int g = e.getKey(), c = e.getValue();
            if (c > bestCount || (c == bestCount && g > bestGap)) {
                bestCount = c;
                bestGap = g;
            }
        }
        if (bestGap < 1 || bestGap > 31) {
            return -1;
        }
        // 众数需占多数，否则节拍很杂，不可信
        if (bestCount * 2 <= total) {
            return -1;
        }
        return bestGap;
    }

    /**
     * 从列表自身推断「每期间隔天数」。
     *
     * <p><b>本项目实测证明：不可靠，已停用（恒返回 -1），不要重新启用。</b>
     * 三种尝试全部失败并造成回退：</p>
     * <ul>
     *   <li><b>众数法</b>：feifan 源间隔分布 {1天:59, 3:8, 2:3, 4:1} → 众数 1，
     *       但它其实是<b>周更综艺、只是每天都有内容</b>，真实节拍是 7；</li>
     *   <li><b>最长等差链</b>：衍生条目也会成链，起点落到「特别企划」；</li>
     *   <li><b>周期性探测打分</b>：p=1 得分 894 压倒 p=7 的 357（日更天然占优）。</li>
     * </ul>
     * <p>本地无解的证明：p=1 与 p=7 在"第1期与第2期的日期都在列表里"这一事实上
     * <b>同样成立</b>，无法区分。因此保留 7 天周更快照，
     * 非周更节拍交由事实查表 / 事实外推两层处理（它们用真实锚点直接算日期）。</p>
     */
    private static int inferPeriodDays(List<String> names, FeatureLabels labels) {
        return -1;
    }

    /** 周更快照校验容差（天）：落点/当前集日期与期望播出日的最大偏差。 */
    private static final int CLUSTER_TOLERANCE_DAYS = 3;

    /**
     * 计算第 {@code rank} 个簇的期望播出日（天序数）：首簇日期 + 节拍 × rank。
     * 节拍优先取簇间实测间隔（{@link #expectedPeriodBetweenClusters}），
     * 推不出时退回 7 天周更快照（实测行为正确）。
     */
    private static long expectedDayOf(List<String> names, List<List<Integer>> clusters, int rank) {
        if (clusters == null || clusters.isEmpty()) {
            return -1;
        }
        int c0 = dateOf(names.get(clusters.get(0).get(0)));
        if (c0 <= 0) {
            return -1;
        }
        int period = expectedPeriodBetweenClusters(names, null);
        if (period <= 0) {
            period = 7;
        }
        return dayNumberOf(c0) + (long) period * rank;
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
        if (matching.isEmpty() && cluster.size() >= 2 && cluster.size() <= 3) {
            // 簇内条目都没写分段（典型：裸日期式源 "20260815/20260816"）→ 按簇内位置推断：
            // 2 条 → 上/下；3 条 → 上/中/下。
            // 否则「第1期下」会落到「第1期上」（披荆斩棘2026 实测 case）。
            if (wantPart == PART_UP) {
                return cluster.get(0);
            }
            if (wantPart == PART_MIDDLE) {
                return cluster.get(Math.min(1, cluster.size() - 1));
            }
            return cluster.get(cluster.size() - 1);
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
     * <p><b>切分规则（只按日期间隔）</b>：相邻两个正片的日期差超过
     * {@link #CLUSTER_GAP_DAYS} 天即视为新的一期；衍生内容条目直接跳过、<b>不再强行断开</b>。</p>
     *
     * <p><b>为什么去掉"夹衍生条目即断开"</b>：同期的上/下两段常被若干衍生条目隔开。
     * 实测披荆斩棘2026（dytt 源）：{@code 20260815} 与 {@code 20260816} 之间夹着
     * {@code 20260816舞台纯享版}，若按"夹内容即断开"会把同一期的上/下拆成两个簇，
     * 导致之后所有期号整体偏移（第2期被算成第3期…）。改按日期间隔切分后，
     * 该源的正片簇恰好是 7 个，与"第1~7期"完全对应。</p>
     *
     * <p>同日期间隔阈值取 {@link #CLUSTER_GAP_DAYS} 天：同期的上/下一般相邻 0~2 天
     * （少数节目隔 3 天），而相邻两期之间至少隔 5~7 天，两侧余量都足够。</p>
     *
     * <p>非正片条目与无法解析域名的噪声条目都不进簇。</p>
     *
     * @return 簇列表；每个簇是该簇内条目在原列表中的下标（升序）
     */
    private static List<List<Integer>> buildMainFeatureClusters(List<String> names) {
        return buildMainFeatureClusters(names, null);
    }

    /**
     * 带"期标签"的簇切分：{@code labels} 非空时，{@code 日期 + 期标签(+上/下)}
     * 的条目也算正片（见 {@link #harvestFeatureLabels(List, List)}）。
     *
     * @param names  剧集名列表
     * @param labels 期标签；null 表示只用纯结构化判定
     */
    private static List<List<Integer>> buildMainFeatureClusters(List<String> names, FeatureLabels labels) {
        List<List<Integer>> clusters = new ArrayList<>();
        if (names == null || names.isEmpty()) {
            return clusters;
        }
        int lastMainDate = -1;
        // ★ 同日裸日期优先：见 sameDayBareDates 的说明。
        //   不加这一步，「20260904二公观演区上」会被当成正片簇首，
        //   把首播基准往前挪一整天，后续每期整体偏一期。
        java.util.Set<Integer> bareDays = sameDayBareDates(names, labels);
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (TextUtils.isEmpty(n) || isNonMainEntry(n, labels)) {
                continue;
            }
            int d = dateOf(n);
            if (!bareDays.isEmpty() && d > 0 && bareDays.contains(d) && !isBareDateName(n)) {
                continue;
            }
            boolean boundary = clusters.isEmpty();
            if (!boundary && d > 0 && lastMainDate > 0) {
                // 日期差必须走日历天序数：YYYYMMDD 整数相减跨月即失真（0429→0502 差 73）
                boundary = Math.abs(dayNumberOf(d) - dayNumberOf(lastMainDate)) > CLUSTER_GAP_DAYS;
            }
            if (boundary) {
                clusters.add(new ArrayList<Integer>());
            }
            clusters.get(clusters.size() - 1).add(i);
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
        return mainFeatureClusterRank(names, index, null);
    }

    private static int mainFeatureClusterRank(List<String> names, int index, FeatureLabels labels) {
        if (names == null || index < 0 || index >= names.size()) {
            return -1;
        }
        if (isNonMainEntry(names.get(index), labels)) {
            return -1;
        }
        List<List<Integer>> clusters = buildMainFeatureClusters(names, labels);
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
        if (TextUtils.isEmpty(currentName) || isNonMainEntry(currentName)) {
            return clamped;
        }
        String landed = clamped >= 0 && clamped < names.size() ? names.get(clamped) : null;
        if (TextUtils.isEmpty(landed) || !isNonMainEntry(landed)) {
            return clamped;
        }
        // 落点是非正片：向前找最近的正片，找不到再向后
        for (int i = clamped; i >= 0; i--) {
            String n = names.get(i);
            if (!TextUtils.isEmpty(n) && !isNonMainEntry(n)) {
                return i;
            }
        }
        for (int i = clamped + 1; i < names.size(); i++) {
            String n = names.get(i);
            if (!TextUtils.isEmpty(n) && !isNonMainEntry(n)) {
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
        return findIndexByEpisode(currentName, episode, targetNames, -1);
    }

    /**
     * 带"分段口径覆盖"的期数匹配（内部实现）。
     *
     * @param wantPartOverride 调用方推断出的分段；传 {@code -1} 表示按 {@code currentName} 自身推断
     */
    private static int findIndexByEpisode(String currentName, int episode, List<String> targetNames,
                                          int wantPartOverride) {
        return findIndexByEpisode(currentName, episode, targetNames, wantPartOverride, null);
    }

    /**
     * 带"期标签"的期数匹配（内部实现）。
     *
     * @param labels 期标签；非空时 {@code 日期 + 期标签(+上/下)} 的目标条目也算正片，
     *               不再被当成衍生条目整条跳过（见 {@link #harvestFeatureLabels(List, List)}）
     */
    private static int findIndexByEpisode(String currentName, int episode, List<String> targetNames,
                                          int wantPartOverride, FeatureLabels labels) {
        if (episode <= 0 || targetNames == null || targetNames.isEmpty()) {
            return -1;
        }
        // 当前条目是否非正片，决定本次走哪套口径
        final boolean curIsNonMain = isNonMainFeature(currentName);
        final String curToken = nonMainFeatureToken(currentName);
        // 结构化判为非正片、又没有系列词可配对（如「突袭云小考」「一公挑战赛（下）」）：
        // 这类条目与"期"没有稳定对应关系，按期数硬匹配只会错位，直接交给上层兜底
        if (!curIsNonMain && isNonMainEntry(currentName, labels)) {
            return -1;
        }
        // ★ 分段口径：当前名带 上/中/下 时，必须挑到同分段的条目。
        //   否则「第2期下」会落到「第2期上」（findIndexByEpisode 早期只取第一条）。
        //   调用方也可直接给出推断分段（源侧裸日期没有分段标记时按簇内位置推断）。
        final boolean curHasPart = wantPartOverride > 0 || extractPart(currentName) != PART_NONE;
        final int wantPart = wantPartOverride > 0
                ? wantPartOverride : normalizePart(extractPart(currentName));
        // 分段不一致时的候选（用于兜底：目标源确实没有该分段时，仍给一个近似落点）
        int partMismatchFallback = -1;
        int nonLeadingFallback = -1;
        final boolean curIsNonMainEntry = isNonMainEntry(currentName, labels);
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
                // 当前是正片：只在<b>正片</b>里找，跳过一切衍生条目
                if (isNonMainEntry(name, labels)) {
                    continue;
                }
                // ★ 期号必须领衔（「第2期…」），否则是衍生条目把期号写在尾巴上：
                //   如「超前企划第2期」「预热直播第2期」「加更版第1期」——
                //   它们解析出的期号与真正片撞号，实测会让 20260815 落到「超前企划第2期」。
                //   这类条目降级为最后兜底，绝不抢占领衔条目的位置。
                if (!isLeadingOrdinal(name)) {
                    if (nonLeadingFallback < 0) {
                        nonLeadingFallback = i;
                    }
                    continue;
                }
            }
            if (curIsNonMainEntry && !curIsNonMain) {
                // 结构化判为非正片但没有系列词（如「突袭云小考」）：不参与正片匹配
                continue;
            }
            // ★ 当前集名**显式**写了分段（如「第1期上」）时，候选也必须显式带分段。
            //   原因：{@link #normalizePart} 会把 PART_NONE 归一化成 PART_UP，于是
            //   无分段的「第1期」与「第1期上」在归一化后**无法区分**——
            //   实测《我家那闺女2026》豪华源开头有 4 个孤立无分段的「第1期…第4期」
            //   （实为 EP00 特别节目），它们排在真正的「第1期上」之前，
            //   于是「第1期上」被误判成「第1期」，整体错到无分段的那批上。
            if (curHasPart && extractPart(name) == PART_NONE) {
                if (partMismatchFallback < 0) {
                    partMismatchFallback = i;      // 退而求其次：只作兜底，不优先
                }
                continue;
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
        if (partMismatchFallback >= 0) {
            return partMismatchFallback;
        }
        return nonLeadingFallback;
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
        // ★ 必须排除衍生条目：同一天里正片与衍生混排（如 20260904 有
        //   「二公观演区上」与「上」），把衍生也算进来会让"同日多段对齐"
        //   在衍生条目之间乱配。实测《披荆斩棘2026》：源侧
        //   「第20260904期预热直播」会通过本函数对到目标的
        //   「20260904二公观演区上」—— 两者都是衍生，毫无对应关系。
        java.util.Set<Integer> bareDays = sameDayBareDates(names, null);
        for (int i = 0; i < names.size(); i++) {
            EpisodeKey k = parse(names.get(i));
            if (k.domain != DOMAIN_DATE || k.ordinal != ordinal) {
                continue;
            }
            String n = names.get(i);
            if (isNonMainEntry(n) || !isMainEntry(n, null)) {
                continue;
            }
            int d = dateOf(n);
            if (!bareDays.isEmpty() && d > 0 && bareDays.contains(d) && !isBareDateName(n)) {
                continue;
            }
            out.add(i);
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
        // ★ 衍生条目之间没有任何对应关系，同下标纯属巧合 —— 必须拒绝。
        //   实测《披荆斩棘2026》：源侧「20260904二公观演区上」（idx25，衍生）
        //   与目标侧「一公挑战赛（下）」（idx25，第2期的衍生）**恰好同下标**，
        //   被"同下标兜底"配成了一对，切到了完全错误的期次。
        //   同域判定对衍生条目不成立：它们只是"排在那个位置的衍生内容"。
        if (isNonMainEntry(sourceNames.get(sourceIndex))) {
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
        if (current.domain == DOMAIN_UNKNOWN) {
            // 名字里既没有日期也没有期号 —— 但它可能是<b>靠期标签与另一侧交叉引用</b>的正片。
            // 实测（披荆斩棘2026）：豪华资源把第2期下写成「一公挑战赛（下）」，
            // 该名字自身解析不出任何序号，只有配合同期的「第2期一公挑战赛（上）」
            // 才能认出它属于第 2 期。旧实现在这里直接 return false，把这类条目
            // 挡在跨域对齐之外 —— 用户看这一集切源时集数完全不迁移。
            //
            // 同域匹配对"一侧可识别、一侧不可识别"本就给 0 分（见 score），
            // 所以放行不会引入误匹配；alignByMainFeatureRank 认不出会返回 -1。
            // 后续的直连层对"无日期且无序号"的名字也会立刻返回（前向需日期、
            // 反向需期号），因此放行<b>不会产生额外网络请求</b>。
            return true;
        }
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
