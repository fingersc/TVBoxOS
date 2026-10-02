package com.github.tvbox.osc.util;

import android.content.Context;
import android.text.TextUtils;

import com.github.tvbox.osc.base.App;

import java.io.File;

/**
 * 综艺"跨源集匹配"解析机制的启动初始化。
 *
 * <p>职责：把离线字典文件、节目名→slug 映射、在线查询缓存目录、在线查询服务地址一次性装配好，
 * 供 {@link EpisodeDict} / {@link ShowSlugMap} / {@link EpisodeOnlineResolver} 使用。</p>
 *
 * <p><b>默认链路（有网优先）</b>：
 * <ol>
 *   <li>第1层 本地同域匹配 —— 毫秒级，绝大多数场景在此终结；</li>
 *   <li>第2层 直连 + 磁盘缓存 —— 跨域（日期 ↔ 期数）问题的<b>权威</b>答案，缓存命中 0ms；</li>
 *   <li>第3层 按序对齐 —— 仅同域兜底，跨域不再猜测。</li>
 * </ol>
 * 离线字典<b>默认不启用</b>（可用 {@link #setOfflineDictEnabled(boolean)} 打开）：
 * 在"使用场景都有网"的前提下，直连完全覆盖字典能力，且免去人工维护与数据膨胀。
 * </p>
 *
 * <p><b>降级原则</b>：本类任何一步失败都只记录日志，<b>绝不抛出</b>，
 * 保证 App 冷启动不受影响。未配置字典/映射时，切源自动退回
 * {@link EpisodeNameMatcher} 的本地能力。</p>
 *
 * <p>使用方式（在 {@code App.onCreate} 中调用一次）：
 * <pre>
 * EpisodeResolveInitializer.init(this);
 * </pre>
 * 在线查询默认走<b>直连站点</b>（免自建服务）；如需额外接自建服务：
 * <pre>
 * EpisodeResolveInitializer.setOnlineEndpoint("http://your-host/episode/lookup");
 * </pre>
 * </p>
 */
public final class EpisodeResolveInitializer {

    /** App 私有目录下的子目录名（存字典与缓存）。 */
    private static final String DIR = "episode";
    /** 离线字典文件名（由 tools/gen_variety_dict.py 生成）。 */
    private static final String DICT_FILE = "variety_dict.json";
    /** 节目名→slug 映射文件名（由 tools/gen_slug_map.py 生成）。 */
    private static final String SLUG_FILE = "slug_map.json";
    /** 是否已初始化，避免重复装配。 */
    private static volatile boolean inited = false;

    /**
     * 离线字典是否启用。默认 <b>false</b>——直连（{@link EpisodeOnlineResolver}）已完全覆盖其能力。
     * 仅在"确实存在长期无网场景"时才需要打开。
     */
    private static volatile boolean offlineDictEnabled = false;

    /**
     * 跨域直连的等待预算（毫秒）。缓存命中不消耗预算；
     * 未命中且网络正常约 800ms 返回；超时立即回退本地兜底。
     */
    private static volatile long crossDomainTimeoutMs = 1200L;

    private EpisodeResolveInitializer() {
    }

    /**
     * 初始化离线字典与在线查询缓存目录。
     *
     * @param context 任意 Context（内部取 applicationContext）
     */
    public static void init(Context context) {
        if (context == null || inited) {
            return;
        }
        inited = true;
        try {
            File base = new File(context.getApplicationContext().getFilesDir(), DIR);
            if (!base.exists()) {
                //noinspection ResultOfMethodCallIgnored
                base.mkdirs();
            }
            // 1) 直连查询的磁盘缓存目录（跨会话持久化，避免重复联网 → 重复切源零延迟）
            EpisodeOnlineResolver.init(base);
            // 2) 离线字典：默认不启用；启用时且文件存在才加载，缺失则字典保持"空"
            if (offlineDictEnabled) {
                File dict = new File(base, DICT_FILE);
                if (dict.exists()) {
                    EpisodeDict.load(dict);
                }
            }
            // 3) 节目名→slug 映射：直连站点解析所需，缺失则直连自动跳过
            File slug = new File(base, SLUG_FILE);
            if (slug.exists()) {
                ShowSlugMap.load(slug);
            }
            // 4) 映射自动更新器：后台补齐新节目（缓存新鲜则直接用缓存，不联网）
            SlugMapUpdater.init(base);
        } catch (Throwable ignored) {
            // 初始化失败一律静默，不影响 App 启动与切源可用性
        }
    }

