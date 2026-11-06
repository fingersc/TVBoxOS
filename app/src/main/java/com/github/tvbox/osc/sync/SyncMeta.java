package com.github.tvbox.osc.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步元数据（sync.meta）。
 *
 * <p>v4.1 引入：记录每台设备的「最后上线时间」与「已看到的数据水位」。
 * 用途：
 * <ul>
 *     <li>墓碑回收：当所有活跃设备都已看到某个墓碑（seenHlc ≥ tomb.hlc）时，可在本地/云端合并时删除它；</li>
 *     <li>30 天回归策略：某设备超过 30 天未上线，再次上线时默认只下载、不上传本地数据，避免把
 *     过期删除重新带回云端。</li>
 * </ul>
 *
 * <p>注意：sync.meta 只是<b>提示</b>，WebDAV 没有多文件事务。真正的数据一致性仍由各 scope 的
 * 内容合并保证；meta 丢失/损坏时退化为「按 30 天年龄回收墓碑」，不影响正确性。</p>
 */
public final class SyncMeta {

    public static final int SCHEMA = 1;
    public static final long STALE_MS = 30L * 24 * 3600 * 1000;

    public int schema = SCHEMA;
    public String owner = "";
    public long resetHlcPhysical = 0L;
    public int resetHlcCounter = 0;
    public List<Device> devices = new ArrayList<>();

    /** 仅内存标记：false 表示读取失败/内容不可信，本轮不要回写覆盖云端 meta。不参与序列化。 */
    public transient boolean reliable = true;

    public static final class Device {
        public String id = "";
        /** 最后一次成功同步的可信时间（毫秒） */
        public long lastSeenAt = 0L;
        /** 该设备已经看到的记录类墓碑水位 */
        public long seenRecordsPhysical = 0L;
        public int seenRecordsCounter = 0;
        /** 该设备已经看到的收藏类墓碑水位 */
        public long seenCollectsPhysical = 0L;
        public int seenCollectsCounter = 0;
    }

    public SyncMeta() {
    }

    public SyncMeta(String owner) {
        this.owner = owner == null ? "" : owner;
    }

    public Device device(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (devices == null) {
            devices = new ArrayList<>();
        }
        for (Device d : devices) {
            if (d != null && id.equals(d.id)) {
                return d;
            }
        }
        return null;
    }

    public Device ensureDevice(String id) {
        Device d = device(id);
        if (d == null) {
            d = new Device();
            d.id = id == null ? "" : id;
            if (devices == null) {
                devices = new ArrayList<>();
            }
            devices.add(d);
        }
        return d;
    }

    /**
     * 该设备是否已超过 30 天未上线。
     *
     * <p>注意：<b>没有 meta 记录不等于离线</b>——升级到 v4.1 后第一次同步时 meta 可能还不存在，
     * 若把它当 stale 会走「只下载」，用云端覆盖掉本机尚未上传的数据（严重丢数据）。
     * 因此只有「有记录且 lastSeenAt 早于 30 天前」才算 stale；无记录按正常同步处理。</p>
     */
    public boolean isStale(String id, long nowMs) {
        Device d = device(id);
        if (d == null || d.lastSeenAt <= 0L) {
            return false;
        }
        return d.lastSeenAt < nowMs - STALE_MS;
    }

    public void touch(String id, long nowMs) {
        Device d = ensureDevice(id);
        if (nowMs > d.lastSeenAt) {
            d.lastSeenAt = nowMs;
        }
    }

    /**
     * 记录本设备已看到的 history 水位。
     *
     * <p>★ v4.2.3：返回「水位是否真的推进」—— 调用方据此决定 sync.meta 要不要落盘。
     * 原来无论有没有推进都无条件置 metaDirty，使「6 小时一次」的门槛失效，
     * 实测 100 条上传里 42 条是 sync.meta（白烧配额）。</p>
     */
    public boolean markSeenRecords(String id, long physical, int counter) {
        Device d = ensureDevice(id);
        if (physical > d.seenRecordsPhysical
                || (physical == d.seenRecordsPhysical && counter > d.seenRecordsCounter)) {
            d.seenRecordsPhysical = physical;
            d.seenRecordsCounter = counter;
            return true;
        }
        return false;
    }

