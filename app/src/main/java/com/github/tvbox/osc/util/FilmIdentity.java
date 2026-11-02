package com.github.tvbox.osc.util;

import java.util.Set;

/**
 * ★ v99-r74：影片身份判定（跨站可用）。
 *
 * <p><b>为什么需要</b>：真机实证 —— 用户看《隐秘而伟大》国产版（ref=28134, 2020），
 * 在搜索页点了**韩国版**（ref=29946, 2013），进详情页的进度恢复机制
 * （{@code migratePlaybackTimeFromOldSource}）把国产版的 <b>431676ms（7 分 11 秒）</b>
 * 搬到了韩国版上 —— 换片不该同步进度。</p>
 *
 * <p>该机制假设「新旧 ref 是同一部片」（同片换源要延续进度），但<b>换片</b>时这个前提不成立。
 * 本类提供"是不是同一部片"的判据，让两条迁移路径都能在换片时停手。</p>
 *
 * <p><b>判据只用跨站稳定的字段</b>：年份（数字，各站一致）与导演（1~2 人，写法统一）。
 * 不用封面（跨站 URL 不同）、不用演员（实测同一部片有 5 种写法：66 人 / 39 人 / 5 人…）。</p>
 *
 * <p><b>信息不足时保守按"同片"处理</b>（返回 true）：宁可延续进度，也不要把正常切源的
 * 进度记忆打断 —— 那是更常见的场景。</p>
 */
public class FilmIdentity {

    private FilmIdentity() {
    }

    /**
     * 演员/导演/类型/地区的<b>交叉</b>匹配。
     *
     * <p>先判"一方包含另一方"（子集），再判交叉：交集 ≥ 2 且 ≥ 较小一侧的一半。
     * 交叉口径是必要的 —— 实测同一部片的演员表有 5 种写法（66 人 vs 5 人、交集 3 人），
     * 纯子集口径会漏掉 21% 的同片对。</p>
     */
    public static boolean metaRelated(String a, String b) {
        if (isEmpty(a) || isEmpty(b)) {
            return false;
        }
        Set<String> sa = splitTags(a);
        Set<String> sb = splitTags(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return false;
        }
        if (sa.containsAll(sb) || sb.containsAll(sa)) {
            return true;
        }
        Set<String> small = sa.size() <= sb.size() ? sa : sb;
        Set<String> big = sa.size() <= sb.size() ? sb : sa;
        int common = 0;
        for (String tag : small) {
            if (big.contains(tag)) {
                common++;
            }
        }
        return common >= 2 && common * 2 >= small.size();
    }

    /**
     * 是不是同一部片。
     *
     * @param yearA/yearB 年份（0 或负数 = 未知）
     * @param directorA/directorB 导演（空 = 未知）
     * @return true = 视为同一部片（可迁移进度）；false = 另一部片（不得迁移）
     */
    public static boolean isSameFilm(int yearA, String directorA, int yearB, String directorB) {
        // 年份：双方都知道且不同 ⇒ 必然不是同一部片
        if (yearA > 0 && yearB > 0 && yearA != yearB) {
            return false;
        }
        // 导演：双方都有且交叉不上 ⇒ 不是同一部片
        if (!isEmpty(directorA) && !isEmpty(directorB) && !metaRelated(directorA, directorB)) {
            return false;
        }
        // 其余（信息不足）⇒ 保守按同片处理
        return true;
    }

    /**
     * ★ r156：切源用的严格影片身份判据。
     *
     * <p>与 {@link #isSameFilm(int, String, int, String)} 的区别：本方法在「信息不足」时
     * 返回 {@code false}，即宁可不自动切，也不把同名翻拍/未知影片当同片。用于电影单片
     * 切源确证（`九门` 这类 title-only 的电影，用户已明确：年份/导演都未知时不允许自动切）。</p>
     *
     * <ul>
     *   <li>双方年份都已知：必须相等；</li>
     *   <li>否则双方导演都已知：必须 {@link #metaRelated(String, String)}；</li>
     *   <li>其余（身份不足）：false。</li>
     * </ul>
     */
    public static boolean isSameFilmStrict(int yearA, String directorA,
                                           int yearB, String directorB) {
        if (isSameFilmConflict(yearA, directorA, yearB, directorB)) {
            return false;
        }
        if (yearA > 0 && yearB > 0) {
            return true;                     // 已由 conflict 排除不相等
        }
        if (!isEmpty(directorA) && !isEmpty(directorB)) {
            return true;                     // 已由 conflict 排除交叉不上
        }
        return false;                        // 身份不足：不自动切
    }

    /**
     * ★ r156：是否存在**硬冲突**（明确不是同一部片）。
     * 只有硬冲突才应把候选源拉黑；身份信息不足只是「不自动切」，不应永久拉黑。
     */
    public static boolean isSameFilmConflict(int yearA, String directorA,
                                             int yearB, String directorB) {
        if (yearA > 0 && yearB > 0 && yearA != yearB) {
            return true;
        }
        if (!isEmpty(directorA) && !isEmpty(directorB) && !metaRelated(directorA, directorB)) {
            return true;
        }
        return false;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static Set<String> splitTags(String raw) {
        Set<String> out = new java.util.HashSet<String>();
        if (raw == null) {
            return out;
        }
        // 注意：Java 的 \s 不匹配 U+3000/NBSP ⇒ 显式补上全角空格
        String[] parts = raw.split("[/,，、|;；\\s\u3000]+");
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
