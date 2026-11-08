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
    /** 退出/手动同步撞上「正在同步」时，挂起等待当前同步收尾的回调（v4.1.2）。 */
    private static final ArrayList<Callback> WAITERS = new ArrayList<>();
    /** 两次自动同步的最小间隔（手动同步不受此限）。 */
    // v4.2.1：前台最小间隔从 60s 降到 30s，与 onForeground 的 30s 新鲜度门控对齐。
    // 否则「回前台立即拉」会在执行层被 60s min-gap 二次挡住（真机日志 05:23:56
    // 已排 0ms，仍被 min-gap 推到 14s 后）。最坏 2 次/分钟，仍属低频。
    private static final long MIN_GAP_MS = 30 * 1000L;

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
    /**
     * ★ v4.2.2（B）：冷启动那一轮是否只跑 SETTINGS + HISTORY。
     *
     * <p>首页「最近观看」只依赖 HISTORY（SETTINGS 提供账号档位，必须先行）。
     * 首轮把 PROGRESS/COLLECT 推迟，可少 2 个条件 GET，首屏「那张卡片」出现得更早；
     * 被推迟的 scope 脏标记保持不变，紧随其后的一轮补跑照常上传，不丢数据。</p>
     */
    private static volatile boolean firstRoundPending;
    /**
     * ★ v4.2.2（B）：首轮轻量同步结束后，下一轮「补跑」是否跳过 min-gap。
     *
     * <p>只放宽最小间隔，<b>「仅 Wi-Fi」门禁照旧生效</b> —— 补跑绝不能烧移动流量。
     * 用完即复位，避免长期绕过间隔。</p>
     */
    private static volatile boolean continuePending;
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
    public static String owner() {
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
            reconcilePushedHlc(migrateOwner);
        }
        // v98 P2：冷启动那一次交给调度器（1 秒内必跑一次）；此后的周期轮询
        //         由调度器按自适应间隔接管，App 不再自己持有 30 分钟的定时器。
        // ★ v4.2.2（B）：并把这一轮标记为「首轮轻量同步」——只处理 SETTINGS + HISTORY，
        //   让首页「最近观看」尽快出现（PROGRESS/COLLECT 交给紧随其后的下一轮）。
        firstRoundPending = true;
        SyncScheduler.requestWithin(migrateOwner, "boot", 1000L);
    }

    /** 按账号命名空间取 Hawk 键（游客用原键）。 */
    private static String key(String base) {
        String owner = AccountContext.ownerKey();
        return (owner == null || owner.length() == 0) ? base : base + "." + owner;
    }

    /**
     * ★ v4.1：启动时用 DB 里的最大 HLC 对账「已推送水位」。
     *
     * <p>若本机数据 HLC 大于上次成功推送的 pushedHlc（进程被杀、上传失败、升级后首次运行），
     * 说明有本地写入尚未上传；此时把对应 scope 标脏，保证下一次同步会带上这些数据。
     * 没有这一步，dirty 布尔位可能在崩溃/重启后丢失，本机数据永远不再上传。</p>
     */
    /**
     * v4.1.3：本机 DB 中某个 scope 的最大 HLC。
     * 用于判断「同步结束时，是否还有本机写入没有推送到 pushedHlc」。
     */
    public static Hlc localMaxHlc(SyncScope scope) {
        if (scope == null) {
            return Hlc.ZERO;
        }
        try {
            com.github.tvbox.osc.data.AppDataBase db = AppDataManager.get();
            Long p = null;
            Integer c = null;
            switch (scope) {
                case HISTORY:
                    p = db.getVodRecordDao().maxHlcPhysical();
                    c = db.getVodRecordDao().maxHlcCounter();
                    break;
                case PROGRESS:
                    p = db.getPlayProgressDao().maxHlcPhysical();
                    c = db.getPlayProgressDao().maxHlcCounter();
                    break;
                case COLLECT:
                    p = db.getVodCollectDao().maxHlcPhysical();
                    c = db.getVodCollectDao().maxHlcCounter();
                    break;
                default:
                    return Hlc.ZERO;
            }
            return new Hlc(p == null ? 0L : p.longValue(),
                    c == null ? 0 : c.intValue(), "");
        } catch (Throwable t) {
            LOG.e("[sync] localMaxHlc failed: " + scope, t);
            return Hlc.ZERO;
        }
    }

    private static void reconcilePushedHlc(String owner) {
        try {
            SyncState st = SyncState.load(owner);
            com.github.tvbox.osc.data.AppDataBase db = com.github.tvbox.osc.data.AppDataManager.get();
            boolean changed = false;
            Long rp = db.getVodRecordDao().maxHlcPhysical();
            Integer rc = db.getVodRecordDao().maxHlcCounter();
            if (rp != null && rp > 0) {
                Hlc pushed = st.pushedHlc(SyncScope.HISTORY);
                if (rp > pushed.physical || (rp.longValue() == pushed.physical && (rc == null ? 0 : rc) > pushed.counter)) {
                    st.markDirty(SyncScope.HISTORY);
                    changed = true;
                }
            }
            Long pp = db.getPlayProgressDao().maxHlcPhysical();
            Integer pc = db.getPlayProgressDao().maxHlcCounter();
            if (pp != null && pp > 0) {
                Hlc pushed = st.pushedHlc(SyncScope.PROGRESS);
                if (pp > pushed.physical || (pp.longValue() == pushed.physical && (pc == null ? 0 : pc) > pushed.counter)) {
                    st.markDirty(SyncScope.PROGRESS);
                    changed = true;
                }
            }
            Long cp = db.getVodCollectDao().maxHlcPhysical();
            Integer cc = db.getVodCollectDao().maxHlcCounter();
            if (cp != null && cp > 0) {
                Hlc pushed = st.pushedHlc(SyncScope.COLLECT);
                if (cp > pushed.physical || (cp.longValue() == pushed.physical && (cc == null ? 0 : cc) > pushed.counter)) {
                    st.markDirty(SyncScope.COLLECT);
                    changed = true;
                }
            }
            if (changed) {
                st.save();
            }
        } catch (Throwable t) {
            LOG.e("[sync] reconcile pushedHlc failed", t);
        }
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
     * 立刻生效的是<b>调度层</b>：只有真脏的账号才走防抖的立即档
     * （播放中 5 分钟、其他场景 60 秒），其余走自适应间隔。</p>
     */
    public static void markDirty(SyncScope... scopes) {
        // ★ r193 P3-1：游客态直接返回，**不写任何脏标记**。
        //   此前这里先写旧散键再判游客，游客下 key(base) 落到无后缀的全局键
        //   「sync_dirty」：既没人读（isDirty 游客恒 false）也没人清，
        //   还会在首个账号做 legacy 迁移时被误当成「该账号的脏标记」
        //   （见 SyncState.migrateLegacyKeys），把 4 个 scope 全部标脏。
        if (AccountContext.isGuest()) {
            return;
        }
        // 旧键仍写一份：回退到旧引擎（SYNC_ENGINE_V2=false）时仍然看得到脏标记
        // （登录态下 key() 会带上 owner 后缀，不会污染全局键）
        Hawk.put(key(HawkConfig.SYNC_DIRTY), true);
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

    /**
     * ★ v4.2.5：退出播放页时的「合并窗口」。
     *
     * <p>一次退出会连着重几笔：{@code onPause} 落进度（脏 PROGRESS）→ {@code onStop}
     * 标脏 HISTORY → {@code onDestroyView} 再落一次进度。若第一笔就 0ms 起跑，
     * 后两笔只能等下一轮 ⇒ 同一次退出被拆成两轮同步（一轮进度、一轮历史），
     * 各付一次 PROPFIND + 完整 GET，看起来就是「同步了两次」。</p>
     *
     * <p>推迟 {@code 300ms} 让三笔都落到开跑之前，一轮带两个 scope 走。
     * 300ms 在退出路径的 2.5s 预算内（{@code HomeActivity.exitWithSync}），
     * 也不会把「进后台补传」推得太晚。</p>
     */
    static final long SETTLE_MS = 300L;

    /** 退出播放页（进度刚落盘）：有改动就交给调度器（合并窗口后必跑一次）。 */
    public static void onPlaybackExit() {
        if (AccountContext.isGuest() || !isDirty()) {
            return;
        }
        SyncScheduler.requestWithin(owner(), "playback-exit", SETTLE_MS);
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
        // r171：20 秒节流只度量“进程内真实流逝”，不能用墙钟——模拟器时钟回拨时
        //       now - lastPullAt 会长期为负，pullNow 被永久挡住。
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (now - lastPullAt < 20 * 1000L) {
                return;
            }
            lastPullAt = now;
        }
        SyncScheduler.requestNow(owner(), "pull", false, cb);
    }

    /**
     * 回到前台（首页 onResume / 播放页 onResume）。
     *
     * <p>v98 P2：不再自己算「距上次 ≥2 分钟」，改由调度器按自适应间隔判断 ——
     * 脏 ⇒ 防抖窗口内跑；不脏 ⇒ 5/10/20/30 分钟档。</p>
     *
     * <p>★ v4.2.7：<b>0ms 抢跑只用于「本地不脏」的纯拉取。</b></p>
     *
     * <p>原来不区分脏/不脏，一律 {@code requestWithin("fg", 0)}。而「切源」会重建
     * 播放页，播放页 {@code onResume} 又必然调到这里，于是「切源刚标脏的任务」被
     * 立刻提前到 0ms —— 实测日志（2026-11-07 00:01，设备本地时间）：</p>
     *
     * <pre>
     *   17:53:53.582 [sync] schedule reason=dirty pri=1 in 60000ms
     *   17:53:53.643 [sync] pull-forward reason=fg capMs=0 -> keep dirty in 0ms   ← 51ms 后
     *   17:53:54.169 [Sync] uploaded scope=HISTORY bytes=8261
     * </pre>
     *
     * <p>切源本身没有时效性需求：脏标记已落盘（{@code SyncState.save()}），进程被杀
     * 还有 boot 轮 + {@code reconcilePushedHlc} 兜底；真正有时效的是「退出播放」那一刻
     * 的进度，那条路径有 300ms 合并窗口（{@link #onBackground()} 与
     * {@link #onPlaybackExit()}）。</p>
     *
     * <p>所以脏数据交给既有防抖（60s）或退出窗口（300ms）带走，回前台只负责
     * 「把另一台设备的改动拉下来」—— 只有那个场景才需要 0ms。</p>
     */
    public static void onForeground() {
        if (AccountContext.isGuest()) {
            return;
        }
        // v4.2：回前台若距上次成功同步已超过 30s，立即补拉一次（0ms 提前），
        // 不再走 5/10/20/30 分钟的 idle 档——否则另一台设备的删除/进度要等很久。
        // 30s 内刚同步过则维持原调度，避免来回切换页面时抖动。
        long last = lastSyncAt();
        long now = com.github.tvbox.osc.sync.TrustedClock.nowPhysical();
        boolean stale = last <= 0 || now < last || now - last > 30_000L;
        if (stale && !isDirty()) {
            SyncScheduler.requestWithin(owner(), "fg", 0L);
        } else {
            // 有脏：走防抖（播放中 5 分钟、其他场景 60 秒，request() 自带 PRI_DIRTY），不抢跑；
            // 若此刻本来就有 300ms 的退出窗口任务，只做合并、绝不推后它的执行时间。
            SyncScheduler.request(owner(), "fg");
        }
    }

    /** 进后台：有改动就在合并窗口后补一次（不保证完成）；纯轮询任务由调度器取消。 */
    public static void onBackground() {
        if (AccountContext.isGuest() || !isDirty()) {
            return;
        }
        SyncScheduler.requestWithin(owner(), SyncScheduler.REASON_BG, SETTLE_MS);
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

    /**
     * ★ v4.2.5：退出 App 前的同步 —— 立即执行，但<b>不强制上传</b>（{@code force=false}）。
     *
     * <p>退出时该跑就跑，但「跑一次」不等于「必须写一次云端」：内容与云端一致时跳过 PUT
     * 才是对的（见 {@code SyncEngine} 的 same 分支）。真正的兜底不是强推，而是
     * dirty 标记 + 下次启动的 {@code reconcilePushedHlc}：只要本次没传成，下次一定会补。</p>
     *
     * <p>「立即同步」按钮仍走 {@link #syncNow(Callback)}（force=true）—— 那是用户
     * 明确要求「别管变没变，跑一遍」的语义。</p>
     */
    public static void syncNowQuiet(Callback cb) {
        if (AccountContext.isGuest() || !hasConfig()) {
            sync(true, false, false, cb);
            return;
        }
        SyncScheduler.requestNow(owner(), "exit", false, cb);
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
            // v4.1.2：不要「立即回调失败」——退出 App 时 syncNow 会撞上在途同步，
            // 立即回调会让 HomeActivity 误以为同步结束而 killProcess，把在途同步杀在半路。
            // 把回调挂到当前同步收尾时统一回调（见 drainWaiters）。
            synchronized (WAITERS) {
                if (cb != null) {
                    WAITERS.add(cb);
                }
            }
            SyncScheduler.skipped(owner, "busy-wait");
            return;
        }
        if (!manual) {
            // ★ v4.2.2（B）：首轮轻量同步的「补跑」跳过 min-gap —— 它紧跟首轮 0ms 排队，
            //   若被 30s 间隔挡住，被推迟的 PROGRESS/COLLECT 就要再等半分钟才上传。
            //   「仅 Wi-Fi」门禁不跳过（见下方），补跑同样不能烧移动流量。
            boolean continuation;
            synchronized (LOCK) {
                continuation = continuePending;
                continuePending = false;
            }
            long last = lastSyncAt();
            // r18：切后台的补传走更短的最小间隔（见 MIN_GAP_BACKGROUND_MS）。
            long gap = background ? MIN_GAP_BACKGROUND_MS : MIN_GAP_MS;
            // r171：lastAt 由 SyncEngine.finish 写的是 TrustedClock 物理时间（含服务器 Date 偏移），
            // 这里必须用同一条时间轴比较；否则设备时钟偏慢时 elapsed 为负，会把 min-gap
            // 误算成“还要等一个时钟偏差那么久”（真机日志曾出现 retryIn≈79 分钟，同步被卡死）。
            long elapsed = com.github.tvbox.osc.sync.TrustedClock.nowPhysical() - last;
            if (elapsed < 0) {
                // 首次学习偏移 / 时钟回拨：保守视为“刚同步过”，只等一个正常 gap，绝不放大。
                elapsed = 0;
            }
            if (continuation) {
                LOG.i("[sync] continuation round, skip min-gap elapsed=" + elapsed + "ms");
            } else if (elapsed < gap) {
                syncing = false;
                // ★ v99-r39：按「真实剩余时间」重排，而不是套用 retry-dirty 的固定 60 秒档。
                //   距上次同步只差几秒时却被推到 60 秒后，退出播放那一刻的进度就传不上去了。
                //   留 300ms 余量：避免算出来 0 导致同一毫秒内反复重试。
                long wait = Math.max(gap - elapsed, 300L);
                // ★ v4.2.8：把 background 一起传下去 —— 否则重排出来的 retry 任务会退回
                //   30 秒档，退出播放的补传从「再等 1.6 秒」变成「再等 20 秒」
                //   （真机日志 2026-11-07 20:38:31.330 / 20:38:32.920 / 20:38:53.137）。
                SyncScheduler.skipped(owner, background ? "min-gap-bg" : "min-gap", wait, background);
                return;
            }
            if (Hawk.get(HawkConfig.SYNC_WIFI_ONLY, false) && !isWifi()) {
                syncing = false;
                // ★ v4.2.2（B）：补跑被门禁挡下时把标记还回去，避免「首轮推迟的 scope」
                //   在这一次被吃掉标记后，又要按普通 30s 间隔再等一轮。
                if (continuation) {
                    synchronized (LOCK) {
                        continuePending = true;
                    }
                }
                SyncScheduler.skipped(owner, "wifi-only");
                return;
            }
            // v98 P2：新引擎自己在内部判断熔断（0 请求早退 + 给"已暂停"文案）；
            //         只有回退到旧 doSync 时才需要在这里挡一道。
            if (!Hawk.get(HawkConfig.SYNC_ENGINE_V2, true) && SyncState.load(owner).inBackoff()) {
                syncing = false;
                if (continuation) {
                    synchronized (LOCK) {
                        continuePending = true;
                    }
                }
                SyncScheduler.skipped(owner, "backoff");
                return;
            }
        }
        final long t0 = System.currentTimeMillis();
        // ★ v4.2.2（B）：首轮轻量同步标记。只有「冷启动那一次」为真，用完立即复位，
        //   保证紧随其后的补跑（fg / retry-pull / dirty）走完整 4 个 scope。
        final boolean lightFirstRound;
        synchronized (LOCK) {
            lightFirstRound = firstRoundPending;
            firstRoundPending = false;
        }
        if (lightFirstRound) {
            LOG.i("[sync] light first round (SETTINGS+HISTORY only)");
        }
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
                        SyncEngine.Outcome r = SyncEngine.runSafe(owner, key, gen, force, lightFirstRound);
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
                drainWaiters(ok, msg);
                if (aborted) {
                    // 账号切换作废：不算一次有效同步，不升降档
                    SyncScheduler.skipped(owner, "aborted");
                } else {
                    SyncScheduler.onSyncResult(owner, ok && (up > 0 || !notModified), up > 0);
                }
                // ★ v4.2.2（B）：首轮轻量同步收尾后，立刻排一轮补跑把 PROGRESS/COLLECT 处理掉。
                //   放在 onSyncResult 之后：补跑是独立的一次调度，不与首轮合并。
                //   只在首轮「成功」时补跑：失败时交给调度器的退避/补排，避免失败即立刻重试。
                if (lightFirstRound && ok && !aborted) {
                    continuePending = true;
                    LOG.i("[sync] light first round done, schedule continuation");
                    SyncScheduler.requestWithin(owner, "light-cont", 0L);
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

    /** 当前同步收尾后，统一回调所有因「忙」而挂起的退出请求。 */
    private static void drainWaiters(final boolean ok, final String msg) {
        ArrayList<Callback> waiters;
        synchronized (WAITERS) {
            if (WAITERS.isEmpty()) {
                return;
            }
            waiters = new ArrayList<>(WAITERS);
            WAITERS.clear();
        }
        for (Callback waiter : waiters) {
            post(waiter, ok, msg);
        }
    }

    /**
     * v4.1.2：退出 App 前把最新 SyncState（dirty/etag/pushedHlc/meta）与设置快照同步落盘，
     * 避免「刚 markDirty 就被 killProcess」导致本次修改下次启动被认为是干净的。
     */
    public static void flushState() {
        String owner = AccountContext.ownerKey();
        if (owner == null || owner.isEmpty()) {
            return;
        }
        try {
            SyncState.load(owner).save();
        } catch (Throwable t) {
            LOG.e("[sync] flush SyncState failed", t);
        }
        try {
            SettingsProfile.save(owner);
        } catch (Throwable t) {
            LOG.e("[sync] flush SettingsProfile failed", t);
        }
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
            // ★ 协议 v4 / P0-3：上传全部本机行，不再按本机档位预截。
            //   裁剪只在合并后按"账号级 HISTORY_NUM"统一执行；否则本机多出的行永远传不上去，
            //   且新设备默认档位(15)会把云端(50)首轮裁掉。
            List<VodRecord> rows = dao.getAll(Integer.MAX_VALUE);
            if (rows != null) {
                for (VodRecord r : rows) {
                    SyncPayload.RecordItem it = new SyncPayload.RecordItem();
                    it.sourceKey = r.sourceKey;
                    it.vodId = r.vodId;
                    // S2：同步包只带 10 个必需字段（本地库仍是完整 VodInfo）
                    it.dataJson = HistorySyncJson.slim(r.dataJson);
                    it.updateTime = r.updateTime;
                    it.hlcPhysical = r.hlcPhysical > 0 ? r.hlcPhysical : r.updateTime;
                    it.hlcCounter = r.hlcCounter;
                    it.dev = notEmpty(r.dev) ? r.dev : deviceId;
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
                    it.hlcPhysical = pr.hlcPhysical > 0 ? pr.hlcPhysical : pr.updateTime;
                    it.hlcCounter = pr.hlcCounter;
                    it.dev = notEmpty(pr.dev) ? pr.dev : deviceId;
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
                    it.hlcPhysical = c.hlcPhysical > 0 ? c.hlcPhysical : c.updateTime;
                    it.hlcCounter = c.hlcCounter;
                    it.dev = notEmpty(c.dev) ? c.dev : deviceId;
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
                    sv.hlcPhysical = 0;
                    sv.hlcCounter = 0;
                }
            }
            p.setSettingsMap(settingMap);
        }
        SyncTombstones.fill(p);
        // ★ 协议 v4：只带「本次启用 scope」的墓碑。否则 history 包里混进 progress/collect
        //   墓碑 → contentEquals 永远不相等 → 每轮重复整包上传（白烧配额）。
        if (!scopes.history) {
            p.deleted.records.clear();
        }
        if (!scopes.progress) {
            p.deleted.progress.clear();
        }
        if (!scopes.collect) {
            p.deleted.collects.clear();
        }
        if (!scopes.settings) {
            p.deleted.settings.clear();
        }
        return p;
    }

    /**
     * v78 / v4.2.3：比较两份包的内容是否一致。
     *
     * <p>只用于"没必要上传"的判定：误判为不同最多多传一次，不会丢数据；
     * 但「误判为相同」会漏传，所以这里只做<b>与内容无关</b>的归一化：</p>
     * <ul>
     *   <li>顶层 {@code updatedAt / dev / hlcMax* / scope} 是本机生成的元数据，置零置空；</li>
     *   <li>各列表按 {@link SyncMerge#canonicalize} 的规范顺序排序 —— <b>在副本上排</b>，
     *       不改原对象。这样「内容相同、只是数组顺序不同」能正确判等
     *       （合并输出的顺序来自 HashMap 迭代，与云端文件里的数组顺序天然不同）。</li>
     * </ul>
     *
     * <p>★ 逐条的 {@code hlcPhysical / hlcCounter / updateTime / dev} 一律参与比较。
     * 它们是合并裁决与墓碑压制的输入，不是可以忽略的元数据：把逐条 HLC 也归一化，
     * 会让「本机 touch 过但正片内容没变」的记录跳过上传，而 {@code SyncEngine} 的
     * same 分支同样会 {@code bumpPushedHlc}，把没上传的水位记成已推送 ——
     * 那条 HLC 就永远上不了云，其它设备的「最近观看」顺序拿不到这次更新，
     * 还可能被更低的 HLC 反超。</p>
     */
    static boolean contentEquals(SyncPayload a, SyncPayload b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            String ja = GSON.toJson(canonicalForCompare(a));
            String jb = GSON.toJson(canonicalForCompare(b));
            return ja.equals(jb);
        } catch (Throwable t) {
            // ★ v4.2.3-diag：这里以前静默吞异常。一旦 canonicalize / GSON 在真实数据上抛错，
            //   每轮都会判「不同」→ 整包重传，而日志里一个字都没有。现在把异常打出来。
            LOG.e("[Sync] contentEquals failed (treated as different)", t);
            return false;
        }
    }

    /**
     * ★ v4.3.0（r180-A）：算出「与 contentEquals 完全同一套规范化」的内容指纹（SHA-256 hex）。
     *
     * <p>用途：{@code SyncEngine} 在「云端 ETag 未变、不需要下载」时，用本机包的指纹与上次
     * 记录下来的远端指纹比对 —— 相等就说明云端内容与本机规范化结果一致，可零请求跳过上传。</p>
     *
     * <p>口径必须与 {@link #contentEquals} 一致（同一个 {@link #canonicalForCompare}），
     * 否则「指纹相等」推不出「内容相等」。失败返回 null（调用方按「未知」处理 ⇒ 老实 GET）。</p>
     */
    static String payloadHash(SyncPayload p) {
        if (p == null) {
            return null;
        }
        try {
            String json = GSON.toJson(canonicalForCompare(p));
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(json.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            LOG.e("[Sync] payloadHash failed (treated as unknown)", t);
            return null;
        }
    }

    // ================= ★ v4.3.3-diag（r181）：same-miss / 合并 差异窗口 =================
    //
    // 背景（1548 双设备日志）：
    //   ① 两次「recs 相同、hlc 也完全相同，却 content=false」（手机 15:50:01 HISTORY recs=15/15、
    //      模拟器 15:54:50 HISTORY recs=14/14）—— 旧日志只报「判了不同」，不报「哪里不同」，
    //      导致每轮白跑一次 GET + PUT（各约 1.7KB），却始终定位不到差异字段，只能靠猜。
    //   ② HISTORY 条数从 15 掉到 14（全程 pruned=false，排除 pruneHistory）—— 同样只看到结果，
    //      看不到是哪条 key 被谁丢的。
    //
    // 这里补一行结构化差异，**只在 same-miss 且 content=false 时调用**，正常轮次零开销：
    //   rec[+0 -1 ~0] -abc|12345(nt) prog[=] col[=]
    //     +   只在本端有（本轮会带上云）
    //     -   只在云端有（本轮被合并/墓碑/裁剪丢弃）；(t)=命中墓碑，(nt)=无墓碑（多为上限裁剪）
    //     ~   两边都有但内容不同，后面附字段级差异与 dataJson 的首个分歧点
    // 不调用 GSON.toJson，只做字段比较与字符串切片，开销与记录数线性相关。

    /** 一次集合比较的计数。 */
    private static final class DiffCount {
        int add;
        int del;
        int chg;
    }

    /**
     * 两个包（同一 scope）的逐条差异摘要，见上方格式说明。异常时返回 {@code diff-failed:...}，
     * 绝不让诊断代码影响同步主流程。
     */
    static String diffBrief(SyncPayload a, SyncPayload b) {
        if (a == null || b == null) {
            return "null";
        }
        try {
            StringBuilder out = new StringBuilder(192);
            DiffCount c = new DiffCount();
            String ex = diffRecords(a, b, c);
            out.append("rec").append(summary(c)).append(ex);
            c = new DiffCount();
            ex = diffProgress(a, b, c);
            out.append(" prog").append(summary(c)).append(ex);
            c = new DiffCount();
            ex = diffCollects(a, b, c);
            out.append(" col").append(summary(c)).append(ex);
            return out.toString();
        } catch (Throwable t) {
            return "diff-failed:" + t;
        }
    }

    private static String summary(DiffCount c) {
        if (c.add == 0 && c.del == 0 && c.chg == 0) {
            return "[=]";
        }
        return "[+" + c.add + " -" + c.del + " ~" + c.chg + "]";
    }

    private static String diffRecords(SyncPayload a, SyncPayload b, DiffCount c) {
        Map<String, SyncPayload.RecordItem> ma = new LinkedHashMap<>();
        if (a.records != null) {
            for (SyncPayload.RecordItem it : a.records) {
                if (it != null) {
                    ma.put(SyncMerge.recordKey(it.sourceKey, it.vodId), it);
                }
            }
        }
        Map<String, SyncPayload.RecordItem> mb = new LinkedHashMap<>();
        if (b.records != null) {
            for (SyncPayload.RecordItem it : b.records) {
                if (it != null) {
                    mb.put(SyncMerge.recordKey(it.sourceKey, it.vodId), it);
                }
            }
        }
        StringBuilder ex = new StringBuilder(128);
        int shown = 0;
        for (Map.Entry<String, SyncPayload.RecordItem> e : ma.entrySet()) {
            SyncPayload.RecordItem other = mb.get(e.getKey());
            if (other == null) {
                c.add++;
                if (shown < 2) {
                    ex.append(" +").append(e.getKey());
                    shown++;
                }
            } else {
                String fd = recordFields(e.getValue(), other);
                if (fd.length() > 0) {
                    c.chg++;
                    if (shown < 2) {
                        ex.append(" ~").append(e.getKey()).append('{').append(fd).append('}');
                        shown++;
                    }
                }
            }
        }
        for (String k : mb.keySet()) {
            if (!ma.containsKey(k)) {
                c.del++;
                if (shown < 2) {
                    ex.append(" -").append(k).append(tombMark(a, k));
                    shown++;
                }
            }
        }
        return ex.toString();
    }

    private static String diffProgress(SyncPayload a, SyncPayload b, DiffCount c) {
        Map<String, SyncPayload.ProgressItem> ma = new LinkedHashMap<>();
        if (a.progress != null) {
            for (SyncPayload.ProgressItem it : a.progress) {
                if (it != null) {
                    ma.put(SyncMerge.progressKey(it.sourceKey, it.vodId, it.flag, it.playIndex, it.epName), it);
                }
            }
        }
        Map<String, SyncPayload.ProgressItem> mb = new LinkedHashMap<>();
        if (b.progress != null) {
            for (SyncPayload.ProgressItem it : b.progress) {
                if (it != null) {
                    mb.put(SyncMerge.progressKey(it.sourceKey, it.vodId, it.flag, it.playIndex, it.epName), it);
                }
            }
        }
        StringBuilder ex = new StringBuilder(128);
        int shown = 0;
        for (Map.Entry<String, SyncPayload.ProgressItem> e : ma.entrySet()) {
            SyncPayload.ProgressItem other = mb.get(e.getKey());
            if (other == null) {
                c.add++;
                if (shown < 2) {
                    ex.append(" +").append(e.getKey());
                    shown++;
                }
            } else {
                String fd = progressFields(e.getValue(), other);
                if (fd.length() > 0) {
                    c.chg++;
                    if (shown < 2) {
                        ex.append(" ~").append(e.getKey()).append('{').append(fd).append('}');
                        shown++;
                    }
                }
            }
        }
        for (String k : mb.keySet()) {
            if (!ma.containsKey(k)) {
                c.del++;
                if (shown < 2) {
                    ex.append(" -").append(k);
                    shown++;
                }
            }
        }
        return ex.toString();
    }

    private static String diffCollects(SyncPayload a, SyncPayload b, DiffCount c) {
        Map<String, SyncPayload.CollectItem> ma = new LinkedHashMap<>();
        if (a.collects != null) {
            for (SyncPayload.CollectItem it : a.collects) {
                if (it != null) {
                    ma.put(SyncMerge.collectKey(it.sourceKey, it.vodId), it);
                }
            }
        }
        Map<String, SyncPayload.CollectItem> mb = new LinkedHashMap<>();
        if (b.collects != null) {
            for (SyncPayload.CollectItem it : b.collects) {
                if (it != null) {
                    mb.put(SyncMerge.collectKey(it.sourceKey, it.vodId), it);
                }
            }
        }
        StringBuilder ex = new StringBuilder(96);
        int shown = 0;
        for (Map.Entry<String, SyncPayload.CollectItem> e : ma.entrySet()) {
            SyncPayload.CollectItem other = mb.get(e.getKey());
            if (other == null) {
                c.add++;
                if (shown < 2) {
                    ex.append(" +").append(e.getKey());
                    shown++;
                }
            } else {
                String fd = collectFields(e.getValue(), other);
                if (fd.length() > 0) {
                    c.chg++;
                    if (shown < 2) {
                        ex.append(" ~").append(e.getKey()).append('{').append(fd).append('}');
                        shown++;
                    }
                }
            }
        }
        for (String k : mb.keySet()) {
            if (!ma.containsKey(k)) {
                c.del++;
                if (shown < 2) {
                    ex.append(" -").append(k).append(tombMarkCollect(a, k));
                    shown++;
                }
            }
        }
        return ex.toString();
    }

    /** 记录墓碑标记：有墓碑 → {@code (t)}；无墓碑 → {@code (nt)}（多半是被上限裁掉）。 */
    private static String tombMark(SyncPayload merged, String key) {
        if (merged == null || merged.deleted == null || merged.deleted.records == null) {
            return "(nt)";
        }
        for (SyncPayload.Tomb t : merged.deleted.records) {
            if (t != null && key.equals(t.key)) {
                return "(t)";
            }
        }
        return "(nt)";
    }

    private static String tombMarkCollect(SyncPayload merged, String key) {
        if (merged == null || merged.deleted == null || merged.deleted.collects == null) {
            return "(nt)";
        }
        for (SyncPayload.Tomb t : merged.deleted.collects) {
            if (t != null && key.equals(t.key)) {
                return "(t)";
            }
        }
        return "(nt)";
    }

    private static String recordFields(SyncPayload.RecordItem x, SyncPayload.RecordItem y) {
        StringBuilder sb = new StringBuilder(96);
        if (!eq(x.sourceKey, y.sourceKey)) {
            sb.append("sourceKey;");
        }
        if (!eq(x.vodId, y.vodId)) {
            sb.append("vodId;");
        }
        if (x.updateTime != y.updateTime) {
            sb.append("updateTime(").append(x.updateTime).append("->").append(y.updateTime).append(");");
        }
        if (!eq(x.dev, y.dev)) {
            sb.append("dev(").append(x.dev).append("->").append(y.dev).append(");");
        }
        if (x.hlcPhysical != y.hlcPhysical) {
            sb.append("hlcP(").append(x.hlcPhysical).append("->").append(y.hlcPhysical).append(");");
        }
        if (x.hlcCounter != y.hlcCounter) {
            sb.append("hlcC(").append(x.hlcCounter).append("->").append(y.hlcCounter).append(");");
        }
        if (!eq(x.dataJson, y.dataJson)) {
            sb.append("dataJson").append(jsonDiff(x.dataJson, y.dataJson)).append(';');
        }
        return sb.toString();
    }

    private static String progressFields(SyncPayload.ProgressItem x, SyncPayload.ProgressItem y) {
        StringBuilder sb = new StringBuilder(96);
        if (!eq(x.sourceKey, y.sourceKey)) {
            sb.append("sourceKey;");
        }
        if (!eq(x.vodId, y.vodId)) {
            sb.append("vodId;");
        }
        if (!eq(x.flag, y.flag)) {
            sb.append("flag;");
        }
        if (x.playIndex != y.playIndex) {
            sb.append("playIndex(").append(x.playIndex).append("->").append(y.playIndex).append(");");
        }
        if (!eq(x.epName, y.epName)) {
            sb.append("epName;");
        }
        if (x.position != y.position) {
            sb.append("position(").append(x.position).append("->").append(y.position).append(");");
        }
        if (x.updateTime != y.updateTime) {
            sb.append("updateTime(").append(x.updateTime).append("->").append(y.updateTime).append(");");
        }
        if (!eq(x.dev, y.dev)) {
            sb.append("dev(").append(x.dev).append("->").append(y.dev).append(");");
        }
        if (x.hlcPhysical != y.hlcPhysical) {
            sb.append("hlcP(").append(x.hlcPhysical).append("->").append(y.hlcPhysical).append(");");
        }
        if (x.hlcCounter != y.hlcCounter) {
            sb.append("hlcC(").append(x.hlcCounter).append("->").append(y.hlcCounter).append(");");
        }
        return sb.toString();
    }

    private static String collectFields(SyncPayload.CollectItem x, SyncPayload.CollectItem y) {
        StringBuilder sb = new StringBuilder(80);
        if (!eq(x.sourceKey, y.sourceKey)) {
            sb.append("sourceKey;");
        }
        if (!eq(x.vodId, y.vodId)) {
            sb.append("vodId;");
        }
        if (!eq(x.name, y.name)) {
            sb.append("name;");
        }
        if (!eq(x.pic, y.pic)) {
            sb.append("pic;");
        }
        if (x.updateTime != y.updateTime) {
            sb.append("updateTime(").append(x.updateTime).append("->").append(y.updateTime).append(");");
        }
        if (!eq(x.dev, y.dev)) {
            sb.append("dev(").append(x.dev).append("->").append(y.dev).append(");");
        }
        if (x.hlcPhysical != y.hlcPhysical) {
            sb.append("hlcP(").append(x.hlcPhysical).append("->").append(y.hlcPhysical).append(");");
        }
        if (x.hlcCounter != y.hlcCounter) {
            sb.append("hlcC(").append(x.hlcCounter).append("->").append(y.hlcCounter).append(");");
        }
        return sb.toString();
    }

    /** dataJson 的首个分歧点 + 两侧片段（各 ≤ 72 字符），用于定位「内容哪里不一样」。 */
    private static String jsonDiff(String a, String b) {
        int la = a == null ? 0 : a.length();
        int lb = b == null ? 0 : b.length();
        int n = Math.min(la, lb);
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        int from = i > 24 ? i - 24 : 0;
        return "(" + la + "->" + lb + " @" + i
                + " A=" + snip(a, from, i + 48)
                + " B=" + snip(b, from, i + 48) + ")";
    }

    private static String snip(String s, int from, int to) {
        if (s == null) {
            return "null";
        }
        if (from < 0) {
            from = 0;
        }
        if (from > s.length()) {
            from = s.length();
        }
        if (to > s.length()) {
            to = s.length();
        }
        if (to < from) {
            to = from;
        }
        return s.substring(from, to);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * 生成「用于比较」的副本：列表按规范顺序排序 + 顶层元数据归一化，<b>不修改入参</b>。
     *
     * <p>★ v4.2.3：旧实现直接改 {@code a}/{@code b} 的字段再还原，GSON 抛异常（如 OOM）时
     * 还原不到，调用方会拿着 {@code hlcMax=0 / dev=""} 的包去上传 —— 副本写法从根上消除这个风险。</p>
     *
     * <p>列表元素是共享引用（不做深拷贝），只新建容器并按规范顺序重排。</p>
     */
    private static SyncPayload canonicalForCompare(SyncPayload src) {
        SyncPayload p = new SyncPayload();
        p.schema = src.schema;
        p.owner = src.owner;
        p.epoch = src.epoch;
        p.records = src.records == null
                ? new ArrayList<SyncPayload.RecordItem>() : new ArrayList<>(src.records);
        p.progress = src.progress == null
                ? new ArrayList<SyncPayload.ProgressItem>() : new ArrayList<>(src.progress);
        p.collects = src.collects == null
                ? new ArrayList<SyncPayload.CollectItem>() : new ArrayList<>(src.collects);
        p.settings = src.settings == null
                ? new ArrayList<SyncPayload.SettingItem>() : new ArrayList<>(src.settings);
        p.deleted = new SyncPayload.Deleted();
        if (src.deleted != null) {
            p.deleted.records = src.deleted.records == null
                    ? new ArrayList<SyncPayload.Tomb>() : new ArrayList<>(src.deleted.records);
            p.deleted.progress = src.deleted.progress == null
                    ? new ArrayList<SyncPayload.Tomb>() : new ArrayList<>(src.deleted.progress);
            p.deleted.collects = src.deleted.collects == null
                    ? new ArrayList<SyncPayload.Tomb>() : new ArrayList<>(src.deleted.collects);
            p.deleted.settings = src.deleted.settings == null
                    ? new ArrayList<SyncPayload.Tomb>() : new ArrayList<>(src.deleted.settings);
        }
        SyncMerge.canonicalize(p);
        // 顶层归一化：这四项是「本机生成」的元数据，不代表内容差异
        p.updatedAt = 0L;
        p.dev = "";
        p.hlcMaxPhysical = 0L;
        p.hlcMaxCounter = 0;
        p.scope = null;
        return p;
    }

    /**
     * ★ 协议 v4（P0-3）：把云端合并结果裁到「账号级历史上限」。
     *
     * <p>v77 的老实现有两个根因问题：</p>
     * <ol>
     *   <li>用<b>本机当前</b> {@code HISTORY_NUM} 裁「共享云端文件」。新设备首次加入账号时
     *       {@code SettingsProfile.resetDefaults()} 会把档位重置成默认 15，而账号实际是 50；
     *       而 {@link SyncScope} 遍历顺序里 HISTORY 在 SETTINGS 之前 ⇒ 首轮就拿 15 裁掉了云端
     *       的 50 条，之后才学到「其实账号是 50」。裁掉的数据是<b>物理删除</b>，无法恢复。</li>
     *   <li>排序键是墙钟 {@code updateTime}：时钟滞后的设备（模拟器常见）刚写入的新记录会排到
     *       后面被裁掉。</li>
     * </ol>
     *
     * <p>现在的规则：</p>
     * <ul>
     *   <li>只按 <b>HLC</b> 降序裁剪，不再看墙钟；</li>
     *   <li>档位未知（设置尚未同步下来 / 本机处于「只拉不推」的 pendingPull 阶段 / 设置同步被
     *       关闭）时<b>一律不裁</b>——宁可云端多留一轮，也不能拿错的档位物理删除别人数据；</li>
     *   <li><b>不再顺带丢弃进度行</b>：进度与历史解耦（P1-4），历史裁剪不应连带删除播放进度。</li>
     * </ul>
     */
    static void truncateMergedHistory(SyncPayload merged, SyncScopes scopes) {
        if (merged == null || !scopes.history || merged.records == null) {
            return;
        }
        int keep = accountHistoryKeep(merged.owner, scopes);
        if (keep <= 0 || merged.records.size() <= keep) {
            return;
        }
        List<SyncPayload.RecordItem> sorted = new ArrayList<>(merged.records);
        Collections.sort(sorted, new Comparator<SyncPayload.RecordItem>() {
            @Override
            public int compare(SyncPayload.RecordItem a, SyncPayload.RecordItem b) {
                int c = b.hlc().compareTo(a.hlc());
                if (c != 0) {
                    return c;
                }
                return SyncMerge.recordKey(a.sourceKey, a.vodId)
                        .compareTo(SyncMerge.recordKey(b.sourceKey, b.vodId));
            }
        });
        merged.records = new ArrayList<>(sorted.subList(0, keep));
        // ★ v4.3.3-diag（r181）：裁剪是「HISTORY 条数悄悄变少」的候选元凶之一（另一种是墓碑
        //   压制）。以前裁完一声不响，只能看到 15→14 却不知道谁被裁、为什么。只报前 2 个 key。
        int before = sorted.size();
        int n = 0;
        StringBuilder dropped = new StringBuilder(64);
        for (int i = keep; i < before && n < 2; i++, n++) {
            dropped.append(' ').append(SyncMerge.recordKey(sorted.get(i).sourceKey, sorted.get(i).vodId));
        }
        LOG.i("[Sync] truncate HISTORY " + before + "->" + keep + " dropped=" + dropped);
    }

    /**
     * 账号级历史上限。返回 0 表示「档位未知，不要裁」。
     *
     * <p>★ v4.3.5（r183）判定顺序：
     * <ol>
     *   <li><b>账号级档位</b>（{@link SyncState#accountHistoryNum}，来自 sync.meta）——
     *       它与「设置同步」开关无关，所以关掉设置同步的设备也能裁到同一个值；</li>
     *   <li>兜底：本机档位（设置同步开启且已拉到账号设置）。老账号 / meta 尚未发布过时走这里。</li>
     * </ol>
     * 仍返回 0 的两种情况：meta 里还没有档位，且本机设置同步关闭或仍在 pendingPull。</p>
     */
    private static int accountHistoryKeep(String owner, SyncScopes scopes) {
        // ① 账号级档位：任何设备都读得到，不受 scope 开关影响。
        try {
            int metaKeep = SyncState.load(owner).accountHistoryNum;
            if (metaKeep > 0) {
                return metaKeep;
            }
        } catch (Throwable ignore) {
            // 读不到就退回本机判断，绝不因异常而不裁/误裁
        }
        // ② 兜底：本机档位。
        // 注意：逐 scope 同步时传入的是 SyncScopes.only(sc)（只启用当前 scope），
        // 不能用它的 settings 标志判断「设置同步是否开启」，否则永远返回 0、历史永不裁剪。
        // 这里必须读全局开关。
        SyncScopes global = SyncScopes.load();
        if (global == null || !global.settings) {
            return 0;
        }
        try {
            if (SettingsProfile.isPendingPull(owner)) {
                return 0;
            }
        } catch (Throwable ignore) {
        }
        int idx = Hawk.get(HawkConfig.HISTORY_NUM, 0);
        int keep = HistoryHelper.getHisNum(idx);
        return keep > 0 ? keep : 0;
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

    static boolean applyLocal(SyncPayload merged, SyncScopes scopes) {
        return applyLocal(merged, scopes, false);
    }

    /**
     * @param reset true 表示本轮是「云端 epoch 变化后的整库重置」：此时事务内**不能**
     *              再用本机旧表重新 merge（否则旧数据会被重新并回、重置失效），
     *              必须以空本地视图落库。
     */
    static boolean applyLocal(SyncPayload merged, SyncScopes scopes, boolean reset) {
        final boolean[] changed = {false};
        SyncTombstones.suppress(true);
        try {
            AppDataManager.get();
            final SyncScopes sc = scopes;
            final boolean doReset = reset;
            // ★ 协议 v4 P0-4：整个"再合并 + 落库"放进同一个 Room 事务，
            //   并在事务内用当前本机数据重新 merge 一次，避免同步窗口期（buildLocal 之后、
            //   落库之前）用户在 UI 新写入的进度/历史被旧快照的 deleteAll 抹掉。
            AppDataManager.get().runInTransaction(() -> {
                String deviceId = deviceId();
                long now = com.github.tvbox.osc.sync.TrustedClock.nowPhysical();
                // baseline 只用于「本次是否改动了本机」的对比；reset 时真正落库用空视图。
                SyncPayload baseline = buildLocal(merged.owner, deviceId, sc, now);
                SyncPayload fresh = doReset
                        ? emptyLocal(merged.owner, deviceId, now)
                        : baseline;
                SyncPayload re = doReset
                        ? merged
                        : SyncMerge.merge(fresh, merged, deviceId, now);
                // 事务内再合并可能让并集重新超过上限（本机 baseline + 云端），
                // 因此在落库前按同一策略再裁一次，保证「云端/本机/上传包」三处口径一致。
                if (!doReset) {
                    truncateMergedHistory(re, sc);
                    truncateMergedProgress(re, sc);
                }

                boolean ch = false;
                if (sc.history) {
                    ch = !recordSig(baseline.records).equals(recordSig(re.records));
                    writeHistory(re.records);
                }
                if (sc.progress) {
                    if (!progressSig(baseline.progress).equals(progressSig(re.progress))) ch = true;
                    writeProgress(re.progress);
                }
                if (sc.collect) {
                    if (!collectSig(baseline.collects).equals(collectSig(re.collects))) ch = true;
                    writeCollect(re.collects);
                }
                changed[0] = ch;

                // 把再合并结果写回 merged，让随后上传的包与本地一致
                merged.records = re.records;
                merged.progress = re.progress;
                merged.collects = re.collects;
                merged.settings = re.settings;
                merged.deleted = re.deleted;
                merged.epoch = re.epoch;
                merged.updatedAt = re.updatedAt;
                merged.hlcMaxPhysical = re.hlcMaxPhysical;
                merged.hlcMaxCounter = re.hlcMaxCounter;
            });
            // Hawk 写在事务之后：Room 事务回滚不了 Hawk，故此处必须幂等（重复执行结果一致）
            if (scopes.settings) {
                // v98 P3：写 Hawk + 判定「需要哪些界面动作」交给 ConfigCenter。
                ConfigCenter.apply(merged.settingsMap());
                ConfigCenter.flush();
            }
        } finally {
            SyncTombstones.suppress(false);
        }
        return changed[0];
    }

    /** 历史落库：事务内 deleteAll 后重建（外层 fresh merge 保证不丢窗口期 UI 写入）。 */
    private static void writeHistory(List<SyncPayload.RecordItem> items) {
        VodRecordDao dao = AppDataManager.get().getVodRecordDao();
        String self = deviceId();
        Map<String, String> full = new java.util.HashMap<>();
        List<VodRecord> olds = dao.getAll(Integer.MAX_VALUE);
        if (olds != null) {
            for (VodRecord r : olds) {
                if (r != null && r.dataJson != null && r.dataJson.length() > 0) {
                    full.put(SyncMerge.recordKey(r.sourceKey, r.vodId), r.dataJson);
                }
            }
        }
        dao.deleteAll();
        if (items == null) {
            return;
        }
        for (SyncPayload.RecordItem it : items) {
            VodRecord r = new VodRecord();
            r.sourceKey = it.sourceKey;
            r.vodId = it.vodId;
            String f = full.get(SyncMerge.recordKey(it.sourceKey, it.vodId));
            // P0-8：远端 winner 时不能直接覆盖本机完整 JSON；把 slim 字段 overlay 到本机全文上
            r.dataJson = f != null ? HistorySyncJson.overlay(f, it.dataJson) : it.dataJson;
            r.updateTime = it.updateTime;
            r.hlcPhysical = it.hlcPhysical > 0 ? it.hlcPhysical : it.updateTime;
            r.hlcCounter = it.hlcCounter;
            r.dev = notEmpty(it.dev) ? it.dev : self;
            dao.insert(r);
        }
    }

    private static void writeProgress(List<SyncPayload.ProgressItem> items) {
        PlayProgressDao dao = AppDataManager.get().getPlayProgressDao();
        String self = deviceId();
        dao.deleteAll();
        if (items == null) {
            return;
        }
        for (SyncPayload.ProgressItem it : items) {
            PlayProgress pr = new PlayProgress();
            pr.sourceKey = it.sourceKey == null ? "" : it.sourceKey;
            pr.vodId = it.vodId == null ? "" : it.vodId;
            pr.flag = it.flag == null ? "" : it.flag;
            pr.playIndex = it.playIndex;
            pr.epName = it.epName == null ? "" : it.epName;
            pr.position = it.position;
            pr.updateTime = it.updateTime;
            pr.hlcPhysical = it.hlcPhysical > 0 ? it.hlcPhysical : it.updateTime;
            pr.hlcCounter = it.hlcCounter;
            pr.dev = notEmpty(it.dev) ? it.dev : self;
            dao.save(pr);
        }
    }

    private static void writeCollect(List<SyncPayload.CollectItem> items) {
        VodCollectDao dao = AppDataManager.get().getVodCollectDao();
        String self = deviceId();
        dao.deleteAll();
        if (items == null) {
            return;
        }
        for (SyncPayload.CollectItem it : items) {
            VodCollect c = new VodCollect();
            c.sourceKey = it.sourceKey;
            c.vodId = it.vodId;
            c.name = it.name;
            c.pic = it.pic;
            c.updateTime = it.updateTime;
            c.hlcPhysical = it.hlcPhysical > 0 ? it.hlcPhysical : it.updateTime;
            c.hlcCounter = it.hlcCounter;
            c.dev = notEmpty(it.dev) ? it.dev : self;
            dao.insert(c);
        }
    }

    private static Map<String, String> recordSig(List<SyncPayload.RecordItem> list) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        if (list != null) {
            for (SyncPayload.RecordItem it : list) {
                if (it != null) {
                    map.put(SyncMerge.recordKey(it.sourceKey, it.vodId), it.hlc().toString() + "#" + it.dataJson);
                }
            }
        }
        return map;
    }

    private static Map<String, String> progressSig(List<SyncPayload.ProgressItem> list) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        if (list != null) {
            for (SyncPayload.ProgressItem it : list) {
                if (it != null) {
                    map.put(SyncMerge.progressKey(it.sourceKey, it.vodId, it.flag, it.playIndex, it.epName),
                            it.hlc() + "@" + it.position);
                }
            }
        }
        return map;
    }

    private static Map<String, String> collectSig(List<SyncPayload.CollectItem> list) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        if (list != null) {
            for (SyncPayload.CollectItem it : list) {
                if (it != null) {
                    map.put(SyncMerge.recordKey(it.sourceKey, it.vodId), it.hlc().toString());
                }
            }
        }
        return map;
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

    private static boolean notEmpty(String s) {
        return s != null && s.length() > 0;
    }

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
