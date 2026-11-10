package com.github.tvbox.osc.server;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.M3u8PrefetchPlan;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PrefetchController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.orhanobut.hawk.Hawk;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * A′ 方案核心：本地分片代理 + 跨切片滚动预取。
 *
 * <p>工作方式：</p>
 * <ol>
 *   <li>播放开始解析 m3u8 时，{@link #prepare} 把切片行改写成本地
 *       {@code /seg?sk=<sessionKey>&i=<序号>}（见 {@link M3u8PrefetchPlan}）；</li>
 *   <li>播放器请求某一 {@code /seg} 时：该片已下载完 ⇒ 直接回整片；正在下载 ⇒
 *       <b>边下边发</b>（chunked，首字节到就返回）；没下 ⇒ 最多等
 *       {@link #FIRST_BYTE_WAIT_MS} 拿首字节，拿不到就 302 回源。
 *       窗口宽度由 {@link PrefetchController} 自适应；</li>
 *   <li>任何异常/超时/不支持 → {@link SegResult#redirect} 让播放器 302 回源，等同于旧行为，
 *       绝不让「加速」成为新的故障点。</li>
 * </ol>
 */
public final class PrefetchManager {

    /** 单设备同时在飞的切片上限。 */
    public static final int MAX_GLOBAL_INFLIGHT = 6;
    /** 单 host 同时在飞的切片上限。 */
    public static final int MAX_PER_HOST = 4;
    /** 每个播放会话的切片缓存上限（字节）。 */
    public static final long SESSION_MEMORY_BUDGET = 16L * 1024 * 1024;
    /**
     * r201：预取缓冲（播放头之前的就绪切片）允许占用的字节上限。
     *
     * <p>与会话总预算分开：总预算里还要给「正在下/正在发的那一片」留位置。</p>
     *
     * <p>r202：8MB → 12MB。取舍是「复用优先」——多留几片，回跳/切播放器时能直接命中，
     * 不必重新向源站要（实测一次 Exo→IJK 切换白重下了约 61MB）。会话总预算仍是
     * {@link #SESSION_MEMORY_BUDGET} 16MB 的硬顶，这里只是把「预取可占的部分」放宽。</p>
     */
    private static final long PREFETCH_MEM_BUDGET = 12L * 1024 * 1024;

    /**
     * r202：预取并发上限（与自适应窗口 W <b>解耦</b>）。
     *
     * <p>W 的语义是「开几条连接才喂得饱码率」，源站越快 W 越小（实测常掉到 1）。
     * 但预取要的是「把深度尽快铺满」，用 W 当并发会让深度只能一片一片串行推进：
     * 深度 4 片 × 每片 3s 下载 = 12s 才能铺满，期间播放头早就把缓冲吃掉了。
     * 所以预取并发单独给一个值，只受 {@link #MAX_PER_HOST}（要给播放头留一个名额）
     * 与近期错误情况约束。</p>
     */
    private static final int PREFETCH_CONCURRENCY = 3;
    /** 单个切片下落上限（防御异常大文件）。 */
    private static final int MAX_SEGMENT_BYTES = 4 * 1024 * 1024;

    private static final int MAX_SESSIONS = 2;
    /** 首字节等待上限：拿不到就 302 回源，绝不把播放器卡在本地代理上。 */
    private static final long FIRST_BYTE_WAIT_MS = 1500;
    /** 单个切片下载的墙钟上限（慢速滴流服务器保护）。 */
    private static final long SEG_DOWNLOAD_DEADLINE_MS = 45000;

    private static volatile float playbackSpeed = 1.0f;

    private static volatile PrefetchManager instance;

    /**
     * r200：当前播放使用的播放器标签（ijk / exo / ext），用于把分片度量分播放器归集。
     * 由 {@code PlayFragment.goPlayUrl} 在选定播放器后写入。
     */
    private static volatile String playerTag = "?";

    /**
     * r200：分片度量（服务端口径，Exo / IJK 通用）。
     *
     * <p>放在服务端而不是播放器侧的理由：ffmpeg（IJK）用自己的 HTTP 栈发请求，
     * 不经过 OkHttp 拦截器，客户端日志里<b>完全看不到</b> /seg 请求；
     * 只有在代理服务端记账，才能对两种播放器用同一把尺子比较。</p>
     */
    public static final class SegStat {
        /** 播放器请求的切片数。 */
        final AtomicLong req = new AtomicLong();
        /** 命中内存缓冲，直接 200/206 回整片。 */
        final AtomicLong hit = new AtomicLong();
        /** 等到首字节后边下边发（chunked）。 */
        final AtomicLong stream = new AtomicLong();
        /** 302 回源（本地拿不到）。 */
        final AtomicLong redirect = new AtomicLong();
        /** 预取提交过的下载任务数。 */
        final AtomicLong dlReq = new AtomicLong();
        /** 下载成功 / 失败。 */
        final AtomicLong dlOk = new AtomicLong();
        final AtomicLong dlFail = new AtomicLong();
        /** 预取下载的字节数。 */
        final AtomicLong dlBytes = new AtomicLong();
        /** 等待首字节的耗时（毫秒）。 */
        final AtomicLong waitMs = new AtomicLong();
        final AtomicLong waitMaxMs = new AtomicLong();
        /** 命中缓存直接回给播放器的字节数。 */
        final AtomicLong servedBytes = new AtomicLong();
        /** r202：重复下载的字节数（同一片在本会话里被下过两次以上，例如回跳/切播放器）。 */
        final AtomicLong dupBytes = new AtomicLong();

        void addWait(long ms) {
            if (ms <= 0) {
                return;
            }
            waitMs.addAndGet(ms);
            long old;
            do {
                old = waitMaxMs.get();
                if (ms <= old) {
                    break;
                }
            } while (!waitMaxMs.compareAndSet(old, ms));
        }

        void merge(SegStat o) {
            req.addAndGet(o.req.get());
            hit.addAndGet(o.hit.get());
            stream.addAndGet(o.stream.get());
            redirect.addAndGet(o.redirect.get());
            dlReq.addAndGet(o.dlReq.get());
            dlOk.addAndGet(o.dlOk.get());
            dlFail.addAndGet(o.dlFail.get());
            dlBytes.addAndGet(o.dlBytes.get());
            servedBytes.addAndGet(o.servedBytes.get());
            dupBytes.addAndGet(o.dupBytes.get());
            waitMs.addAndGet(o.waitMs.get());
            long old;
            do {
                old = waitMaxMs.get();
                if (o.waitMaxMs.get() <= old) {
                    break;
                }
            } while (!waitMaxMs.compareAndSet(old, o.waitMaxMs.get()));
        }

        /** 一行摘要，便于在日志里横向比较 Exo 与 IJK。 */
        public String summary() {
            long r = req.get();
            long w = waitMs.get();
            double mb = dlBytes.get() / 1024.0 / 1024.0;
            return "req=" + r
                    + " hit=" + hit.get()
                    + " stream=" + stream.get()
                    + " redir=" + redirect.get()
                    + " dl=" + dlOk.get() + "/" + (dlOk.get() + dlFail.get())
                    + " dlMB=" + String.format(java.util.Locale.US, "%.1f", mb)
                    + " waitAvg=" + (r > 0 ? (w / r) : 0) + "ms"
                    + " waitMax=" + waitMaxMs.get() + "ms"
                    + " servedMB=" + String.format(java.util.Locale.US, "%.1f", servedBytes.get() / 1024.0 / 1024.0)
                    + " dupMB=" + String.format(java.util.Locale.US, "%.1f", dupBytes.get() / 1024.0 / 1024.0);
        }
    }

    /** 全局（按播放器标签分桶）的分片度量。 */
    private static final Map<String, SegStat> globalStats = new ConcurrentHashMap<>();

    /** 取（或建）某个播放器标签对应的全局度量桶。 */
    private static SegStat globalStat(String tag) {
        SegStat s = globalStats.get(tag);
        if (s == null) {
            synchronized (globalStats) {
                s = globalStats.get(tag);
                if (s == null) {
                    s = new SegStat();
                    globalStats.put(tag, s);
                }
            }
        }
        return s;
    }

    /** r200：设置当前播放器标签（ijk / exo / ext），用于度量分桶。 */
    public void setPlayerTag(String tag) {
        if (tag != null && !tag.isEmpty()) {
            playerTag = tag;
        }
    }

    public static PrefetchManager get() {
        if (instance == null) {
            synchronized (PrefetchManager.class) {
                if (instance == null) {
                    instance = new PrefetchManager();
                }
            }
        }
        return instance;
    }

    /** 功能开关：默认开启，可在设置里关闭。 */
    public static boolean isEnabled() {
        try {
            return Hawk.get(HawkConfig.M3U8_PREFETCH, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 播放器切换倍速时同步（需求按倍速放大）。 */
    public static void setPlaybackSpeed(float speed) {
        if (speed > 0 && speed < 8) {
            playbackSpeed = speed;
        }
    }

    private final OkHttpClient http;
    private final ExecutorService pool;
    private final Semaphore global = new Semaphore(MAX_GLOBAL_INFLIGHT, true);
    private final Map<String, Semaphore> hostSem = new ConcurrentHashMap<>();

    /** 会话表（访问序，便于淘汰最久未用的会话）。 */
    private final Map<String, Session> sessions =
            Collections.synchronizedMap(new LinkedHashMap<String, Session>(4, 0.75f, true));

    private PrefetchManager() {
        OkHttpClient base;
        try {
            base = OkGoHelper.getItvClient();
        } catch (Throwable t) {
            base = null;
        }
        OkHttpClient.Builder b = base != null ? base.newBuilder() : new OkHttpClient.Builder();
        http = b.connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        final AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(12, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "seg-prefetch-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** 播放列表改写结果（供上层放进 /proxyM3u8）。 */
    public static final class Prepared {
        public String rewritten;
    }

    /** /seg 的响应数据。 */
    public static final class SegResult {
        public int code;              // 200/206/302/404
        public String mime;
        public byte[] data;
        /** 非 null 时走 chunked 边下边发；与 {@link #data} 二选一。 */
        public InputStream stream;
        public boolean partial;
        public long rangeStart;
        public long rangeEnd;
        public long rangeTotal;
        public String redirectUrl;

        static SegResult full(String mime, byte[] data) {
            SegResult r = new SegResult();
            r.code = 200;
            r.mime = mime;
            r.data = data;
            r.rangeTotal = data.length;
            return r;
        }

        static SegResult partial(String mime, byte[] data, long start, long end, long total) {
            SegResult r = new SegResult();
            r.code = 206;
            r.mime = mime;
            r.data = data;
            r.partial = true;
            r.rangeStart = start;
            r.rangeEnd = end;
            r.rangeTotal = total;
            return r;
        }

        static SegResult redirect(String url) {
            SegResult r = new SegResult();
            if (url == null) {
                r.code = 404;
                r.mime = "text/plain";
                r.data = new byte[]{'n', 'o'};
            } else {
                r.code = 302;
                r.redirectUrl = url;
            }
            return r;
        }

        /** 边下边发：返回已拿到首字节的流，由 NanoHTTPD chunked 写给播放器。 */
        static SegResult stream(String mime, InputStream in) {
            SegResult r = new SegResult();
            r.code = 200;
            r.mime = mime;
            r.stream = in;
            return r;
        }
    }

    private static final class Seg {
        static final int EMPTY = 0;
        static final int LOADING = 1;
        static final int DONE = 2;
        static final int FAILED = 3;

        int state = EMPTY;
        byte[] data;
        /** 下载中的增量缓冲（边下边发用）；DONE 后与 data 指向同一数组（长度一致时）。 */
        byte[] grow;
        int length;
        long size;
        int attempts;
        /**
         * r194：正在读这一片的 {@link SegStream} 读者数。
         * &gt;0 时禁止淘汰该片——否则会把正在流式发送的数组清掉，播放器读到半截。
         */
        int readers;
        /**
         * r201：已提交下载任务但尚未结束（防止同一片被重复排队）。
         */
        boolean queued;
    }

    private final class Session {
        final String sk;
        final String baseUrl;
        final List<M3u8PrefetchPlan.Segment> segs;
        final Headers okHeaders;
        final String host;
        final Object lock = new Object();
        final Map<Integer, Seg> states = new HashMap<>();
        final LinkedHashMap<Integer, Long> lru = new LinkedHashMap<>(16, 0.75f, true);
        final boolean[] recent = new boolean[10];
        /** r200：本会话的分片度量（会话级，release 时汇总）。 */
        final SegStat stat = new SegStat();
        /** r200：本会话度量是否已并入全局桶（避免重复计）。 */
        volatile boolean statMerged = false;

        int window = 2;
        /**
         * r201：预取深度（片）——播放头之前希望已就绪的片数。
         *
         * <p>与 {@link #window}（同时在飞的下载数）<b>解耦</b>：自适应窗口回答的是
         * 「开几条连接才喂得饱码率」，深度回答的是「播放头之前留多少缓冲」。</p>
         */
        int depth = 2;
        /** r201：预取在飞数（不含播放头正在点名的那一片）。 */
        int prefetchInflight = 0;
        /** r201：已下载片的字节累计（用于按内存预算算深度）。 */
        long segBytesSum = 0;
        int segCount = 0;
        /** r201：实测最大片字节（深度预算按它算，避免最坏情况超预算）。 */
        long segBytesMax = 0;
        /** r201：平均片长（秒），来自播放列表的 EXTINF。 */
        double avgSegSec = 0;
        /**
         * r202：哪些片在本会话里已经下载成功过（下标 = 播放列表序号 1..n）。
         *
         * <p>用位图而不是挂在 {@link Seg} 上：{@code Seg} 实例会被淘汰删掉
         * （{@code states.remove}），挂在它上面的「下过没」会随之丢失，
         * 重复下载就统计不出来了。n 通常一两千，占用可忽略。</p>
         */
        final boolean[] doneOnce;

        volatile int cursor = 1;
        volatile long lastAccess = System.currentTimeMillis();
        volatile boolean closed = false;

        long totalBytes = 0;
        double ewmaBpm = 0;          // bytes/ms（单连接）
        double bitrateMbps;          // 运行中估算
        double sampleW2 = -1;
        double sampleW4 = -1;
        boolean perIpSuspect = false;
        int recentPos = 0;

        Session(String sk, String baseUrl, M3u8PrefetchPlan.Plan plan, HashMap<String, String> headers) {
            this.sk = sk;
            this.baseUrl = baseUrl;
            this.segs = plan.segments;
            this.doneOnce = new boolean[plan.segments.size() + 1];
            this.bitrateMbps = plan.bitrateMbps;
            this.host = hostOf(plan.segments.isEmpty() ? baseUrl : plan.segments.get(0).url);
            Headers.Builder hb = new Headers.Builder();
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        try {
                            hb.add(e.getKey(), e.getValue());
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (hb.get("User-Agent") == null) {
                try {
                    hb.add("User-Agent", okhttp3.internal.Version.userAgent());
                } catch (Throwable ignored) {
                }
            }
            this.okHeaders = hb.build();
        }

        float speed() {
            return playbackSpeed;
        }

        void record(boolean err) {
            recent[recentPos % recent.length] = err;
            recentPos++;
        }

        int recentErrors() {
            int c = 0;
            for (boolean b : recent) {
                if (b) {
                    c++;
                }
            }
            return c;
        }
    }

    private static String hostOf(String url) {
        try {
            String h = new URL(url).getHost();
            return h == null ? "?" : h;
        } catch (Throwable t) {
            return "?";
        }
    }

    private Semaphore hostSemaphore(String host) {
        Semaphore s = hostSem.get(host);
        if (s == null) {
            synchronized (hostSem) {
                s = hostSem.get(host);
                if (s == null) {
                    s = new Semaphore(MAX_PER_HOST, true);
                    hostSem.put(host, s);
                }
            }
        }
        return s;
    }

    /**
     * 解析并注册一个预取会话；返回改写后的播放列表。不支持/关闭时返回 null（调用方保持原行为）。
     */
    public Prepared prepare(String sk, String baseUrl, String content, HashMap<String, String> headers) {
        if (!isEnabled() || sk == null || sk.isEmpty() || content == null) {
            return null;
        }
        M3u8PrefetchPlan.Plan plan = M3u8PrefetchPlan.parse(baseUrl, content, "/seg?sk=" + sk + "&i=");
        if (!plan.supported || plan.segments.isEmpty()) {
            LOG.d("prefetch skip (" + plan.reason + ") segs=" + plan.segments.size());
            return null;
        }
        Session s = new Session(sk, baseUrl, plan, headers);
        // r201：平均片长（来自播放列表的 EXTINF）——折算预取深度要用
        double durSum = 0;
        int durCount = 0;
        for (M3u8PrefetchPlan.Segment seg : plan.segments) {
            if (seg.durationSec > 0) {
                durSum += seg.durationSec;
                durCount++;
            }
        }
        if (durCount > 0) {
            s.avgSegSec = durSum / durCount;
        }
        s.depth = PrefetchController.targetDepth(plan.bitrateMbps, s.avgSegSec, 0L, PREFETCH_MEM_BUDGET);
        synchronized (sessions) {
            Session old = sessions.remove(sk);
            if (old != null) {
                abort(old);
            }
            sessions.put(sk, s);
            while (sessions.size() > MAX_SESSIONS) {
                Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                Map.Entry<String, Session> eldest = it.next();
                it.remove();
                abort(eldest.getValue());
            }
        }
        LOG.d("prefetch session " + shortSk(sk) + " segs=" + plan.segments.size()
                + " bitrate=" + plan.bitrateMbps + " host=" + s.host);
        // ★ r187：不再从第 1 片盲预取——续播/seek 时起播点是任意 i，
        //   盲下 1、2、3 既浪费流量又抢同 host 并发，反而拖慢真正要的那片。
        //   由第一次 /seg 请求的 index 驱动预取（见 serveSegment）。
        Prepared p = new Prepared();
        p.rewritten = plan.rewritten;
        return p;
    }

    /** 释放会话（切源/退出播放时调用），立即停止下载并释放缓冲。 */
    public void release(String sk) {
        if (sk == null || sk.isEmpty()) {
            return;
        }
        Session s;
        synchronized (sessions) {
            s = sessions.remove(sk);
        }
        if (s != null) {
            abort(s);
            LOG.d("prefetch release " + shortSk(sk));
            // r200：会话收尾时打一行汇总（并入全局桶的动作在 abort 里做，避免重复计）。
            LOG.i("[pref] stat tag=" + playerTag + " sk=" + shortSk(sk)
                    + " END " + s.stat.summary());
        }
    }

    private static String shortSk(String sk) {
        return sk.length() > 6 ? sk.substring(0, 6) : sk;
    }

    /**
     * 处理播放器的 /seg 请求：
     * <ul>
     *   <li>整片已在内存 ⇒ 200/206 直接回（支持 Range）；</li>
     *   <li>正在下载/刚发起 ⇒ 最多等 {@link #FIRST_BYTE_WAIT_MS} 拿首字节，
     *       拿到就 chunked 边下边发；</li>
     *   <li>拿不到 ⇒ 302 回源，绝不阻塞。</li>
     * </ul>
     */
    public SegResult serveSegment(String sk, int index, String rangeHeader) {
        Session s;
        synchronized (sessions) {
            s = sessions.get(sk);
        }
        if (s == null) {
            return SegResult.redirect(null);
        }
        s.lastAccess = System.currentTimeMillis();
        // r200：服务端口径度量（Exo / IJK 同尺子）
        SegStat g = globalStat(playerTag);
        s.stat.req.incrementAndGet();
        g.req.incrementAndGet();
        if (index < 1 || index > s.segs.size()) {
            s.stat.redirect.incrementAndGet();
            g.redirect.incrementAndGet();
            return SegResult.redirect(null);
        }
        String mime = mimeFor(s.segs.get(index - 1).url);
        byte[] ready = readyData(s, index);
        if (ready != null) {
            s.cursor = index;
            s.stat.hit.incrementAndGet();
            g.hit.incrementAndGet();
            s.stat.servedBytes.addAndGet(ready.length);
            g.servedBytes.addAndGet(ready.length);
            // r201：命中也要补深度——否则缓冲被播放头吃空后没人再往前铺，
            // 下一次未命中时又回到「点名才开下」的零提前量。
            schedule(s, index);
            logStatIfDue(s, index);
            return applyRange(mime, ready, rangeHeader);
        }
        if (rangeHeader != null && !rangeHeader.isEmpty()) {
            // 区间请求只从完整缓冲服务（HLS 极少走这条）；未就绪就回源
            s.cursor = index;
            schedule(s, index);
            s.stat.redirect.incrementAndGet();
            g.redirect.incrementAndGet();
            logStatIfDue(s, index);
            return SegResult.redirect(s.segs.get(index - 1).url);
        }
        s.cursor = index;
        schedule(s, index);
        long t0 = System.currentTimeMillis();
        Seg st = awaitFirstByte(s, index, FIRST_BYTE_WAIT_MS);
        long waited = System.currentTimeMillis() - t0;
        s.stat.addWait(waited);
        g.addWait(waited);
        if (st == null || st.length <= 0) {
            s.stat.redirect.incrementAndGet();
            g.redirect.incrementAndGet();
            logStatIfDue(s, index);
            return SegResult.redirect(s.segs.get(index - 1).url);
        }
        s.stat.stream.incrementAndGet();
        g.stream.incrementAndGet();
        logStatIfDue(s, index);
        return SegResult.stream(mime, new SegStream(s, index, st));
    }

    /**
     * r200：每若干片打一行进度度量（不逐片打，避免日志噪声）。
     * 每行都带播放器标签，Exo / IJK 可以直接对照。
     */
    private void logStatIfDue(Session s, int index) {
        long n = s.stat.req.get();
        if (n == 0 || n % 20 != 0) {
            return;
        }
        LOG.i("[pref] stat tag=" + playerTag + " sk=" + shortSk(s.sk)
                + " i=" + index
                + " win=" + s.window
                + " depth=" + s.depth
                + " preKB=" + (prefetchRetained(s) / 1024)
                + " bitrate=" + String.format(java.util.Locale.US, "%.2f", s.bitrateMbps)
                + " " + s.stat.summary());
    }

    private static byte[] readyData(Session s, int index) {
        synchronized (s.lock) {
            Seg st = s.states.get(index);
            if (st != null && st.state == Seg.DONE && st.data != null) {
                return st.data;
            }
            return null;
        }
    }

    private Seg awaitFirstByte(Session s, int index, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (s.lock) {
            while (true) {
                if (s.closed) {
                    return null;
                }
                Seg st = s.states.get(index);
                if (st != null) {
                    // r194：只认「正在下」或「已下完」的片，避免把淘汰后残留的旧片当成新片发出去
                    if (st.length > 0 && (st.state == Seg.LOADING || st.state == Seg.DONE)) {
                        return st;
                    }
                    if (st.state == Seg.FAILED) {
                        return null;
                    }
                }
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    return null;
                }
                try {
                    s.lock.wait(Math.min(remain, 200));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    /** 把共享的下载缓冲当流读；下载线程往里追加，本流从头消费。 */
    private static final class SegStream extends InputStream {
        private final Session s;
        private final int index;
        /** r194：构造时锁定的那一片（读者计数挂在它上面，避免换对象后计数漂移）。 */
        private final Seg seg;
        private int pos;
        private boolean closed;
        private boolean released;

        SegStream(Session s, int index) {
            this(s, index, null);
        }

        /**
         * r194：直接锁定调用方已确认的那一片（{@code known}），避免「拿到 Seg 后又被淘汰移除」
         * 导致读者计数挂在一个已不在 states 里的对象上。
         */
        SegStream(Session s, int index, Seg known) {
            this.s = s;
            this.index = index;
            Seg st = known;
            synchronized (s.lock) {
                if (st == null) {
                    st = s.states.get(index);
                }
                if (st != null) {
                    st.readers++;
                }
            }
            this.seg = st;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (b == null) {
                throw new NullPointerException();
            }
            if (off < 0 || len < 0 || off + len > b.length) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }
            synchronized (s.lock) {
                while (true) {
                    if (closed || s.closed) {
                        throw new IOException("segment stream closed");
                    }
                    Seg st = seg != null ? seg : s.states.get(index);
                    if (st == null) {
                        throw new IOException("segment gone");
                    }
                    if (pos > st.length) {
                        throw new IOException("segment reset");
                    }
                    if (pos < st.length) {
                        // r194：增量缓冲可能已被释放（读者走完后），退回整片缓冲读
                        byte[] src = st.grow != null ? st.grow : st.data;
                        if (src == null) {
                            throw new IOException("segment buffer released");
                        }
                        int n = Math.min(len, st.length - pos);
                        System.arraycopy(src, pos, b, off, n);
                        pos += n;
                        // r202：边下边发实际送达播放器的字节也要计入 servedBytes。
                        //   之前只统计「命中整片」路径，于是 dlMB 远大于 servedMB，
                        //   看起来像「下了一堆没人用」，其实是 stream 路径没记账。
                        s.stat.servedBytes.addAndGet(n);
                        globalStat(playerTag).servedBytes.addAndGet(n);
                        return n;
                    }
                    if (st.state == Seg.DONE) {
                        releaseReader(st);
                        return -1;
                    }
                    if (st.state == Seg.FAILED) {
                        releaseReader(st);
                        throw new IOException("segment download failed");
                    }
                    if (st.state == Seg.EMPTY) {
                        // r194：该片已被淘汰/重试释放，不能再干等下去（否则这个请求线程会永久挂住）
                        releaseReader(st);
                        throw new IOException("segment released");
                    }
                    try {
                        s.lock.wait(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            Seg st = seg;
            synchronized (s.lock) {
                releaseReader(st);
                s.lock.notifyAll();
            }
        }

        /** r194：读者退场 → 计数减一；无人再读且该片已下完时释放下载增量缓冲。 */
        private void releaseReader(Seg st) {
            if (released) {
                return;
            }
            released = true;
            if (st == null) {
                return;
            }
            synchronized (s.lock) {
                if (st.readers > 0) {
                    st.readers--;
                }
                if (st.readers == 0 && st.state != Seg.LOADING) {
                    // grow 与 data 可能是同一数组：置空 grow 不影响 data 持有的整片
                    st.grow = null;
                }
                s.lock.notifyAll();
            }
        }
    }

    /**
     * r201：调度下载——<b>深度（缓冲余量）与并发（连接数）解耦</b>。
     *
     * <p>旧实现只有一个「窗口 W」：既当并发数用，又当缓冲深度用，
     * 而自适应公式算出来的 W 回答的是「要开几条连接才喂得饱码率」。
     * CDN 越快 ⇒ W 越小 ⇒ 缓冲越薄：W=1 时每片都是「播放头点名才开下」，
     * 提前量≈0，源站一抖就卡顿（IJK/ffmpeg 串行消费切片，正是最吃亏的那个）。</p>
     *
     * <p>现在拆成两件事：</p>
     * <ul>
     *   <li><b>并发</b>：预取同时在飞数 = 自适应窗口 W（再留一个名额给播放头）；</li>
     *   <li><b>深度</b>：播放头之前铺多少片已就绪 = {@code s.depth}
     *       （按目标缓冲秒数换算，再受预取内存预算约束）。</li>
     * </ul>
     *
     * <p>播放头点名的那一片永远优先开下，不与预取抢名额。</p>
     */
    private void schedule(Session s, int from) {
        List<Integer> head = new ArrayList<>();
        List<Integer> pre = new ArrayList<>();
        synchronized (s.lock) {
            if (s.closed) {
                return;
            }
            // ① 播放头要点名的那一片：优先开下
            Seg hs = s.states.get(from);
            if (hs == null) {
                hs = new Seg();
                s.states.put(from, hs);
            }
            if (hs.state == Seg.EMPTY && !hs.queued) {
                hs.queued = true;
                head.add(from);
            }
            // ② 深度预取：铺到 from+depth 为止，并发不超过自适应窗口
            int limit = Math.min(s.segs.size(), from + Math.max(1, s.depth));
            int cap = prefetchCap(s);
            for (int i = from + 1; i <= limit && s.prefetchInflight < cap; i++) {
                Seg st = s.states.get(i);
                if (st == null) {
                    st = new Seg();
                    s.states.put(i, st);
                }
                if (st.state == Seg.EMPTY && !st.queued) {
                    st.queued = true;
                    pre.add(i);
                    s.prefetchInflight++;
                }
            }
        }
        for (final Integer i : head) {
            submitDownload(s, i, false);
        }
        for (final Integer i : pre) {
            submitDownload(s, i, true);
        }
    }

    /**
     * r201：预取并发上限。
     *
     * <p>r202：不再由自适应窗口 W 推导。原来的 {@code cap = W - 1}（下限 1）在源站很快时
     * 会退化成 {@code cap = 1}——实测 {@code win=1} 恒定，于是深度 4 片只能串行铺，
     * 缓冲永远补不满。现在预取并发独立取 {@link #PREFETCH_CONCURRENCY}，
     * 只受单 host 名额（给播放头留一个）与近期错误约束。</p>
     */
    private static int prefetchCap(Session s) {
        int cap = Math.min(PREFETCH_CONCURRENCY, MAX_PER_HOST - 1);
        if (cap < 1) {
            cap = 1;
        }
        // 源站不稳时收敛并发，避免「一片失败带崩一片」
        int errs = s.recentErrors();
        if (errs >= 2) {
            cap = 1;
        } else if (errs >= 1) {
            cap = Math.min(cap, 2);
        }
        if (s.perIpSuspect) {
            cap = Math.min(cap, 2);
        }
        return cap;
    }

    /** r201：预取任务收尾——释放名额并把深度补回去。 */
    private void onPrefetchDone(Session s) {
        synchronized (s.lock) {
            if (s.prefetchInflight > 0) {
                s.prefetchInflight--;
            }
        }
        try {
            schedule(s, s.cursor);
        } catch (Throwable ignored) {
        }
    }

    /** r201：把下载任务丢进线程池（r200：预取下载任务计数）。 */
    private void submitDownload(final Session s, final int index, final boolean prefetch) {
        s.stat.dlReq.incrementAndGet();
        globalStat(playerTag).dlReq.incrementAndGet();
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    download(s, index, prefetch);
                }
            });
        } catch (Throwable t) {
            LOG.d("prefetch submit fail: " + t.getMessage());
            synchronized (s.lock) {
                Seg st = s.states.get(index);
                if (st != null) {
                    st.queued = false; // r201：提交失败要放开排队标记，否则这一片永远排不上
                }
            }
            if (prefetch) {
                onPrefetchDone(s);
            }
        }
    }

    private void download(final Session s, final int index, final boolean prefetch) {
        Seg st = null;
        boolean skip = false;
        synchronized (s.lock) {
            if (s.closed) {
                skip = true;
            } else {
                st = s.states.get(index);
                if (st == null) {
                    st = new Seg();
                    s.states.put(index, st);
                }
                if (st.state != Seg.EMPTY) {
                    skip = true; // 已经有人在下了 / 已经下过
                } else {
                    st.state = Seg.LOADING;
                }
            }
        }
        if (skip) {
            if (prefetch) {
                // r201：名额释放（放在锁外，避免在锁内做调度）
                onPrefetchDone(s);
            }
            return;
        }

        M3u8PrefetchPlan.Segment seg = s.segs.get(index - 1);
        Semaphore hs = hostSemaphore(s.host);
        boolean globalHeld = false;
        boolean hostHeld = false;
        try {
            if (!global.tryAcquire(30, TimeUnit.SECONDS)) {
                throw new IOException("global busy");
            }
            globalHeld = true;
            if (!hs.tryAcquire(30, TimeUnit.SECONDS)) {
                throw new IOException("host busy");
            }
            hostHeld = true;

            long t0 = System.currentTimeMillis();
            byte[] data = fetchStreaming(s, st, seg.url);
            long dt = Math.max(1, System.currentTimeMillis() - t0);
            // r200：下载侧度量（成功/字节）
            s.stat.dlOk.incrementAndGet();
            s.stat.dlBytes.addAndGet(data.length);
            globalStat(playerTag).dlOk.incrementAndGet();
            globalStat(playerTag).dlBytes.addAndGet(data.length);
            // r202：同一片第二次下载（曾被淘汰后又回跳/切播放器要用）记为重复下载，
            //   用于量化「缓存不跨 seek 复用」到底浪费了多少流量。
            if (index >= 1 && index < s.doneOnce.length && s.doneOnce[index]) {
                s.stat.dupBytes.addAndGet(data.length);
                globalStat(playerTag).dupBytes.addAndGet(data.length);
            } else if (index >= 1 && index < s.doneOnce.length) {
                s.doneOnce[index] = true;
            }

            synchronized (s.lock) {
                st.state = Seg.DONE;
                st.data = data;
                st.size = data.length;
                st.queued = false; // r201：下载结束，释放排队标记（失败重试需要能再次被调度）
                // r201：实测平均/最大片字节（供 targetDepth 按内存预算算深度）
                s.segBytesSum += data.length;
                s.segCount++;
                if (data.length > s.segBytesMax) {
                    s.segBytesMax = data.length;
                }
                if (st.readers == 0) {
                    // r194：没人读这一片 → 释放下载增量缓冲（data 已持有整片）
                    st.grow = null;
                }
                double bpm = data.length / (double) dt;
                s.ewmaBpm = s.ewmaBpm <= 0 ? bpm : (s.ewmaBpm * 0.7 + bpm * 0.3);
                if (seg.durationSec > 0) {
                    double est = data.length * 8.0 / (seg.durationSec * 1e6);
                    s.bitrateMbps = s.bitrateMbps <= 0 ? est : (s.bitrateMbps * 0.85 + est * 0.15);
                }
                store(s, index, data.length, data);
                s.record(false);
                if (s.window == 2 && s.sampleW2 < 0) {
                    s.sampleW2 = s.ewmaBpm;
                }
                if (s.window >= 4) {
                    s.sampleW4 = s.sampleW4 <= 0 ? s.ewmaBpm : (s.sampleW4 * 0.7 + s.ewmaBpm * 0.3);
                }
                adapt(s);
                s.lock.notifyAll();
            }
        } catch (Throwable e) {
            s.stat.dlFail.incrementAndGet();
            globalStat(playerTag).dlFail.incrementAndGet();
            synchronized (s.lock) {
                st.attempts++;
                st.queued = false; // r201：失败也要释放排队标记，否则这一片再也不会被重新调度
                if (st.attempts < 2 && !s.closed) {
                    st.state = Seg.EMPTY; // 重试一次
                } else {
                    st.state = Seg.FAILED;
                    if (st.readers == 0) {
                        // r194：失败的片不再需要增量缓冲
                        st.grow = null;
                    }
                }
                s.record(true);
                adapt(s);
                s.lock.notifyAll();
            }
            LOG.d("prefetch seg " + index + " fail: " + e.getMessage());
        } finally {
            if (hostHeld) {
                hs.release();
            }
            if (globalHeld) {
                global.release();
            }
            if (prefetch) {
                // r201：预取任务收尾——释放名额，并把深度补回去（否则名额永远卡住，深度形同虚设）
                onPrefetchDone(s);
            }
        }
    }

    /** 下载整片，同时把到达的字节追加进 {@code st.grow}，让 /seg 可以边下边发。 */
    private byte[] fetchStreaming(Session s, Seg st, String url) throws IOException {
        Request.Builder rb = new Request.Builder().url(url).headers(s.okHeaders).get();
        Response resp = http.newCall(rb.build()).execute();
        try {
            int code = resp.code();
            if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code);
            }
            ResponseBody body = resp.body();
            if (body == null) {
                throw new IOException("empty body");
            }
            long len = body.contentLength();
            int cap = len > 0 && len <= MAX_SEGMENT_BYTES ? (int) len : 65536;
            synchronized (s.lock) {
                st.grow = new byte[cap];
                st.length = 0;
            }
            InputStream in = body.byteStream();
            byte[] buf = new byte[16384];
            int total = 0;
            long deadline = System.currentTimeMillis() + SEG_DOWNLOAD_DEADLINE_MS;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (s.closed) {
                    throw new IOException("session closed");
                }
                synchronized (s.lock) {
                    if (st.grow == null || total + n > st.grow.length) {
                        int want = total + n;
                        int next = Math.max(want, st.grow == null ? 65536 : st.grow.length + st.grow.length / 2);
                        byte[] bigger = new byte[next];
                        if (st.grow != null) {
                            System.arraycopy(st.grow, 0, bigger, 0, total);
                        }
                        st.grow = bigger;
                    }
                    System.arraycopy(buf, 0, st.grow, total, n);
                    total += n;
                    st.length = total;
                    s.lock.notifyAll();
                }
                if (total > MAX_SEGMENT_BYTES) {
                    throw new IOException("segment too large");
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("read deadline");
                }
            }
            if (total == 0) {
                throw new IOException("empty segment");
            }
            if (st.grow.length == total) {
                return st.grow;
            }
            return Arrays.copyOf(st.grow, total);
        } finally {
            resp.close();
        }
    }

    private void store(Session s, int index, long size, byte[] data) {
        synchronized (s.lock) {
            Seg st = s.states.get(index);
            if (st == null) {
                // r194：该片已被淘汰或会话已重置 —— 不再缓存，直接丢弃（否则又挂回 states）
                return;
            }
            st.data = data;
            s.lru.put(index, retainedOf(st));
            evictIfNeeded(s);
        }
    }

    /** r194：该片当前真实占用的堆字节（data 与 grow 指向同一数组时只算一次）。 */
    private static long retainedOf(Seg st) {
        if (st == null) {
            return 0;
        }
        long n = 0;
        if (st.data != null) {
            n += st.data.length;
        }
        if (st.grow != null && st.grow != st.data) {
            n += st.grow.length;
        }
        return n;
    }

    /** r194：会话内真实占用合计（直接扫 states，不再依赖可能漂移的记账值）。 */
    private static long totalRetained(Session s) {
        long n = 0;
        for (Seg st : s.states.values()) {
            n += retainedOf(st);
        }
        return n;
    }

    /**
     * r194：超出 {@link #SESSION_MEMORY_BUDGET} 时淘汰播放头之后的旧片，并**真正释放字节**。
     *
     * <p>旧实现只把 {@code st.data} 置空，而 {@code st.grow} 仍引用着同一个数组
     * （Content-Length 命中时 data 与 grow 就是同一块），一字节都没释放；
     * 同时 {@code s.states} 只增不减，于是整部已播视频的分片全部滞留在堆上，
     * 按码率线性增长（实测约 13MB/min），最终 Java 堆 OOM、进程被杀。</p>
     */
    /**
     * r201：超出内存预算时淘汰。
     *
     * <p>r202：改成<b>「离播放头最远优先」</b>的单级策略（r201 是「先已播、再窗口外、最后窗口内」）。
     * 理由：旧的「已播过的一律先扔」在回跳/seek 时会立刻把刚播过的片全丢掉，
     * 而 r202 的取舍是<b>复用优先</b>——回跳、切播放器（Exo↔IJK）都希望附近几片还在内存里。
     * 按距离淘汰天然满足两点：</p>
     * <ul>
     *   <li>播放头<b>前后各留一片近邻</b>，回跳一两片不用回源；</li>
     *   <li>内存吃紧时先丢最远的（无论是往前预取的尾巴，还是很久以前播过的老片）。</li>
     * </ul>
     */
    private void evictIfNeeded(Session s) {
        // ① 预取缓冲单独有预算（12MB）：超了先丢「播放头之前」里离得最远的那片。
        while (prefetchRetained(s) > PREFETCH_MEM_BUDGET) {
            if (!evictFarthest(s, true)) {
                break; // 剩下的都在发送/下载中，动不了
            }
        }
        // ② 会话总预算（16MB 硬顶）：这时才允许动播放头之前的片（远的先走）。
        while (true) {
            s.totalBytes = totalRetained(s);
            if (s.totalBytes <= SESSION_MEMORY_BUDGET) {
                return;
            }
            if (!evictFarthest(s, false)) {
                return; // 没有可淘汰的（都在播放头附近，或正在发送/下载）
            }
        }
    }

    /**
     * r201：预取缓冲（index &gt; cursor 的已就绪片）当前占用的字节。
     *
     * <p>要持锁读：{@code s.lru} 是普通 LinkedHashMap，可能正在被下载线程改。</p>
     */
    private static long prefetchRetained(Session s) {
        synchronized (s.lock) {
            long n = 0;
            for (Map.Entry<Integer, Long> e : s.lru.entrySet()) {
                if (e.getKey() > s.cursor) {
                    n += e.getValue() == null ? 0L : e.getValue();
                }
            }
            return n;
        }
    }

    /**
     * r202：淘汰「离播放头最远」的一片。
     *
     * <p>距离＝{@code |index - cursor|}，播放头前后一视同仁——这样回跳复用与
     * 预取深度是同一把尺子，不需要两套规则。</p>
     *
     * <p>淘汰后若这一片仍落在「会被重新调度」的区间（{@code cursor < idx <= cursor+depth}），
     * 就标成 FAILED 保留条目，防止「淘汰 → 重排 → 再淘汰」的抖动；
     * 区间外的直接回 EMPTY 并移出 states（r194 语义，避免无界增长）。</p>
     *
     * @param aheadOnly true＝只在 {@code idx > cursor} 的预取片里挑（预取预算超了时用，
     *                  避免把播放头之前的回跳复用片也一起清掉）
     */
    private boolean evictFarthest(Session s, boolean aheadOnly) {
        int worst = -1;
        int worstDist = -1;
        for (Map.Entry<Integer, Long> e : s.lru.entrySet()) {
            int idx = e.getKey();
            if (aheadOnly && idx <= s.cursor) {
                continue;
            }
            Seg st = s.states.get(idx);
            if (st != null && (st.readers > 0 || st.state == Seg.LOADING)) {
                continue; // 正在流式发送 / 正在下载，不能动
            }
            int dist = Math.abs(idx - s.cursor);
            if (dist > worstDist || (dist == worstDist && idx > worst)) {
                worstDist = dist;
                worst = idx;
            }
        }
        if (worst < 0) {
            return false;
        }
        boolean inScheduleWindow = worst > s.cursor
                && worst <= s.cursor + Math.max(1, s.depth);
        s.lru.remove(worst);
        releaseSeg(s, s.states.get(worst), worst, inScheduleWindow);
        return true;
    }

    /**
     * r201：真正释放一片的字节（r194 的语义，抽成方法便于复用）。
     *
     * @param failed true＝标成 FAILED 并<b>保留</b>条目——它还在深度窗口里，
     *               若把条目删掉，下一轮调度会把它当成新片再排一次，
     *               形成「淘汰 → 重排 → 再淘汰」的抖动；
     *               保留 FAILED 状态则不再重排，播放头真要这一片时
     *               走 302 回源（老路），不会卡住。
     *               false＝回到 EMPTY 并把条目移出 states（r194：条目真正移出，避免 states 无界增长）。
     */
    private void releaseSeg(Session s, Seg st, int idx, boolean failed) {
        if (st == null) {
            return;
        }
        st.data = null;
        st.grow = null;
        st.length = 0;
        st.size = 0;
        st.queued = false;
        if (failed) {
            st.state = Seg.FAILED;
            return; // 保留条目，只放掉字节
        }
        st.state = Seg.EMPTY;
        s.states.remove(idx); // r194：条目真正移出，避免 states 无界增长
    }

    private void adapt(Session s) {
        boolean suspect = s.perIpSuspect;
        if (s.sampleW2 > 0 && s.sampleW4 > 0 && s.sampleW4 < 0.575 * s.sampleW2) {
            suspect = true;
        }
        s.perIpSuspect = suspect;
        int target = PrefetchController.targetWindow(s.bitrateMbps, s.speed(), s.ewmaBpm,
                s.window, s.recentErrors(), suspect);
        s.window = PrefetchController.clamp(target, 1, M3u8PrefetchPlan.HARD_MAX_WINDOW);
        // r201：深度单独算——按目标缓冲秒数换算成片数，再受预取内存预算约束。
        //   注意：这里用的是「实测片字节」，不是自适应窗口；
        //   窗口回答「几条连接喂得饱码率」，深度回答「播放头之前留多少缓冲」。
        //   用实测最大片字节算（而不是平均），保证最坏情况也不超内存预算。
        long segBytes = s.segBytesMax > 0 ? s.segBytesMax
                : (s.segCount > 0 ? s.segBytesSum / s.segCount : 0L);
        s.depth = PrefetchController.targetDepth(s.bitrateMbps, s.avgSegSec, segBytes,
                PREFETCH_MEM_BUDGET);
    }

    private static String mimeFor(String url) {
        String u = url == null ? "" : url.toLowerCase();
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        if (u.endsWith(".m4s") || u.endsWith(".mp4") || u.endsWith(".m4a")) {
            return "video/mp4";
        }
        if (u.endsWith(".aac")) {
            return "audio/aac";
        }
        if (u.endsWith(".vtt")) {
            return "text/vtt";
        }
        if (u.endsWith(".key") || u.endsWith(".bin")) {
            return "application/octet-stream";
        }
        return "video/mp2t";
    }

    /** 应用 Range；无 Range 返回 200 全量，有 Range 返回 206 片段。 */
    private static SegResult applyRange(String mime, byte[] data, String rangeHeader) {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            return SegResult.full(mime, data);
        }
        try {
            String spec = rangeHeader.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash < 0) {
                return SegResult.full(mime, data);
            }
            String a = spec.substring(0, dash).trim();
            String b = spec.substring(dash + 1).trim();
            long total = data.length;
            long start;
            long end;
            if (a.isEmpty()) {
                // bytes=-N 取末尾 N 字节
                long n = Long.parseLong(b);
                start = Math.max(0, total - n);
                end = total - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? total - 1 : Long.parseLong(b);
            }
            if (start < 0) {
                start = 0;
            }
            if (end >= total) {
                end = total - 1;
            }
            if (start > end || start >= total) {
                return SegResult.full(mime, data);
            }
            byte[] slice = Arrays.copyOfRange(data, (int) start, (int) end + 1);
            return SegResult.partial(mime, slice, start, end, total);
        } catch (Throwable t) {
            return SegResult.full(mime, data);
        }
    }

    private void abort(Session s) {
        // r200：会话结束（释放 / 被新会话顶掉 / LRU 淘汰）时把度量并进全局桶，
        //   保证 Exo 与 IJK 的对比数据不会因为会话被顶掉而丢。
        if (!s.statMerged) {
            s.statMerged = true;
            globalStat(playerTag).merge(s.stat);
        }
        s.closed = true;
        synchronized (s.lock) {
            for (Seg st : s.states.values()) {
                st.data = null;
                st.grow = null; // r194：增量缓冲一并释放
                st.length = 0;
                st.size = 0;
                st.state = Seg.EMPTY;
            }
            s.states.clear();
            s.lru.clear();
            s.totalBytes = 0;
            s.lock.notifyAll();
        }
    }
}
