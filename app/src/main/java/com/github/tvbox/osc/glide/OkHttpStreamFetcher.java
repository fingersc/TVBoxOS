package com.github.tvbox.osc.glide;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.HttpException;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.data.DataFetcher.DataCallback;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.util.ContentLengthInputStream;
import com.bumptech.glide.util.Preconditions;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class OkHttpStreamFetcher implements DataFetcher<InputStream>, Callback {
    private final Call.Factory client;
    private final Call.Factory retryClient;   // 可空：失败后"换源重试"用
    private final GlideUrl url;
    private InputStream stream;
    private ResponseBody responseBody;
    private DataCallback<? super InputStream> callback;
    private Request request;                  // 保存以便失败重试
    private boolean retried = false;

    /**
     * 公共图片中转前缀（v99-r99）。
     * 直连被网络 RST（如 WiFi 对部分图床域名的连接级阻断）时，把图片 URL 交给该中转再取一次。
     * 只对"直连真失败"的请求生效，直连成功的图片不经过它。换域名只改这一处。
     */
    private static final String IMG_PROXY_BASE = "https://images.weserv.nl/?url=";

    @Nullable
    private volatile Call call;

    public OkHttpStreamFetcher(Call.Factory client, GlideUrl url) {
        this(client, null, url);
    }

    public OkHttpStreamFetcher(Call.Factory client, Call.Factory retryClient, GlideUrl url) {
        this.client = client;
        this.retryClient = retryClient;
        this.url = url;
    }

    @Override
    public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super InputStream> callback) {
        Request.Builder requestBuilder = new Request.Builder().url(url.toStringUrl());
        for (Map.Entry<String, String> headerEntry : url.getHeaders().entrySet()) {
            String key = headerEntry.getKey();
            String value = headerEntry.getValue();
            if (key != null && value != null) requestBuilder.addHeader(key, value);
        }
        Request request = requestBuilder.tag(com.github.catvod.net.OkHttp.IMAGE_TAG).build();
        this.request = request;
        this.callback = callback;
        call = client.newCall(request);
        call.enqueue(this);
    }

    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
        // 被取消的请求不再重试（否则会"复活"一个已经作废的加载）。
        if (call.isCanceled()) {
            DataCallback<? super InputStream> local = callback;
            if (local != null) local.onLoadFailed(e);
            return;
        }
        // v99-r99：直连失败（如 WiFi 对图床域名的连接级 RST）后，改走公共中转再取一次。
        if (tryProxyRetry()) return;
        DataCallback<? super InputStream> local = callback;
        if (local != null) local.onLoadFailed(e);
    }

    /**
     * v99-r99：直连失败后，改用公共图片中转（默认 images.weserv.nl）把同一图片再取一次。
     *
     * <p>背景：部分网络（尤其某些 WiFi 宽带）会对特定"图床域名"做连接级重置
     * （ERR_CONNECTION_RESET）。这种阻断发生在 DNS 之后，换 DNS/换 IP 都无效；
     * 唯一实用绕法是让图片从一个未被阻断的中转地址进来：中转去源站取图、再回传给本机，
     * 对本机而言只是访问 weserv 域名，从而避开原图床域名的阻断。</p>
     *
     * <p>只重试一次、且只对"确实失败"的请求生效（直连成功的图片不经过中转）；
     * 用备用客户端 retryClient（其 DNS 为备用源并集）发出，顺带换一组解析。</p>
     */
    private boolean tryProxyRetry() {
        if (retried || request == null) return false;
        String proxy = proxyUrl(url.toStringUrl());
        if (proxy == null) return false;
        retried = true;
        try {
            Request proxyReq = request.newBuilder().url(proxy).build();
            this.request = proxyReq;
            Call.Factory cf = retryClient != null ? retryClient : client;
            Call retryCall = cf.newCall(proxyReq);
            this.call = retryCall;
            retryCall.enqueue(this);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把原图 URL 换成"公共中转 URL"；不适合代理的（data:/已是中转/本地proxy/非http）返回 null。 */
    private static String proxyUrl(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (u.isEmpty()) return null;
        if (u.startsWith("data:")) return null;
        if (u.contains("images.weserv.nl") || u.contains("wsrv.nl")) return null;
        if (u.startsWith("proxy://") || u.contains("/proxy?")) return null;
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null;
        try {
            return IMG_PROXY_BASE + java.net.URLEncoder.encode(u, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) {
        responseBody = response.body();
        DataCallback<? super InputStream> local = callback;
        if (local == null) return;
        if (response.isSuccessful()) {
            long contentLength = Preconditions.checkNotNull(responseBody).contentLength();
            stream = ContentLengthInputStream.obtain(responseBody.byteStream(), contentLength);
            local.onDataReady(stream);
        } else {
            int code = response.code();
            // 403/451 多为防盗链/WAF：先关掉本次响应体，再走中转重取一次
            if ((code == 403 || code == 451) && tryProxyRetry()) {
                if (responseBody != null) responseBody.close();
                return;
            }
            local.onLoadFailed(new HttpException(response.message(), code));
        }
    }

    @Override
    public void cleanup() {
        try {
            if (stream != null) stream.close();
        } catch (IOException ignored) {
        }
        if (responseBody != null) responseBody.close();
        callback = null;
    }

    @Override
    public void cancel() {
        Call local = call;
        if (local != null) local.cancel();
    }

    @NonNull
    @Override
    public Class<InputStream> getDataClass() {
        return InputStream.class;
    }

    @NonNull
    @Override
    public DataSource getDataSource() {
        return DataSource.REMOTE;
    }
}
