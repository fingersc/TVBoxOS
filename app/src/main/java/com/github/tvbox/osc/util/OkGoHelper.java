package com.github.tvbox.osc.util;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.ProxyRule;
import com.github.tvbox.osc.player.danmu.Parser;
import com.github.tvbox.osc.picasso.MyOkhttpDownLoader;
import com.github.tvbox.osc.util.net.OkProxySelector;
import com.github.tvbox.osc.util.net.ProxyAuthenticator;
import com.github.tvbox.osc.util.SSL.SSLSocketFactoryCompat;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.https.HttpsUtils;
import com.lzy.okgo.interceptor.HttpLoggingInterceptor;
import com.lzy.okgo.model.HttpHeaders;
import com.orhanobut.hawk.Hawk;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;
import okhttp3.internal.Version;
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;


public class OkGoHelper {
    public static final long DEFAULT_MILLISECONDS = 20000;      //默认的超时时间
    /**
     * v97：建连（TCP + TLS）超时，单位毫秒。
     *
     * <p>与 read/write 分开管理：connect 表达"一个连不上的地址最多浪费多久"。
     * 从 20s 收到 8s —— 本机/路由器没有可用 IPv6 时 OkHttp 会先试 AAAA，
     * 那条连接要挂满 connectTimeout 才回退 IPv4（实测 20.8 秒黑洞）。</p>
     */
    public static final long CONNECT_TIMEOUT_MS = 8000L;

    // 内置doh json
    private static final String dnsConfigJson = "["
            + "{\"name\": \"腾讯\", \"url\": \"https://doh.pub/dns-query\"},"
            + "{\"name\": \"阿里\", \"url\": \"https://dns.alidns.com/dns-query\"},"
            + "{\"name\": \"360\", \"url\": \"https://doh.360.cn/dns-query\"}"
            + "]";
    static OkHttpClient ItvClient = null;
    private static OkProxySelector proxySelector = null;
    private static ProxyAuthenticator proxyAuthenticator = null;

    /** 全部 OkHttp 日志拦截器：调试模式切换时即时改级别，无需重启。 */
    private static final List<HttpLoggingInterceptor> HTTP_LOG_INTERCEPTORS = new ArrayList<>();

    /** 创建受调试模式总闸管理的 OkHttp 日志拦截器（替代原先各处重复的 if/else 配置）。 */
    private static HttpLoggingInterceptor newHttpLogInterceptor(String name) {
        HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor(name);
        HTTP_LOG_INTERCEPTORS.add(interceptor);
        interceptor.setPrintLevel(LOG.isDebugEnabled()
                ? HttpLoggingInterceptor.Level.BODY : HttpLoggingInterceptor.Level.NONE);
        interceptor.setColorLevel(LOG.isDebugEnabled() ? Level.INFO : Level.OFF);
        return interceptor;
    }

    /** 调试模式开 ⇒ BODY 级全量打印；关 ⇒ NONE（完全静默）。 */
    public static void applyHttpDebug(boolean open) {
        for (HttpLoggingInterceptor interceptor : HTTP_LOG_INTERCEPTORS) {
            interceptor.setPrintLevel(open
                    ? HttpLoggingInterceptor.Level.BODY : HttpLoggingInterceptor.Level.NONE);
            interceptor.setColorLevel(open ? Level.INFO : Level.OFF);
        }
        // v97：爬虫 client 的拦截器等级是创建时固定的，切换调试模式后重建一次让它跟上
        com.github.catvod.net.OkHttp.resetClient();
    }

    public static synchronized OkProxySelector proxySelector() {
        if (proxySelector == null) proxySelector = new OkProxySelector();
        return proxySelector;
    }

    public static synchronized ProxyAuthenticator proxyAuthenticator() {
        if (proxyAuthenticator == null) proxyAuthenticator = new ProxyAuthenticator(proxySelector());
        return proxyAuthenticator;
    }

    public static synchronized void setProxyList(List<ProxyRule> proxyRules) {
        proxySelector().clear();
        if (proxyRules != null && !proxyRules.isEmpty()) proxySelector().addAll(proxyRules);
        com.github.catvod.net.OkHttp.reset();
    }

