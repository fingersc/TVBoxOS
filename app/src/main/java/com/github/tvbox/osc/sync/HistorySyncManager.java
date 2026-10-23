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
import com.github.tvbox.osc.util.HistoryHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 同步编排（数据面）：导出 → 拉取 → 解密 → 合并 → 写回 → 加密上传。
 *
 * <p>触发（v98 P2 起全部经由 {@link SyncScheduler}）：冷启动 1.5s / 回前台 /
 * 数据变更（60s 防抖 + 合并）/ 自适应间隔轮询（5→10→20→30min，后台不轮询）/
 * 手动 / 退出播放 / 网络恢复 / 切换账号。</p>
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
    /** 两次自动同步的最小间隔（手动同步不受此限）。 */
    private static final long MIN_GAP_MS = 60 * 1000L;

    /**
     * ★ v99-r18：切后台/退出播放页那「最后一次补传」的最小间隔。
     *
     * <p>用 60s 的话实测 100% 被挡：退出播放距上一次同步往往只有几十秒，而进程随时可能被杀，
     * 等满 60s 就等于没传。降到 10s 既能立刻落盘，又不至于被「反复进出播放页」刷成高频同步。
     * 注意它<b>只放宽最小间隔，「仅 Wi-Fi」门禁照旧生效</b> —— 后台自动同步绝不能烧移动流量。</p>
     */
    private static final long MIN_GAP_BACKGROUND_MS = 10 * 1000L;

    private static volatile boolean syncing;
    private static boolean started;
    /** 页面主动拉取的节流时间戳（避免来回切页面刷请求）。 */
    private static long lastPullAt;
    /** 账号切换代数：切换时 +1，在途同步发现代数变了就放弃写库（防串库）。 */
    private static volatile int generation;
    // v98 P3：原先的「8 秒启动静默期 + pendingConfigEvent」已删除 ——
    //         它由 ConfigCenter 的 homeReady / uiReady 两个门控取代，口径更准
    //         （不是拍一个 8 秒常数，而是真的等首屏数据加载完）。

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

    /**
     * v98 P2：同步状态统一读 {@link SyncState}（每账号一份），
     * 只有「还没迁过」时才回退到旧的散键（start() 与 AccountSwitcher 都会迁）。
     */
    private static String owner() {
        return AccountContext.ownerKey();
    }

    private static long lastSyncAt() {
        String owner = owner();
        if (owner != null && owner.length() > 0) {
            SyncState st = SyncState.load(owner);
            if (st.lastAt > 0) {
                return st.lastAt;
            }
        }
        long v = Hawk.get(key(HawkConfig.SYNC_LAST_AT), -1L);
        return v >= 0 ? v : Hawk.get(HawkConfig.SYNC_LAST_AT, 0L);
    }

    private static long lastEpoch() {
        String owner = owner();
        if (owner != null && owner.length() > 0) {
            SyncState st = SyncState.load(owner);
            if (st.lastEpoch > 0) {
                return st.lastEpoch;
            }
        }
        long v = Hawk.get(key(HawkConfig.SYNC_LAST_EPOCH), -1L);
        return v >= 0 ? v : Hawk.get(HawkConfig.SYNC_LAST_EPOCH, 0L);
    }

    // v98 P2：原先的 DEBOUNCE_TASK（5 分钟防抖）与 PERIODIC_TASK（固定 30 分钟周期）
    //         已由 SyncScheduler 接管：前者变成 60 秒防抖 + 合并，后者变成自适应间隔
    //         （5→10→20→30 分钟，且后台不轮询）。这里不再自己 postDelayed。

    private HistorySyncManager() {
    }

    // v98 P3：原先的 HOME_RELOAD_KEYS / LIVE_ONLY_KEYS / configSnapshot 已搬到
    //         ConfigPlan（键 → 动作分类表）与 ConfigCenter（前后快照 diff）。
    //         这里不再关心「哪些键需要动界面」——那是界面的事，不是同步的事。

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
        // v98 P2：冷启动那一次交给调度器（1.5 秒内必跑一次）；此后的周期轮询
        //         由调度器按自适应间隔接管，App 不再自己持有 30 分钟的定时器。
        SyncScheduler.requestWithin(migrateOwner, "boot", 1500L);
    }

    /** 按账号命名空间取 Hawk 键（游客用原键）。 */
    private static String key(String base) {
        String owner = AccountContext.ownerKey();
        return (owner == null || owner.length() == 0) ? base : base + "." + owner;
    }

    /**
     * 数据变更时调用（旧签名）：默认标脏「历史 + 进度 + 收藏」。
     *
     * <p>保留旧签名是为了让既有调用点零改动；需要精确标脏的地方请用
     * {@link #markDirty(SyncScope...)}。</p>
     */
    public static void markDirty() {
        markDirty(SyncScope.HISTORY, SyncScope.PROGRESS, SyncScope.COLLECT);
    }

    /**
     * v98 P2：按 scope 标脏 —— 「改一个设置」不再等价于「四张表全脏」。
     *
     * <p>当前引擎还没按 scope 裁剪请求体（协议不变、行为等价），按 scope 标脏
     * 立刻生效的是<b>调度层</b>：只有真脏的账号才走 60 秒防抖的立即档，
     * 其余走自适应间隔。</p>
     */
    public static void markDirty(SyncScope... scopes) {
        // 旧键仍写一份：回退到旧引擎（SYNC_ENGINE_V2=false）时仍然看得到脏标记
        Hawk.put(key(HawkConfig.SYNC_DIRTY), true);
        if (AccountContext.isGuest()) {
            return;
        }
        SyncState st = SyncState.load(owner());
        st.markDirty(scopes);
        st.save();
        SyncScheduler.request(owner(), "dirty");
    }

    public static boolean isDirty() {
        String owner = owner();
        if (owner == null || owner.length() == 0) {
            return false;
        }
        if (SyncState.exists(owner)) {
            return SyncState.load(owner).anyDirty();
        }
        // 还没迁过（理论上不会发生）⇒ 读旧键兜底
        return Hawk.get(key(HawkConfig.SYNC_DIRTY), false);
    }

    /** 退出 App / 切换账号前：是否值得等一次同步。 */
    public static boolean needsSyncBeforeExit() {
        return !AccountContext.isGuest() && isDirty() && hasConfig();
    }

    /** 退出播放页（进度刚落盘）：有改动就交给调度器（防抖窗口内必跑一次）。 */
    public static void onPlaybackExit() {
        if (AccountContext.isGuest() || !isDirty()) {
            return;
        }
        SyncScheduler.request(owner(), "playback-exit");
    }

    /**
     * 前台网络从断到通：1 秒内补一次。
     *
     * <p>v98 P2：若之前是「网络抖动」造成的失败（连续失败 ≤2 次）就顺手解除熔断 ——
     * 网都回来了还白等 30 秒 / 2 分钟没有意义。<b>认证失败（failCount=3）不解除</b>：
     * 否则网一恢复就拿错误的应用密码去撞 15 分钟。</p>
     */
    public static void onNetworkAvailable() {
        if (AccountContext.isGuest() || !hasConfig()) {
            return;
        }
        SyncState st = SyncState.load(owner());
        if (st.failCount > 0 && st.failCount <= 2) {
            st.clearFailure();
            st.save();
        }
        SyncScheduler.requestWithin(owner(), "net-up", 1000L);
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
        SyncScheduler.requestNow(owner(), "pull", false, cb);
    }

    /**
     * 回到前台（首页 onResume）。
     *
     * <p>v98 P2：不再自己算「距上次 ≥2 分钟」，改由调度器按自适应间隔判断 ——
     * 脏 ⇒ 防抖窗口内跑；不脏 ⇒ 5/10/20/30 分钟档。</p>
     */
    public static void onForeground() {
        if (AccountContext.isGuest()) {
            return;
        }
        SyncScheduler.request(owner(), "fg");
    }

    /** 进后台：有改动就尽快补一次（不保证完成）；纯轮询任务由调度器取消。 */
    public static void onBackground() {
        if (AccountContext.isGuest() || !isDirty()) {
            return;
        }
        SyncScheduler.requestWithin(owner(), SyncScheduler.REASON_BG, 0L);
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

    /**
     * v98 P3：账号页展示的完整状态 —— 时间 + 正常 / 有未同步改动 / 重试中 / 已暂停。
     *
     * <p>熔断期间用户此前是完全看不到的（只显示「上次同步：xx」），
     * 会误以为同步还在正常工作。</p>
     */
    public static String syncStatusText() {
        if (AccountContext.isGuest()) {
            return "登录后可跨设备同步";
        }
        SyncState st = SyncState.load(owner());
        StringBuilder sb = new StringBuilder("上次同步：");
        if (st.lastAt <= 0) {
            sb.append("尚未同步");
        } else {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
            sb.append(f.format(new java.util.Date(st.lastAt)));
        }
        if (st.needsManualSync()) {
            // ★ P3-4：永久熔断时必须明确告诉用户「不会自动恢复」——
            //   否则沿用下面的「N 秒后重试」文案，用户会一直等一个永远不会到来的重试。
            sb.append("（已停止自动同步：连续失败 ").append(st.failCount)
                    .append(" 次，请检查网盘配置后点「立即同步」）");
        } else if (st.inBackoff()) {
            long left = (st.failUntil - System.currentTimeMillis()) / 1000L;
            sb.append("（已暂停，").append(Math.max(1, left)).append(" 秒后重试）");
        } else if (st.failCount > 0) {
            sb.append("（重试中）");
        } else if (st.anyDirty()) {
            sb.append("（有未同步改动）");
        } else {
            sb.append("（正常）");
        }
        return sb.toString();
    }

    /** 手动同步：立即执行，绕过最小间隔与「仅 Wi-Fi」，并强制上传（无视内容比对）。 */
    public static void syncNow(Callback cb) {
        if (AccountContext.isGuest() || !hasConfig()) {
            // 调度器对游客 / 未配置是静默忽略的，这里必须直接走一次，让页面拿到提示
            sync(true, false, true, cb);
            return;
        }
        SyncScheduler.requestNow(owner(), "manual", true, cb);
    }

    // ---------------------------------------------------------------- 主流程

    /**
     * 真正跑一次同步（包可见：由 {@link SyncScheduler} 的任务线程调用）。
     *
     * <p>v98 P2：所有「这次没跑成」的分支都要回调 {@link SyncScheduler#skipped}，
     * 否则调度器的自适应轮询链条会断掉（旧代码靠一个 30 分钟定时器兜底，现在没有了）。</p>
     */
    /**
     * 跑一次同步。
     *
     * <p>★ v99-r20：{@code manual}（绕过执行层门禁）与 {@code force}（引擎层无视内容比对
     * 强推 + 熔断强破）解耦。此前两者同源（都来自 {@code pri == PRI_NOW}），导致打开历史页
     * 的 {@code pullNow} 即使内容与云端一致也全量 PUT 一遍。现在只有「立即同步 / 退出 App /
     * 切换账号」保持 force=true，{@code pullNow} 传 force=false —— 它的语义是"拉"。</p>
     *
     * @param manual     绕过执行层门禁（最小间隔 / 仅 Wi-Fi）
     * @param background 进后台补传（最小间隔走 10s 档，见 {@link #MIN_GAP_BACKGROUND_MS}）
     * @param force      强制上传（引擎层无视内容比对；熔断中强行恢复）
     * @param cb         完成回调
     */
    static void sync(final boolean manual, final boolean background, final boolean force, final Callback cb) {
        final String owner = AccountContext.ownerKey();
        if (AccountContext.isGuest()) {
            post(cb, false, "游客模式：登录后才能同步");
            SyncScheduler.skipped(owner, "guest");
            return;
        }
        if (!hasConfig()) {
            post(cb, false, "请先配置 WebDAV 存储");
            SyncScheduler.skipped(owner, "no-config");
            return;
        }
        final byte[] key = AccountContext.key();
        if (key == null || owner == null || owner.length() == 0) {
            post(cb, false, "登录状态异常，请重新登录");
            SyncScheduler.skipped(owner, "bad-account");
            return;
        }
        boolean taken = false;
        synchronized (LOCK) {
            if (!syncing) {
                syncing = true;
                taken = true;
            }
        }
        if (!taken) {
            // 被占用时必须给回调，否则页面会一直停在「正在同步…」
            post(cb, false, "正在同步，请稍后再试");
            SyncScheduler.skipped(owner, "busy");
            return;
        }
        if (!manual) {
            long last = lastSyncAt();
            // r18：切后台的补传走更短的最小间隔（见 MIN_GAP_BACKGROUND_MS）。
            long gap = background ? MIN_GAP_BACKGROUND_MS : MIN_GAP_MS;
            long elapsed = System.currentTimeMillis() - last;
            if (elapsed < gap) {
                syncing = false;
                // ★ v99-r39：按「真实剩余时间」重排，而不是套用 retry-dirty 的固定 60 秒档。
                //   距上次同步只差几秒时却被推到 60 秒后，退出播放那一刻的进度就传不上去了。
                //   留 300ms 余量：避免算出来 0 导致同一毫秒内反复重试。
                long wait = Math.max(gap - elapsed, 300L);
                SyncScheduler.skipped(owner, background ? "min-gap-bg" : "min-gap", wait);
                return;
            }
            if (Hawk.get(HawkConfig.SYNC_WIFI_ONLY, false) && !isWifi()) {
                syncing = false;
                SyncScheduler.skipped(owner, "wifi-only");
                return;
            }
            // v98 P2：新引擎自己在内部判断熔断（0 请求早退 + 给"已暂停"文案）；
            //         只有回退到旧 doSync 时才需要在这里挡一道。
            if (!Hawk.get(HawkConfig.SYNC_ENGINE_V2, true) && SyncState.load(owner).inBackoff()) {
                syncing = false;
                SyncScheduler.skipped(owner, "backoff");
                return;
            }
        }
        final long t0 = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                boolean aborted = false;
                String msg;
                final int gen = generation;
                boolean engineHandled = false;
                int requests = -1;   // -1 = 旧路径未统计
                long down = 0L;
                long up = 0L;
                boolean notModified = false;
                try {
                    // ★ v99-r8：云端已改为「按 scope 分文件」（schema 3），旧引擎 doSync 只认
                    //   单文件 data.bin，回退过去会读到过期数据 ⇒ 这里强制走新引擎，
                    //   SYNC_ENGINE_V2 开关不再生效（doSync 代码保留仅供查阅，不再调用）。
                    //   成败状态由引擎自行维护，外层不再 noteFailure/clearFailure。
                    // ★ v99-r11-fix：runSafe **不抛异常**（内部把异常全转成 Outcome），
                    //   所以本 try 体里没有任何受检异常可抛 ⇒ 原来那批
                    //   catch(SwitchAborted/AuthException/ConflictException/IOException)
                    //   全部不可达，javac 直接编译失败
                    //   （"exception X is never thrown in body of corresponding try statement"）。
                    //   改为：只 catch Throwable 兜底，具体的错误语义由 Outcome.message 承载。
                    {
                        SyncEngine.Outcome r = SyncEngine.runSafe(owner, key, gen, force);
                        ok = r.ok;
                        msg = r.message == null ? "同步完成" : r.message;
                        engineHandled = true;
                        requests = r.requests;
                        down = r.bytesDown;
                        up = r.bytesUp;
                        notModified = r.notModified;
                        // ★ 作废（切换账号）必须由返回值传递，不能靠 catch
                        aborted = r.aborted;
                    }
                } catch (Throwable t) {
                    // 兜底：runSafe 理论上不抛，但真抛了也不能让同步线程静默死掉
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
                // v98 P2：结构化日志 —— 一眼看出这次同步到底发了几个请求、跑了多少字节
                String state = !ok ? "FAIL"
                        : (engineHandled && requests == 0 ? "BACKOFF" : "OK");
                LOG.i("[sync] owner=" + owner + " reason=" + (manual ? "manual" : "auto")
                        + " req=" + requests + " down=" + down + " up=" + up
                        + " skip=" + (notModified ? "304" : (up > 0 ? "no" : "same"))
                        + " state=" + state + " dur=" + (System.currentTimeMillis() - t0) + "ms");
                post(cb, ok, msg);
                if (aborted) {
                    // 账号切换作废：不算一次有效同步，不升降档
                    SyncScheduler.skipped(owner, "aborted");
                } else {
                    SyncScheduler.onSyncResult(owner, ok && (up > 0 || !notModified), up > 0);
                }
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
        // ★ v99-r7 P0-3：与 SyncEngine 同一修法 —— 不再物理删库，
        //   改用「空包参与合并」。旧实现在这里就删掉三张表，而流程此刻还没有任何成功保证：
        //   "epoch 变更 + 上传失败" ⇒ lastEpoch 不更新 ⇒ 下次又删一次，把期间新数据也清掉。
        SyncPayload local = resetLocal
                ? emptyLocal(owner, deviceId, now)
                : buildLocal(owner, deviceId, scopes, now);
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

    /**
     * ★ v99-r7 P0-3：整库重置（云端 epoch 变化）时参与合并的「空本地视图」。
     *
     * <p>不在这里物理删库 —— 删除要等 {@code applyLocal(merged)} 在事务里完成。
     * 否则「本地已清空但上传失败」会让用户数据凭空消失，且下次同步还会再清一次。</p>
     *
     * <p>为什么必须用空包代替本地数据：merge 时 remote 是空包，
     * 若仍拿真实本地数据参与合并，{@code pickRecord(a, null)} 会返回 a，
     * 本地旧条目全部胜出 ⇒ 等于把整库重置抵消掉了。</p>
     */
    static SyncPayload emptyLocal(String owner, String deviceId, long now) {
        SyncPayload p = new SyncPayload();
        p.owner = owner;
        p.dev = deviceId;
        p.updatedAt = now;
        p.epoch = 0;   // merge 取 max ⇒ 云端的新 epoch 胜出
        return p;
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

    /**
     * ★ v99-r10：每部片最多保留多少条播放进度<b>记录</b>（不分线路）。
     *
     * <p>本地落库（{@code PlayProgressManager.trimByVod}）与云端同步包
     * （{@link #truncateMergedProgress}）共用这一个口径，避免两边不一致。</p>
     *
     * <p>换线路时旧线路的记录会整批迁到新线路并丢弃
     * （{@code PlayProgressManager.migrateByFlag}），所以稳态下每片就是
     * 「N 集 × 当前那一条线路」，不需要为每条线路各留 N 套。</p>
     */
    public static final int KEEP_PROGRESS_EPISODES = 10;

    /**
     * ★ v99-r10：把同步包里的进度也裁到「每片最近 {@link #KEEP_PROGRESS_EPISODES} 集」。
     *
     * <p><b>为什么本地裁了还要裁这里</b>：云端可能还存着裁之前的老数据（几十上百条），
     * 一次 {@code merge(local 10 条, remote 100 条)} 会按 LWW 求并集又变回 100 条，
     * 然后 {@code applyLocal} 把它写回本地 —— 本地刚裁掉的全被"复活"。
     * 所以在 applyLocal 之前先裁 merged，云端才能被真正收敛。</p>
     */
    static void truncateMergedProgress(SyncPayload merged, SyncScopes scopes) {
        if (!scopes.progress || merged.progress == null || merged.progress.isEmpty()) {
            return;
        }
        // 规则在 SyncMerge.keepRecent（纯函数，本地落库与这里共用同一套）
        merged.progress = SyncMerge.keepRecent(merged.progress, KEEP_PROGRESS_EPISODES);
    }

    static void applyLocal(SyncPayload merged, SyncScopes scopes) {
        SyncTombstones.suppress(true);
        try {
            AppDataManager.get();
            // ★ v99-r7 P0-1：merged 里的 dataJson 是「同步包精简格式」（HistorySyncJson.slim，只留 10 个字段）。
            //   原先这里 deleteAll 后把精简版原样写回 ⇒ **同步一次就把本机完整 VodInfo 降级成 10 字段**，
            //   与 HistorySyncJson 头部承诺的「本机数据库仍保存完整 VodInfo」相反，而且不可逆
            //   （后续上传的也是精简版，云端与所有设备一起降级）。
            //   修法：落库前先按 (sourceKey|vodId) 把本机现有的完整 dataJson 读出来，
            //        命中则保留本机完整版，其余字段（updateTime 等）仍用合并结果。
            final Map<String, String> localFullJson = new java.util.HashMap<>();
            if (scopes.history) {
                List<VodRecord> olds = AppDataManager.get().getVodRecordDao().getAll(Integer.MAX_VALUE);
                if (olds != null) {
                    for (VodRecord r : olds) {
                        if (r != null && r.dataJson != null && r.dataJson.length() > 0) {
                            localFullJson.put(SyncMerge.recordKey(r.sourceKey, r.vodId), r.dataJson);
                        }
                    }
                }
            }
            // v98 P1：三张表放进同一个 Room 事务 —— 失败即整体回滚，不再留下"半套数据"
            AppDataManager.get().runInTransaction(() -> {
            if (scopes.history) {
                VodRecordDao dao = AppDataManager.get().getVodRecordDao();
                dao.deleteAll();
                for (SyncPayload.RecordItem it : merged.records) {
                    VodRecord r = new VodRecord();
                    r.sourceKey = it.sourceKey;
                    r.vodId = it.vodId;
                    String full = localFullJson.get(SyncMerge.recordKey(it.sourceKey, it.vodId));
                    r.dataJson = full != null ? full : it.dataJson;
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
                // v98 P3：写 Hawk + 判定「需要哪些界面动作」交给 ConfigCenter：
                //   · 键 → 动作的分类表（reload / home / live）在 ConfigPlan
                //   · 何时真正执行（首屏已就绪 / 首页在前台 / 60 秒节流 + 合并）由 flush() 决定
                // 取代原先「8 秒启动静默期 + pendingConfigEvent + 无条件直播预热」的老三件套：
                // 老实现里 homeChanged || liveChanged 都会去预热直播，改个首页源也白跑一趟网络。
                ConfigCenter.apply(merged.settingsMap());
                ConfigCenter.flush();
            }
        } finally {
            SyncTombstones.suppress(false);
        }
    }

    /**
     * 清空本机该账号的同步范围数据。
     *
     * <p>★ v99-r7 起<b>不再由同步流程调用</b>（改为 {@link #emptyLocal} + applyLocal 事务内重写）——
     * 提前物理删除在"上传失败"时会造成数据反复丢失。保留本方法供将来"用户主动清空本机数据"使用。</p>
     */
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
        final int gen = generation;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // ★ v99-r8：改为走引擎（原来这里自己 new WebDavClient + put(...,null)：
                //   不查熔断、不带 If-Match，与正在同步的设备可能互相覆盖；
                //   而且只清云端不清本机，下次同步又把数据传回去，等于没清）。
                SyncEngine.Outcome r = SyncEngine.clearCloud(owner, key, gen);
                if (r.ok) {
                    SyncState st = SyncState.load(owner);
                    Hawk.put(key(HawkConfig.SYNC_LAST_EPOCH), st.lastEpoch);
                    Hawk.put(key(HawkConfig.SYNC_LAST_AT), st.lastAt);
                    Hawk.put(key(HawkConfig.SYNC_DIRTY), false);
                }
                post(cb, r.ok, r.message == null ? (r.ok ? "已清空云端" : "清空失败") : r.message);
            }
        }, "tvbox-sync-clear").start();
    }

    // ---------------------------------------------------------------- 失败退避

    /**
     * v98 P2：失败退避统一写 {@link SyncState}。
     *
     * <p>此前只写旧的散键，而 {@code sync()} 的熔断判断已经改读 SyncState，
     * 会导致「回退到旧引擎后熔断失效，失败一次就无限重试」。新旧两份都写，
     * 保证两条路径看到的是同一份状态。</p>
     */
    private static void noteFailure() {
        SyncState st = SyncState.load(owner());
        st.noteFailure();
        st.save();
        Hawk.put(key(HawkConfig.SYNC_FAIL_COUNT), st.failCount);
        Hawk.put(key(HawkConfig.SYNC_FAIL_UNTIL), st.failUntil);
    }

    private static void clearFailure() {
        SyncState st = SyncState.load(owner());
        st.clearFailure();
        st.save();
        Hawk.put(key(HawkConfig.SYNC_FAIL_COUNT), 0);
        Hawk.put(key(HawkConfig.SYNC_FAIL_UNTIL), 0L);
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
