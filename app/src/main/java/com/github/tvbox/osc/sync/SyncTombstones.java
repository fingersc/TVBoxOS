package com.github.tvbox.osc.sync;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.util.List;

/**
 * 待上传的删除墓碑。
 *
 * <p>用户删除单条历史/收藏/进度时登记在这里，下一次同步随包上传；
 * 上传成功后清除。同步自身写入/清理数据时必须 {@link #suppress(boolean)}，
 * 否则会把"同步过程中的删除"误记成用户删除，传播到其它设备。</p>
 *
 * <p>协议 v4：每条墓碑同时记录 HLC（physical/counter），合并时只有 HLC 不高于墓碑的条目
 * 才会被删除；这样"删除后又在别的设备重新播放"不会复活失败，也不再依赖本机墙钟。</p>
 */
public final class SyncTombstones {

    /**
     * ★ v99-r7 P2-1：必须是 <b>ThreadLocal</b>，不能用静态 boolean。
     *
     * <p>原实现是 {@code private static boolean suppress}（进程级）。同步线程在
     * {@code applyLocal()} / {@code wipeLocal()} 期间置 true，而这两处会做全表 deleteAll
     * 重写（几十~几百 ms）；若此刻用户在 UI 线程删了一条历史，{@code addRecord()} 就会
     * 看到 suppress=true 而<b>跳过记墓碑</b> ⇒ 该删除不会同步出去 ⇒ 下次从云端"复活"。</p>
     *
     * <p>改成 ThreadLocal 后语义不变（同线程内的抑制照常生效），但不再跨线程污染。</p>
     */
    private static final ThreadLocal<Boolean> SUPPRESS = new ThreadLocal<>();

    /** 按账号命名空间取键：A 账号的删除墓碑绝不能混进 B 账号的同步包。 */
    private static String key() {
        String owner = AccountContext.ownerKey();
        return (owner == null || owner.length() == 0)
                ? HawkConfig.SYNC_TOMBS : HawkConfig.SYNC_TOMBS + "." + owner;
    }

    private SyncTombstones() {
    }

    public static void suppress(boolean value) {
        if (value) {
            SUPPRESS.set(Boolean.TRUE);
        } else {
            SUPPRESS.remove();
        }
    }

    public static boolean isSuppressed() {
        return Boolean.TRUE.equals(SUPPRESS.get());
    }

    public static void addRecord(String sourceKey, String vodId) {
        if (isSuppressed()) {
            return;
        }
        add("r", SyncMerge.recordKey(sourceKey, vodId), HlcClock.next());
    }

    /**
     * ★ 协议 v4：用调用方指定的 HLC 登记墓碑。
     *
     * <p>用于「同名去重淘汰 loser」这类<b>内部合并</b>：墓碑 HLC 必须取被删除条目自身的 HLC，
     * 而不是当前时钟 —— 否则墓碑会连 winner 一起压制（winner 的 HLC 低于当前时钟），
     * 下次合并整条记录消失。取 loser 的 HLC 则只会压制 loser 及其更旧版本。</p>
     */
    public static void addRecord(String sourceKey, String vodId, Hlc hlc) {
        if (isSuppressed()) {
            return;
        }
        add("r", SyncMerge.recordKey(sourceKey, vodId), hlc);
    }

    public static void addProgress(String sourceKey, String vodId, String flag, int index, String epName) {
        if (isSuppressed()) {
            return;
        }
        add("p", SyncMerge.progressKey(sourceKey, vodId, flag, index, epName), HlcClock.next());
    }

    public static void addCollect(String sourceKey, String vodId) {
        if (isSuppressed()) {
            return;
        }
        add("c", SyncMerge.collectKey(sourceKey, vodId), HlcClock.next());
    }