    static void initExoOkHttpClient() {
        OkHttpClient base = getDefaultClient();
        OkHttpClient.Builder builder = base != null ? base.newBuilder() : new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = newHttpLogInterceptor("OkExoPlayer");
        builder.addInterceptor(loggingInterceptor);

        builder.retryOnConnectionFailure(true);
        builder.followRedirects(true);
        builder.followSslRedirects(true);
        builder.proxySelector(proxySelector());
        builder.proxyAuthenticator(proxyAuthenticator());

        // ── 流媒体连接复用与超时调优 ──
        //
        // 这段是针对"网速够但部分站点卡顿"的一项服务端无关优化：所有播放请求
        // 都经过这个 client（ExoMediaSourceHelper.setOkClient(ItvClient)），
        // 所以在这里调一次即全局生效。
        //
        //   · 连接池
        //     默认连接池是 5 个空闲连接 / 5 分钟。HLS 播放时每个分片都是一次
        //     独立 HTTP 请求，若源站是"每片一个连接"的形态，默认池太小会让
        //     请求排队等复用、甚至频繁新建 TCP（每片都要三次握手 + TLS 协商，
        //     慢源上这一段往往就是卡顿的元凶）。提到 12 个空闲、保活 5 分钟，
        //     既覆盖分片并发、又不会长期占着文件描述符。
        //
        //   · 超时
        //     readTimeout 默认 10s 对慢源偏紧：慢源不是"断流"，而是"慢"，
        //     10 秒内没吐出下一个字节就抛 SocketTimeoutException → Exo 判定
        //     该分片失败 → 走 HlsErrorHandlingPolicy 重试/跳过 → 观众看到卡顿
        //     甚至跳片。放宽到 30s 让"慢但有响应"的源有机会把数据送完。
        //     connectTimeout 8s：连不上就快速失败并让上层换线，
        //     拉长只会让用户在"黑屏等起播"上浪费时间。
        //     writeTimeout 对流媒体无实际意义（请求体为空），保留默认即可。
        //
        //   · Dispatcher
        //     默认 maxRequests=64 / maxRequestsPerHost=5。分片下载是"同一 host
        //     多请求"，5 的上限会成为瓶颈（尤其在开了后续并行分片能力时）。
        //     提到 10 —— 不设更高是刻意的：单片源并发过高会互相抢带宽，
        //     对慢源反而不利（每个连接分到的速率更低，更易触发欠载）。
        try {
            builder.connectionPool(new ConnectionPool(12, 5, TimeUnit.MINUTES));
            builder.connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);   // 15s → 8s
            builder.readTimeout(30, TimeUnit.SECONDS);                            // 30s 保持不动
            Dispatcher dispatcher = new Dispatcher();
            dispatcher.setMaxRequests(64);
            dispatcher.setMaxRequestsPerHost(10);
            builder.dispatcher(dispatcher);
        } catch (Throwable th) {
            // 调优失败不应影响播放：宁可退回默认客户端，也不能让起播挂掉。
            th.printStackTrace();
        }

        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }

