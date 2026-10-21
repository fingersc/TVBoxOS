package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号级设置快照（v76）。
 *
 * <p>设置分三层：设备级（播放器/渲染/解码等）不随账号切换；账号级（= {@link SyncSettings#SYNC_KEYS}）
 * 在切换时保存/恢复；运行时状态不持久化。这里只处理账号级这一层。</p>
 *
 * <p>没有本地快照的账号 = 首次加入：重置为默认并置 pendingPull，
 * 首次同步只拉不推（本地设置时间戳记 0），避免用默认值覆盖对方云端设置。</p>
 */
public final class SettingsProfile {

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<SyncPayload.SettingItem>>() {
    }.getType();

    private SettingsProfile() {
    }

    private static String key(String base, String owner) {
        return (owner == null || owner.isEmpty()) ? base : base + "." + owner;
    }

    /** 保存当前设置到该账号的本地快照。 */
    public static void save(String owner) {
        try {
            Map<String, SyncPayload.SettingValue> cur = SyncSettings.collect();
            List<SyncPayload.SettingItem> items = new ArrayList<>();
            for (Map.Entry<String, SyncPayload.SettingValue> e : cur.entrySet()) {
                SyncPayload.SettingItem it = new SyncPayload.SettingItem();
                it.key = e.getKey();
                it.v = e.getValue().v;
                it.ty = e.getValue().ty;
                it.t = e.getValue().t;
                items.add(it);
            }
            Hawk.put(key(HawkConfig.SYNC_SETTINGS_PROFILE, owner), GSON.toJson(items));
        } catch (Throwable t) {
            LOG.e("[Account] save settings profile failed", t);
        }
    }

    /** 恢复该账号的本地快照；没有快照返回 false。 */
    public static boolean load(String owner) {
        String json = Hawk.get(key(HawkConfig.SYNC_SETTINGS_PROFILE, owner), "");
        if (json == null || json.isEmpty()) {
            return false;
        }
        try {
            List<SyncPayload.SettingItem> items = GSON.fromJson(json, LIST_TYPE);
            if (items == null) {
                return false;
            }
            Map<String, SyncPayload.SettingValue> map = new LinkedHashMap<>();
            for (SyncPayload.SettingItem it : items) {
                if (it != null && it.key != null) {
                    map.put(it.key, new SyncPayload.SettingValue(it.v, it.ty, it.t));
                }
            }
            SyncSettings.apply(map);
            SyncSettings.saveSnapshot(map);
            return true;
        } catch (Throwable t) {
            LOG.e("[Account] load settings profile failed", t);
            return false;
        }
    }

    /** 把账号级设置清空，回到 App 默认值（只用于首次加入的账号）。 */
    public static void resetDefaults() {
        try {
            for (String k : SyncSettings.SYNC_KEYS) {
                Hawk.delete(k);
            }
            Hawk.delete(HawkConfig.SYNC_SETTINGS_SNAPSHOT);
        } catch (Throwable ignored) {
        }
    }

    /** 该账号是否「还没从云端拉过设置」。 */
    public static boolean isPendingPull(String owner) {
        return Hawk.get(key(HawkConfig.SYNC_SETTINGS_PENDING, owner), false);
    }

    public static void setPendingPull(String owner, boolean pending) {
        if (pending) {
            Hawk.put(key(HawkConfig.SYNC_SETTINGS_PENDING, owner), true);
        } else {
            Hawk.delete(key(HawkConfig.SYNC_SETTINGS_PENDING, owner));
        }
    }

    /** 移除账号时清掉它的快照与标记。 */
    public static void delete(String owner) {
        Hawk.delete(key(HawkConfig.SYNC_SETTINGS_PROFILE, owner));
        Hawk.delete(key(HawkConfig.SYNC_SETTINGS_PENDING, owner));
    }
}
