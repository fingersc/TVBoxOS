package com.github.tvbox.osc.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 观看历史「同名合并」用的片名键（v99-r46 建立，r219 与全项目片名口径统一）。
 *
 * <p><b>要解决的问题</b>（用户实测反馈）：同一部片在不同源/不同设备上的写法有差异，
 * 历史里于是出现好几张「意思完全一样」的卡片：</p>
 * <pre>
 *   现在就出发第四季 / 现在就出发 第四季 / 现在就出发 第4季 / 现在就出发第4季
 * </pre>
 * <p>这些变体各占一个历史名额（档位 15/30/50），把其它影片的记录挤掉，
 * 表现为「历史里能看到的片变少了」+「同一个片出现好几张」。两端其实是同一个病根：
 * <b>变体没有被识别成同一个片</b>。</p>
 *
 * <p><b>★ r219：为什么改为委托 {@link ShowTitleKey#familyKey(String)}</b>：切源候选池
 * （{@code [FB] poolKey}）与详情兜底缓存早已用 {@code familyKey} 判同一部，历史却用
 * 本类旧口径 —— 旧口径<b>不剥标点与运营标签</b>，于是同一部片
 * {@code 有人刚好提起我吗？} / {@code 有人刚好提起我吗} 在切源时能互切、在历史里却是
 * 两张卡片（真机日志：{@code hopDiffName 「有人刚好提起我吗?」->「有人刚好提起我吗」}）。
 * 现在先把片名交给 {@code familyKey}，两套口径合一。</p>
 *
 * <p><b>归一化规则</b>（只用于「同名判定」这个<b>键</b>，不改变界面显示的片名）：</p>
 * <ol>
 *   <li>先走 {@link ShowTitleKey#familyKey(String)}：剥运营标签（{@code 【全集】}/{@code HD}/
 *       {@code 完结} 等，带词边界保护）、删全部标点与分隔符（含全角/半角问号）、
 *       删空白、统一小写，并把「语言」「第N季/部/篇」抽成红线字段；</li>
 *   <li>再做本类原有收尾：不可见字符清理、全角字母数字 → 半角
 *       （{@code 第４季} ≡ {@code 第4季}）、{@code 第N季/第N期/第N部/第N篇} → 阿拉伯数字
 *       （{@code 第十二期} ≡ {@code 第12期}）。</li>
 * </ol>
 *
 * <p><b>刻意不做的事</b>（避免把真正不同的片误并；这些红线由 {@code familyKey} 与本类
 * 共同保证）：</p>
 * <ul>
 *   <li>不合并「有季号」与「无季号」（{@code 庆余年} ≠ {@code 庆余年 第一季}）；</li>
 *   <li>不合并不同季/部/篇的序号（{@code 第一季} ≠ {@code 第二季}）；</li>
 *   <li>不合并不同语言标注（{@code 国语版} ≠ {@code 粤语版}；有标注 ≠ 无标注 ——
 *       否则不同音轨会被串成一部）；</li>
 *   <li>不合并不同年份或裸数字续作（{@code 隐秘而伟大(2020)} ≠ {@code 隐秘而伟大}、
 *       {@code 庆余年2} ≠ {@code 庆余年}）；</li>
 *   <li>不合并形态词（{@code 电影版}/{@code TV版}/{@code 剧场版} 与无形态词）；</li>
 *   <li>{@code 全集人生} 这类「标签词当正文」的片名受词边界规则保护，不会被剥成 {@code 人生}。</li>
 * </ul>
 *
 * <p><b>已知代价</b>：仅差标点的两个条目（如 {@code 北京·爱情故事} 与
 * {@code 北京爱情故事}）会被认作同一部 —— 与切源候选池的既有口径一致，换取
 * 「同一部剧永远只有一张历史卡」。</p>
 */
public final class VodNameNormalizer {
    private VodNameNormalizer() {
    }

    /** 连续空白（含删掉不可见字符后剩下的普通空白）。 */
    private static final Pattern WS = Pattern.compile("\\s+");

    /** 「第&lt;中文数字&gt;{季,期,部,篇}」——只匹配中文数字，阿拉伯数字写法原样保留。 */
    private static final Pattern CN_ORDINAL =
            Pattern.compile("第([零〇一二三四五六七八九十百]+)([季期部篇])");

    /** 中文数字位值表（索引 +1 即数值；「零」与「〇」都按 0 处理，不走此表）。 */
    private static final String CN_DIGITS = "一二三四五六七八九";

    /**
     * 归一化片名。{@code null} / 空串返回 {@code ""}。
     *
     * @param raw 原始片名（可以是 null）
     * @return 用于同名判定的键
     */
    public static String normalize(String raw) {
        if (raw == null || raw.length() == 0) {
            return "";
        }
        // ★ r219：历史「同名」键先走项目统一的片名键（familyKey）—— 剥运营标签、删全部
        //   标点与分隔符、统一小写，并把「语言」「第N季/部/篇」抽成红线字段。
        //   与切源候选池 / 详情兜底缓存同口径，修「有人刚好提起我吗？」与
        //   「有人刚好提起我吗」在历史里分成两张卡片的根因。
        String s = ShowTitleKey.familyKey(raw);
        if (s.length() == 0) {
            return "";
        }
        // 下面几步是历史键原有的收尾，familyKey 不做（或只对部分情形做），必须保留：
        //  · 不可见字符：familyKey 归一化后为空时会回退 trim 原串，原串仍可能夹 NBSP/零宽；
        s = s
                .replace('\u00A0', ' ')   // 不换行空格（r28）
                .replace('\u3000', ' ')   // 全角空格
                .replace("\u200B", "")    // 零宽空格（r28）
                .replace("\u200C", "")    // 零宽非连接符（r28）
                .replace("\u200D", "")    // 零宽连接符（r28）
                .replace("\uFEFF", "");   // BOM（r28）
        // 全部空白一律删除：不同源的片名差异十有八九是「有没有空格 / 空格在哪」
        s = WS.matcher(s).replaceAll("");
        // 全角字母数字 → 半角（第４季 → 第4季；familyKey 保留全角字母/数字）
        s = toHalfWidth(s);
        // 「第<中文数字>{季,期,部,篇}」→ 阿拉伯数字（第十二期 → 第12期；
        // 季/部/篇 familyKey 已编码，这里主要兜「期」与普通文本）
        s = cnOrdinalToArabic(s);
        return s.trim();
    }

    /** 全角（U+FF01–U+FF5E）逐字符转半角；其余原样。 */
    private static String toHalfWidth(String s) {
        boolean hit = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= '\uFF01' && s.charAt(i) <= '\uFF5E') {
                hit = true;
                break;
            }
        }
        if (!hit) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append((c >= '\uFF01' && c <= '\uFF5E') ? (char) (c - 0xFEE0) : c);
        }
        return sb.toString();
    }

    /** 把「第&lt;中文数字&gt;{季,期,部,篇}」里的中文数字换成阿拉伯数字；解析失败原样保留。 */
    private static String cnOrdinalToArabic(String s) {
        Matcher m = CN_ORDINAL.matcher(s);
        boolean found = m.find();
        if (!found) {
            return s;
        }
        m.reset();
        StringBuffer sb = new StringBuffer(s.length() + 8);
        while (m.find()) {
            int n = chineseToArabic(m.group(1));
            // 替换串只含数字与中文，无 $ \ ，appendReplacement 不会误解
            m.appendReplacement(sb, n >= 0 ? "第" + n + m.group(2) : m.group(0));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 中文数字 → 阿拉伯数字，支持 0–999（十 / 百 位值法）。
     *
     * <p>例：十→10、十二→12、二十→20、二十一号→21、一百→100、一百二十→120。
     * 出现非数字字符（含「万」等超出范围的量词）返回 -1，调用方按「原样保留」处理。</p>
     */
    static int chineseToArabic(String cn) {
        if (cn == null || cn.length() == 0) {
            return -1;
        }
        int total = 0;
        int num = 0;
        boolean any = false;
        for (int i = 0; i < cn.length(); i++) {
            char c = cn.charAt(i);
            if (c == '十') {
                total += (num == 0 ? 1 : num) * 10;
                num = 0;
                any = true;
            } else if (c == '百') {
                total += (num == 0 ? 1 : num) * 100;
                num = 0;
                any = true;
            } else {
                int d = cnDigit(c);
                if (d < 0) {
                    return -1;
                }
                num = d;
                any = true;
            }
        }
        return any ? total + num : -1;
    }

    /** 单个中文数字字符 → 数值；「零」「〇」→ 0；非数字 → -1。 */
    private static int cnDigit(char c) {
        if (c == '零' || c == '〇') {
            return 0;
        }
        int d = CN_DIGITS.indexOf(c);
        return d < 0 ? -1 : d + 1;
    }
}