    /**
     * 开关离线字典层。默认关闭；打开后 {@link EpisodeDict} 参与切源匹配（作为直连的本地前置）。
     *
     * <p>需要在 {@link #init(Context)} 之前或之后都有效果——之后调用时若字典文件已存在会立即加载。</p>
     */
    public static void setOfflineDictEnabled(boolean enabled) {
        offlineDictEnabled = enabled;
        if (!enabled) {
            EpisodeDict.reset();
            return;
        }
        try {
            File f = getDictFile(App.getInstance());
            if (f != null && f.exists()) {
                EpisodeDict.load(f);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 离线字典当前是否启用。 */
    public static boolean isOfflineDictEnabled() {
        return offlineDictEnabled;
    }

    /**
     * 设置跨域直连等待预算（毫秒）。设 0 表示不做阻塞等待，退回纯异步修正模式。
     *
     * @param ms 建议 900~1500；小于 0 时按 0 处理
     */
    public static void setCrossDomainTimeoutMs(long ms) {
        crossDomainTimeoutMs = Math.max(0L, ms);
    }

    /** 跨域直连等待预算（毫秒）。 */
    public static long getCrossDomainTimeoutMs() {
        return crossDomainTimeoutMs;
    }

    /**
     * 配置在线查询服务地址。留空则完全禁用在线查询（只走本地两层）。
     *
     * @param endpoint 形如 {@code http://your-host/episode/lookup}；可为 null
     */
    public static void setOnlineEndpoint(String endpoint) {
        if (TextUtils.isEmpty(endpoint)) {
            EpisodeOnlineResolver.OnlineResolveConfig.setEndpoint("");
        } else {
            EpisodeOnlineResolver.OnlineResolveConfig.setEndpoint(endpoint.trim());
        }
    }

    /** 存储字典/缓存的目录（供字典更新逻辑写入新字典后重载）。 */
    public static File getWorkDir(Context context) {
        if (context == null) return null;
        return new File(context.getApplicationContext().getFilesDir(), DIR);
    }

    /** 字典文件路径（供外部下载更新后调用 {@link EpisodeDict#load(File)}）。 */
    public static File getDictFile(Context context) {
        File dir = getWorkDir(context);
        return dir == null ? null : new File(dir, DICT_FILE);
    }

    /** 重新加载字典（更新字典文件后调用）。 */
    public static void reloadDict(Context context) {
        File f = getDictFile(context);
        if (f == null) return;
        EpisodeDict.reset();
        EpisodeDict.load(f);
    }

    /** slug 映射文件路径。 */
    public static File getSlugFile(Context context) {
        File dir = getWorkDir(context);
        return dir == null ? null : new File(dir, SLUG_FILE);
    }

    /** 重新加载 slug 映射（更新映射文件后调用）。 */
    public static void reloadSlugMap(Context context) {
        File f = getSlugFile(context);
        if (f == null) return;
        ShowSlugMap.reset();
        ShowSlugMap.load(f);
    }

    /**
     * 后台刷新 slug 映射（补齐新上线综艺），零人工维护。
     *
     * <p>策略：缓存 24h 内直接用缓存、不联网；过期则在后台拉取站点列表页并持久化。
     * 任何失败都静默降级，保留内置表与旧缓存。</p>
     *
     * @param executor 执行线程池；传 null 则同步执行（不推荐在主线程传 null）
     */
    public static void refreshSlugMapAsync(java.util.concurrent.Executor executor) {
        try {
            SlugMapUpdater.init(getWorkDir(App.getInstance()));
            SlugMapUpdater.scheduleUpdate(executor);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 导出一份"离线字典快照"文本（直连缓存沉淀格式），可直接另存为 {@code variety_dict.json}。
     *
     * <p>用途：想给 App 预置一份零延迟字典时，先在正常使用中积累直连缓存，
     * 再导出即可得到一份<b>真实可用</b>的字典，无需跑离线脚本、无需人工整理。</p>
     *
     * @return JSON 文本；暂无数据返回空串
     */
    public static String exportDictSnapshot() {
        return EpisodeOnlineResolver.exportSnapshot();
    }
}
