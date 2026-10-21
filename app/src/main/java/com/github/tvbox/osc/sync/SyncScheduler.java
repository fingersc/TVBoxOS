package com.github.tvbox.osc.sync;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.util.LOG;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 同步调度器（v98 P2）。
 *
 * <p>把原先散落在 7 处的触发源（冷启动 / 回前台 / 数据变更 / 30min 周期 / 手动 /
 * 退出播放 / 网络恢复 / 切换账号）收成一个入口，只解决三件事：</p>
 *
 * <ol>
 *   <li><b>合并（coalesce）</b>：同一个账号同时只排一个任务。看剧时进度每秒级落盘，
 *       若每次 {@code markDirty()} 都发一次同步，一集能发几十个请求；
 *       这里按优先级合并 —— 已在排队的任务不会被后面的请求「推后」。</li>
 *   <li><b>自适应间隔</b>：连续 N 次同步都没有任何变化 ⇒ 间隔升档（5→10→20→30 分钟）；
 *       一旦有变更或上传成功 ⇒ 立刻回到 5 分钟。云端没动静时不再白跑。</li>
 *   <li><b>后台停止轮询</b>：App 不在前台就不再排「纯轮询」任务。
 *       盒子上的 App 常年不死，旧的固定 30 分钟周期会在后台一直发请求。</li>
 * </ol>
 *
 * <p>本类只决定「什么时候请求一次同步」，不碰同步协议、不碰数据库。
 * 真正的执行仍然走 {@link HistorySyncManager#sync}。</p>
 */
public final class SyncScheduler {

    /** 自适应间隔档位（分钟 5 / 10 / 20 / 30）。 */
    static final long[] INTERVALS = {5 * 60_000L, 10 * 60_000L, 20 * 60_000L, 30 * 60_000L};

    /** 立即执行：手动同步 / 切换账号 / 退出 App 前。 */
    static final int PRI_NOW = 0;
    /** 本地有改动：60 秒防抖后执行。 */
    static final int PRI_DIRTY = 1;
    /** 单纯轮询云端：按自适应间隔执行。 */
    static final int PRI_IDLE = 2;

    /**
     * 脏数据防抖窗口。
     *
     * <p>不能像旧代码那样用 5 分钟：那样「看完就退出」要等 5 分钟才传；
     * 也不能太短：进度保存是秒级的，太短会退化成「每写完一次就同步一次」。
     * 60 秒与 {@code HistorySyncManager} 的最小间隔一致，取两者交集。</p>
     */
    private static final long DIRTY_DEBOUNCE_MS = 60_000L;

    private static final Object LOCK = new Object();
    private static final Map<String, Task> TASKS = new HashMap<>();

    /** 延迟到首次使用时再建：类初始化不碰 Android，纯策略方法才能在 JVM 上单测。 */
    private static volatile Handler main;
    private static volatile boolean foreground;

    private SyncScheduler() {
    }

    private static Handler main() {
        Handler h = main;
        if (h == null) {
            synchronized (SyncScheduler.class) {
                h = main;
                if (h == null) {
                    h = new Handler(Looper.getMainLooper());
                    main = h;
                }
            }
        }
        return h;
    }

    // ---------------------------------------------------------------- 对外入口

    /**
     * 触发源统一入口（自适应）。
     *
     * @param owner  账号 key（游客传空串会直接忽略）
     * @param reason 仅用于日志
     */
    public static void request(String owner, String reason) {
        schedule(owner, reason, priorityOf(owner), Long.MAX_VALUE, null);
    }

    /**
     * 同上，但「最多等 capMs 毫秒」——冷启动（1.5s）、进后台、网络刚恢复时用它，
     * 保证该跑的那一次不会被自适应间隔推到 5 分钟以后。
     */
    public static void requestWithin(String owner, String reason, long capMs) {
        schedule(owner, reason, priorityOf(owner), capMs, null);
    }

    /** 立即执行（手动 / 切换账号 / 退出 App 前）。cb 可为 null。 */
    public static void requestNow(String owner, String reason, HistorySyncManager.Callback cb) {
        schedule(owner, reason, PRI_NOW, 0L, cb);
    }

    /**
     * 一次同步跑完后的回调：升降档 + 重排下一次空闲轮询。
     *
     * @param active   本次有真实数据流动（下载并合并，或上传成功）
     * @param uploaded 本次确实上传了
     */
    public static void onSyncResult(String owner, boolean active, boolean uploaded) {
        try {
            SyncState st = SyncState.load(owner);
            st.intervalIdx = nextIdx(st.intervalIdx, active || uploaded);
            st.save();
        } catch (Throwable t) {
            LOG.e("[sync] onSyncResult failed", t);
        }
        armIdle(owner);
    }

    /**
     * 本次没有真正发请求（游客 / 未配置存储 / 正在同步 / 最小间隔内 / 仅 Wi-Fi 未满足）。
     * 只重排，不改档位 —— 被节流掉不代表「云端没变化」。
     */
    public static void skipped(String owner, String reason) {
        LOG.i("[sync] skip owner=" + owner + " reason=" + reason);
        armIdle(owner);
    }

    /**
     * 前台状态：{@code false} 时取消所有「纯轮询」任务（脏数据的合并任务保留，
     * 它自己会在防抖窗口内跑完），并且不再排新的轮询。
     */
    public static void setForeground(boolean fg) {
        foreground = fg;
        if (fg) {
            return;
        }
        int n = 0;
        synchronized (LOCK) {
            Iterator<Map.Entry<String, Task>> it = TASKS.entrySet().iterator();
            while (it.hasNext()) {
                Task t = it.next().getValue();
                if (t.pri >= PRI_IDLE) {
                    main().removeCallbacks(t);
                    it.remove();
                    n++;
                }
            }
        }
        LOG.i("[sync] background: cancelled " + n + " idle task(s)");
    }

    /** 切换账号：取消旧账号在排队的任务（新账号的由调用方 requestNow 触发）。 */
    public static void cancel(String owner) {
        if (owner == null || owner.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            Task t = TASKS.remove(owner);
            if (t != null) {
                main().removeCallbacks(t);
            }
        }
        LOG.i("[sync] cancel owner=" + owner);
    }

    /** 调试/日志用：当前排队情况。 */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (Map.Entry<String, Task> e : TASKS.entrySet()) {
                Task t = e.getValue();
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(t.owner).append('/').append(t.reason).append("/pri").append(t.pri)
                        .append("/in").append(Math.max(0L, t.runAt - System.currentTimeMillis())).append("ms");
            }
        }
        return sb.length() == 0 ? "idle" : sb.toString();
    }

    // ---------------------------------------------------------------- 内部

    private static int priorityOf(String owner) {
        try {
            return SyncState.load(owner).anyDirty() ? PRI_DIRTY : PRI_IDLE;
        } catch (Throwable t) {
            return PRI_IDLE;
        }
    }

    private static void schedule(String owner, String reason, int pri, long capMs,
                                 HistorySyncManager.Callback cb) {
        if (owner == null || owner.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            Task pending = TASKS.get(owner);
            if (!shouldReplace(pending == null ? -1 : pending.pri, pri)) {
                // 已有更早的任务在排队 ⇒ 合并掉这次请求（防抖的关键：不推后在途任务）
                LOG.i("[sync] coalesce owner=" + owner + " reason=" + reason
                        + " pri=" + pri + " -> keep " + pending.reason);
                return;
            }
            if (pending != null) {
                main().removeCallbacks(pending);
                TASKS.remove(owner);
            }
            long delay = delayOf(pri, SyncState.load(owner).intervalIdx);
            if (capMs < delay) {
                delay = capMs;
            }
            Task t = new Task(owner, reason, pri, cb);
            t.runAt = now + delay;
            TASKS.put(owner, t);
            main().postDelayed(t, delay);
            LOG.i("[sync] schedule owner=" + owner + " reason=" + reason
                    + " pri=" + pri + " in " + delay + "ms");
        }
    }

    /** 重排下一次空闲轮询。后台 / 游客 / 没配存储 ⇒ 不排。 */
    private static void armIdle(String owner) {
        if (owner == null || owner.isEmpty()) {
            return;
        }
        if (!foreground) {
            LOG.i("[sync] idle chain paused (background) owner=" + owner);
            return;
        }
        try {
            if (AccountContext.isGuest() || !HistorySyncManager.hasConfig()) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        schedule(owner, "idle", PRI_IDLE, Long.MAX_VALUE, null);
    }

    // ---------------------------------------------------------------- 纯策略（可 JVM 单测）

    /**
     * 是否用新请求替换在途任务。
     *
     * <p>规则：没有在途任务 ⇒ 排；新请求是「立即」⇒ 一定替换；
     * 否则只有更高优先级（数值更小 = 更早）才替换，<b>同级或更低一律合并</b> ——
     * 这样「每次写进度都 request」不会把执行时间一次次往后推。</p>
     */
    static boolean shouldReplace(int pendingPri, int newPri) {
        if (pendingPri < 0) {
            return true;
        }
        if (newPri == PRI_NOW) {
            return true;
        }
        return newPri < pendingPri;
    }

    static int clampIdx(int idx) {
        if (idx < 0) {
            return 0;
        }
        return idx >= INTERVALS.length ? INTERVALS.length - 1 : idx;
    }

    static long intervalFor(int idx) {
        return INTERVALS[clampIdx(idx)];
    }

    static long delayOf(int pri, int idx) {
        switch (pri) {
            case PRI_NOW:
                return 0L;
            case PRI_DIRTY:
                return DIRTY_DEBOUNCE_MS;
            default:
                return intervalFor(idx);
        }
    }

    /** 有变化 ⇒ 回到 5 分钟档；连续无变化 ⇒ 升档（封顶 30 分钟）。 */
    static int nextIdx(int idx, boolean active) {
        int cur = clampIdx(idx);
        if (active) {
            return 0;
        }
        return cur >= INTERVALS.length - 1 ? cur : cur + 1;
    }

    // ---------------------------------------------------------------- 任务

    private static final class Task implements Runnable {
        final String owner;
        final String reason;
        final int pri;
        final HistorySyncManager.Callback cb;
        long runAt;

        Task(String owner, String reason, int pri, HistorySyncManager.Callback cb) {
            this.owner = owner;
            this.reason = reason;
            this.pri = pri;
            this.cb = cb;
        }

        @Override
        public void run() {
            synchronized (LOCK) {
                Task cur = TASKS.get(owner);
                if (cur == this) {
                    TASKS.remove(owner);
                }
            }
            // 立即任务（手动 / 切换账号 / 退出 App 前）按 manual 处理：
            // 绕过最小间隔与「仅 Wi-Fi」，否则用户点了「立即同步」却什么都不发生。
            HistorySyncManager.sync(pri == PRI_NOW, false, cb);
        }
    }
}