//        builder.dns(dnsOverHttps);
        builder.dns(new CustomDns());
        builder.eventListener(new NetEventListener());
        ItvClient=builder.build();

        ExoMediaSourceHelper.getInstance(App.getInstance()).setOkClient(ItvClient);
    }

    public static DnsOverHttps dnsOverHttps = null;

    /**
     * 图片加载"换源重试"专用的 DoH 实例（v99-r98）。
     *
     * <p>与用户当前 DoH 设置无关，恒指向内置"腾讯"DoH。仅在图片直连失败后、作为"换一个解析源
     * 再解析一次"用 —— 同一域名的不同解析源常给出不同的 CDN 边缘 IP，直连拿到被 RST 的坏 IP 时，
     * 换源往往能拿到可用 IP。只发生在"这次真请求已失败"之后，不影响正常请求，也不缓存"判死"。</p>
     */
    private static DnsOverHttps altDoh = null;

    public static ArrayList<String> dnsHttpsList = new ArrayList<>();

    public static boolean is_doh = false;
    public static Map<String, String> myHosts = null;

    public static String getDohUrl(int type) {
        String json=Hawk.get(HawkConfig.DOH_JSON,"");
        if(json.isEmpty())json=dnsConfigJson;
        JsonArray jsonArray = JsonParser.parseString(json).getAsJsonArray();
        if (type >= 1 && type < dnsHttpsList.size()) {
            JsonObject dnsConfig = jsonArray.get(type - 1).getAsJsonObject();
            return dnsConfig.get("url").getAsString();  // 获取对应的 URL
        }
        return "";
    }

    public static void setDnsList() {
        dnsHttpsList.clear();
        String json=Hawk.get(HawkConfig.DOH_JSON,"");
        if(json.isEmpty())json=dnsConfigJson;
        JsonArray jsonArray = JsonParser.parseString(json).getAsJsonArray();
        dnsHttpsList.add("关闭");
        for (int i = 0; i < jsonArray.size(); i++) {
            JsonObject dnsConfig = jsonArray.get(i).getAsJsonObject();
            String name = dnsConfig.has("name") ? dnsConfig.get("name").getAsString() : "Unknown Name";
            dnsHttpsList.add(name);
        }
        if(Hawk.get(HawkConfig.DOH_URL, 0)+1>dnsHttpsList.size())Hawk.put(HawkConfig.DOH_URL, 0);
        myHosts = ApiConfig.get().getMyHost();
    }

    private static List<InetAddress> DohIps(JsonArray ips) {
        List<InetAddress> inetAddresses = new ArrayList<>();
        if (ips != null) {
            for (int j = 0; j < ips.size(); j++) {
                try {
                    InetAddress inetAddress = InetAddress.getByName(ips.get(j).getAsString());
                    inetAddresses.add(inetAddress);  // 添加到 List 中
                } catch (Exception e) {
                    e.printStackTrace();  // 处理无效的 IP 字符串
                }
            }
        }
        return inetAddresses;
    }

    static void initDnsOverHttps() {
        Integer dohSelector=Hawk.get(HawkConfig.DOH_URL, 0);
        JsonArray ips=null;
        try {
            dnsHttpsList.clear();
            dnsHttpsList.add("关闭");
            String json=Hawk.get(HawkConfig.DOH_JSON,"");
            if(json.isEmpty())json=dnsConfigJson;
            JsonArray jsonArray = JsonParser.parseString(json).getAsJsonArray();
            if(dohSelector>jsonArray.size()) {
                Hawk.put(HawkConfig.DOH_URL, 0);
                dohSelector = 0;
            }
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject dnsConfig = jsonArray.get(i).getAsJsonObject();
                String name = dnsConfig.has("name") ? dnsConfig.get("name").getAsString() : "Unknown Name";
                dnsHttpsList.add(name);
                if(dohSelector==(i+1))ips = dnsConfig.has("ips") ? dnsConfig.getAsJsonArray("ips") : null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.proxySelector(proxySelector());
        builder.proxyAuthenticator(proxyAuthenticator());
        HttpLoggingInterceptor loggingInterceptor = newHttpLogInterceptor("OkExoPlayer");
        builder.addInterceptor(loggingInterceptor);
        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        builder.cache(new Cache(new File(App.getInstance().getCacheDir().getAbsolutePath(), "dohcache"), 100 * 1024 * 1024));
        OkHttpClient dohClient = builder.build();
        String dohUrl = getDohUrl(Hawk.get(HawkConfig.DOH_URL, 0));
//        if (!dohUrl.isEmpty()) is_doh = true;
        dnsOverHttps = new DnsOverHttps.Builder().client(dohClient).url(dohUrl.isEmpty() ? null : HttpUrl.get(dohUrl)).bootstrapDnsHosts((ips!=null && !dohUrl.equals("https://doh.pub/dns-query"))?DohIps(ips):null).build();
        try {
            // 图片失败重试用的"备用解析源"：恒用内置腾讯 DoH，与当前设置无关。
            String altUrl = getDohUrl(1);
            if (altUrl == null || altUrl.isEmpty()) altUrl = "https://doh.pub/dns-query";
            altDoh = new DnsOverHttps.Builder().client(dohClient).url(HttpUrl.get(altUrl)).bootstrapDnsHosts(null).build();
        } catch (Throwable ignored) {
            altDoh = null;
        }
    }

    // 自定义 DNS 解析器
    static class CustomDns implements Dns {
        private  ConcurrentHashMap<String, List<InetAddress>> map;
        private final String excludeIps = "2409:8087:6c02:14:100::14,2409:8087:6c02:14:100::18,39.134.108.253,39.134.108.245";

        // 接收外部注入的 DoH 实例
        public CustomDns() {
        }
        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
            String originalHost = hostname;
            Map<String, String> hosts = myHosts;
            if (hosts == null) hosts = ApiConfig.get().getMyHost();
            if(hosts != null && !hosts.isEmpty() && hosts.containsKey(hostname)) {
                hostname=hosts.get(hostname);
            }
            assert hostname != null;
            if (isValidIpAddress(hostname)) {
                return Collections.singletonList(InetAddress.getByName(hostname));
            }
            else {
                Dns dns = dnsOverHttps != null ? dnsOverHttps : Dns.SYSTEM;
                List<InetAddress> all = dns.lookup(hostname);
                // v97：双栈优选 —— 只排序、不删除地址。
                //      未命中缓存时 IPv4 排前、AAAA 保留在尾部（配合 8s connect 仍有兜底价值）；
                //      缓存命中（含真实连接观测）时把上次最快可达的地址排最前。
                // v99-r83：这里传 0（端口未知）—— Dns.lookup 的签名拿不到端口，原先写死 443，
                //      而写缓存用的是真实端口（直播流多为 80），key 永不相等 ⇒ 粘性排序从未生效、
                //      实际恒走"IPv4 优先"，遇到"两族后端不同"的源就会稳定 404。
                if (all != null && all.size() > 1) {
                    return DualStackResolver.order(all, hostname, 0);
                }
                return all;
            }
        }

        public synchronized void mapHosts(Map<String,String> hosts) throws UnknownHostException {
            map=new ConcurrentHashMap<>();
            for (Map.Entry<String, String> entry : hosts.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if(isValidIpAddress(value)){
                    map.put(key,Collections.singletonList(InetAddress.getByName(value)));
                }else {
                    map.put(key,getAllByName(value));
                }
            }
        }

        private List<InetAddress> getAllByName(String host) {
            try {
                // 获取所有与主机名关联的 IP 地址
                InetAddress[] allAddresses = InetAddress.getAllByName(host);
                if(excludeIps.isEmpty())return Arrays.asList(allAddresses);
                // 创建一个列表用于存储有效的 IP 地址
                List<InetAddress> validAddresses = new ArrayList<>();
                Set<String> excludeIpsSet = new HashSet<>();
                for (String ip : excludeIps.split(",")) {
                    excludeIpsSet.add(ip.trim());  // 添加到集合，去除多余的空格
                }
                for (InetAddress address : allAddresses) {
                    if (!excludeIpsSet.contains(address.getHostAddress())) {
                        validAddresses.add(address);
                    }
                }
                return validAddresses;
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }

        //简单判断减少开销
        private boolean isValidIpAddress(String str) {
            if (str.indexOf('.') > 0) return isValidIPv4(str);
            return str.indexOf(':') > 0;
        }

        private boolean isValidIPv4(String str) {
            String[] parts = str.split("\\.");
            if (parts.length != 4) return false;
            for (String part : parts) {
                try {
                    Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 图片加载用的 DNS：复用与播放/API 完全一致的双栈优选（{@link CustomDns} → {@link DualStackResolver}）。
     *
     * <p>v99-r98：原先图片用 {@code OkHttp.ipv4FirstDns()}，只把 IPv4 排前，<b>绕开</b>了
     * DualStackResolver 的"地址证据 / 轮转 / 粘性缓存"。于是同一个 host 上次明明在 104.21.x
     * 上连通过，图片仍可能从 172.67.x 裸试起。这里直接复用 CustomDns，图片也能吃到
     * "上次哪个地址真连通过"的证据（仍然只排序、不删除）。</p>
     */
    public static Dns imageDns() {
        return new CustomDns();
    }

    /**
     * 图片加载"换源重试"用的 DNS（v99-r98）。
     *
     * <p>取"备用 DoH（腾讯）∪ 系统 DNS"的并集并去重，再交给 DualStackResolver 排序。
     * 目的：直连失败（多半是选中了被 RST 的坏 IPv4，或 IPv6 黑洞）后，换一个/多个解析源
     * 重新拿一组地址，尽量把可用边缘 IP 也纳入这次重试 —— OkHttp 会按顺序把返回地址都试一遍。</p>
     *
     * <p>仍然是"只排序、不删除"，且只在"真请求失败一次之后"才走，符合项目铁律。</p>
     */
    public static Dns altImageDns() {
        return hostname -> {
            List<InetAddress> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            // 1) 备用 DoH（腾讯）
            try {
                if (altDoh != null) {
                    for (InetAddress a : altDoh.lookup(hostname)) {
                        if (a != null && seen.add(a.getHostAddress())) out.add(a);
                    }
                }
            } catch (Throwable ignored) {
            }
            // 2) 系统 DNS
            try {
                for (InetAddress a : Dns.SYSTEM.lookup(hostname)) {
                    if (a != null && seen.add(a.getHostAddress())) out.add(a);
                }
            } catch (Throwable ignored) {
            }
            if (out.isEmpty()) throw new UnknownHostException(hostname);
            return out.size() > 1 ? DualStackResolver.order(out, hostname, 0) : out;
        };
    }

    static OkHttpClient defaultClient = null;
    static OkHttpClient noRedirectClient = null;

    public static OkHttpClient getDefaultClient() {
        return defaultClient;
    }

    public static OkHttpClient getNoRedirectClient() {
        return noRedirectClient;
    }

    public static OkHttpClient getItvClient() {
        return ItvClient;
    }

    public static void init() {
        initDnsOverHttps();

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = newHttpLogInterceptor("OkGo");

        //builder.retryOnConnectionFailure(false);

        builder.addInterceptor(loggingInterceptor);

        builder.readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS);      // 读：20s 不动
        builder.writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS);     // 写：20s 不动
        builder.connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);     // 连：20s → 8s

        builder.dns(new CustomDns());
        builder.proxySelector(proxySelector());
        builder.proxyAuthenticator(proxyAuthenticator());
        // v87：网络分段计时（DNS/建连/TLS/TTFB），只在慢时打印
        builder.eventListener(new NetEventListener());
        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }

        HttpHeaders.setUserAgent(Version.userAgent());

        OkHttpClient okHttpClient = builder.build();
        OkGo.getInstance().setOkHttpClient(okHttpClient);

        defaultClient = okHttpClient;

        builder.followRedirects(false);
        builder.followSslRedirects(false);
        // v97：给无重定向客户端独立 tag，便于和默认 client / 爬虫 / 播放器区分
        builder.addInterceptor(newHttpLogInterceptor("OkNoRedirect"));
        noRedirectClient = builder.build();

        initExoOkHttpClient();
        initPicasso(okHttpClient);
    }

    public static synchronized void reloadDns() {
        initDnsOverHttps();

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = newHttpLogInterceptor("OkGo");

        builder.addInterceptor(loggingInterceptor);

        builder.readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS);      // 读：20s 不动
        builder.writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS);     // 写：20s 不动
        builder.connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);     // 连：20s → 8s

        builder.dns(new CustomDns());
        builder.proxySelector(proxySelector());
        builder.proxyAuthenticator(proxyAuthenticator());
        builder.eventListener(new NetEventListener());
        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }

        HttpHeaders.setUserAgent(Version.userAgent());

        OkHttpClient okHttpClient = builder.build();
        OkGo.getInstance().setOkHttpClient(okHttpClient);

        defaultClient = okHttpClient;

        builder.followRedirects(false);
        builder.followSslRedirects(false);
        // v97：给无重定向客户端独立 tag，便于和默认 client / 爬虫 / 播放器区分
        builder.addInterceptor(newHttpLogInterceptor("OkNoRedirect"));
        noRedirectClient = builder.build();

        initExoOkHttpClient();
        Parser.resetHttpClient();
        com.github.catvod.net.OkHttp.resetClient();
    }

    static void initPicasso(OkHttpClient client) {
        client.dispatcher().setMaxRequestsPerHost(10);
        MyOkhttpDownLoader downloader = new MyOkhttpDownLoader(client);
        Picasso picasso = new Picasso.Builder(App.getInstance())
                .downloader(downloader)
                .defaultBitmapConfig(Bitmap.Config.RGB_565)
                .build();
        Picasso.setSingletonInstance(picasso);
    }

    private static synchronized void setOkHttpSsl(OkHttpClient.Builder builder) {
        try {
            // 自定义一个信任所有证书的TrustManager，添加SSLSocketFactory的时候要用到
            final X509TrustManager trustAllCert =
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    };
            final SSLSocketFactory sslSocketFactory = new SSLSocketFactoryCompat(trustAllCert);
            builder.sslSocketFactory(sslSocketFactory, trustAllCert);
            builder.hostnameVerifier(HttpsUtils.UnSafeHostnameVerifier);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
