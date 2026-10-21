package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 本机账号清单（v76）。
 *
 * <p>只保存「账号名 + ownerKey + 时间戳」，<b>不保存密码、不保存密钥</b>：
 * 切换账号时必须重新用密码或扫码认证。游客不是条目，而是「当前 ownerKey 为空」的隐式状态。</p>
 */
public final class AccountStore {

    private AccountStore() {
    }

    public static class Item {
        public String name;
        public String owner;
        public long addedAt;
        public long lastUsedAt;
    }

    public static List<Item> list() {
        return parse(Hawk.get(HawkConfig.ACCOUNT_LIST, ""));
    }

    public static boolean contains(String owner) {
        if (owner == null || owner.isEmpty()) {
            return false;
        }
        for (Item it : list()) {
            if (owner.equals(it.owner)) {
                return true;
            }
        }
        return false;
    }

    public static Item find(String owner) {
        if (owner == null || owner.isEmpty()) {
            return null;
        }
        for (Item it : list()) {
            if (owner.equals(it.owner)) {
                return it;
            }
        }
        return null;
    }

    /** 加入清单（按 owner 去重；已存在则只刷新名字）。 */
    public static void add(String name, String owner) {
        if (owner == null || owner.isEmpty() || name == null || name.isEmpty()) {
            return;
        }
        Hawk.put(HawkConfig.ACCOUNT_LIST, addToJson(Hawk.get(HawkConfig.ACCOUNT_LIST, ""),
                AccountPaths.normalize(name), owner, System.currentTimeMillis()));
    }

    public static void remove(String owner) {
        Hawk.put(HawkConfig.ACCOUNT_LIST, removeFromJson(Hawk.get(HawkConfig.ACCOUNT_LIST, ""), owner));
    }

    public static void touch(String owner) {
        Hawk.put(HawkConfig.ACCOUNT_LIST, touchJson(Hawk.get(HawkConfig.ACCOUNT_LIST, ""),
                owner, System.currentTimeMillis()));
    }

    /**
     * 升级迁移：清单为空但已有登录态时，把当前账号播种进清单，
     * 避免老用户升级后「账号页看不见自己」。
     */
    public static void migrateIfNeeded() {
        try {
            if (!list().isEmpty() || AccountContext.isGuest()) {
                return;
            }
            String owner = AccountContext.ownerKey();
            String name = AccountContext.displayName();
            if (owner == null || owner.isEmpty() || name == null || name.isEmpty()) {
                return;
            }
            // 迁移时把当前设置存成该账号的档案，之后切走再切回能还原
            SettingsProfile.save(owner);
            add(name, owner);
            touch(owner);
            LOG.i("[Account] migrated current login into list: " + name);
        } catch (Throwable t) {
            LOG.e("[Account] migrate failed", t);
        }
    }

    // ---------------------------------------------------------------- 纯函数（便于单测）

    static String addToJson(String json, String name, String owner, long now) {
        List<Item> items = parse(json);
        for (Item it : items) {
            if (it.owner != null && it.owner.equals(owner)) {
                it.name = name;
                it.lastUsedAt = now;
                return write(items);
            }
        }
        Item it = new Item();
        it.name = name;
        it.owner = owner;
        it.addedAt = now;
        it.lastUsedAt = now;
        items.add(it);
        return write(items);
    }

    static String removeFromJson(String json, String owner) {
        List<Item> items = parse(json);
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (it.owner == null || !it.owner.equals(owner)) {
                out.add(it);
            }
        }
        return write(out);
    }

    static String touchJson(String json, String owner, long now) {
        List<Item> items = parse(json);
        for (Item it : items) {
            if (it.owner != null && it.owner.equals(owner)) {
                it.lastUsedAt = now;
            }
        }
        return write(items);
    }

    static List<Item> parse(String json) {
        List<Item> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String owner = o.optString("owner", "");
                String name = o.optString("name", "");
                if (owner.isEmpty() || name.isEmpty()) {
                    continue;
                }
                Item it = new Item();
                it.name = name;
                it.owner = owner;
                it.addedAt = o.optLong("addedAt", 0L);
                it.lastUsedAt = o.optLong("lastUsedAt", 0L);
                out.add(it);
            }
        } catch (Throwable t) {
            LOG.e("[Account] parse list failed", t);
        }
        return out;
    }

    private static String write(List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("name", it.name);
                o.put("owner", it.owner);
                o.put("addedAt", it.addedAt);
                o.put("lastUsedAt", it.lastUsedAt);
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        return arr.toString();
    }
}
