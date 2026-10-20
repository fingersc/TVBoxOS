package com.github.tvbox.osc.sync;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 极简 WebDAV 客户端（只用 GET / PUT / MKCOL / PROPFIND）。
 *
 * <p>兼容坚果云、InfiniCLOUD、Koofr、pCloud、群晖等标准 WebDAV。
 * 只做同步需要的最小集合，避免引入额外依赖。</p>
 */
public final class WebDavClient {

    private static final MediaType OCTET = MediaType.parse("application/octet-stream");

    public static class ConflictException extends IOException {
    }

    public static class AuthException extends IOException {
        public AuthException(String msg) {
            super(msg);
        }
    }

    private final OkHttpClient client;
    private final HttpUrl base;
    private final String auth;

    public WebDavClient(String url, String user, String password) {
        HttpUrl parsed = url == null ? null : HttpUrl.parse(url.trim());
        if (parsed == null) {
            throw new IllegalArgumentException("WebDAV 地址无效");
        }
        this.base = parsed;
        this.auth = Credentials.basic(user == null ? "" : user, password == null ? "" : password);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private HttpUrl url(String path) {
        HttpUrl.Builder b = base.newBuilder();
        if (path != null) {
            for (String seg : path.split("/")) {
                if (seg.length() > 0) {
                    b.addPathSegment(seg);
                }
            }
        }
        return b.build();
    }

    /** 测试连接：对根目录做 PROPFIND（Depth 0），207/200 视为成功。 */
    public void test() throws IOException {
        Request req = new Request.Builder()
                .url(url(""))
                .header("Authorization", auth)
                .header("Depth", "0")
                .method("PROPFIND", RequestBody.create(null, new byte[0]))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            checkAuth(resp);
            int code = resp.code();
            if (code != 207 && code != 200 && code != 204) {
                throw new IOException("服务器返回 " + code);
            }
        }
    }

    /** 读取文件；不存在返回 null。etagOut[0] 会带回 ETag（若有）。 */
    public byte[] get(String path, String[] etagOut) throws IOException {
        Request req = new Request.Builder()
                .url(url(path))
                .header("Authorization", auth)
                .get()
                .build();
        try (Response resp = client.newCall(req).execute()) {
            checkAuth(resp);
            if (resp.code() == 404) {
                return null;
            }
            if (!resp.isSuccessful()) {
                throw new IOException("读取失败：" + resp.code());
            }
            if (etagOut != null && etagOut.length > 0) {
                etagOut[0] = resp.header("ETag");
            }
            return resp.body() == null ? new byte[0] : resp.body().bytes();
        }
    }

    /** 写入文件；ifMatch 非空时带 If-Match（并发写冲突会抛 ConflictException）。 */
    public void put(String path, byte[] data, String ifMatch) throws IOException {
        Request.Builder b = new Request.Builder()
                .url(url(path))
                .header("Authorization", auth)
                .put(RequestBody.create(OCTET, data));
        if (ifMatch != null && ifMatch.length() > 0) {
            b.header("If-Match", ifMatch);
        }
        try (Response resp = client.newCall(b.build()).execute()) {
            checkAuth(resp);
            if (resp.code() == 412 || resp.code() == 409 && ifMatch != null) {
                throw new ConflictException();
            }
            if (!resp.isSuccessful()) {
                throw new IOException("写入失败：" + resp.code());
            }
        }
    }

    /** 逐级创建目录（已存在或不允许创建都忽略）。 */
    public void ensureDirs(String path) {
        if (path == null) {
            return;
        }
        StringBuilder cur = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.length() == 0) {
                continue;
            }
            cur.append(seg).append('/');
            try {
                Request req = new Request.Builder()
                        .url(url(cur.toString()))
                        .header("Authorization", auth)
                        .method("MKCOL", RequestBody.create(null, new byte[0]))
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    // 201 创建成功；405 已存在；401 由后续 PUT 统一报错
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void checkAuth(Response resp) throws AuthException {
        if (resp.code() == 401 || resp.code() == 403) {
            throw new AuthException("认证失败（请检查账号与「应用密码」）");
        }
    }
}
