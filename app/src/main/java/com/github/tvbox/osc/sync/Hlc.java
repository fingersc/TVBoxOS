package com.github.tvbox.osc.sync;

/**
 * 混合逻辑时钟（Hybrid Logical Clock）值对象。
 *
 * <p>比较顺序固定为 {@code (physical, counter, dev)}，与并发的设备无关：
 * 两端对同一批数据一定算出同一个胜负，不会再出现"谁最后写谁说了算"的墙钟漂移问题。</p>
 *
 * <p>{@code physical} 使用 {@link TrustedClock#nowPhysical()}（本机时钟 + 云端 Date 偏移），
 * {@code counter} 解决同一毫秒内的多次写入，{@code dev} 是最终确定性裁决。</p>
 *
 * <p>不可变线程安全。</p>
 */
public final class Hlc implements Comparable<Hlc> {

    public static final Hlc ZERO = new Hlc(0L, 0, "");

    public final long physical;
    public final int counter;
    public final String dev;

    public Hlc(long physical, int counter, String dev) {
        this.physical = physical;
        this.counter = counter;
        this.dev = dev == null ? "" : dev;
    }

    public boolean isZero() {
        return physical <= 0 && counter <= 0;
    }

    @Override
    public int compareTo(Hlc o) {
        if (o == null) {
            return 1;
        }
        return compare(physical, counter, dev, o.physical, o.counter, o.dev);
    }

    public boolean after(Hlc o) {
        return o == null || compareTo(o) > 0;
    }

    /** 只比较物理分量与逻辑计数，忽略设备 id（用于墓碑水位/收敛判断）。 */
    public int compareIgnoringDev(Hlc o) {
        if (o == null) {
            return 1;
        }
        return compare(physical, counter, "", o.physical, o.counter, "");
    }

    /** 三元组比较：physical → counter → dev（字典序）。null dev 视为空串。 */
    public static int compare(long p1, int c1, String d1, long p2, int c2, String d2) {
        if (p1 != p2) {
            return p1 < p2 ? -1 : 1;
        }
        if (c1 != c2) {
            return c1 < c2 ? -1 : 1;
        }
        String a = d1 == null ? "" : d1;
        String b = d2 == null ? "" : d2;
        return a.compareTo(b);
    }

    /** 用条目字段（可能为旧数据：hlcPhysical=0）构造 HLC，旧数据回落到 updateTime。 */
    public static Hlc of(long hlcPhysical, int hlcCounter, String dev, long legacyTime) {
        long p = hlcPhysical > 0 ? hlcPhysical : legacyTime;
        return new Hlc(p, hlcCounter, dev);
    }

    @Override
    public String toString() {
        return physical + ":" + counter + ":" + dev;
    }
}
