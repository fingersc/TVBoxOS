package com.github.tvbox.osc.sync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.cache.PlayProgress;
import com.github.tvbox.osc.cache.PlayProgressDao;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.cache.VodCollectDao;
import com.github.tvbox.osc.cache.VodRecord;
import com.github.tvbox.osc.cache.VodRecordDao;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.orhanobut.hawk.Hawk;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同步编排：导出 → 拉取 → 解密 → 合并 → 写回 → 加密上传。
 *
 * <p>触发：登录成功 / 冷启动 10s / 回前台（≥10min）/ 数据变更（5min 节流）/
 * 进后台（≥60s，尽力）/ 手动 / 前台每 30min 兜底。</p>
 *
 * <p>不引入任何后台调度组件：进程被杀就等下次打开补，数据不丢（dirty 已落盘）。</p>
 */
public final class HistorySyncManager {

    public interface Callback {
        void onDone(boolean ok, String message);
    }

    private static final Gson GSON = new Gson();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final long DEBOUNCE_MS = 5 * 60 * 1000L;
    private static final long MIN_GAP_MS = 3 * 60 * 1000L;

    private static volatile boolean syncing;
    private static boolean started;

    private static final Runnable DEBOUNCE_TASK = new Runnable() {
        @Override
        public void run() {
            sync(false, false, null);
        }
    };

    private static final Runnable PERIODIC_TASK = new Runnable() {
        @Override
        public void run() {
            sync(false, false, null);
            MAIN.postDelayed(this, 30 * 60 * 1000L);
        }
    };

    private HistorySyncManager() {
    }

