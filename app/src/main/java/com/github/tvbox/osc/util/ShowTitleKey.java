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
}
