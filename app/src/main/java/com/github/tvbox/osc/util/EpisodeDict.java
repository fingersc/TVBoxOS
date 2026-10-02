package com.github.tvbox.osc.util;

import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 综艺"播出日期 ↔ 期数"离线字典。
 *
 * <p>背景：同一档综艺在不同采集源里命名不一致（源A 用 {@code 20260809}，源B 用 {@code 第8期}）。
 * {@link EpisodeNameMatcher} 已能处理"两源排列顺序一致"的常见情况；当两源顺序不一致、
 * 按序对齐的单调性校验失败时，可用本字典做精确换算。</p>
 *
 * <p>字典由离线脚本 {@code tools/gen_variety_dict.py} 生成，格式：
 * <pre>
 * {
 *   "_meta": { "show_count": 112, "entry_count": 1764, "window": 30 },
 *   "by_date": { "忙忙碌碌寻宝藏|20260809": 8, ... }
 * }
 * </pre>
 * 其中 {@code by_date} 是<b>唯一</b>被读取的段落；
 * 生成脚本默认按"滚动窗口"（每档保留最近 {@code window} 期）裁剪，使体积恒定不增长。
 * </p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>懒加载：首次查询才读文件，避免影响冷启动；</li>
 *   <li>失败降级：文件缺失/损坏时字典视为空，调用方自然回退到 {@link EpisodeNameMatcher}，不影响切源；</li>
 *   <li>纯本地：不发起任何网络请求，切源速度不受影响。</li>
 * </ul>
 * </p>
 */
public final class EpisodeDict {

    /** 节目名|日期(YYYYMMDD) -> 期数 */
    private static volatile Map<String, Integer> byDate = null;
    private static volatile boolean loaded = false;

    private EpisodeDict() {
    }

    /**
     * 从指定文件加载字典（JSON）。可放在 App 私有目录，由更新逻辑写入。
     *
     * @param jsonFile 字典文件
     * @return 是否加载成功
     */
    public static synchronized boolean load(File jsonFile) {
        if (jsonFile == null || !jsonFile.exists() || !jsonFile.isFile()) {
            byDate = new HashMap<>();
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
            byDate = new HashMap<>();
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

    /** 直接注入 JSON 字符串（便于测试或从配置下载后内存注入）。 */
    public static synchronized boolean parseJson(String json) {
        Map<String, Integer> map = new HashMap<>();
        try {
            if (!TextUtils.isEmpty(json)) {
                JSONObject root = new JSONObject(json);
                JSONObject bd = root.optJSONObject("by_date");
                if (bd != null) {
                    Iterator<String> keys = bd.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        int v = bd.optInt(k, -1);
                        if (v > 0) {
                            map.put(k, v);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        byDate = map;
        loaded = true;
        return !map.isEmpty();
    }

    /** 是否已加载且有数据。 */
    public static boolean isReady() {
        return loaded && byDate != null && !byDate.isEmpty();
    }

    /**
     * 查询：给定节目名与播出日期，返回对应期数。
     *
     * @param showName 节目名（需与字典中的键一致；内部做去空格容错）
     * @param date     播出日期，形如 {@code 20260809}
     * @return 期数；查不到返回 -1
     */
    public static int lookup(String showName, String date) {
        if (TextUtils.isEmpty(showName) || TextUtils.isEmpty(date)) {
            return -1;
        }
        Map<String, Integer> map = byDate;
        if (map == null || map.isEmpty()) {
            return -1;
        }
        Integer v = map.get(showName.trim() + "|" + date.trim());
        return v == null ? -1 : v;
    }

    /**
     * 便捷方法：把当前集名解析为日期，再查字典得到期数。
     * 仅当当前集名确实是日期格式时才有意义。
     *
     * @param showName    节目名
     * @param currentName 当前集名（如 {@code 忙忙碌碌寻宝藏20260809} 或 {@code 20260809期}）
     * @return 期数；无法解析或查不到返回 -1
     */
    public static int lookupBySeriesName(String showName, String currentName) {
        String date = extractDate(currentName);
        if (TextUtils.isEmpty(date)) {
            return -1;
        }
        return lookup(showName, date);
    }

    /** 从任意名称中提取 8 位播出日期（YYYYMMDD）。 */
    public static String extractDate(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<!\\d)(20\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(?!\\d)")
                .matcher(name);
        return m.find() ? m.group(0) : "";
    }

    /** 清空并释放（用于字典更新后重载）。 */
    public static synchronized void reset() {
        byDate = null;
        loaded = false;
    }
}
