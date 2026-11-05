package com.github.tvbox.osc.sync;

import androidx.annotation.Keep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同步包（明文 JSON，落盘前整体加密）。 */
@Keep
public class SyncPayload {

    /**
     * 同步包 schema。
     *
     * <p>2 = 单文件 data.bin 全量；3 = 按 scope 分文件（history/progress/collects/settings.bin）。</p>
     * <p><b>4 = HLC 协议</b>：条目新增 hlcPhysical/hlcCounter，顶层新增 scope/hlcMax，
     * 用于跨设备时钟不可信时的确定性排序。读旧数据（schema 2/3）仍可反序列化，
     * 旧条目按 updateTime 合成 HLC。</p>
     */
    public static final int SCHEMA = 5;

    public int schema = SCHEMA;
    public String owner = "";
    /** 本包所属 scope（history/progress/collects/settings），用于校验文件名与内容一致。 */
    public String scope = "";
    /** 本包内所有条目（含墓碑）的最大 HLC，接收方据此推进本地时钟。 */
    public long hlcMaxPhysical = 0;
    public int hlcMaxCounter = 0;
    /** 整库代数：云端 +1 表示"该账号历史被整体清空"，各设备需跟随清空。 */
    public long epoch = 0;
    public long updatedAt = 0;
    /** 最后写入该包的设备，用于时间戳相同时的确定性裁决。 */
    public String dev = "";

    public List<RecordItem> records = new ArrayList<>();
    public List<ProgressItem> progress = new ArrayList<>();
    public List<CollectItem> collects = new ArrayList<>();
    public Deleted deleted = new Deleted();
    /**
     * 设置项列表（不用 Map：R8 在 release 里会把 Map 字段收紧成 LinkedHashMap，
     * 而 Gson 反序列化 Map 时给的是 LinkedTreeMap，会抛 IllegalArgumentException）。
     */
    public List<SettingItem> settings = new ArrayList<>();

    @Keep
    public static class SettingItem {
        public String key;
        public String v;
        public String ty;
        public long t;
        public long hlcPhysical;
        public int hlcCounter;
    }

    /** 列表 → Map（内部计算用，不参与序列化）。 */
    public Map<String, SettingValue> settingsMap() {
        Map<String, SettingValue> map = new LinkedHashMap<>();
        if (settings != null) {
            for (SettingItem it : settings) {
                if (it != null && it.key != null) {
                    map.put(it.key, new SettingValue(it.v, it.ty, it.t, it.hlcPhysical, it.hlcCounter));
                }
            }
        }
        return map;
    }

    public void setSettingsMap(Map<String, SettingValue> map) {
        settings = new ArrayList<>();
        if (map != null) {
            for (Map.Entry<String, SettingValue> e : map.entrySet()) {
                SettingItem it = new SettingItem();
                it.key = e.getKey();
                it.v = e.getValue().v;
                it.ty = e.getValue().ty;
                it.t = e.getValue().t;
                it.hlcPhysical = e.getValue().hlcPhysical;
                it.hlcCounter = e.getValue().hlcCounter;
                settings.add(it);
            }
        }
    }

