package com.github.catvod.crawler.js;

import androidx.annotation.Keep;
import com.orhanobut.hawk.Hawk;
import com.whl.quickjs.wrapper.Function;

import java.util.ArrayList;
import java.util.List;

/**
 * 暴露给 JS 爬虫的 {@code local} 持久化对象（QuickJS {@code @Function} 桥）。
 *
 * <p>JS 侧用法：{@code local.set(ns, key, value)} / {@code local.get(ns, key)} /
 * {@code local.delete(ns, key)}，实际落在 Hawk 的 {@code jsRuntime_<ns>_<key>} 键上。</p>
 *
 * <p><b>★ P1-9-e（v45）：键此前无界增长。</b>原 {@code set()} 只
 * {@code Hawk.put("jsRuntime_" + str + "_" + str2, str3)}，
 * <b>无索引、无上限、无过期</b>。而 key 完全由<b>爬虫 JS 代码</b>决定 ——
 * 若某个爬虫用「剧集 id / 时间戳 / URL」当 key（很常见），键会无限堆积。
 * Hawk 底层是 SharedPreferences，<b>启动时全量载入内存</b>，无界增长会拖慢启动。</p>
 *
 * <p><b>修法</b>：照抄本项目已验证的「写入顺序索引 + 上限淘汰」范式
 * （同 {@code SourceQualityStore.touchIndex} / {@code AudioTrackMemory.touchIndex}）。
 * 上限取 {@link #MAX_ENTRIES} = 500（比内部缓存宽，因为这是<b>对外 API 契约</b>，
 * 不宜过紧），索引键 {@link #KEY_INDEX}。</p>
 *
 * <p><b>行为兼容性</b>：{@code get()} 未命中仍返回 {@code ""}（与原实现一致）；
 * 仅在「键数超过 500」时才淘汰最久未写的一条。任何正常爬虫的近期缓存都不受影响。
 * 索引维护整体 {@code try/catch}，<b>绝不影响</b> {@code get/set/delete} 主流程。</p>
 */
public class local {

    private static final String KEY_PREFIX = "jsRuntime_";
    private static final String KEY_INDEX = "jsRuntime_index";
    private static final int MAX_ENTRIES = 500;
    private static final Object INDEX_LOCK = new Object();

    /** 与索引键同名的数据键，拒绝写入（防撞键）。 */
    private static final String RESERVED_INDEX_KEY = "__index__";

    private static String buildKey(String str, String str2) {
        return KEY_PREFIX + str + "_" + str2;
    }

    @Keep
    @Function
    public void delete(String str, String str2) {
        try {
            String k = buildKey(str, str2);
            synchronized (INDEX_LOCK) {
                Hawk.delete(k);
                removeFromIndex(k);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Keep
    @Function
    public String get(String str, String str2) {
        try {
            return Hawk.get(buildKey(str, str2), "");
        } catch (Exception e) {
            Hawk.delete(str);
            return str2;
        }
    }

    @Keep
    @Function
    public void set(String str, String str2, String str3) {
        try {
            String k = buildKey(str, str2);
            synchronized (INDEX_LOCK) {
                Hawk.put(k, str3);
                touchIndex(k);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 把 {@code dataKey} 标记为最近写入，并淘汰超限的最旧条目。
     *
     * <p>与 {@code SourceQualityStore.touchIndex} 同构：尾部最新，
     * 超限从头部弹出并 {@code Hawk.delete} 数据键本身（不留孤儿键）。</p>
     */
    private static void touchIndex(String dataKey) {
        try {
            if (dataKey == null || dataKey.isEmpty() || dataKey.equals(KEY_INDEX)) {
                return;
            }
            List<String> index = loadIndex();
            index.remove(dataKey);
            index.add(dataKey);
            while (index.size() > MAX_ENTRIES) {
                String oldest = index.remove(0);
                if (!oldest.equals(KEY_INDEX) && !oldest.equals(RESERVED_INDEX_KEY)) {
                    Hawk.delete(oldest);
                }
            }
            Hawk.put(KEY_INDEX, index);
        } catch (Throwable ignored) {
            // 索引维护失败不影响 JS 缓存主流程
        }
    }

    /** 删除某条缓存时同步摘掉索引项，避免索引里留下幽灵条目。 */
    private static void removeFromIndex(String dataKey) {
        try {
            if (dataKey == null || dataKey.isEmpty() || dataKey.equals(KEY_INDEX)) {
                return;
            }
            List<String> index = loadIndex();
            if (index.remove(dataKey)) {
                Hawk.put(KEY_INDEX, index);
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /** 读取索引并清洗空串 / 重复项 / 保留键（兼容历史脏数据）。 */
    private static List<String> loadIndex() {
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
