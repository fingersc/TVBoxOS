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
            return normalizePart(cur.part) == normalizePart(tgt.part) ? 80 : 0;
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
     *   <li><b>求旧源当前集的"实际后缀"</b>（{@link #resolvePart}）：
     *       有后缀就用它本身；<b>无后缀</b>时用同日其它条目已用的后缀反推；</li>
     *   <li><b>在新源同日组内按序查找</b>：
     *       先找<b>同后缀</b>条目 → 命中即用；
     *       找不到 → 退而选择<b>无后缀</b>条目 → 命中即用；
     *       都没有 → 放弃（返回 -1，由上层跳过该源）。</li>
     * </ol>
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
        if (sourceGroup.size() < 2) {
            // 当天只有一条：不存在"多段"场景，交由 findIndex 处理
            return -1;
        }

        // --- 新源：与当前集同日的分组 ---
        List<Integer> targetGroup = sameDayGroup(targetNames, current.ordinal);
        if (targetGroup.isEmpty()) {
            // 新源没有当天的条目 —— 是真的缺集，交给上层跳过
            return -1;
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
        if (!isMonotonicAscending(targetNames)) {
            // 新源顺序无法确认单调递增，按序对齐不可信
            return -1;
        }
        // 两源期数排列顺序一致时，同下标即为同一期
        return sourceIndex < targetNames.size() ? sourceIndex : -1;
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
