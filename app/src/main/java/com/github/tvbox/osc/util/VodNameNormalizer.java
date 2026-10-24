package com.github.tvbox.osc.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ v99-r46：观看历史「同名合并」用的片名归一化。
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
 * <p><b>用户口径</b>：不要求严格同名，只要意思一样就算同一个片
 * （20260808 ≡ 第20260808期 ≡ 20260808期 的同理，延伸到片名层）。</p>
 *
 * <p><b>归一化规则</b>（只用于「同名判定」这个<b>键</b>，不改变界面显示的片名）：</p>
 * <ol>
 *   <li>不可见字符删除：NBSP / 零宽空格 / 零宽非连接符 / 零宽连接符 / BOM（v99-r28 已有）；</li>
 *   <li>全角空格（U+3000）按普通空白处理；</li>
 *   <li><b>删除全部空白</b>：「现在就出发 第四季」≡「现在就出发第四季」；</li>
 *   <li><b>全角字母数字 → 半角</b>：「第４季」≡「第4季」；</li>
 *   <li><b>第N季/期/部/篇 里的中文数字 → 阿拉伯数字</b>：「第四季」≡「第4季」，
 *       「第十二期」≡「第12期」。</li>
 * </ol>
 *
 * <p><b>刻意不做的事</b>（避免把真正不同的片误并）：</p>
 * <ul>
 *   <li>不动大小写（英文片名大小写差异通常是不同抓取源的细节，但改大小写风险大于收益）；</li>
 *   <li>不动标点（「北京·爱情故事」与「北京爱情故事」不排除是不同条目）；</li>
 *   <li>不统一「季/期/部/篇」用词（「第四季」与「第四部」不互认）；</li>
 *   <li>不动第N后面的数字本身（「第1季」与「第2季」永远是不同的片）。</li>
 * </ul>
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
        String s = raw
                .replace('\u00A0', ' ')   // 不换行空格（r28）
                .replace('\u3000', ' ')   // 全角空格
                .replace("\u200B", "")    // 零宽空格（r28）
                .replace("\u200C", "")    // 零宽非连接符（r28）
                .replace("\u200D", "")    // 零宽连接符（r28）
                .replace("\uFEFF", "");   // BOM（r28）
        // 全部空白一律删除：不同源的片名差异十有八九是「有没有空格 / 空格在哪」
        s = WS.matcher(s).replaceAll("");
        // 全角字母数字 → 半角（第４季 → 第4季）
        s = toHalfWidth(s);
        // 第N季/期/部/篇 的中文数字 → 阿拉伯数字（第四季 → 第4季）
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
