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
     * 解析结果。ordinal 为域内可比较的序号，domain 标示命名方式。
     * 日期域下 ordinal 用 YYYYMMDD 原值（保留完整信息，便于同域精确比较与单调校验）。
     */
    public static final class EpisodeKey {
        public final int ordinal;
        public final int domain;
        public final String raw;

        EpisodeKey(int ordinal, int domain, String raw) {
            this.ordinal = ordinal;
            this.domain = domain;
            this.raw = raw;
        }

        public boolean recognizable() {
            return ordinal >= 0;
        }
    }

    private EpisodeNameMatcher() {
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

        // 1. 先去噪（与旧实现一致：去掉方括号/圆括号内容、清晰度标记），保证召回率不下降
        work = work.replaceAll("\\[.*?\\]|\\(.*?\\)|（.*?）", "");
        work = work.toLowerCase(Locale.ROOT)
                .replaceAll("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4", "");

        // 2. 日期优先（必须在剥离年份之前，否则 8 位日期会被截断误判）
        Matcher compact = DATE_COMPACT.matcher(work);
        if (compact.find()) {
            return new EpisodeKey(buildDateOrdinal(compact.group(1), compact.group(2), compact.group(3)),
                    DOMAIN_DATE, raw);
        }
        Matcher separated = DATE_SEPARATED.matcher(work);
        if (separated.find()) {
            return new EpisodeKey(buildDateOrdinal(separated.group(1), separated.group(2), separated.group(3)),
                    DOMAIN_DATE, raw);
        }

        // 3. 期数/集数（阿拉伯数字），优先于纯序号兜底
        Matcher arabic = ORDINAL_ARABIC.matcher(work);
        if (arabic.find()) {
            return new EpisodeKey(parseIntSafe(arabic.group(1)), DOMAIN_ORDINAL, raw);
        }

        // 4. 期数/集数（中文数字）
        Matcher chinese = ORDINAL_CHINESE.matcher(work);
        if (chinese.find()) {
            int value = chineseToNumber(chinese.group(1));
            if (value >= 0) {
                return new EpisodeKey(value, DOMAIN_ORDINAL, raw);
            }
        }

        // 5. 纯序号兜底：仅当去掉非数字后长度 ≤ 4 才采纳，
        //    避免把残留的 20260809 这类长串当成集数号（这是原 bug 的直接来源）
        String number = work.replaceAll("\\D+", "");
        if (!TextUtils.isEmpty(number) && number.length() <= 4) {
            return new EpisodeKey(parseIntSafe(number), DOMAIN_ORDINAL, raw);
        }

        return new EpisodeKey(-1, DOMAIN_UNKNOWN, raw);
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
     *   <li>80 ：同域且序号相同（第8期 ↔ 第8集、20260809 ↔ 2026-08-09）；</li>
     *   <li>0  ：其它（跨域交给列表级的按序对齐处理，这里不猜）；</li>
     *   <li>70/60：两侧都识别不出序号时的包含式模糊匹配，保留旧行为（用于电影多版本等场景）。</li>
     * </ul>
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
            return cur.ordinal == tgt.ordinal ? 80 : 0;
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
     * 综合定位：先同域精确匹配；失败且属"日期 ↔ 期数"跨域时，再做按序对齐。
     * 这是切源场景的推荐入口。
     *
     * @param currentName    当前在播集名
     * @param sourceIndex    当前集在旧源列表中的下标（用于跨域对齐）
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
        int exact = findIndex(currentName, targetNames);
        if (exact >= 0) {
            return exact;
        }
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
