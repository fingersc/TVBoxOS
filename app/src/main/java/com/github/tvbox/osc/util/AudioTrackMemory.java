package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * 音轨记忆。
 *
 * <p><b>键粒度</b>：{@code playKey} 派生自播放进度键，结构为
 * {@code (sourceKey, vodId, flag, playIndex, epName)} —— 也就是<b>一集一个键</b>
 * （Exo 再占 group/track 两个子键）。</p>
 *
 * <p><b>★ P1-9-a：必须限界。</b>此前的实现「只写不删」：
 * 每看一集的音轨选择，就永久新增 SharedPreferences 键，<b>永不淘汰</b>。
 * 追 5 部剧 × 40 集 × 3 个源 ≈ 1200 个键且持续增长。
 * 而 {@code SharedPreferences} 会在 <b>App 启动时全量加载进内存</b>，
 * 无界增长会同时抬高<b>启动耗时</b>与<b>常驻内存</b>。</p>
 *
 * <p><b>做法</b>：照抄本项目 {@code DetailActivity.trimFallbackCacheIndex} 已验证的
 * 「写入顺序索引 + 上限淘汰」范式 —— 维护一张写入顺序表，
 * 超出 {@link #MAX_ENTRIES} 就删最旧的（连同其子键一起删，不留孤儿键）。</p>
 */
public class AudioTrackMemory {
    private static AudioTrackMemory instance;
    private final SharedPreferences prefs;
    private static final String PREFS_NAME = "audio_track_prefs";
    private static final String KEY_GROUP_SUFFIX = "_group";
    private static final String KEY_TRACK_SUFFIX = "_track";

    /** 写入顺序索引（存 playKey 原文，不含 {@code _exo}/{@code _ijk} 后缀）。 */
    private static final String KEY_INDEX = "audio_track_index";

    /**
     * 键上限。
     *
     * <p>取值说明：一个键 ≈ 一集，200 足够覆盖「近期在追的若干部剧 × 多源」，
     * 同时把 SharedPreferences 的规模钉死在可控范围内。</p>
     */
    private static final int MAX_ENTRIES = 200;

    private AudioTrackMemory(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized AudioTrackMemory getInstance(Context context) {
        if (instance == null) {
            instance = new AudioTrackMemory(context);
        }
        return instance;
    }

    public void save(String playKey, int groupIndex, int trackIndex) {
        String base = baseKey(playKey);
        String exoKey = base + "_exo";
        prefs.edit()
                .putInt(exoKey + KEY_GROUP_SUFFIX, groupIndex)
                .putInt(exoKey + KEY_TRACK_SUFFIX, trackIndex)
                .apply();
        touchIndex(base);
    }

    public void save(String playKey, int trackIndex) {
        String base = baseKey(playKey);
        prefs.edit().putInt(base + "_ijk" + KEY_TRACK_SUFFIX, trackIndex).apply();
        touchIndex(base);
    }

    public Pair<Integer, Integer> exoLoad(String playKey) {
        String exoKey = baseKey(playKey) + "_exo";
        int group = prefs.getInt(exoKey + KEY_GROUP_SUFFIX, -1);
        int track = prefs.getInt(exoKey + KEY_TRACK_SUFFIX, -1);
        if (group >= 0 && track >= 0) {
            return Pair.create(group, track);
        }
        return null;
    }

    public Integer ijkLoad(String playKey) {
        return prefs.getInt(baseKey(playKey) + "_ijk" + KEY_TRACK_SUFFIX, -1);
    }

    /**
     * 归一化 base key：调用方可能传入带 {@code _exo}/{@code _ijk} 后缀的串
     * （历史调用方 {@code ExoPlayer} 传入的 playKey 已含后缀），
     * 这里统一剥掉，保证索引与子键命名一致、不会重复计数。
     */
    private static String baseKey(String playKey) {
        String k = playKey == null ? "" : playKey;
        if (k.endsWith("_exo")) {
            k = k.substring(0, k.length() - 4);
        } else if (k.endsWith("_ijk")) {
            k = k.substring(0, k.length() - 4);
        }
        return k;
    }

    /**
     * 把 {@code base} 标记为「最近使用」，并淘汰超限的最旧键。
     *
     * <p>淘汰时<b>必须连子键一起删</b>（{@code _exo_group}/{@code _exo_track}/
     * {@code _ijk_track}），否则会在 SharedPreferences 里留下永不失效的孤儿键 ——
     * 那就等于没做限界。</p>
     */
    private void touchIndex(String base) {
        try {
            List<String> index = new ArrayList<>();
            String raw = prefs.getString(KEY_INDEX, "");
            if (raw != null && !raw.isEmpty()) {
                for (String s : raw.split("\n")) {
                    if (!s.isEmpty()) {
                        index.add(s);
                    }
                }
            }
            index.remove(base);
            index.add(base);

            boolean changed = false;
            SharedPreferences.Editor editor = prefs.edit();
            while (index.size() > MAX_ENTRIES) {
                String oldest = index.remove(0);
                String exoKey = oldest + "_exo";
                String ijkKey = oldest + "_ijk";
                editor.remove(exoKey + KEY_GROUP_SUFFIX);
                editor.remove(exoKey + KEY_TRACK_SUFFIX);
                editor.remove(ijkKey + KEY_TRACK_SUFFIX);
                changed = true;
            }
            if (changed || index.size() > 0) {
                editor.putString(KEY_INDEX, joinIndex(index));
                editor.apply();
            }
        } catch (Throwable ignored) {
            // 索引维护失败不能影响音轨记忆本身（核心功能优先）
        }
    }

    private static String joinIndex(List<String> index) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < index.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(index.get(i));
        }
        return sb.toString();
    }
}
