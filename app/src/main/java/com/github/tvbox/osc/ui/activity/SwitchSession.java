package com.github.tvbox.osc.ui.activity;

import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.SwitchBudget;

/**
 * 一轮切源的显式状态与预算计数（r146 / P2）。
 *
 * <p>只收口「一轮」的相位时钟、自动续切次数、对齐续切次数与轮次起点；
 * 其余业务字段仍留在 DetailActivity，避免一次性搬动 65 个字段带来的并发风险。
 * 所有方法都在主线程/现有回调线程上调用，内部不再另起线程。</p>
 */
final class SwitchSession {

    enum Phase {
        IDLE, SNAPSHOT, POOL, PICKING, LOADING_DETAIL, MATCHING, MIGRATING, PLAYING
    }

    private Phase phase = Phase.IDLE;
    private long phaseStartedAt = 0L;
    private long startedAt = 0L;
    private int failoverCount = 0;
    private int alignFailover = 0;
    private long roundStartMs = 0L;

    /** 进入新阶段；manual=true 时不标记超总预算。 */
    synchronized void enter(Phase p, boolean manual) {
        if (p == null) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (p == phase) {
            phaseStartedAt = now;
            return;
        }
        long cost = phaseStartedAt == 0L ? 0L : (now - phaseStartedAt);
        if (phase == Phase.IDLE || p == Phase.SNAPSHOT) {
            startedAt = now;
        }
        long total = startedAt == 0L ? 0L : (now - startedAt);
        LOG.sw("[FB] phase " + phase + " -> " + p
                + " costMs=" + cost + " totalMs=" + total
                + (manual ? " (manual)"
                : (total > SwitchBudget.TOTAL_BUDGET_MS ? " (OVER_BUDGET)" : "")));
        phase = p;
        phaseStartedAt = now;
    }

    /** 彻底复位：相位、时钟、两类预算计数一起归零。 */
    synchronized void reset() {
        phase = Phase.IDLE;
        phaseStartedAt = 0L;
        startedAt = 0L;
        failoverCount = 0;
        alignFailover = 0;
        roundStartMs = 0L;
    }

    /** 开新一轮：计数与轮次起点归零（保留相位）。 */
    synchronized void beginRound() {
        failoverCount = 0;
        alignFailover = 0;
        roundStartMs = System.currentTimeMillis();
    }

    /** 一轮收尾：只清计数与轮次起点，不改相位。 */
    synchronized void endRound() {
        failoverCount = 0;
        alignFailover = 0;
        roundStartMs = 0L;
    }

    synchronized int failoverCount() {
        return failoverCount;
    }

    /** r209：供「相位停摆」看门狗读取当前相位（此前无 getter，兜底逻辑无法判断卡在哪一步）。 */
    synchronized Phase phase() {
        return phase;
    }

    /** r209：当前相位已持续多久（ms），用于停摆判定。 */
    synchronized long phaseAgeMs() {
        return phaseStartedAt == 0L ? 0L : (android.os.SystemClock.elapsedRealtime() - phaseStartedAt);
    }

    synchronized boolean canFailover(int max) {
        return failoverCount < max;
    }

    synchronized int noteFailover() {
        return ++failoverCount;
    }

    synchronized int alignFailover() {
        return alignFailover;
    }

    synchronized boolean canAlignFailover(int max, long maxMs) {
        return alignFailover < max
                && (roundStartMs <= 0L || System.currentTimeMillis() - roundStartMs < maxMs);
    }

    synchronized int noteAlignFailover() {
        return ++alignFailover;
    }
}