    /** App 启动时调用一次。 */
    public static void start() {
        if (started) {
            return;
        }
        started = true;
        AccountContext.load();
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                sync(false, false, null);
            }
        }, 10 * 1000L);
        MAIN.postDelayed(PERIODIC_TASK, 30 * 60 * 1000L);
    }

    /** 按账号命名空间取 Hawk 键（游客用原键）。 */
    private static String key(String base) {
        String owner = AccountContext.ownerKey();
        return (owner == null || owner.length() == 0) ? base : base + "." + owner;
    }

    /** 数据变更时调用：置脏 + 5 分钟后合并成一次同步。 */
    public static void markDirty() {
        Hawk.put(key(HawkConfig.SYNC_DIRTY), true);
        if (AccountContext.isGuest()) {
            return;
        }
        MAIN.removeCallbacks(DEBOUNCE_TASK);
        MAIN.postDelayed(DEBOUNCE_TASK, DEBOUNCE_MS);
    }

    public static boolean isDirty() {
        return Hawk.get(key(HawkConfig.SYNC_DIRTY), false);
    }

    /** 退出 App / 切换账号前：是否值得等一次同步。 */
    public static boolean needsSyncBeforeExit() {
        return !AccountContext.isGuest() && isDirty() && hasConfig();
    }

    /** 退出播放页（进度刚落盘）：距上次 ≥60 秒就立即补一次。 */
    public static void onPlaybackExit() {
        if (AccountContext.isGuest() || !isDirty()) {
            return;
        }
        long last = Hawk.get(HawkConfig.SYNC_LAST_AT, 0L);
        if (System.currentTimeMillis() - last >= 60 * 1000L) {
            sync(false, false, null);
        }
    }

    /** 前台网络从断到通：补一次（自动同步的 3 分钟门槛仍然生效）。 */
    public static void onNetworkAvailable() {
        if (AccountContext.isGuest() || !isDirty()) {
            return;
        }
        sync(false, false, null);
    }

    /** 回到前台（首页 onResume）：距上次同步 ≥10 分钟才拉。 */
    public static void onForeground() {
        if (AccountContext.isGuest()) {
            return;
        }
        long last = Hawk.get(HawkConfig.SYNC_LAST_AT, 0L);
        if (System.currentTimeMillis() - last >= 10 * 60 * 1000L) {
            sync(false, false, null);
        }
    }

    /** 进后台：有改动且距上次 ≥60 秒时尽力同步一次（不保证完成）。 */
    public static void onBackground() {
        if (AccountContext.isGuest() || !isDirty()) {
            return;
        }
        long last = Hawk.get(HawkConfig.SYNC_LAST_AT, 0L);
        if (System.currentTimeMillis() - last >= 60 * 1000L) {
            sync(false, true, null);
        }
    }

    public static boolean isSyncing() {
        return syncing;
    }

    public static String lastSyncText() {
        long last = Hawk.get(HawkConfig.SYNC_LAST_AT, 0L);
        if (last <= 0) {
            return "尚未同步";
        }
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
        return f.format(new java.util.Date(last)) + (isDirty() ? "（有未同步改动）" : "");
    }

    public static void syncNow(Callback cb) {
        sync(true, false, cb);
    }

    // ---------------------------------------------------------------- 主流程

    private static void sync(final boolean manual, final boolean background, final Callback cb) {
        if (AccountContext.isGuest()) {
            post(cb, false, "游客模式：登录后才能同步");
            return;
        }
        if (!hasConfig()) {
            post(cb, false, "请先配置 WebDAV 存储");
            return;
        }
        if (!manual && !background && AccountContext.isGuest()) {
            return;
        }
        synchronized (LOCK) {
            if (syncing) {
                return;
            }
            syncing = true;
        }
        if (!manual) {
            long last = Hawk.get(HawkConfig.SYNC_LAST_AT, 0L);
            if (System.currentTimeMillis() - last < MIN_GAP_MS) {
                syncing = false;
                return;
            }
            if (Hawk.get(HawkConfig.SYNC_WIFI_ONLY, false) && !isWifi()) {
                syncing = false;
                return;
            }
            if (System.currentTimeMillis() < Hawk.get(key(HawkConfig.SYNC_FAIL_UNTIL), 0L)) {
                syncing = false;
                return;
            }
        }
        final byte[] key = AccountContext.key();
        final String owner = AccountContext.ownerKey();
        if (key == null || owner == null || owner.length() == 0) {
            syncing = false;
            post(cb, false, "登录状态异常，请重新登录");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String msg;
                try {
                    msg = doSync(owner, key, manual);
                    ok = true;
                } catch (WebDavClient.AuthException e) {
                    msg = "认证失败：请检查网盘账号与应用密码";
                } catch (SecurityException e) {
                    msg = "账号或密码不正确（解密失败）";
                } catch (WebDavClient.ConflictException e) {
                    msg = "云端被其它设备修改，重试后仍冲突";
                } catch (IOException e) {
                    msg = "网络或服务器错误：" + e.getMessage();
                } catch (Throwable t) {
                    msg = "同步失败：" + t.getClass().getSimpleName();
                    LOG.e("[Sync] failed", t);
                } finally {
                    syncing = false;
                }
                if (ok) {
                    clearFailure();
                } else {
                    noteFailure();
                }
                post(cb, ok, msg);
            }
        }, "tvbox-sync").start();
    }

    private static void post(final Callback cb, final boolean ok, final String msg) {
        if (cb == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onDone(ok, msg);
            }
        });
    }

    private static String doSync(String owner, byte[] key, boolean manual) throws Exception {
        long now = System.currentTimeMillis();
        String deviceId = deviceId();
        SyncScopes scopes = SyncScopes.load();
        // v74：云端目录 = <存储根目录>/<账号名>/，由管理员创建，App 不自动建
        String accountName = AccountContext.displayName();
        if (!AccountPaths.isValidName(accountName)) {
            throw new IOException("账号名无效，请重新登录");
        }
        String path = AccountPaths.data(accountName);

        WebDavClient dav = new WebDavClient(
                Hawk.get(HawkConfig.SYNC_WEBDAV_URL, ""),
                Hawk.get(HawkConfig.SYNC_WEBDAV_USER, ""),
                Hawk.get(HawkConfig.SYNC_WEBDAV_PASS, ""));
        if (!dav.dirExists(AccountPaths.dir(accountName))) {
            throw new IOException("账号目录不存在（可能被管理员删除）");
        }
        AccountService.ensureProfile(dav, accountName, key);

        String[] etag = new String[1];
        byte[] remoteBytes = dav.get(path, etag);
        SyncPayload remote;
        if (remoteBytes == null || remoteBytes.length == 0) {
            remote = new SyncPayload();
            remote.owner = owner;
        } else {
            remote = GSON.fromJson(new String(Crypto.decrypt(key, remoteBytes), "UTF-8"), SyncPayload.class);
            if (remote == null) {
                remote = new SyncPayload();
                remote.owner = owner;
            }
            if (remote.deleted == null) {
                remote.deleted = new SyncPayload.Deleted();
            }
        }

        long lastEpoch = Hawk.get(HawkConfig.SYNC_LAST_EPOCH, 0L);
        boolean resetLocal = remote.epoch > lastEpoch;
        if (resetLocal) {
            wipeLocal(scopes);
        }

        SyncPayload local = buildLocal(owner, deviceId, scopes, now);
        SyncPayload merged = SyncMerge.merge(local, remote, deviceId, now);
        applyLocal(merged, scopes);

        byte[] out = Crypto.encrypt(key, GSON.toJson(merged).getBytes("UTF-8"));
        try {
            dav.put(path, out, etag[0]);
        } catch (WebDavClient.ConflictException conflict) {
            // 另一台设备刚写过：重拉、重合并、再写一次
            String[] etag2 = new String[1];
            byte[] again = dav.get(path, etag2);
            if (again != null && again.length > 0) {
                SyncPayload r2 = GSON.fromJson(new String(Crypto.decrypt(key, again), "UTF-8"), SyncPayload.class);
                if (r2 != null) {
                    if (r2.deleted == null) {
                        r2.deleted = new SyncPayload.Deleted();
                    }
                    merged = SyncMerge.merge(buildLocal(owner, deviceId, scopes, now), r2, deviceId, now);
                    applyLocal(merged, scopes);
                    out = Crypto.encrypt(key, GSON.toJson(merged).getBytes("UTF-8"));
                }
            }
            dav.put(path, out, null);
        }

        Hawk.put(HawkConfig.SYNC_LAST_AT, now);
        Hawk.put(HawkConfig.SYNC_LAST_EPOCH, merged.epoch);
        Hawk.put(key(HawkConfig.SYNC_DIRTY), false);
        SyncTombstones.clear(now);
        LOG.i("[Sync] done owner=" + owner + " rec=" + merged.records.size()
                + " prog=" + merged.progress.size() + " col=" + merged.collects.size()
                + " set=" + merged.settings.size() + " epoch=" + merged.epoch);
        return "同步完成：历史 " + merged.records.size() + " / 进度 " + merged.progress.size()
                + " / 收藏 " + merged.collects.size() + " / 设置 " + merged.settings.size();
    }

    private static SyncPayload buildLocal(String owner, String deviceId, SyncScopes scopes, long now) {
        SyncPayload p = new SyncPayload();
        p.owner = owner;
        p.dev = deviceId;
        p.updatedAt = now;
        p.epoch = Hawk.get(HawkConfig.SYNC_LAST_EPOCH, 0L);
        AppDataManager.get();
        if (scopes.history) {
            VodRecordDao dao = AppDataManager.get().getVodRecordDao();
            List<VodRecord> rows = dao.getAll(Integer.MAX_VALUE);
            if (rows != null) {
                for (VodRecord r : rows) {
                    SyncPayload.RecordItem it = new SyncPayload.RecordItem();
                    it.sourceKey = r.sourceKey;
                    it.vodId = r.vodId;
                    it.dataJson = r.dataJson;
                    it.updateTime = r.updateTime;
                    it.dev = deviceId;
                    p.records.add(it);
                }
            }
        }
        if (scopes.progress) {
            PlayProgressDao dao = AppDataManager.get().getPlayProgressDao();
            List<PlayProgress> rows = dao.getAllRows();
            if (rows != null) {
                for (PlayProgress pr : rows) {
                    SyncPayload.ProgressItem it = new SyncPayload.ProgressItem();
                    it.sourceKey = pr.sourceKey;
                    it.vodId = pr.vodId;
                    it.flag = pr.flag;
                    it.playIndex = pr.playIndex;
                    it.epName = pr.epName;
                    it.position = pr.position;
                    it.updateTime = pr.updateTime;
                    it.dev = deviceId;
                    p.progress.add(it);
                }
            }
        }
        if (scopes.collect) {
            VodCollectDao dao = AppDataManager.get().getVodCollectDao();
            List<VodCollect> rows = dao.getAll();
            if (rows != null) {
                for (VodCollect c : rows) {
                    SyncPayload.CollectItem it = new SyncPayload.CollectItem();
                    it.sourceKey = c.sourceKey;
                    it.vodId = c.vodId;
                    it.name = c.name;
                    it.pic = c.pic;
                    it.updateTime = c.updateTime;
                    it.dev = deviceId;
                    p.collects.add(it);
                }
            }
        }
        if (scopes.settings) {
            // payload.settings 是 List<SettingItem>（规避 R8 收紧 Map 字段），Map 需经适配器转换
            p.setSettingsMap(SyncSettings.detectChanges(SyncSettings.loadSnapshot(), now));
        }
        SyncTombstones.fill(p);
        return p;
    }

    private static void applyLocal(SyncPayload merged, SyncScopes scopes) {
        SyncTombstones.suppress(true);
        try {
            AppDataManager.get();
            if (scopes.history) {
                VodRecordDao dao = AppDataManager.get().getVodRecordDao();
                dao.deleteAll();
                for (SyncPayload.RecordItem it : merged.records) {
                    VodRecord r = new VodRecord();
                    r.sourceKey = it.sourceKey;
                    r.vodId = it.vodId;
                    r.dataJson = it.dataJson;
                    r.updateTime = it.updateTime;
                    dao.insert(r);
                }
            }
            if (scopes.progress) {
                PlayProgressDao dao = AppDataManager.get().getPlayProgressDao();
                dao.deleteAll();
                for (SyncPayload.ProgressItem it : merged.progress) {
                    PlayProgress pr = new PlayProgress();
                    pr.sourceKey = it.sourceKey == null ? "" : it.sourceKey;
                    pr.vodId = it.vodId == null ? "" : it.vodId;
                    pr.flag = it.flag == null ? "" : it.flag;
                    pr.playIndex = it.playIndex;
                    pr.epName = it.epName == null ? "" : it.epName;
                    pr.position = it.position;
                    pr.updateTime = it.updateTime;
                    dao.save(pr);
                }
            }
            if (scopes.collect) {
                VodCollectDao dao = AppDataManager.get().getVodCollectDao();
                dao.deleteAll();
                for (SyncPayload.CollectItem it : merged.collects) {
                    VodCollect c = new VodCollect();
                    c.sourceKey = it.sourceKey;
                    c.vodId = it.vodId;
                    c.name = it.name;
                    c.pic = it.pic;
                    c.updateTime = it.updateTime;
                    dao.insert(c);
                }
            }
            if (scopes.settings) {
                SyncSettings.apply(merged.settingsMap());
                SyncSettings.saveSnapshot(merged.settingsMap());
            }
        } finally {
            SyncTombstones.suppress(false);
        }
    }

    /** 云端 epoch 变化（整库重置）时清空本机该账号的同步范围数据。 */
    private static void wipeLocal(SyncScopes scopes) {
        SyncTombstones.suppress(true);
        try {
            AppDataManager.get();
            if (scopes.history) {
                AppDataManager.get().getVodRecordDao().deleteAll();
            }
            if (scopes.progress) {
                AppDataManager.get().getPlayProgressDao().deleteAll();
            }
            if (scopes.collect) {
                AppDataManager.get().getVodCollectDao().deleteAll();
            }
        } finally {
            SyncTombstones.suppress(false);
        }
    }

    /** 清空云端（当前账号）：epoch+1 空包，其它设备下次同步跟随清空。 */
    public static void clearCloud(final Callback cb) {
        if (AccountContext.isGuest()) {
            post(cb, false, "游客模式没有云端数据");
            return;
        }
        if (!hasConfig()) {
            post(cb, false, "请先配置 WebDAV 存储");
            return;
        }
        final byte[] key = AccountContext.key();
        final String owner = AccountContext.ownerKey();
        final String accountName = AccountContext.displayName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String msg;
                try {
                    WebDavClient dav = new WebDavClient(
                            Hawk.get(HawkConfig.SYNC_WEBDAV_URL, ""),
                            Hawk.get(HawkConfig.SYNC_WEBDAV_USER, ""),
                            Hawk.get(HawkConfig.SYNC_WEBDAV_PASS, ""));
                    SyncPayload empty = new SyncPayload();
                    empty.owner = owner;
                    empty.epoch = Hawk.get(HawkConfig.SYNC_LAST_EPOCH, 0L) + 1;
                    empty.updatedAt = System.currentTimeMillis();
                    empty.dev = deviceId();
                    byte[] out = Crypto.encrypt(key, GSON.toJson(empty).getBytes("UTF-8"));
                    dav.put(AccountPaths.data(accountName), out, null);
                    Hawk.put(HawkConfig.SYNC_LAST_EPOCH, empty.epoch);
                    Hawk.put(HawkConfig.SYNC_LAST_AT, System.currentTimeMillis());
                    Hawk.put(key(HawkConfig.SYNC_DIRTY), false);
                    ok = true;
                    msg = "已清空云端（其它设备下次同步会跟随清空）";
                } catch (Throwable t) {
                    msg = "清空失败：" + t.getMessage();
                }
                post(cb, ok, msg);
            }
        }, "tvbox-sync-clear").start();
    }

    // ---------------------------------------------------------------- 失败退避

    private static void noteFailure() {
        int count = Hawk.get(key(HawkConfig.SYNC_FAIL_COUNT), 0) + 1;
        long delay = Math.min(30 * 60 * 1000L, 60 * 1000L * (1L << Math.min(count - 1, 5)));
        Hawk.put(key(HawkConfig.SYNC_FAIL_COUNT), count);
        Hawk.put(key(HawkConfig.SYNC_FAIL_UNTIL), System.currentTimeMillis() + delay);
    }

    private static void clearFailure() {
        Hawk.delete(key(HawkConfig.SYNC_FAIL_COUNT));
        Hawk.delete(key(HawkConfig.SYNC_FAIL_UNTIL));
    }

    // ---------------------------------------------------------------- 工具

    public static boolean hasConfig() {
        String url = Hawk.get(HawkConfig.SYNC_WEBDAV_URL, "");
        String user = Hawk.get(HawkConfig.SYNC_WEBDAV_USER, "");
        String pass = Hawk.get(HawkConfig.SYNC_WEBDAV_PASS, "");
        return url != null && url.length() > 0 && user != null && pass != null;
    }

    public static String deviceId() {
        String id = Hawk.get(HawkConfig.SYNC_DEVICE_ID, "");
        if (id == null || id.length() == 0) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            Hawk.put(HawkConfig.SYNC_DEVICE_ID, id);
        }
        return id;
    }

    private static boolean isWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) App.getInstance()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Throwable t) {
            return true;
        }
    }
}
