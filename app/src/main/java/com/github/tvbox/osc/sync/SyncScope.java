package com.github.tvbox.osc.sync;

/** v98：同步范围（用于按 scope 标脏，避免"改一个设置也全量同步"）。 */
public enum SyncScope {
    HISTORY,
    PROGRESS,
    COLLECT,
    SETTINGS
}
