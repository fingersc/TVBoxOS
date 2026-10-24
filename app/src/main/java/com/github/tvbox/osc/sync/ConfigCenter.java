package com.github.tvbox.osc.sync;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LiveConfigPrefetcher;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;

import java.util.Map;

/**
 * 配置生效中枢（v98 P3）。
 *
 * <p>把原先<b>三套互相重叠</b>的「同步写回设置 → 让界面生效」机制合并成一个：</p>
 *
 * <table border="1">
 *   <tr><th>被合并掉的旧机制</th><th>位置</th><th>问题</th></tr>
 *   <tr><td>{@code pendingConfigEvent} + 8 秒启动静默期</td><td>HistorySyncManager / HomeActivity</td>
 *       <td>静默期是拍脑袋的常数，两处各自消费一次可能重复重载</td></tr>
 *   <tr><td>{@code pendingConfigReload}</td><td>HomeActivity</td>
 *       <td>只挡「页面不可见」，不挡「首屏还没出来」</td></tr>
 *   <tr><td>{@code lastConfigReloadAt}（60 秒节流）</td><td>HomeActivity</td>
 *       <td>与上面两套并存，节流窗口各自计时</td></tr>
 * </table>
 *
 * <p>新的统一口径只有三个：</p>
 * <ol>
 *   <li><b>homeReady</b>：首屏数据已加载过一次 ⇒ 才允许重载（v88 的教训：
 *       同步在启动 1.5 秒写回配置，此刻重载会把正在解析的首页推倒重来，
 *       表现就是「加了账号同步之后起播变慢」）。</li>
 *   <li><b>uiReady</b>：首页当前在前台 ⇒ 才允许重载（用户在看详情页时不打扰）。</li>
 *   <li><b>lastFlushAt</b>：60 秒内最多执行一次，窗口内到达的计划<b>合并</b>而不是丢弃。</li>
 * </ol>
 *
 * <p>「键 → 需要哪个动作」的分类表在 {@link ConfigPlan}（纯 Java，可单测）。</p>
 */
public final class ConfigCenter {

    /** 两次执行的节流窗口：期间到达的计划会合并，窗口结束后一起执行。 */
    private static final long THROTTLE_MS = 60_000L;

    /**
     * 首屏迟迟不就绪时的兜底等待。
     *
     * <p>「首次安装 → 登录 → 同步写回接口地址」这条链路上，首屏很可能根本没加载成功过
     * （还没有 api_url），此时如果死等 {@code homeReady} 就永远不会重载 ——
     * 用户会一直看到空首页。等 15 秒还没就绪就直接放行。</p>
     */
    private static final long FORCE_AFTER_MS = 15_000L;

    /** 首屏刚就绪后的额外缓冲，避免和 initData() / jar 加载抢资源。 */
    private static final long HOME_READY_DELAY_MS = 1500L;

    /** 直播预热与首页动作错开的时间。 */
    private static final long PREWARM_DELAY_MS = 2000L;

    private static final ConfigPlan PENDING = new ConfigPlan();
    private static final Object LOCK = new Object();

    /** 延迟到首次使用时再建：类初始化不碰 Android。 */
    private static volatile Handler main;
    private static volatile boolean homeReady;
    private static volatile boolean uiReady;
    private static long lastFlushAt;
    /** 最老一个未执行计划的产生时刻（0 = 无待办）。 */
    private static long pendingSince;

    private static final Runnable FLUSH_TASK = new Runnable() {
        @Override
        public void run() {
            flush();
        }
    };

    private ConfigCenter() {
    }

    private static Handler main() {
        Handler h = main;
        if (h == null) {
            synchronized (ConfigCenter.class) {
                h = main;
                if (h == null) {
                    h = new Handler(Looper.getMainLooper());
                    main = h;
                }
            }
        }
        return h;
    }

    // ---------------------------------------------------------------- 数据面

    /**
     * 把合并后的设置写回本机，并算出这次需要哪些界面动作。
     *
     * <p>只负责「写 + 算」；真正执行动作要等 {@link #flush()}（可能被门控推迟）。
     * 这样即使本次被推迟，设置本身也已经落到 Hawk，不会丢。</p>
     *
     * @return 本次产生的计划（已被并入待办队列）
     */
    public static ConfigPlan apply(Map<String, SyncPayload.SettingValue> merged) {
        String[] before = snapshot();
        try {
            SyncSettings.apply(merged);
            SyncSettings.saveSnapshot(merged);
        } catch (Throwable t) {
            LOG.e("[Config] apply settings failed", t);
        }
        ConfigPlan p = ConfigPlan.diff(before, snapshot());
        if (!p.isEmpty()) {
            mergeBack(p);
            LOG.i("[Config] plan=" + p + " keys=" + (merged == null ? 0 : merged.size()));
        }
        return p;
    }

    /** 没有具体键变化的整包重载（切换账号：整套设置快照被换掉）。 */
    public static void requestReload(String reason) {
        ConfigPlan p = new ConfigPlan();
        p.set(ConfigPlan.RELOAD);
        mergeBack(p);
        LOG.i("[Config] request reload reason=" + reason);
        flush();
    }

    /** 只预热直播配置（切换账号）。 */
    public static void requestPrewarmLive(String reason) {
        ConfigPlan p = new ConfigPlan();
        p.set(ConfigPlan.LIVE);
        mergeBack(p);
        LOG.i("[Config] request prewarm reason=" + reason);
        flush();
    }

    // ---------------------------------------------------------------- 门控 / 节流 / 执行

