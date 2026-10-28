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
     * 封面/图片专用 client（v99-r98）：
     * 1) 缩短超时 —— 死链快速失败，不长期占用连接槽；
     * 2) DNS 复用与播放/API 一致的双栈优选（CustomDns → DualStackResolver）：只排序、不删除，
     *    能吃"上次哪个地址真连通过"的证据；不再用原先仅"IPv4 排前"的简易排序。
     * 仅用于图片加载，不影响播放/解析链路。
     */
    public static OkHttpClient imageClient() {
        return client().newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .dns(OkGoHelper.imageDns())
                .build();
    }

    /**
     * 图片加载"换源重试"client（v99-r98）：配置同 {@link #imageClient()}，仅 DNS 换成
     * "备用 DoH ∪ 系统 DNS"的并集。只在图片直连失败后由 OkHttpStreamFetcher 重试一次时使用。
     */
    public static OkHttpClient imageRetryClient() {
        return client().newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .dns(OkGoHelper.altImageDns())
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
