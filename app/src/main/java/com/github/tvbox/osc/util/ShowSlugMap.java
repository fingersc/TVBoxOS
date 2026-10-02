package com.github.tvbox.osc.util;

import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 综艺节目名 → 站点 slug 的离线映射（内置，零依赖）。
 *
 * <p>背景：zyshow.net 的节目页 URL 形如 {@code /dl/{拼音slug}/}，而 App 侧只有中文节目名，
 * 无法可靠地把中文转成站点 slug（多音字、生僻字、特殊符号都会转错）。
 * 因此预置一张 125 条的映射表，由 {@code tools/gen_slug_map.py} 生成。</p>
 *
 * <p><b>降级</b>：映射文件缺失/损坏时映射为空，{@link #lookup} 返回空串，
 * 调用方自然跳过直连、回退本地匹配，<b>不影响切源</b>。</p>
 */
public final class ShowSlugMap {

    private static volatile Map<String, String> name2slug = null;
    private static volatile boolean loaded = false;

    private ShowSlugMap() {
    }

    /**
     * 加载映射文件（JSON）。
     *
     * @param jsonFile 形如 {@code <filesDir>/episode/slug_map.json}
     * @return 是否加载到有效数据
     */
    public static synchronized boolean load(File jsonFile) {
        if (jsonFile == null || !jsonFile.exists() || !jsonFile.isFile()) {
            name2slug = new HashMap<>();
            loaded = true;
            return false;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(jsonFile), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return parseJson(sb.toString());
        } catch (Throwable t) {
            name2slug = new HashMap<>();
            loaded = true;
            return false;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 直接注入 JSON 字符串（便于测试）。 */
    public static synchronized boolean parseJson(String json) {
        Map<String, String> map = new HashMap<>();
        try {
            if (!TextUtils.isEmpty(json)) {
                JSONObject root = new JSONObject(json);
                JSONObject n2s = root.optJSONObject("name_to_slug");
                if (n2s != null) {
                    Iterator<String> keys = n2s.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        String v = n2s.optString(k, "");
                        if (!TextUtils.isEmpty(k) && !TextUtils.isEmpty(v)) {
                            map.put(k.trim(), v.trim());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        name2slug = map;
        loaded = true;
        return !map.isEmpty();
    }

    /** 是否已加载且有数据。 */
    public static boolean isReady() {
        return loaded && name2slug != null && !name2slug.isEmpty();
    }

    /**
     * 按节目名查 slug。**只做可信匹配**：精确、去空格、去尾部括号。
     *
     * <p><b>为什么不做包含式模糊匹配</b>：曾出过真实的错配事故——
     * 「披荆斩棘2026」不在表里，却被「披荆斩棘」包含命中，于是直连去抓了
     * <b>另一个节目</b>的页面，拿到完全无关的期数，最终把用户切到了不相干的集上。
     * 这类"张冠李戴"比"查不到"危害大得多（查不到只是退回本地兜底，错配会污染播放记录）。</p>
     *
     * <p>需要宽松匹配的旁路请用 {@link #lookupFuzzy}，它要求调用方做二次校验。</p>
     *
     * @param showName 节目中文名（如"忙忙碌碌寻宝藏"）
     * @return slug（如"mangmangluluxunbaozang"）；查不到返回空串
     */
    public static String lookup(String showName) {
        String hit = lookupStrict(showName);
        if (!TextUtils.isEmpty(hit)) {
            return hit;
        }
        // 变体3：去掉尾部"年份/季"数字后缀，如 "披荆斩棘2026" -> "披荆斩棘"
        // 仅当去掉后能在表里精确命中才采纳，且**必须**由调用方二次校验页面标题
        return lookupByYearStrip(showName);
    }

    /**
     * 可信匹配（子集）：精确 → 去空格 → 去尾部括号后缀。
     *
     * <p>这三类命中都直接命中表中的完整节目名，不存在"截断成另一个节目"的风险，
     * 因此调用方可以直接采信，无需页面标题二次校验。</p>
     *
     * @return slug；无可信命中返回空串
     */
    static String lookupStrict(String showName) {
        if (TextUtils.isEmpty(showName)) {
            return "";
        }
        Map<String, String> map = name2slug;
        if (map == null || map.isEmpty()) {
            return "";
        }
        String name = showName.trim();
        String hit = map.get(name);
        if (hit != null) {
            return hit;
        }
        // 变体1：去所有空格
        String noSpace = name.replaceAll("\\s+", "");
        hit = map.get(noSpace);
        if (hit != null) {
            return hit;
        }
        // 变体2：去掉尾部括号后缀，如 "五十公里桃花坞(第2季)"
        String stripped = stripSuffix(noSpace);
        if (!stripped.equals(noSpace)) {
            hit = map.get(stripped);
            if (hit != null) {
                return hit;
            }
        }
        return "";
    }

    /**
     * 去年份后缀匹配（**不可信**）：如「披荆斩棘2026」→「披荆斩棘」。
     *
     * <p>剥离后命中的很可能是<b>同名的另一季节目</b>，直连抓到的页面未必对应当前节目，
     * 调用方<b>必须</b>用页面标题做二次校验后方可采信。</p>
     *
     * @return slug；未命中返回空串
     */
    static String lookupByYearStrip(String showName) {
        if (TextUtils.isEmpty(showName)) {
            return "";
        }
        Map<String, String> map = name2slug;
        if (map == null || map.isEmpty()) {
            return "";
        }
        String noSpace = showName.trim().replaceAll("\\s+", "");
        String noYear = stripYearSuffix(noSpace);
        if (noYear.equals(noSpace)) {
            return "";
        }
        String hit = map.get(noYear);
        return hit == null ? "" : hit;
    }

    /**
     * 宽松匹配（**含包含式，不可信**）：仅供需要额外校验的调用方使用。
     *
     * <p>返回值可能来自"另一个节目"，调用方<b>必须</b>用站点页面标题等权威信息
     * 做二次校验后才能采信，否则会重演「披荆斩棘2026」错配事故。</p>
     *
     * @return slug；无可信匹配时返回空串
     */
    public static String lookupFuzzy(String showName) {
        String exact = lookup(showName);
        if (!TextUtils.isEmpty(exact)) {
            return exact;
        }
        if (TextUtils.isEmpty(showName)) {
            return "";
        }
        Map<String, String> map = name2slug;
        if (map == null || map.isEmpty()) {
            return "";
        }
        String noSpace = showName.trim().replaceAll("\\s+", "");
        String noYear = stripYearSuffix(noSpace);
        for (Map.Entry<String, String> e : map.entrySet()) {
            String key = e.getKey();
            if (key.length() < 3) {
                continue;
            }
            if (noSpace.contains(key) || key.contains(noSpace)) {
                return e.getValue();
            }
            // 剥掉年份后缀后再比一次：「披荆斩棘2026」vs 表里的「披荆斩棘」
            if (!noYear.equals(noSpace)
                    && (noYear.contains(key) || key.contains(noYear))) {
                return e.getValue();
            }
        }
        return "";
    }

    /** 去掉尾部形如 "(...)"、"（...）"、"_..." 的后缀。 */
    private static String stripSuffix(String s) {
        String r = s.replaceAll("[（(][^）)]*[）)]\\s*$", "");
        r = r.replaceAll("[_\\-·].*$", "");
        return r.trim();
    }

    /**
     * 去掉尾部的"年份 / 第N季 / 季数"后缀，用于「披荆斩棘2026」→「披荆斩棘」这类变体。
     *
     * <p>只剥离<b>尾部</b>的纯数字年份、或「第N季」结构，不做任何中间截断，
     * 避免把正常的节目名截成另一个节目。</p>
     * <p>例：{@code 披荆斩棘2026 → 披荆斩棘}、{@code 再见爱人第4季 → 再见爱人}、
     * {@code 某某节目2026 → 某某节目}。</p>
     */
    static String stripYearSuffix(String s) {
        if (TextUtils.isEmpty(s)) {
            return "";
        }
        String r = s;
        // "第4季" / "第4期" 结尾
        r = r.replaceAll("第\\s*[0-9一二三四五六七八九十]{1,3}\\s*季\\s*$", "");
        // 尾部 4 位年份（20xx），且前面还有内容
        r = r.replaceAll("(20\\d{2})\\s*$", "");
        // 尾部 "季" 单字
        r = r.replaceAll("季\\s*$", "");
        r = r.trim();
        // 剥离后太短则视为不可信（避免截成单字节目名）
        return r.length() >= 2 ? r : s;
    }

    /** 清空（用于映射更新后重载）。 */
    public static synchronized void reset() {
        name2slug = null;
        loaded = false;
    }

    /**
     * 合并加载：把外部文件（内置表或运行时更新的缓存）与当前内存表做<b>并集</b>，
     * 同名的以外部文件为准。这样"内置快照"与"运行时新抓到的节目"能共存。
     *
     * @param jsonFile 形如 {@code name_to_slug} 结构的 JSON 文件
     * @return 是否加载到有效数据
     */
    public static synchronized boolean loadMerged(File jsonFile) {
        if (jsonFile == null || !jsonFile.exists() || !jsonFile.isFile()) {
            return false;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(jsonFile), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return mergeJson(sb.toString());
        } catch (Throwable t) {
            return false;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 把 JSON 里的映射并入当前表（同名覆盖，其余保留）。 */
    static synchronized boolean mergeJson(String json) {
        if (TextUtils.isEmpty(json)) {
            return false;
        }
        Map<String, String> merged = new HashMap<>();
        if (name2slug != null) {
            merged.putAll(name2slug);
        }
        int added = 0;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject n2s = root.optJSONObject("name_to_slug");
            if (n2s != null) {
                Iterator<String> keys = n2s.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = n2s.optString(k, "");
                    if (TextUtils.isEmpty(k) || TextUtils.isEmpty(v)) continue;
                    String kk = k.trim();
                    String vv = v.trim();
                    if (!vv.equals(merged.get(kk))) {
                        if (!merged.containsKey(kk)) added++;
                        merged.put(kk, vv);
                    }
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        name2slug = merged;
        loaded = true;
        return added > 0 || !merged.isEmpty();
    }

    // ==================== 直连解析：日期 → 期数 ====================

    /** 多站点配置：主站在前，备用在后，依次尝试。 */
    private static final List<Site> SITES = new ArrayList<>();

    static {
        SITES.add(new Site("zyshow-pc", "https://www.zyshow.net",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"));
        SITES.add(new Site("zyshow-mobile", "https://m.zyshow.net",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
                        + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"));
    }

    /** 从详情页 title 提取"第N期"的正则。 */
    private static final Pattern RE_TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern RE_EPISODE = Pattern.compile("第\\s*(\\d{1,4})\\s*期");

    /** 单站点描述。 */
    private static final class Site {
        final String name;
        final String base;
        final String ua;

        Site(String name, String base, String ua) {
            this.name = name;
            this.base = base;
            this.ua = ua;
        }

        String episodeUrl(String slug, String date) {
            return base + "/dl/" + slug + "/v/" + date + ".html";
        }
    }

    /** 直连查询结果。 */
    public static final class DirectResult {
        public final int episode;
        public final String site;

        DirectResult(int episode, String site) {
            this.episode = episode;
            this.site = site;
        }

        public boolean found() {
            return episode > 0;
        }
    }

    /** 反向直连查询结果（期数 → 播出日期）。 */
    public static final class DateResult {
        public final String date;
        public final String site;

        DateResult(String date, String site) {
            this.date = date;
            this.site = site;
        }

        public boolean found() {
            return !TextUtils.isEmpty(date);
        }
    }

    /**
     * 探测某个日期是不是目标期数（反向查询的单点验证）。
     *
     * <p>与 {@link #resolveDirect} 不同：这里<b>已知日期</b>，
     * 直接抓该日期的页面并返回期数，或返回 -1 表示该日期不是这一期。</p>
     *
     * @param showName 节目中文名
     * @param date     待验证的日期（YYYYMMDD）
     * @return 该日期对应的期数；不是该节目/无期数返回 -1
     */
    static int probeEpisodeAtDate(String showName, String date) {
        if (TextUtils.isEmpty(showName) || TextUtils.isEmpty(date)) {
            return -1;
        }
        String trimmed = showName.trim();
        String slug = lookupStrict(trimmed);
        boolean strict = true;
        if (TextUtils.isEmpty(slug)) {
            slug = lookupByYearStrip(trimmed);
            strict = TextUtils.isEmpty(slug);
        }
        if (TextUtils.isEmpty(slug)) {
            slug = lookupFuzzy(trimmed);
            strict = TextUtils.isEmpty(slug);
        }
        if (TextUtils.isEmpty(slug)) {
            return -1;
        }
        for (Site site : SITES) {
            try {
                String html = httpGet(site.episodeUrl(slug, date), site.ua);
                int ep = parseEpisodeFromTitle(html);
                if (ep <= 0) {
                    continue;
                }
                if (!strict && !titleMatches(extractTitle(html), trimmed)) {
                    continue;
                }
                return ep;
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** 反向扫描窗口：最多前后各 {@code MAX_WEEKS} 周。 */
    private static final int MAX_WEEKS = 26;

    /**
     * 反向直连解析：给定节目名与期数，找出对应的播出日期。
     *
     * <p><b>为什么需要它</b>：正向 {@link #resolveDirect} 只覆盖"日期 → 期数"。
     * 但切源场景也存在反方向——当前正在看的集名是<b>期数式</b>
     * （如 {@code 第1期上}，本身不含日期），而目标源用<b>日期式</b>
     * （如 {@code 20260404上}）。此时正向查询没有日期可用，整条链路失手，
     * 只能退到"按位置猜"，必然错位。</p>
     *
     * <p><b>算法</b>：从 {@code anchorDate} 起按周向两侧扫描
     * （综艺多为周更，周步进最快逼近），命中后再在 ±7 天邻域精调。
     * 扫描窗口限制在前后各 {@link #MAX_WEEKS} 周内，避免无意义的长时间遍历。</p>
     *
     * <p><b>性能</b>：单次探测约 300~800ms，逐天遍历代价高，
     * 因此先用周步进把最坏情况的探测次数压到个位数。</p>
     *
     * <p>必须在后台线程调用；任何失败返回未命中的空结果。</p>
     *
     * @param showName      节目中文名
     * @param targetEpisode 目标期数（如 1）
     * @param anchorDate    起点日期（YYYYMMDD）
     * @return 结果对象；{@link DateResult#found()} 为 false 表示未命中
     */
    public static DateResult resolveEpisodeToDate(String showName, int targetEpisode, String anchorDate) {
        if (TextUtils.isEmpty(showName) || targetEpisode <= 0) {
            return new DateResult(null, null);
        }
        java.util.Calendar cal = parseCalendar(anchorDate);
        if (cal == null) {
            return new DateResult(null, null);
        }
        // 第一轮：按周步进快速逼近（综艺多为周更）
        for (int step = 0; step <= MAX_WEEKS; step++) {
            for (int sign : new int[]{1, -1}) {
                if (step == 0 && sign < 0) {
                    continue;
                }
                java.util.Calendar c = (java.util.Calendar) cal.clone();
                c.add(java.util.Calendar.DAY_OF_YEAR, sign * step * 7);
                String d = formatDate(c);
                if (probeEpisodeAtDate(showName, d) == targetEpisode) {
                    return new DateResult(d, null);
                }
            }
        }
        // 第二轮：邻域精扫（覆盖一周内的全部日期）
        for (int week = -6; week <= 6; week++) {
            java.util.Calendar base = (java.util.Calendar) cal.clone();
            base.add(java.util.Calendar.DAY_OF_YEAR, week * 7);
            for (int inner = -1; inner <= 1; inner++) {
                java.util.Calendar c = (java.util.Calendar) base.clone();
                c.add(java.util.Calendar.DAY_OF_YEAR, inner);
                String d = formatDate(c);
                if (probeEpisodeAtDate(showName, d) == targetEpisode) {
                    return new DateResult(d, null);
                }
            }
        }
        return new DateResult(null, null);
    }

    /**
     * 反向解析（<b>多日期版</b>）：给定期数，返回<b>所有</b>属于该期的播出日期。
     *
     * <p><b>为什么需要多日期</b>：一个「期」在日期式源里常常<b>跨越两天</b>——
     * 综艺普遍把一期的"上/下"两段分两天播出：</p>
     * <pre>
     *   jisu 源（期数式）        dytt 源（日期式）
     *   ────────────────────────────────────────────
     *   第2期上          ←→      20260411上
     *   第2期下          ←→      20260412下
     * </pre>
     * <p>此时 {@code 20260411} 与 {@code 20260412} <b>都属于第2期</b>。
     * 单日期版 {@link #resolveEpisodeToDate} 只返回先扫到的那个，
     * 于是"第2期上"可能被对到 {@code 20260412上}（错）而非 {@code 20260411上}（对）。</p>
     *
     * <p><b>算法</b>：与单日期版相同的双轮扫描（周步进 + 邻域精扫），
     * 但命中后<b>不立即返回</b>，而是把日期收进结果集；
     * 同时对该日期前后各若干天做一次短程延伸探测，把"同期的相邻天"一并收齐。</p>
     *
     * <p>调用方随后可用日期顺序 + 分集后缀，把"第N期上/下"精确对到对应日期。</p>
     *
     * <p>必须在后台线程调用；任何失败返回空列表。</p>
     *
     * @param showName      节目中文名
     * @param targetEpisode 目标期数（如 2）
     * @param anchorDate    起点日期（YYYYMMDD）
     * @param spanDays      命中后向两侧延伸探测的天数（建议 1~3）
     * @return 属于该期的日期列表（升序，可能为空）
     */
    public static java.util.List<String> resolveEpisodeToDates(String showName, int targetEpisode,
                                                              String anchorDate, int spanDays) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (TextUtils.isEmpty(showName) || targetEpisode <= 0) {
            return out;
        }
        java.util.Calendar cal = parseCalendar(anchorDate);
        if (cal == null) {
            return out;
        }
        java.util.Set<String> seenHit = new java.util.LinkedHashSet<>();
        // 本季窗口：以锚点年份为界，避免把去年同期号的日期一并收进结果。
        final int seasonYear = seasonYearOf(anchorDate);
        // 第一轮：按周步进快速逼近
        // ★ 循环条件修正：原为 {@code out.isEmpty()} —— 但 out 在本函数末尾才被填充，
        //   该条件恒为 true，使得步进一路扫完 {@link #MAX_WEEKS} 周，
        //   把不同季的同期号也收进 seenHit → 排序后异常日期可能排在首位 → 错位。
        //   正确意图是"命中即停"（同一 season 内期号唯一），用 seenHit.isEmpty() 作为迴归条件。
        for (int step = 0; step <= MAX_WEEKS && seenHit.isEmpty(); step++) {
            for (int sign : new int[]{1, -1}) {
                if (step == 0 && sign < 0) {
                    continue;
                }
                java.util.Calendar c = (java.util.Calendar) cal.clone();
                c.add(java.util.Calendar.DAY_OF_YEAR, sign * step * 7);
                String d = formatDate(c);
                if (outOfSeason(d, seasonYear)) {
                    continue;
                }
                if (probeEpisodeAtDate(showName, d) == targetEpisode) {
                    seenHit.add(d);
                }
            }
        }
        // 第二轮：邻域精扫（仅在第一轮无果时进行，保持与单日期版一致的探测预算）
        if (seenHit.isEmpty()) {
            for (int week = -6; week <= 6; week++) {
                java.util.Calendar base = (java.util.Calendar) cal.clone();
                base.add(java.util.Calendar.DAY_OF_YEAR, week * 7);
                for (int inner = -1; inner <= 1; inner++) {
                    java.util.Calendar c = (java.util.Calendar) base.clone();
                    c.add(java.util.Calendar.DAY_OF_YEAR, inner);
                    String d = formatDate(c);
                    if (outOfSeason(d, seasonYear)) {
                        continue;
                    }
                    if (probeEpisodeAtDate(showName, d) == targetEpisode) {
                        seenHit.add(d);
                    }
                }
            }
        }
        if (seenHit.isEmpty()) {
            return out;
        }
        // 延伸：对每个命中日，向两侧各 spanDays 天探测，收齐"同期的相邻天"
        int span = Math.max(0, Math.min(spanDays, 5));
        java.util.Set<String> all = new java.util.TreeSet<>(seenHit);
        for (String hit : seenHit) {
            java.util.Calendar hc = parseCalendar(hit);
            if (hc == null) {
                continue;
            }
            for (int off = -span; off <= span; off++) {
                if (off == 0) {
                    continue;
                }
                java.util.Calendar c = (java.util.Calendar) hc.clone();
                c.add(java.util.Calendar.DAY_OF_YEAR, off);
                String d = formatDate(c);
                if (outOfSeason(d, seasonYear)) {
                    continue;
                }
                if (probeEpisodeAtDate(showName, d) == targetEpisode) {
                    all.add(d);
                }
            }
        }
        out.addAll(all);
        return out;
    }

    /**
     * 取锚点日期所属年份（用作本季窗口下界）。
     *
     * <p>综艺单季不会跨年（即使跨年也只在年界附近几天），
     * 因此用"锚点年份"作为本季范围是安全且充分的。
     * 无效锚点返回 -1（表示不做年份限制）。</p>
     */
    private static int seasonYearOf(String anchorDate) {
        java.util.Calendar c = parseCalendar(anchorDate);
        return c == null ? -1 : c.get(java.util.Calendar.YEAR);
    }

    /**
     * 判断某日期是否超出本季窗口。
     *
     * <p><b>为什么需要它</b>：反向扫描会从锚点向两侧跨最多 {@link #MAX_WEEKS} 周。
     * 若不加限制，去年的「第N期」也会被当作本季命中收进结果，
     * 排序后早年的日期排在首位，导致切到完全错误的集。</p>
     *
     * @param date       待检查日期（YYYYMMDD）
     * @param seasonYear 本季年份；≤ 0 时不限制
     * @return true 表示应跳过该日期
     */
    private static boolean outOfSeason(String date, int seasonYear) {
        if (seasonYear <= 0 || TextUtils.isEmpty(date) || date.length() != 8) {
            return false;
        }
        try {
            return Integer.parseInt(date.substring(0, 4)) != seasonYear;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解析 YYYYMMDD 为 Calendar；不合法返回 null。 */
    private static java.util.Calendar parseCalendar(String date) {
        if (TextUtils.isEmpty(date) || date.length() != 8) {
            return null;
        }
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(date.substring(0, 4)),
                    Integer.parseInt(date.substring(4, 6)) - 1,
                    Integer.parseInt(date.substring(6, 8)));
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 格式化为 YYYYMMDD。 */
    static String formatDate(java.util.Calendar c) {
        return String.format(java.util.Locale.ROOT, "%04d%02d%02d",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /**
     * 直连解析：给定节目名与播出日期，直接从站点抓取对应期数。
     *
     * <p>多站点依次尝试（主站失败自动切备用），全部失败返回 {@code episode=-1}。
     * 必须在后台线程调用。</p>
     *
     * <p><b>二次校验（防张冠李戴）</b>：slug 一旦来自"去年份后缀"或包含式这类宽松匹配，
     * 抓到的可能是<b>另一个节目</b>的页面。因此拿到 HTML 后必须确认
     * 页面标题里确实出现了当前节目名（或其去年份后缀的形式），否则一律弃用。
     * 宁可返回 -1 退回本地兜底，也不能把用户切到无关的集上。</p>
     *
     * @param showName 节目中文名
     * @param date     播出日期（YYYYMMDD）
     * @return 结果对象；{@link DirectResult#found()} 为 false 表示未命中
     */
    public static DirectResult resolveDirect(String showName, String date) {
        if (TextUtils.isEmpty(showName) || TextUtils.isEmpty(date)) {
            return new DirectResult(-1, null);
        }
        String trimmed = showName.trim();
        // 先试可信匹配（精确/去空格/去括号）；没有再试"去年份后缀"，最后才是包含式。
        // 可信来源（精确命中）无需标题校验；其余两类都可能命中"另一个节目"，必须校验。
        String slug = lookupStrict(trimmed);
        boolean strict = true;
        if (TextUtils.isEmpty(slug)) {
            // 去年份后缀，如「披荆斩棘2026」→「披荆斩棘」。
            // 命中后抓到的极可能是同名前作的页面，属于不可信来源，必须二次校验标题。
            slug = lookupByYearStrip(trimmed);
            strict = TextUtils.isEmpty(slug);
        }
        if (TextUtils.isEmpty(slug)) {
            slug = lookupFuzzy(trimmed);
            strict = TextUtils.isEmpty(slug);
        }
        if (TextUtils.isEmpty(slug)) {
            // 节目不在映射表内，无法直连
            return new DirectResult(-1, null);
        }
        for (Site site : SITES) {
            try {
                String html = httpGet(site.episodeUrl(slug, date), site.ua);
                int ep = parseEpisodeFromTitle(html);
                if (ep <= 0) {
                    continue;
                }
                // 宽松来源（可能命中"另一个节目"）必须做标题二次校验
                if (!strict && !titleMatches(extractTitle(html), trimmed)) {
                    continue;
                }
                // ★ 归一：站点按自然周编号，源侧按播出次数编号，量纲不同。
                //   把周序号换算成播出序号，否则 20260411 会被当成"第2期"。
                int normalized = normalizeToBroadcastOrdinal(slug, date, ep);
                return new DirectResult(normalized, site.name);
            } catch (Throwable ignored) {
                // 单站点失败，继续尝试下一个（备用的意义所在）
            }
        }
        return new DirectResult(-1, null);
    }

    /**
     * 把站点的「第N期」归一成<b>播出序号</b>（而非自然周序号）。
     *
     * <p><b>为什么必须归一（关键修复）</b>：站点详情页标题里的「第N期」是
     * 该站自己的编号口径。实测 {@code zyshow.net} 的综艺按<b>自然周</b>编号：
     * 同一自然周内播出的多天共用同一个期号，下一周才 +1。
     * 而各视频源（jisu / 360zy 等）的「第N期」按<b>播出次数</b>编号，每播一次 +1。
     * 两者量纲不同，直接套用会让用户切到完全不相干的集。</p>
     *
     * <pre>
     *   实测（哈哈哈哈哈第六季，2026 年）：
     *   播出日期     播出序号   站点周序号
     *   ───────────────────────────────────
     *   20260404       1           1
     *   20260405       2           1     ← 同周共用
     *   20260406       3           2
     *   20260409       4           2     ← 同周共用
     *   20260411       5           2     ★ 用户在此切源，站点说"第2期"
     * </pre>
     * <p>于是 {@code 第20260411期} 被错配到源里的 {@code 第2期上}。</p>
     *
     * <p><b>归一算法</b>：以本季首个播出日为原点，按<b>天</b>步进扫描早期日期，
     * 统计"该节目确实有页面（即当天有播出）"的天数，即为播出序号。
     * 为了避免逐天扫描代价过高，只在<b>起始若干天</b>内扫描（覆盖本季开头），
     * 一旦累计天数达到站点给的周序号就停止——因为我们只需要知道
     * "站点周序号 N 对应真实播出序号 M"，且 M ≥ N（周序号只会少不会多）。</p>
     *
     * <p>扫描失败（超时/无网）时<b>原样返回站点期数</b>：宁可维持现状，
     * 也不引入新的错配。</p>
     *
     * @param slug       站点 slug
     * @param date       目标播出日期（YYYYMMDD）
     * @param siteEpisode 站点标题里解析出的期数（周序号口径）
     * @return 归一后的播出序号；无法归一/失败时返回 {@code siteEpisode}
     */
    static int normalizeToBroadcastOrdinal(String slug, String date, int siteEpisode) {
        if (TextUtils.isEmpty(slug) || TextUtils.isEmpty(date) || siteEpisode <= 0) {
            return siteEpisode;
        }
        java.util.Calendar target = parseCalendar(date);
        if (target == null) {
            return siteEpisode;
        }
        // 从目标日期回溯最多 60 天，覆盖本季开头
        java.util.Calendar cursor = (java.util.Calendar) target.clone();
        cursor.add(java.util.Calendar.DAY_OF_YEAR, -60);
        // 逐天统计：从起点开始，数到目标日期为止，有多少天"该节目确实有页面"
        int broadcastCount = 0;
        java.util.Calendar probe = (java.util.Calendar) cursor.clone();
        // 限制总探测次数，避免慢网下卡顿（最多 62 次 ≈ 2 个月）
        for (int i = 0; i <= 62; i++) {
            String d = formatDate(probe);
            int ep = -1;
            for (Site site : SITES) {
                try {
                    String html = httpGet(site.episodeUrl(slug, d), site.ua);
                    int got = parseEpisodeFromTitle(html);
                    if (got > 0) {
                        ep = got;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (ep > 0) {
                broadcastCount++;
            }
            if (d.equals(date)) {
                // 已数到目标日期
                break;
            }
            probe.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        // 统计数必然 ≥ 站点周序号；只有统计数不小于站点期数时才采用（防护异常数据）
        if (broadcastCount >= siteEpisode) {
            return broadcastCount;
        }
        return siteEpisode;
    }

    /** 取出 {@code <title>} 内容；取不到返回空串。 */
    static String extractTitle(String html) {
        if (TextUtils.isEmpty(html)) {
            return "";
        }
        Matcher tm = RE_TITLE.matcher(html);
        if (!tm.find()) {
            return "";
        }
        String t = tm.group(1);
        return t == null ? "" : t;
    }

    /**
     * 校验站点页面标题是否确实属于当前节目。
     *
     * <p>判定：把标题与节目名都归一化（去空格、去标点）后，检查标题中是否包含
     * 节目名主体。考虑到站点标题常形如
     * {@code 【披荆斩棘2026】_第8期：...} 或 {@code 披荆斩棘20260816_第8期_...}，
     * 这里同时接受"节目名"与"去掉年份后缀的节目名"两种形式。</p>
     *
     * @param title    站点页面 title 原文
     * @param showName 当前节目名
     * @return true 表示标题确实属于该节目
     */
    static boolean titleMatches(String title, String showName) {
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(showName)) {
            return false;
        }
        String t = normalizeForCompare(title);
        String n = normalizeForCompare(showName);
        if (n.length() < 2 || t.isEmpty()) {
            return false;
        }
        // 直接包含
        if (t.contains(n)) {
            return true;
        }
        // 去掉年份后缀后再比一次（站点常把年份放在节目名里，也可能不放）
        String nNoYear = normalizeForCompare(stripYearSuffix(showName));
        if (nNoYear.length() >= 2 && t.contains(nNoYear)) {
            return true;
        }
        // 反过来：站点标题里可能带了额外后缀，节目名去掉尾部数字后再比
        String nBase = normalizeForCompare(showName.replaceAll("\\d+\\s*$", ""));
        return nBase.length() >= 3 && t.contains(nBase);
    }

    /** 归一化：去空白与常见标点，便于标题比对。 */
    private static String normalizeForCompare(String s) {
        if (TextUtils.isEmpty(s)) {
            return "";
        }
        return s.replaceAll("[\\s\\[\\]【】()（）_\\-—·、,，.。:：!！?？\"'“”‘’]+", "");
    }

    /** 从 HTML 的 {@code <title>} 中解析"第N期"。返回 -1 表示无期数。 */
    static int parseEpisodeFromTitle(String html) {
        if (TextUtils.isEmpty(html)) {
            return -1;
        }
        Matcher tm = RE_TITLE.matcher(html);
        if (!tm.find()) {
            return -1;
        }
        String title = tm.group(1);
        if (title == null) {
            return -1;
        }
        Matcher em = RE_EPISODE.matcher(title);
        if (em.find()) {
            try {
                return Integer.parseInt(em.group(1));
            } catch (Throwable ignored) {
                return -1;
            }
        }
        // 形如"大结局上"等无期数的排期，按设计返回 -1（宁缺毋滥）
        return -1;
    }

    /** 使用项目统一的 OkHttp 客户端发起 GET。 */
    private static String httpGet(String url, String ua) {
        okhttp3.Response response = null;
        try {
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", ua == null ? "" : ua)
                    .build();
            okhttp3.OkHttpClient client = com.github.catvod.net.OkHttp.client();
            response = client.newCall(request).execute();
            if (response.body() != null) {
                return response.body().string();
            }
        } catch (Throwable ignored) {
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }
}
