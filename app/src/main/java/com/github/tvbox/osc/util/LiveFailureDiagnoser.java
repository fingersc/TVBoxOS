package com.github.tvbox.osc.util;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 直播起播失败原因判定（v79）。
 *
 * <p>起播超时后，先判断是「线路连不上」还是「播放器/解码起不来」：</p>
 * <ul>
 *   <li>对当前流地址发一次 2 秒的轻量探测（Range: bytes=0-0）</li>
 *   <li>探测失败 ⇒ 线路问题，直接换线路</li>
 *   <li>探测成功但仍没画面 ⇒ 播放器/解码问题，换播放器</li>
 * </ul>
 *
 * <p>同一域名 5 分钟内复用结果，避免每次失败都探测。</p>
 */
public final class LiveFailureDiagnoser {

    private static final long HOST_CACHE_MS = 5 * 60 * 1000L;
    private static final long PROBE_TIMEOUT_MS = 2000L;

    private final Map<String, long[]> hostCache = new HashMap<>();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build();

    /** true = 连不上（大概率是线路问题）；false = 连得上或无法判断（交给换播放器）。 */
    public boolean isUrlUnreachable(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String host = hostOf(url);
        long now = System.currentTimeMillis();
        long[] cached = hostCache.get(host);
        if (cached != null && now - cached[1] < HOST_CACHE_MS) {
            return cached[0] == 0L;
        }
        boolean ok = probe(url);
        hostCache.put(host, new long[]{ok ? 1L : 0L, now});
        LOG.i("[live] probe " + (ok ? "reachable" : "unreachable") + " host=" + host);
        return !ok;
    }

    private boolean probe(String url) {
        Response resp = null;
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", "okhttp/3.12.13")
                    .get()
                    .build();
            resp = client.newCall(req).execute();
            // 只要服务器有响应（含 403/404 这类"能连上但拒绝"）就说明线路可达
            return resp.code() > 0;
        } catch (IOException e) {
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (resp != null) {
                try {
                    resp.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String hostOf(String url) {
        try {
            okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(url);
            return u == null ? url : u.host();
        } catch (Throwable t) {
            return url;
        }
    }
}
