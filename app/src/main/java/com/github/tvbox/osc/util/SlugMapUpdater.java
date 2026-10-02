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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 节目名 → 站点 slug 映射的<b>运行时自动更新</b>。
 *
 * <p>解决的问题：{@link ShowSlugMap} 的内置映射表是静态快照，
 * <b>新综艺节目上线后不在表里，直连会被跳过</b>。本类在 App 启动后
 * 后台拉取一次站点列表页，自动补齐映射并持久化，做到零人工维护。</p>
 *
 * <p><b>更新策略</b>
 * <ul>
 *   <li>后台线程执行，不阻塞启动与界面；</li>
 *   <li>本地缓存未过期（默认 24h）则跳过，避免每次启动都联网；</li>
 *   <li>远端失败时保留现有映射（内置表 + 上次缓存），功能不受影响；</li>
 *   <li>只做"并集"合并：远端拿到的条目覆盖同名的，其余保留。</li>
 * </ul>
 * </p>
 *
 * <p><b>降级</b>：任何失败都静默返回，绝不影响切源与启动。</p>
 */
public final class SlugMapUpdater {

    /** 缓存有效期：24 小时。 */
    private static final long TTL_MS = 24L * 60 * 60 * 1000;
    /** 缓存文件名。 */
    private static final String CACHE_FILE = "slug_map_remote.json";
    /** 上次更新时间的记录文件。 */
    private static final String STAMP_FILE = "slug_map_stamp.txt";

    /** 列表页地址（主 → 备）。 */
    private static final String[][] LIST_URLS = {
            {"https://www.zyshow.net/dl/index.html",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"},
            {"https://m.zyshow.net/dl/index.html",
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
                            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"},
    };

    /** 匹配 href="/dl/{slug}/" title="{中文名}" 或 /dl/{slug}/" title="..."。 */
    private static final Pattern RE_ITEM =
            Pattern.compile("/dl/([a-z0-9_]+)/\"\\s*title=\"([^\"]{1,60})\"");

    private static File workDir = null;

    private SlugMapUpdater() {
    }

    /** 设置工作目录（与 EpisodeResolveInitializer 一致）。 */
    public static void init(File dir) {
        workDir = dir;
    }

    /**
     * 若缓存未过期则直接加载缓存；否则在后台拉取更新。
     *
     * <p>必须在后台线程调用（{@link #updateIfNeededFromCache()} 可与
     * {@link #scheduleUpdate} 配合）。</p>
     */
    public static void updateIfNeededFromCache() {
        if (workDir == null) return;
        File cache = new File(workDir, CACHE_FILE);
        if (cache.exists() && !isExpired()) {
            // 缓存新鲜：直接用它覆盖内置表
            ShowSlugMap.loadMerged(cache);
        }
    }

    /** 后台更新入口：线程池由调用方提供。 */
    public static void scheduleUpdate(final java.util.concurrent.Executor executor) {
        if (workDir == null) return;
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isExpired()) {
                        // 未过期：只加载缓存，不联网
                        updateIfNeededFromCache();
                        return;
                    }
                    Map<String, String> remote = fetchRemote();
                    if (remote == null || remote.isEmpty()) {
                        // 远端失败：至少用旧缓存兜底
                        updateIfNeededFromCache();
                        return;
                    }
                    saveCache(remote);
                    markUpdated();
                    ShowSlugMap.loadMerged(new File(workDir, CACHE_FILE));
                } catch (Throwable ignored) {
                }
            }
        };
        if (executor != null) {
            executor.execute(task);
        } else {
            task.run();
        }
    }

    // ---------------- 网络 ----------------

    /** 多站点依次尝试抓取列表页并解析映射。 */
    private static Map<String, String> fetchRemote() {
        for (String[] site : LIST_URLS) {
            try {
                String html = httpGet(site[0], site[1]);
                Map<String, String> m = parseListPage(html);
                if (m != null && !m.isEmpty()) {
                    return m;
                }
            } catch (Throwable ignored) {
                // 主站失败自动切备份
            }
        }
        return null;
    }

    /** 从列表页 HTML 解析"节目名 -> slug"。 */
    static Map<String, String> parseListPage(String html) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(html)) {
            return map;
        }
        Matcher m = RE_ITEM.matcher(html);
        while (m.find()) {
            String slug = m.group(1);
            String name = m.group(2);
            if (TextUtils.isEmpty(slug) || TextUtils.isEmpty(name)) continue;
            name = name.trim();
            if (TextUtils.isEmpty(name)) continue;
            // 同名保留首个（与离线脚本策略一致）
            if (!map.containsKey(name)) {
                map.put(name, slug.trim());
            }
        }
        return map;
    }

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

    // ---------------- 缓存与时间戳 ----------------

    private static boolean isExpired() {
        if (workDir == null) return true;
        File stamp = new File(workDir, STAMP_FILE);
        if (!stamp.exists()) return true;
        try {
            long last = Long.parseLong(readText(stamp).trim());
            return System.currentTimeMillis() - last > TTL_MS;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void markUpdated() {
        if (workDir == null) return;
        try {
            writeText(new File(workDir, STAMP_FILE), String.valueOf(System.currentTimeMillis()));
        } catch (Throwable ignored) {
        }
    }

    private static void saveCache(Map<String, String> map) {
        if (workDir == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("source", "runtime");
            JSONObject n2s = new JSONObject();
            for (Map.Entry<String, String> e : map.entrySet()) {
                n2s.put(e.getKey(), e.getValue());
            }
            root.put("name_to_slug", n2s);
            writeText(new File(workDir, CACHE_FILE), root.toString());
        } catch (Throwable ignored) {
        }
    }

    private static String readText(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeText(File f, String s) {
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
            w.write(s);
            w.flush();
        } catch (Throwable ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) {}
        }
    }

    /** 清空缓存（调试用，下次会强制联网）。 */
    public static void clearCache() {
        if (workDir == null) return;
        try {
            new File(workDir, CACHE_FILE).delete();
            new File(workDir, STAMP_FILE).delete();
        } catch (Throwable ignored) {
        }
    }
}
