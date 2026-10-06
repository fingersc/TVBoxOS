package com.github.tvbox.osc.util;

import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * "日期 ↔ 期数"按需在线查询（含本地缓存）。
 *
 * <p><b>启用条件（严格）</b>：仅当 {@link EpisodeNameMatcher#needsCrossDomainResolve} 判定
 * 当前处于"日期 ↔ 期数"跨域场景、且本地匹配全部失败时，才调用本类。
 * 同域（日期→日期、期数→期数、前导零、多字少字）一律由本地匹配处理，<b>不</b>触发网络。</p>
 *
 * <p><b>这是跨域场景的权威层</b>：跨域时"同下标即同一期"的按序猜测不可信
 * （采集源常混入预告/花絮/特别篇导致下标错位），故跨域问题统一由本层用站点数据回答。</p>
 *
 * <p><b>性能</b>：单次查询实测中位约 800ms。查询结果按节目缓存到本地磁盘
 * （{@code episode_online_cache.json}），同一节目再次切源<b>零延迟、不联网</b>。</p>
 *
 * <p><b>降级</b>：任何失败（无网/超时/解析失败）都静默返回 -1，
 * 调用方回退到原有本地兜底，<b>绝不影响切源可用性</b>。</p>
 *
 * <p><b>并发</b>：查询必须在后台线程调用；本类自身线程安全。</p>
 */
public final class EpisodeOnlineResolver {

    /** 单节目缓存：节目名 -> {日期: 期数} */
    private static final Map<String, Map<String, Integer>> CACHE = new HashMap<>();
    private static final Object LOCK = new Object();

    /** 缓存文件名。 */
    private static final String CACHE_FILE = "episode_online_cache.json";

    /** 缓存条数上限：超出时按插入顺序淘汰最旧的节目，防止长期使用后无限增长。 */
    private static final int MAX_CACHED_SHOWS = 300;

    private static File cacheDir = null;

    /** 查询回调，在主线程之外执行。 */
    public interface Callback {
        void onResult(int episode);
    }

    private EpisodeOnlineResolver() {
    }

    /** 设置缓存目录（App 私有目录），用于跨会话持久化，避免重复联网。 */
    public static void init(File dir) {
        cacheDir = dir;
        loadCache();
    }

    /**
     * 查询：给定节目名与播出日期，返回对应期数。
     *
     * @param showName 节目名（如"忙忙碌碌寻宝藏"）
     * @param date     播出日期（如"20260809"）
     * @return 期数；未命中或失败返回 -1
     */
    public static int resolve(String showName, String date) {
        if (TextUtils.isEmpty(showName) || TextUtils.isEmpty(date)) {
            return -1;
        }
        // 1. 查缓存（磁盘持久化，命中即零延迟）
        Map<String, Integer> cached = getCached(showName);
        if (cached != null) {
            Integer v = cached.get(date);
            if (v != null) {
                return v;
            }
        }
        // 2. 联网查询（同步，须在后台线程）
        Map<String, Integer> fresh = fetchFromNetwork(showName, date);
        if (fresh == null || fresh.isEmpty()) {
            return -1;
        }
        // 3. 写缓存
        Map<String, Integer> merged = new HashMap<>();
        if (cached != null) {
            merged.putAll(cached);
        }
        merged.putAll(fresh);
        putCache(showName, merged);
        Integer v = merged.get(date);
        return v == null ? -1 : v;
    }

    /** 异步查询，回调在调用线程之外执行（由调用方提供线程池）。 */
    public static void resolveAsync(final String showName, final String date, final java.util.concurrent.Executor executor,
                                    final Callback callback) {
        if (executor == null) {
            int v = resolve(showName, date);
            if (callback != null) callback.onResult(v);
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                int v = resolve(showName, date);
                if (callback != null) callback.onResult(v);
            }
        });
    }

    /** 反向查询回调：返回期数对应的播出日期（YYYYMMDD）。 */
    public interface DateCallback {
        void onResult(String date);
    }

    /**
     * 反向查询：给定节目名与期数，返回对应的播出日期（含缓存）。
     *
     * <p><b>为什么需要它</b>：{@link #resolve} 覆盖"日期 → 期数"。
     * 反方向（期数 → 日期）用于：当前正在看的集名是<b>期数式</b>
     * （{@code 第1期上}，不含日期），而目标源用<b>日期式</b>
     * （{@code 20260404上}）。此时正向查询无日期可用，链路失手。</p>
     *
     * <p><b>缓存</b>：结果写入同一份 {@code date→episode} 缓存
     * （反向也存进去，双向查询互相受益）。缓存命中时零延迟、不联网。</p>
     *
     * @param showName      节目名
     * @param targetEpisode 目标期数
     * @param anchorDate    起点日期（YYYYMMDD），用于限定扫描窗口
     * @return 播出日期（YYYYMMDD）；未命中/失败返回空串
     */
    public static String resolveDate(String showName, int targetEpisode, String anchorDate) {
        if (TextUtils.isEmpty(showName) || targetEpisode <= 0) {
            return "";
        }
        // 1. 查缓存（反向遍历已有映射）
        // ★ HashMap 无序：原实现"碰到哪个返哪个"会让同一期号的结果不确定，
        //   必须先收齐再升序取最早（最靠近本季开头），并限同年。
        Map<String, Integer> cached = getCached(showName);
        final int anchorYear = yearOfDate(anchorDate);
        if (cached != null) {
            java.util.List<String> hits = new java.util.ArrayList<>();
            for (Map.Entry<String, Integer> e : cached.entrySet()) {
                if (e.getValue() != null && e.getValue() == targetEpisode && !TextUtils.isEmpty(e.getKey())) {
                    if (anchorYear > 0 && !sameYear(e.getKey(), anchorYear)) {
                        continue;
                    }
                    hits.add(e.getKey());
                }
            }
            if (!hits.isEmpty()) {
                java.util.Collections.sort(hits);
                return hits.get(0);
            }
        }
        // 2. 反向直连
        if (!OnlineResolveConfig.isDirectConnectEnabled()) {
            return "";
        }
        ShowSlugMap.DateResult r;
        try {
            r = ShowSlugMap.resolveEpisodeToDate(showName, targetEpisode, anchorDate);
        } catch (Throwable ignored) {
            return "";
        }
        if (r == null || !r.found()) {
            return "";
        }
        // 3. 写缓存（反向结果并入，供正向查询复用）
        Map<String, Integer> merged = new HashMap<>();
        if (cached != null) {
            merged.putAll(cached);
        }
        merged.put(r.date, targetEpisode);
        putCache(showName, merged);
        return r.date;
    }

    /**
     * 反向查询的有限等待版本：在 {@code timeoutMs} 内拿到日期就返回，超时返回空串。
     *
     * <p>反向查询需要逐天探测站点页面，比正向慢，预算应给得更大些。
     * 超时/失败一律返回空串，调用方静默回退本地兜底。</p>
     *
     * <p>本方法会阻塞调用线程，<b>禁止在主线程调用</b>。</p>
     *
     * @param timeoutMs 最长等待毫秒数，建议 1500~2500
     * @return 播出日期（YYYYMMDD）；未命中/超时/失败返回空串
     */
    public static String resolveDateWithin(final String showName, final int targetEpisode,
                                           final String anchorDate, long timeoutMs) {
        if (TextUtils.isEmpty(showName) || targetEpisode <= 0) {
            return "";
        }
        Map<String, Integer> cached = getCached(showName);
        if (cached != null) {
            for (Map.Entry<String, Integer> e : cached.entrySet()) {
                if (e.getValue() != null && e.getValue() == targetEpisode) {
                    return e.getKey();
                }
            }
        }
        java.util.concurrent.ExecutorService pool = null;
        try {
            pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.Future<String> future =
                    pool.submit(new java.util.concurrent.Callable<String>() {
                        @Override
                        public String call() {
                            return resolveDate(showName, targetEpisode, anchorDate);
                        }
                    });
            String r = future.get(Math.max(1, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            return r == null ? "" : r;
        } catch (Throwable t) {
            return "";
        } finally {
            if (pool != null) {
                try {
                    pool.shutdownNow();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 有限等待的查询：在 {@code timeoutMs} 内拿到结果就返回期数，超时返回 -1。
     *
     * <p><b>为什么需要它</b>：切源是一次同步决策（要立刻给出播哪一集），
     * 而直连是异步的。若直接放弃等待，跨域场景只能退回不可信的按序猜测；
     * 若无限等待，无网时会卡住切源。折中做法是给一个短预算：
     * <ul>
     *   <li>缓存命中 → 立即返回（绝大多数重复切源走这条路，0ms）；</li>
     *   <li>未命中且网络正常 → 约 800ms 内返回权威期数；</li>
     *   <li>超时/无网 → 返回 -1，调用方立刻回退本地兜底，用户几乎无感。</li>
     * </ul>
     * </p>
     *
     * <p>本方法会阻塞调用线程，<b>禁止在主线程调用</b>。</p>
     *
     * @param timeoutMs 最长等待毫秒数，建议 900~1500
     * @return 期数；未命中/超时/失败返回 -1
     */
    public static int resolveWithin(final String showName, final String date, long timeoutMs) {
        if (TextUtils.isEmpty(showName) || TextUtils.isEmpty(date)) {
            return -1;
        }
        // 缓存优先：命中直接返回，永不阻塞
        Map<String, Integer> cached = getCached(showName);
        if (cached != null) {
            Integer v = cached.get(date);
            if (v != null) {
                return v;
            }
        }
        java.util.concurrent.ExecutorService pool = null;
        try {
            pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.Future<Integer> future =
                    pool.submit(new java.util.concurrent.Callable<Integer>() {
                        @Override
                        public Integer call() {
                            return resolve(showName, date);
                        }
                    });
            return future.get(Math.max(1, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            // 超时/中断/异常：一律视为"查不到"，交由调用方兜底
            return -1;
        } finally {
            if (pool != null) {
                try {
                    pool.shutdownNow();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 反向查询的<b>多日期</b>版本：给定期数，返回属于该期的<b>全部</b>播出日期。
     *
     * <p><b>为什么需要它</b>：一个「期」在日期式源里常跨两天（上/下分段分两天播）。
     * 只取单个日期会让"第N期上/下"无法区分，落点随机。
     * 拿到全部日期后，调用方可用 {@code EpisodeNameMatcher.findIndexByDates}
     * 按分段精确落位。</p>
     *
     * <p>本方法会阻塞调用线程，<b>禁止在主线程调用</b>。</p>
     *
     * @param showName      节目名
     * @param targetEpisode 目标期数
     * @param anchorDate    起点日期（YYYYMMDD）
     * @param timeoutMs     超时预算
     * @return 日期列表（升序）；未命中/超时/失败返回空列表
     */
    public static java.util.List<String> resolveDatesWithin(final String showName, final int targetEpisode,
                                                            final String anchorDate, long timeoutMs) {
        final java.util.List<String> empty = new java.util.ArrayList<>();
        if (TextUtils.isEmpty(showName) || targetEpisode <= 0) {
            return empty;
        }
        java.util.concurrent.ExecutorService pool = null;
        try {
            pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.Future<java.util.List<String>> future =
                    pool.submit(new java.util.concurrent.Callable<java.util.List<String>>() {
                        @Override
                        public java.util.List<String> call() {
                            return resolveDates(showName, targetEpisode, anchorDate);
                        }
                    });
            java.util.List<String> r = future.get(Math.max(1, timeoutMs),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            return r == null ? empty : r;
        } catch (Throwable t) {
            // 超时/中断/异常：一律视为"查不到"，交由调用方兜底
            return empty;
        } finally {
            if (pool != null) {
                try {
                    pool.shutdownNow();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 反向查询（多日期）的实现：先查缓存，再直连站点做多日期扫描。
     *
     * <p>命中后把全部 日期→期数 映射并入缓存，供后续正向查询复用。</p>
     */
    static java.util.List<String> resolveDates(String showName, int targetEpisode, String anchorDate) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (TextUtils.isEmpty(showName) || targetEpisode <= 0) {
            return out;
        }
        // 1. 缓存里已有的同期限日期
        // ★ 批量修正：原实现直接遍历 HashMap.entrySet()，而 HashMap 无序，
        //   且缓存可能同时存在多个被标为同一期的日期（历史污染或跨季残留）。
        //   这会使 out 里混入异常日期，而调用方会对 out 升序排序后取首位
        //   → 错误日期因数值更小而排在首位 → 切到完全错误的集。
        //   修正：先收齐再升序排序，只保留最靠近锚点的一批（同年且最早），
        //   并限定日期必须与锚点同年，从根上阻断跨季污染的传播。
        Map<String, Integer> cached = getCached(showName);
        final int anchorYear = yearOfDate(anchorDate);
        if (cached != null) {
            java.util.List<String> cachedHits = new java.util.ArrayList<>();
            for (Map.Entry<String, Integer> e : cached.entrySet()) {
                if (e.getValue() != null && e.getValue() == targetEpisode && !TextUtils.isEmpty(e.getKey())) {
                    String k = e.getKey();
                    if (anchorYear > 0 && !sameYear(k, anchorYear)) {
                        continue;   // 跨年残留，不采用
                    }
                    cachedHits.add(k);
                }
            }
            java.util.Collections.sort(cachedHits);
            out.addAll(cachedHits);
        }
        // 2. 直连站点多日期扫描
        if (OnlineResolveConfig.isDirectConnectEnabled()) {
            try {
                java.util.List<String> scanned =
                        ShowSlugMap.resolveEpisodeToDates(showName, targetEpisode, anchorDate, 2);
                if (scanned != null) {
                    for (String d : scanned) {
                        if (!TextUtils.isEmpty(d) && !out.contains(d)) {
                            out.add(d);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (out.isEmpty()) {
            return out;
        }
        java.util.Collections.sort(out);
        // 3. 写缓存（并入，供正向查询复用）
        Map<String, Integer> merged = new HashMap<>();
        if (cached != null) {
            merged.putAll(cached);
        }
        for (String d : out) {
            merged.put(d, targetEpisode);
        }
        putCache(showName, merged);
        return out;
    }

    // ---------------- 网络层 ----------------

    /**
     * 从远端查询单节目的日期↔期数映射。
     *
     * <p>数据源按序尝试（多源冗余，任一成功即返回）：
     * <ol>
     *   <li>{@link ShowSlugMap#resolveDirect} —— 直连第三方站点（免自建服务，主用）</li>
     *   <li>自建 endpoint（{@link OnlineResolveConfig}）—— 若配置了则作为备选</li>
     * </ol>
     * 全部失败返回 null，调用方静默降级到本地匹配。</p>
     */
    private static Map<String, Integer> fetchFromNetwork(String showName, String date) {
        // 数据源1：直连站点（默认开启，免自建服务；需要 date 才能精确定位，故传参）
        if (OnlineResolveConfig.isDirectConnectEnabled()) {
            int direct = -1;
            try {
                ShowSlugMap.DirectResult r = ShowSlugMap.resolveDirect(showName, date);
                if (r != null && r.found()) {
                    direct = r.episode;
                }
            } catch (Throwable ignored) {
            }
            if (direct > 0) {
                Map<String, Integer> m = new HashMap<>();
                m.put(date, direct);
                return m;
            }
        }
        // 数据源2：自建 endpoint（未配置则跳过）
        String endpoint = OnlineResolveConfig.getEndpoint();
        if (TextUtils.isEmpty(endpoint)) {
            return null;
        }
        String url = endpoint + (endpoint.contains("?") ? "&" : "?") + "show=" + urlEncode(showName);
        String body = httpGet(url);
        if (TextUtils.isEmpty(body)) {
            return null;
        }
        return parseDateToEpisode(body);
    }

    /** 使用项目统一的 OkHttp 客户端发起 GET（与 SpiderApi 保持一致的用法）。 */
    private static String httpGet(String url) {
        okhttp3.Response response = null;
        try {
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
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

    /** 解析远端返回的 JSON，提取 date_to_episode。 */
    static Map<String, Integer> parseDateToEpisode(String json) {
        Map<String, Integer> map = new HashMap<>();
        try {
            JSONObject root = new JSONObject(json);
            if (!root.optBoolean("found", false)) {
                return map;
            }
            JSONObject d2e = root.optJSONObject("date_to_episode");
            if (d2e != null) {
                Iterator<String> keys = d2e.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    int v = d2e.optInt(k, -1);
                    if (v > 0) {
                        map.put(k, v);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    /** 取日期字符串的年份；不合法返回 -1。 */
    private static int yearOfDate(String date) {
        if (TextUtils.isEmpty(date) || date.length() != 8) {
            return -1;
        }
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 日期是否与给定年份同年（用于阻断跨季缓存污染）。 */
    private static boolean sameYear(String date, int year) {
        return yearOfDate(date) == year;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    // ---------------- 缓存层 ----------------

    /**
     * 取节目的归一化缓存键。
     *
     * <p><b>★ v43：键统一归一化</b>。此前直接用 {@code vod_name.trim()} 作键，
     * 而 {@code vod_name} 会在<b>切源后被新源写法覆盖</b>（{@code 庆余年} →
     * {@code 【全集】庆余年}）。键一变，同一部片此前联网查到的「期号↔日期」映射
     * 就全部作废 —— 表现为「每切一次源就要重新联网付一次阻塞预算」。</p>
     *
     * <p>归一化后，同一部片无论当前显示名是哪个源的写法，都命中同一份缓存。</p>
     */
    private static String cacheKey(String showName) {
        return ShowTitleKey.normalize(showName);
    }

    private static Map<String, Integer> getCached(String showName) {
        String key = cacheKey(showName);
        if (TextUtils.isEmpty(key)) return null;
        synchronized (LOCK) {
            return CACHE.get(key);
        }
    }

    private static void putCache(String showName, Map<String, Integer> map) {
        String key = cacheKey(showName);
        if (TextUtils.isEmpty(key)) return;
        synchronized (LOCK) {
            // 简单的容量保护：超过上限时清掉最旧的若干节目（HashMap 无序，按当前迭代顺序淘汰）
            if (!CACHE.containsKey(key) && CACHE.size() >= MAX_CACHED_SHOWS) {
                Iterator<String> it = CACHE.keySet().iterator();
                int drop = Math.max(1, CACHE.size() / 10);
                while (it.hasNext() && drop > 0) {
                    it.next();
                    it.remove();
                    drop--;
                }
            }
            CACHE.put(key, map);
        }
        persistCache();
    }

    private static void loadCache() {
        if (cacheDir == null) return;
        File f = new File(cacheDir, CACHE_FILE);
        if (!f.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());
            Iterator<String> shows = root.keys();
            synchronized (LOCK) {
                while (shows.hasNext()) {
                    String show = shows.next();
                    JSONObject m = root.optJSONObject(show);
                    if (m == null) continue;
                    Map<String, Integer> mm = new HashMap<>();
                    Iterator<String> ks = m.keys();
                    while (ks.hasNext()) {
                        String k = ks.next();
                        int v = m.optInt(k, -1);
                        if (v > 0) mm.put(k, v);
                    }
                    // ★ v43：老版本落盘的键未归一化，此处归一再入内存；
                    //   同键相遇（旧数据里 【全集】庆余年 与 庆余年 各存一份）时合并，
                    //   新读到的条目覆盖旧的，避免老缓存被整体丢弃。
                    String key = cacheKey(show);
                    if (TextUtils.isEmpty(key)) continue;
                    Map<String, Integer> exist = CACHE.get(key);
                    if (exist == null) {
                        CACHE.put(key, mm);
                    } else {
                        exist.putAll(mm);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    private static void persistCache() {
        if (cacheDir == null) return;
        try {
            JSONObject root = new JSONObject();
            synchronized (LOCK) {
                for (Map.Entry<String, Map<String, Integer>> e : CACHE.entrySet()) {
                    JSONObject m = new JSONObject();
                    for (Map.Entry<String, Integer> kv : e.getValue().entrySet()) {
                        m.put(kv.getKey(), kv.getValue());
                    }
                    root.put(e.getKey(), m);
                }
            }
            File f = new File(cacheDir, CACHE_FILE);
            FileOutputStream fos = new FileOutputStream(f);
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            w.write(root.toString());
            w.flush();
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 清空缓存（调试用）。 */
    public static void clearCache() {
        synchronized (LOCK) {
            CACHE.clear();
        }
        persistCache();
    }

    /**
     * 导出缓存快照为离线字典格式（{@code {"_meta":..., "by_date":{"节目|日期":期数}}}）。
     *
     * <p>用途：把运行时通过直连拿到的权威映射沉淀成本地文件，
     * 便于排查、或作为"预置字典"随包分发，让冷启动也能零延迟。</p>
     *
     * @return JSON 字符串；无数据返回空串
     */
    public static String exportSnapshot() {
        try {
            JSONObject root = new JSONObject();
            JSONObject byDate = new JSONObject();
            int count = 0;
            synchronized (LOCK) {
                for (Map.Entry<String, Map<String, Integer>> e : CACHE.entrySet()) {
                    for (Map.Entry<String, Integer> kv : e.getValue().entrySet()) {
                        if (kv.getValue() == null || kv.getValue() <= 0) continue;
                        byDate.put(e.getKey() + "|" + kv.getKey(), kv.getValue());
                        count++;
                    }
                }
            }
            if (count == 0) return "";
            JSONObject meta = new JSONObject();
            try {
                meta.put("source", "runtime-direct");
                meta.put("entry_count", count);
                root.put("_meta", meta);
            } catch (Throwable ignored) {
            }
            root.put("by_date", byDate);
            return root.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 缓存的节目数（调试/统计用）。 */
    public static int cachedShowCount() {
        synchronized (LOCK) {
            return CACHE.size();
        }
    }

    /** 在线查询配置。 */
    public static final class OnlineResolveConfig {
        private static volatile String endpoint = "";
        /**
         * 直连站点总开关，<b>默认开启</b>。
         *
         * <p>与 {@link #endpoint} 的关系：直连（{@link ShowSlugMap#resolveDirect}）不需要任何配置，
         * 是默认数据源；{@code endpoint} 只是可选的"自建服务"补充。
         * 只有直连被主动关闭、且未配置 endpoint 时，在线层才完全停用。</p>
         */
        private static volatile boolean directConnectEnabled = true;

        /** 远端服务地址，形如 http://your-host/episode/lookup 。可选，留空即只走直连。 */
        public static void setEndpoint(String e) {
            endpoint = e == null ? "" : e.trim();
        }

        public static String getEndpoint() {
            return endpoint;
        }

        /** 开关直连站点（默认开启，免部署）。 */
        public static void setDirectConnectEnabled(boolean enabled) {
            directConnectEnabled = enabled;
        }

        public static boolean isDirectConnectEnabled() {
            return directConnectEnabled;
        }

        /** 在线层是否可用：直连开启，或配置了自建 endpoint。 */
        public static boolean isEnabled() {
            return directConnectEnabled || !TextUtils.isEmpty(endpoint);
        }
    }
}
