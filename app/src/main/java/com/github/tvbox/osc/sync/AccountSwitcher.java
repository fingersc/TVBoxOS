package com.github.tvbox.osc.sync;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;

import com.orhanobut.hawk.Hawk;

/**
 * 账号切换编排（v76）。
 *
 * <p>顺序固定：等当前同步收尾 → 保存当前账号设置快照 → 切库 → 恢复目标账号设置快照
 * → 强制拉一次云端 → 回调。切换期间用 generation 让在途同步放弃写库，避免串库。</p>
 */
public final class AccountSwitcher {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * ★ r217：切号前同步的活性兜底（毫秒）。
     *
     * <p><b>不是「为了不阻塞切换」的预算</b> —— 超时的结果是拦住切号并询问用户，
     * 不是放行。它只用来防止一次死活完不成的同步把账号页永久卡住。</p>
     */
    private static final long SWITCH_SYNC_DEADLINE_MS = 90_000L;

    public interface Callback {
        void onDone(boolean ok, String message);

        /**
         * ★ r217：切号/移除前的同步没有完成，动作已中止（<b>没有</b>切库、<b>没有</b>删库）。
         *
         * <p>默认实现把"被拦下"转成一次失败回调；账号页会覆盖它，弹二次确认，
         * 用户明确选择「仍要切换 / 仍要移除」后再走 {@code skipPreSync} 重载。</p>
         */
        default void onSyncBlocked(String reason) {
            onDone(false, "同步未完成：" + reason);
        }

        /** ★ r217：阶段提示（"正在同步当前账号…" / "正在切换…"），可能在后台线程回调。 */
        default void onStage(String text) {
        }
    }

    private AccountSwitcher() {
    }

    /** 切换到指定账号（调用方必须已通过密码或扫码完成认证）。 */
    public static void switchTo(final String owner, final String name, final byte[] key,
                                final Callback cb) {
        switchTo(owner, name, key, true, false, cb);
    }

    /**
     * r197：waitForSync=false 时「切换完成」不等首轮同步跑完（扫码登录专用，电视立刻显示已登录），
     * 首轮同步转后台继续；true 保持原语义（密码登录 / 手动切账号需要等云端设置到位）。
     */
    public static void switchTo(final String owner, final String name, final byte[] key,
                                final boolean waitForSync, final Callback cb) {
        switchTo(owner, name, key, waitForSync, false, cb);
    }

