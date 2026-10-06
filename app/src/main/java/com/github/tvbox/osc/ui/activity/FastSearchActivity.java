package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.animation.BounceInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.ui.adapter.FastListAdapter;
import com.github.tvbox.osc.ui.adapter.FastSearchAdapter;
import com.github.tvbox.osc.ui.adapter.SearchWordAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.SourceQualityStore;

import com.orhanobut.hawk.Hawk;
import com.github.tvbox.osc.viewmodel.SearchSession;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lzy.okgo.OkGo;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class FastSearchActivity extends BaseActivity {
    private static final int SEARCH_THREAD_COUNT = 6;
    private static final int SEARCH_MAX_THREAD_COUNT = Build.VERSION.SDK_INT >= 35 ? 24 : Build.VERSION.SDK_INT >= 30 ? 18 : 12;
    private static final int SEARCH_PUMP_SECONDS = 2;
    private static final int SEARCH_NEXT_BATCH_SECONDS = 3;
    private static final int SEARCH_SITE_TIMEOUT_SECONDS = 15;
    private static final long POSTER_FOCUS_ANIM_DURATION = 300L;
    private static final float POSTER_FOCUS_SCALE = 1.05f;
    private static final String SEARCH_ALL_NAME = "\u5168\u90e8";
    private LinearLayout llLayout;
    private TextView mSearchTitle;
    private TvRecyclerView mGridView;
    private TvRecyclerView mGridViewFilter;
    private TvRecyclerView mGridViewWord;
    private TvRecyclerView mGridViewWordFenci;
    SourceViewModel sourceViewModel;

    private SearchWordAdapter searchWordAdapter;
    private FastSearchAdapter searchAdapter;
    private FastSearchAdapter searchAdapterFilter;
    private FastListAdapter spListAdapter;
    private String searchTitle = "";
    private HashMap<String, String> spNames;
    private boolean isFilterMode = false;
    private String searchFilterKey = "";    // 过滤的key
    private HashMap<String, ArrayList<Movie.Video>> resultVods; // 搜索结果
    private final List<Movie.Video> highMatchVods = new ArrayList<>();
    private boolean showHighMatchResults = false;
    private final List<String> quickSearchWord = new ArrayList<>();
    private final Set<String> wordListNames = new HashSet<>();
    private int wordListVersion = 0;
    private String selectedWordName = "";
    private HashMap<String, String> mCheckSources = null;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_fast_search;
    }

    @Override
    protected void init() {
        spNames = new HashMap<String, String>();
        resultVods = new HashMap<String, ArrayList<Movie.Video>>();
        initView();
        initViewModel();
        initData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ★ v42：从详情页返回 → 先解除降速（并发 2 → 6）。
        //
        //   为什么必须放在 searchPaused 判断之前：降速期间搜索<b>从未停止</b>，
        //   searchPaused 应当始终为 false；先解降速可以把上一轮可能残留的
        //   searchPaused=true（老逻辑走进去的）一并绕开。
        sharedSession().exitDetailThrottle();
        if (searchPaused) {
            // 兼容旧路径：仅当确实处于「整轮被掐断」状态时才重建重跑。
            // v42 后进详情页不再走 pauseSearchTasks()，此分支不应再被触发。
            resumePausedSearches();
        }
    }

    private void initView() {
        EventBus.getDefault().register(this);
        llLayout = findViewById(R.id.llLayout);
        mSearchTitle = findViewById(R.id.mSearchTitle);
        mGridView = findViewById(R.id.mGridView);
        mGridViewWord = findViewById(R.id.mGridViewWord);
        mGridViewFilter = findViewById(R.id.mGridViewFilter);

        mGridViewWord.setHasFixedSize(true);
        mGridViewWord.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        spListAdapter = new FastListAdapter();
        mGridViewWord.setAdapter(spListAdapter);

        spListAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                selectWord(spListAdapter.getItem(position));
            }
        });

        mGridViewWord.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                selectWord(spListAdapter.getItem(position));
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                selectWord(spListAdapter.getItem(position));
            }
        });
        mGridViewWord.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View view) {
                return direction == View.FOCUS_UP;
            }
        });

        mGridView.setHasFixedSize(true);
        mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, 4));

        searchAdapter = new FastSearchAdapter();
        mGridView.setAdapter(searchAdapter);
        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                setPosterFocusScale(itemView, false);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                setPosterFocusScale(itemView, true);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });

        searchAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = searchAdapter.getData().get(position);
                if (video != null) {
                    openSearchVideo(video, false);
                }
            }
        });


        mGridViewFilter.setLayoutManager(new V7GridLayoutManager(this.mContext, 4));
        searchAdapterFilter = new FastSearchAdapter();
        mGridViewFilter.setAdapter(searchAdapterFilter);
        mGridViewFilter.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                setPosterFocusScale(itemView, false);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                setPosterFocusScale(itemView, true);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });
        searchAdapterFilter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = searchAdapterFilter.getData().get(position);
                if (video != null) {
                    openSearchVideo(video, true);
                }
            }
        });

        setLoadSir(llLayout);

        // 分词
        searchWordAdapter = new SearchWordAdapter();
        mGridViewWordFenci = findViewById(R.id.mGridViewWordFenci);
        mGridViewWordFenci.setAdapter(searchWordAdapter);
        mGridViewWordFenci.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        searchWordAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                String str = searchWordAdapter.getData().get(position);
                search(str);
            }
        });
        searchWordAdapter.setNewData(new ArrayList<>());
    }

    private void setPosterFocusScale(View itemView, boolean focused) {
        if (itemView == null) return;
        if (focused) {
            itemView.bringToFront();
        }
        float scale = focused ? POSTER_FOCUS_SCALE : 1.0f;
        itemView.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(POSTER_FOCUS_ANIM_DURATION)
                .setInterpolator(new BounceInterpolator())
                .start();
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new androidx.lifecycle.Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml data) {
                if (!folderLoading) return;
                folderLoading = false;
                if (data == null || data.movie == null || data.movie.videoList == null) {
                    showEmpty();
                    return;
                }
                showSuccess();
                if (folderFilterMode) {
                    mGridView.setVisibility(View.GONE);
                    mGridViewFilter.setVisibility(View.VISIBLE);
                    searchAdapterFilter.setNewData(data.movie.videoList);
                } else {
                    mGridViewFilter.setVisibility(View.GONE);
                    mGridView.setVisibility(View.VISIBLE);
                    searchAdapter.setNewData(data.movie.videoList);
                }
            }
        });
    }

    /**
     * 共享候选池会话（v42 新增）。
     *
     * <p><b>为什么 FastSearch 也要接入它</b>：详情页的切源候选池读的是
     * {@link SearchSession#getShared()}，而 {@code FAST_SEARCH_MODE=true}（默认）
     * 时真正的聚合搜索执行体是<b>本类</b>，不是 {@code SearchActivity}。
     * 若不接入，详情页读到的永远是空池，只能退化成点击那一刻的 Intent 快照
     * —— 这正是「切源数量不随时间增加」的根因（logcat 实锤：poolSize 恒为 3/7）。</p>
     *
     * <p>本类只<b>写</b>池、读自己的活池，<b>不</b>调用
     * {@code setViewModel()} 注册触发通道（那是 {@code SearchActivity} 的事），
     * 故用 {@code getShared()} 而非 {@code getInstance()}。</p>
     */
    private SearchSession sharedSession() {
        return SearchSession.getShared();
    }

    private void openSearchVideo(Movie.Video video, boolean filterMode) {
        if (TextUtils.equals("folder", video.tag)) {
            // 文件夹是「展开本站更多结果」，属于同一页内的下钻，需要暂停本页搜索。
            pauseSearchTasks();
            folderHistory.add(new ArrayList<>(filterMode ? searchAdapterFilter.getData() : searchAdapter.getData()));
            folderHistoryFilter.add(filterMode);
            folderHistorySiteKeys.add(getSelectedSearchSiteKey());
            folderFilterMode = filterMode;
            folderLoading = true;
            showLoading();
            sourceViewModel.getList(video.sourceKey, video.id);
            return;
        }
        // ★ v42：进详情页不再 pauseSearchTasks()。
        //
        //   旧实现（v41 前）调 pauseSearchTasks() → shutdownNow() 掉线程池、
        //   把 currentSearchToken 清成 ""；而恢复只能靠 onResume 的
        //   resumePausedSearches()，它会换一个<b>新 token</b> 重建全部 pending，
        //   等于「返回搜索页时从头再搜一遍」—— 这正是用户反复反馈的现象。
        //
        //   新实现改为「降速」：并发 6 → 2。候选池在详情页停留期间持续增长，
        //   同时把 QuickJS 队列留给详情页预览起播（详情页起播与搜索都要跑 JS）。
        sharedSession().enterDetailThrottle();
        Bundle bundle = new Bundle();
        bundle.putString("id", video.id);
        bundle.putString("sourceKey", video.sourceKey);
        bundle.putString("title", video.name);
        bundle.putString("picture", video.pic);
        bundle.putString(DetailActivity.EXTRA_DETAIL_FALLBACK_TITLE,
                video.name == null ? "" : video.name.trim());
        // ★ P2-4：不再往 Intent 里塞候选快照（见下方已删除方法的说明），
        //   详情页会凭 EXTRA_DETAIL_FALLBACK_TITLE 回候选池取最新全集。
        jumpActivity(DetailActivity.class, bundle);
    }

    @Override
    public void onBackPressed() {
        if (!folderHistory.isEmpty()) {
            String currentSiteKey = getSelectedSearchSiteKey();
            String folderSiteKey = folderHistorySiteKeys.get(folderHistorySiteKeys.size() - 1);
            if (!TextUtils.equals(currentSiteKey, folderSiteKey)) {
                folderLoading = false;
                folderHistory.clear();
                folderHistoryFilter.clear();
                folderHistorySiteKeys.clear();
                super.onBackPressed();
                return;
            }
            folderLoading = false;
            List<Movie.Video> previous = folderHistory.remove(folderHistory.size() - 1);
            boolean filterMode = folderHistoryFilter.remove(folderHistoryFilter.size() - 1);
            folderHistorySiteKeys.remove(folderHistorySiteKeys.size() - 1);
            folderFilterMode = filterMode;
            showSuccess();
            if (filterMode) {
                mGridView.setVisibility(View.GONE);
                mGridViewFilter.setVisibility(View.VISIBLE);
                searchAdapterFilter.setNewData(previous);
            } else {
                mGridViewFilter.setVisibility(View.GONE);
                mGridView.setVisibility(View.VISIBLE);
                searchAdapter.setNewData(previous);
            }
            return;
        }
        super.onBackPressed();
    }

    private String getSelectedSearchSiteKey() {
        if (TextUtils.isEmpty(selectedWordName) || TextUtils.equals(selectedWordName, SEARCH_ALL_NAME)) {
            return SEARCH_ALL_NAME;
        }
        String key = spNames.get(selectedWordName);
        return TextUtils.isEmpty(key) ? selectedWordName : key;
    }

    private void filterResult(String spName) {
        if (TextUtils.isEmpty(spName)) return;
        selectedWordName = spName;
        setSelectedWordName(spName);
        if (TextUtils.equals(spName, SEARCH_ALL_NAME)) {
            mGridView.setVisibility(View.VISIBLE);
            mGridViewFilter.setVisibility(View.GONE);
            return;
        }
        mGridView.setVisibility(View.GONE);
        mGridViewFilter.setVisibility(View.VISIBLE);
        String key = spNames.get(spName);
        if (TextUtils.isEmpty(key)) return;

        if (TextUtils.equals(searchFilterKey, key)) return;
        searchFilterKey = key;

        List<Movie.Video> list = resultVods.get(key);
        if (list == null) {
            list = new ArrayList<>();
        }
        searchAdapterFilter.setNewData(list);
    }

    private void selectWord(String spName) {
        if (TextUtils.isEmpty(spName) || TextUtils.equals(selectedWordName, spName)) return;
        filterResult(spName);
    }

    private void updateWordListWhenIdle(final Runnable action) {
        if (action == null) return;
        if (mGridViewWord == null) {
            action.run();
            return;
        }
        if (mGridViewWord.isComputingLayout()) {
            mGridViewWord.post(new Runnable() {
                @Override
                public void run() {
                    updateWordListWhenIdle(action);
                }
            });
            return;
        }
        action.run();
    }

    private void setSelectedWordName(final String spName) {
        updateWordListWhenIdle(new Runnable() {
            @Override
            public void run() {
                spListAdapter.setSelectedName(spName);
                spListAdapter.refreshVisibleSelection(mGridViewWord);
            }
        });
    }

    private void setWordListData(List<String> data) {
        final List<String> wordList = new ArrayList<>(data);
        final int version = ++wordListVersion;
        wordListNames.clear();
        wordListNames.addAll(wordList);
        updateWordListWhenIdle(new Runnable() {
            @Override
            public void run() {
                if (version != wordListVersion) return;
                spListAdapter.setNewData(wordList);
                if (wordList.size() > 0 && TextUtils.equals(wordList.get(0), SEARCH_ALL_NAME)) {
                    mGridViewWord.setSelectedPosition(0);
                    mGridViewWord.setSelection(0);
                    requestWordListFirstFocus();
                }
            }
        });
    }

    private void requestWordListFirstFocus() {
        if (mGridViewWord == null) return;
        mGridViewWord.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || mGridViewWord == null) return;
                mGridViewWord.setSelectedPosition(0);
                mGridViewWord.setSelection(0);
                View firstChild = mGridViewWord.getChildAt(0);
                if (firstChild != null) {
                    firstChild.requestFocus();
                } else {
                    mGridViewWord.requestFocus();
                }
            }
        });
    }

    private void addWordListDataIfAbsent(final String name) {
        if (TextUtils.isEmpty(name) || !wordListNames.add(name)) return;
        final int version = wordListVersion;
        updateWordListWhenIdle(new Runnable() {
            @Override
            public void run() {
                if (version != wordListVersion) return;
                List<String> names = spListAdapter.getData();
                for (int i = 0; i < names.size(); ++i) {
                    if (TextUtils.equals(name, names.get(i))) {
                        return;
                    }
                }
                spListAdapter.addData(name);
            }
        });
    }

    private void fenci() {
        if (!quickSearchWord.isEmpty()) return; // 如果经有分词了，不再进行二次分词
        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle));
        List<String> words = new ArrayList<>(new LinkedHashSet<>(quickSearchWord));
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words));
    }

    private void initData() {
        initCheckedSourcesForSearch();
        Intent intent = getIntent();
        String title = null;
        if (intent != null && intent.hasExtra("title")) {
            title = intent.getStringExtra("title");
        }
        // ★ 进程被回收后Activity 会被系统重建，Intent 里的 title 还在，
        //   但已暂停的搜索不会自动重跑 —— 结果区只剩一个空白页。
        //   因此额外记住"最近一次搜索词"，重建时若尚未真正搜过则自动补跑。
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(searchTitle)) {
            String last = Hawk.get(HawkConfig.LAST_SEARCH_KEYWORD, "");
            if (!TextUtils.isEmpty(last)) {
                title = last;
            }
        }
        if (!TextUtils.isEmpty(title)) {
            showLoading();
            search(title);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_SEARCH) {
            String title = (String) event.obj;
            showLoading();
            search(title);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_SEARCH_RESULT) {
            try {
                searchData(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                searchData(null);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD) {
            if (event.obj != null) {
                List<String> data = (List<String>) event.obj;
                searchWordAdapter.setNewData(data);
            }
        }
        updateSearchStatus();
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    private void search(String title) {
        cancel();
        showLoading();
        this.searchTitle = title;
        fenci();
        mGridView.setVisibility(View.INVISIBLE);
        mGridViewFilter.setVisibility(View.GONE);
        searchAdapter.setNewData(new ArrayList<>());
        searchAdapterFilter.setNewData(new ArrayList<>());

        selectedWordName = "";
        filterResult(SEARCH_ALL_NAME);
        resultVods.clear();
        highMatchVods.clear();
        showHighMatchResults = false;
        searchFilterKey = "";
        isFilterMode = false;
        spNames.clear();
        totalSearchCount.set(0);
        timedOutSearchCount.set(0);
        updateSearchStatus();

        //写入历史记录
        HistoryHelper.setSearchHistory(title);
        // 记住最近一次搜索词：进程被回收、Activity 重建时用于自动恢复搜索，
        // 否则用户从详情页返回会落到一个空白的搜索结果页。
        try {
            Hawk.put(HawkConfig.LAST_SEARCH_KEYWORD, title);
        } catch (Throwable ignored) {
        }

        searchResult();
    }

    private ExecutorService searchExecutorService = null;
    private ScheduledExecutorService searchTimeoutExecutor = null;
    private final AtomicInteger allRunCount = new AtomicInteger(0);
    private final Set<String> pendingSearchKeys = Collections.synchronizedSet(new HashSet<String>());
    private final List<SearchTask> waitingSearchTasks = Collections.synchronizedList(new ArrayList<SearchTask>());
    private final Set<String> startedSearchKeys = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> releasedSearchKeys = Collections.synchronizedSet(new HashSet<String>());
    private int startedSearchCountOffset = 0;
    private final AtomicInteger searchTokenSeq = new AtomicInteger(0);
    private final AtomicInteger totalSearchCount = new AtomicInteger(0);
    private final AtomicInteger timedOutSearchCount = new AtomicInteger(0);
    private String currentSearchToken = "";
    private boolean searchPaused = false;
    private final List<Movie.Video> detailFallbackSearchResults = new ArrayList<>();
    private boolean folderFilterMode;
    private final List<List<Movie.Video>> folderHistory = new ArrayList<>();
    private final List<Boolean> folderHistoryFilter = new ArrayList<>();
    private final List<String> folderHistorySiteKeys = new ArrayList<>();
    private boolean folderLoading;

    private void searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
                JsLoader.stopAll();
            }
            if (searchTimeoutExecutor != null) {
                searchTimeoutExecutor.shutdownNow();
                searchTimeoutExecutor = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            searchAdapter.setNewData(new ArrayList<>());
            searchAdapterFilter.setNewData(new ArrayList<>());
            allRunCount.set(0);
            pendingSearchKeys.clear();
            waitingSearchTasks.clear();
            startedSearchKeys.clear();
            releasedSearchKeys.clear();
            startedSearchCountOffset = 0;
            currentSearchToken = String.valueOf(searchTokenSeq.incrementAndGet());
            searchPaused = false;
            totalSearchCount.set(0);
            timedOutSearchCount.set(0);
            detailFallbackSearchResults.clear();
            folderHistory.clear();
            folderHistoryFilter.clear();
            folderHistorySiteKeys.clear();
            updateSearchStatus();
        }
        List<SourceBean> searchRequestList = new ArrayList<>();
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        searchRequestList.remove(home);
        searchRequestList.add(0, home);


        ArrayList<SearchTask> fastSearchTasks = new ArrayList<>();
        ArrayList<SearchTask> blockingSearchTasks = new ArrayList<>();
        ArrayList<String> hots = new ArrayList<>();
        hots.add(SEARCH_ALL_NAME);

        setWordListData(hots);
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            SearchTask task = new SearchTask(bean.getKey(), searchTitle, currentSearchToken, isBlockingSearchSource(bean));
            if (task.blocking) {
                blockingSearchTasks.add(task);
            } else {
                fastSearchTasks.add(task);
            }
            this.spNames.put(bean.getName(), bean.getKey());
        }
        ArrayList<SearchTask> searchTasks = new ArrayList<>();
        // 按源质量重排「非阻塞源」的下发顺序：好源先搜、先出结果。
        // 阻塞源（type==3）仍整体排在最后，维持原有策略不变。
        sortSearchTasksByQuality(fastSearchTasks);
        searchTasks.addAll(fastSearchTasks);
        searchTasks.addAll(blockingSearchTasks);

        if (searchTasks.size() <= 0) {
            showEmpty();
            setSearchStatusText("\u65e0\u641c\u7d22\u6e90", "\u7ed3\u679c 0");
            return;
        }
        for (SearchTask task : searchTasks) {
            pendingSearchKeys.add(task.sourceKey);
        }
        allRunCount.set(searchTasks.size());
        totalSearchCount.set(searchTasks.size());
        updateSearchStatus();
        searchExecutorService = createSearchExecutor();
        searchTimeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        waitingSearchTasks.addAll(searchTasks);
        startNextSearchBatch(currentSearchToken);
        startSearchPump(currentSearchToken);
        updateSearchStatus();
    }

    // 向过滤栏添加有结果的spname
    private String addWordAdapterIfNeed(String key) {
        try {
            String name = "";
            for (String n : spNames.keySet()) {
                if (TextUtils.equals(spNames.get(n), key)) {
                    name = n;
                }
            }
            if (TextUtils.isEmpty(name)) return key;

            addWordListDataIfAbsent(name);
            return key;
        } catch (Exception e) {
            return key;
        }
    }

    private boolean matchSearchResult(String name, String searchTitle) {
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(searchTitle)) return false;
        searchTitle = searchTitle.trim();
        String[] arr = searchTitle.split("\\s+");
        int matchNum = 0;
        for(String one : arr) {
            if (name.contains(one)) matchNum++;
        }
        return matchNum == arr.length;
    }

    private boolean isExactSearchResult(Movie.Video video) {
        return video != null && !TextUtils.isEmpty(video.name) && !TextUtils.isEmpty(searchTitle)
                && TextUtils.equals(video.name.trim(), searchTitle.trim());
    }

    private boolean isHighMatchSearchResult(Movie.Video video) {
        return video != null && !TextUtils.isEmpty(video.name) && !TextUtils.isEmpty(searchTitle)
                && video.name.replaceAll("\\s+", "").startsWith(searchTitle.replaceAll("\\s+", ""));
    }

    private boolean shouldShowHighMatchResults() {
        if (showHighMatchResults || searchAdapter.getData().size() > 0) return false;
        int total = totalSearchCount.get();
        int threshold = Math.min(SEARCH_THREAD_COUNT, total);
        return threshold > 0 && total - allRunCount.get() >= threshold;
    }

    /**
     * 提交一批搜索结果到列表。
     *
     * <p>排序策略（H1 + I1：不加权聚合站、全自动无开关）：
     * 结果按源质量分降序排列，同一个源内部保持其原始顺序（稳定排序）。
     * 排序在「批次提交」时机统一做，而不是每来一条就重排，
     * 避免列表高频跳动导致封面请求被反复取消。
     */
    private void addMainSearchResults(List<Movie.Video> data) {
        if (data == null || data.isEmpty()) return;
        List<Movie.Video> ordered = sortBySourceQuality(data);
        if (searchAdapter.getData().size() > 0) {
            searchAdapter.addData(ordered);
        } else {
            showSuccess();
            if (!isFilterMode) mGridView.setVisibility(View.VISIBLE);
            searchAdapter.setNewData(ordered);
        }
    }

    /** 按源质量分降序稳定排序（同分保持原顺序，结果可预测）。 */
    private List<Movie.Video> sortBySourceQuality(List<Movie.Video> data) {
        if (data == null || data.size() <= 1) {
            return data;
        }
        try {
            List<Movie.Video> copy = new ArrayList<>(data);
            // ★ 先批量取分，比较器内不再读 Hawk（原因见 SourceQualityStore.Snapshot 注释）
            SourceQualityStore.Snapshot snapshot =
                    SourceQualityStore.snapshotForSearch(collectSourceKeys(copy));
            if (snapshot.isEmpty()) {
                // 冷启动：一条历史都没有，全是中性分，排序不会改变顺序 —— 直接跳过
                return data;
            }
            final SourceQualityStore.Snapshot scores = snapshot;
            Collections.sort(copy, new Comparator<Movie.Video>() {
                @Override
                public int compare(Movie.Video a, Movie.Video b) {
                    String ka = a == null ? null : a.sourceKey;
                    String kb = b == null ? null : b.sourceKey;
                    return Double.compare(scores.get(kb), scores.get(ka));
                }
            });
            return copy;
        } catch (Throwable th) {
            return data;
        }
    }

    /**
     * 按源质量重排「搜索任务的下发顺序」——决定先搜哪个源，而不是先显示哪条结果。
     *
     * <p>与 {@link #sortBySourceQuality(List)} 的区别：
     * <ul>
     *   <li>那个管「结果回来后怎么摆」，本方法管「请求按什么顺序发」。</li>
     *   <li>好源先发 ⇒ 好源的结果先回来 ⇒ 用户更早看到能播的片子。</li>
     * </ul>
     *
     * <p>降级安全：冷启动（无任何统计数据）时所有源得分相同，
     * 稳定排序会保持仓库原始顺序，与改动前行为一致。
     */
    private void sortSearchTasksByQuality(List<SearchTask> tasks) {
        if (tasks == null || tasks.size() <= 1) {
            return;
        }
        try {
            // ★ 先批量取分，比较器内不再读 Hawk：任务数可能上百，比较次数是 O(n log n)。
            List<String> keys = new ArrayList<>(tasks.size());
            for (SearchTask task : tasks) {
                if (task != null && !TextUtils.isEmpty(task.sourceKey)) {
                    keys.add(task.sourceKey);
                }
            }
            final SourceQualityStore.Snapshot snapshot = SourceQualityStore.snapshotForSearch(keys);
            if (snapshot.isEmpty()) {
                return;   // 冷启动：无可参考历史，保持原下发顺序
            }
            Collections.sort(tasks, new Comparator<SearchTask>() {
                @Override
                public int compare(SearchTask a, SearchTask b) {
                    String ka = a == null ? null : a.sourceKey;
                    String kb = b == null ? null : b.sourceKey;
                    return Double.compare(snapshot.get(kb), snapshot.get(ka));
                }
            });
        } catch (Throwable th) {
            LOG.e("sortSearchTasksByQuality fail: " + th);
        }
    }

    /** 从视频列表里收集源 key（去重交给快照内部处理）。 */
    private List<String> collectSourceKeys(List<Movie.Video> data) {
        List<String> keys = new ArrayList<>(data.size());
        for (Movie.Video video : data) {
            if (video != null && !TextUtils.isEmpty(video.sourceKey)) {
                keys.add(video.sourceKey);
            }
        }
        return keys;
    }

    private void searchData(AbsXml absXml) {
        if (!isCurrentSearchResult(absXml)) {
            return;
        }
        String sourceKey = absXml == null ? "" : absXml.sourceKey;
        if (!markSearchFinished(sourceKey, absXml.searchToken)) {
            return;
        }
        releaseSearchSlotAndStartNext(sourceKey, absXml.searchToken);
        String lastSourceKey = "";
        List<Movie.Video> exactData = new ArrayList<>();
        List<Movie.Video> highData = new ArrayList<>();
        // ★ v42：本批命中的视频同时并入共享候选池，供详情页切源使用。
        //   详情页读的是 SearchSession，而本类才是 FAST_SEARCH_MODE 下真正的
        //   搜索执行体 —— 不写进去，详情页看到的就永远是点击那一刻的快照。
        List<Movie.Video> mergedForSession = new ArrayList<>();

        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            for (Movie.Video video : absXml.movie.videoList) {
                if (!matchSearchResult(video.name, searchTitle)) continue;
                detailFallbackSearchResults.add(video);
                mergedForSession.add(video);
                if (!resultVods.containsKey(video.sourceKey)) {
                    resultVods.put(video.sourceKey, new ArrayList<Movie.Video>());
                }
                resultVods.get(video.sourceKey).add(video);
                if (isHighMatchSearchResult(video)) {
                    highMatchVods.add(video);
                    highData.add(video);
                }
                if (isExactSearchResult(video)) {
                    exactData.add(video);
                }
                if (!TextUtils.equals(video.sourceKey, lastSourceKey)) {
                    lastSourceKey = this.addWordAdapterIfNeed(video.sourceKey);
                }
            }
        }
        // 池外写入：mergeVideos 内部自带同步与去重，可安全在任意线程调用。
        if (!mergedForSession.isEmpty()) {
            sharedSession().mergeVideos(mergedForSession);
        }

        if (showHighMatchResults) {
            addMainSearchResults(highData);
        } else if (!exactData.isEmpty()) {
            addMainSearchResults(exactData);
        } else if (shouldShowHighMatchResults()) {
            showHighMatchResults = true;
            addMainSearchResults(new ArrayList<>(highMatchVods));
        }

        finishSearchIfDone();
    }

    // ★ P2-4：putDetailFallbackCandidates / trimVideoForIntent 已删除。
    //
    // 这两个方法把「点击搜索结果那一刻的候选快照」（限 20 条、严格 Equals 匹配）
    // 序列化进 Intent。它已被「只传标题、详情页回候选池取最新全集」的路径取代，
    // 是后者的**真子集**；保留只会白白承担 TransactionTooLargeException 风险。
    // 缓存写入现在统一由 DetailActivity 的标题路径 + 后台回流完成。

    private void scheduleSearchAdvance(final String sourceKey, final String searchToken) {
        if (searchTimeoutExecutor == null) return;
        searchTimeoutExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentSearchToken(searchToken)) return;
                if (isSearchPending(sourceKey, searchToken) && releaseSearchSlot(sourceKey, searchToken)) {
                    startNextSearchTask(searchToken);
                }
            }
        }, SEARCH_NEXT_BATCH_SECONDS, TimeUnit.SECONDS);
    }

    private void startSearchPump(final String searchToken) {
        if (searchTimeoutExecutor == null) return;
        searchTimeoutExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isCurrentSearchToken(searchToken) || allRunCount.get() <= 0) return;
                    if (getWaitingSearchCount() > 0) {
                        startNextSearchBatch(searchToken);
                        updateSearchStatusOnUiThread();
                    }
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }
        }, SEARCH_PUMP_SECONDS, SEARCH_PUMP_SECONDS, TimeUnit.SECONDS);
    }

    private void updateSearchStatusOnUiThread() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateSearchStatus();
            }
        });
    }

    private void scheduleSearchTimeout(final String sourceKey, final String searchToken) {
        if (searchTimeoutExecutor == null) return;
        searchTimeoutExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentSearchToken(searchToken)) return;
                if (markSearchFinished(sourceKey, searchToken)) {
                    timedOutSearchCount.incrementAndGet();
                    releaseSearchSlotAndStartNext(sourceKey, searchToken);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateSearchStatus();
                            finishSearchIfDone();
                        }
                    });
                }
            }
        }, SEARCH_SITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private boolean submitSearchTask(SearchTask task) {
        if (!isSearchPending(task.sourceKey, task.searchToken)) return false;
        if (searchExecutorService == null || searchExecutorService.isShutdown()) return false;
        try {
            searchExecutorService.execute(task);
        } catch (RejectedExecutionException e) {
            return false;
        }
        scheduleSearchAdvance(task.sourceKey, task.searchToken);
        scheduleSearchTimeout(task.sourceKey, task.searchToken);
        updateSearchStatusOnUiThread();
        return true;
    }

    private ExecutorService createSearchExecutor() {
        return new ThreadPoolExecutor(0, SEARCH_MAX_THREAD_COUNT, 30L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
    }

    private void startNextSearchBatch(String searchToken) {
        // ★ v42：起播保护窗内不派发新任务。
        //
        //   详情页按下播放时，spider 会执行 playerContent（JS），与搜索的
        //   searchContent（同为 JS）抢同一条 QuickJS 队列。窗口期（5s 或首帧）
        //   内让路，改由 searchPump 每 3s 重试，窗口一关自然续跑 —— 不需要
        //   任何显式恢复回调。在途请求不受影响，其结果照常回流。
        if (sharedSession().isStartupGuardActive()) {
            return;
        }
        for (int i = 0; i < SEARCH_THREAD_COUNT; i++) {
            if (!startNextSearchTask(searchToken)) {
                return;
            }
        }
    }

    private boolean startNextSearchTask(String searchToken) {
        if (!isCurrentSearchToken(searchToken)) return false;
        SearchTask task = takeNextSearchTask(searchToken);
        if (task == null) {
            return false;
        }
        if (!submitSearchTask(task)) {
            startedSearchKeys.remove(task.sourceKey);
            synchronized (waitingSearchTasks) {
                waitingSearchTasks.add(0, task);
            }
            return false;
        }
        return true;
    }

    private SearchTask takeNextSearchTask(String searchToken) {
        synchronized (waitingSearchTasks) {
            while (!waitingSearchTasks.isEmpty()) {
                SearchTask task = waitingSearchTasks.remove(0);
                if (!isSearchPending(task.sourceKey, searchToken) || !startedSearchKeys.add(task.sourceKey)) {
                    continue;
                }
                return task;
            }
        }
        return null;
    }

    private void resumePausedSearches() {
        if (!searchPaused) {
            return;
        }
        searchPaused = false;
        List<String> sourceKeys = getPendingSearchKeys();
        if (sourceKeys.isEmpty()) {
            finishSearchIfDone();
            return;
        }
        currentSearchToken = String.valueOf(searchTokenSeq.incrementAndGet());
        waitingSearchTasks.clear();
        startedSearchCountOffset = Math.max(0, totalSearchCount.get() - allRunCount.get());
        startedSearchKeys.clear();
        releasedSearchKeys.clear();
        for (String sourceKey : sourceKeys) {
            SourceBean bean = ApiConfig.get().getSource(sourceKey);
            waitingSearchTasks.add(new SearchTask(sourceKey, searchTitle, currentSearchToken, isBlockingSearchSource(bean)));
        }
        if (searchExecutorService == null || searchExecutorService.isShutdown()) {
            searchExecutorService = createSearchExecutor();
        }
        if (searchTimeoutExecutor == null || searchTimeoutExecutor.isShutdown()) {
            searchTimeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        }
        startNextSearchBatch(currentSearchToken);
        startSearchPump(currentSearchToken);
        updateSearchStatus();
    }

    private void pauseSearchTasks() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
                JsLoader.stopAll();
            }
            if (searchTimeoutExecutor != null) {
                searchTimeoutExecutor.shutdownNow();
                searchTimeoutExecutor = null;
            }
            searchPaused = allRunCount.get() > 0;
            if (searchPaused) {
                cancel();
                currentSearchToken = "";
            }
            updateSearchStatus();
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private boolean isCurrentSearchResult(AbsXml absXml) {
        return absXml != null && isCurrentSearchToken(absXml.searchToken);
    }

    private boolean isCurrentSearchToken(String searchToken) {
        return !TextUtils.isEmpty(searchToken) && searchToken.equals(currentSearchToken);
    }

    private boolean markSearchFinished(String sourceKey, String searchToken) {
        if (!isCurrentSearchToken(searchToken)) return false;
        synchronized (pendingSearchKeys) {
            if (TextUtils.isEmpty(sourceKey)) {
                return false;
            }
            if (!pendingSearchKeys.remove(sourceKey)) {
                return false;
            }
            allRunCount.set(pendingSearchKeys.size());
            return true;
        }
    }

    private boolean releaseSearchSlot(String sourceKey, String searchToken) {
        if (!isCurrentSearchToken(searchToken) || TextUtils.isEmpty(sourceKey)) return false;
        return releasedSearchKeys.add(sourceKey);
    }

    private void releaseSearchSlotAndStartNext(String sourceKey, String searchToken) {
        if (releaseSearchSlot(sourceKey, searchToken)) {
            startNextSearchTask(searchToken);
        }
    }

    private boolean isSearchPending(String sourceKey, String searchToken) {
        if (!isCurrentSearchToken(searchToken) || TextUtils.isEmpty(sourceKey)) return false;
        synchronized (pendingSearchKeys) {
            return pendingSearchKeys.contains(sourceKey);
        }
    }

    private boolean isBlockingSearchSource(SourceBean bean) {
        return bean == null || bean.getType() == 3;
    }

    private List<String> getPendingSearchKeys() {
        synchronized (pendingSearchKeys) {
            return new ArrayList<>(pendingSearchKeys);
        }
    }

    private int getWaitingSearchCount() {
        synchronized (waitingSearchTasks) {
            return waitingSearchTasks.size();
        }
    }

    private void finishSearchIfDone() {
        if (allRunCount.get() > 0) return;
        searchPaused = false;
        updateSearchStatus();
        if (searchAdapter.getData().size() == 0 && resultVods.isEmpty()) {
            showEmpty();
        }
        cancel();
        if (searchTimeoutExecutor != null) {
            searchTimeoutExecutor.shutdownNow();
            searchTimeoutExecutor = null;
        }
    }

    private int getStartedSearchCount() {
        synchronized (startedSearchKeys) {
            return startedSearchCountOffset + startedSearchKeys.size();
        }
    }

    private int getResultCount() {
        return searchAdapter == null ? 0 : searchAdapter.getData().size();
    }

    private void updateSearchStatus() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            updateSearchStatusOnUiThread();
            return;
        }
        int total = totalSearchCount.get();
        int pending = allRunCount.get();
        int finished = Math.max(0, total - pending);
        int started = Math.min(total, getStartedSearchCount());
        int results = getResultCount();
        int timeouts = timedOutSearchCount.get();

        String firstLine;
        String secondLine;
        if (total <= 0) {
            firstLine = "\u51c6\u5907\u641c\u7d22";
            secondLine = "\u7ed3\u679c 0";
        } else if (searchPaused && pending > 0) {
            firstLine = "\u5df2\u6682\u505c " + finished + "/" + total;
            secondLine = "\u7ed3\u679c " + results + " \u00b7 \u5f85 " + pending;
        } else if (pending <= 0) {
            firstLine = "\u641c\u7d22\u5b8c\u6210 " + results;
            secondLine = "\u6e90 " + total + "/" + total;
            if (timeouts > 0) {
                secondLine += " \u00b7 \u8d85\u65f6 " + timeouts;
            }
        } else if (started >= total) {
            firstLine = finished > 0 ? "\u7b49\u6162\u6e90 " + pending : "\u7b49\u5f85\u8fd4\u56de " + pending;
            secondLine = "\u7ed3\u679c " + results + " \u00b7 \u5b8c\u6210 " + finished + "/" + total;
        } else {
            firstLine = "\u641c\u6e90 " + started + "/" + total;
            secondLine = "\u7ed3\u679c " + results + " \u00b7 \u5f85 " + pending;
        }
        setSearchStatusText(firstLine, secondLine);
    }

    private void setSearchStatusText(String firstLine, String secondLine) {
        if (mSearchTitle == null) return;
        String text = firstLine + "\n" + secondLine;
        SpannableString span = new SpannableString(text);
        int split = firstLine.length();
        span.setSpan(new StyleSpan(Typeface.BOLD), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new RelativeSizeSpan(1.05f), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new RelativeSizeSpan(0.78f), split + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new ForegroundColorSpan(0xCCFFFFFF), split + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mSearchTitle.setText(span);
    }

    private class SearchTask implements Runnable {
        private final String sourceKey;
        private final String title;
        private final String searchToken;
        private final boolean blocking;

        private SearchTask(String sourceKey, String title, String searchToken, boolean blocking) {
            this.sourceKey = sourceKey;
            this.title = title;
            this.searchToken = searchToken;
            this.blocking = blocking;
        }

        @Override
        public void run() {
            if (!isSearchPending(sourceKey, searchToken)) return;
            try {
                sourceViewModel.getSearch(sourceKey, title, searchToken);
            } catch (Throwable th) {
                th.printStackTrace();
                if (markSearchFinished(sourceKey, searchToken)) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateSearchStatus();
                            finishSearchIfDone();
                        }
                    });
                }
            }
        }
    }

    private void cancel() {
        OkGo.getInstance().cancelTag("search");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancel();
        // ★ v42：不再无条件 shutdownNow()。
        //
        //   旧实现「Activity 一销毁就把搜索连同线程池一起掐掉」。加上
        //   openSearchVideo 里的 pauseSearchTasks()，构成了「进详情页 → 搜索
        //   彻底停摆 → 返回时换新 token 从头重跑」的完整闭环 —— 这正是
        //   「切源数量不随时间增加」的根因。
        //
        //   现在：只要没能真正「结束这一轮」（用户退出搜索页会显式调用
        //   finishRound），就保留线程池，让在途请求把候选池补完。
        //   isFinishing() 为真 = 用户主动离开（返回/跳走），此时才释放资源。
        if (isFinishing()) {
            try {
                if (searchExecutorService != null) {
                    searchExecutorService.shutdownNow();
                    searchExecutorService = null;
                    JsLoader.stopAll();
                }
                if (searchTimeoutExecutor != null) {
                    searchTimeoutExecutor.shutdownNow();
                    searchTimeoutExecutor = null;
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        EventBus.getDefault().unregister(this);
    }
}
