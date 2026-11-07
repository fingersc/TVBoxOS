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
 *   <li>播放器请求某一 {@code /seg} 时，本类确保该片及后续 W-1 片同时在下载
 *       （{@link #serveSegment} 阻塞等待目标片，窗口由 {@link PrefetchController} 自适应）；</li>
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
    /** 单个切片下落上限（防御异常大文件）。 */
    private static final int MAX_SEGMENT_BYTES = 4 * 1024 * 1024;

    private static final int MAX_SESSIONS = 2;
    private static final long WAIT_FIRST_MS = 20000;
    private static final long WAIT_OTHERS_MS = 12000;
    /** 单个切片下载的墙钟上限（慢速滴流服务器保护）。 */
    private static final long SEG_DOWNLOAD_DEADLINE_MS = 45000;

    private static volatile float playbackSpeed = 1.0f;

    private static volatile PrefetchManager instance;

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
    }

    private static final class Seg {
        static final int EMPTY = 0;
        static final int LOADING = 1;
        static final int DONE = 2;
        static final int FAILED = 3;

        int state = EMPTY;
        byte[] data;
        long size;
        int attempts;
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

        int window = 2;
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
        // 起播预取前 2 片（当免费探针）
        schedule(s, 1);
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
        }
    }

    private static String shortSk(String sk) {
        return sk.length() > 6 ? sk.substring(0, 6) : sk;
    }

    /** 处理播放器的 /seg 请求：等待（或触发）该切片下载，必要时 302 回源。 */
    public SegResult serveSegment(String sk, int index, String rangeHeader) {
        Session s;
        synchronized (sessions) {
            s = sessions.get(sk);
        }
        if (s == null) {
            return SegResult.redirect(null);
        }
        s.lastAccess = System.currentTimeMillis();
        if (index < 1 || index > s.segs.size()) {
            return SegResult.redirect(null);
        }
        s.cursor = index;
        schedule(s, index);
        byte[] data = waitFor(s, index, index == 1 ? WAIT_FIRST_MS : WAIT_OTHERS_MS);
        if (data == null) {
            // 下载失败/超时：直接让播放器回源，等同于旧行为
            return SegResult.redirect(s.segs.get(index - 1).url);
        }
        String mime = mimeFor(s.segs.get(index - 1).url);
        return applyRange(mime, data, rangeHeader);
    }

    private byte[] waitFor(Session s, int index, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (s.lock) {
            while (true) {
                if (s.closed) {
                    return null;
                }
                Seg st = s.states.get(index);
                if (st != null) {
                    if (st.state == Seg.DONE && st.data != null) {
                        return st.data;
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
                    s.lock.wait(Math.min(remain, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    private void schedule(Session s, int from) {
        int w = PrefetchController.clamp(s.window, 1, M3u8PrefetchPlan.HARD_MAX_WINDOW);
        int hi = Math.min(s.segs.size(), from + w - 1);
        List<Integer> submit = new ArrayList<>();
        synchronized (s.lock) {
            if (s.closed) {
                return;
            }
            for (int i = from; i <= hi; i++) {
                Seg st = s.states.get(i);
                if (st == null) {
                    st = new Seg();
                    s.states.put(i, st);
                }
                if (st.state == Seg.EMPTY) {
                    submit.add(i);
                }
            }
        }
        for (final Integer i : submit) {
            try {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        download(s, i);
                    }
                });
            } catch (Throwable t) {
                LOG.d("prefetch submit fail: " + t.getMessage());
            }
        }
    }

    private void download(final Session s, final int index) {
        Seg st;
        synchronized (s.lock) {
            if (s.closed) {
                return;
            }
            st = s.states.get(index);
            if (st == null) {
                st = new Seg();
                s.states.put(index, st);
            }
            if (st.state != Seg.EMPTY) {
                return;
            }
            st.state = Seg.LOADING;
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
            byte[] data = fetch(seg.url, s.okHeaders, s);
            long dt = Math.max(1, System.currentTimeMillis() - t0);

            synchronized (s.lock) {
                st.state = Seg.DONE;
                st.data = data;
                st.size = data.length;
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
            synchronized (s.lock) {
                st.attempts++;
                if (st.attempts < 2 && !s.closed) {
                    st.state = Seg.EMPTY; // 重试一次
                } else {
                    st.state = Seg.FAILED;
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
        }
    }

    private byte[] fetch(String url, Headers headers, Session s) throws IOException {
        Request.Builder rb = new Request.Builder().url(url).headers(headers).get();
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
            InputStream in = body.byteStream();
            long len = body.contentLength();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(
                    len > 0 && len < MAX_SEGMENT_BYTES ? (int) len : 65536);
            byte[] buf = new byte[16384];
            long total = 0;
            long deadline = System.currentTimeMillis() + SEG_DOWNLOAD_DEADLINE_MS;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (s.closed) {
                    throw new IOException("session closed");
                }
                bos.write(buf, 0, n);
                total += n;
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
            return bos.toByteArray();
        } finally {
            resp.close();
        }
    }

    private void store(Session s, int index, long size, byte[] data) {
        s.lru.put(index, size);
        s.totalBytes += size;
        s.states.get(index).data = data;
        while (s.totalBytes > SESSION_MEMORY_BUDGET) {
            boolean evicted = false;
            Iterator<Map.Entry<Integer, Long>> it = s.lru.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Long> e = it.next();
                int idx = e.getKey();
                if (idx < s.cursor - 1) {
                    it.remove();
                    s.totalBytes -= e.getValue();
                    Seg st = s.states.get(idx);
                    if (st != null) {
                        st.data = null;
                        st.state = Seg.EMPTY;
                    }
                    evicted = true;
                    break;
                }
            }
            if (!evicted) {
                break;
            }
        }
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
        s.closed = true;
        synchronized (s.lock) {
            for (Seg st : s.states.values()) {
                st.data = null;
                st.state = Seg.EMPTY;
            }
            s.states.clear();
            s.lru.clear();
            s.totalBytes = 0;
            s.lock.notifyAll();
        }
    }
}
