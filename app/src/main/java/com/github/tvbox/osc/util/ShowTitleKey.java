package com.github.tvbox.osc.util;

import android.text.TextUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 片名键（show title key）治理工具。
 *
 * <p><b>为什么需要这个类</b>：本项目有多处「以片名作缓存/索引键」的容器，而
 * {@code vod_name} 会在<b>切源</b>时被新资源站的写法覆盖
 * （{@code DetailActivity} 里 {@code vod_name = video.name}）。同一个片名在不同源
 * 写作 {@code 【全集】庆余年} / {@code 庆余年} / {@code 庆余年 第一季} 是常态，
 * 于是「写键」和「读键」的口径一旦不一致，读取就会大面积落空。</p>
 *
 * <p>审计发现同一个根因伤了三处，差别只在「有没有归一化」：</p>
 * <ul>
 *   <li>{@code SearchSession.candidatePool}：无归一化 → 切源后读池落空；</li>
 *   <li>{@code EpisodeOnlineResolver.CACHE}：无归一化 → 跨域映射缓存全废、反复联网；</li>
 *   <li>{@code DetailActivity.detailFallbackCache}：有归一化（本类的前身
 *       {@code isSameFallbackTitle}）→ 兜住了。</li>
 * </ul>
 *
 * <p>因此把归一化提到公共层，让所有以片名作键的容器<b>共用同一口径</b>。</p>
 *
 * <p><b>归一化分两步</b>：</p>
 * <ol>
 *   <li><b>剥标签词</b>：见 {@link #stripTags}。资源站爱在片名前后加
 *       {@code 【全集】}{@code 【完结】}{@code (HD)} 之类运营标签，这些不是片名的一部分，
 *       但都是汉字/字母，光靠「剔符号」去不掉。</li>
 *   <li><b>去装饰</b>：只保留「字母、数字、表意文字（中日韩汉字）」并统一转小写，
 *       剔除空格、标点、全角/半角差异、{@code ·} {@code /} {@code -} 等分隔符、
 *       以及 {@code 【】} 之类装饰符号。</li>
 * </ol>
 *
 * <p><b>安全边界</b>：标签词只在「被包起来」或「处于首尾」时才剥离
 * （详见 {@link #stripTags}），因此 {@code 全集人生}（"全集"是片名正文）
 * 不会被误伤；而 {@code 庆余年 第一季} 与 {@code 庆余年第二季} 的季数
 * <b>永不剥离</b>，避免把不同季混成一部。</p>
 *
 * <p><b>注意</b>：归一化是<b>有损</b>的（{@code 庆余年} 与 {@code 庆·余年} 会归一到一起）。
 * 用于「键」是合适的，用于「显示」必须用原始串。</p>
 */
public final class ShowTitleKey {

    private ShowTitleKey() {
    }

    /**
     * 站点运营标签词表：这些词出现在片名中时，通常是站的标注而非片名本身。
     *
     * <p><b>严禁</b>把季/部/集数纳入本表（{@code 第二季}、{@code 第3部}、{@code 2} …），
     * 否则 {@code 庆余年第一季} 与 {@code 庆余年第二季} 会被并成同一部片。</p>
     */
    private static final Set<String> TAG_WORDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    // —— 资源完整性标签 ——
                    "全集", "完结", "已完结", "连载", "更新", "更新中", "未完待续",
                    "全",  // 仅在全包裹/紧贴"集"时生效，见 stripTags
                    // —— 画质标签 ——
                    "高清", "超清", "蓝光", "蓝光版", "hd", "bd", "hdtv", "dvd", "web",
                    // —— 版本/清晰度标签 ——
                    "tc", "ts", "抢先", "抢先版", "枪版", "正式版", "完整版", "未删减",
                    "国语", "粤语", "双语", "原声", "国语版", "粤语版", "中字", "韩语",
                    // ★ v65：普通话与国语是同一种语言的不同写法，必须对称入表 ——
                    //   只入一个会让「庆余年普通话版」与「庆余年国语版」归一化后不等。
                    "普通话", "普通话版",
                    "英语", "日语", "免费", "在线", "超高清", "4k", "1080p", "720p",
                    // —— 资源站自身前缀 ——
                    "资源", "资源站", "影视", "电影", "电视剧", "综艺", "动漫", "剧集"
            )));

    /** 成对包裹符号：{@code 【全集】}、{@code (HD)}、{@code [完结]} 等。 */
    private static final String OPENERS = "【（([〔＜<《「『";
    private static final String CLOSERS = "】）)]〕＞>》」』";

    /** 判断是否为「词边界」字符：分隔符/装饰符（非字母、非数字、非表意文字）。 */
    private static boolean isBoundaryChar(char c) {
        return !(Character.isLetterOrDigit(c) || Character.isIdeographic(c));
    }

    /**
     * 剥离站点运营标签。
     *
     * <p>三条<b>互相独立</b>的规则，从严到宽：</p>
     *
     * <p><b>规则一：包裹剥离。</b>若出现 {@code 【X】}（开闭符号成对），
     * 且 {@code X} 整体命中 {@link #TAG_WORDS}，则连符号一起剥掉。
     * 例：{@code 【全集】庆余年} → {@code 庆余年}。
     * 首尾都做，因此 {@code 庆余年【全集】} 也成立。</p>
     *
     * <p><b>规则二 / 规则三：首尾裸词剥离（有限迭代）。</b>若片名<b>开头（或结尾）</b>
     * 就是某个标签词，且该词<b>内侧</b>紧邻「词边界」（分隔符/开闭符号）或已到另一端，
     * 则剥掉它并继续，最多 {@link #MAX_STRIP_ROUNDS} 轮。
     * 例：{@code 完结 庆余年} / {@code 庆余年 HD} → {@code 庆余年}。
     * <b>不会</b>动 {@code 全集人生} —— "全集"后紧跟的是汉字 {@code 人}，非词边界；
     * 也<b>不会</b>动 {@code 谁是真凶 大结局} —— "结局"不在词表里。</p>
     *
     * <p>词表按长度<b>降序</b>匹配，保证 {@code 抢先版} 先于 {@code 抢先} 命中。</p>
     */
    private static String stripTags(String s) {
        if (TextUtils.isEmpty(s)) {
            return s;
        }
        String cur = s;

        // —— 规则一：包裹剥离（可迭代，允许多层如【全集】(HD)） ——
        for (int round = 0; round < MAX_STRIP_ROUNDS; round++) {
            String next = stripOneWrappedTag(cur);
            if (next == null) {
                break;
            }
            cur = next;
        }

        // —— 规则二：首部裸词剥离 ——
        for (int round = 0; round < MAX_STRIP_ROUNDS; round++) {
            int cut = matchLeadingTag(cur);
            if (cut <= 0) {
                break;
            }
            cur = cur.substring(cut);
        }

        // —— 规则三：尾部裸词剥离 ——
        for (int round = 0; round < MAX_STRIP_ROUNDS; round++) {
            int cut = matchTrailingTag(cur);
            if (cut <= 0) {
                break;
            }
            cur = cur.substring(0, cur.length() - cut);
        }
        return cur;
    }

    /** 最多剥离轮数，防止畸形输入（如连续几十个标签）导致长循环。 */
    private static final int MAX_STRIP_ROUNDS = 8;

    /**
     * 尝试剥掉开头/结尾的一个<b>包裹</b>标签。
     *
     * <p>结尾的包裹标签也剥（{@code 庆余年【全集】}），但<b>不</b>碰中间出现的
     * {@code 【】} —— 那更可能是分集信息（如 {@code 庆余年【第1集】}），不能动。</p>
     *
     * @return 剥掉后的新串；{@code null} 表示没匹配上。
     */
    private static String stripOneWrappedTag(String s) {
        // 先试开头
        int head = matchWrappedAt(s, true);
        if (head >= 0) {
            return s.substring(head);
        }
        // 再试结尾
        int tail = matchWrappedAt(s, false);
        if (tail >= 0) {
            return s.substring(0, tail);
        }
        return null;
    }

    /**
     * 在 {@code s} 的开头（{@code fromHead=true}）或结尾匹配一个包裹标签。
     *
     * @return 从<b>头部</b>剥时返回「应保留的起始下标」；
     *         从<b>尾部</b>剥时返回「应保留的结束下标」（exclusive）。
     *         {@code -1} 表示没匹配上。
     */
    private static int matchWrappedAt(String s, boolean fromHead) {
        int n = s.length();
        if (n < 3) {
            return -1;
        }
        if (fromHead) {
            if (OPENERS.indexOf(s.charAt(0)) < 0) {
                return -1;
            }
            int close = -1;
            for (int i = 1; i < n; i++) {
                if (CLOSERS.indexOf(s.charAt(i)) >= 0) {
                    close = i;
                    break;
                }
            }
            if (close < 0) {
                return -1;
            }
            if (TAG_WORDS.contains(s.substring(1, close).trim().toLowerCase())) {
                return close + 1;
            }
            return -1;
        } else {
            if (CLOSERS.indexOf(s.charAt(n - 1)) < 0) {
                return -1;
            }
            int open = -1;
            for (int i = n - 2; i >= 0; i--) {
                if (OPENERS.indexOf(s.charAt(i)) >= 0) {
                    open = i;
                    break;
                }
            }
            if (open < 0) {
                return -1;
            }
            if (TAG_WORDS.contains(s.substring(open + 1, n - 1).trim().toLowerCase())) {
                return open;
            }
            return -1;
        }
    }

    /**
     * 在片名<b>开头</b>匹配标签词。
     *
     * @return 需要裁掉的字符数；{-1} 表示没匹配上。
     */
    private static int matchLeadingTag(String s) {
        String lower = s.toLowerCase();
        for (int len = Math.min(MAX_TAG_LEN, lower.length()); len >= 1; len--) {
            String head = lower.substring(0, len);
            if (!TAG_WORDS.contains(head)) {
                continue;
            }
            // 词后必须是「边界」或串尾，避免吃掉正常片名
            if (lower.length() == len || isBoundaryChar(s.charAt(len))) {
                return len;
            }
        }
        return -1;
    }

    /**
     * 在片名<b>结尾</b>匹配标签词（{@link #matchLeadingTag} 的镜像）。
     *
     * @return 需要从尾部裁掉的字符数；{@code -1} 表示没匹配上。
     */
    private static int matchTrailingTag(String s) {
        String lower = s.toLowerCase();
        for (int len = Math.min(MAX_TAG_LEN, lower.length()); len >= 1; len--) {
            String tail = lower.substring(lower.length() - len);
            if (!TAG_WORDS.contains(tail)) {
                continue;
            }
            // 词前必须是「边界」或串首，避免吃掉正常片名
            if (lower.length() == len || isBoundaryChar(s.charAt(s.length() - len - 1))) {
                return len;
            }
        }
        return -1;
    }

    /** 标签词表中最长词的长度，用于限制前缀扫描范围。 */
    private static final int MAX_TAG_LEN = maxTagLen();

    private static int maxTagLen() {
        int max = 1;
        for (String w : TAG_WORDS) {
            if (w.length() > max) {
                max = w.length();
            }
        }
        return max;
    }

    /**
     * 把片名归一化成稳定的键。
     *
     * <p>返回保证非 null：输入为空时返回空串，调用方据此跳过即可。
     * 若归一化后为空（例如片名全是符号），<b>退回 {@code trim} 后的原串</b> ——
     * 这样键至少还是可用的，不会让所有「纯符号片名」都撞到同一个空键。</p>
     */
    public static String normalize(String title) {
        if (TextUtils.isEmpty(title)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(title.length());
        String stripped = stripTags(title.trim());
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isIdeographic(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        String result = sb.toString();
        return TextUtils.isEmpty(result) ? title.trim() : result;
    }

    /**
     * 两个片名是否指向同一部片（归一化后相等）。
     *
     * <p>先做严格相等短路（最常见的路径，省一次两次归一化），再比归一化键。</p>
     */
    public static boolean same(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) {
            return false;
        }
        String ta = a.trim();
        String tb = b.trim();
        if (ta.equals(tb)) {
            return true;
        }
        String na = normalize(ta);
        String nb = normalize(tb);
        return !TextUtils.isEmpty(na) && na.equals(nb);
    }

    /**
     * 「同一部片」的宽匹配：供<b>切源候选</b>判定使用（键口径仍用严格的 {@link #same}）。
     *
     * <p><b>★ v65：为修「切源只有两三个站」而加。</b>各资源站对同一部片的写法
     * 差异远不止符号与运营标签：{@code 庆余年} / {@code 庆余年TV版} /
     * {@code 庆余年HD} / {@code 庆余年 完结} / {@code 庆余年重制版} 是常态。
     * 而 {@link #same} 的归一化<b>刻意</b>不剥季数/版号（防止把不同季混成一部），
     * 于是切源候选只认严格相等时，写法带后缀的站全部进不了候选池，
     * 用户看到的就是「能切的源只剩两三个」。</p>
     *
     * <p><b>判定规则（三条硬红线 + 一条放宽）</b>：</p>
     * <ol>
     *   <li><b>语言红线</b>：片名上标注的语言（国语/普通话/粤语/双语/中字/英语/
     *       日语/韩语/原声）<b>有无与取值必须一致</b>。看国语版的片，候选里
     *       绝不出现粤语版；没标语言的片，也不收标注了语言的站。</li>
     *   <li><b>序号红线</b>：尾部序号（第X季/第X部/第X篇）的有无与数值必须一致
     *       （第一季 ≡ 第1季；第一季 ≠ 第二季；有序号 ≠ 无序号）。</li>
     *   <li><b>数字红线</b>：主干差异中任何数字段一律不放行
     *       （神雕侠侣1995 ≠ 神雕侠侣2005；庆余年 ≠ 庆余年2）。</li>
     *   <li><b>放宽</b>：主干为前缀关系，且长出部分只是画质/状态/版本微调修饰
     *       （HD/4K/蓝光/完结/全集/重制版/修复版/完整版/未删减/正式版/抢先版…）时，
     *       算同一部片。</li>
     * </ol>
     *
     * <p><b>为什么语言/序号要在 normalize 之前判</b>：{@link #normalize} 会把
     * {@code 国语版} 当作运营标签剥掉（TAG_WORDS），若先归一化再判语言，
     * 「庆余年国语版」与「庆余年粤语版」会变成同一个键 —— 红线就被绕过了。
     * 因此本方法先在<b>原始串</b>上抽语言词做红线判定，再走归一化比较。</p>
     *
     * <p><b>形态词一律拒绝</b>：{@code 电影版}/{@code TV版}/{@code 电视剧版}/
     * {@code 动漫版}/{@code 剧场版}/{@code 网络版}/{@code 配音版} 改变作品形态
     * （《一人之下》的动漫与电影不是同一部），即使主干相同也拒绝。
     * 而 {@code 重制版}/{@code 修复版} 是同一作品的再制作，形态未变，放行。</p>
     */
    public static boolean sameFamily(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) {
            return false;
        }
        String ta = a.trim();
        String tb = b.trim();
        if (ta.equals(tb)) {
            return true;
        }

        // ---- 红线1：语言（原始串上判，见类注释「为什么在 normalize 之前判」）----
        String langA = findLanguageWord(ta);
        String langB = findLanguageWord(tb);
        if (!languageEq(langA, langB)) {
            return false;
        }
        // 语言词显式剥除：紧贴汉字的写法（庆余年普通话版）normalize 的词边界规则剥不掉，
        // 不剥的话两边带着不同的语言后缀，前缀比较直接失败。
        String noLangA = langA == null ? ta : ta.replace(langA, "");
        String noLangB = langB == null ? tb : tb.replace(langB, "");

        // ---- 红线2：尾部序号 ----
        // ★ 在原始串上找，不要求它在串尾：「庆余年第一季HD」这类「序号紧跟运营标签」
        //   的写法，normalize 剥不掉 HD（「季」不是词边界，matchTrailingTag 不剥），
        //   若等归一化后再找序号会被 HD 挡住而漏判。
        Ordinal ordA = findOrdinalSegment(noLangA);
        Ordinal ordB = findOrdinalSegment(noLangB);
        if (!ordinalEq(ordA, ordB)) {
            return false;
        }
        // 剥掉序号段再归一化（剩下的运营标签/画质由 normalize 负责）
        String coreA = ordA == null ? noLangA : noLangA.substring(0, ordA.start) + noLangA.substring(ordA.end);
        String coreB = ordB == null ? noLangB : noLangB.substring(0, ordB.start) + noLangB.substring(ordB.end);
        String na = normalize(coreA);
        String nb = normalize(coreB);
        if (TextUtils.isEmpty(na) || TextUtils.isEmpty(nb)) {
            return false;
        }
        if (na.equals(nb)) {
            return true;
        }
        if (na.length() == nb.length()) {
            return false;
        }

        // ---- 放宽：主干前缀 + 长出部分纯修饰（数字段一律拒绝）----
        String longer = na.length() > nb.length() ? na : nb;
        String shorter = na.length() > nb.length() ? nb : na;
        if (!longer.startsWith(shorter)) {
            return false;
        }
        return isPureModifier(longer.substring(shorter.length()));
    }

    /** 语言词表：出现在片名中即视为「语言标注」，是红线字段而非可忽略修饰。 */
    private static final String[] LANGUAGE_WORDS = {
            "普通话版", "普通话", "国语版", "国语", "粤语版", "粤语",
            "双语", "中字", "英语", "英文", "日语", "韩语", "原声版", "原声",
    };

    /** 形态改变词：命中即拒绝（小写比较 —— normalize 后字母统一小写）。 */
    private static final Set<String> FORM_CHANGE_WORDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "电影版", "tv版", "电视剧版", "动漫版", "剧场版", "网络版", "配音版")));

    /**
     * 修饰词表：{@link #TAG_WORDS} 全量 + 剥语言后残留的「版」+ 同作品再制作版本。
     *
     * <p>与 TAG_WORDS 合并而非另起一套：画质/状态类标签（HD/完结/全集…）
     * {@link #normalize} 已经会剥，这里只是为了「前缀长出部分」判定时
     * 能按词扫过它们；真正新增的只有 {@code 重制版}/{@code 修复版}/{@code 版}。</p>
     */
    private static final Set<String> MODIFIER_WORDS;
    /** 修饰词表最长词长度（限制最长匹配扫描范围）。 */
    private static final int MAX_MODIFIER_LEN;

    static {
        Set<String> s = new HashSet<>(TAG_WORDS);
        s.addAll(Arrays.asList("重制版", "修复版", "版"));
        MODIFIER_WORDS = Collections.unmodifiableSet(s);
        int max = 1;
        for (String w : MODIFIER_WORDS) {
            if (w.length() > max) {
                max = w.length();
            }
        }
        MAX_MODIFIER_LEN = max;
    }

    /**
     * 在原始串里找语言标注词（按词长降序，返回词表面；无则 null）。
     *
     * <p>用 contains 而非「首尾边界」：语言词可能出现在片名任何位置
     * （{@code 庆余年 国语} / {@code 国语的庆余年}）。红线宁可错杀。</p>
     */
    private static String findLanguageWord(String s) {
        for (String w : LANGUAGE_WORDS) {
            if (s.contains(w)) {
                return w;
            }
        }
        return null;
    }

    /** 语言值归一：普通话≡国语；带不带「版」不算差异。 */
    private static String canonicalLanguage(String w) {
        if (w == null) {
            return null;
        }
        if (w.startsWith("普通话")) {
            return "国语";
        }
        if (w.startsWith("国语")) {
            return "国语";
        }
        if (w.startsWith("粤语")) {
            return "粤语";
        }
        if (w.startsWith("双语")) {
            return "双语";
        }
        if (w.startsWith("中字")) {
            return "中字";
        }
        if (w.startsWith("英语") || w.startsWith("英文")) {
            return "英语";
        }
        if (w.startsWith("日语")) {
            return "日语";
        }
        if (w.startsWith("韩语")) {
            return "韩语";
        }
        if (w.startsWith("原声")) {
            return "原声";
        }
        return w;
    }

    private static boolean languageEq(String a, String b) {
        String ca = canonicalLanguage(a);
        String cb = canonicalLanguage(b);
        return ca == null ? cb == null : ca.equals(cb);
    }

    /** 尾部序号段：kind 为 季/部/篇，value 为数值，[start,end) 是「第X季」整段在串中的区间。 */
    private static final class Ordinal {
        final String kind;
        final int value;
        final int start;
        final int end;

        Ordinal(String kind, int value, int start, int end) {
            this.kind = kind;
            this.value = value;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * 在原始串里定位「第X季 / 第X部 / 第X篇」序号段（取最靠后的一个）。
     *
     * <p>只认「第」+数字（阿拉伯或中文）+「季/部/篇」的整段；裸数字编号
     * （{@code 庆余年2}）<b>不</b>在此列 —— 那是续作，交给数字红线拒绝。</p>
     *
     * <p>不要求序号在串尾：序号后面还可能跟运营标签（{@code 庆余年第一季HD}）。</p>
     */
    private static Ordinal findOrdinalSegment(String s) {
        for (int i = s.length() - 1; i >= 2; i--) {
            char c = s.charAt(i);
            if (c != '季' && c != '部' && c != '篇') {
                continue;
            }
            int di = s.lastIndexOf('第', i - 1);
            if (di < 0) {
                continue;
            }
            String mid = s.substring(di + 1, i);
            int value = parseSequenceNumber(mid);
            if (value <= 0) {
                continue;
            }
            return new Ordinal(String.valueOf(c), value, di, i + 1);
        }
        return null;
    }

    private static boolean ordinalEq(Ordinal a, Ordinal b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.kind.equals(b.kind) && a.value == b.value;
    }

    /** 解析序号数字：纯阿拉伯数字，或中文数字（一~九十九）。 */
    private static int parseSequenceNumber(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        boolean allDigit = true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                allDigit = false;
                break;
            }
        }
        if (allDigit) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return parseChineseNumber(s);
    }

    private static int parseChineseNumber(String s) {
        int total = 0;
        int section = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '十') {
                section = (section == 0 ? 1 : section) * 10;
                total += section;
                section = 0;
            } else {
                int d = chineseDigit(c);
                if (d < 0) {
                    return 0;
                }
                section = section * 10 + d;
            }
        }
        return total + section;
    }

    private static int chineseDigit(char c) {
        switch (c) {
            case '一':
                return 1;
            case '二':
                return 2;
            case '三':
                return 3;
            case '四':
                return 4;
            case '五':
                return 5;
            case '六':
                return 6;
            case '七':
                return 7;
            case '八':
                return 8;
            case '九':
                return 9;
            default:
                return -1;
        }
    }

    /**
     * 「长出部分」是否纯修饰（不含形态词、不含任何数字）。
     *
     * <p>最长匹配逐段扫 {@link #MODIFIER_WORDS}：</p>
     * <ul>
     *   <li>含形态词（电影版/TV版/…）→ 直接 false；</li>
     *   <li>出现扫不进词表的字符（含任何数字：{@code 2019}/{@code 2}）→ false；</li>
     *   <li>{@code 1080p} 这类含数字的画质词整词在表内，可命中。</li>
     * </ul>
     */
    private static boolean isPureModifier(String tail) {
        if (tail == null || tail.isEmpty()) {
            return false;
        }
        for (String f : FORM_CHANGE_WORDS) {
            if (tail.contains(f)) {
                return false;
            }
        }
        int i = 0;
        int n = tail.length();
        while (i < n) {
            boolean matched = false;
            int maxLen = Math.min(MAX_MODIFIER_LEN, n - i);
            for (int len = maxLen; len >= 1; len--) {
                if (MODIFIER_WORDS.contains(tail.substring(i, i + len))) {
                    i += len;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }
}