    /**
     * ★ v99-r8：只保留某个 scope 的数据，其余置空 —— 用于「按 scope 分文件」序列化。
     *
     * <p>拆文件后每个文件只该装自己那一类数据；若不裁剪，四个文件会各装全量，
     * 流量反而变成原来的 4 倍。</p>
     */
    public SyncPayload forScope(SyncScope scope) {
        SyncPayload p = new SyncPayload();
        p.schema = SCHEMA;
        p.owner = owner;
        p.scope = scope.name().toLowerCase(java.util.Locale.ROOT);
        p.epoch = epoch;
        p.updatedAt = updatedAt;
        p.dev = dev;
        // hlcMax 必须只统计本 scope 的条目（否则 settings 包会携带 history 的 HLC，
        // 让对端无谓地推进时钟；虽然只增不减不会丢数据，但语义应准确）。
        long[] acc = new long[]{0L, 0L};
        if (scope == SyncScope.HISTORY) {
            if (records != null) {
                p.records = records;
                for (RecordItem it : records) {
                    if (it != null) {
                        bump(acc, it.hlcPhysical > 0 ? it.hlcPhysical : it.updateTime, it.hlcCounter);
                    }
                }
            }
            if (deleted != null && deleted.records != null) {
                p.deleted.records = deleted.records;
                for (Tomb t : deleted.records) {
                    if (t != null) {
                        bump(acc, t.hlcPhysical > 0 ? t.hlcPhysical : t.at, t.hlcCounter);
                    }
                }
            }
        } else if (scope == SyncScope.PROGRESS) {
            if (progress != null) {
                p.progress = progress;
                for (ProgressItem it : progress) {
                    if (it != null) {
                        bump(acc, it.hlcPhysical > 0 ? it.hlcPhysical : it.updateTime, it.hlcCounter);
                    }
                }
            }
            if (deleted != null && deleted.progress != null) {
                p.deleted.progress = deleted.progress;
                for (Tomb t : deleted.progress) {
                    if (t != null) {
                        bump(acc, t.hlcPhysical > 0 ? t.hlcPhysical : t.at, t.hlcCounter);
                    }
                }
            }
        } else if (scope == SyncScope.COLLECT) {
            if (collects != null) {
                p.collects = collects;
                for (CollectItem it : collects) {
                    if (it != null) {
                        bump(acc, it.hlcPhysical > 0 ? it.hlcPhysical : it.updateTime, it.hlcCounter);
                    }
                }
            }
            if (deleted != null && deleted.collects != null) {
                p.deleted.collects = deleted.collects;
                for (Tomb t : deleted.collects) {
                    if (t != null) {
                        bump(acc, t.hlcPhysical > 0 ? t.hlcPhysical : t.at, t.hlcCounter);
                    }
                }
            }
        } else if (scope == SyncScope.SETTINGS) {
            if (settings != null) {
                p.settings = settings;
                for (SettingItem it : settings) {
                    if (it != null) {
                        bump(acc, it.hlcPhysical > 0 ? it.hlcPhysical : it.t, it.hlcCounter);
                    }
                }
            }
            if (deleted != null && deleted.settings != null) {
                p.deleted.settings = deleted.settings;
                for (Tomb t : deleted.settings) {
                    if (t != null) {
                        bump(acc, t.hlcPhysical > 0 ? t.hlcPhysical : t.at, t.hlcCounter);
                    }
                }
            }
        }
        p.hlcMaxPhysical = acc[0];
        p.hlcMaxCounter = (int) acc[1];
        return p;
    }

    private static void bump(long[] acc, long p, int c) {
        if (p > acc[0] || (p == acc[0] && c > acc[1])) {
            acc[0] = p;
            acc[1] = c;
        }
    }

    @Keep
    public static class RecordItem {
        public String sourceKey;
        public String vodId;
        public String dataJson;
        public long updateTime;
        public String dev = "";
        public long hlcPhysical;
        public int hlcCounter;

        public Hlc hlc() {
            return Hlc.of(hlcPhysical, hlcCounter, dev, updateTime);
        }
    }

    @Keep
    public static class ProgressItem {
        public String sourceKey;
        public String vodId;
        public String flag;
        public int playIndex;
        public String epName;
        public long position;
        public long updateTime;
        public String dev = "";
        public long hlcPhysical;
        public int hlcCounter;

        public Hlc hlc() {
            return Hlc.of(hlcPhysical, hlcCounter, dev, updateTime);
        }
    }

    @Keep
    public static class CollectItem {
        public String sourceKey;
        public String vodId;
        public String name;
        public String pic;
        public long updateTime;
        public String dev = "";
        public long hlcPhysical;
        public int hlcCounter;

        public Hlc hlc() {
            return Hlc.of(hlcPhysical, hlcCounter, dev, updateTime);
        }
    }

    /** 设置项：值以字符串存，ty 标记类型（s/i/l/b），t 为最后修改时间。 */
    @Keep
    public static class SettingValue {
        public String v;
        public String ty;
        public long t;
        public long hlcPhysical;
        public int hlcCounter;

        public SettingValue() {
        }

        public SettingValue(String v, String ty, long t) {
            this.v = v;
            this.ty = ty;
            this.t = t;
        }

        public SettingValue(String v, String ty, long t, long hlcPhysical, int hlcCounter) {
            this.v = v;
            this.ty = ty;
            this.t = t;
            this.hlcPhysical = hlcPhysical;
            this.hlcCounter = hlcCounter;
        }

        public Hlc hlc() {
            return Hlc.of(hlcPhysical, hlcCounter, "", t);
        }
    }

    @Keep
    public static class Tomb {
        public String key;
        public long at;
        public long hlcPhysical;
        public int hlcCounter;

        public Tomb() {
        }

        public Tomb(String key, long at) {
            this.key = key;
            this.at = at;
        }

        public Tomb(String key, long at, long hlcPhysical, int hlcCounter) {
            this.key = key;
            this.at = at;
            this.hlcPhysical = hlcPhysical;
            this.hlcCounter = hlcCounter;
        }

        public Hlc hlc() {
            return Hlc.of(hlcPhysical, hlcCounter, "", at);
        }
    }

    @Keep
    public static class Deleted {
        public List<Tomb> records = new ArrayList<>();
        public List<Tomb> progress = new ArrayList<>();
        public List<Tomb> collects = new ArrayList<>();
        public List<Tomb> settings = new ArrayList<>();
    }
}
