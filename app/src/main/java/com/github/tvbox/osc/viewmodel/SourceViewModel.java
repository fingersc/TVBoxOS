package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;

import android.util.Base64;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.catvod.net.OkHttp;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.GetRequest;
import com.orhanobut.hawk.Hawk;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;


import java.io.IOException;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class SourceViewModel extends ViewModel {
    private static final String PUSH_AGENT = "push_agent";
    private static final String PUSH_FALLBACK = "push_fallback";
    private static final String PUSH_HEADERS_MARKER = "@Headers=";
    /**
     * ★ v99-r43：detail 请求显式要全量集数。
     *
     * <p><b>为什么</b>：MacCMS 的 {@code ac=detail} 有默认 {@code limit}（实测 20/30），
     * 不传就会把 {@code vod_play_url} 按条数截断 —— 真机日志（2026-10-24，我家那闺女2026）
     * 里 {@code ac=detail&wd=} 只拿到 28 集，而同一影片从另一个源（忽略 limit）拿到 49 集。
     * 两侧正片序列长度不等 ⇒ {@code alignByMainFeatureRank} 的"正片序列位置映射"
     * （最强、最通用的一层）直接失效，整条链退化到期号换算/就近兜底，
     * 实测把「20260906第2期上」切到「20260830上」（少一期）。</p>
     *
     * <p>取 1000 而不是 0：0 在个别 CMS 上被当无效值回落默认，1000 覆盖全部真实集数
     * （单季综艺/电视剧极少超过 200 集），且遵守参数的站点会返回全量、忽略的站点行为不变。</p>
     */
    private static final String DETAIL_LIMIT = "1000";

    public MutableLiveData<AbsSortXml> sortResult;
    public MutableLiveData<AbsXml> listResult;
    public MutableLiveData<AbsXml> searchResult;
    public MutableLiveData<AbsXml> quickSearchResult;
    public MutableLiveData<AbsXml> detailFallbackSearchResult;
    public MutableLiveData<AbsXml> detailResult;
    /**
     * r134：攒池阶段「补拉候选源详情」的独立出口。
     *
     * <p>刻意不复用 {@link #detailResult}：详情页观察 detailResult 时会把它当作「当前播放源的详情」，
     * 补拉一个候选若也走这条出口，会把 settled / fallbackResult 判定带偏，干扰正常切源。</p>
     */
    public MutableLiveData<AbsXml> prefetchDetailResult;
    public MutableLiveData<JSONObject> actionResult;
    public MutableLiveData<JSONObject> playResult;
    public Gson gson;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger playRequestSeq = new AtomicInteger();

    /**
     * 聚合搜索会话：承载一轮搜索的状态与线程池，跨 Activity 重建存活。
     *
     * <p>搜索原本由 {@code SearchActivity} 自身持有，Activity 一销毁就全丢；
     * 迁到 ViewModel 后，进详情页/返回搜索页都不再打断正在跑的搜索，
     * 候选池也得以边搜边补（修掉「切源只有两三个站」的根因）。
     * 详见 {@link SearchSession}。</p>
     */
    private SearchSession searchSession;

    public SourceViewModel() {
        sortResult = new MutableLiveData<>();
        listResult = new MutableLiveData<>();
        searchResult = new MutableLiveData<>();
        quickSearchResult = new MutableLiveData<>();
        detailFallbackSearchResult = new MutableLiveData<>();
        detailResult = new MutableLiveData<>();
        prefetchDetailResult = new MutableLiveData<>();
        actionResult = new MutableLiveData<>();
        playResult = new MutableLiveData<>();
        gson=new Gson();
    }

    /**
     * 取得（惰性创建）搜索会话。
     *
     * <p><b>★ 返回的是进程级单例</b>（{@link SearchSession#getInstance()}），
     * 不是本 ViewModel 私有的一份。</p>
     *
     * <p><b>为什么必须是单例</b>：本项目每个 Activity 都
     * {@code new ViewModelProvider(this)}，SearchActivity 与 DetailActivity
     * 拿到的是<b>两个不同的 SourceViewModel</b>。若会话挂在实例上，
     * 详情页读到的必然是一份<b>全新空会话</b> —— 正是「搜索页候选在涨、
     * 详情页切源数量却不动」的根因。</p>
     *
     * <p>每次调用都把「自己」注册为触发通道（弱引用），保证
     * 「谁在搜，就用谁的 {@code getSearch} 发请求」；详情页只读候选池，
     * 即使覆盖了触发通道也不影响它读取。</p>
     */
    public SearchSession getSearchSession() {
        SearchSession session = SearchSession.getInstance();
        // 谁在用，谁就是当前触发通道（弱引用，不阻塞本 ViewModel 回收）。
        session.setViewModel(this);
        this.searchSession = session;
        return session;
    }

    public static final ExecutorService spThreadPool = Executors.newSingleThreadExecutor();
    private static final ExecutorService httpPrepareThreadPool = Executors.newFixedThreadPool(3);

    /**
     * 整包响应日志专用后台线程。
     *
     * <p><b>为什么必须挪出主线程</b>：OkGo 的 {@code onSuccess} 默认在主线程回调，
     * 直接 {@code LOG.i(json)} 会把几十~上百 KB 的响应体在主线程写进 logcat。
     * 详情页"切源一圈"会同时探测数十个源（每批 20 个），叠加调试模式的 BODY 级
     * 网络日志后，实测主线程一次卡顿 2.1s（Skipped 226 frames），
     * 首屏内容 8.8s 才出现——用户看到的就是"只有背景"的空白页。
     * 挪到后台线程后诊断信息一条不少，主线程零开销。</p>
     */
    private static final ExecutorService responseLogPool = Executors.newSingleThreadExecutor();

    /** 后台打印整包响应体（不阻塞主线程）。 */
    private static void logResponseBody(final String body) {
        if (body == null) {
            return;
        }
        responseLogPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    LOG.i(body);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    //homeContent缓存，最多存储5个sourceKey的AbsSortXml对象
    private static final Map<String, AbsSortXml> sortCache = new LinkedHashMap<String, AbsSortXml>(5, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Entry<String, AbsSortXml> eldest) {
            return size() > 5;
        }
    };

    private static void cacheSort(String sourceKey, AbsSortXml sortXml) {
        attachSortSource(sourceKey, sortXml);
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && !hasHomeRecVideos(sortXml)) {
            return;
        }
        if (!shouldBypassSortCache(sourceKey, sourceBean) && !hasActionSort(sortXml)) {
            sortCache.put(sourceKey, sortXml);
        }
    }

    public static void clearSortCache(String sourceKey) {
        sortCache.remove(sourceKey);
    }

    public static void clearRuntimeCache() {
        sortCache.clear();
        extendCache.clear();
    }

    private static AbsSortXml attachSortSource(String sourceKey, AbsSortXml sortXml) {
        if (sortXml != null) {
            sortXml.sourceKey = sourceKey;
        }
        return sortXml;
    }

    private void postSortResult(String sourceKey, AbsSortXml sortXml) {
        if (sortXml == null) {
            sortXml = new AbsSortXml();
        }
        sortResult.postValue(attachSortSource(sourceKey, sortXml));
    }

    private static boolean hasActionSort(AbsSortXml sortXml) {
        if (sortXml == null) return false;
        if (hasActionVideo(sortXml.videoList)) return true;
        return sortXml.list != null && hasActionVideo(sortXml.list.videoList);
    }

    private static boolean hasHomeRecVideos(AbsSortXml sortXml) {
        return sortXml != null && sortXml.videoList != null && !sortXml.videoList.isEmpty();
    }

    private static boolean hasActionVideo(List<Movie.Video> videos) {
        if (videos == null) return false;
        for (Movie.Video video : videos) {
            if (video != null && video.action != null) return true;
        }
        return false;
    }

    private static boolean shouldBypassSortCache(String sourceKey, SourceBean sourceBean) {
        return isFirstSource(sourceKey) && isDoubanSource(sourceBean);
    }

    private static boolean isFirstSource(String sourceKey) {
        List<SourceBean> sources = ApiConfig.get().getSourceBeanList();
        return !sources.isEmpty() && sources.get(0) != null && sourceKey.equals(sources.get(0).getKey());
    }

    private static boolean isDoubanSource(SourceBean sourceBean) {
        if (sourceBean == null) return false;
        return containsDouban(sourceBean.getKey())
                || containsDouban(sourceBean.getName())
                || containsDouban(sourceBean.getApi())
                || containsDouban(sourceBean.getExt());
    }

    private static boolean containsDouban(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String lower = value.toLowerCase();
        return lower.contains("douban") || value.contains("\u8c46\u74e3");
    }


    // homeContent
    public void getSort(final String sourceKey) {
        if (sourceKey == null) {
            sortResult.postValue(new AbsSortXml());
            return;
        }

        // 优先检查缓存
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            LOG.i("echo--getSort-source-null--" + sourceKey);
            postSortResult(sourceKey, null);
            return;
        }
        if(sourceBean.getName().length()<=3 && sourceBean.getName().endsWith("搜")){
            postSortResult(sourceKey, null);
            return;
        }

        if (!shouldBypassSortCache(sourceKey, sourceBean)) {
            AbsSortXml cached = sortCache.get(sourceKey);
            if (cached != null) {
                int homeRec = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC);
                boolean shouldUseCache = (homeRec != 1) || (cached.videoList != null && !cached.videoList.isEmpty());
                if (shouldUseCache) {
                    attachSortSource(sourceKey, cached);
                    postSortResult(sourceKey, cached);
                    return;
                }
            }
        }

        final int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            String json = sp.homeContent(true);
                            return json;
                        }
                    });
                    String sortJson = null;
                    try {
                        sortJson = future.get(30, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        LOG.i("echo--getSort-timeout--" + sourceBean.getKey());
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        Throwable cause = e.getCause();
                        LOG.i("echo--getSort-error--" + sourceBean.getKey() + "--" + e.getClass().getSimpleName() + "--" + (cause != null ? cause.getClass().getSimpleName() + ":" + cause.getMessage() : e.getMessage()));
                        e.printStackTrace();
                    } finally {
                        if (sortJson != null) {
                            final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                            attachSortSource(sourceKey, sortXml);
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
                                AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                    sortXml.videoList = absXml.movie.videoList;
                                    postSortResult(sourceKey, sortXml);
                                    cacheSort(sourceKey, sortXml);
                                } else {
                                    getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                        @Override
                                        public void done(List<Movie.Video> videos) {
                                            sortXml.videoList = videos;
                                            postSortResult(sourceKey, sortXml);
                                            cacheSort(sourceKey, sortXml);
                                        }
                                    });
                                }
                            } else {
                                postSortResult(sourceKey, sortXml);
                                cacheSort(sourceKey, sortXml);
                            }
                        } else {
                            postSortResult(sourceKey, null);
                        }
                        try {
                            executor.shutdown();
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                }
            };
            httpPrepareThreadPool.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_sort")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AbsSortXml sortXml = null;
                            if (type == 0) {
                                String xml = response.body();
                                sortXml = sortXml(sortResult, xml);
                            } else if (type == 1) {
                                String json = response.body();
                                sortXml = sortJson(sortResult, json);
                            }
                            attachSortSource(sourceKey, sortXml);
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && sortXml.list != null && sortXml.list.videoList != null && sortXml.list.videoList.size() > 0) {
                                ArrayList<String> ids = new ArrayList<>();
                                for (Movie.Video vod : sortXml.list.videoList) {
                                    ids.add(vod.id);
                                }
                                final AbsSortXml finalSortXml = sortXml;
                                getHomeRecList(sourceBean, ids, new HomeRecCallback() {
                                    @Override
                                    public void done(List<Movie.Video> videos) {
                                        finalSortXml.videoList = videos;
                                        postSortResult(sourceKey, finalSortXml);
                                        cacheSort(sourceKey, finalSortXml);
                                    }
                                });
                            } else {
                                postSortResult(sourceKey, sortXml);
                                cacheSort(sourceKey, sortXml);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            postSortResult(sourceKey, null);
                        }
                    });
        }else if (type == 4) {
            String extend=sourceBean.getExt();
            extend=getFixUrl(extend);
            if(URLEncoder.encode(extend).length()<1000){
                GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                        .tag(sourceBean.getKey() + "_sort")
                        .params("filter", "true");
                // 当 extend 不为空且非空字符串时添加参数
                if (extend != null && !extend.isEmpty()) {
                    request.params("extend", extend);
                }
                request.execute(new AbsCallback<String>() {
                            @Override
                            public String convertResponse(okhttp3.Response response) throws Throwable {
                                if (response.body() != null) {
                                    return response.body().string();
                                } else {
                                    throw new IllegalStateException("网络请求错误");
                                }
                            }

                            @Override
                            public void onSuccess(Response<String> response) {
                                String sortJson  = response.body();
                                if (sortJson != null) {
                                    final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                                    attachSortSource(sourceKey, sortXml);
                                    if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
                                        AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                            sortXml.videoList = absXml.movie.videoList;
                                            postSortResult(sourceKey, sortXml);
                                            cacheSort(sourceKey, sortXml);
                                        } else {
                                            getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                                @Override
                                                public void done(List<Movie.Video> videos) {
                                                    sortXml.videoList = videos;
                                                    postSortResult(sourceKey, sortXml);
                                                    cacheSort(sourceKey, sortXml);
                                                }
                                            });
                                        }
                                    } else {
                                        postSortResult(sourceKey, sortXml);
                                        cacheSort(sourceKey, sortXml);
                                    }
                                } else {
                                    postSortResult(sourceKey, null);
                                }
                            }

                            @Override
                            public void onError(Response<String> response) {
                                super.onError(response);
                                postSortResult(sourceKey, null);
                            }
                        });
            }else {
                try {
                    Map<String, String> params = new HashMap<>();
                    params.put("filter","true");
                    if (extend != null && !extend.isEmpty()) {
                        params.put("extend",extend);
                    }
                    RemoteTVBox.post(sourceBean.getApi(), params, new okhttp3.Callback() {
                        @Override
                        public void onFailure(@NonNull Call call, IOException e) {
                            postSortResult(sourceKey, null);
                        }

                        @Override
                        public void onResponse(@NonNull Call call, @NonNull okhttp3.Response response) throws IOException {
                            assert response.body() != null;
                            String sortJson = response.body().string();
                            final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                            attachSortSource(sourceKey, sortXml);
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
                                AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                    sortXml.videoList = absXml.movie.videoList;
                                    postSortResult(sourceKey, sortXml);
                                    cacheSort(sourceKey, sortXml);
                                }
                            } else {
                                postSortResult(sourceKey, sortXml);
                                cacheSort(sourceKey, sortXml);
                            }
                        }
                    });
                } catch (Exception ignored) {
                    postSortResult(sourceKey, null);
                }
            }
        } else {
            postSortResult(sourceKey, null);
        }
    }
    // categoryContent
    public void getList(MovieSort.SortData sortData, int page) {
        if (sortData == null) {
            LOG.i("echo-getList-sortData-null");
            listResult.postValue(null);
            return;
        }
        SourceBean homeSourceBean = ApiConfig.get().getHomeSourceBean();
        int type = homeSourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(homeSourceBean);
                            return sp.categoryContent(sortData.id, page + "", true, sortData.filterSelect);
                        }
                    });
                    String json = null;
                    try {
                        json = future.get(homeSourceBean.getPlayTimeoutSeconds(), TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        LOG.i("echo--getList-timeout--" + homeSourceBean.getKey());
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        Throwable cause = e.getCause();
                        LOG.i("echo--getList-error--" + homeSourceBean.getKey() + "--" + e.getClass().getSimpleName() + "--" + (cause != null ? cause.getClass().getSimpleName() + ":" + cause.getMessage() : e.getMessage()));
                        e.printStackTrace();
                    } finally {
                        executor.shutdown();
                        if (json != null) {
                            json(listResult, json,homeSourceBean.getKey());
                        } else {
                            listResult.postValue(null);
                        }
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(homeSourceBean.getApi())
                    .tag(homeSourceBean.getApi())
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("t", sortData.id)
                    .params("pg", page)
                    .params(sortData.filterSelect)
                    .params("f", (sortData.filterSelect == null || sortData.filterSelect.size() <= 0) ? "" : new JSONObject(sortData.filterSelect).toString())
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(listResult, xml, homeSourceBean.getKey());
                            } else {
                                String json = response.body();
                                json(listResult, json, homeSourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            listResult.postValue(null);
                        }
                    });
        }else if (type == 4) {
            String ext= "";
            String extend=homeSourceBean.getExt();
            extend=getFixUrl(extend);
            if (sortData.filterSelect != null && sortData.filterSelect.size() > 0) {
                try {
                    String selectExt = new JSONObject(sortData.filterSelect).toString();
                    ext = Base64.encodeToString(selectExt.getBytes("UTF-8"), Base64.DEFAULT |  Base64.NO_WRAP);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }else {
                ext = Base64.encodeToString("{}".getBytes(), Base64.DEFAULT |  Base64.NO_WRAP);
            }

            GetRequest<String> request = OkGo.<String>get(homeSourceBean.getApi())
                    .tag(homeSourceBean.getApi())
                    .params("ac", "detail")
                    .params("filter", "true")
                    .params("t", sortData.id)
                    .params("pg", page)
                    .params("ext", ext);
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            try {
                                if (response.body() != null) {
                                    return response.body().string();
                                } else {
                                    throw new IllegalStateException("网络请求错误，response body 为 null");
                                }
                            } catch (Exception e) {
                                LOG.i("echo-list: convertResponse error"+ e.getMessage());
                                throw e;  // 重新抛出异常
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
                            json(listResult, json, homeSourceBean.getKey());
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            listResult.postValue(null);
                        }
                    });

        } else {
            listResult.postValue(null);
        }
    }

    /** Loads a folder/category for a specific source (used by inline search folders). */
    public void getList(String sourceKey, String id) {
        final SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null || TextUtils.isEmpty(id)) {
            listResult.postValue(null);
            return;
        }
        final int type = sourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.categoryContent(id, "1", true, new HashMap<String, String>());
                        }
                    });
                    String json = null;
                    try {
                        json = future.get(sourceBean.getPlayTimeoutSeconds(), TimeUnit.SECONDS);
                    } catch (Throwable ignored) {
                        future.cancel(true);
                    } finally {
                        executor.shutdown();
                        if (json != null) {
                            json(listResult, json, sourceBean.getKey());
                        } else {
                            listResult.postValue(null);
                        }
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_folder")
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("t", id)
                    .params("pg", 1)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) return response.body().string();
                            throw new IllegalStateException("网络请求错误");
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) xml(listResult, response.body(), sourceBean.getKey());
                            else json(listResult, response.body(), sourceBean.getKey());
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            listResult.postValue(null);
                        }
                    });
        } else if (type == 4) {
            String extend = getFixUrl(sourceBean.getExt());
            GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_folder")
                    .params("ac", "detail")
                    .params("filter", "true")
                    .params("t", id)
                    .params("pg", 1)
                    .params("ext", Base64.encodeToString("{}".getBytes(), Base64.DEFAULT | Base64.NO_WRAP));
            if (!TextUtils.isEmpty(extend)) request.params("extend", extend);
            request.execute(new AbsCallback<String>() {
                @Override
                public String convertResponse(okhttp3.Response response) throws Throwable {
                    if (response.body() != null) return response.body().string();
                    throw new IllegalStateException("网络请求错误");
                }

                @Override
                public void onSuccess(Response<String> response) {
                    json(listResult, response.body(), sourceBean.getKey());
                }

                @Override
                public void onError(Response<String> response) {
                    super.onError(response);
                    listResult.postValue(null);
                }
            });
        } else {
            listResult.postValue(null);
        }
    }

    interface HomeRecCallback {
        void done(List<Movie.Video> videos);
    }
