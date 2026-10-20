package com.github.tvbox.osc.sync;

import androidx.annotation.Keep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同步包（明文 JSON，落盘前整体加密）。 */
@Keep
public class SyncPayload {

    public static final int SCHEMA = 2;

    public int schema = SCHEMA;
    public String owner = "";
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
    }

    /** 列表 → Map（内部计算用，不参与序列化）。 */
    public Map<String, SettingValue> settingsMap() {
        Map<String, SettingValue> map = new LinkedHashMap<>();
        if (settings != null) {
            for (SettingItem it : settings) {
                if (it != null && it.key != null) {
                    map.put(it.key, new SettingValue(it.v, it.ty, it.t));
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
                settings.add(it);
            }
        }
    }

    @Keep
    public static class RecordItem {
        public String sourceKey;
        public String vodId;
        public String dataJson;
        public long updateTime;
        public String dev = "";
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
    }

    @Keep
    public static class CollectItem {
        public String sourceKey;
        public String vodId;
        public String name;
        public String pic;
        public long updateTime;
        public String dev = "";
    }

    /** 设置项：值以字符串存，ty 标记类型（s/i/l/b），t 为最后修改时间。 */
    @Keep
    public static class SettingValue {
        public String v;
        public String ty;
        public long t;

        public SettingValue() {
        }

        public SettingValue(String v, String ty, long t) {
            this.v = v;
            this.ty = ty;
            this.t = t;
        }
    }

    @Keep
    public static class Tomb {
        public String key;
        public long at;

        public Tomb() {
        }

        public Tomb(String key, long at) {
            this.key = key;
            this.at = at;
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
