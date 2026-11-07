package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

/**
 * 同步状态统一对象（v98 P1）。
 *
 * <p>替换原先散落的 6 个 Hawk 键（sync_last_at / sync_last_epoch / sync_dirty /
 * sync_fail_count / sync_fail_until 等），并新增条件请求所需的 ETag 与自适应间隔档位。</p>
 *
 * <p>序列化到 Hawk：{@code "sync_state." + owner}（每账号一份）。</p>
 */
public final class SyncState {

    private static final String PREFIX = "sync_state.";
    private static final String MIGRATED = "sync_state_migrated.";

    public String owner;
    /** 上次成功同步时间 */
    public long lastAt;
    /** 上次同步时的云端 epoch */
    public long lastEpoch;
    /**
     * 旧的单文件 ETag（schema ≤2 的 data.bin）。
     *
     * <p>★ v99-r8 起云端按 scope 分文件，改用 {@link #etags}；
     * 本字段仅为兼容旧 Hawk 数据保留，不再参与条件请求。</p>
     */
    public String etag;
    /**
     * ★ v99-r8：各 scope 文件的 ETag（下标 = {@link SyncScope#ordinal()}）。
     *
     * <p>拆文件后每个文件有自己的版本号，条件请求要逐文件带自己的 ETag ——
     * 否则改一个收藏就把历史也整包重传，拆文件等于白拆。</p>
     */
    public String[] etags = new String[SyncScope.COUNT];
    /** 按 scope 的脏标记：history / progress / collect / settings */
    public boolean[] dirty = new boolean[4];
    /** 连续失败次数 */
    public int failCount;
    /** 熔断到期时间（此前不发任何请求） */
    public long failUntil;
    /** 自适应间隔档位（0=5min … 3=30min） */
    public int intervalIdx;
    /**
     * ★ v99-r8：是否已把旧单文件 {@code data.bin} 迁移成 4 个 scope 文件。
     *
     * <p>迁移成功后置 true，之后不再读 data.bin（文件保留作人工备份）。
     * 账号切换/清空缓存不会清这个标记 —— 它描述的是「云端格式是否已升级」。</p>
     */
    public boolean splitMigrated;
    /**
     * ★ v4.1：每个 scope 最近一次成功上传时的 HLC 水位。
     * 启动时与 DB/云端实际最大 HLC 对账，若本机有更新的数据则补标脏，
     * 避免「上次上传成功但 dirty 因异常路径没清」或「同步期间新写入被旧快照清掉」造成漏传。
     */
    public long[] pushedHlcPhysical = new long[4];
    public int[] pushedHlcCounter = new int[4];
    /** v4.1：sync.meta 的条件请求 ETag */
    public String metaEtag;
    /** v4.1：sync.meta 最近一次内容缓存（ETag 未变时避免重复下载） */
    public String metaJson;
    /**
     * ★ v4.3.0（r180-A）：各 scope 最近一次「配对观测」到的远端内容指纹（规范化 JSON 的 SHA-256）。
     *
     * <p>语义严格是「在 {@link #etags} 里那个 ETag 下，远端内容是这个指纹」——
     * 它必须和 ETag 来自<b>同一次观测</b>，否则会误判"云端就是我的内容"而跳过下载。
     * 配对规则写在 {@code SyncEngine} 的上传段；这里只负责存。</p>
     */
    public String[] remoteHash = new String[SyncScope.COUNT];
    /** ★ v4.3.0（r180-A）：与 {@link #remoteHash} 同一次观测到的密文字节数（上传后配对校验用）。 */
    public long[] remoteSize = new long[SyncScope.COUNT];

    public SyncState() {
    }

    public SyncState(String owner) {
        this.owner = owner;
    }

    // ---------------------------------------------------------------- 读写

    public static SyncState load(String owner) {
        try {
            SyncState st = Hawk.get(PREFIX + owner);
            if (st != null) {
                if (st.dirty == null || st.dirty.length != 4) {
                    st.dirty = new boolean[4];
                }
                // v99-r8：旧数据没有 etags 字段（Gson 给 null）或长度不符 ⇒ 重建
                if (st.etags == null || st.etags.length != SyncScope.COUNT) {
                    st.etags = new String[SyncScope.COUNT];
                }
                if (st.pushedHlcPhysical == null || st.pushedHlcPhysical.length != 4) {
                    st.pushedHlcPhysical = new long[4];
                }
                if (st.pushedHlcCounter == null || st.pushedHlcCounter.length != 4) {
                    st.pushedHlcCounter = new int[4];
                }
                // ★ v4.3.0：老数据没有指纹字段（Gson 给 null）⇒ 重建，等价于"下轮做一次完整 GET"
                if (st.remoteHash == null || st.remoteHash.length != SyncScope.COUNT) {
                    st.remoteHash = new String[SyncScope.COUNT];
                }
                if (st.remoteSize == null || st.remoteSize.length != SyncScope.COUNT) {
                    st.remoteSize = new long[SyncScope.COUNT];
                }
                return st;
            }
        } catch (Throwable ignored) {
        }
        return new SyncState(owner);
    }