//    homeVideoContent
    void getHomeRecList(SourceBean sourceBean, ArrayList<String> ids, HomeRecCallback callback) {
        int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            String json = sp.homeVideoContent();
                            return json;
                        }
                    });
                    String sortJson = null;
                    try {
                        sortJson = future.get(20, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    } finally {
                        if (sortJson != null) {
                            AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        } else {
                            callback.done(null);
                        }
                        try {
                            executor.shutdown();
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                }
            };
            spThreadPool.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag("detail")
                    .params("ac", sourceBean.getType() == 0 ? "videolist" : "detail")
                    .params("limit", DETAIL_LIMIT)
                    .params("ids", TextUtils.join(",", ids))
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AbsXml absXml;
                            if (sourceBean.getType() == 0) {
                                String xml = response.body();
                                absXml = xml(null, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                absXml = json(null, json, sourceBean.getKey());
                            }
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            callback.done(null);
                        }
                    });
        } else {
            callback.done(null);
        }
    }
    // detailContent
    public void getDetail(String sourceKey, String urlid) {
        getDetail(sourceKey, urlid, false);
    }

    /**
     * r134：取详情实现（多出口共用）。
     *
     * <p>抽出来的目的：攒池阶段要「补拉候选源详情」，但补拉结果绝不能写进
     * {@link #detailResult} —— 详情页/播放页把它当「当前播放源详情」，误喂会带偏
     * settled / fallbackResult 判定。故这里把结果出口参数化。</p>
     *
     * @param out 结果出口：正常详情传 {@link #detailResult}，补拉传 {@link #prefetchDetailResult}
     */
    private void doGetDetail(String sourceKey, String urlid, boolean fallback, MutableLiveData<AbsXml> out) {
        if (urlid.startsWith("push://") && ApiConfig.get().getSource(PUSH_AGENT) != null) {
            String pushUrl = urlid.substring(7);
            if (pushUrl.startsWith("b64:")) {
                try {
                    pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            } else {
                pushUrl = URLDecoder.decode(pushUrl);
            }
            sourceKey = isCastPushUrl(pushUrl) ? PUSH_FALLBACK : PUSH_AGENT;
            urlid = pushUrl;
        } else if (PUSH_AGENT.equals(sourceKey) && isCastPushUrl(urlid)) {
            sourceKey = PUSH_FALLBACK;
        }
        String id = urlid;
    
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            // r134：补拉时站点可能已被删/改名，宁可回空也不抛 NPE 打断攒池。
            out.postValue(null);
            return;
        }
        if (isPushFallback(sourceKey, sourceBean)) {
            out.postValue(createPushDetail(urlid, sourceKey));
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            List<String> ids = new ArrayList<>();
                            ids.add(id);
                            try {
                                return sp.detailContent(ids);
                            } catch (Exception e) {
                                LOG.i("echo--getDetail--error: " + e.getMessage());
                                return "";
                            }
                        }
                    });

                    String json = null;
                    try {
                        json = future.get(fallback ? 6 : 30, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        LOG.i("echo--getDetail--timeout");
                        future.cancel(true);
                    } catch (Exception e) {
                        LOG.i("echo--getDetail--error: " + e.getMessage());
                    } finally {
                        json(out, json, sourceBean.getKey());
                        executor.shutdown();
                    }
                }
            });
        } else if (type == 0 || type == 1|| type == 4) {
            String extend=sourceBean.getExt();
            extend=fallback ? getFixUrl(extend, 6) : getFixUrl(extend);

            GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag("detail")
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("limit", DETAIL_LIMIT)
                    .params("ids", id);
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(out, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                logResponseBody(json);
                                json(out, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            json(out, "", sourceBean.getKey());
                        }
                    });
        } else {
            AbsXml data = new AbsXml();
            data.sourceKey = sourceKey;
            out.postValue(data);
        }
    }

    public void getDetail(String sourceKey, String urlid, boolean fallback) {
        doGetDetail(sourceKey, urlid, fallback, detailResult);
    }

    /**
     * r134：攒池阶段「补拉候选源详情」。
     *
     * <p>与 {@link #getDetail(String, String, boolean)} 共用同一条取详情通道（类型 0/1/3/4 全支持），
     * 但结果只投到 {@link #prefetchDetailResult}，不会顶掉当前播放源的详情。</p>
     *
     * <p>固定按 fallback 口径取数（超时 6s、extend 走 6s 修复），避免补拉把攒池阶段拖住。</p>
     */
    public void prefetchDetail(String sourceKey, String urlid) {
        doGetDetail(sourceKey, urlid, true, prefetchDetailResult);
    }

    public void action(String sourceKey, String action) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null || action == null) {
            actionResult.postValue(null);
            return;
        }
        if (sourceBean.getType() == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Spider sp = ApiConfig.get().getCSP(sourceBean);
                        String json = sp.action(action);
                        actionResult.postValue(TextUtils.isEmpty(json) ? null : new JSONObject(json));
                    } catch (Throwable th) {
                        th.printStackTrace();
                        actionResult.postValue(null);
                    }
                }
            });
        } else {
            actionResult.postValue(null);
        }
    }

    // searchContent
    public void getSearch(String sourceKey, String wd) {
        getSearch(sourceKey, wd, "");
    }

    public void getSearch(String sourceKey, String wd, String searchToken) {
        getSearch(sourceKey, wd, searchToken, searchResult, "search");
    }

    /**
     * 攒池搜索：前台切源与后台攒池共用这一条通道。
     *
     * <p>★ v99-r58：原先后台（静默预热）走另一个 requestTag
     * {@code detail_fallback_preheat}，是因为主搜索收尾的 {@code cancelTag} 会连带掐掉后台请求。
     * 两条腿合并成一条常驻队列后不再需要区分 —— 只有
     * 「彻底复位（退出 / 换片）」才会 {@code cancelTag}，届时前后台本就该一起停。</p>
     */
    public void getDetailFallbackSearch(String sourceKey, String wd, String searchToken) {
        getSearch(sourceKey, wd, searchToken, detailFallbackSearchResult, "detail_fallback_search");
    }

    private void getSearch(String sourceKey, String wd, String searchToken, MutableLiveData<AbsXml> result, String requestTag) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            postEmptySearchResult(result, sourceKey, searchToken);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                String search = sp.searchContent(wd, false);
                if(!TextUtils.isEmpty(search)){
                    json(result, search, sourceBean.getKey(), searchToken);
                } else {
                    json(result, "", sourceBean.getKey(), searchToken);
                }
            } catch (Throwable th) {
                th.printStackTrace();
                json(result, "", sourceBean.getKey(), searchToken);
            }
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag(requestTag)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(result, xml, sourceBean.getKey(), searchToken);
                            } else {
                                String json = response.body();
                                json(result, json, sourceBean.getKey(), searchToken);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            postEmptySearchResult(result, sourceBean.getKey(), searchToken);
                        }
                    });
        }else if (type == 4) {
            final String searchWd = wd;
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
            String extend=sourceBean.getExt();
            extend=getFixUrlDirect(extend);
            String queryWd = searchWd;
            try {
                queryWd=URLEncoder.encode(queryWd, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag(requestTag)
                    .params("wd", queryWd)
                    .params("ac" ,"detail")
                    .params("limit", DETAIL_LIMIT)
                    .params("quick" ,"false");
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            LOG.i("echo-t4 search-网络请求错误");
                            throw new IllegalStateException("网络请求错误");
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                            String json = response.body();
                            json(result, json, sourceBean.getKey(), searchToken);
                    }

                    @Override
                    public void onError(Response<String> response) {
                        LOG.i("echo-t4 search-onError");
                        super.onError(response);
                        postEmptySearchResult(result, sourceBean.getKey(), searchToken);
                    }
                });
                }
            });
        } else {
            postEmptySearchResult(result, sourceBean.getKey(), searchToken);
        }
    }
    // searchContent
    public void getQuickSearch(String sourceKey, String wd) {
        getQuickSearch(sourceKey, wd, "");
    }

    public void getQuickSearch(String sourceKey, String wd, String searchToken) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            postEmptySearchResult(quickSearchResult, sourceKey, searchToken);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                json(quickSearchResult, sp.searchContent(wd, true), sourceBean.getKey(), searchToken);
            } catch (Throwable th) {
                th.printStackTrace();
                postEmptySearchResult(quickSearchResult, sourceBean.getKey(), searchToken);
            }
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag("quick_search")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(quickSearchResult, xml, sourceBean.getKey(), searchToken);
                            } else {
                                String json = response.body();
                                json(quickSearchResult, json, sourceBean.getKey(), searchToken);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            postEmptySearchResult(quickSearchResult, sourceBean.getKey(), searchToken);
                        }
                    });
        }else if (type == 4) {
            final String searchWd = wd;
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
            String extend=sourceBean.getExt();
            extend=getFixUrlDirect(extend);

            GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag("search")
                    .params("wd", searchWd)
                    .params("ac" ,"detail")
                    .params("limit", DETAIL_LIMIT)
                    .params("quick" ,"true");
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            throw new IllegalStateException("网络请求错误");
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        String json = response.body();
                        logResponseBody(json);
                        json(quickSearchResult, json, sourceBean.getKey(), searchToken);
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        postEmptySearchResult(quickSearchResult, sourceBean.getKey(), searchToken);
                    }
                });
                }
            });
        } else {
            postEmptySearchResult(quickSearchResult, sourceBean.getKey(), searchToken);
        }
    }
    // playerContent
    public void getPlay(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        final int requestSeq = playRequestSeq.incrementAndGet();
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        boolean pushFallback = isPushFallback(sourceKey, sourceBean);
        PushUrl pushUrl = pushFallback ? parsePushUrl(url) : createPushUrl(url);
        String requestUrl = pushUrl.url;
        if (pushFallback) {
            postPlayResult(requestSeq, createPushPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag));
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            if (TextUtils.isEmpty(requestUrl)) return "";
                            try {
                                return sp.playerContent(playFlag, requestUrl, ApiConfig.get().getVipParseFlags());
                            } catch (Exception e) {
                                LOG.i("echo--getPlay--error: " + e.getMessage());
                                return "";
                            }
                        }
                    });

                    try {
                        String json = future.get(sourceBean.getPlayTimeoutSeconds(), TimeUnit.SECONDS);
                        // 处理返回的 JSON
                        if (!TextUtils.isEmpty(json)) {
                            JSONObject result = normalizePlayerResult(new JSONObject(json));
                            result.put("key", url);
                            mergePushHeaders(result, pushUrl);
                            result.put("proKey", progressKey);
                            result.put("subtKey", subtitleKey);
                            if (!result.has("flag"))
                                result.put("flag", playFlag);
                            if (TextUtils.isEmpty(result.optString("url", "")) && shouldDirectPlay(sourceBean, requestUrl)) {
                                postPlayResult(requestSeq, createDirectPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag));
                            } else {
                                postPlayResult(requestSeq, result);
                            }
                        } else {
                            postPlayResult(requestSeq, null);
                        }
                    } catch (TimeoutException e) {
                        // 如果超时了，处理超时逻辑
                        LOG.i("echo--getPlay--timeout");
                        future.cancel(true);
                        postPlayResult(requestSeq, null);
                    } catch (Exception e) {
                        // 捕获其他异常
                        LOG.i("echo--getPlay--error: " + e.getMessage());
                        postPlayResult(requestSeq, null);
                    } finally {
                        executor.shutdown();
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            JSONObject result = new JSONObject();
            try {
                result.put("key", url);
                String playUrl = sourceBean.getPlayerUrl().trim();
                if (DefaultConfig.isVideoFormat(requestUrl) && playUrl.isEmpty()) {
                    result.put("parse", 0);
                    result.put("url", requestUrl);
                } else {
                    result.put("parse", 1);
                    result.put("url", requestUrl);
                }
                mergePushHeaders(result, pushUrl);
                result.put("proKey", progressKey);
                result.put("subtKey", subtitleKey);
                result.put("playUrl", playUrl);
                result.put("flag", playFlag);
                postPlayResult(requestSeq, result);
            } catch (Throwable th) {
                th.printStackTrace();
                postPlayResult(requestSeq, null);
            }
        } else if (type == 4) {
            String extend=sourceBean.getExt();
            extend=getFixUrl(extend);

            GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag("play")
                    .params("play", requestUrl)
                    .params("flag" ,playFlag);
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            throw new IllegalStateException("网络请求错误");
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        String json = response.body();
                        logResponseBody(json);
                        try {
                            JSONObject result = normalizePlayerResult(new JSONObject(json));
                            result.put("key", url);
                            mergePushHeaders(result, pushUrl);
                            result.put("proKey", progressKey);
                            result.put("subtKey", subtitleKey);
                            if (!result.has("flag"))
                                result.put("flag", playFlag);
                            postPlayResult(requestSeq, result);
                        } catch (Throwable th) {
                            th.printStackTrace();
                            postPlayResult(requestSeq, null);
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        postPlayResult(requestSeq, null);
                    }
                });
        }else {
            postPlayResult(requestSeq, null);
        }
    }

    public void cancelPlayRequest() {
        playRequestSeq.incrementAndGet();
    }

    private boolean shouldDirectPlay(SourceBean sourceBean, String requestUrl) {
        return sourceBean != null
                && !TextUtils.isEmpty(requestUrl)
                && (requestUrl.startsWith("http://") || requestUrl.startsWith("https://"));
    }

    private JSONObject createDirectPlayResult(String rawUrl, PushUrl pushUrl, String progressKey, String subtitleKey, String playFlag) {
        try {
            JSONObject result = new JSONObject();
            result.put("key", rawUrl);
            result.put("proKey", progressKey);
            result.put("subtKey", subtitleKey);
            result.put("flag", playFlag);
            result.put("parse", 0);
            result.put("jx", 0);
            result.put("url", pushUrl.url);
            mergePushHeaders(result, pushUrl);
            return result;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        }
    }

    private JSONObject normalizePlayerResult(JSONObject result) {
        if (result == null) return null;
        try {
            String playUrl = result.optString("playUrl", "");
            String url = result.optString("url", "");
            if (TextUtils.isEmpty(url)) return result;
            if (url.startsWith("[") && url.endsWith("]")) {
                JSONArray array = new JSONArray(url);
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.get(i);
                    if (item instanceof String) {
                        String str = (String) item;
                        if (str.startsWith("proxy://")) {
                            str = DefaultConfig.checkReplaceProxy(str);
                            array.put(i, str);
                        } else if (str.startsWith("video://")) {
                            str = str.substring(8);
                            array.put(i, str);
                        }
                    }
                }
                result.put("url", array.toString());
                result.put("parse", 0);
                return result;
            }
            if (url.startsWith("video://")) {
                url = url.substring(8);
                result.put("url", url);
                result.put("parse", 1);
            } else if (url.startsWith("proxy://")) {
                url = DefaultConfig.checkReplaceProxy(url);
                result.put("url", url);
                result.put("parse", 0);
            } else if (playUrl.length() == 0
                    && DefaultConfig.isVideoFormat(url)
                    && !result.has("parse")
                    && !result.has("jx")) {
                result.put("parse", 0);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return result;
    }

    private boolean isPushFallback(String sourceKey, SourceBean sourceBean) {
        return PUSH_FALLBACK.equals(sourceKey) || (sourceBean != null && PUSH_AGENT.equals(sourceBean.getKey()) && sourceBean.getType() == -1);
    }

    private boolean isCastPushUrl(String url) {
        return !TextUtils.isEmpty(url) && url.contains(PUSH_HEADERS_MARKER);
    }

    private AbsXml createPushDetail(String url, String sourceKey) {
        AbsXml data = new AbsXml();
        data.sourceKey = sourceKey;
        Movie movie = new Movie();
        movie.videoList = new ArrayList<>();
        Movie.Video video = new Movie.Video();
        video.id = url;
        video.name = url;
        video.type = "推送";
        video.sourceKey = sourceKey;
        video.urlBean = new Movie.Video.UrlBean();
        video.urlBean.infoList = new ArrayList<>();
        Movie.Video.UrlBean.UrlInfo urlInfo = new Movie.Video.UrlBean.UrlInfo();
        urlInfo.flag = "推送";
        urlInfo.urls = "播放$" + url;
        urlInfo.beanList = new ArrayList<>();
        urlInfo.beanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean("播放", url));
        video.urlBean.infoList.add(urlInfo);
        movie.videoList.add(video);
        data.movie = movie;
        return data;
    }

    private JSONObject createPushPlayResult(String rawUrl, PushUrl pushUrl, String progressKey, String subtitleKey, String playFlag) {
        try {
            JSONObject result = new JSONObject();
            result.put("key", rawUrl);
            result.put("proKey", progressKey);
            result.put("subtKey", subtitleKey);
            result.put("flag", playFlag);
            result.put("parse", 0);
            result.put("url", pushUrl.url);
            mergePushHeaders(result, pushUrl);
            return result;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        }
    }

    private void mergePushHeaders(JSONObject result, PushUrl pushUrl) {
        if (result == null || pushUrl == null || pushUrl.headers.isEmpty()) return;
        try {
            JSONObject header = result.optJSONObject("header");
            if (header == null) header = result.optJSONObject("headers");
            if (header == null) header = new JSONObject();
            for (String key : pushUrl.headers.keySet()) {
                header.put(key, pushUrl.headers.get(key));
            }
            result.put("header", header);
        } catch (Throwable ignored) {
        }
    }

    private PushUrl createPushUrl(String rawUrl) {
        PushUrl pushUrl = new PushUrl();
        pushUrl.url = rawUrl == null ? "" : rawUrl;
        return pushUrl;
    }

    private PushUrl parsePushUrl(String rawUrl) {
        PushUrl pushUrl = createPushUrl(rawUrl);
        parseMarkedHeaders(pushUrl);
        return pushUrl;
    }

    private boolean parseMarkedHeaders(PushUrl pushUrl) {
        String marker = PUSH_HEADERS_MARKER;
        int start = pushUrl.url.indexOf(marker);
        if (start < 0) return false;
        int valueStart = start + marker.length();
        int end = pushUrl.url.indexOf('@', valueStart);
        if (end < 0) return false;
        try {
            String text = URLDecoder.decode(pushUrl.url.substring(valueStart, end), "UTF-8");
            JSONObject json = new JSONObject(text);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = json.optString(key, "");
                if (!TextUtils.isEmpty(key)) pushUrl.headers.put(key, value);
            }
            pushUrl.url = pushUrl.url.substring(0, start) + pushUrl.url.substring(end + 1);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static class PushUrl {
        String url = "";
        HashMap<String, String> headers = new HashMap<>();
    }

    private void postPlayResult(int requestSeq, JSONObject result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (requestSeq != playRequestSeq.get()) {
                    LOG.i("echo--getPlay--ignore stale result");
                    return;
                }
                playResult.setValue(result);
            }
        });
    }

    private static final ConcurrentHashMap<String, String> extendCache = new ConcurrentHashMap<>();

    private String getFixUrl(final String extend) {
        return getFixUrl(extend, 20);
    }

    private String getFixUrl(final String extend, final long timeoutSeconds) {
        if (TextUtils.isEmpty(extend)) return "";
        if(!extend.startsWith("http"))return extend;
        final String key = MD5.string2MD5(extend);
        if (extendCache.containsKey(key)) {
            return extendCache.get(key);
        }
        Future<String> future = spThreadPool.submit(new Callable<String>() {
            @Override
            public String call() {
                String result = extend;
                if (extend.startsWith("http://127.0.0.1")) {
                    String path = extend.replaceAll("^http.+/file/", FileUtils.getRootPath() + "/");
                    path = path.replaceAll("localhost/", "/");
                    result = FileUtils.readFileToString(path, "UTF-8");
                    result = tryMinifyJson(result);
                    extendCache.putIfAbsent(key, result);
                } else if (extend.startsWith("http")) {
                    result = OkHttp.string(extend, null);
                    if (!result.isEmpty()) {
                        result = tryMinifyJson(result);
                        if(result.length()>2500)result = extend;
                        extendCache.putIfAbsent(key, result);
                    }
                }
                return result;
            }
        });

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            te.printStackTrace();
            future.cancel(true);
            return extend;
        } catch (Exception e) {
            e.printStackTrace();
            return extend;
        }
    }

    private String tryMinifyJson(String raw) {
        try {
            raw = raw.trim();
            JsonElement jsonElement = JsonParser.parseString(raw);
            return gson.toJson(jsonElement);
        } catch (Exception e) {
            return raw;
        }
    }

    private MovieSort.SortFilter getSortFilter(JsonObject obj) {
        String key = obj.get("key").getAsString();
        String name = obj.get("name").getAsString();
        JsonArray kv = obj.getAsJsonArray("value");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (JsonElement ele : kv) {
            JsonObject ele_obj = ele.getAsJsonObject();
            String values_key=ele_obj.has("n")?ele_obj.get("n").getAsString():"";
            String values_value=ele_obj.has("v")?ele_obj.get("v").getAsString():"";
            values.put(values_key, values_value);
        }
        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        return filter;
    }

    private AbsSortXml sortJson(MutableLiveData<AbsSortXml> result, String json) {
        try {
            if (TextUtils.isEmpty(json)) {
                return new AbsSortJson().toAbsSortXml();
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbsSortJson sortJson = gson.fromJson(obj, new TypeToken<AbsSortJson>() {
            }.getType());
            AbsSortXml data = sortJson.toAbsSortXml();
            try {
                if (obj.has("filters")) {
                    LinkedHashMap<String, ArrayList<MovieSort.SortFilter>> sortFilters = new LinkedHashMap<>();
                    JsonObject filters = obj.getAsJsonObject("filters");
                    for (String key : filters.keySet()) {
                        ArrayList<MovieSort.SortFilter> sortFilter = new ArrayList<>();
                        JsonElement one = filters.get(key);
                        if (one.isJsonObject()) {
                            sortFilter.add(getSortFilter(one.getAsJsonObject()));
                        } else {
                            for (JsonElement ele : one.getAsJsonArray()) {
                                sortFilter.add(getSortFilter(ele.getAsJsonObject()));
                            }
                        }
                        sortFilters.put(key, sortFilter);
                    }
                    if (data.classes != null && data.classes.sortList != null) {
                        for (MovieSort.SortData sort : data.classes.sortList) {
                            if (sortFilters.containsKey(sort.id) && sortFilters.get(sort.id) != null) {
                                sort.filters = sortFilters.get(sort.id);
                            }
                        }
                    }
                }
            } catch (Throwable th) {

            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private AbsSortXml sortXml(MutableLiveData<AbsSortXml> result, String xml) {
        try {
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsSortXml.class);
            xstream.ignoreUnknownElements();
            AbsSortXml data = (AbsSortXml) xstream.fromXML(xml);
            for (MovieSort.SortData sort : data.classes.sortList) {
                if (sort.filters == null) {
                    sort.filters = new ArrayList<>();
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private void absXml(AbsXml data, String sourceKey) {
        absXml(data, sourceKey, "");
    }

    private void absXml(AbsXml data, String sourceKey, String searchToken) {
        data.sourceKey = sourceKey;
        data.searchToken = searchToken;
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video.urlBean != null && video.urlBean.infoList != null) {
                    for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                        String[] str = null;
                        if (urlInfo.urls.contains("#")) {
                            str = urlInfo.urls.split("#");
                        } else {
                            str = new String[]{urlInfo.urls};
                        }
                        List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                        for (String s : str) {
                            String[] ss = s.split("\\$", 2);
                            if (ss.length > 0) {
                                if (ss.length >= 2) {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                } else {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                }
                            }
                        }
                        urlInfo.beanList = infoBeanList;
                    }
                }
                video.sourceKey = sourceKey;
            }
        }
    }
    
    private AbsXml checkPush(AbsXml data) {
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() > 0) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null && video.urlBean.infoList.size() > 0) {
                for (int i = 0; i < video.urlBean.infoList.size(); i++) {
                    Movie.Video.UrlBean.UrlInfo urlinfo = video.urlBean.infoList.get(i);
                    if (urlinfo != null && urlinfo.beanList != null && !urlinfo.beanList.isEmpty()) {
                        for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlinfo.beanList) {
                            if (infoBean.url.startsWith("push://")) {
                                String pushUrl = infoBean.url.substring(7);
                                if (pushUrl.startsWith("b64:")) {
                                    try {
                                        pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                                    } catch (UnsupportedEncodingException e) {
                                        e.printStackTrace();
                                    }
                                } else {
                                    pushUrl = URLDecoder.decode(pushUrl);
                                }

                                final AbsXml[] resData = {null};

                                final CountDownLatch countDownLatch = new CountDownLatch(1);
                                ExecutorService threadPool = Executors.newSingleThreadExecutor();
                                String finalPushUrl = pushUrl;
                                threadPool.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        SourceBean sb = ApiConfig.get().getSource("push_agent");
                                        if (sb == null) {
                                            countDownLatch.countDown();
                                            return;
                                        }
                                        if (sb.getType() == 4) {
                                            OkGo.<String>get(sb.getApi())
                                                    .tag("detail")
                                                    .params("ac","detail")
                                                    .params("ids", finalPushUrl)
                                                    .execute(new AbsCallback<String>() {
                                                        @Override
                                                        public String convertResponse(okhttp3.Response response) throws Throwable {
                                                            if (response.body() != null) {
                                                                return response.body().string();
                                                            } else {
                                                                return "";
                                                            }
                                                        }

                                                        @Override
                                                        public void onSuccess(Response<String> response) {
                                                            String res = response.body();
                                                            if (!TextUtils.isEmpty(res)) {
                                                                try {
                                                                    AbsJson absJson = gson.fromJson(res, new TypeToken<AbsJson>() {
                                                                    }.getType());
                                                                    resData[0] = absJson.toAbsXml();
                                                                    absXml(resData[0], sb.getKey());
                                                                } catch (Exception e) {
                                                                    e.printStackTrace();
                                                                }
                                                            }
                                                            countDownLatch.countDown();
                                                        }

                                                        @Override
                                                        public void onError(Response<String> response) {
                                                            super.onError(response);
                                                            countDownLatch.countDown();
                                                        }
                                                    });
                                        } else {
                                            try {
                                                Spider sp = ApiConfig.get().getCSP(sb);
                                             //   ApiConfig.get().setPlayJarKey(sb.getJar());
                                                List<String> ids = new ArrayList<>();
                                                ids.add(finalPushUrl);
                                                String res = sp.detailContent(ids);
                                                if (!TextUtils.isEmpty(res)) {
                                                    try {
                                                        AbsJson absJson = gson.fromJson(res, new TypeToken<AbsJson>() {}.getType());
                                                        resData[0] = absJson.toAbsXml();
                                                        absXml(resData[0], sb.getKey());
                                                    } catch (Exception e) {
                                                        e.printStackTrace();
                                                    }
                                                }
                                            } catch (Throwable th) {
                                                th.printStackTrace();
                                            }
                                            countDownLatch.countDown();
                                        }
                                    }
                                });
                                try {
                                    countDownLatch.await(15, TimeUnit.SECONDS);
                                    threadPool.shutdown();
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                                if (resData[0] != null) {
                                    AbsXml res = resData[0];
                                    if (res.movie != null && res.movie.videoList != null && res.movie.videoList.size() > 0) {
                                        Movie.Video resVideo = res.movie.videoList.get(0);
                                        if (resVideo != null && resVideo.urlBean != null && resVideo.urlBean.infoList != null && resVideo.urlBean.infoList.size() > 0) {
                                            if (urlinfo.beanList.size() == 1) {
                                                video.urlBean.infoList.remove(i);
                                            } else {
                                                urlinfo.beanList.remove(infoBean);
                                            }
                                            for (Movie.Video.UrlBean.UrlInfo resUrlinfo : resVideo.urlBean.infoList) {
                                                if (resUrlinfo != null && resUrlinfo.beanList != null && !resUrlinfo.beanList.isEmpty()) {
                                                    video.urlBean.infoList.add(resUrlinfo);
                                                }
                                            }
                                            video.sourceKey = "push_agent";
                                            return data;
                                        }
                                    }
                                }
                                infoBean.name = "解析失败 >>> " + infoBean.name;
                            }
                        }
                    }
                }
            }
        }
        return data;
    }

    public void checkThunder(AbsXml data, int index) {
        boolean thunderParse = false;
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() == 1) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null) {
                boolean hasThunder=false;
                thunderLoop:
                for (int idx=0;idx<video.urlBean.infoList.size();idx++) {
                    Movie.Video.UrlBean.UrlInfo urlInfo = video.urlBean.infoList.get(idx);
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                        if(Thunder.isSupportUrl(infoBean.url)){
                            hasThunder=true;
                            break thunderLoop;
                        }
                    }
                }
                if (hasThunder) {
                    thunderParse = true;
                    Thunder.parse(App.getInstance(), video.urlBean, new Thunder.ThunderCallback() {
                        @Override
                        public void status(int code, String info) {
                            if (code >= 0) {
                                LOG.i(info);
                            } else {
                                video.urlBean.infoList.get(0).beanList.get(0).name = info;
                                detailResult.postValue(data);
                            }
                        }

                        @Override
                        public void list(Map<Integer, String> urlMap) {
                            for (int key : urlMap.keySet()) {
                                String playList=urlMap.get(key);
                                video.urlBean.infoList.get(key).urls = playList;
                                String[] str = playList.split("#");
                                List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                                for (String s : str) {
                                    if (s.contains("$")) {
                                        String[] ss = s.split("\\$", 2);

                                        if (ss.length > 0) {
                                            if (ss.length >= 2) {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                            } else {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                            }
                                        }
                                    }
                                }
                                video.urlBean.infoList.get(key).beanList = infoBeanList;
                            }
                            detailResult.postValue(data);
                        }

                        @Override
                        public void play(String url) {

                        }
                    });
                }
            }
        }
        if (!thunderParse && index==0) {
            detailResult.postValue(data);
        }
    }

    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey) {
        return xml(result, xml, sourceKey, "");
    }

    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey, String searchToken) {
        try {
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            if (xml.contains("<year></year>")) {
                xml = xml.replace("<year></year>", "<year>0</year>");
            }
            if (xml.contains("<state></state>")) {
                xml = xml.replace("<state></state>", "<state>0</state>");
            }
            AbsXml data = (AbsXml) xstream.fromXML(xml);
            absXml(data, sourceKey, searchToken);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                	data = checkPush(data);
                    checkThunder(data,0);
                }else {
                    postSearchResult(result, data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result || quickSearchResult == result || detailFallbackSearchResult == result) {
                postEmptySearchResult(result, sourceKey, searchToken);
            } else if (result != null) {
                if (result == detailResult) {
                    AbsXml data = new AbsXml();
                    data.sourceKey = sourceKey;
                    result.postValue(data);
                } else {
                    result.postValue(null);
                }
            }
            return null;
        }
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey) {
        return json(result, json, sourceKey, "");
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey, String searchToken) {
        try {
            AbsJson absJson = gson.fromJson(json, new TypeToken<AbsJson>() {
            }.getType());
            AbsXml data = absJson.toAbsXml();
            absXml(data, sourceKey, searchToken);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                	data = checkPush(data);
                    checkThunder(data,0);
                }else {
                    postSearchResult(result, data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result || quickSearchResult == result || detailFallbackSearchResult == result) {
                postEmptySearchResult(result, sourceKey, searchToken);
            } else if (result != null) {
                if (result == detailResult) {
                    AbsXml data = new AbsXml();
                    data.sourceKey = sourceKey;
                    result.postValue(data);
                } else {
                    result.postValue(null);
                }
            }
            return null;
        }
    }

    private String getFixUrlDirect(final String extend) {
        if (TextUtils.isEmpty(extend)) return "";
        if (!extend.startsWith("http")) return extend;
        final String key = MD5.string2MD5(extend);
        if (extendCache.containsKey(key)) {
            return extendCache.get(key);
        }
        String result = extend;
        try {
            if (extend.startsWith("http://127.0.0.1")) {
                String path = extend.replaceAll("^http.+/file/", FileUtils.getRootPath() + "/");
                path = path.replaceAll("localhost/", "/");
                result = FileUtils.readFileToString(path, "UTF-8");
                result = tryMinifyJson(result);
                extendCache.putIfAbsent(key, result);
            } else {
                result = OkHttp.string(extend, null);
                if (!TextUtils.isEmpty(result)) {
                    result = tryMinifyJson(result);
                    if (result.length() > 2500) result = extend;
                    extendCache.putIfAbsent(key, result);
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
            return extend;
        }
        return result;
    }

    private void postEmptySearchResult(MutableLiveData<AbsXml> result, String sourceKey) {
        postEmptySearchResult(result, sourceKey, "");
    }

    private void postEmptySearchResult(MutableLiveData<AbsXml> result, String sourceKey, String searchToken) {
        AbsXml data = new AbsXml();
        data.sourceKey = sourceKey;
        data.searchToken = searchToken;
        if (searchResult == result) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
        } else if (quickSearchResult == result) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
        } else if (result != null) {
            postSearchResult(result, data);
        }
    }

    private void postSearchResult(final MutableLiveData<AbsXml> result, final AbsXml data) {
        if (result == detailFallbackSearchResult) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    result.setValue(data);
                }
            });
        } else {
            result.postValue(data);
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
    }
}
