package com.github.tvbox.osc.util;

/**
 * 切源一轮的预算参数集中处（r146 / P2）。
 *
 * <p>原先这些常量散落在 DetailActivity 顶部，与实际使用点相隔数百行；新增/调整一个超时
 * 需要跨文件找齐所有引用。这里只集中「一轮切源」相关的预算，不改任何取值与语义：
 * 调用方仍按原常量名使用，避免一次性大改行为。</p>
 */
public final class SwitchBudget {

    private SwitchBudget() {
    }

    /** 切源搜索的全局兜底超时。 */
    public static final long SEARCH_TIMEOUT_MS = 8000L;
    /** 搜索期间被挡下的补位重试延迟。 */
    public static final long BUSY_REFILL_DELAY_MS = 300L;
    /** 详情成功后攒池的启动延迟。 */
    public static final long PREHEAT_DELAY_MS = 1500L;
    /** 攒池任务硬闸。 */
    public static final long POOL_FILL_TIMEOUT_MS = 120000L;
    /** 第一个候选落池后的提前消费窗口。 */
    public static final long FIRST_CONSUME_DELAY_MS = 1200L;
    /** 单次详情加载超时。 */
    public static final long DETAIL_TIMEOUT_MS = 6000L;
    /** 探路请求超时。 */
    public static final long PROBE_TIMEOUT_MS = 2500L;
    /** 单源超时。 */
    public static final long SOURCE_TIMEOUT_MS = 3500L;
    /** 一轮切源因候选不可用自动续切的上限。 */
    public static final int MAX_FAILOVER = 3;
    /** 一轮切源因「该源没有该集」续切的上限。 */
    public static final int MAX_ALIGN_FAILOVER = 12;
    /** 「该源没有该集」续切的总时间预算。 */
    public static final long ALIGN_MAX_MS = 20000L;
    /** 一轮切源总预算（用于观测，不强制中断）。 */
    public static final long TOTAL_BUDGET_MS = 90000L;
}