    // ---------------------------------------------------------------- 分 scope 的 ETag

    /** 取某个 scope 的 ETag（无则返回 null）。 */
    public String getEtag(SyncScope s) {
        if (s == null || etags == null || s.index >= etags.length) {
            return null;
        }
        return etags[s.index];
    }

    /** 设置某个 scope 的 ETag（null/空串表示清除）。 */
    public void setEtag(SyncScope s, String value) {
        if (s == null) {
            return;
        }
        if (etags == null || etags.length != SyncScope.COUNT) {
            etags = new String[SyncScope.COUNT];
        }
        String next = (value == null || value.isEmpty()) ? null : value;
        String old = etags[s.index];
        etags[s.index] = next;
        // ★ v4.3.0：ETag 一变，之前记的指纹就不再对应这份内容 ⇒ 立刻作废。
        //   这条不变式是「跳过下载」的安全底线，别在别处绕过它直接写 etags[]。
        if (old == null ? next != null : !old.equals(next)) {
            ensureRemote();
            remoteHash[s.index] = null;
            remoteSize[s.index] = 0L;
        }
    }

    // ------------------------------------------------ ★ v4.3.0 远端内容指纹（r180-A）

    private void ensureRemote() {
        if (remoteHash == null || remoteHash.length != SyncScope.COUNT) {
            remoteHash = new String[SyncScope.COUNT];
        }
        if (remoteSize == null || remoteSize.length != SyncScope.COUNT) {
            remoteSize = new long[SyncScope.COUNT];
        }
    }

    /** 该 scope 记下的远端内容指纹（无则 null ⇒ 必须走完整 GET）。 */
    public String remoteHash(SyncScope s) {
        if (s == null) {
            return null;
        }
        ensureRemote();
        return remoteHash[s.index];
    }

    /** 该 scope 记下的远端密文长度（0 表示未知）。 */
    public long remoteSize(SyncScope s) {
        if (s == null) {
            return 0L;
        }
        ensureRemote();
        return remoteSize[s.index];
    }

    /**
     * 记下「在这个 ETag 下，远端内容是这份指纹」。
     *
     * <p>调用方必须保证三者来自同一次观测（GET 响应头 + 解码结果、304 继承、
     * PUT 响应头 + 刚发出的字节，或上传后 PROPFIND + 长度校验）。</p>
     */
    public void setRemoteSnapshot(SyncScope s, String etag, String hash, long size) {
        if (s == null) {
            return;
        }
        setEtag(s, etag);
        ensureRemote();
        remoteHash[s.index] = (hash == null || hash.isEmpty()) ? null : hash;
        remoteSize[s.index] = Math.max(0L, size);
    }

    /** 作废某个 scope 的远端指纹（配对失败 / 云端异常时用）。 */
    public void clearRemoteSnapshot(SyncScope s) {
        if (s == null) {
            return;
        }
        ensureRemote();
        remoteHash[s.index] = null;
        remoteSize[s.index] = 0L;
    }

    public Hlc pushedHlc(SyncScope s) {
        if (s == null) {
            return Hlc.ZERO;
        }
        ensurePushed();
        return new Hlc(pushedHlcPhysical[s.index], pushedHlcCounter[s.index], "");
    }

    public void setPushedHlc(SyncScope s, Hlc h) {
        if (s == null) {
            return;
        }
        ensurePushed();
        if (h == null) {
            pushedHlcPhysical[s.index] = 0L;
            pushedHlcCounter[s.index] = 0;
            return;
        }
        pushMax(s.index, h.physical, h.counter);
    }

    public void bumpPushedHlc(SyncScope s, long physical, int counter) {
        if (s == null) {
            return;
        }
        ensurePushed();
        pushMax(s.index, physical, counter);
    }

    private void pushMax(int idx, long physical, int counter) {
        if (physical > pushedHlcPhysical[idx]
                || (physical == pushedHlcPhysical[idx] && counter > pushedHlcCounter[idx])) {
            pushedHlcPhysical[idx] = physical;
            pushedHlcCounter[idx] = counter;
        }
    }

    private void ensurePushed() {
        if (pushedHlcPhysical == null || pushedHlcPhysical.length != 4) {
            pushedHlcPhysical = new long[4];
        }
        if (pushedHlcCounter == null || pushedHlcCounter.length != 4) {
            pushedHlcCounter = new int[4];
        }
    }

    public void save() {
        try {
            Hawk.put(PREFIX + owner, this);
        } catch (Throwable ignored) {
        }
    }

