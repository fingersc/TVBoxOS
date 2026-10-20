package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.util.List;

/**
 * 待上传的删除墓碑。
 *
 * <p>用户删除单条历史/收藏/进度时登记在这里，下一次同步随包上传；
 * 上传成功后清除。同步自身写入/清理数据时必须 {@link #suppress(boolean)}，
 * 否则会把"同步过程中的删除"误记成用户删除，传播到其它设备。</p>
 */
public final class SyncTombstones {

    private static boolean suppress;

    /** 按账号命名空间取键：A 账号的删除墓碑绝不能混进 B 账号的同步包。 */
    private static String key() {
        String owner = AccountContext.ownerKey();
        return (owner == null || owner.length() == 0)
                ? HawkConfig.SYNC_TOMBS : HawkConfig.SYNC_TOMBS + "." + owner;
    }

    private SyncTombstones() {
    }

    public static void suppress(boolean value) {
        suppress = value;
    }

    public static boolean isSuppressed() {
        return suppress;
    }

    public static void addRecord(String sourceKey, String vodId) {
        if (suppress) {
            return;
        }
        add("r", SyncMerge.recordKey(sourceKey, vodId));
    }

    public static void addProgress(String sourceKey, String vodId, String flag, int index, String epName) {
        if (suppress) {
            return;
        }
        add("p", SyncMerge.progressKey(sourceKey, vodId, flag, index, epName));
    }

    public static void addCollect(String sourceKey, String vodId) {
        if (suppress) {
            return;
        }
        add("c", SyncMerge.collectKey(sourceKey, vodId));
    }

    private static synchronized void add(String type, String key) {
        try {
            JSONObject root = readRoot();
            JSONObject map = root.optJSONObject(type);
            if (map == null) {
                map = new JSONObject();
                root.put(type, map);
            }
            map.put(key, System.currentTimeMillis());
            Hawk.put(key(), root.toString());
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void fill(SyncPayload payload) {
        try {
            JSONObject root = readRoot();
            fillList(root.optJSONObject("r"), payload.deleted.records);
            fillList(root.optJSONObject("p"), payload.deleted.progress);
            fillList(root.optJSONObject("c"), payload.deleted.collects);
        } catch (Throwable ignored) {
        }
    }

    /** 上传成功后清除已随包上传的墓碑（按上传时间戳之前）。 */
    public static synchronized void clear(long upTo) {
        try {
            JSONObject root = readRoot();
            JSONObject r = filter(root.optJSONObject("r"), upTo);
            JSONObject p = filter(root.optJSONObject("p"), upTo);
            JSONObject c = filter(root.optJSONObject("c"), upTo);
            JSONObject out = new JSONObject();
            out.put("r", r);
            out.put("p", p);
            out.put("c", c);
            Hawk.put(key(), out.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void fillList(JSONObject map, List<SyncPayload.Tomb> out) {
        if (map == null) {
            return;
        }
        java.util.Iterator<String> it = map.keys();
        while (it.hasNext()) {
            String key = it.next();
            out.add(new SyncPayload.Tomb(key, map.optLong(key, 0L)));
        }
    }

    private static JSONObject filter(JSONObject map, long upTo) {
        JSONObject out = new JSONObject();
        if (map == null) {
            return out;
        }
        java.util.Iterator<String> it = map.keys();
        while (it.hasNext()) {
            String key = it.next();
            long at = map.optLong(key, 0L);
            if (at > upTo) {
                try {
                    out.put(key, at);
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static JSONObject readRoot() {
        try {
            String json = Hawk.get(key(), "");
            if (json != null && json.length() > 0) {
                return new JSONObject(json);
            }
        } catch (Throwable ignored) {
        }
        return new JSONObject();
    }
}
