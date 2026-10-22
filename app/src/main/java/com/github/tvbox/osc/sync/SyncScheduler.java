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

    /**
     * 「没有上限」的 capMs 哨兵。
     *
     * <p>★ v99-r17：这里<b>不能</b>沿用 {@code Long.MAX_VALUE} —— 判提前时要算
     * {@code now + capMs}，MAX_VALUE 会溢出成负数，比较恒真，把在途任务推到永不执行。
     * 负数既不会溢出，又能被 {@code capMs >= 0} 一道判定干净地排除。</p>
     */
    static final long NO_CAP = -1L;

    /**
     * 「进后台补一次」的 reason。与 {@code HistorySyncManager.onBackground()} 对应 ——
     * 调度层据此把 {@code background=true} 传给执行层，让它走更短的最小间隔。
     */
    public static final String REASON_BG = "bg";

    private static final Object LOCK = new Object();
    private static final Map<String, Task> TASKS = new HashMap<>();

    /** 延迟到首次使用时再建：类初始化不碰 Android，纯策略方法才能在 JVM 上单测。 */
    private static volatile Handler main;

    /**
     * ★ v99-r18：前台判定拆成两个独立来源，取「或」。
     *
     * <p>r17 只用一个 {@code foreground} 标志：播放页 onResume 置 true、HomeActivity.onStop
     * 置 false。而「首页 → 打开详情页」的真实顺序是
     * <b>Play.onResume(true) → Home.onStop(false)</b>，后执行的 false 必然盖掉前面的 true
     * ⇒ 播放期间 100% 被判成后台。实测证据：每次进播放页约 200ms 后就会打出
     * {@code [sync] background: cancelled 1 idle task(s)}，播放中一直是
     * {@code idle chain paused (background)}。</p>
     *
     * <p>拆成两个来源后各自独立，只要有一个为真就是前台，谁也不会覆盖谁。</p>
     */
    private static volatile boolean homeVisible;
    /** 播放页是否在前台（PlayFragment 置位）。见 {@link #homeVisible}。 */
    private static volatile boolean playing;

    /** 当前是否算前台：首页可见 或 播放页在前台，两者取或。 */
    public static boolean isForeground() {
        return homeVisible || playing;
    }

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
        schedule(owner, reason, priorityOf(owner), NO_CAP, null);
    }

    /**
     * 同上，但「最多等 capMs 毫秒」——冷启动（1.5s）、进后台、网络刚恢复时用它，
     * 保证该跑的那一次不会被自适应间隔推到 5 分钟以后。
     *
     * <p>★ v99-r17：这个「最多等」在合并分支里同样兑现 —— 已在途的任务会被
     * <b>提前</b>到 capMs（见 {@link #pullForwardMs}），而不是被合并掉就当作没说过。
     * 之所以必须提前：{@code onBackground()} 传的是 0，若被 60 秒防抖任务吞掉，
     * 「退出播放页 / 上滑清理后台」就成了等 60 秒才上传，进程早死了。</p>
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
     *
     * <p>v99-r5 ★：被挡掉的如果是「脏数据」的同步，60 秒后重试，而不是按自适应档位
     * （最长 30 分钟）—— 否则冷启动 / 网络抖动正好撞上最小间隔时，本地改动可能半小时都传不上去。</p>
     *
     * <p>只在<b>前台</b>重试：后台不打扰（脏标记已落盘，回前台时 onForeground 会按脏优先排队）。</p>
     */
    public static void skipped(String owner, String reason) {
        LOG.i("[sync] skip owner=" + owner + " reason=" + reason);
        if (isForeground() && owner != null && !owner.isEmpty()) {
            try {
                if (SyncState.load(owner).anyDirty()) {
                    schedule(owner, "retry-dirty", PRI_DIRTY, NO_CAP, null);
                    return;
                }
            } catch (Throwable t) {
                LOG.e("[sync] retry check failed", t);
            }
        }
        armIdle(owner);
    }

    /** HomeActivity 的可见性变化（onResume / onStop）。 */
    public static void setForeground(boolean fg) {
        homeVisible = fg;
        applyForegroundState();
    }

    /**
     * 播放页的前台状态变化（v99-r18）。
     *
     * <p>只在本 Fragment 真的可见/不可见时置位，与首页可见性互不覆盖 —— 详见
     * {@link #homeVisible}。退出播放页时置 false：此刻首页若已可见（返回首页），
     * 合并结果仍是前台；若直接退到桌面，则正确地落入后台。</p>
     */
    public static void setPlaying(boolean p) {
        playing = p;
        applyForegroundState();
    }

    /**
     * 前台状态：合并结果为 {@code false} 时取消所有「纯轮询」任务（脏数据的合并任务保留，
     * 它自己会在防抖窗口内跑完），并且不再排新的轮询。
     */
    private static void applyForegroundState() {
        if (isForeground()) {
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
                // 已有更早的任务在排队 ⇒ 合并掉这次请求（防抖的关键：不推后在途任务）。
                //
                // ★ v99-r17：合并 ≠ 把「最多等 capMs」当作没说过。
                //   传入 capMs 的请求（进后台 0ms / 网络恢复 1s / 冷启动 1.5s）即便不替换任务，
                //   也要把在途任务**提前**到 capMs —— 只提前，绝不推后（推后会让秒级写入
                //   把防抖窗口无限延后，一次都发不出去）。
                long pf = pullForwardMs(pending == null ? -1L : pending.runAt, now, capMs);
                if (pf >= 0) {
                    main().removeCallbacks(pending);
                    pending.runAt = now + pf;
                    main().postDelayed(pending, pf);
                    LOG.i("[sync] pull-forward owner=" + owner + " reason=" + reason
                            + " capMs=" + capMs + " -> keep " + pending.reason + " in " + pf + "ms");
                } else {
                    LOG.i("[sync] coalesce owner=" + owner + " reason=" + reason
                            + " pri=" + pri + " -> keep " + pending.reason);
                }
                return;
            }
            if (pending != null) {
                main().removeCallbacks(pending);
                TASKS.remove(owner);
            }
            long delay = delayOf(pri, SyncState.load(owner).intervalIdx);
            if (capMs >= 0 && capMs < delay) {
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
        if (!isForeground()) {
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
        schedule(owner, "idle", PRI_IDLE, NO_CAP, null);
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

    /**
     * 合并分支里「是否把在途任务提前」，返回新的延迟毫秒；{@code <0} 表示不提前。
     *
     * <p>只在「新请求要求得更早」时提前，且<b>永远不推后</b>。三道门：</p>
     * <ul>
     *   <li>{@code capMs < 0}：{@link #NO_CAP} / 无上限请求 ⇒ 不提前；</li>
     *   <li>{@code pendingRunAt < 0}：没有在途任务 ⇒ 无从提前（调用方会走正常排期）；</li>
     *   <li>{@code pendingRunAt <= now + capMs}：在途任务本来就比 capMs 更早 ⇒ 不提前。</li>
     * </ul>
     *
     * @param pendingRunAt 在途任务的原定执行时刻（{@code <0} 表示没有在途任务）
     * @param now          当前时刻
     * @param capMs        本次请求要求的最长等待（{@link #NO_CAP} 表示无要求）
     */
    static long pullForwardMs(long pendingRunAt, long now, long capMs) {
        if (capMs < 0 || pendingRunAt < 0 || pendingRunAt <= now + capMs) {
            return -1L;
        }
        return capMs;
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
            //
            // background：r17 只把「进后台补一次」提前到 0ms，执行层却仍被 60s 最小间隔
            // 挡回来（实测 3 次退出播放 100% 命中 min-gap）。进后台是最后一次机会，
            // 进程随时可能被杀，这里告诉执行层走更短的最小间隔（仍保留「仅 Wi-Fi」门禁）。
            HistorySyncManager.sync(pri == PRI_NOW, REASON_BG.equals(reason), cb);
        }
    }
}