    /** 同 {@link #markSeenRecords}，针对收藏。 */
    public boolean markSeenCollects(String id, long physical, int counter) {
        Device d = ensureDevice(id);
        if (physical > d.seenCollectsPhysical
                || (physical == d.seenCollectsPhysical && counter > d.seenCollectsCounter)) {
            d.seenCollectsPhysical = physical;
            d.seenCollectsCounter = counter;
            return true;
        }
        return false;
    }

    /**
     * 墓碑回收：删除「所有活跃设备都已看到」且已存在超过 minAgeMs 的墓碑。
     * 保守策略：至少要有 2 台活跃设备（单设备账号没有跨设备复活风险，交给 30 天年龄回收即可），
     * 且墓碑存在超过 minAgeMs，避免刚上线的新设备尚未登记就被误判为「全员已见」。
     *
     * <p>★ v4.2.3：返回真正被回收的墓碑条数。回收会改变包内容（必须上传），
     * 所以这也是 sync.meta 需要落盘的条件之一 —— 回收结果要连同新水位一起发布出去。</p>
     */
    public int dropSeenTombs(SyncPayload p, long nowMs, long minAgeMs) {
        if (p == null || p.deleted == null) {
            return 0;
        }
        int before = size(p.deleted.records) + size(p.deleted.collects);
        p.deleted.records = filterSeen(p.deleted.records, nowMs, minAgeMs, true);
        p.deleted.collects = filterSeen(p.deleted.collects, nowMs, minAgeMs, false);
        int after = size(p.deleted.records) + size(p.deleted.collects);
        return before > after ? before - after : 0;
    }

    private static int size(List<SyncPayload.Tomb> list) {
        return list == null ? 0 : list.size();
    }

    private List<SyncPayload.Tomb> filterSeen(List<SyncPayload.Tomb> tombs, long nowMs,
                                              long minAgeMs, boolean records) {
        if (tombs == null || tombs.isEmpty()) {
            return tombs;
        }
        List<SyncPayload.Tomb> kept = new ArrayList<>();
        for (SyncPayload.Tomb t : tombs) {
            if (t == null) {
                continue;
            }
            Hlc h = t.hlc();
            boolean oldEnough = h.physical <= 0L || h.physical < nowMs - minAgeMs;
            boolean seen = records ? allSeenRecords(h, nowMs) : allSeenCollects(h, nowMs);
            if (oldEnough && seen) {
                continue;   // 所有活跃设备都已看过，墓碑可回收
            }
            kept.add(t);
        }
        return kept;
    }

    /** 所有活跃设备是否都已看到该墓碑水位（超过 30 天未上线的设备不计，允许其删除失效）。*/
    public boolean allSeenRecords(Hlc tomb, long nowMs) {
        return allSeen(tomb, nowMs, true);
    }

    public boolean allSeenCollects(Hlc tomb, long nowMs) {
        return allSeen(tomb, nowMs, false);
    }

    private boolean allSeen(Hlc tomb, long nowMs, boolean records) {
        if (devices == null || devices.isEmpty()) {
            return false;
        }
        int activeCount = 0;
        for (Device d : devices) {
            if (d == null || d.id == null || d.id.isEmpty()) {
                continue;
            }
            if (d.lastSeenAt <= 0L || d.lastSeenAt < nowMs - STALE_MS) {
                continue;
            }
            activeCount++;
            long p = records ? d.seenRecordsPhysical : d.seenCollectsPhysical;
            int c = records ? d.seenRecordsCounter : d.seenCollectsCounter;
            if (tomb.physical > p || (tomb.physical == p && tomb.counter > c)) {
                return false;
            }
        }
        // 至少 2 台活跃设备都看到，才敢回收；单设备账号交给 30 天年龄回收（避免升级/新设备未登记时误删）。
        return activeCount >= 2;
    }
}
