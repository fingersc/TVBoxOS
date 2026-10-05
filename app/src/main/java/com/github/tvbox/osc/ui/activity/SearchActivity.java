package com.github.tvbox.osc.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;

import android.os.Handler;
import android.os.Looper;

import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.lifecycle.ViewModelProvider;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.ui.adapter.PinyinAdapter;
import com.github.tvbox.osc.ui.adapter.SearchAdapter;
import com.github.tvbox.osc.ui.dialog.RemoteDialog;
import com.github.tvbox.osc.ui.dialog.SearchCheckboxDialog;
import com.github.tvbox.osc.ui.tv.widget.SearchKeyboard;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.SourceQualityStore;
import com.github.tvbox.osc.viewmodel.SearchSession;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
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
import java.util.List;
import java.util.concurrent.Executors;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class SearchActivity extends BaseActivity {
    private static final String HOT_SEARCH_URL = "https://movie.douban.com/j/search_subjects?type=tv&tag=%E7%83%AD%E9%97%A8&sort=recommend&page_limit=20&page_start=0";
    private static final int SEARCH_THREAD_COUNT = 6;
    private static final int SEARCH_MAX_THREAD_COUNT = Build.VERSION.SDK_INT >= 35 ? 24 : Build.VERSION.SDK_INT >= 30 ? 18 : 12;
    private static final int SEARCH_NEXT_BATCH_SECONDS = 3;
    private static final int SEARCH_SITE_TIMEOUT_SECONDS = 15;
    private static final String[] DEFAULT_HOT_WORDS = {
            "\u5bb6\u4e1a",
            "\u4e3b\u89d2",
            "\u4f4e\u667a\u5546\u72af\u7f6a",
            "\u82cf\u8d85",
            "\u4e66\u5377\u4e00\u68a6",
            "\u7f8e\u4eba\u4f59",
            "\u85cf\u6d77\u4f20",
            "\u957f\u5b89\u7684\u8354\u679d",
            "\u5e86\u4f59\u5e74",
            "\u51e1\u4eba\u4fee\u4ed9\u4f20"
    };
    private LinearLayout llLayout;
    private LinearLayout llHistoryWord;
    private TvRecyclerView mGridView;
    private TvRecyclerView mGridViewWord;
    private GridLayout historyWordGrid;
    SourceViewModel sourceViewModel;
    private RemoteDialog remoteDialog;
    private EditText etSearch;
    private TextView tvSearch;
    private TextView tvClear;
    private ImageView tvHistoryClear;
    private SearchKeyboard keyboard;
    private SearchAdapter searchAdapter;
    private PinyinAdapter wordAdapter;
    private PinyinAdapter hotWordAdapter;
    private String searchTitle = "";
    private final List<Movie.Video> highMatchVods = new ArrayList<>();
    private boolean showHighMatchResults = false;
    private TextView tvSearchCheckboxBtn;

    private static HashMap<String, String> mCheckSources = null;
    private SearchCheckboxDialog mSearchCheckboxDialog = null;

    private TextView wordsSwitch;
    private boolean aggregateSearchMode;
    private boolean aggregateSearchModeInited = false;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_search;
    }


    private static Boolean hasKeyBoard;
    private static Boolean isSearchBack;
    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
        hasKeyBoard = true;
        isSearchBack = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ★ v42：本页只在 FAST_SEARCH_MODE=false 时才是真正的搜索执行体
        //   （默认 true 时它 1.3s 后跳到 FastSearchActivity，聚合搜索由那边跑）。
        //   两条路径共用一个候选池，故这里也要解除详情页降速。
        session().exitDetailThrottle();
        // 起播保护窗若把派发压住了，这里补一次（幂等）。
        session().resumeDispatch();
        requestSearchFocusWhenReady();
        applySearchWordMode();
        if (aggregateSearchMode) {
            refreshSearchHistoryWords();
            if (hots != null && !hots.isEmpty()) {
                hotWordAdapter.setNewData(hots);
            }
        }
    }

    private void requestSearchFocusWhenReady() {
        final View focusView = hasKeyBoard || isSearchBack ? tvSearch : etSearch;
        if (focusView == null) return;
        focusView.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                focusView.requestFocus();
                focusView.requestFocusFromTouch();
            }
        });
    }

    private void initView() {
        EventBus.getDefault().register(this);
        llLayout = findViewById(R.id.llLayout);
        llHistoryWord = findViewById(R.id.llHistoryWord);
        etSearch = findViewById(R.id.etSearch);
        tvSearch = findViewById(R.id.tvSearch);
        tvSearchCheckboxBtn = findViewById(R.id.tvSearchCheckboxBtn);
        tvClear = findViewById(R.id.tvClear);
        mGridView = findViewById(R.id.mGridView);
        keyboard = findViewById(R.id.keyBoardRoot);
        mGridViewWord = findViewById(R.id.mGridViewWord);
        historyWordGrid = findViewById(R.id.historyWordGrid);
        tvHistoryClear = findViewById(R.id.tvHistoryClear);
        mGridViewWord.setHasFixedSize(true);
        wordAdapter = new PinyinAdapter();
        hotWordAdapter = new PinyinAdapter();
        wordsSwitch = findViewById(R.id.wordSwitch);
        applySearchWordMode();
        wordAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                startSearch(wordAdapter.getItem(position));
            }
        });
        hotWordAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                startSearch(hotWordAdapter.getItem(position));
            }
        });
        mGridView.setHasFixedSize(true);
        // lite
        if (Hawk.get(HawkConfig.SEARCH_VIEW, 0) == 0)
            mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
            // with preview
        else
            mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, 3));
        searchAdapter = new SearchAdapter();
        mGridView.setAdapter(searchAdapter);
        searchAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = searchAdapter.getData().get(position);
                if (video != null) {
                    openSearchVideo(video);
                }
            }
        });
        wordsSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (aggregateSearchMode) {
                    return;
                }
                FastClickCheckUtil.check(v);
                String wd = wordsSwitch.getText().toString().trim();
                if(wd.contains("热词")){
                    ArrayList<String> hisWord= Hawk.get(HawkConfig.SEARCH_HISTORY, new ArrayList<String>());
                    if (hisWord.isEmpty()){
                        Toast.makeText(mContext, "暂无历史搜索", Toast.LENGTH_SHORT).show();
                    }else {
                        wordsSwitch.setText("历史 搜索");
                        wordAdapter.setNewData(hisWord);
                    }
                }
                if(wd.equals("历史 搜索")){
                    wordsSwitch.setText("热词 搜索");
                    if(hots!=null && !hots.isEmpty()){
                        wordAdapter.setNewData(hots);
                    }
                }
            }
        });
        tvHistoryClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                HistoryHelper.clearSearchHistory();
                refreshSearchHistoryWords();
                Toast.makeText(mContext, "已清空搜索历史", Toast.LENGTH_SHORT).show();
            }
        });
        tvSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                hasKeyBoard = true;
                String wd = etSearch.getText().toString().trim();
                if (!TextUtils.isEmpty(wd)) {
                    if(Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)){
                        Bundle bundle = new Bundle();
                        bundle.putString("title", wd);
                        jumpActivity(FastSearchActivity.class, bundle);
                    }else {
                        search(wd);
                    }
                } else {
                    Toast.makeText(mContext, "输入内容不能为空", Toast.LENGTH_SHORT).show();
                }
            }
        });
        tvClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                initData();
                etSearch.setText("");
            }
        });

        //软键盘

        etSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    String wd = etSearch.getText().toString().trim();
                    if (!TextUtils.isEmpty(wd)) {
                        if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                            Bundle bundle = new Bundle();
                            bundle.putString("title", wd);
                            jumpActivity(FastSearchActivity.class, bundle);
                        } else {
                            hiddenImm();
                            search(wd);
                        }
                    } else {
                        Toast.makeText(mContext, "输入内容不能为空", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                return false;
            }
        });

        // 监听遥控器
        etSearch.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    String wd = etSearch.getText().toString().trim();
                    if (!TextUtils.isEmpty(wd)) {
                        if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                            Bundle bundle = new Bundle();
                            bundle.putString("title", wd);
                            jumpActivity(FastSearchActivity.class, bundle);
                        } else {
                            hiddenImm();
                            search(wd);
                        }
                    } else {
                        Toast.makeText(mContext, "输入内容不能为空", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                return false;
            }
        });
        keyboard.setOnSearchKeyListener(new SearchKeyboard.OnSearchKeyListener() {
            @Override
            public void onSearchKey(int pos, String key) {
                if (pos > 1) {
                    String text = etSearch.getText().toString().trim();
                    text += key;
                    etSearch.setText(text);
                    if (text.length() > 0) {
                        loadRec(text);
                    }
                } else if (pos == 1) {
                    String text = etSearch.getText().toString().trim();
                    if (text.length() > 0) {
                        text = text.substring(0, text.length() - 1);
                        etSearch.setText(text);
                    }
                    if (text.length() > 0) {
                        loadRec(text);
                    }
                } else if (pos == 0) {
                    remoteDialog = new RemoteDialog(mContext);
                    remoteDialog.show();
                }
            }
        });
        setLoadSir(llLayout);
        tvSearchCheckboxBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                List<SourceBean> searchAbleSource = ApiConfig.get().getSearchSourceBeanList();
                if (mSearchCheckboxDialog == null) {
                    mSearchCheckboxDialog = new SearchCheckboxDialog(SearchActivity.this, searchAbleSource, mCheckSources);
                }else {
                    if(searchAbleSource.size()!=mSearchCheckboxDialog.mSourceList.size()){
                        mSearchCheckboxDialog.setMSourceList(searchAbleSource);
                    }
                }
                mSearchCheckboxDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        dialog.dismiss();
                    }
                });
                mSearchCheckboxDialog.show();
            }
        });
    }

    private void startSearch(String wd) {
        if (TextUtils.isEmpty(wd)) {
            return;
        }
        if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
            Bundle bundle = new Bundle();
            bundle.putString("title", wd);
            jumpActivity(FastSearchActivity.class, bundle);
        } else {
            search(wd);
        }
    }

    private boolean isAggregateSearchMode() {
        return Hawk.get(HawkConfig.FAST_SEARCH_MODE, true);
    }

    private void setAggregateHotTitle() {
        wordsSwitch.setText("热  门");
        wordsSwitch.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.ts_22));
        wordsSwitch.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            wordsSwitch.setLetterSpacing(0.08f);
        }
    }

    private void setNormalWordTitle() {
        wordsSwitch.setText("热词 | 历史");
        wordsSwitch.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.ts_20));
        wordsSwitch.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            wordsSwitch.setLetterSpacing(0f);
        }
    }

    private void applySearchWordMode() {
        boolean aggregateMode = isAggregateSearchMode();
        if (aggregateSearchModeInited && aggregateSearchMode == aggregateMode) {
            return;
        }
        aggregateSearchModeInited = true;
        aggregateSearchMode = aggregateMode;
        if (aggregateSearchMode) {
            llHistoryWord.setVisibility(View.VISIBLE);
            llLayout.setVisibility(View.GONE);
            mGridView.setVisibility(View.GONE);
            setAggregateHotTitle();
            wordsSwitch.setFocusable(false);
            wordsSwitch.setBackground(null);
            mGridViewWord.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
            mGridViewWord.setAdapter(hotWordAdapter);
            refreshSearchHistoryWords();
        } else {
            llHistoryWord.setVisibility(View.GONE);
            llLayout.setVisibility(View.VISIBLE);
            if (mGridView.getVisibility() == View.GONE) {
                mGridView.setVisibility(View.INVISIBLE);
            }
            setNormalWordTitle();
            wordsSwitch.setFocusable(true);
            wordsSwitch.setBackgroundResource(R.drawable.shape_user_focus);
            mGridViewWord.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
            mGridViewWord.setAdapter(wordAdapter);
        }
    }

    private void setHotWordsData(ArrayList<String> data) {
        if (aggregateSearchMode) {
            hotWordAdapter.setNewData(data);
        } else {
            wordAdapter.setNewData(data);
        }
    }

    private void refreshSearchHistoryWords() {
        historyWordGrid.post(new Runnable() {
            @Override
            public void run() {
                if (!aggregateSearchMode) return;
                ArrayList<String> history = Hawk.get(HawkConfig.SEARCH_HISTORY, new ArrayList<String>());
                historyWordGrid.removeAllViews();
                int itemHeight = getResources().getDimensionPixelSize(R.dimen.vs_50);
                int itemMargin = getResources().getDimensionPixelSize(R.dimen.vs_5);
                int paddingH = getResources().getDimensionPixelSize(R.dimen.vs_10);
                int minWidth = getResources().getDimensionPixelSize(R.dimen.vs_80);
                int availableWidth = historyWordGrid.getWidth();
                if (availableWidth <= 0) availableWidth = llHistoryWord.getWidth();
                float textSize = getResources().getDimension(R.dimen.ts_22);
                int textColor = getResources().getColor(R.color.color_FFFFFF);
                LinearLayout row = null;
                int rowWidth = 0;
                for (int i = 0; i < history.size(); i++) {
                    final String word = history.get(i);
                    TextView item = new TextView(SearchActivity.this);
                    item.setText(word);
                    item.setSingleLine(true);
                    item.setGravity(Gravity.CENTER);
                    item.setIncludeFontPadding(false);
                    item.setFocusable(true);
                    item.setTextColor(textColor);
                    item.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
                    item.setMinWidth(minWidth);
                    item.setPadding(paddingH, 0, paddingH, 0);
                    item.setBackgroundResource(R.drawable.shape_user_focus);
                    item.measure(
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                            View.MeasureSpec.makeMeasureSpec(itemHeight, View.MeasureSpec.EXACTLY));
                    int itemWidth = Math.max(minWidth, item.getMeasuredWidth());
                    int rowItemWidth = itemWidth + itemMargin * 2;
                    if (row == null || (rowWidth > 0 && rowWidth + rowItemWidth > availableWidth)) {
                        row = new LinearLayout(SearchActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        GridLayout.LayoutParams rowParams = new GridLayout.LayoutParams(
                                GridLayout.spec(GridLayout.UNDEFINED),
                                GridLayout.spec(GridLayout.UNDEFINED));
                        rowParams.width = GridLayout.LayoutParams.MATCH_PARENT;
                        rowParams.height = GridLayout.LayoutParams.WRAP_CONTENT;
                        historyWordGrid.addView(row, rowParams);
                        rowWidth = 0;
                    }
                    item.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            startSearch(word);
                        }
                    });
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(itemWidth, itemHeight);
                    params.setMargins(itemMargin, itemMargin, itemMargin, itemMargin);
                    row.addView(item, params);
                    rowWidth += rowItemWidth;
                }
            }
        });
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
                mGridView.setVisibility(View.VISIBLE);
                searchAdapter.setNewData(data.movie.videoList);
            }
        });
    }

    /**
     * 打开搜索结果。
     *
     * <p><b>★ v41 修正：这里不再暂停搜索</b></p>
     *
     * <p>历史演变：</p>
     * <ol>
     *   <li>最初第一行 {@code pauseSearchTasks()} —— 会 {@code shutdownNow()} 线程池并清空
     *       token，返回时用新 token 重跑，候选池永远残缺。</li>
     *   <li>v39 改为 {@code pauseDispatch()}：保留 token 与在途请求，但<b>停掉新任务派发</b>。
     *       实测仍不对：用户「搜索刚开始（只出几条）就点进详情页」，此后搜索一直暂停，
     *       候选池再也不增长 —— 表现为「切源数量不随时间增加，只能在几个站里打转」
     *       （日志证据：poolSize=5 恒定、newCycle 恒为 false）。</li>
     *   <li><b>现在：进详情页不暂停，搜索继续跑</b>，池子边看边涨。
     *       真正需要「给起播让路」的时刻是——用户按下播放，此时由
     *       {@link SearchSession#beginStartupGuard}(在 {@code DetailActivity.jumpToPlay}
     *       里触发) 短暂暂停新派发，首帧到达或 5s 超时后自动恢复。
     *       即：<b>让路是「起播瞬间」的事，不是「待在详情页」的事</b>。</li>
     * </ol>
     */
    private void openSearchVideo(Movie.Video video) {
        hasKeyBoard = false;
        if (TextUtils.equals("folder", video.tag)) {
            folderHistory.add(new ArrayList<>(searchAdapter.getData()));
            folderLoading = true;
            showLoading();
            sourceViewModel.getList(video.sourceKey, video.id);
            return;
        }
        isSearchBack = true;
        // ★ v42：与 FastSearchActivity 一致 —— 进详情页改为「降速」而非暂停。
        session().enterDetailThrottle();
        Bundle bundle = new Bundle();
        bundle.putString("id", video.id);
        bundle.putString("sourceKey", video.sourceKey);
        bundle.putString("title", video.name);
        bundle.putString("picture", video.pic);
        // 不再打包「点击时刻快照」：候选池已由 session 单一持有，详情页直接读，
        // 且随时能读到后续增量。这里只传一个标题作为读取 key。
        bundle.putString(DetailActivity.EXTRA_DETAIL_FALLBACK_TITLE,
                video.name == null ? "" : video.name.trim());
        jumpActivity(DetailActivity.class, bundle);
    }

    @Override
    public void onBackPressed() {
        if (!folderHistory.isEmpty()) {
            folderLoading = false;
            List<Movie.Video> previous = folderHistory.remove(folderHistory.size() - 1);
            showSuccess();
            mGridView.setVisibility(View.VISIBLE);
            searchAdapter.setNewData(previous);
            return;
        }
        // 用户真正离开搜索页 → 显式终止本轮搜索（方向1：取消由显式动作触发，
        // 而非 Activity 销毁被动触发）。
        session().cancelRound();
        super.onBackPressed();
    }

    /**
     * 拼音联想
     */
    private void loadRec(String key) {
        OkGo.get("https://tv.aiseet.atianqi.com/i-tvbin/qtv_video/search/get_search_smart_box")
                .params("format", "json")
                .params("page_num", 0)
                .params("page_size", 20)
                .params("key", key)
                .execute(new AbsCallback() {
                    @Override
                    public void onSuccess(Response response) {
                        try {
                            ArrayList hots = new ArrayList<>();
                            String result = (String) response.body();
                            Gson gson = new Gson();
                            JsonElement json = gson.fromJson(result, JsonElement.class);
                            JsonArray groupDataArr = json.getAsJsonObject()
                                    .get("data").getAsJsonObject()
                                    .get("search_data").getAsJsonObject()
                                    .get("vecGroupData").getAsJsonArray()
                                    .get(0).getAsJsonObject()
                                    .get("group_data").getAsJsonArray();
                            for (JsonElement groupDataElement : groupDataArr) {
                                JsonObject groupData = groupDataElement.getAsJsonObject();
                                String keywordTxt = groupData.getAsJsonObject("dtReportInfo")
                                        .getAsJsonObject("reportData")
                                        .get("keyword_txt").getAsString();
                                hots.add(keywordTxt.trim());
                            }
                            wordsSwitch.setText("猜你 想搜");
                            setHotWordsData(hots);
                            mGridViewWord.smoothScrollToPosition(0);
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }
                });
    }

    private static ArrayList<String> hots;
    private static boolean hotWordsRequested;

    private void useDefaultHotWords() {
        ArrayList<String> data = new ArrayList<>();
        for (String word : DEFAULT_HOT_WORDS) {
            data.add(word);
        }
        cacheHotWords(data);
    }

    private void cacheHotWords(ArrayList<String> data) {
        hots = data;
        setHotWordsData(hots);
    }

    private String cleanHotWord(String title) {
        if (TextUtils.isEmpty(title)) return "";
        return title.trim().replaceAll("<|>|《|》|-", "").split(" ")[0];
    }

    private void addHotWord(ArrayList<String> data, String title) {
        String word = cleanHotWord(title);
        if (!TextUtils.isEmpty(word) && !data.contains(word)) {
            data.add(word);
        }
    }

    private void initData() {
        initCheckedSourcesForSearch();
        applySearchWordMode();
        Intent intent = getIntent();
        String title = null;
        // ★ 只有"带 title 的重建"才允许用上次的关键词补跑。
        //   首页点搜索图标进来时 Intent 里没有 title —— 那是一个全新的搜索入口，
        //   应当停在默认页。若此时拿 LAST_SEARCH_KEYWORD 兜底，用户会看到
        //   "一进搜索页就自动搜了上次那个词"（实测：首页进搜索直接搜了"歌手2026"，
        //   得再返回一次才回到默认搜索页）。
        boolean rebuiltWithTitle = false;
        if (intent != null && intent.hasExtra("title")) {
            title = intent.getStringExtra("title");
            rebuiltWithTitle = true;
        }
        // 进程被回收后 Activity 会被系统重建，Intent 的 title 还在，
        // 但已暂停的搜索不会自动重跑 —— 结果区只剩一个空白页。
        // 仅当"本来就有 title"且本次尚未真正搜过（searchTitle 为空）时补跑。
        if (rebuiltWithTitle && TextUtils.isEmpty(searchTitle)) {
            String last = Hawk.get(HawkConfig.LAST_SEARCH_KEYWORD, "");
            if (!TextUtils.isEmpty(last) && !TextUtils.equals(last, title)) {
                title = last;
            }
        }
        if (!TextUtils.isEmpty(title)) {
            // ★ Activity 重建（如从详情页返回、配置变更）时，若 session 里
            //   已有针对同一关键词的在跑/已完成的搜索，就不要再起一轮 ——
            //   否则「返回搜索页」会重跑一遍，正是要修掉的老毛病。
            boolean reuseRunning = session().canReuseRound(title);
            if (reuseRunning) {
                this.searchTitle = title;
                showLoading();
                session().resumeDispatch();
            } else if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                showLoading();
                Bundle bundle = new Bundle();
                bundle.putString("title", title);
                jumpActivity(FastSearchActivity.class, bundle);
            } else {
                showLoading();
                search(title);
            }
        }
        if (aggregateSearchMode) {
            setAggregateHotTitle();
            refreshSearchHistoryWords();
        } else {
            setNormalWordTitle();
        }
        if(hots!=null && !hots.isEmpty()){
            setHotWordsData(hots);
            return;
        }
        if (hotWordsRequested) {
            return;
        }
        hotWordsRequested = true;
        // 加载热词
        OkGo.<String>get(HOT_SEARCH_URL)
//        OkGo.<String>get("https://api.web.360kan.com/v1/rank")
//                .params("cat", "1")
                .headers("User-Agent", "Mozilla/5.0")
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            ArrayList<String> data = new ArrayList<String>();
                            JsonArray itemList = JsonParser.parseString(response.body()).getAsJsonObject().get("subjects").getAsJsonArray();
//                            JsonArray itemList = JsonParser.parseString(response.body()).getAsJsonObject().get("data").getAsJsonArray();
                            for (JsonElement ele : itemList) {
                                JsonObject obj = (JsonObject) ele;
                                if (obj.has("title")) {
                                    addHotWord(data, obj.get("title").getAsString());
                                }
                            }
                            if (data.isEmpty()) {
                                useDefaultHotWords();
                                return;
                            }
                            cacheHotWords(data);
                        } catch (Throwable th) {
                            th.printStackTrace();
                            useDefaultHotWords();
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        useDefaultHotWords();
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }
                });

    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_SEARCH) {
            String title = (String) event.obj;
            showLoading();
            if(Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)){
                Bundle bundle = new Bundle();
                bundle.putString("title", title);
                jumpActivity(FastSearchActivity.class, bundle);
            }else{
                search(title);
            }
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
        }
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    public static void setCheckedSourcesForSearch(HashMap<String,String> checkedSources) {
        mCheckSources = checkedSources;
    }

    private void search(String title) {
        cancel();
        if (remoteDialog != null) {
            remoteDialog.dismiss();
            remoteDialog = null;
        }
        showLoading();
        etSearch.setText(title);

        //写入历史记录
        HistoryHelper.setSearchHistory(title);
        // 记住最近一次搜索词：进程被回收、Activity 重建时用于自动恢复搜索，
        // 否则用户从详情页返回会落到一个空白的搜索结果页。
        try {
            Hawk.put(HawkConfig.LAST_SEARCH_KEYWORD, title);
        } catch (Throwable ignored) {
        }


        this.searchTitle = title;
        mGridView.setVisibility(View.INVISIBLE);
        searchAdapter.setNewData(new ArrayList<>());
        searchResult();
    }

    // ===== 搜索状态已迁至 SearchSession（方向1：执行体与 Activity 解耦）=====
    // 这里不再持有 Executor / token / pending 集合，全部委托给
    // sourceViewModel.getSearchSession()，使搜索跨 Activity 重建存活。
    private SearchSession session() {
        return sourceViewModel.getSearchSession();
    }

    // 聚合搜索：把短时间内的多次源结果合并为一次列表提交，避免 RecyclerView 高频重排
    // 导致封面请求被反复取消、同一张失败图被反复重试
    private final List<Movie.Video> pendingResultBuffer = new ArrayList<>();
    private boolean flushScheduled = false;
    private static final long RESULT_FLUSH_DELAY_MS = 200;
    // 用主线程 Handler 管理延时提交，便于在重新搜索时统一取消（View.postDelayed 无 removeCallbacksAndMessages API）
    private final Handler mainHandler = new Handler(Looper.getMainLooper());


    /** 本轮搜索结果里「片名高位匹配」的集合（仅本 Activity 用于展示，候选池已由 session 持有）。 */
    private final List<Movie.Video> detailFallbackSearchResults = new ArrayList<>();
    private final List<List<Movie.Video>> folderHistory = new ArrayList<>();
    private boolean folderLoading;

    /**
     * 发起一轮新的聚合搜索。
     *
     * <p>筛选与排序仍在 Activity 侧完成（依赖 UI 的 {@code mCheckSources} 勾选状态），
     * 但<b>状态与线程池全部交给 {@link SearchSession}</b>。搜索自此不再随
     * Activity 的销毁而丢失，也不再因进详情页被打断。</p>
     */
    private void searchResult() {
        pendingResultBuffer.clear();
        flushScheduled = false;
        mainHandler.removeCallbacksAndMessages(null);
        searchAdapter.setNewData(new ArrayList<>());
        highMatchVods.clear();
        detailFallbackSearchResults.clear();
        folderHistory.clear();
        showHighMatchResults = false;

        List<SourceBean> searchRequestList = new ArrayList<>();
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        searchRequestList.remove(home);
        searchRequestList.add(0, home);

        List<SourceBean> filtered = new ArrayList<>();
        for (SourceBean bean : searchRequestList) {
            if (bean == null || !bean.isSearchable()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            filtered.add(bean);
        }
        // 按源质量重排下发顺序：好源先搜、先出结果。
        // 冷启动（无统计数据）时得分相同，稳定排序保持原始顺序，与改动前一致。
        sortSourceBeansByQuality(filtered);
        if (filtered.isEmpty()) {
            Toast.makeText(mContext, "没有指定搜索源", Toast.LENGTH_SHORT).show();
            showEmpty();
            return;
        }
        session().startNewRound(searchTitle, filtered);
    }

    private boolean matchSearchResult(String name, String searchTitle) {
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(searchTitle)) return false;
        return TextUtils.equals(name.trim(), searchTitle.trim());
    }

    private boolean isHighMatchSearchResult(Movie.Video video) {
        return video != null && !TextUtils.isEmpty(video.name) && !TextUtils.isEmpty(searchTitle)
                && video.name.replaceAll("\\s+", "").startsWith(searchTitle.replaceAll("\\s+", ""));
    }

    private boolean shouldShowHighMatchResults() {
        if (showHighMatchResults || searchAdapter.getData().size() > 0) return false;
        int total = session().getTotalSearchCount();
        int threshold = Math.min(SEARCH_THREAD_COUNT, total);
        return threshold > 0 && total - session().getAllRunCount() >= threshold;
    }

    private void addSearchResults(List<Movie.Video> data) {
        if (data == null || data.isEmpty()) return;
        showSuccess();
        mGridView.setVisibility(View.VISIBLE);
        pendingResultBuffer.addAll(data);
        if (flushScheduled) return;
        flushScheduled = true;
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                flushScheduled = false;
                if (pendingResultBuffer.isEmpty()) return;
                List<Movie.Video> batch = new ArrayList<>(pendingResultBuffer);
                pendingResultBuffer.clear();
                // 排序在 flush 时机统一做（每 200ms 一批），而不是每来一条就重排，
                // 避免列表高频跳动、封面请求被反复取消。
                batch = sortBySourceQuality(batch);
                if (searchAdapter.getData().isEmpty()) {
                    searchAdapter.setNewData(batch);
                } else {
                    searchAdapter.addData(batch);
                }
            }
        }, RESULT_FLUSH_DELAY_MS);
    }

    /** 按源质量分降序稳定排序（H1：不给聚合站加权；I1：全自动无用户开关）。 */
    private List<Movie.Video> sortBySourceQuality(List<Movie.Video> data) {
        if (data == null || data.size() <= 1) {
            return data;
        }
        try {
            List<Movie.Video> copy = new ArrayList<>(data);
            // ★ 先批量取分，比较器内不再读 Hawk（否则 O(n log n) 次读会卡列表）
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
     * 按源质量重排源列表（决定先搜哪个源，而不是先显示哪条结果）。
     *
     * <p>好源先发 ⇒ 好源的结果先回来 ⇒ 用户更早看到能播的片子。
     * 冷启动（无统计数据）时所有源得分相同，稳定排序保持仓库原始顺序。</p>
     *
     * <p>注：搜索任务对象（{@code SearchTask}）与派发顺序已迁入
     * {@link SearchSession}，排序改为直接作用于 {@link SourceBean}，
     * 排序语义与原先完全一致。</p>
     */
    private void sortSourceBeansByQuality(List<SourceBean> beans) {
        if (beans == null || beans.size() <= 1) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>(beans.size());
            for (SourceBean bean : beans) {
                if (bean != null && !TextUtils.isEmpty(bean.getKey())) {
                    keys.add(bean.getKey());
                }
            }
            final SourceQualityStore.Snapshot snapshot = SourceQualityStore.snapshotForSearch(keys);
            if (snapshot.isEmpty()) {
                return;
            }
            Collections.sort(beans, new Comparator<SourceBean>() {
                @Override
                public int compare(SourceBean a, SourceBean b) {
                    String ka = a == null ? null : a.getKey();
                    String kb = b == null ? null : b.getKey();
                    return Double.compare(snapshot.get(kb), snapshot.get(ka));
                }
            });
        } catch (Throwable th) {
            LOG.e("sortSourceBeansByQuality fail: " + th);
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

    /**
     * 单个源搜索返回。
     *
     * <p>本轮改动：代际校验与「候选池增量写入」下沉到 {@link SearchSession#onSourceResult}。
     * 这里只负责 UI 侧（列表展示 / 高位匹配）。即使本 Activity 已不在前台，
     * session 也会照常把候选并入池子 —— 这正是修掉快照残缺的关键。</p>
     */
    private void searchData(AbsXml absXml) {
        if (!session().onSourceResult(absXml)) {
            return;
        }
        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null
                && absXml.movie.videoList.size() > 0) {
            // Activity 已进入后台（如已跳详情页）时不必刷 UI，省一次列表重排
            if (isFinishing() || isDestroyed()) {
                return;
            }
            List<Movie.Video> exactData = new ArrayList<>();
            List<Movie.Video> highData = new ArrayList<>();
            for (Movie.Video video : absXml.movie.videoList) {
                if (isHighMatchSearchResult(video)) {
                    highMatchVods.add(video);
                    highData.add(video);
                    detailFallbackSearchResults.add(video);
                }
                if (matchSearchResult(video.name, searchTitle)) {
                    exactData.add(video);
                }
            }

            if (showHighMatchResults) {
                addSearchResults(highData);
            } else if (!exactData.isEmpty()) {
                addSearchResults(exactData);
            }
        }

        if (shouldShowHighMatchResults()) {
            showHighMatchResults = true;
            addSearchResults(new ArrayList<>(highMatchVods));
        }

        finishSearchIfDone();
    }

    /**
     * 候选池读取入口：优先从 {@link SearchSession} 的单一候选池取。
     *
     * <p>保留此方法是为了兼容 DetailActivity 的读取契约 —— 详情页拿到标题后
     * 调 {@code getDetailFallbackCandidates(title)}，返回<b>当前最新</b>的候选集合。
     * 池子为空时返回空表，详情页会自行发起全网搜索（原有逻辑不变）。</p>
     */
    public List<Movie.Video> getDetailFallbackCandidates(String title) {
        return session().getCandidates(title);
    }

    /**
     * 把视频裁成「只保留候选池需要的轻量字段」，剥离 {@code urlBean}。
     *
     * <p>旧的 Intent 打包路径已废弃（候选池改为内存单一真相源），但此方法仍被
     * 其它路径复用（如候选池落盘前的瘦身），故保留。</p>
     */
    private Movie.Video trimVideoForIntent(Movie.Video src) {
        Movie.Video dst = new Movie.Video();
        if (src == null) return dst;
        dst.last = src.last;
        dst.id = src.id;
        dst.tid = src.tid;
        dst.name = src.name;
        dst.type = src.type;
        dst.pic = src.pic;
        dst.lang = src.lang;
        dst.area = src.area;
        dst.year = src.year;
        dst.state = src.state;
        dst.note = src.note;
        dst.actor = src.actor;
        dst.director = src.director;
        dst.des = src.des;
        dst.sourceKey = src.sourceKey;
        dst.tag = src.tag;
        dst.action = src.action;
        // urlBean 有意不复制（超大 Playlist 是 Bundle 超限的主要来源）
        return dst;
    }
    
    // ===== 以下派发/调度/代际逻辑已整体迁入 SearchSession =====
    // 原 scheduleSearchAdvance / scheduleSearchTimeout / submitSearchTask /
    // createSearchExecutor / startNextSearchBatch / startNextSearchTask /
    // takeNextSearchTask / resumePausedSearches / pauseSearchTasks /
    // isCurrentSearchToken / markSearchFinished / releaseSearchSlot /
    // isSearchPending / startFastSearchTasks / submitDirectSearchTask /
    // getPendingSearchKeys / SearchTask(内部类) 等，均已在
    // com.github.tvbox.osc.viewmodel.SearchSession 中重写。
    // 关键差异：pauseDispatch() 只停新派发、保留 token 与 pending 集合，
    // 因此「进详情页」不再打断搜索，候选池得以边搜边补。

    /**
     * 一轮搜索收尾（所有源都已返回/超时）。
     *
     * <p>只做 UI 侧收尾；线程池的清理由 session 自己负责 —— 它必须活得比
     * 本 Activity 久。</p>
     */
    private void finishSearchIfDone() {
        if (session().getAllRunCount() > 0) {
            return;
        }
        if (searchAdapter.getData().size() <= 0) {
            showEmpty();
        }
        cancel();
    }

    private void cancel() {
        OkGo.getInstance().cancelTag("search");
    }

    /**
     * 退出搜索页。
     *
     * <p><b>★ 与旧实现的关键差异</b>：不再 {@code shutdownNow()} 搜索线程池。
     * 搜索状态已迁至 {@link SearchSession}（由 ViewModel 持有），
     * Activity 销毁只是「界面没了」，一轮搜索该跑完的照跑完 —— 这样
     * 用户从详情页返回时，候选池是完整的，而不是重头再搜一遍。</p>
     *
     * <p>真正要终止搜索的场景（用户发起新搜索 / 退出应用）由
     * {@link SearchSession#cancelRound()} 与 {@link SearchSession#startNewRound} 处理。</p>
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 仅取消「本页面发起」的 OkGo 请求；不动 session 的线程池
        EventBus.getDefault().unregister(this);
    }

    private void hiddenImm()
    {
        InputMethodManager imm = (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        }
    }
}