    private static synchronized void add(String type, String key, Hlc hlc) {
        try {
            JSONObject root = readRoot();
            JSONObject map = root.optJSONObject(type);
            if (map == null) {
                map = new JSONObject();
                root.put(type, map);
            }
            Hlc use = hlc == null ? HlcClock.next() : hlc;
            JSONObject v = new JSONObject();
            v.put("at", use.physical);
            v.put("p", use.physical);
            v.put("c", use.counter);
            map.put(key, v);
            Hawk.put(key(), root.toString());
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void fill(SyncPayload payload) {
        try {
            JSONObject root = readRoot();
            fillList(root.optJSONObject("r"), payload.deleted.records);
            fillList(root.optJSONObject("p"), payload.deleted.progress);
            fillList(root.optJSONObject("c"), payload.deleted.collects);
        } catch (Throwable ignored) {
        }
    }

    /** 上传成功后清除已随包上传的墓碑（按上传时间戳之前）。全部类型。 */
    public static synchronized void clear(long upTo) {
        clear(new Hlc(upTo, Integer.MAX_VALUE, ""), true, true, true);
    }

    /**
     * 只清除「本轮确实上传成功」的那几类墓碑，且以 HLC 水位（physical+counter）为准。
     *
     * <p>为什么不能一律 {@code clear(now)}：若某类 scope 本轮被关闭或失败，它的墓碑
     * 并没有随包上传，清掉就永久丢失删除意图，重新启用后云端旧数据会「删了又回来」。</p>
     *
     * <p>为什么按 HLC 而不是墙钟：同步过程中（或同一毫秒内）用户新登记的墓碑 physical
     * 可能等于 now，按 {@code at > now} 会被误清；按 (physical,counter) 比较则能保留
     * 水位之后新产生的墓碑。</p>
     */
    public static synchronized void clear(Hlc upTo, boolean doRecords,
                                          boolean doProgress, boolean doCollects) {
        try {
            JSONObject root = readRoot();
            JSONObject out = new JSONObject();
            out.put("r", doRecords
                    ? filterHlc(root.optJSONObject("r"), upTo)
                    : keepAll(root.optJSONObject("r")));
            out.put("p", doProgress
                    ? filterHlc(root.optJSONObject("p"), upTo)
                    : keepAll(root.optJSONObject("p")));
            out.put("c", doCollects
                    ? filterHlc(root.optJSONObject("c"), upTo)
                    : keepAll(root.optJSONObject("c")));
            Hawk.put(key(), out.toString());
        } catch (Throwable ignored) {
        }
    }

    private static JSONObject keepAll(JSONObject map) {
        return map == null ? new JSONObject() : map;
    }

    private static JSONObject filterHlc(JSONObject map, Hlc upTo) {
        JSONObject out = new JSONObject();
        if (map == null) {
            return out;
        }
        java.util.Iterator<String> it = map.keys();
        while (it.hasNext()) {
            String key = it.next();
            Object raw = map.opt(key);
            long p;
            int c;
            if (raw instanceof JSONObject) {
                p = ((JSONObject) raw).optLong("p", ((JSONObject) raw).optLong("at", 0L));
                c = ((JSONObject) raw).optInt("c", 0);
            } else {
                p = map.optLong(key, 0L);
                c = 0;
            }
            Hlc h = new Hlc(p, c, "");
            if (upTo == null || h.compareIgnoringDev(upTo) > 0) {
                try {
                    out.put(key, raw);
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static void fillList(JSONObject map, List<SyncPayload.Tomb> out) {
        if (map == null) {
            return;
        }
        java.util.Iterator<String> it = map.keys();
        while (it.hasNext()) {
            String key = it.next();
            Object raw = map.opt(key);
            long at = 0L;
            long p = 0L;
            int c = 0;
            if (raw instanceof JSONObject) {
                JSONObject o = (JSONObject) raw;
                at = o.optLong("at", 0L);
                p = o.optLong("p", 0L);
                c = o.optInt("c", 0);
            } else {
                // 兼容旧格式（key → at 数字）
                at = map.optLong(key, 0L);
                p = at;
                c = 0;
            }
            out.add(new SyncPayload.Tomb(key, at, p, c));
        }
    }

    private static JSONObject filter(JSONObject map, long upTo) {
        JSONObject out = new JSONObject();
        if (map == null) {
            return out;
        }
        java.util.Iterator<String> it = map.keys();
        while (it.hasNext()) {
            String key = it.next();
            Object raw = map.opt(key);
            long at;
            if (raw instanceof JSONObject) {
                at = ((JSONObject) raw).optLong("at", 0L);
            } else {
                at = map.optLong(key, 0L);
            }
            if (at > upTo) {
                try {
                    out.put(key, raw);
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static JSONObject readRoot() {
        try {
            String json = Hawk.get(key(), "");
            if (json != null && json.length() > 0) {
                return new JSONObject(json);
            }
        } catch (Throwable ignored) {
        }
        return new JSONObject();
    }
}
