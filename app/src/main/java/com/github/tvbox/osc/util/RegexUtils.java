package com.github.tvbox.osc.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 运行时正则缓存。
 *
 * <p>用于"正则串来自运行时输入"（采集规则、catchup 规则、代理规则等）的场合：
 * 这类正则无法写成 {@code static final} 常量，但同一串会被反复匹配，
 * 必须缓存，避免每次调用都 {@code Pattern.compile}。</p>
 *
 * <p><b>线程安全 + 有界</b>：原实现用裸 {@code HashMap}，并发 put 有隐患，
 * 且无上限，正则来源随用户配置/站点增长会无界滞留内存。现改为
 * {@link LinkedHashMap}（accessOrder=true）实现的有界 LRU，并加锁访问。</p>
 */
public class RegexUtils {

    /** 缓存上限：正则会随运行时配置增长，必须设上限防止无界占用内存。 */
    private static final int MAX_CACHE_SIZE = 256;

    private static final Map<String, Pattern> patternCache =
            new LinkedHashMap<String, Pattern>(MAX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            };

    /** 缓存键包含 flag，避免同一正则不同 flag 互相覆盖（原实现忽略 flag）。 */
    private static String cacheKey(String regex, int flag) {
        return flag + "\u0001" + regex;
    }

    public static Pattern getPattern(String regex) {
        return getPattern(regex, 0);
    }

    public static Pattern getPattern(String regex, int flag) {
        String key = cacheKey(regex, flag);
        synchronized (patternCache) {
            Pattern pattern = patternCache.get(key);
            if (pattern == null) {
                pattern = Pattern.compile(regex, flag);
                patternCache.put(key, pattern);
            }
            return pattern;
        }
    }
}
