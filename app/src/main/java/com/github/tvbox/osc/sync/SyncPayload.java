package com.github.tvbox.osc.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同步包（明文 JSON，落盘前整体加密）。 */
public class SyncPayload {

    public static final int SCHEMA = 1;

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
    public Map<String, SettingValue> settings = new LinkedHashMap<>();

    public static class RecordItem {
        public String sourceKey;
        public String vodId;
        public String dataJson;
        public long updateTime;
        public String dev = "";
    }

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

    public static class CollectItem {
        public String sourceKey;
        public String vodId;
        public String name;
        public String pic;
        public long updateTime;
        public String dev = "";
    }

    /** 设置项：值以字符串存，ty 标记类型（s/i/l/b），t 为最后修改时间。 */
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

    public static class Deleted {
        public List<Tomb> records = new ArrayList<>();
        public List<Tomb> progress = new ArrayList<>();
        public List<Tomb> collects = new ArrayList<>();
        public List<Tomb> settings = new ArrayList<>();
    }
}
