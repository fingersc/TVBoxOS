package com.github.tvbox.osc.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 同步合并引擎（纯逻辑，无 Android 依赖，便于单测）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>逐条合并，不是整文件覆盖；</li>
 *   <li>同一条记录：updateTime 大者胜；时间相同则比较写入设备 id（字典序大者胜），
 *       保证两端算出的结果一致、不会来回翻；</li>
 *   <li>墓碑（删除标记）时间大于两侧条目时间 ⇒ 删除；</li>
 *   <li>墓碑保留 90 天；</li>
 *   <li>设置按 key 逐条 LWW，值相同则保留原值。</li>
 * </ul>
 */
public final class SyncMerge {

    public static final long TOMB_KEEP_MS = 90L * 24 * 3600 * 1000;

    private SyncMerge() {
    }

    public static String recordKey(String sourceKey, String vodId) {
        return (sourceKey == null ? "" : sourceKey) + "|" + (vodId == null ? "" : vodId);
    }

    public static String progressKey(String sourceKey, String vodId, String flag, int playIndex, String epName) {
        return (sourceKey == null ? "" : sourceKey) + "|" + (vodId == null ? "" : vodId) + "|"
                + (flag == null ? "" : flag) + "|" + playIndex + "|" + (epName == null ? "" : epName);
    }

    public static String collectKey(String sourceKey, String vodId) {
        return recordKey(sourceKey, vodId);
    }

    public static SyncPayload merge(SyncPayload local, SyncPayload remote, String deviceId, long now) {
        SyncPayload out = new SyncPayload();
        out.owner = notEmpty(local.owner) ? local.owner : remote.owner;
        out.epoch = Math.max(local.epoch, remote.epoch);
        out.updatedAt = now;
        out.dev = deviceId;

        // ── 观看历史 ──
        Map<String, SyncPayload.Tomb> recordTombs = tombMap(local.deleted.records, remote.deleted.records);
        Map<String, SyncPayload.RecordItem> localRecords = indexRecords(local.records);
        Map<String, SyncPayload.RecordItem> remoteRecords = indexRecords(remote.records);
        for (String key : union(localRecords.keySet(), remoteRecords.keySet())) {
            SyncPayload.RecordItem a = localRecords.get(key);
            SyncPayload.RecordItem b = remoteRecords.get(key);
            long maxTime = Math.max(a == null ? 0 : a.updateTime, b == null ? 0 : b.updateTime);
            SyncPayload.Tomb tomb = recordTombs.get(key);
            if (tomb != null && tomb.at > maxTime) {
                continue;
            }
            SyncPayload.RecordItem win = pickRecord(a, b);
            if (win != null) {
                out.records.add(win);
            }
        }
        out.deleted.records = prune(recordTombs, now);

        // ── 播放进度 ──
        Map<String, SyncPayload.Tomb> progressTombs = tombMap(local.deleted.progress, remote.deleted.progress);
        Map<String, SyncPayload.ProgressItem> localProgress = indexProgress(local.progress);
        Map<String, SyncPayload.ProgressItem> remoteProgress = indexProgress(remote.progress);
        for (String key : union(localProgress.keySet(), remoteProgress.keySet())) {
            SyncPayload.ProgressItem a = localProgress.get(key);
            SyncPayload.ProgressItem b = remoteProgress.get(key);
            long maxTime = Math.max(a == null ? 0 : a.updateTime, b == null ? 0 : b.updateTime);
            SyncPayload.Tomb tomb = progressTombs.get(key);
            if (tomb != null && tomb.at > maxTime) {
                continue;
            }
            SyncPayload.ProgressItem win = pickProgress(a, b);
            if (win != null) {
                out.progress.add(win);
            }
        }
        out.deleted.progress = prune(progressTombs, now);

        // ── 收藏 ──
        Map<String, SyncPayload.Tomb> collectTombs = tombMap(local.deleted.collects, remote.deleted.collects);
        Map<String, SyncPayload.CollectItem> localCollects = indexCollects(local.collects);
        Map<String, SyncPayload.CollectItem> remoteCollects = indexCollects(remote.collects);
        for (String key : union(localCollects.keySet(), remoteCollects.keySet())) {
            SyncPayload.CollectItem a = localCollects.get(key);
            SyncPayload.CollectItem b = remoteCollects.get(key);
            long maxTime = Math.max(a == null ? 0 : a.updateTime, b == null ? 0 : b.updateTime);
            SyncPayload.Tomb tomb = collectTombs.get(key);
            if (tomb != null && tomb.at > maxTime) {
                continue;
            }
            SyncPayload.CollectItem win = pickCollect(a, b);
            if (win != null) {
                out.collects.add(win);
            }
        }
        out.deleted.collects = prune(collectTombs, now);

        // ── 设置 ──
        Map<String, SyncPayload.Tomb> settingTombs = tombMap(local.deleted.settings, remote.deleted.settings);
        Set<String> settingKeys = union(local.settings.keySet(), remote.settings.keySet());
        for (String key : settingKeys) {
            SyncPayload.SettingValue a = local.settings.get(key);
            SyncPayload.SettingValue b = remote.settings.get(key);
            long maxTime = Math.max(a == null ? 0 : a.t, b == null ? 0 : b.t);
            SyncPayload.Tomb tomb = settingTombs.get(key);
            if (tomb != null && tomb.at > maxTime) {
                continue;
            }
            SyncPayload.SettingValue win = pickSetting(a, b);
            if (win != null) {
                out.settings.put(key, win);
            }
        }
        out.deleted.settings = prune(settingTombs, now);
        return out;
    }

