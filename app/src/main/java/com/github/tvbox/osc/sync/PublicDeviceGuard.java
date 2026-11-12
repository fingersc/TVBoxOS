package com.github.tvbox.osc.sync;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * r216：公共设备模式（「公共设备」开关）。
 *
 * <p><b>要解决的问题</b>：电视是共用的，一个人看完忘了退出登录，下一个人的观看历史与播放进度
 * 就记进了前一个人的账号，两个人的记录互相污染。</p>
 *
 * <p><b>做法</b>：开关开启后，<b>下一次冷启动</b>时若上次留下的是登录态，就先把该账号
 * 未上传的改动同步完，再把账号回退到游客。退出时不回退 —— 这是刻意取舍：退出路径上
 * 已经有"退出前同步"在跑（{@code HomeActivity.exitWithSync}），再叠一段"切库 + 落盘"
 * 只会跟同步抢那 2.5 秒预算，反而更容易让同步半途而废。</p>
 *
 * <p><b>为什么冷启动能覆盖"忘退出"</b>：电视上的 App 极少被正常退出，绝大多数离场是
 * 按 Home、待机、被系统回收或直接断电 —— 这些最终都表现为"下一次冷启动"。所以把回退
 * 放在冷启动，覆盖面比放在退出路径更大，且不与同步争抢。</p>
 *
 * <p><b>代价（已知，用户已拍板）</b>：进程没被杀、只是从最近任务切回来时，仍是上一个账号。</p>
 *
 * <p><b>线程约定</b>：判定在 {@code App.onCreate}（主线程）同步完成；真正的工作
 * （保存设置快照、跑那一轮同步）都在后台线程；只有"切库"这一步回到主线程。
 * 这样冷启动主线程不会被 Room 打开或 JSON 序列化拖住。</p>
 *
 * <p>本类的所有入口都保证不抛异常：任何问题只打日志，绝不影响启动与首屏。</p>
 */
public final class PublicDeviceGuard {

    private static final String TAG = "[PublicDevice]";

    /**
     * 预回退同步的硬上限。
     *
     * <p>冷启动时这一轮同步是<b>与首屏并行</b>跑的（配置拉取/解析、View 膨胀同时在走），
     * 所以通常不会真的等满；上限只是防止弱网下把首屏永久卡住。到点就先作废在途同步
     * 再切库，剩下的脏数据留在本地，该账号下次登录时补传。</p>
     */
    private static final long BOOT_SYNC_BUDGET_MS = 5000L;