    /**
     * ★ r217：{@code skipPreSync=true} = 用户已在「同步未完成」弹窗里明确选择"仍要切换"，
     * 跳过切号前的同步闸门直接切库（旧账号未上传的改动留在本机，下次登录该账号补传）。
     */
    public static void switchTo(final String owner, final String name, final byte[] key,
                                final boolean waitForSync, final boolean skipPreSync,
                                final Callback cb) {
        if (owner == null || owner.isEmpty() || key == null) {
            post(cb, false, "账号信息不完整，请重新认证");
            return;
        }
        if (owner.equals(AccountContext.ownerKey())) {
            post(cb, true, "已经是当前账号");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String previous = AccountContext.ownerKey();
                if (skipPreSync) {
                    // 强制切换：给在途同步最多 1.5 秒收尾，随后 bumpGeneration 会让它作废
                    HistorySyncManager.awaitIdle(1500);
                } else if (!preSwitchSync(previous, cb)) {
                    return;   // 同步没到位：不切库，等用户决定
                }
                // v98 P2：取消旧账号排队中的同步任务 —— 它一旦跑起来就会用新账号的库
                SyncScheduler.cancel(previous);
                SettingsProfile.save(previous == null ? "" : previous);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        String message = "已切换到：" + name;
                        try {
                            HistorySyncManager.bumpGeneration();
                            AccountContext.login(owner, name, key);
                            // r216：换了账号 ⇒ 让同步线程对新账号重做一次「已推送水位对账」
                            //（原实现在启动主线程对新登录账号跑；挪到同步线程后需在此复位）
                            HistorySyncManager.resetReconciled();
                            // v98 P1：目标账号的旧散键状态迁到 SyncState（每账号只做一次）
                            SyncState.migrateLegacyKeys(owner);
                            AccountStore.add(name, owner);
                            AccountStore.touch(owner);
                            boolean loaded = SettingsProfile.load(owner);
                            if (!loaded) {
                                // 首次加入本机：不清空设备已有配置，只标记「首次同步只拉不推」，
                                // 云端有设置就以云端为准，没有就沿用当前配置
                                SettingsProfile.setPendingPull(owner, true);
                                message = "已切换到：" + name + "（首次使用，正在拉取云端设置）";
                            }
                        } catch (Throwable t) {
                            LOG.e("[Account] switch failed", t);
                            post(cb, false, "切换失败：" + t.getMessage());
                            return;
                        }
                        // v98 P3：换了账号 ⇒ 接口地址整套变了（整包重载）+ 直播配置要重新预热。
                        //         交给 ConfigCenter：首页不在前台 / 首屏还没就绪时会自动推迟，
                        //         不会像以前那样在账号页背后偷偷重载首页。
                        ConfigCenter.requestReload("switch");
                        ConfigCenter.requestPrewarmLive("switch");
                        final String msg = message;
                        // v98 P2：切换账号是「必须立刻拉一次」的场景，
                        //        走调度器的立即档（不再借用历史页 pullNow 的 20 秒节流）
                        if (waitForSync) {
                            SyncScheduler.requestNow(owner, "switch", true, new HistorySyncManager.Callback() {
                                @Override
                                public void onDone(boolean ok, String text) {
                                    post(cb, true, ok ? msg : msg + "；同步未完成，稍后会自动重试");
                                }
                            });
                        } else {
                            // r197：扫码登录——账号切好就先回调（电视立刻显示已登录），
                            //        首轮同步放后台跑，不再让用户盯着「正在登录」等网盘往返。
                            post(cb, true, msg);
                            SyncScheduler.requestNow(owner, "switch", true, new HistorySyncManager.Callback() {
                                @Override
                                public void onDone(boolean ok, String text) {
                                    LOG.i("[Sync] qr background sync done ok=" + ok);
                                }
                            });
                        }
                    }
                });
            }
        }, "tvbox-account-switch").start();
    }

    /** 切换到游客（不删除任何账号，也不清存储配置）。 */
    public static void switchToGuest(final Callback cb) {
        switchToGuest(cb, true);
    }

    /**
     * r216：切换到游客，可选择是否顺带请求界面重载。
     *
     * <p>{@code reloadUi=false} 供「冷启动回退」使用：调用方紧接着会自己重新走一遍
     * {@code HomeActivity.initData()}，此时再让 {@link ConfigCenter} 排一次重载只是重复劳动
     * （且首屏还没就绪，动作会被推迟到下一个门控点，白白多绕一圈）。</p>
     */
    public static void switchToGuest(final Callback cb, final boolean reloadUi) {
        switchToGuest(cb, reloadUi, false);
    }

    /**
     * ★ r217：{@code skipPreSync=true} 语义同
     * {@link #switchTo(String, String, byte[], boolean, boolean, Callback)}：
     * 用户已确认"仍要切换"，不再等待同步。
     */
    public static void switchToGuest(final Callback cb, final boolean reloadUi,
                                     final boolean skipPreSync) {
        if (AccountContext.isGuest()) {
            post(cb, true, "当前已是游客模式");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String previous = AccountContext.ownerKey();
                if (skipPreSync) {
                    HistorySyncManager.awaitIdle(1500);
                } else if (!preSwitchSync(previous, cb)) {
                    return;   // 同步没到位：不切库，等用户决定
                }
                SyncScheduler.cancel(previous);
                // 快照在后台线程存（SettingsProfile.save → HlcClock 会打开 Room，
                // 不适合放到主线程的切库动作里）。下面 doLogoutToGuest 传 saveProfile=false。
                try {
                    saveProfileBeforeLogout();
                } catch (Throwable t) {
                    LOG.e("[Account] save profile before guest switch failed", t);
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // 快照已在后台线程存过（见上方 SettingsProfile.save），
                            // 这里不再重复打开 Room。
                            doLogoutToGuest(reloadUi, false);
                        } catch (Throwable t) {
                            LOG.e("[Account] switch guest failed", t);
                        }
                        post(cb, true, "已切换到游客模式");
                    }
                });
            }
        }, "tvbox-account-guest").start();
    }

    /**
     * r216：<b>主线程同步版</b>的「回退到游客」。
     *
     * <p>给冷启动回退用：此时进程刚起来，没有正在跑的界面动作，也等不起一个后台线程往返。
     * 次序与 {@link #switchToGuest(Callback, boolean)} 一致（只去掉"等同步收尾"那一步 ——
     * 调用方必须自己保证同步已经结束或已被作废）：</p>
     *
     * <ol>
     *   <li>{@link #saveProfileBeforeLogout()}：保住该账号的设置快照（调用方应先在后台线程做完）；</li>
     *   <li>{@link SyncScheduler#cancel(String)}：撤掉该账号排队中的任务；</li>
     *   <li>{@link HistorySyncManager#bumpGeneration()}：让可能还在途的同步作废（不写错库）；</li>
     *   <li>{@link AccountContext#logout()}：清登录态 + 切回游客库；</li>
     *   <li>{@link SettingsProfile#load(String)}：恢复游客的设置快照。</li>
     * </ol>
     *
     * <p>必须、且只允许在主线程调用（{@code AccountContext.logout} 会切 Room 库）。</p>
     */
    public static void logoutToGuestNow() {
        doLogoutToGuest(false, true);
    }

    /**
     * r216：回退到游客，但<b>不再保存设置快照</b>。
     *
     * <p>只给"已经在后台线程调用过 {@link #saveProfileBeforeLogout()}"的路径用 ——
     * 避免在主线程上因为 {@code SettingsProfile.save} → {@code HlcClock.next()} 而打开 Room。</p>
     */
    public static void logoutToGuestNowSkipSave() {
        doLogoutToGuest(false, false);
    }

    /**
     * r216：把当前账号的设置存成快照。
     *
     * <p>内部会经 {@code HlcClock.next()} 打开 Room 并与 DB 对账，属于会阻塞的活；
     * 冷启动回退时由调用方放在<b>后台线程</b>执行，不要直接在主线程调。</p>
     */
    public static void saveProfileBeforeLogout() {
        String owner = AccountContext.ownerKey();
        if (owner == null || owner.length() == 0) {
            return;
        }
        SettingsProfile.save(owner);
    }

    /**
     * 回退到游客的实体动作（主线程）。
     *
     * @param reloadUi     是否顺带请求界面重载，见 {@link #switchToGuest(Callback, boolean)}
     * @param saveProfile  是否在这里补存设置快照。<b>只有"调用方已经在后台线程存过"时才传 false</b>
     *                     —— 这个调用会在主线程上打开 Room（{@code SettingsProfile.save} →
     *                     {@code HlcClock.next}），冷启动路径要避开它。
     */
    private static void doLogoutToGuest(boolean reloadUi, boolean saveProfile) {
        if (AccountContext.isGuest()) {
            return;
        }
        final String previous = AccountContext.ownerKey();
        if (saveProfile) {
            try {
                SettingsProfile.save(previous == null ? "" : previous);
            } catch (Throwable t) {
                LOG.e("[Account] save profile before logout failed", t);
            }
        }
        try {
            SyncScheduler.cancel(previous);
        } catch (Throwable ignored) {
        }
        HistorySyncManager.bumpGeneration();
        AccountContext.logout();
        // 换了账号 ⇒ 对账要为新账号重做一次（进程级"只做一次"的标记必须复位）
        HistorySyncManager.resetReconciled();
        // 账号级 HLC 时钟也是每账号一份：切号必须让它重新加载，否则会沿用上一个账号的
        // 水位（Hawk 键 sync_hlc.<owner>），游客下分配出的时间戳会带着别人的历史。
        HlcClock.reset();
        SettingsProfile.load("");
        if (reloadUi) {
            ConfigCenter.requestReload("guest");
            ConfigCenter.requestPrewarmLive("guest");
        }
        LOG.i("[Account] rolled back to guest (reloadUi=" + reloadUi + ")");
    }

    /** 移除本机账号：删清单、设置快照、数据库文件；云端数据不动。 */
    public static void remove(final String owner, final Callback cb) {
        remove(owner, false, cb);
    }

    /**
     * ★ r217：移除账号前先同步。
     *
     * <p>删本地库（{@link AppDataManager#deleteOwnerDb(String)}）会把这台设备上尚未上传的
     * 改动一起删掉，这是不可逆的。所以对<b>当前账号</b>先过一次同步闸门，
     * 失败则中止删除，等用户明确选择「仍要移除」再走 {@code skipPreSync=true}。</p>
     *
     * <p>非当前账号没有密钥，无法替它同步；脏标记只用于确认框里的提示。</p>
     */
    public static void remove(final String owner, final boolean skipPreSync, final Callback cb) {
        if (owner == null || owner.isEmpty()) {
            post(cb, false, "游客不能移除");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HistorySyncManager.awaitIdle(1200);
                boolean current = owner.equals(AccountContext.ownerKey());
                if (current && !skipPreSync && !preSwitchSync(owner, cb)) {
                    return;   // 同步没到位：不删库
                }
                SyncScheduler.cancel(owner);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (owner.equals(AccountContext.ownerKey())) {
                                HistorySyncManager.bumpGeneration();
                                AccountContext.logout();
                                HistorySyncManager.resetReconciled();
                                SettingsProfile.load("");
                            }
                            AccountStore.remove(owner);
                            SettingsProfile.delete(owner);
                            AppDataManager.deleteOwnerDb(owner);
                            // v77：移除最后一个账号 = 彻底退出，连网盘凭据一起清掉（不再单独提供"退出登录"）
                            if (AccountStore.list().isEmpty()) {
                                Hawk.delete(HawkConfig.SYNC_WEBDAV_URL);
                                Hawk.delete(HawkConfig.SYNC_WEBDAV_USER);
                                Hawk.delete(HawkConfig.SYNC_WEBDAV_PASS);
                                post(cb, true, "已移除本机账号，并清空网盘配置");
                                return;
                            }
                        } catch (Throwable t) {
                            LOG.e("[Account] remove failed", t);
                            post(cb, false, "移除失败：" + t.getMessage());
                            return;
                        }
                        post(cb, true, "已移除本机账号（云端数据未删除）");
                    }
                });
            }
        }, "tvbox-account-remove").start();
    }

    /**
     * ★ r217：切库/删库前的同步闸门。
     *
     * <p>拦截时通过 {@link Callback#onSyncBlocked(String)} 通知调用方，由 UI 弹二次确认；
     * 用户确认后调用方用 {@code skipPreSync=true} 重入同一条路径。</p>
     *
     * @return true = 已同步到位，可以继续；false = 已回调 onSyncBlocked，调用方必须中止
     */
    private static boolean preSwitchSync(final String owner, final Callback cb) {
        if (owner == null || owner.isEmpty()) {
            return true;   // 游客：没有云端数据可同步
        }
        postStage(cb, "正在同步当前账号…");
        HistorySyncManager.SwitchSync r =
                HistorySyncManager.syncBeforeSwitch(owner, SWITCH_SYNC_DEADLINE_MS);
        LOG.i("[Account] pre-switch sync ok=" + r.ok + " rounds=" + r.rounds
                + " msg=" + (r.message == null ? "" : r.message));
        if (r.ok) {
            postStage(cb, "正在切换…");
            return true;
        }
        postSyncBlocked(cb, r.message == null || r.message.length() == 0
                ? "仍有未上传的改动" : r.message);
        return false;
    }

    private static void postStage(final Callback cb, final String text) {
        if (cb == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onStage(text);
            }
        });
    }

    private static void postSyncBlocked(final Callback cb, final String reason) {
        if (cb == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onSyncBlocked(reason);
            }
        });
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
}
