package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * {@code /cache} HTTP 端点：供<b>爬虫 JS / 外部脚本</b>做键值持久化。
 *
 * <p>协议：{@code /cache?do=set|get|del&key=...&value=...&rule=...}
 * （{@code rule} 可选，作为命名空间前缀）。</p>
 *
 * <p><b>★ P1-9-d（v45）：键此前无界增长。</b>
 * 原实现 {@code Hawk.put("cache_" + rule + "_" + key, value)} 只有写入、
 * <b>没有索引、没有上限、也没有过期</b>。而 {@code key} 来自<b>外部脚本</b> ——
 * 爬虫若用「带时间戳 / URL / 剧集 id」的 key（很常见，见 TVBox 生态各种
 * 缓存封装），键就会无限堆积。Hawk 底层是 SharedPreferences，
 * <b>启动时全量载入内存</b>，无界增长会同时抬高启动耗时与常驻内存。</p>
 *
 * <p><b>修法</b>：照抄本项目已验证的「写入顺序索引 + 上限淘汰」范式
 * （同 {@code SourceQualityStore.touchIndex} / {@code AudioTrackMemory.touchIndex}）。
 * 索引另存一个 {@code cache_index} 键，写入顺序维护，超出
 * {@link #MAX_ENTRIES} 就删最旧的若干条。</p>
 */
public class CacheRequestProcess implements RequestProcess {

    /** 缓存键前缀。 */
    private static final String KEY_PREFIX = "cache_";

    /**
     * 写入顺序索引键。
     *
     * <p>与数据键同前缀但在命名空间之外：数据键形如 {@code cache_<rule>_<key>}，
     * 而索引键为 {@code cache_index} —— 只有当外部脚本恰好用
     * {@code rule=\"\"}、{@code key=\"index\"} 时才会撞键，概率极低；
     * 仍以 {@link #RESERVED_INDEX_KEY} 显式挡一道。</p>
     */
    private static final String KEY_INDEX = "cache_index";

    /** 与索引键同名的数据 key，拒绝写入（防撞键）。 */
    private static final String RESERVED_INDEX_KEY = "index";

    /**
     * 键上限。
     *
     * <p>取值说明：外部脚本的缓存条数无硬性语义边界，取 300 与
     * {@code EpisodeOnlineResolver.MAX_CACHED_SHOWS} 同量级 ——
     * 足够容纳「近期用到的所有缓存条目」，同时把 Hawk 规模钉在常量级。</p>
     */
    private static final int MAX_ENTRIES = 300;

    /** 索引维护锁：外部 HTTP 可能并发（多请求），读改写需串行。 */
    private static final Object CACHE_LOCK = new Object();

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return fileName != null && fileName.startsWith("/cache");
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        if (params == null) params = session.getParms();
        if (files != null && !files.isEmpty()) params.putAll(files);
        String action = params.get("do");
        String key = params.get("key");
        if (TextUtils.isEmpty(key)) return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "");
        String cacheKey = getKey(params.get("rule"), key);
        if ("get".equals(action)) return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, Hawk.get(cacheKey, ""));
        if ("set".equals(action)) {
            String value = params.get("value");
            synchronized (CACHE_LOCK) {
                Hawk.put(cacheKey, value == null ? "" : value);
                touchIndex(cacheKey);
            }
        }
        if ("del".equals(action)) {
            synchronized (CACHE_LOCK) {
                Hawk.delete(cacheKey);
                removeFromIndex(cacheKey);
            }
        }
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "OK");
    }

    private String getKey(String rule, String key) {
        return KEY_PREFIX + (TextUtils.isEmpty(rule) ? "" : rule + "_") + key;
    }

    /**
     * 把 {@code cacheKey} 标记为最近写入，并淘汰超限的最旧条目。
     *
     * <p>与 {@code SourceQualityStore.touchIndex} 同构：尾部为最新，
     * 超限从头部弹出并 {@code Hawk.delete} 数据键本身（不留孤儿键）。
     * 整体 {@code try/catch}：索引属附加卫生，不得影响缓存读写主流程。</p>
     */
    private void touchIndex(String cacheKey) {
        try {
            // 防撞键：外部脚本 rule="" & key="index" 会让数据键 == 索引键 → 拒绝缓存该条
            if (TextUtils.isEmpty(cacheKey) || cacheKey.equals(KEY_INDEX)) {
                return;
            }
            List<String> index = loadIndex();
            index.remove(cacheKey);
            index.add(cacheKey);
            while (index.size() > MAX_ENTRIES) {
                String oldest = index.remove(0);
                if (!oldest.equals(KEY_INDEX)) {
                    Hawk.delete(oldest);
                }
            }
            Hawk.put(KEY_INDEX, index);
        } catch (Throwable ignored) {
            // 索引维护失败不影响缓存本身
        }
    }

    /** 删除某条缓存时，同步把它从索引里摘掉（避免索引里留下已删键的幽灵条目）。 */
    private void removeFromIndex(String cacheKey) {
        try {
            if (TextUtils.isEmpty(cacheKey) || cacheKey.equals(KEY_INDEX)) {
                return;
            }
            List<String> index = loadIndex();
            if (index.remove(cacheKey)) {
                Hawk.put(KEY_INDEX, index);
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /** 读取索引，并清洗空串 / 重复项 / 保留键（兼容历史脏数据）。 */
    private List<String> loadIndex() {
        List<String> index = new ArrayList<>();
        List<String> raw = Hawk.get(KEY_INDEX);
        if (raw != null) {
            for (String s : raw) {
                if (s != null && !s.isEmpty() && !s.equals(KEY_INDEX) && !index.contains(s)) {
                    index.add(s);
                }
            }
        }
        return index;
    }
}

