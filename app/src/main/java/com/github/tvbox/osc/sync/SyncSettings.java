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

    /**
     * 集合 / Map 类型的设置键：<b>不参与同步</b>。
     *
     * <p>原因：同步包是「字符串值 + 类型标记」，集合若按 String 写回会破坏 Hawk 里的实际类型，
     * 导致 {@code Hawk.get(API_HISTORY, new ArrayList<String>())} 抛 ClassCastException。
     * 这些键本身也是「本机配置历史 / 本机线路列表 / 搜索源勾选」，跨设备同步价值低。</p>
     */
    public static final String[] NON_SYNC_COLLECTION_KEYS = {
            HawkConfig.API_HISTORY, HawkConfig.LIVE_API_HISTORY, HawkConfig.EPG_HISTORY,
            HawkConfig.API_LINE_LIST, HawkConfig.SOURCES_FOR_SEARCH,
    };

    /** 参与同步的键（其余 Hawk 键一律不同步）。 */
    public static final String[] SYNC_KEYS = {
            // 源与线路
            HawkConfig.API_URL, HawkConfig.API_LINE_SOURCE, HawkConfig.HOME_API, HawkConfig.LIVE_API_URL,
            HawkConfig.EPG_URL,
            // 首页与搜索
            HawkConfig.HOME_REC, HawkConfig.HOME_REC_STYLE, HawkConfig.SHOW_PREVIEW,
            HawkConfig.DEFAULT_LOAD_LIVE, HawkConfig.SEARCH_VIEW, HawkConfig.FAST_SEARCH_MODE,
            // 历史
            // ★ v99-r47：HISTORY_NUM / HISTORY_MERGE 回归同步范围。
            //   r29 撤出的理由是「改了又被云端覆盖回旧值」（用户实测"关掉合并后行为没变"）——
            //   复盘后根因是实现缺陷，不是"不该同步"：
            //     · SettingsProfile.save 把所有键的 t 写成 0（见该处注释）⇒ 切账号往返后
            //       本地设置永远以 t=0 参与 LWW，必输给云端旧值；
            //     · ConfigPlan 没有「历史」这一组动作 ⇒ 值写回 Hawk 了，但首页/历史列表不刷新、
            //       裁剪不按新档位跑，看起来就像"开关没生效"。
            //   两处都已修（r47），故重新纳入：档位与合并开关属于【账号偏好】，两端应一致。
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
            HawkConfig.LIVE_SHOW_NET_SPEED,
            HawkConfig.LIVE_SHOW_RESOLUTION, HawkConfig.LIVE_SHOW_TIME,
            HawkConfig.LIVE_WEB_HEADER,
    };

    private static final String[] BOOL_KEYS = {
            HawkConfig.HOME_REC_STYLE, HawkConfig.FAST_SEARCH_MODE, HawkConfig.HISTORY_MERGE,
            HawkConfig.AUTO_SWITCH_LINE, HawkConfig.M3U8_PURIFY, HawkConfig.IJK_CACHE_PLAY,
            HawkConfig.DANMU_OPEN, HawkConfig.DANMU_RANDOM_COLOR, HawkConfig.LIVE_SHOW_NET_SPEED,
            HawkConfig.LIVE_SHOW_TIME, HawkConfig.LIVE_CHANNEL_REVERSE, HawkConfig.LIVE_CROSS_GROUP,
            HawkConfig.SHOW_PREVIEW, HawkConfig.DEFAULT_LOAD_LIVE,
    };

    private static final String[] INT_KEYS = {
            HawkConfig.SUBTITLE_EXO_SCALE, HawkConfig.SUBTITLE_TIME_DELAY, HawkConfig.SUBTITLE_TEXT_SIZE,
            HawkConfig.HISTORY_NUM, HawkConfig.HOME_REC, HawkConfig.SEARCH_VIEW,
            HawkConfig.DANMU_MAX_LINE, HawkConfig.DOH_URL, HawkConfig.LIVE_CONNECT_TIMEOUT,
            HawkConfig.SCREEN_DISPLAY,
    };

    private static final String[] FLOAT_KEYS = {
            HawkConfig.DANMU_ALPHA, HawkConfig.DANMU_SPEED, HawkConfig.DANMU_SIZE_SCALE,
            HawkConfig.SUBTITLE_EXO_POSITION,
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
        java.util.Set<String> allowed = new java.util.HashSet<>();
        java.util.Collections.addAll(allowed, SYNC_KEYS);
        for (Map.Entry<String, SyncPayload.SettingValue> e : merged.entrySet()) {
            String key = e.getKey();
            SyncPayload.SettingValue sv = e.getValue();
            if (sv == null || sv.v == null || !allowed.contains(key)) {
                continue;
            }
            try {
                Object cur = Hawk.contains(key) ? Hawk.get(key) : null;
                Object next = fromValue(sv);
                if (next == null) {
                    continue;
                }
                // 类型护栏：已有值类型不同就绝不覆盖（防再次把集合写成字符串）
                if (cur != null && !cur.getClass().equals(next.getClass())) {
                    continue;
                }
                if (cur != null && String.valueOf(cur).equals(String.valueOf(next))) {
                    continue;
                }
                Hawk.put(key, next);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 生成本次同步后的快照（写入 Hawk，供下次识别改动）。 */
    /** 设置快照按账号隔离（游客用原键），避免切换账号后拿别人的快照做 diff。 */
    private static String snapshotKey() {
        String owner = AccountContext.ownerKey();
        return (owner == null || owner.isEmpty())
                ? HawkConfig.SYNC_SETTINGS_SNAPSHOT
                : HawkConfig.SYNC_SETTINGS_SNAPSHOT + "." + owner;
    }

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
            Hawk.put(snapshotKey(), o.toString());
        } catch (Throwable ignored) {
        }
    }

    public static Map<String, SyncPayload.SettingValue> loadSnapshot() {
        Map<String, SyncPayload.SettingValue> out = new LinkedHashMap<>();
        try {
            String json = Hawk.get(snapshotKey(), "");
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

    /**
     * 自愈：修复被 v73 早期版本写坏的集合类型（String → ArrayList / Map）。
     * App 启动时调用一次，幂等。
     */
    public static void repairCorruptedTypes() {
        for (String key : BOOL_KEYS) {
            repairScalar(key, "b");
        }
        for (String key : INT_KEYS) {
            repairScalar(key, "i");
        }
        for (String key : FLOAT_KEYS) {
            repairScalar(key, "f");
        }
        repairStringList(HawkConfig.API_HISTORY);
        repairStringList(HawkConfig.LIVE_API_HISTORY);
        repairStringList(HawkConfig.EPG_HISTORY);
        repairStringList(HawkConfig.API_LINE_LIST);
        try {
            if (Hawk.contains(HawkConfig.SOURCES_FOR_SEARCH)) {
                Object v = Hawk.get(HawkConfig.SOURCES_FOR_SEARCH);
                if (v instanceof String) {
                    // 无法可靠还原嵌套 Map，直接清掉，搜索时会按默认重建
                    Hawk.delete(HawkConfig.SOURCES_FOR_SEARCH);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把被写成 String 的标量恢复成正确类型；解析失败则删除（回落到默认值）。 */
    private static void repairScalar(String key, String ty) {
        try {
            if (!Hawk.contains(key)) {
                return;
            }
            Object v = Hawk.get(key);
            if (!(v instanceof String)) {
                return;
            }
            Object val = fromValue(new SyncPayload.SettingValue((String) v, ty, 0L));
            if (val == null) {
                Hawk.delete(key);
            } else {
                Hawk.put(key, val);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void repairStringList(String key) {
        try {
            if (!Hawk.contains(key)) {
                return;
            }
            Object v = Hawk.get(key);
            if (!(v instanceof String)) {
                return;
            }
            String s = (String) v;
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            if (s.length() > 2 && s.startsWith("[") && s.endsWith("]")) {
                String body = s.substring(1, s.length() - 1).trim();
                if (body.length() > 0) {
                    for (String part : body.split(", ")) {
                        if (part.length() > 0) {
                            list.add(part);
                        }
                    }
                }
            }
            Hawk.put(key, list);
        } catch (Throwable ignored) {
        }
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
        if (v instanceof Float || v instanceof Double) {
            return new SyncPayload.SettingValue(String.valueOf(v), "f", t);
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
                case "f":
                    return Float.parseFloat(sv.v);
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