    /**
     * 尝试执行待办计划。
     *
     * <p>被门控或节流挡下时，计划会<b>原样并回队列</b>并重新定时，
     * 不会因为「这一刻不该执行」就永远丢失。</p>
     */
    public static void flush() {
        final ConfigPlan p = consumePending();
        if (p.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!uiReady) {
            // 首页不在前台：一律推迟。用户回到首页时 onResume 会 setUiReady(true) 再 flush。
            mergeBack(p);
            LOG.i("[Config] deferred: home not resumed " + p);
            return;
        }
        if (!homeReady) {
            long waited = now - pendingSince;
            if (waited < FORCE_AFTER_MS) {
                mergeBack(p);
                scheduleFlush(FORCE_AFTER_MS - waited + 100L);
                LOG.i("[Config] deferred: first screen not ready, waited " + waited + "ms " + p);
                return;
            }
            LOG.i("[Config] force flush: first screen still not ready after " + waited + "ms " + p);
        }
        if (now - lastFlushAt < THROTTLE_MS) {
            mergeBack(p);
            scheduleFlush(THROTTLE_MS - (now - lastFlushAt) + 50L);
            LOG.i("[Config] throttled, merged back " + p);
            return;
        }
        lastFlushAt = now;
        execute(p);
    }

    /** 取出并清空待办（取到即视为"已受理"，执行失败也不会重复堆积）。 */
    public static ConfigPlan consumePending() {
        ConfigPlan p = new ConfigPlan();
        synchronized (LOCK) {
            p.merge(PENDING);
            PENDING.reloadConfig = false;
            PENDING.refreshHome = false;
            PENDING.prewarmLive = false;
            PENDING.refreshHistory = false;
        }
        return p;
    }

    /**
     * 首屏数据已加载过一次（HomeActivity 的 loadConfig 成功回调里调用）。
     *
     * <p>不立刻 flush：首屏刚出来的几百毫秒里正是 initData() / jar 加载的高峰，
     * 这里再补 1.5 秒缓冲。</p>
     */
    public static void markHomeReady() {
        homeReady = true;
        scheduleFlush(HOME_READY_DELAY_MS);
    }

    /** 首页是否在前台（HomeActivity onResume/onPause 调用）。 */
    public static void setUiReady(boolean ready) {
        uiReady = ready;
        if (ready) {
            flush();
        }
    }

    public static boolean isHomeReady() {
        return homeReady;
    }

    /** 调试/日志用。 */
    public static String dump() {
        synchronized (LOCK) {
            return "pending=" + PENDING + " homeReady=" + homeReady + " uiReady=" + uiReady
                    + " since=" + pendingSince;
        }
    }

    // ---------------------------------------------------------------- 内部

    private static void execute(final ConfigPlan p) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main().post(new Runnable() {
                @Override
                public void run() {
                    execute(p);
                }
            });
            return;
        }
        LOG.i("[Config] flush " + p);
        synchronized (LOCK) {
            pendingSince = 0L;
        }
        if (p.reloadConfig) {
            // 接口地址 / 线路变了：只能整包重载。HomeActivity 内部会优先走缓存，
            // URL 真变了缓存 key 才不同，才会重新拉网络。
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_CONFIG_CHANGE));
        } else if (p.refreshHome) {
            // 只是首页源变了：接口配置完全不用重新解析，直接换源再刷新布局。
            applyHomeSource();
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HOME_SOURCE_CHANGE));
        }
        if (p.refreshHistory) {
            // ★ v99-r47：历史档位 / 同名合并开关变了 —— 只让列表按新口径重排。
            //   这里**不裁剪**：真实裁剪由设置页的二次确认（或下一次写历史）执行，
            //   在配置下发路径上删数据会把"调低后又调回"变成不可逆误删。
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
        }
        if (p.prewarmLive) {
            // 与上面的首页动作错开 2 秒：两者一起跑就是又一次「抢首屏」。
            main().postDelayed(new Runnable() {
                @Override
                public void run() {
                    LiveConfigPrefetcher.prefetch();
                }
            }, PREWARM_DELAY_MS);
        }
    }

    /**
     * 把首页源切到 {@code home_api} 指向的站点。
     *
     * <p>{@code ApiConfig.parseJson} 是在解析接口配置时才读 {@code home_api} 的，
     * 因此只改这个键而不重新解析 ⇒ 首页源不会变（这正是 v79 的隐性缺陷：
     * 它被归到「整包重载」组，却又常常因为「api url 没变」被降级成只刷排序，
     * 结果首页源的改动根本没生效）。</p>
     */
    private static void applyHomeSource() {
        try {
            String home = Hawk.get(HawkConfig.HOME_API, "");
            if (home == null || home.length() == 0) {
                return;
            }
            SourceBean sb = ApiConfig.get().getSource(home);
            if (sb == null) {
                LOG.i("[Config] home source not in current config: " + home);
                return;
            }
            if (sb != ApiConfig.get().getHomeSourceBean()) {
                ApiConfig.get().setSourceBean(sb);
                LOG.i("[Config] home source -> " + home);
            }
        } catch (Throwable t) {
            LOG.e("[Config] apply home source failed", t);
        }
    }

    private static String[] snapshot() {
        String[] keys = ConfigPlan.keys();
        String[] out = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = valueOf(keys[i]);
        }
        return out;
    }

    private static String valueOf(String key) {
        try {
            Object v = Hawk.contains(key) ? Hawk.get(key) : null;
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void mergeBack(ConfigPlan p) {
        synchronized (LOCK) {
            if (pendingSince <= 0) {
                pendingSince = System.currentTimeMillis();
            }
            PENDING.merge(p);
        }
    }

    private static void scheduleFlush(long delay) {
        if (delay < 0L) {
            delay = 0L;
        }
        Handler h = main();
        h.removeCallbacks(FLUSH_TASK);
        h.postDelayed(FLUSH_TASK, delay);
    }
}
