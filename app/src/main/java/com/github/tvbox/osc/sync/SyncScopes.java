package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

/** 同步范围开关（历史 / 进度 / 收藏 / 设置），默认全开。 */
public final class SyncScopes {

    public boolean history = true;
    public boolean progress = true;
    public boolean collect = true;
    public boolean settings = true;

    public static SyncScopes load() {
        SyncScopes s = new SyncScopes();
        try {
            String json = Hawk.get(HawkConfig.SYNC_SCOPES, "");
            if (json == null || json.length() == 0) {
                return s;
            }
            JSONObject o = new JSONObject(json);
            s.history = o.optBoolean("history", true);
            s.progress = o.optBoolean("progress", true);
            s.collect = o.optBoolean("collect", true);
            s.settings = o.optBoolean("settings", true);
        } catch (Throwable ignored) {
        }
        return s;
    }

    public void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("history", history);
            o.put("progress", progress);
            o.put("collect", collect);
            o.put("settings", settings);
            Hawk.put(HawkConfig.SYNC_SCOPES, o.toString());
        } catch (Throwable ignored) {
        }
    }
}