    // ---------------------------------------------------------------- 选择规则

    private static SyncPayload.RecordItem pickRecord(SyncPayload.RecordItem a, SyncPayload.RecordItem b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.updateTime != b.updateTime) {
            return a.updateTime > b.updateTime ? a : b;
        }
        return devCompare(a.dev, b.dev) >= 0 ? a : b;
    }

    private static SyncPayload.ProgressItem pickProgress(SyncPayload.ProgressItem a, SyncPayload.ProgressItem b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.updateTime != b.updateTime) {
            return a.updateTime > b.updateTime ? a : b;
        }
        return devCompare(a.dev, b.dev) >= 0 ? a : b;
    }

    private static SyncPayload.CollectItem pickCollect(SyncPayload.CollectItem a, SyncPayload.CollectItem b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.updateTime != b.updateTime) {
            return a.updateTime > b.updateTime ? a : b;
        }
        return devCompare(a.dev, b.dev) >= 0 ? a : b;
    }

    private static SyncPayload.SettingValue pickSetting(SyncPayload.SettingValue a, SyncPayload.SettingValue b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.t != b.t) {
            return a.t > b.t ? a : b;
        }
        String av = a.v == null ? "" : a.v;
        String bv = b.v == null ? "" : b.v;
        return av.compareTo(bv) >= 0 ? a : b;
    }

    private static int devCompare(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        return x.compareTo(y);
    }

    // ---------------------------------------------------------------- 索引/集合工具

    private static Map<String, SyncPayload.RecordItem> indexRecords(List<SyncPayload.RecordItem> list) {
        Map<String, SyncPayload.RecordItem> map = new LinkedHashMap<>();
        if (list != null) {
            for (SyncPayload.RecordItem it : list) {
                if (it != null) {
                    map.put(recordKey(it.sourceKey, it.vodId), it);
                }
            }
        }
        return map;
    }

    private static Map<String, SyncPayload.ProgressItem> indexProgress(List<SyncPayload.ProgressItem> list) {
        Map<String, SyncPayload.ProgressItem> map = new LinkedHashMap<>();
        if (list != null) {
            for (SyncPayload.ProgressItem it : list) {
                if (it != null) {
                    map.put(progressKey(it.sourceKey, it.vodId, it.flag, it.playIndex, it.epName), it);
                }
            }
        }
        return map;
    }

    private static Map<String, SyncPayload.CollectItem> indexCollects(List<SyncPayload.CollectItem> list) {
        Map<String, SyncPayload.CollectItem> map = new LinkedHashMap<>();
        if (list != null) {
            for (SyncPayload.CollectItem it : list) {
                if (it != null) {
                    map.put(collectKey(it.sourceKey, it.vodId), it);
                }
            }
        }
        return map;
    }

    private static Map<String, SyncPayload.Tomb> tombMap(List<SyncPayload.Tomb> a, List<SyncPayload.Tomb> b) {
        Map<String, SyncPayload.Tomb> map = new HashMap<>();
        putTombs(map, a);
        putTombs(map, b);
        return map;
    }

    private static void putTombs(Map<String, SyncPayload.Tomb> map, List<SyncPayload.Tomb> list) {
        if (list == null) {
            return;
        }
        for (SyncPayload.Tomb t : list) {
            if (t == null || t.key == null) {
                continue;
            }
            SyncPayload.Tomb old = map.get(t.key);
            if (old == null || t.at > old.at) {
                map.put(t.key, t);
            }
        }
    }

    private static List<SyncPayload.Tomb> prune(Map<String, SyncPayload.Tomb> map, long now) {
        List<SyncPayload.Tomb> out = new ArrayList<>();
        for (SyncPayload.Tomb t : map.values()) {
            if (t.at >= now - TOMB_KEEP_MS) {
                out.add(t);
            }
        }
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>();
        if (a != null) {
            out.addAll(a);
        }
        if (b != null) {
            out.addAll(b);
        }
        return out;
    }

    private static boolean notEmpty(String s) {
        return s != null && s.length() > 0;
    }
}
