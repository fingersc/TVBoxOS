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

    /** ★ v99-r8：该 scope 是否启用（拆文件后要逐个判断）。 */
    public boolean isEnabled(SyncScope s) {
        if (s == null) {
            return false;
        }
        switch (s) {
            case HISTORY:
                return history;
            case PROGRESS:
                return progress;
            case COLLECT:
                return collect;
            case SETTINGS:
                return settings;
            default:
                return false;
        }
    }

    /**
     * ★ v99-r8：只启用某一个 scope 的视图。
     *
     * <p>拆文件后每次只处理一个 scope，而 {@code buildLocal/applyLocal} 的签名
     * 收的是 SyncScopes —— 用这个"单 scope 视图"传进去即可，不必改动这两个方法。</p>
     */
    public static SyncScopes only(SyncScope s) {
        SyncScopes o = new SyncScopes();
        o.history = (s == SyncScope.HISTORY);
        o.progress = (s == SyncScope.PROGRESS);
        o.collect = (s == SyncScope.COLLECT);
        o.settings = (s == SyncScope.SETTINGS);
        return o;
    }

    /** 是否至少启用了一个 scope。 */
    public boolean anyEnabled() {
        return history || progress || collect || settings;
    }

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
