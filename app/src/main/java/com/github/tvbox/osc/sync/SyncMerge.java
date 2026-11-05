package com.github.tvbox.osc.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 同步合并引擎（纯逻辑，无 Android 依赖，便于单测）。
 *
 * <p>规则（协议 v4）：</p>
 * <ul>
 *   <li>逐条合并，不是整文件覆盖；</li>
 *   <li>同一条记录按 <b>HLC</b>（physical → counter → dev）比较，大者胜。
 *       两端对同一批数据一定算出同一结果，不再受设备墙钟漂移影响；</li>
 *   <li>墓碑只压制 <b>HLC 不高于自己</b> 的条目（{@code item.hlc <= tomb.hlc} 才删），
 *       删除之后又被重新播放/重新写入的条目会正常复活；</li>
 *   <li>墓碑按 HLC 距离回收（{@link #TOMB_KEEP_MS}），不再用本机墙钟；</li>
 *   <li>设置按 key 逐条 LWW，同样用 HLC。</li>
 * </ul>
 */
public final class SyncMerge {

    /**
     * 墓碑保留期（按 HLC 距离，而非本机墙钟）。
     *
     * <p>v4.1 已拍板：墓碑 30 天过期；超过 30 天未同步的设备回归时，允许它持有的
     * 旧删除标记失效（否则墓碑要永久保留，云端体积与流量不可控）。</p>
     */
    public static final long TOMB_KEEP_MS = 30L * 24 * 3600 * 1000;

    /** 历史墓碑硬上限（每类 ≤64KB）。 */
    public static final int MAX_TOMB_RECORDS = 200;

    /** 收藏墓碑硬上限（每类 ≤64KB）。 */
    public static final int MAX_TOMB_COLLECTS = 50;

    /** 进度/设置不做墓碑（v4.1）。 */
    public static final int MAX_TOMB_PROGRESS = 0;
    public static final int MAX_TOMB_SETTINGS = 0;

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
        out.schema = SyncPayload.SCHEMA;
        out.owner = notEmpty(local.owner) ? local.owner : remote.owner;
        out.epoch = Math.max(local.epoch, remote.epoch);
        out.updatedAt = now;
        out.dev = deviceId;

        long maxP;
        int maxC;
        if (local.hlcMaxPhysical >= remote.hlcMaxPhysical) {
            maxP = local.hlcMaxPhysical;
            maxC = local.hlcMaxCounter;
        } else {
            maxP = remote.hlcMaxPhysical;
            maxC = remote.hlcMaxCounter;
        }
        // 墓碑本身也可能携带更高的 HLC（例如删除发生在最近一次同步之后）
        for (SyncPayload.Tomb t : allTombs(local, remote)) {
            Hlc h = t.hlc();
            if (h.physical > maxP || (h.physical == maxP && h.counter > maxC)) {
                maxP = h.physical;
                maxC = h.counter;
            }
        }

        // ── 观看历史 ──
        Map<String, SyncPayload.Tomb> recordTombs = tombMap(local.deleted.records, remote.deleted.records);
        Map<String, SyncPayload.RecordItem> localRecords = indexRecords(local.records);
        Map<String, SyncPayload.RecordItem> remoteRecords = indexRecords(remote.records);
        for (String key : union(localRecords.keySet(), remoteRecords.keySet())) {
            SyncPayload.RecordItem a = localRecords.get(key);
            SyncPayload.RecordItem b = remoteRecords.get(key);
            SyncPayload.Tomb tomb = recordTombs.get(key);
            if (suppressed(tomb, a) || suppressed(tomb, b)) {
                if (tomb != null && !tombBeaten(tomb, a, b)) {
                    continue;
                }
            }
            SyncPayload.RecordItem win = pickRecord(a, b);
            if (win != null) {
                out.records.add(win);
            }
        }
        out.deleted.records = prune(recordTombs, maxP, maxC, MAX_TOMB_RECORDS);

        // ── 播放进度（v4.1：零进度墓碑）──
        // 进度删除不单独传播：单集删除只在本机生效；整片删除由历史墓碑
        // 隐含压制「同一 recordKey 且 HLC 不高于墓碑」的全部进度。
        Map<String, SyncPayload.ProgressItem> localProgress = indexProgress(local.progress);
        Map<String, SyncPayload.ProgressItem> remoteProgress = indexProgress(remote.progress);
        // 兼容旧客户端：仍读取对端携带的进度墓碑（用于压制旧数据），但本端不再生成新进度墓碑。
        Map<String, SyncPayload.Tomb> progressTombs = tombMap(local.deleted.progress, remote.deleted.progress);
        for (String key : union(localProgress.keySet(), remoteProgress.keySet())) {
            SyncPayload.ProgressItem a = localProgress.get(key);
            SyncPayload.ProgressItem b = remoteProgress.get(key);
            SyncPayload.ProgressItem probe = a != null ? a : b;
            SyncPayload.Tomb recordTomb = probe == null ? null
                    : recordTombs.get(recordKey(probe.sourceKey, probe.vodId));
            SyncPayload.Tomb progressTomb = progressTombs.get(key);
            boolean aSuppressed = suppressed(recordTomb, a) || suppressed(progressTomb, a);
            boolean bSuppressed = suppressed(recordTomb, b) || suppressed(progressTomb, b);
            if (aSuppressed && bSuppressed) {
                continue;
            }
            SyncPayload.ProgressItem win = pickProgress(aSuppressed ? null : a, bSuppressed ? null : b);
            if (win != null) {
                out.progress.add(win);
            }
        }
        out.deleted.progress = new ArrayList<>();

        // ── 收藏 ──
        Map<String, SyncPayload.Tomb> collectTombs = tombMap(local.deleted.collects, remote.deleted.collects);
        Map<String, SyncPayload.CollectItem> localCollects = indexCollects(local.collects);
        Map<String, SyncPayload.CollectItem> remoteCollects = indexCollects(remote.collects);
        for (String key : union(localCollects.keySet(), remoteCollects.keySet())) {
            SyncPayload.CollectItem a = localCollects.get(key);
            SyncPayload.CollectItem b = remoteCollects.get(key);
            SyncPayload.Tomb tomb = collectTombs.get(key);
            if (suppressed(tomb, a) || suppressed(tomb, b)) {
                if (tomb != null && !tombBeaten(tomb, a, b)) {
                    continue;
                }
            }
            SyncPayload.CollectItem win = pickCollect(a, b);
            if (win != null) {
                out.collects.add(win);
            }
        }
        out.deleted.collects = prune(collectTombs, maxP, maxC, MAX_TOMB_COLLECTS);

        // ── 设置（v4.1：不做墓碑，最新值覆盖）──
        Map<String, SyncPayload.SettingValue> localSettings = local.settingsMap();
        Map<String, SyncPayload.SettingValue> remoteSettings = remote.settingsMap();
        Map<String, SyncPayload.SettingValue> mergedSettings = new LinkedHashMap<>();
        Set<String> settingKeys = union(localSettings.keySet(), remoteSettings.keySet());
        for (String key : settingKeys) {
            SyncPayload.SettingValue win = pickSetting(localSettings.get(key), remoteSettings.get(key));
            if (win != null) {
                mergedSettings.put(key, win);
            }
        }
        out.setSettingsMap(mergedSettings);
        out.deleted.settings = new ArrayList<>();

        // 最终 hlcMax 覆盖所有输出条目与墓碑（写入方据此推进本地时钟）
        long[] mm = finalMax(out, maxP, maxC);
        out.hlcMaxPhysical = mm[0];
        out.hlcMaxCounter = (int) mm[1];
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
        return a.hlc().compareTo(b.hlc()) >= 0 ? a : b;
    }

    private static SyncPayload.ProgressItem pickProgress(SyncPayload.ProgressItem a, SyncPayload.ProgressItem b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.hlc().compareTo(b.hlc()) >= 0 ? a : b;
    }

    private static SyncPayload.CollectItem pickCollect(SyncPayload.CollectItem a, SyncPayload.CollectItem b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.hlc().compareTo(b.hlc()) >= 0 ? a : b;
    }

    private static SyncPayload.SettingValue pickSetting(SyncPayload.SettingValue a, SyncPayload.SettingValue b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        int cmp = a.hlc().compareTo(b.hlc());
        if (cmp != 0) {
            return cmp > 0 ? a : b;
        }
        String av = a.v == null ? "" : a.v;
        String bv = b.v == null ? "" : b.v;
        return av.compareTo(bv) >= 0 ? a : b;
    }

    // ---------------------------------------------------------------- 墓碑

    /**
     * 墓碑是否压制该条目：只有条目 HLC <b>不高于</b> 墓碑 HLC 时才删。
     *
     * <p>这样"删除后又在别的设备重新播放/重新收藏"的条目不会被旧墓碑误杀。</p>
     */
    private static boolean suppressed(SyncPayload.Tomb tomb, SyncPayload.RecordItem item) {
        if (tomb == null || item == null) {
            return false;
        }
        return compareIgnoringDev(item.hlc(), tomb.hlc()) <= 0;
    }

    private static boolean suppressed(SyncPayload.Tomb tomb, SyncPayload.ProgressItem item) {
        if (tomb == null || item == null) {
            return false;
        }
        return compareIgnoringDev(item.hlc(), tomb.hlc()) <= 0;
    }

    private static boolean suppressed(SyncPayload.Tomb tomb, SyncPayload.CollectItem item) {
        if (tomb == null || item == null) {
            return false;
        }
        return compareIgnoringDev(item.hlc(), tomb.hlc()) <= 0;
    }

    private static boolean suppressed(SyncPayload.Tomb tomb, SyncPayload.SettingValue item) {
        if (tomb == null || item == null) {
            return false;
        }
        return compareIgnoringDev(item.hlc(), tomb.hlc()) <= 0;
    }

    /** 两侧条目里只要有一条压过了墓碑，就说明删除之后又被重新写入。 */
    private static boolean tombBeaten(SyncPayload.Tomb tomb,
                                      SyncPayload.RecordItem a, SyncPayload.RecordItem b) {
        return (a != null && compareIgnoringDev(a.hlc(), tomb.hlc()) > 0)
                || (b != null && compareIgnoringDev(b.hlc(), tomb.hlc()) > 0);
    }

    private static boolean tombBeaten(SyncPayload.Tomb tomb,
                                      SyncPayload.ProgressItem a, SyncPayload.ProgressItem b) {
        return (a != null && compareIgnoringDev(a.hlc(), tomb.hlc()) > 0)
                || (b != null && compareIgnoringDev(b.hlc(), tomb.hlc()) > 0);
    }

    private static boolean tombBeaten(SyncPayload.Tomb tomb,
                                      SyncPayload.CollectItem a, SyncPayload.CollectItem b) {
        return (a != null && compareIgnoringDev(a.hlc(), tomb.hlc()) > 0)
                || (b != null && compareIgnoringDev(b.hlc(), tomb.hlc()) > 0);
    }

    private static boolean tombBeaten(SyncPayload.Tomb tomb,
                                      SyncPayload.SettingValue a, SyncPayload.SettingValue b) {
        return (a != null && compareIgnoringDev(a.hlc(), tomb.hlc()) > 0)
                || (b != null && compareIgnoringDev(b.hlc(), tomb.hlc()) > 0);
    }

    /** 比较 (physical, counter)，忽略 dev —— 墓碑不带 dev，避免同毫秒跨设备漏判。 */
    private static int compareIgnoringDev(Hlc a, Hlc b) {
        if (a.physical != b.physical) {
            return a.physical < b.physical ? -1 : 1;
        }
        if (a.counter != b.counter) {
            return a.counter < b.counter ? -1 : 1;
        }
        return 0;
    }

    private static List<SyncPayload.Tomb> allTombs(SyncPayload a, SyncPayload b) {
        List<SyncPayload.Tomb> out = new ArrayList<>();
        addTombs(out, a);
        addTombs(out, b);
        return out;
    }

    private static void addTombs(List<SyncPayload.Tomb> out, SyncPayload p) {
        if (p == null || p.deleted == null) {
            return;
        }
        addTombList(out, p.deleted.records);
        addTombList(out, p.deleted.progress);
        addTombList(out, p.deleted.collects);
        addTombList(out, p.deleted.settings);
    }

    private static void addTombList(List<SyncPayload.Tomb> out, List<SyncPayload.Tomb> list) {
        if (list == null) {
            return;
        }
        for (SyncPayload.Tomb t : list) {
            if (t != null) {
                out.add(t);
            }
        }
    }

    /** 计算最终 hlcMax = max(基础值, 所有输出条目, 所有墓碑)。 */
    private static long[] finalMax(SyncPayload out, long baseP, int baseC) {
        long p = baseP;
        int c = baseC;
        if (out.records != null) {
            for (SyncPayload.RecordItem it : out.records) {
                Hlc h = it.hlc();
                if (h.physical > p || (h.physical == p && h.counter > c)) {
                    p = h.physical;
                    c = h.counter;
                }
            }
        }
        if (out.progress != null) {
            for (SyncPayload.ProgressItem it : out.progress) {
                Hlc h = it.hlc();
                if (h.physical > p || (h.physical == p && h.counter > c)) {
                    p = h.physical;
                    c = h.counter;
                }
            }
        }
        if (out.collects != null) {
            for (SyncPayload.CollectItem it : out.collects) {
                Hlc h = it.hlc();
                if (h.physical > p || (h.physical == p && h.counter > c)) {
                    p = h.physical;
                    c = h.counter;
                }
            }
        }
        if (out.settings != null) {
            for (SyncPayload.SettingItem it : out.settings) {
                Hlc h = Hlc.of(it.hlcPhysical, it.hlcCounter, "", it.t);
                if (h.physical > p || (h.physical == p && h.counter > c)) {
                    p = h.physical;
                    c = h.counter;
                }
            }
        }
        for (SyncPayload.Tomb t : allTombs(out, null)) {
            Hlc h = t.hlc();
            if (h.physical > p || (h.physical == p && h.counter > c)) {
                p = h.physical;
                c = h.counter;
            }
        }
        return new long[]{p, c};
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
            if (old == null || t.hlc().compareTo(old.hlc()) > 0) {
                map.put(t.key, t);
            }
        }
    }

    /**
     * 墓碑回收：按 HLC 距离保留 {@link #TOMB_KEEP_MS}，并限制每类最多 {@code limit} 条
     * （v4.1：历史 200、收藏 50、进度/设置 0）。用 HLC（可信时间）而非本机墙钟。
     */
    private static List<SyncPayload.Tomb> prune(Map<String, SyncPayload.Tomb> map,
                                                long maxP, int maxC, int limit) {
        List<SyncPayload.Tomb> out = new ArrayList<>();
        if (map == null || map.isEmpty() || limit <= 0) {
            return out;
        }
        long horizon = maxP - TOMB_KEEP_MS;
        for (SyncPayload.Tomb t : map.values()) {
            if (t == null) {
                continue;
            }
            if (t.hlc().physical >= horizon) {
                out.add(t);
            }
        }
        if (out.size() > limit) {
            Collections.sort(out, new Comparator<SyncPayload.Tomb>() {
                @Override
                public int compare(SyncPayload.Tomb a, SyncPayload.Tomb b) {
                    int c = b.hlc().compareTo(a.hlc());   // 新者在前
                    if (c != 0) {
                        return c;
                    }
                    String ka = a.key == null ? "" : a.key;
                    String kb = b.key == null ? "" : b.key;
                    return ka.compareTo(kb);
                }
            });
            return new ArrayList<>(out.subList(0, limit));
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

    // ---------------------------------------------------------------- 进度裁剪

    /**
     * 每部片只保留最近 {@code keepPerVod} 条播放进度记录。
     *
     * <p><b>按「记录」计数，不按「集」聚合</b>（用户明确要求）：同一部片下各线路（flag）
     * 的记录合在一起只留最新的 N 条。理由是「切换线路」和「切源」目的相同（都为了更快的播放），
     * 所以换线路时旧线路的记录会整批迁到新线路并丢弃
     * （见 {@code PlayProgressManager.migrateByFlag}）—— 稳态下每片就是
     * 「N 集 × 当前那一条线路」，不需要再为每条线路各留 N 集。</p>
     *
     * <p>排序改用 HLC；同一 HLC 时按主键字典序，保证两端裁剪结果完全一致。</p>
     *
     * <p>纯函数、无 Android 依赖：本地落库（{@code PlayProgressManager.trimByVod}）
     * 与同步包（{@code HistorySyncManager.truncateMergedProgress}）共用同一套规则。</p>
     */
    public static List<SyncPayload.ProgressItem> keepRecent(
            List<SyncPayload.ProgressItem> all, int keepPerVod) {
        if (all == null || all.isEmpty() || keepPerVod <= 0) {
            return all;
        }
        // 1) 按片分组
        Map<String, List<SyncPayload.ProgressItem>> byVod = new LinkedHashMap<>();
        for (SyncPayload.ProgressItem it : all) {
            if (it == null) {
                continue;
            }
            String k = recordKey(it.sourceKey, it.vodId);
            List<SyncPayload.ProgressItem> list = byVod.get(k);
            if (list == null) {
                list = new ArrayList<>();
                byVod.put(k, list);
            }
            list.add(it);
        }
        List<SyncPayload.ProgressItem> out = new ArrayList<>(all.size());
        for (List<SyncPayload.ProgressItem> list : byVod.values()) {
            if (list.size() <= keepPerVod) {
                out.addAll(list);   // 未超限，整片原样保留
                continue;
            }
            // 2) 片内按 HLC 降序取前 N 条
            List<SyncPayload.ProgressItem> sorted = new ArrayList<>(list);
            Collections.sort(sorted, new Comparator<SyncPayload.ProgressItem>() {
                @Override
                public int compare(SyncPayload.ProgressItem a, SyncPayload.ProgressItem b) {
                    int c = b.hlc().compareTo(a.hlc());
                    if (c != 0) {
                        return c;
                    }
                    return progressKey(a.sourceKey, a.vodId, a.flag, a.playIndex, a.epName)
                            .compareTo(progressKey(b.sourceKey, b.vodId, b.flag, b.playIndex, b.epName));
                }
            });
            out.addAll(sorted.subList(0, keepPerVod));
        }
        return out;
    }

    private static boolean notEmpty(String s) {
        return s != null && s.length() > 0;
    }

    // ---------------------------------------------------------------- 索引

    private static Map<String, SyncPayload.RecordItem> indexRecords(List<SyncPayload.RecordItem> list) {
        Map<String, SyncPayload.RecordItem> map = new HashMap<>();
        if (list == null) {
            return map;
        }
        for (SyncPayload.RecordItem it : list) {
            if (it != null) {
                map.put(recordKey(it.sourceKey, it.vodId), it);
            }
        }
        return map;
    }

    private static Map<String, SyncPayload.ProgressItem> indexProgress(List<SyncPayload.ProgressItem> list) {
        Map<String, SyncPayload.ProgressItem> map = new HashMap<>();
        if (list == null) {
            return map;
        }
        for (SyncPayload.ProgressItem it : list) {
            if (it != null) {
                map.put(progressKey(it.sourceKey, it.vodId, it.flag, it.playIndex, it.epName), it);
            }
        }
        return map;
    }

    private static Map<String, SyncPayload.CollectItem> indexCollects(List<SyncPayload.CollectItem> list) {
        Map<String, SyncPayload.CollectItem> map = new HashMap<>();
        if (list == null) {
            return map;
        }
        for (SyncPayload.CollectItem it : list) {
            if (it != null) {
                map.put(collectKey(it.sourceKey, it.vodId), it);
            }
        }
        return map;
    }
}
