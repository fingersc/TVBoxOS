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
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LiveConfigPrefetcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    static final Gson GSON = new Gson();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final long DEBOUNCE_MS = 5 * 60 * 1000L;
    private static final long MIN_GAP_MS = 60 * 1000L;

    private static volatile boolean syncing;
    private static boolean started;
    /** 页面主动拉取的节流时间戳（避免来回切页面刷请求）。 */
    private static long lastPullAt;
    /** 账号切换代数：切换时 +1，在途同步发现代数变了就放弃写库（防串库）。 */
    private static volatile int generation;
    /**
     * v88：启动静默期。冷启动时同步会在 1.5 秒左右写回配置，如果此刻就发「重载首页/预热直播」，
     * 正好和用户点开直播/历史的起播抢网络与 CPU（用户反馈"加了账号同步之后起播变慢"）。
     * 静默期内只记录，等首页首次渲染完成后由 HomeActivity 消费。
     */
    private static final long APP_START_AT = System.currentTimeMillis();
    private static final long STARTUP_GRACE_MS = 8 * 1000L;
    private static volatile boolean pendingConfigEvent;

    /** v88：取出并清除"启动期被推迟的配置变更"，返回 true 表示首页首屏后需要补一次重载。 */
    public static boolean consumePendingConfigEvent() {
        boolean v = pendingConfigEvent;
        pendingConfigEvent = false;
        return v;
    }

    /** 切换账号时调用：让在途同步放弃写库。 */
    public static void bumpGeneration() {
        generation++;
    }

    public static int currentGeneration() {
        return generation;
    }

    /** 等当前同步收尾（最多 ms 毫秒），供账号切换前调用。 */
    public static void awaitIdle(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (syncing && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 账号切换导致本次同步作废：不记失败、不写库。 */
    static class SwitchAborted extends IOException {
        SwitchAborted() {
            super("账号已切换，本次同步已放弃");
        }
    }

    static void checkGeneration(int gen) throws IOException {
        if (gen != generation) {
            throw new SwitchAborted();
        }
    }

    /** 同步状态按账号隔离（读取时回退到旧的全局键，兼容升级）。 */
    private static long lastSyncAt() {
        long v = Hawk.get(key(HawkConfig.SYNC_LAST_AT), -1L);
        return v >= 0 ? v : Hawk.get(HawkConfig.SYNC_LAST_AT, 0L);
    }

    private static long lastEpoch() {
        long v = Hawk.get(key(HawkConfig.SYNC_LAST_EPOCH), -1L);
        return v >= 0 ? v : Hawk.get(HawkConfig.SYNC_LAST_EPOCH, 0L);
    }

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

    /**
     * 与首页加载直接相关的配置键：这些键变化后首页必须重新 loadConfig，
     * 否则「首次安装 → 登录 → 同步写回接口地址」后首页仍然是空的。
     */
    /** v79：需要「整包重载首页」的键（接口地址 / 线路 / 首页源）。 */
    private static final String[] HOME_RELOAD_KEYS = {
            HawkConfig.API_URL, HawkConfig.API_LINE_SOURCE, HawkConfig.HOME_API,
    };

    /** v79：只需预热直播配置、完全不必碰首页的键。 */
    private static final String[] LIVE_ONLY_KEYS = {
            HawkConfig.LIVE_API_URL, HawkConfig.EPG_URL,
    };

    private static String configSnapshot(String[] keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            Object v = Hawk.contains(k) ? Hawk.get(k) : null;
            sb.append(k).append('=').append(v == null ? "" : String.valueOf(v)).append('\n');
        }
        return sb.toString();
    }

    /** App 启动时调用一次。 */
    public static void start() {
        if (started) {
            return;
        }
        started = true;
        AccountContext.load();
        // v98 P1：把旧的散键同步状态迁到 SyncState（只执行一次，带标记位）
        String migrateOwner = AccountContext.ownerKey();
        if (migrateOwner != null && migrateOwner.length() > 0) {
            SyncState.migrateLegacyKeys(migrateOwner);
        }
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                sync(false, false, null);
            }
        }, 1500L);
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
        long last = lastSyncAt();
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

    /** 页面主动拉取（历史页 onResume）：绕过自动同步的最小间隔，自带 20 秒节流。 */
    public static void pullNow(final Callback cb) {
        if (AccountContext.isGuest() || !hasConfig()) {
            post(cb, false, "登录后才能同步");
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - lastPullAt < 20 * 1000L) {
                return;
            }
            lastPullAt = now;
        }
        sync(true, false, cb);
    }

    /** 回到前台（首页 onResume）：距上次同步 ≥2 分钟才拉。 */
    public static void onForeground() {
        if (AccountContext.isGuest()) {
            return;
        }
        long last = lastSyncAt();
        if (System.currentTimeMillis() - last >= 2 * 60 * 1000L) {
            sync(false, false, null);
        }
    }

    /** 进后台：有改动且距上次 ≥60 秒时尽力同步一次（不保证完成）。 */
    public static void onBackground() {
        if (AccountContext.isGuest() || !isDirty()) {
            return;
        }
        long last = lastSyncAt();
        if (System.currentTimeMillis() - last >= 60 * 1000L) {
            sync(false, true, null);
        }
    }

    public static boolean isSyncing() {
        return syncing;
    }

    public static String lastSyncText() {
        long last = lastSyncAt();
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
                // 被占用时必须给回调，否则页面会一直停在「正在同步…」
                post(cb, false, "正在同步，请稍后再试");
                return;
            }
            syncing = true;
        }
        if (!manual) {
            long last = lastSyncAt();
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
                boolean aborted = false;
                String msg;
                final int gen = generation;
                boolean engineHandled = false;
                try {
                    if (Hawk.get(HawkConfig.SYNC_ENGINE_V2, true)) {
                        // v98 P1：新引擎（条件请求 304 + 事务化 + 退避熔断），
                        //        成败状态由引擎自行维护，外层不再 noteFailure/clearFailure
                        SyncEngine.Outcome r = SyncEngine.runSafe(owner, key, gen, manual);
                        ok = r.ok;
                        msg = r.message == null ? "同步完成" : r.message;
                        engineHandled = true;
                    } else {
                        msg = doSync(owner, key, manual, gen);
                        ok = true;
                        engineHandled = false;
                    }
                } catch (SwitchAborted e) {
                    aborted = true;
                    msg = e.getMessage();
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
                if (engineHandled) {
                    // 引擎已把 lastAt / etag / dirty / failCount 写进 SyncState
                } else if (ok) {
                    clearFailure();
                } else if (!aborted) {
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

    private static String doSync(String owner, byte[] key, boolean manual, int gen) throws Exception {
        checkGeneration(gen);
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

        long lastEp = lastEpoch();
        boolean resetLocal = remote.epoch > lastEp;
        checkGeneration(gen);
        if (resetLocal) {
            wipeLocal(scopes);
        }

        SyncPayload local = buildLocal(owner, deviceId, scopes, now);
        SyncPayload merged = SyncMerge.merge(local, remote, deviceId, now);
        checkGeneration(gen);
        truncateMergedHistory(merged, scopes);
        applyLocal(merged, scopes);

        // v78：与云端内容完全一致时跳过上传（省流量 + 省一个 PUT 请求）
        if (remoteBytes != null && remoteBytes.length > 0 && contentEquals(merged, remote)) {
            Hawk.put(key(HawkConfig.SYNC_LAST_AT), now);
            Hawk.put(key(HawkConfig.SYNC_LAST_EPOCH), merged.epoch);
            Hawk.put(key(HawkConfig.SYNC_DIRTY), false);
            SettingsProfile.setPendingPull(owner, false);
            SyncTombstones.clear(now);
            LOG.i("[Sync] no change, skip upload owner=" + owner
                    + " rec=" + merged.records.size() + " prog=" + merged.progress.size());
            return "同步完成（无变更）：历史 " + merged.records.size()
                    + " / 进度 " + merged.progress.size()
                    + " / 收藏 " + merged.collects.size()
                    + " / 设置 " + merged.settings.size();
        }

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
                    checkGeneration(gen);
                    applyLocal(merged, scopes);
                    out = Crypto.encrypt(key, GSON.toJson(merged).getBytes("UTF-8"));
                }
            }
            dav.put(path, out, null);
        }

        checkGeneration(gen);
        Hawk.put(key(HawkConfig.SYNC_LAST_AT), now);
        Hawk.put(key(HawkConfig.SYNC_LAST_EPOCH), merged.epoch);
        Hawk.put(key(HawkConfig.SYNC_DIRTY), false);
        // 首次加入的账号已完成「只拉不推」，之后正常参与 LWW
        SettingsProfile.setPendingPull(owner, false);
        SyncTombstones.clear(now);
        LOG.i("[Sync] done owner=" + owner + " rec=" + merged.records.size()
                + " prog=" + merged.progress.size() + " col=" + merged.collects.size()
                + " set=" + merged.settings.size() + " epoch=" + merged.epoch);
        return "同步完成：历史 " + merged.records.size() + " / 进度 " + merged.progress.size()
                + " / 收藏 " + merged.collects.size() + " / 设置 " + merged.settings.size();
    }

    static SyncPayload buildLocal(String owner, String deviceId, SyncScopes scopes, long now) {
        SyncPayload p = new SyncPayload();
        p.owner = owner;
        p.dev = deviceId;
        p.updatedAt = now;
        p.epoch = lastEpoch();
        AppDataManager.get();
        if (scopes.history) {
            VodRecordDao dao = AppDataManager.get().getVodRecordDao();
            // v77：同步只带当前档位的条数（本机 DB 也已按档位裁剪，这里再兜一层）
            int keep = HistoryHelper.getHisNum(Hawk.get(HawkConfig.HISTORY_NUM, 0));
            List<VodRecord> rows = dao.getAll(keep > 0 ? keep : Integer.MAX_VALUE);
            if (rows != null) {
                for (VodRecord r : rows) {
                    SyncPayload.RecordItem it = new SyncPayload.RecordItem();
                    it.sourceKey = r.sourceKey;
                    it.vodId = r.vodId;
                    // S2：同步包只带 10 个必需字段（本地库仍是完整 VodInfo）
                    it.dataJson = HistorySyncJson.slim(r.dataJson);
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
            Map<String, SyncPayload.SettingValue> settingMap =
                    SyncSettings.detectChanges(SyncSettings.loadSnapshot(), now);
            if (SettingsProfile.isPendingPull(owner)) {
                // 首次加入本机的账号：只看云端，不拿默认值覆盖对方设置
                for (SyncPayload.SettingValue sv : settingMap.values()) {
                    sv.t = 0;
                }
            }
            p.setSettingsMap(settingMap);
        }
        SyncTombstones.fill(p);
        return p;
    }

    /**
     * v78：比较两份包的内容是否一致（忽略每次都变的 updatedAt / dev）。
     *
     * <p>只用于"没必要上传"的判定：误判为不同最多多传一次，不会丢数据。</p>
     */
    static boolean contentEquals(SyncPayload a, SyncPayload b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            long at = a.updatedAt;
            long bt = b.updatedAt;
            String ad = a.dev;
            String bd = b.dev;
            a.updatedAt = 0L;
            b.updatedAt = 0L;
            a.dev = "";
            b.dev = "";
            String ja = GSON.toJson(a);
            String jb = GSON.toJson(b);
            a.updatedAt = at;
            a.dev = ad;
            b.updatedAt = bt;
            b.dev = bd;
            return ja.equals(jb);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * v77：云端包也按本机档位裁剪——只保留最新 N 条历史，并丢弃已不在列表里的进度行。
     * 直接丢弃、不写墓碑：否则会把"裁剪"当成用户删除同步到别的设备。
     */
    static void truncateMergedHistory(SyncPayload merged, SyncScopes scopes) {
        if (!scopes.history || merged.records == null) {
            return;
        }
        int keep = HistoryHelper.getHisNum(Hawk.get(HawkConfig.HISTORY_NUM, 0));
        if (keep > 0 && merged.records.size() > keep) {
            List<SyncPayload.RecordItem> sorted = new ArrayList<>(merged.records);
            Collections.sort(sorted, new Comparator<SyncPayload.RecordItem>() {
                @Override
                public int compare(SyncPayload.RecordItem a, SyncPayload.RecordItem b) {
                    if (a.updateTime != b.updateTime) {
                        return a.updateTime > b.updateTime ? -1 : 1;
                    }
                    String da = a.dev == null ? "" : a.dev;
                    String db = b.dev == null ? "" : b.dev;
                    return da.compareTo(db);
                }
            });
            merged.records = new ArrayList<>(sorted.subList(0, keep));
        }
        if (scopes.progress && merged.progress != null) {
            Set<String> alive = new HashSet<>();
            for (SyncPayload.RecordItem r : merged.records) {
                alive.add((r.sourceKey == null ? "" : r.sourceKey) + "\u0001" + (r.vodId == null ? "" : r.vodId));
            }
            List<SyncPayload.ProgressItem> kept = new ArrayList<>();
            for (SyncPayload.ProgressItem p : merged.progress) {
                String k = (p.sourceKey == null ? "" : p.sourceKey) + "\u0001" + (p.vodId == null ? "" : p.vodId);
                if (alive.contains(k)) {
                    kept.add(p);
                }
            }
            merged.progress = kept;
        }
    }

    static void applyLocal(SyncPayload merged, SyncScopes scopes) {
        SyncTombstones.suppress(true);
        try {
            AppDataManager.get();
            // v98 P1：三张表放进同一个 Room 事务 —— 失败即整体回滚，不再留下"半套数据"
            AppDataManager.get().runInTransaction(() -> {
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
            });
            // Hawk 写在事务之后：Room 事务回滚不了 Hawk，故此处必须幂等（重复执行结果一致）
            if (scopes.settings) {
                String homeBefore = configSnapshot(HOME_RELOAD_KEYS);
                String liveBefore = configSnapshot(LIVE_ONLY_KEYS);
                SyncSettings.apply(merged.settingsMap());
                SyncSettings.saveSnapshot(merged.settingsMap());
                final boolean homeChanged = !homeBefore.equals(configSnapshot(HOME_RELOAD_KEYS));
                final boolean liveChanged = !liveBefore.equals(configSnapshot(LIVE_ONLY_KEYS));
                if (homeChanged || liveChanged) {
                    if (System.currentTimeMillis() - APP_START_AT < STARTUP_GRACE_MS) {
                        // v88：启动静默期内不打扰首页首屏与起播
                        pendingConfigEvent = true;
                        LOG.i("[Sync] config changed during startup grace, deferred");
                    } else {
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                if (homeChanged) {
                                    // v79：只有接口地址/线路/首页源变了才整包重载首页
                                    EventBus.getDefault().post(
                                            new RefreshEvent(RefreshEvent.TYPE_CONFIG_CHANGE));
                                }
                                // 直播地址/EPG 变化：只预热，不动首页
                                LiveConfigPrefetcher.prefetch();
                            }
                        });
                    }
                }
            }
        } finally {
            SyncTombstones.suppress(false);
        }
    }

    /** 云端 epoch 变化（整库重置）时清空本机该账号的同步范围数据。 */
    static void wipeLocal(SyncScopes scopes) {
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
                    empty.epoch = lastEpoch() + 1;
                    empty.updatedAt = System.currentTimeMillis();
                    empty.dev = deviceId();
                    byte[] out = Crypto.encrypt(key, GSON.toJson(empty).getBytes("UTF-8"));
                    dav.put(AccountPaths.data(accountName), out, null);
                    Hawk.put(key(HawkConfig.SYNC_LAST_EPOCH), empty.epoch);
                    Hawk.put(key(HawkConfig.SYNC_LAST_AT), System.currentTimeMillis());
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
