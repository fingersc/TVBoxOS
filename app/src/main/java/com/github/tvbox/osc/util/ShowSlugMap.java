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
     * 按节目名查 slug。会依次尝试精确、去空格、去后缀括号等常见变体。
     *
     * @param showName 节目中文名（如"忙忙碌碌寻宝藏"）
     * @return slug（如"mangmangluluxunbaozang"）；查不到返回空串
     */
    public static String lookup(String showName) {
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
        // 变体3：遍历找"包含"关系（节目名带"第N季"等后缀时的宽松匹配）
        for (Map.Entry<String, String> e : map.entrySet()) {
            String key = e.getKey();
            if (key.length() >= 3 && (noSpace.contains(key) || key.contains(noSpace))) {
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

    /**
     * 直连解析：给定节目名与播出日期，直接从站点抓取对应期数。
     *
     * <p>多站点依次尝试（主站失败自动切备用），全部失败返回 {@code episode=-1}。
     * 必须在后台线程调用。</p>
     *
     * @param showName 节目中文名
     * @param date     播出日期（YYYYMMDD）
     * @return 结果对象；{@link DirectResult#found()} 为 false 表示未命中
     */
    public static DirectResult resolveDirect(String showName, String date) {
        if (TextUtils.isEmpty(showName) || TextUtils.isEmpty(date)) {
            return new DirectResult(-1, null);
        }
        String slug = lookup(showName);
        if (TextUtils.isEmpty(slug)) {
            // 节目不在映射表内，无法直连
            return new DirectResult(-1, null);
        }
        for (Site site : SITES) {
            try {
                String html = httpGet(site.episodeUrl(slug, date), site.ua);
                int ep = parseEpisodeFromTitle(html);
                if (ep > 0) {
                    return new DirectResult(ep, site.name);
                }
            } catch (Throwable ignored) {
                // 单站点失败，继续尝试下一个（备用的意义所在）
            }
        }
        return new DirectResult(-1, null);
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
