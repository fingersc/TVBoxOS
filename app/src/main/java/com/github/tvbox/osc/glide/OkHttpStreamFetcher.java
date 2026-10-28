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
        // v99-r98：第一次失败后，换"备用 DoH ∪ 系统 DNS"的解析源重试一次（换一组地址再试）。
        if (tryRetry()) return;
        DataCallback<? super InputStream> local = callback;
        if (local != null) local.onLoadFailed(e);
    }

    /**
     * 失败重试一次：直连失败（多为选中被 RST 的坏 IPv4 / IPv6 黑洞）后，改用 retryClient
     * （其 DNS 为备用源并集）重新发起同一请求，尽量换到可用边缘 IP。只重试一次。
     */
    private boolean tryRetry() {
        if (retried || retryClient == null || request == null) return false;
        retried = true;
        try {
            Call retryCall = retryClient.newCall(request);
            this.call = retryCall;
            retryCall.enqueue(this);
            return true;
        } catch (Throwable t) {
            return false;
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
            local.onLoadFailed(new HttpException(response.message(), response.code()));
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
