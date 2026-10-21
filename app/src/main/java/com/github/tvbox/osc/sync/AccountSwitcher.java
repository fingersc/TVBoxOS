package com.github.tvbox.osc.sync;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;

import org.greenrobot.eventbus.EventBus;

/**
 * 账号切换编排（v76）。
 *
 * <p>顺序固定：等当前同步收尾 → 保存当前账号设置快照 → 切库 → 恢复目标账号设置快照
 * → 强制拉一次云端 → 回调。切换期间用 generation 让在途同步放弃写库，避免串库。</p>
 */
public final class AccountSwitcher {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onDone(boolean ok, String message);
    }

    private AccountSwitcher() {
    }

    /** 切换到指定账号（调用方必须已通过密码或扫码完成认证）。 */
    public static void switchTo(final String owner, final String name, final byte[] key,
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
                // 等当前账号的同步收尾（最多 1.5 秒），避免切换瞬间把旧数据写进新库
                HistorySyncManager.awaitIdle(1500);
                final String previous = AccountContext.ownerKey();
                SettingsProfile.save(previous == null ? "" : previous);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        String message = "已切换到：" + name;
                        try {
                            HistorySyncManager.bumpGeneration();
                            AccountContext.login(owner, name, key);
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
                        // 换了账号：让首页按新账号的接口地址重载配置
                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_CONFIG_CHANGE));
                        final String msg = message;
                        HistorySyncManager.pullNow(new HistorySyncManager.Callback() {
                            @Override
                            public void onDone(boolean ok, String text) {
                                post(cb, true, ok ? msg : msg + "；同步未完成，稍后会自动重试");
                            }
                        });
                    }
                });
            }
        }, "tvbox-account-switch").start();
    }

    /** 切换到游客（不删除任何账号，也不清存储配置）。 */
    public static void switchToGuest(final Callback cb) {
        if (AccountContext.isGuest()) {
            post(cb, true, "当前已是游客模式");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HistorySyncManager.awaitIdle(1500);
                final String previous = AccountContext.ownerKey();
                SettingsProfile.save(previous == null ? "" : previous);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            HistorySyncManager.bumpGeneration();
                            AccountContext.logout();
                            SettingsProfile.load("");
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_CONFIG_CHANGE));
                        } catch (Throwable t) {
                            LOG.e("[Account] switch guest failed", t);
                        }
                        post(cb, true, "已切换到游客模式");
                    }
                });
            }
        }, "tvbox-account-guest").start();
    }

    /** 移除本机账号：删清单、设置快照、数据库文件；云端数据不动。 */
    public static void remove(final String owner, final Callback cb) {
        if (owner == null || owner.isEmpty()) {
            post(cb, false, "游客不能移除");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HistorySyncManager.awaitIdle(1200);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (owner.equals(AccountContext.ownerKey())) {
                                HistorySyncManager.bumpGeneration();
                                AccountContext.logout();
                                SettingsProfile.load("");
                            }
                            AccountStore.remove(owner);
                            SettingsProfile.delete(owner);
                            AppDataManager.deleteOwnerDb(owner);
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