    /** 空转探测：{@code sync()} 有几个"直接返回、不回调"的分支（最小间隔 / 仅 Wi-Fi）。 */
    private static final long NO_SYNC_PROBE_MS = 1200L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 回退前后的后台工作线程（保存设置快照等）。 */
    private static final ExecutorService WORK =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "tvbox-public-device");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final Object LOCK = new Object();
    /** 冷启动判定是否已完成（{@link #awaitBootCheck} 据此决定要不要挂起首屏）。 */
    private static volatile boolean checkFinished;
    /** 本次冷启动是否需要回退。 */
    private static volatile boolean rollbackPending;
    /** 回退前是否必须先跑一轮同步（有未上传改动且配了网盘）。 */
    private static volatile boolean needSyncFirst;
    /** 回退流程是否已经在跑（避免被重复发起）。 */
    private static volatile boolean rollbackStarted;
    /** 在等首屏的界面回调（回退完成后一起放行）。 */
    private static final java.util.List<Runnable> WAITERS = new java.util.ArrayList<>();

    private PublicDeviceGuard() {
    }

    // ------------------------------------------------------------------ 开关

    /** 开关是否开启（默认关）。 */
    public static boolean isEnabled() {
        try {
            return Hawk.get(HawkConfig.PUBLIC_DEVICE, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 写入「本次冷启动豁免回退」的一次性标记。
     *
     * <p>给设置页「清空缓存」触发的自重启用：那也是一次冷启动，但它不是"用户离开电视"，
     * 不该把账号踢掉。标记只消费一次。</p>
     */
    public static void markKeepOnce() {
        try {
            Hawk.put(HawkConfig.PUBLIC_DEVICE_KEEP_ONCE, true);
            LOG.i(TAG + " keep-once marked");
        } catch (Throwable ignored) {
        }
    }

    /** 消费一次性豁免标记；返回 true 表示本次冷启动应跳过回退。 */
    private static boolean consumeKeepOnce() {
        try {
            if (!Hawk.get(HawkConfig.PUBLIC_DEVICE_KEEP_ONCE, false)) {
                return false;
            }
            Hawk.delete(HawkConfig.PUBLIC_DEVICE_KEEP_ONCE);
            LOG.i(TAG + " keep-once consumed, skip rollback");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 冷启动判定

    /**
     * 进程启动时调用一次（{@code App.onCreate}，在 {@code HistorySyncManager.start()} 之后）。
     *
     * <p>此时 {@link AccountContext} 已经恢复出上次的登录态、数据库也已切到该账号，
     * 正好是判定"要不要回退"的时机。判定只做三件廉价的事（Hawk 读 + 一次脏标记判断），
     * 真正的活在首屏门控里由后台线程做。</p>
     */
    public static void onProcessStart() {
        try {
            synchronized (LOCK) {
                if (checkFinished) {
                    return;
                }
            }
            // 一次性豁免先消费：开关关着时也要清掉，否则它会一直躺着，
            // 等哪天用户重新打开开关时把"下一次冷启动"白白吃掉。
            boolean keepOnce = consumeKeepOnce();
            if (!isEnabled()) {
                finish(false, false);
                return;
            }
            if (AccountContext.isGuest()) {
                // 上次结束时已经是游客（或从未登录），没什么可回退的
                finish(false, false);
                return;
            }
            if (keepOnce) {
                finish(false, false);
                return;
            }
            boolean needSync = HistorySyncManager.needsSyncBeforeExit();
            if (needSync) {
                // 有未上传改动且配了网盘 ⇒ 先同步再回退。
                // ★ 必须在这里（而不是等门控）清掉「首轮轻量」标记：start() 已经排了
                //   一个 +1s 的 boot 任务，若它先跑起来就是"只跑 SETTINGS+HISTORY"的轻量轮，
                //   PROGRESS/COLLECT 会留在一个即将被解绑的账号上。
                HistorySyncManager.forceFullRoundNextSync();
            }
            LOG.i(TAG + " pending rollback, needSyncFirst=" + needSync);
            finish(true, needSync);
        } catch (Throwable t) {
            LOG.e(TAG + " onProcessStart failed", t);
            finish(false, false);
        }
    }

    private static void finish(boolean pending, boolean needSync) {
        synchronized (LOCK) {
            rollbackPending = pending;
            needSyncFirst = needSync;
            checkFinished = true;
        }
    }

    // ------------------------------------------------------------------ 首屏门控

    /**
     * 首屏门控（主线程，{@code HomeActivity.initData()} 开头调用）。
     *
     * @return {@code true} 表示"回退还没做完，首屏先别加载，我会在完成后回调 {@code done}"；
     *         {@code false} 表示"现在就可以加载首屏"（绝大多数情况走这条，零延迟）。
     *
     * <p>★ 支持多次登记：回退进行中 Activity 被重建（配置变更 / 资源回收）时，新实例也会
     * 挂进来，回退完成后一并放行 —— 否则新实例会在"仍是旧账号设置"的状态下加载首屏。</p>
     */
    public static boolean awaitBootCheck(final Runnable done) {
        synchronized (LOCK) {
            if (!rollbackPending) {
                return false;
            }
            if (done != null) {
                WAITERS.add(done);
            }
            if (rollbackStarted) {
                return true;   // 已经在跑，登记后一起等
            }
            rollbackStarted = true;
        }
        runRollbackThen();
        return true;
    }

    /** 回退完成后放行所有在等首屏的界面。 */
    private static void releaseWaiters() {
        java.util.List<Runnable> waiters;
        synchronized (LOCK) {
            rollbackPending = false;
            waiters = new java.util.ArrayList<>(WAITERS);
            WAITERS.clear();
        }
        for (Runnable r : waiters) {
            try {
                r.run();
            } catch (Throwable t) {
                LOG.e(TAG + " release waiter failed", t);
            }
        }
    }

    /**
     * 「先同步、再回退」的编排。
     *
     * <p>顺序：〔可选〕跑一轮完整同步 → 后台线程保存该账号的设置快照 → 主线程切库回游客
     * → 放行首屏。任何一步失败/超时都继续往下走：<b>回退是硬目标，同步是尽力而为</b>。</p>
     */
    private static void runRollbackThen() {
        final boolean[] settled = {false};
        final Runnable proceed = new Runnable() {
            @Override
            public void run() {
                synchronized (settled) {
                    if (settled[0]) {
                        return;
                    }
                    settled[0] = true;
                }
                // 保存设置快照要在后台做：SettingsProfile.save 会经过 HlcClock.next()，
                // 而后者首次调用会打开 Room 并与 DB 里的最大 HLC 对账 —— 那正是 P2 要从
                // 冷启动主线程上挪走的东西。
                try {
                    WORK.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                AccountSwitcher.saveProfileBeforeLogout();
                            } catch (Throwable t) {
                                LOG.e(TAG + " save profile failed", t);
                            }
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        // 快照刚在后台线程存过 ⇒ 这里不要再存一次
                                        //（SettingsProfile.save 会经 HlcClock 打开 Room，
                                        //  放在冷启动主线程上正是 P2 要避免的）
                                        AccountSwitcher.logoutToGuestNowSkipSave();
                                    } catch (Throwable t) {
                                        LOG.e(TAG + " rollback failed", t);
                                    }
                                    releaseWaiters();
                                }
                            });
                        }
                    });
                } catch (Throwable t) {
                    // 线程池都不可用（几乎不可能）：退回主线程直接做完，保证不卡首屏
                    LOG.e(TAG + " work rejected, do it inline", t);
                    try {
                        AccountSwitcher.logoutToGuestNow();
                    } catch (Throwable ignored) {
                    }
                    releaseWaiters();
                }
            }
        };
        // 硬上限：同步没在预算内结束也必须回退，绝不把首屏永久挂起
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (settled) {
                    if (settled[0]) {
                        return;
                    }
                }
                LOG.i(TAG + " sync budget exceeded, rollback anyway");
                proceed.run();
            }
        }, BOOT_SYNC_BUDGET_MS);

        if (!needSyncFirst) {
            // 没有未上传改动 / 没配网盘 ⇒ 不等任何网络（零请求）
            LOG.i(TAG + " nothing to sync, rollback now (no network)");
            proceed.run();
            return;
        }

        // 空转探测：sync() 可能因为"最小间隔未到 / 仅 Wi-Fi"直接返回且不回调。
        // 那时等满 5 秒毫无意义 —— 1.2 秒后确认没有任何同步在跑就立刻回退。
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (settled) {
                    if (settled[0]) {
                        return;
                    }
                }
                if (HistorySyncManager.isSyncing()) {
                    return;   // 真的在跑，交给回调或预算
                }
                LOG.i(TAG + " no sync in flight (gated), rollback now");
                proceed.run();
            }
        }, NO_SYNC_PROBE_MS);

        try {
            // 再清一次（幂等）：确保紧接着的这一轮是完整轮。
            HistorySyncManager.forceFullRoundNextSync();
            HistorySyncManager.syncNowQuiet(new HistorySyncManager.Callback() {
                @Override
                public void onDone(boolean ok, String message) {
                    LOG.i(TAG + " pre-rollback sync done ok=" + ok + " msg=" + message);
                    proceed.run();
                }
            });
        } catch (Throwable t) {
            LOG.e(TAG + " pre-rollback sync failed", t);
            proceed.run();
        }
    }
}
