package com.github.catvod.net;

import androidx.collection.ArrayMap;

import com.github.tvbox.osc.util.SSL.SSLSocketFactoryCompat;
import com.github.tvbox.osc.util.LOG;
import com.lzy.okgo.interceptor.HttpLoggingInterceptor;
import com.github.tvbox.osc.util.OkGoHelper;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import okhttp3.Dns;
import java.util.logging.Level;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OkHttp {

    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(30);
    private static OkDns dns;
    private static OkHttpClient client;

    /** v97：爬虫链路专用日志拦截器（tag: Spider），与 OkGo / OkExoPlayer 区分来源。
     *   等级跟随调试模式：关=NONE 完全静默，开=BODY 全量打印（与 OkGoHelper 口径一致）。 */
    private static HttpLoggingInterceptor newHttpLogInterceptor(String tag) {
        HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor(tag);
        interceptor.setPrintLevel(LOG.isDebugEnabled()
                ? HttpLoggingInterceptor.Level.BODY : HttpLoggingInterceptor.Level.NONE);
        interceptor.setColorLevel(LOG.isDebugEnabled() ? Level.INFO : Level.OFF);
        return interceptor;
    }

    public static synchronized OkDns dns() {
        if (dns == null) dns = new OkDns();
        return dns;
    }

    public static synchronized OkHttpClient client() {
        if (client != null) return client;
        OkHttpClient base = OkGoHelper.getDefaultClient();
        // v97：爬虫复用默认 client，日志 tag 会与 OkGo 混在一起；追加独立 tag 便于区分来源
        if (base != null) return client = base.newBuilder().dns(dns())
                .addInterceptor(newHttpLogInterceptor("Spider")).build();
        OkHttpClient.Builder builder = new OkHttpClient.Builder().dns(dns()).proxySelector(OkGoHelper.proxySelector()).proxyAuthenticator(OkGoHelper.proxyAuthenticator()).connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS).readTimeout(TIMEOUT, TimeUnit.MILLISECONDS).writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS);
        setOkHttpSsl(builder);
        return client = builder.build();
    }

    public static OkHttpClient player() {
        return client();
    }

    public static OkHttpClient client(long timeout) {
        return client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).build();
    }

    /** 图片/封面请求统一 tag：供 NetEventListener 识别"这是图片请求"（不污染播放器节点缓存）。 */
    public static final String IMAGE_TAG = "img#cover";

    /**
     * 封面/图片专用 client（v99-r100）：
     * 1) 收紧超时 —— 连接 4s / 读 5s：被墙或黑洞连接最多等 4~5s 就失败，尽快切到中转，
     *    避免 Grid 里每张都先干等 8s 才转（这是"部分站点加载很慢/要很久"的主因）。
     *    读超时是"两次收包间隔"，5s 对正常大图也够，不会误杀。
     * 2) DNS 复用与播放/API 一致的双栈优选（CustomDns → DualStackResolver）：只排序、不删除。
     * 仅用于图片加载，不影响播放/解析链路。
     */
    public static OkHttpClient imageClient() {
        return client().newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .dns(OkGoHelper.imageDns())
                .build();
    }

    /**
     * 图片"中转重试"client（v99-r100）：直连失败后，用它对公共中转（images.weserv.nl）再取一次。
     *
     * <p>关键：DNS 用 {@link Dns#SYSTEM}，不再用 altImageDns()（腾讯 DoH ∪ 系统）。
     * 因为这里要解析的是 weserv 自身域名（不被墙），走 DoH 只会白白增加几秒延迟、DoH 被拦时还会失败；
     * 系统 DNS 快且会缓存。读取超时放宽到 20s —— 中转要回源取图，首字节可能较慢，需给足时间。</p>
     */
    public static OkHttpClient imageRetryClient() {
        return client().newBuilder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .dns(Dns.SYSTEM)
                .build();
    }

    public static OkHttpClient noRedirect() {
        return noRedirect(TIMEOUT);
    }

    public static OkHttpClient noRedirect(long timeout) {
        OkHttpClient base = OkGoHelper.getNoRedirectClient();
        if (base == null) base = client();
        return base.newBuilder().dns(dns()).connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build();
    }

    public static synchronized void reset() {
        client = null;
        dns = null;
    }

    public static synchronized void resetClient() {
        client = null;
    }

    public static OkHttpClient client(boolean redirect, long timeout) {
        return redirect ? client(timeout) : noRedirect(timeout);
    }

    public static String string(String url) {
        if (url == null || !url.startsWith("http")) return "";
        try (Response res = newCall(url).execute()) {
            return res.body() != null ? res.body().string() : "";
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static String string(String url, long timeout) {
        if (url == null || !url.startsWith("http")) return "";
        try (Response res = newCall(client(timeout), url).execute()) {
            return res.body() != null ? res.body().string() : "";
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static String string(String url, Map<String, String> headers) {
        if (url == null || !url.startsWith("http")) return "";
        try (Response res = newCall(url, headers).execute()) {
            return res.body() != null ? res.body().string() : "";
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static Call newCall(String url) {
        return client().newCall(new Request.Builder().url(url).build());
    }

    public static Call newCall(String url, String tag) {
        return client().newCall(new Request.Builder().url(url).tag(tag).build());
    }

    public static Call newCall(OkHttpClient client, String url) {
        return client.newCall(new Request.Builder().url(url).build());
    }

    public static Call newCall(OkHttpClient client, String url, String tag) {
        return client.newCall(new Request.Builder().url(url).tag(tag).build());
    }

    public static Call newCall(String url, Map<String, String> headers) {
        return client().newCall(new Request.Builder().url(url).headers(headers(headers)).build());
    }

    public static Call newCall(String url, Map<String, String> headers, ArrayMap<String, String> params) {
        return client().newCall(new Request.Builder().url(buildUrl(url, params)).headers(headers(headers)).build());
    }

    public static Call newCall(String url, Map<String, String> headers, RequestBody body) {
        return client().newCall(new Request.Builder().url(url).headers(headers(headers)).post(body).build());
    }

    public static Call newCall(String url, RequestBody body, String tag) {
        return client().newCall(new Request.Builder().url(url).post(body).tag(tag).build());
    }

    public static Call newCall(OkHttpClient client, String url, RequestBody body) {
        return client.newCall(new Request.Builder().url(url).post(body).build());
    }

    public static void cancel(String tag) {
        cancel(client(), tag);
    }

    public static void cancel(OkHttpClient client, String tag) {
        if (client == null || tag == null) return;
        for (Call call : client.dispatcher().queuedCalls()) if (tag.equals(call.request().tag())) call.cancel();
        for (Call call : client.dispatcher().runningCalls()) if (tag.equals(call.request().tag())) call.cancel();
    }

    public static void cancelAll() {
        cancelAll(client());
    }

    public static void cancelAll(OkHttpClient client) {
        if (client != null) client.dispatcher().cancelAll();
    }

    public static FormBody toBody(ArrayMap<String, String> params) {
        FormBody.Builder body = new FormBody.Builder();
        if (params != null) for (Map.Entry<String, String> entry : params.entrySet()) body.add(entry.getKey(), entry.getValue());
        return body.build();
    }

    private static Headers headers(Map<String, String> headers) {
        return headers == null ? new Headers.Builder().build() : Headers.of(headers);
    }

    private static void setOkHttpSsl(OkHttpClient.Builder builder) {
        try {
            SSLSocketFactory sslSocketFactory = new SSLSocketFactoryCompat(TRUST_ALL_CERT);
            builder.sslSocketFactory(sslSocketFactory, TRUST_ALL_CERT);
            builder.hostnameVerifier((hostname, session) -> true);
        } catch (Throwable ignored) {
        }
    }

    private static final X509TrustManager TRUST_ALL_CERT = new X509TrustManager() {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[]{};
        }
    };

    private static HttpUrl buildUrl(String url, ArrayMap<String, String> params) {
        HttpUrl.Builder builder = HttpUrl.parse(url).newBuilder();
        if (params != null) for (Map.Entry<String, String> entry : params.entrySet()) builder.addQueryParameter(entry.getKey(), entry.getValue());
        return builder.build();
    }
}