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

    /**
     * 进程级共享的 OkHttpClient。
     *
     * <p>★ v99-r9（审查 P1-4）：原来每个 WebDavClient 实例都 {@code new OkHttpClient.Builder()...}，
     * 而同步一次会新建 WebDavClient，于是 <b>每次同步都重建连接池与线程池</b>，
     * 一条同步里的 4~6 个请求（4 个 scope 的 GET + PUT）本来能复用同一条 TLS 连接，
     * 结果每次都要重新握手。改成进程级单例后，连接可跨次同步复用。</p>
     */
    private static volatile OkHttpClient SHARED;

    private static OkHttpClient sharedClient() {
        OkHttpClient c = SHARED;
        if (c == null) {
            synchronized (WebDavClient.class) {
                c = SHARED;
                if (c == null) {
                    c = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(20, TimeUnit.SECONDS)
                            .writeTimeout(20, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                    SHARED = c;
                }
            }
        }
        return c;
    }

    public WebDavClient(String url, String user, String password) {
        HttpUrl parsed = url == null ? null : HttpUrl.parse(url.trim());
        if (parsed == null) {
            throw new IllegalArgumentException("WebDAV 地址无效");
        }
        this.base = parsed;
        this.auth = Credentials.basic(user == null ? "" : user, password == null ? "" : password);
        this.client = sharedClient();
    }

    /** 5xx 起手就重试的间隔：服务端抖动很常见，不值得为此走 30 秒退避。 */
    private static final long SERVER_ERR_RETRY_DELAY_MS = 500L;

    /**
     * ★ v99-r10 P3-3：执行请求；遇到 <b>5xx</b> 立即重试一次。
     *
     * <p>原来 500/502/503 会直接抛 IOException ⇒ 计一次失败 ⇒ 整个同步退避 30 秒，
     * 而这类错误多半是服务端瞬时抖动，隔 500ms 再试基本就好。
     * GET / 条件 GET / PUT 都是幂等的，重试安全。</p>
     *
     * <p>只重试一次：连两次 5xx 说明不是抖动，交给上层的退避熔断处理。</p>
     */
    private Response exec(Request req) throws IOException {
        Response resp = client.newCall(req).execute();
        int code = resp.code();
        if (code >= 500 && code < 600) {
            resp.close();
            try {
                Thread.sleep(SERVER_ERR_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("重试被中断");
            }
            resp = client.newCall(req).execute();
        }
        return resp;
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
        try (Response resp = exec(req)) {
            checkAuth(resp);
            int code = resp.code();
            if (code != 207 && code != 200 && code != 204) {
                throw new IOException("服务器返回 " + code);
            }
        }
    }

    /** 目录是否存在（PROPFIND Depth 0）；404 返回 false，其它异常抛 IOException。 */
    public boolean dirExists(String path) throws IOException {
        Request req = new Request.Builder()
                .url(url(path))
                .header("Authorization", auth)
                .header("Depth", "0")
                .method("PROPFIND", RequestBody.create(null, new byte[0]))
                .build();
        try (Response resp = exec(req)) {
            checkAuth(resp);
            if (resp.code() == 404) {
                return false;
            }
            return resp.code() == 207 || resp.code() == 200 || resp.code() == 204;
        }
    }

    /**
     * ★ v99-r21：单独取文件的 ETag（PROPFIND Depth 0，只读响应头/XML，不下载内容）。
     *
     * <p><b>为什么需要</b>：坚果云等 WebDAV 服务端的 <b>PUT 响应不带 ETag</b>，
     * 于是 {@code put()} 返回 null ⇒ 本地 ETag 被清空 ⇒ 之后所有条件请求都退化成
     * 200 全量下载（实测日志里 {@code skip=304} 从此再没出现过，全是
     * {@code skip=same down=NNNN}）。全量下载本身就要流量，更麻烦的是每次都会进入
     * 「合并后与云端比对」分支：只要本地进度有一点点漂移就判定 same=false 并上传，
     * 而上传又清空 ETag ⇒ 形成「每 5 分钟传一次 progress.bin」的自锁循环。</p>
     *
     * <p>PUT 后拿不到 ETag 时调用本方法补一次（1 个 PROPFIND 请求，无 body）。</p>
     *
     * @return ETag；服务端未提供返回 null
     */
    /** 从 PROPFIND 207 多状态体里取 getetag（命名空间前缀各家不同，宽松匹配），静态预编译。 */
    private static final java.util.regex.Pattern ETAG_PATTERN =
            java.util.regex.Pattern.compile("<[^>]*:?getetag>\\s*([^<]+?)\\s*</[^>]*:?getetag>",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    public String getEtag(String path) throws IOException {
        Request req = new Request.Builder()
                .url(url(path))
                .header("Authorization", auth)
                .header("Depth", "0")
                .method("PROPFIND", RequestBody.create(null, new byte[0]))
                .build();
        try (Response resp = exec(req)) {
            checkAuth(resp);
            if (!resp.isSuccessful()) {
                return null;
            }
            String h = resp.header("ETag");
            if (h != null && h.length() > 0) {
                return h;
            }
            // 响应头没有 ⇒ 从 207 多状态体的 XML 里取 <...:getetag>"xxx"</...:getetag>
            //   命名空间前缀各家不同（D:/d:/lp1: 等），用宽松正则匹配任意前缀
            String body = resp.body() == null ? "" : resp.body().string();
            java.util.regex.Matcher m = ETAG_PATTERN.matcher(body);
            if (m.find()) {
                String v = m.group(1).trim();
                // 去掉 XML 实体与可能的引号包裹
                v = v.replace("&quot;", "\"").replace("&apos;", "'");
                return v;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取文件；不存在返回 null。etagOut[0] 会带回 ETag（若有）。 */
    public byte[] get(String path, String[] etagOut) throws IOException {
        Request req = new Request.Builder()
                .url(url(path))
                .header("Authorization", auth)
                .get()
                .build();
        try (Response resp = exec(req)) {
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

    /** HTTP 304：条件请求命中（内容未变） */
    public static final int HTTP_NOT_MODIFIED = 304;

    /**
     * 条件读取（v98 P1）：带 {@code If-None-Match}。
     *
     * <p>ETag 未变时服务端返回 304，此时返回 null 且 {@code notModified[0]=true}，
     * 调用方据此"零下载 / 零解密 / 零解析 / 零写库"早退 —— 稳态同步从
     * 3~4 个请求 + 23~56KB 传输降到 1 个小请求 + 0 传输。</p>
     */
    public byte[] getIfNoneMatch(String path, String etag, boolean[] notModified, String[] etagOut) throws IOException {
        Request.Builder b = new Request.Builder()
                .url(url(path))
                .header("Authorization", auth)
                .get();
        if (etag != null && !etag.isEmpty()) {
            b.header("If-None-Match", etag);
        }
        try (Response resp = exec(b.build())) {
            checkAuth(resp);
            if (resp.code() == HTTP_NOT_MODIFIED) {
                if (notModified != null && notModified.length > 0) {
                    notModified[0] = true;
                }
                return null;
            }
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

    /**
     * 写入文件；ifMatch 非空时带 If-Match（并发写冲突会抛 ConflictException）。
     *
     * <p>★ v99-r8：返回服务端在响应里给的新 ETag（可能为 null）。
     * 调用方应把它记进 {@code SyncState.etags} —— 原来不返回 ETag，
     * 结果本地一直留着「PUT 之前」的旧 ETag，下一次条件请求必然 200（整包重下）。
     * </p>
     */
    public String put(String path, byte[] data, String ifMatch) throws IOException {
        Request.Builder b = new Request.Builder()
                .url(url(path))
                .header("Authorization", auth)
                .put(RequestBody.create(OCTET, data));
        if (ifMatch != null && ifMatch.length() > 0) {
            b.header("If-Match", ifMatch);
        }
        try (Response resp = exec(b.build())) {
            checkAuth(resp);
            if (resp.code() == 412 || (resp.code() == 409 && ifMatch != null)) {
                throw new ConflictException();
            }
            if (!resp.isSuccessful()) {
                throw new IOException("写入失败：" + resp.code());
            }
            return resp.header("ETag");
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
                try (Response resp = exec(req)) {
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