    /** v98 P2：该账号是否已经有过 SyncState 记录（用于决定能否读旧散键兜底）。 */
    public static boolean exists(String owner) {
        if (owner == null || owner.isEmpty()) {
            return false;
        }
        try {
            return Hawk.contains(PREFIX + owner);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- scope

    /** v98 P2 起按 scope 标脏；P1 阶段等价于全局脏。 */
    public void markDirty(SyncScope... scopes) {
        if (scopes == null || scopes.length == 0) {
            for (int i = 0; i < dirty.length; i++) dirty[i] = true;
            return;
        }
        for (SyncScope s : scopes) {
            if (s != null && s.index < dirty.length) dirty[s.index] = true;
        }
    }

    public boolean isDirty(SyncScope s) {
        return s != null && s.index < dirty.length && dirty[s.index];
    }

    public boolean anyDirty() {
        for (boolean b : dirty) {
            if (b) return true;
        }
        return false;
    }

    public void clearDirty() {
        for (int i = 0; i < dirty.length; i++) dirty[i] = false;
    }

    // ---------------------------------------------------------------- 退避

    /** 1 次失败 30s；2 次 2min（×2 + 抖动）；≥3 次 15min */
    public void noteFailure() {
        failCount++;
        long base;
        if (failCount <= 1) base = 30 * 1000L;
        else if (failCount == 2) base = 2 * 60 * 1000L;
        else base = 15 * 60 * 1000L;
        long jitter = (long) (Math.random() * (base / 4L + 1L));
        failUntil = System.currentTimeMillis() + base + jitter;
    }

    /** 认证失败：直接熔断 15min，不再重试 */
    public void noteAuthFailure() {
        failCount = 3;
        failUntil = System.currentTimeMillis() + 15 * 60 * 1000L;
    }

    public void clearFailure() {
        failCount = 0;
        failUntil = 0L;
    }

    /**
     * ★ v99-r10 P3-4：连续失败达到这个次数就<b>彻底停止自动同步</b>，只等用户手动同步。
     */
    public static final int MAX_FAIL_BEFORE_MANUAL = 10;

    public boolean inBackoff() {
        // ★ P3-4：连续失败到上限 ⇒ 永久熔断，直到用户手动同步（或重新登录）把它清零。
        //   原来是 failCount>=3 就固定 15 分钟、**永远不停**：网盘密码改了但 App 里没改时，
        //   会每 15 分钟发一次注定失败的请求，一直白耗电和流量。
        if (failCount >= MAX_FAIL_BEFORE_MANUAL) {
            return true;
        }
        return System.currentTimeMillis() < failUntil;
    }

    /** 是否已进入「需要手动同步才能恢复」的状态（供账号页提示）。 */
    public boolean needsManualSync() {
        return failCount >= MAX_FAIL_BEFORE_MANUAL;
    }

    // ---------------------------------------------------------------- 迁移

    /**
     * v98 P1：把旧的散键状态迁到 SyncState，只执行一次（带标记位）。
     *
     * <p>旧 {@code sync_dirty} 为 true 时保守地把 4 个 scope 全标脏 —— 宁可多同步一次，
     * 也不要漏同步。</p>
     */
    public static void migrateLegacyKeys(String owner) {
        try {
            if (Hawk.get(MIGRATED + owner, false)) {
                return;
            }
            SyncState st = new SyncState(owner);
            st.lastAt = readLong(HawkConfig.SYNC_LAST_AT + "." + owner, -1L);
            if (st.lastAt < 0) {
                st.lastAt = Hawk.get(HawkConfig.SYNC_LAST_AT, 0L);
            }
            st.lastEpoch = readLong(HawkConfig.SYNC_LAST_EPOCH + "." + owner, -1L);
            if (st.lastEpoch < 0) {
                st.lastEpoch = Hawk.get(HawkConfig.SYNC_LAST_EPOCH, 0L);
            }
            boolean legacyDirty = Hawk.get(HawkConfig.SYNC_DIRTY + "." + owner, false)
                    || Hawk.get(HawkConfig.SYNC_DIRTY, false);
            if (legacyDirty) {
                st.markDirty();
            }
            st.failCount = Hawk.get(HawkConfig.SYNC_FAIL_COUNT + "." + owner, 0);
            st.failUntil = readLong(HawkConfig.SYNC_FAIL_UNTIL + "." + owner, 0L);
            // 旧熔断键必须清零：否则升级后在途的 legacy failUntil 会继续挡住同步
            Hawk.put(HawkConfig.SYNC_FAIL_UNTIL + "." + owner, 0L);
            Hawk.put(HawkConfig.SYNC_FAIL_UNTIL, 0L);
            Hawk.put(HawkConfig.SYNC_FAIL_COUNT + "." + owner, 0);
            st.save();
            Hawk.put(MIGRATED + owner, true);
            LOG.i("[Sync] state migrated owner=" + owner + " lastAt=" + st.lastAt
                    + " dirty=" + legacyDirty);
        } catch (Throwable t) {
            // 迁移失败不阻塞启动：下次同步照常工作，只是少一次 304 优化
            LOG.e("[Sync] state migrate failed", t);
        }
    }

    private static long readLong(String key, long def) {
        try {
            return Hawk.get(key, def);
        } catch (Throwable t) {
            return def;
        }
    }
}
