package com.github.tvbox.osc.sync;

/**
 * v98：同步范围（用于按 scope 标脏，避免"改一个设置也全量同步"）。
 *
 * <p>★ v99-r8：云端改为**按 scope 分文件**存储（原来是单个 data.bin 全量上传）。
 * 每个 scope 一个文件、一个独立 ETag —— 只上传脏的那几个，单次同步流量约为原来的 1/3。
 * <b>枚举顺序不可调整</b>：{@code SyncState.dirty[]} / {@code etags[]} 用它做下标。</p>
 */
public enum SyncScope {
    HISTORY("history.bin"),
    PROGRESS("progress.bin"),
    COLLECT("collects.bin"),
    SETTINGS("settings.bin");

    /** 云端文件名（在账号目录下）。 */
    public final String fileName;

    /**
     * 显式下标，用于 {@code SyncState.dirty[]} / {@code etags[]}。
     *
     * <p>★ v99-r8：代码里一律用 {@link #index}，不再直接写 {@code ordinal()} ——
     * 避免「枚举顺序一改、Hawk 里持久化的数组就整体错位」这种隐性依赖。
     * 当前两者数值相同，但语义上一个是"持久化键"，一个是"声明顺序"。</p>
     */
    public final int index;

    SyncScope(String fileName) {
        this.fileName = fileName;
        this.index = ordinal();
    }

    public static final int COUNT = 4;
}
