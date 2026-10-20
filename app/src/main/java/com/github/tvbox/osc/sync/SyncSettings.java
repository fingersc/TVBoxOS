package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置同步：只同步「内容/偏好类」键，设备绑定与运行时状态一律排除。
 *
 * <p>不侵入现有写入点：同步时对比"上次同步快照"识别本机改过哪些键，
 * 给它们打上时间戳，再与云端按 key 逐条 LWW。</p>
 */
public final class SyncSettings {

    /** 参与同步的键（其余 Hawk 键一律不同步）。 */
    public static final String[] SYNC_KEYS = {
            // 源与线路
            HawkConfig.API_URL, HawkConfig.API_HISTORY, HawkConfig.API_LINE_LIST,
            HawkConfig.API_LINE_SOURCE, HawkConfig.HOME_API, HawkConfig.LIVE_API_URL,
            HawkConfig.LIVE_API_HISTORY, HawkConfig.EPG_URL, HawkConfig.EPG_HISTORY,
            // 首页与搜索
            HawkConfig.HOME_REC, HawkConfig.HOME_REC_STYLE, HawkConfig.SHOW_PREVIEW,
            HawkConfig.DEFAULT_LOAD_LIVE, HawkConfig.SEARCH_VIEW, HawkConfig.FAST_SEARCH_MODE,
            HawkConfig.SOURCES_FOR_SEARCH,
            // 历史
            HawkConfig.HISTORY_NUM, HawkConfig.HISTORY_MERGE,
            // 播放偏好
            HawkConfig.AUTO_SWITCH_LINE, HawkConfig.M3U8_PURIFY, HawkConfig.PLAY_TIME_STEP,
            HawkConfig.SCREEN_DISPLAY, HawkConfig.IJK_CACHE_PLAY,
            // 字幕
            HawkConfig.SUBTITLE_TEXT_SIZE, HawkConfig.SUBTITLE_TIME_DELAY,
            HawkConfig.SUBTITLE_EXO_SCALE, HawkConfig.SUBTITLE_EXO_POSITION,
            // 弹幕
            HawkConfig.DANMU_OPEN, HawkConfig.DANMU_MAX_LINE, HawkConfig.DANMU_SPEED,
            HawkConfig.DANMU_ALPHA, HawkConfig.DANMU_SIZE_SCALE, HawkConfig.DANMU_RANDOM_COLOR,
            HawkConfig.DANMU_API,
            // DNS
            HawkConfig.DOH_URL, HawkConfig.DOH_JSON,
            // 直播偏好
            HawkConfig.LIVE_CHANNEL_REVERSE, HawkConfig.LIVE_CROSS_GROUP,
            HawkConfig.LIVE_CONNECT_TIMEOUT, HawkConfig.LIVE_SHOW_NET_SPEED,
            HawkConfig.LIVE_SHOW_RESOLUTION, HawkConfig.LIVE_SHOW_TIME,
            HawkConfig.LIVE_WEB_HEADER,
    };

    private SyncSettings() {
    }

    /** 读取本机当前设置（t=0 表示"时间待定"）。 */
    public static Map<String, SyncPayload.SettingValue> collect() {
        Map<String, SyncPayload.SettingValue> out = new LinkedHashMap<>();
        for (String key : SYNC_KEYS) {
            try {
                if (!Hawk.contains(key)) {
                    continue;
                }
                Object v = Hawk.get(key);
                if (v == null) {
                    continue;
                }
                out.put(key, toValue(v, 0L));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * 与上次同步快照对比，找出本机改过的键并打上 now；
     * 未变的键沿用快照里的时间（保持云端既有先后关系）。
     */
    public static Map<String, SyncPayload.SettingValue> detectChanges(
            Map<String, SyncPayload.SettingValue> snapshot, long now) {
        Map<String, SyncPayload.SettingValue> current = collect();
        Map<String, SyncPayload.SettingValue> out = new LinkedHashMap<>();
        for (Map.Entry<String, SyncPayload.SettingValue> e : current.entrySet()) {
            String key = e.getKey();
            SyncPayload.SettingValue cur = e.getValue();
            SyncPayload.SettingValue old = snapshot == null ? null : snapshot.get(key);
            boolean changed = old == null
                    || !eq(old.v, cur.v)
                    || !eq(old.ty, cur.ty);
            long t = changed ? now : old.t;
            out.put(key, new SyncPayload.SettingValue(cur.v, cur.ty, t));
        }
        return out;
    }

    /** 把合并结果写回本机（只写真正不同的键）。 */
    public static void apply(Map<String, SyncPayload.SettingValue> merged) {
        if (merged == null) {
            return;
        }
        for (Map.Entry<String, SyncPayload.SettingValue> e : merged.entrySet()) {
            String key = e.getKey();
            SyncPayload.SettingValue sv = e.getValue();
            if (sv == null || sv.v == null) {
                continue;
            }
            try {
                Object cur = Hawk.contains(key) ? Hawk.get(key) : null;
                Object next = fromValue(sv);
                if (next == null) {
                    continue;
                }
                if (cur != null && cur.getClass().equals(next.getClass()) && String.valueOf(cur).equals(String.valueOf(next))) {
                    continue;
                }
                Hawk.put(key, next);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 生成本次同步后的快照（写入 Hawk，供下次识别改动）。 */
    public static void saveSnapshot(Map<String, SyncPayload.SettingValue> merged) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, SyncPayload.SettingValue> e : merged.entrySet()) {
                SyncPayload.SettingValue v = e.getValue();
                JSONObject item = new JSONObject();
                item.put("v", v.v);
                item.put("ty", v.ty);
                item.put("t", v.t);
                o.put(e.getKey(), item);
            }
            Hawk.put(HawkConfig.SYNC_SETTINGS_SNAPSHOT, o.toString());
        } catch (Throwable ignored) {
        }
    }

    public static Map<String, SyncPayload.SettingValue> loadSnapshot() {
        Map<String, SyncPayload.SettingValue> out = new LinkedHashMap<>();
        try {
            String json = Hawk.get(HawkConfig.SYNC_SETTINGS_SNAPSHOT, "");
            if (json == null || json.length() == 0) {
                return out;
            }
            JSONObject o = new JSONObject(json);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject item = o.optJSONObject(key);
                if (item == null) {
                    continue;
                }
                out.put(key, new SyncPayload.SettingValue(
                        item.optString("v", ""), item.optString("ty", "s"), item.optLong("t", 0L)));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ---------------------------------------------------------------- 类型编解码

    private static SyncPayload.SettingValue toValue(Object v, long t) {
        if (v instanceof Boolean) {
            return new SyncPayload.SettingValue(((Boolean) v) ? "1" : "0", "b", t);
        }
        if (v instanceof Integer) {
            return new SyncPayload.SettingValue(String.valueOf(v), "i", t);
        }
        if (v instanceof Long) {
            return new SyncPayload.SettingValue(String.valueOf(v), "l", t);
        }
        return new SyncPayload.SettingValue(String.valueOf(v), "s", t);
    }

    private static Object fromValue(SyncPayload.SettingValue sv) {
        String ty = sv.ty == null ? "s" : sv.ty;
        try {
            switch (ty) {
                case "b":
                    return "1".equals(sv.v) || "true".equalsIgnoreCase(sv.v);
                case "i":
                    return Integer.parseInt(sv.v);
                case "l":
                    return Long.parseLong(sv.v);
                default:
                    return sv.v;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
