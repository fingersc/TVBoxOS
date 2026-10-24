package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.util.HawkConfig;

/**
 * 配置生效计划（v98 P3）—— 把 37 个同步设置键收敛成 3 类界面动作。
 *
 * <p><b>为什么需要这张表：</b>同步写回设置后，绝大多数键（历史条数 / 字幕 / 弹幕 /
 * 播放器 / 渲染 …）写进 Hawk 就立即生效，<b>不需要任何界面动作</b>。
 * 真正需要动界面的只有 5 个键，而且它们需要的动作还不一样：</p>
 *
 * <table border="1">
 *   <tr><th>键</th><th>需要的动作</th><th>代价</th></tr>
 *   <tr><td>{@code api_url} / {@code api_line_source}</td><td>整包重载接口配置</td><td>最重（重新解析 + 拉 jar）</td></tr>
 *   <tr><td>{@code home_api}</td><td>切换首页源 + 刷新布局</td><td>中（不必重新解析接口）</td></tr>
 *   <tr><td>{@code live_api_url} / {@code epg_url}</td><td>只预热直播配置</td><td>最轻（首页一个字节不动）</td></tr>
 * </table>
 *
 * <p>v79 的实现把前 3 个键一律当成「整包重载」，又把「直播键变化」和「首页键变化」
 * 混在一起无条件触发直播预热 —— 结果是<b>改一个首页源也要去预热一次直播配置</b>，
 * 白跑一趟网络。这里按键精确分类，各走各的路。</p>
 *
 * <p>纯 Java：不碰 Hawk / EventBus / Android，键分类与 diff 可以直接在 JVM 上跑测试。
 * 真正执行动作的代码在 {@link ConfigCenter}。</p>
 */
public final class ConfigPlan {

    /** 无动作。 */
    public static final int NONE = 0;
    /** 整包重载首页接口配置。 */
    public static final int RELOAD = 1;
    /** 只切换首页源 + 刷新首页布局。 */
    public static final int HOME = 2;
    /** 只预热直播配置。 */
    public static final int LIVE = 3;
    /** 只刷新历史列表（档位/同名合并开关变了）。 */
    public static final int HISTORY = 4;

    /** 变了就必须重新解析接口配置的键。 */
    public static final String[] RELOAD_KEYS = {
            HawkConfig.API_URL, HawkConfig.API_LINE_SOURCE,
    };

    /**
     * 只影响「用哪个站点做首页」的键。
     *
     * <p>{@code ApiConfig.parseJson} 里靠 {@code home_api} 选首页源，
     * 但接口配置本身没变 ⇒ 不必重新拉接口、不必重新解析，直接换源即可。</p>
     */
    public static final String[] HOME_KEYS = {
            HawkConfig.HOME_API,
    };

    /** 只影响直播的键：首页完全不用动。 */
    public static final String[] LIVE_KEYS = {
            HawkConfig.LIVE_API_URL, HawkConfig.EPG_URL,
    };

    /**
     * 只影响「历史列表」的键（v99-r47）。
     *
     * <p>历史档位与同名合并开关都是账号级设置、会跨设备同步；值写回 Hawk 之后
     * 必须让首页/历史页立刻按新口径重排，否则用户看到的是"设置改了但没反应"。</p>
     *
     * <p><b>刻意不做裁剪</b>：真实裁剪要等用户确认（设置页的二次确认弹窗）或下一次
     * 写历史时自然发生 —— 在这里删数据会把"调低后又调回"变成不可逆的误删。</p>
     */
    public static final String[] HISTORY_KEYS = {
            HawkConfig.HISTORY_NUM, HawkConfig.HISTORY_MERGE,
    };

    /** 快照长度：RELOAD + HOME + LIVE + HISTORY 四组键按此顺序排列。 */
    public static final int KEY_COUNT = RELOAD_KEYS.length + HOME_KEYS.length
            + LIVE_KEYS.length + HISTORY_KEYS.length;

    public boolean reloadConfig;
    public boolean refreshHome;
    public boolean prewarmLive;
    public boolean refreshHistory;

    /** 按固定顺序展开全部动作键，供 {@link #diff} 的前后两帧快照使用。 */
    public static String[] keys() {
        String[] out = new String[KEY_COUNT];
        int i = 0;
        for (String k : RELOAD_KEYS) {
            out[i++] = k;
        }
        for (String k : HOME_KEYS) {
            out[i++] = k;
        }
        for (String k : LIVE_KEYS) {
            out[i++] = k;
        }
        for (String k : HISTORY_KEYS) {
            out[i++] = k;
        }
        return out;
    }

    /** 键 → 动作分类；不在表里的键一律 {@link #NONE}（写 Hawk 即生效）。 */
    public static int classify(String key) {
        if (key == null) {
            return NONE;
        }
        for (String k : RELOAD_KEYS) {
            if (k.equals(key)) {
                return RELOAD;
            }
        }
        for (String k : HOME_KEYS) {
            if (k.equals(key)) {
                return HOME;
            }
        }
        for (String k : LIVE_KEYS) {
            if (k.equals(key)) {
                return LIVE;
            }
        }
        for (String k : HISTORY_KEYS) {
            if (k.equals(key)) {
                return HISTORY;
            }
        }
        return NONE;
    }

    public boolean isEmpty() {
        return !reloadConfig && !refreshHome && !prewarmLive && !refreshHistory;
    }

    public void merge(ConfigPlan o) {
        if (o == null) {
            return;
        }
        reloadConfig |= o.reloadConfig;
        refreshHome |= o.refreshHome;
        prewarmLive |= o.prewarmLive;
        refreshHistory |= o.refreshHistory;
    }

    public void set(int action) {
        switch (action) {
            case RELOAD:
                reloadConfig = true;
                break;
            case HOME:
                refreshHome = true;
                break;
            case LIVE:
                prewarmLive = true;
                break;
            case HISTORY:
                refreshHistory = true;
                break;
            default:
                break;
        }
    }

    /**
     * 比较前后两帧快照，得出需要执行哪些动作。
     *
     * <p>纯函数：只做字符串比较，不读写任何外部状态 ⇒ 可 JVM 单测。</p>
     *
     * @param before {@link #keys()} 顺序的旧值（null 视为空串）
     * @param after  {@link #keys()} 顺序的新值
     */
    public static ConfigPlan diff(String[] before, String[] after) {
        ConfigPlan p = new ConfigPlan();
        if (before == null || after == null) {
            return p;
        }
        int n = Math.min(before.length, after.length);
        int i = 0;
        for (int c = 0; c < RELOAD_KEYS.length && i < n; c++, i++) {
            if (!eq(before[i], after[i])) {
                p.reloadConfig = true;
            }
        }
        for (int c = 0; c < HOME_KEYS.length && i < n; c++, i++) {
            if (!eq(before[i], after[i])) {
                p.refreshHome = true;
            }
        }
        for (int c = 0; c < LIVE_KEYS.length && i < n; c++, i++) {
            if (!eq(before[i], after[i])) {
                p.prewarmLive = true;
            }
        }
        for (int c = 0; c < HISTORY_KEYS.length && i < n; c++, i++) {
            if (!eq(before[i], after[i])) {
                p.refreshHistory = true;
            }
        }
        return p;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        if (reloadConfig) {
            sb.append("reload ");
        }
        if (refreshHome) {
            sb.append("home ");
        }
        if (prewarmLive) {
            sb.append("live ");
        }
        if (refreshHistory) {
            sb.append("history ");
        }
        if (isEmpty()) {
            sb.append("none");
        }
        return sb.append(']').toString();
    }
}
