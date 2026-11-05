package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.cache.PlayProgressDao;
import com.github.tvbox.osc.cache.VodCollectDao;
import com.github.tvbox.osc.cache.VodRecordDao;
import com.github.tvbox.osc.data.AppDataManager;
import com.orhanobut.hawk.Hawk;

/**
 * 全局 HLC 分配器（每账号一份，持久化）。
 *
 * <p>保证同一设备上分配出的 HLC <b>严格单调递增</b>，即使系统时钟回拨/冻结：
 * 物理分量取 {@code max(trustedNow, lastPhysical)}，同毫秒内 counter+1。</p>
 *
 * <p>同时提供 {@link #observe(Hlc)}：每次读到远端包后把本地时钟推进到远端最大值，
 * 保证本机之后新产生的数据一定大于任何已见数据（这是 HLC 的接收规则，缺了它慢钟设备
 * 即使有了 HLC 仍会一直输）。</p>
 *
 * <p>启动/切换账号时从 Hawk 恢复，并与数据库里已有的最大 HLC 对账，避免进程被杀丢状态。</p>
 */
public final class HlcClock {

    private static final String PREFIX = "sync_hlc.";

    private static String sOwner;
    private static long sPhysical;
    private static int sCounter;
    private static boolean sLoaded;

    private HlcClock() {
    }

    private static synchronized void ensure() {
        String owner = AccountContext.ownerKey();
        owner = owner == null ? "" : owner;
        if (sLoaded && owner.equals(sOwner)) {
            return;
        }
        sOwner = owner;
        sPhysical = 0L;
        sCounter = 0;
        try {
            String s = Hawk.get(PREFIX + owner, "");
            if (s != null && s.length() > 0) {
                int i = s.indexOf(':');
                if (i > 0) {
                    sPhysical = parseLong(s.substring(0, i), 0L);
                    sCounter = (int) parseLong(s.substring(i + 1), 0L);
                }
            }
        } catch (Throwable ignored) {
        }
        reconcileFromDb();
        sLoaded = true;
    }

    /** 与 DB 中已有最大 HLC 对账（进程被杀/旧数据迁移后 Hawk 可能落后）。 */
    private static void reconcileFromDb() {
        try {
            AppDataManager.get();
            VodRecordDao rd = AppDataManager.get().getVodRecordDao();
            PlayProgressDao pd = AppDataManager.get().getPlayProgressDao();
            VodCollectDao cd = AppDataManager.get().getVodCollectDao();
            bumpMax(rd.maxHlcPhysical(), rd.maxHlcCounter());
            bumpMax(pd.maxHlcPhysical(), pd.maxHlcCounter());
            bumpMax(cd.maxHlcPhysical(), cd.maxHlcCounter());
        } catch (Throwable ignored) {
        }
    }

    private static void bumpMax(Long physical, Integer counter) {
        if (physical == null || physical <= 0) {
            return;
        }
        int c = counter == null ? 0 : counter;
        if (physical > sPhysical || (physical == sPhysical && c > sCounter)) {
            sPhysical = physical;
            sCounter = c;
        }
    }

    /** 分配一个新的、严格大于本设备所有已分配值的 HLC。 */
    public static synchronized Hlc next() {
        ensure();
        long now = TrustedClock.nowPhysical();
        long p = now > sPhysical ? now : sPhysical;
        int c = (p == sPhysical) ? sCounter + 1 : 0;
        sPhysical = p;
        sCounter = c;
        persist();
        return new Hlc(p, c, HistorySyncManager.deviceId());
    }

    /** HLC 接收规则：把本地时钟推进到不小于远端（物理, 计数）。 */
    public static synchronized void observe(Hlc remote) {
        if (remote == null) {
            return;
        }
        ensure();
        boolean changed = false;
        if (remote.physical > sPhysical) {
            sPhysical = remote.physical;
            sCounter = remote.counter;
            changed = true;
        } else if (remote.physical == sPhysical && remote.counter > sCounter) {
            sCounter = remote.counter;
            changed = true;
        }
        if (changed) {
            persist();
        }
    }

    public static synchronized void observe(long physical, int counter) {
        if (physical <= 0 && counter <= 0) {
            return;
        }
        observe(new Hlc(physical, counter, ""));
    }

    /** 当前水位（不分配）。 */
    public static synchronized Hlc peek() {
        ensure();
        return new Hlc(sPhysical, sCounter, HistorySyncManager.deviceId());
    }

    /** 账号切换时调用，强制下次重新加载。 */
    public static synchronized void reset() {
        sLoaded = false;
        sOwner = null;
        sPhysical = 0L;
        sCounter = 0;
    }

    private static void persist() {
        try {
            Hawk.put(PREFIX + sOwner, sPhysical + ":" + sCounter);
        } catch (Throwable ignored) {
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
