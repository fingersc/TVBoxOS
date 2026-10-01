package com.github.tvbox.osc.ui.activity;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipboardManager;
import android.content.ClipData;

import androidx.fragment.app.FragmentContainerView;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearSmoothScroller;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.adapter.SeriesAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesFlagAdapter;
import com.github.tvbox.osc.ui.dialog.DescDialog;
import com.github.tvbox.osc.ui.dialog.QuickSearchDialog;
import com.github.tvbox.osc.ui.fragment.PlayFragment;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lzy.okgo.OkGo;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.jessyan.autosize.utils.AutoSizeUtils;

import android.graphics.Paint;

import com.github.tvbox.osc.cache.PlayProgressManager;

import java.util.Collections;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */

public class DetailActivity extends BaseActivity {
    private static final String STATE_FULL_WINDOWS = "detail_full_windows";
    private static final String DETAIL_FALLBACK_SEARCH_TAG = "detail_fallback_search";
    public static final String EXTRA_DETAIL_FALLBACK_CANDIDATES = "detailFallbackCandidates";
    private static final int DETAIL_FALLBACK_MAX_SEARCH = 20;
    private static final long DETAIL_FALLBACK_SEARCH_TIMEOUT_MS = 8000L;
    private static final long DETAIL_FALLBACK_DETAIL_TIMEOUT_MS = 6000L;
    private LinearLayout llLayout;
    private FragmentContainerView llPlayerFragmentContainer;
    private View llPlayerFragmentContainerBlock;
    private View llPlayerPlace;
    private PlayFragment playFragment = null;
    private View thumbContainer;
    private ImageView ivThumb;
    private TextView tvName;
    private TextView tvYear;
    private TextView tvSite;
    private TextView tvArea;
    private TextView tvLang;
    private TextView tvType;
    private TextView tvActor;
    private TextView tvDirector;
    private TextView tvPlayUrl;
    private TextView tvDes;
    private TextView tvPlay;
//    private TextView tvSort;
    private TextView tvDesc;
    private TextView tvSeriesSort;
    private TextView tvQuickSearch;
    private TextView tvChangeSource;
    private TextView tvCollect;
    private TvRecyclerView mGridViewFlag;
    private TvRecyclerView mGridViewQuality;
    private TvRecyclerView mGridView;
    private TvRecyclerView mSeriesGroupView;
    private LinearLayout mEmptyPlayList;
    private LinearLayout tvSeriesGroup;
    private SourceViewModel sourceViewModel;
    private Movie.Video mVideo;
    private VodInfo vodInfo;
    private SeriesFlagAdapter seriesFlagAdapter;
    private BaseQuickAdapter<String, BaseViewHolder> qualityAdapter;
    private BaseQuickAdapter<String, BaseViewHolder> seriesGroupAdapter;
    private SeriesAdapter seriesAdapter;
    public String vodId;
    public String sourceKey;
    public String firstsourceKey;
    private boolean fromCollect;
    boolean seriesSelect = false;
    private View seriesFlagFocus = null;
    private boolean isReverse;
    private String preFlag="";
    private VodInfo.VodSeries routeSwitchSeries;
    private boolean firstReverse;
    private V7GridLayoutManager mGridViewLayoutMgr = null;
    private HashMap<String, String> mCheckSources = null;
    private final ArrayList<String> seriesGroupOptions = new ArrayList<>();
    private final ArrayList<String> qualityOptions = new ArrayList<>();
    private View currentSeriesGroupView;
    private int selectedSeriesGroupPosition;
    private int GroupCount;
    private int qualityPosition;
    boolean showPreview = Hawk.get(HawkConfig.SHOW_PREVIEW, true);; // true 开启 false 关闭

    private LinearSmoothScroller smoothScroller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            fullWindows = savedInstanceState.getBoolean(STATE_FULL_WINDOWS, false);
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_detail;
    }

    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        initView();
        initViewModel();
        initData();
    }

    private void initView() {
        llLayout = findViewById(R.id.llLayout);
        llPlayerPlace = findViewById(R.id.previewPlayerPlace);
        llPlayerFragmentContainer = findViewById(R.id.previewPlayer);
        llPlayerFragmentContainerBlock = findViewById(R.id.previewPlayerBlock);
        applyPreviewRoundCorners();
        thumbContainer = findViewById(R.id.thumbContainer);
        ivThumb = findViewById(R.id.ivThumb);
        applyThumbPreviewStyle();
        tvName = findViewById(R.id.tvName);
        tvYear = findViewById(R.id.tvYear);
        tvSite = findViewById(R.id.tvSite);
        tvArea = findViewById(R.id.tvArea);
        tvLang = findViewById(R.id.tvLang);
        tvType = findViewById(R.id.tvType);
        tvActor = findViewById(R.id.tvActor);
        tvDirector = findViewById(R.id.tvDirector);
        tvPlayUrl = findViewById(R.id.tvPlayUrl);
        tvDes = findViewById(R.id.tvDes);
        tvPlay = findViewById(R.id.tvPlay);
//        tvSort = findViewById(R.id.tvSort);
        tvDesc = findViewById(R.id.tvDesc);
        tvSeriesSort = findViewById(R.id.mSeriesSortTv);
        tvCollect = findViewById(R.id.tvCollect);
        tvQuickSearch = findViewById(R.id.tvQuickSearch);
        tvChangeSource = findViewById(R.id.tvChangeSource);
        mEmptyPlayList = findViewById(R.id.mEmptyPlaylist);
        mGridView = findViewById(R.id.mGridView);
        mGridView.setHasFixedSize(false);
        this.mGridViewLayoutMgr = new V7GridLayoutManager(this.mContext, 6);
        mGridView.setLayoutManager(this.mGridViewLayoutMgr);
//        mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));

        smoothScroller = new LinearSmoothScroller(mContext) {
            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return 100f / displayMetrics.densityDpi;
            }
            @Override
            public PointF computeScrollVectorForPosition(int targetPosition) {
                return mGridViewLayoutMgr.computeScrollVectorForPosition(targetPosition);
            }
        };

        seriesAdapter = new SeriesAdapter(this.mGridViewLayoutMgr);
        mGridView.setAdapter(seriesAdapter);
        mGridViewFlag = findViewById(R.id.mGridViewFlag);
        mGridViewFlag.setHasFixedSize(true);
        mGridViewFlag.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        seriesFlagAdapter = new SeriesFlagAdapter();
        mGridViewFlag.setAdapter(seriesFlagAdapter);
        mGridViewQuality = findViewById(R.id.mGridViewQuality);
        mGridViewQuality.setHasFixedSize(true);
        mGridViewQuality.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        qualityAdapter = new BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_series_flag, qualityOptions) {
            @Override
            protected void convert(BaseViewHolder helper, String item) {
                helper.setText(R.id.tvSeriesFlag, item);
                helper.getView(R.id.tvSeriesFlagSelect).setVisibility(helper.getLayoutPosition() == qualityPosition ? View.VISIBLE : View.GONE);
                helper.itemView.setNextFocusUpId(tvSeriesGroup.getVisibility() == View.VISIBLE ? R.id.mSeriesSortTv : R.id.mGridViewFlag);
                helper.itemView.setNextFocusDownId(R.id.mGridView);
            }
        };
        mGridViewQuality.setAdapter(qualityAdapter);
        mGridViewQuality.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (playFragment == null) return;
                if (position == qualityPosition) {
                    if (showPreview && !fullWindows && playFragment.getPlayer().isPlaying()) enterFullPreview();
                    return;
                }
                if (playFragment.selectQuality(position)) {
                    qualityPosition = position;
                    qualityAdapter.notifyDataSetChanged();
                }
            }
        });
        isReverse = false;
        firstReverse = false;
        preFlag = "";
        if (showPreview) {
            ensurePlayFragment();
            tvPlay.setText("全屏");
        }
        llPlayerFragmentContainerBlock.setFocusable(showPreview);

        mSeriesGroupView = findViewById(R.id.mSeriesGroupView);
        tvSeriesGroup = findViewById(R.id.mSeriesGroupTv);
        mSeriesGroupView.setHasFixedSize(true);
        mSeriesGroupView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        seriesGroupAdapter = new BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_series_flag, seriesGroupOptions) {
            @Override
            protected void convert(BaseViewHolder helper, String item) {
                TextView tvSeries = helper.getView(R.id.tvSeriesFlag);
                tvSeries.setText(item);
                helper.getView(R.id.tvSeriesFlagSelect).setVisibility(helper.getLayoutPosition() == selectedSeriesGroupPosition ? View.VISIBLE : View.GONE);
                helper.itemView.setNextFocusUpId(R.id.mGridViewFlag);
                if (helper.getLayoutPosition() == getData().size() - 1) {
                    helper.itemView.setId(View.generateViewId());
                    helper.itemView.setNextFocusRightId(helper.itemView.getId());
                }else {
                    helper.itemView.setNextFocusRightId(View.NO_ID);
                }
            }
        };
        mSeriesGroupView.setAdapter(seriesGroupAdapter);

        llPlayerFragmentContainerBlock.setOnClickListener(v -> {
            enterFullPreview();
            if (firstReverse) {
                jumpToPlay();
                firstReverse=false;
            }
        });

        tvPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                if (showPreview) {
                    enterFullPreview();
                    if(firstReverse){
                        jumpToPlay();
                        firstReverse=false;
                    }
                } else {
                    jumpToPlay();
                }
            }
        });

        tvQuickSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startQuickSearch();
                QuickSearchDialog quickSearchDialog = new QuickSearchDialog(DetailActivity.this);
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, quickSearchData));
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, quickSearchWord));
                quickSearchDialog.show();
                if (pauseRunnable != null && pauseRunnable.size() > 0) {
                    searchExecutorService = Executors.newFixedThreadPool(5);
                    for (Runnable runnable : pauseRunnable) {
                        searchExecutorService.execute(runnable);
                    }
                    pauseRunnable.clear();
                    pauseRunnable = null;
                }
                quickSearchDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        try {
                            if (searchExecutorService != null) {
                                pauseRunnable = searchExecutorService.shutdownNow();
                                searchExecutorService = null;
                            }
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                });
            }
        });
        tvChangeSource.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                startDetailFallbackFromMenu();
            }
        });
        tvCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = tvCollect.getText().toString();
                if ("加入收藏".equals(text)) {
                    RoomDataManger.insertVodCollect(sourceKey, vodInfo);
                    Toast.makeText(DetailActivity.this, "已加入收藏夹", Toast.LENGTH_SHORT).show();
                    tvCollect.setText("取消收藏");
                } else {
                    RoomDataManger.deleteVodCollect(sourceKey, vodInfo);
                    Toast.makeText(DetailActivity.this, "已移除收藏夹", Toast.LENGTH_SHORT).show();
                    tvCollect.setText("加入收藏");
                }
            }
        });
        tvPlayUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //获取剪切板管理器
                ClipboardManager cm = (ClipboardManager)getSystemService(mContext.CLIPBOARD_SERVICE);
                //设置内容到剪切板
                cm.setPrimaryClip(ClipData.newPlainText(null, tvPlayUrl.getText().toString().replace("播放地址：","")));
                Toast.makeText(DetailActivity.this, "已复制", Toast.LENGTH_SHORT).show();
            }
        });


        tvSeriesSort.setOnClickListener(new View.OnClickListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onClick(View v) {
                if (vodInfo != null && vodInfo.seriesMap.size() > 0) {
                    vodInfo.reverseSort = !vodInfo.reverseSort;
                    isReverse = !isReverse;
                    tvSeriesSort.setText(isReverse?"倒序":"正序");
                    vodInfo.reverse();
                    List<VodInfo.VodSeries> revList = getPlayingSeriesList();
                    if (vodInfo.playIndex >= 0 && !revList.isEmpty()) {
                        vodInfo.playIndex = (revList.size() - 1) - vodInfo.playIndex;
                    }
                    firstReverse = !firstReverse;
                    setSeriesGroupOptions();
                    seriesAdapter.notifyDataSetChanged();

                    if (vodInfo.playIndex >= 0) customSeriesScrollPos(vodInfo.playIndex);
                    if(currentSeriesGroupView != null) {
                        TextView txtView = currentSeriesGroupView.findViewById(R.id.tvSeriesFlag);
                        txtView.setTextColor(Color.WHITE);
                    }
                }
            }
        });
        tvDesc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        FastClickCheckUtil.check(v);
                        DescDialog dialog = new DescDialog(mContext);
                        dialog.setDescribe(removeHtmlTag(mVideo.des));
                        dialog.show();
                    }
                });
            }
        });

        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                seriesSelect = false;
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                seriesSelect = true;
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });
        mGridViewFlag.setOnItemListener(new TvRecyclerView.OnItemListener() {
            private void refresh(View itemView, int position) {
                String newFlag = seriesFlagAdapter.getData().get(position).name;
                if (vodInfo != null && !vodInfo.playFlag.equals(newFlag)) {
                    String oldFlag = vodInfo.playFlag;
                    int oldIndex = vodInfo.playIndex;
                    // 预览模式下实际在播对象是 previewVodInfo，旧进度 key 要按它拼
                    String actualOldFlag = (previewVodInfo != null && !TextUtils.isEmpty(previewVodInfo.playFlag)) ? previewVodInfo.playFlag : oldFlag;
                    int actualOldIndex = previewVodInfo != null ? previewVodInfo.playIndex : oldIndex;
                    VodInfo.VodSeries currentSeries = getPlayingSeries(previewVodInfo, previewVodInfo == null ? null : previewVodInfo.playFlag);
                    List<VodInfo.VodSeries> oldSeriesList = vodInfo.seriesMap.get(oldFlag);
                    if (currentSeries == null && previewVodInfo == null && oldIndex >= 0 && oldSeriesList != null && !oldSeriesList.isEmpty()) {
                        int safeOldIndex = Math.max(0, Math.min(oldIndex, oldSeriesList.size() - 1));
                        currentSeries = oldSeriesList.get(safeOldIndex);
                    }
                    if (currentSeries == null) {
                        currentSeries = routeSwitchSeries;
                    }
                    for (int i = 0; i < vodInfo.seriesFlags.size(); i++) {
                        VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(i);
                        if (flag.name.equals(oldFlag)) {
                            flag.selected = false;
                            View oldItemView = mGridViewFlag.getLayoutManager().findViewByPosition(i);
                            if (oldItemView != null) oldItemView.findViewById(R.id.tvSeriesFlagSelect).setVisibility(View.GONE);
                            break;
                        }
                    }
                    VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(position);
                    flag.selected = true;
                    itemView.findViewById(R.id.tvSeriesFlagSelect).setVisibility(View.VISIBLE);
                    // clean pre flag select status
                    if (oldSeriesList != null && oldIndex >= 0 && oldSeriesList.size() > oldIndex) {
                        oldSeriesList.get(oldIndex).selected = false;
                    }
                    vodInfo.playFlag = newFlag;
                    List<VodInfo.VodSeries> newSeriesList = vodInfo.seriesMap.get(newFlag);
                    if (newSeriesList != null && !newSeriesList.isEmpty()) {
                        vodInfo.playIndex = clampIndex(findMatchingEpisodeIndex(currentSeries, newSeriesList), newSeriesList);
                        for (VodInfo.VodSeries series : newSeriesList) {
                            series.selected = false;
                        }
                        VodInfo.VodSeries switchedSeries = newSeriesList.get(vodInfo.playIndex);
                        switchedSeries.selected = true;
                        routeSwitchSeries = switchedSeries;
                        // 跨线路迁移播放时间（hhyun -> hhm3u8 等）：先落盘实时进度，再把旧线路 key 的时间复制到新线路 key
                        if (playFragment != null) playFragment.saveCurrentProgressNow();
                        migratePlaybackTimeAcrossFlags(actualOldFlag, actualOldIndex,
                                currentSeries == null ? "" : currentSeries.name,
                                newFlag, vodInfo.playIndex, switchedSeries.name);
                    } else if (currentSeries != null) {
                        routeSwitchSeries = currentSeries;
                    }
                    refreshList();
                }
                seriesFlagFocus = itemView;
            }

            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
//                seriesSelect = false;
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                refresh(itemView, position);
//                if(isReverse)vodInfo.reverse();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                refresh(itemView, position);
//                if(isReverse)vodInfo.reverse();
            }
        });
        seriesAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                List<VodInfo.VodSeries> clickList = getPlayingSeriesList();
                if (vodInfo != null && !clickList.isEmpty()) {
                    boolean reload = false;
                    for (int j = 0; j < clickList.size(); j++) {
                        seriesAdapter.getData().get(j).selected = false;
                        seriesAdapter.notifyItemChanged(j);
                    }
                    //解决倒叙不刷新
                    if (vodInfo.playIndex != position) {
                        // 同片多版本切换（如"HD中字"↔"HD国语"）：先落盘实时进度，再把旧版本时间迁移到新版本
                        List<VodInfo.VodSeries> curSeriesList = getPlayingSeriesList();
                        if (position >= 0 && position < curSeriesList.size()) {
                            VodInfo playingInfo = getActualPlayingVodInfo();
                            VodInfo.VodSeries oldSeries = getCurrentSeriesOf(playingInfo);
                            VodInfo.VodSeries newSeries = curSeriesList.get(position);
                            if (isSameContentVariant(oldSeries, newSeries)) {
                                if (playFragment != null) playFragment.saveCurrentProgressNow();
                                migrateSameContentPlaybackTime(playingInfo, oldSeries, vodInfo.playFlag, position, newSeries);
                            }
                        }
                        seriesAdapter.getData().get(position).selected = true;
                        seriesAdapter.notifyItemChanged(position);
                        vodInfo.playIndex = position;
                        routeSwitchSeries = seriesAdapter.getData().get(position);

                        reload = true;
                    }
                    //解决当前集不刷新的BUG
                    if (!preFlag.isEmpty() && !vodInfo.playFlag.equals(preFlag)) {
                        reload = true;
                    }
                    boolean isCurrentPlaying = !showPreview || isCurrentPreviewPlaying(position);
                    if (showPreview && !isCurrentPlaying) {
                        reload = true;
                    }

                    seriesAdapter.getData().get(vodInfo.playIndex).selected = true;
                    seriesAdapter.notifyItemChanged(vodInfo.playIndex);
                    //选集全屏 想选集不全屏的注释下面一行
                    if (showPreview && !fullWindows && previewVodInfo != null && TextUtils.equals(vodInfo.playFlag, previewVodInfo.playFlag) && (playFragment.getPlayer().isPlaying() || isCurrentPlaying)) enterFullPreview();
                    if (!showPreview || reload) {
                        jumpToPlay();
                        firstReverse=false;
                    }
                }
            }
        });

        mSeriesGroupView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                TextView txtView = itemView.findViewById(R.id.tvSeriesFlag);
                txtView.setTextColor(Color.WHITE);
//                currentSeriesGroupView = null;
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                selectSeriesGroup(itemView, position);
                if (vodInfo != null && !getPlayingSeriesList().isEmpty()) {
                    int targetPos = position * GroupCount;
//                    mGridView.smoothScrollToPosition(targetPos);
                    customSeriesScrollPos(targetPos);
                }
                currentSeriesGroupView = itemView;
                currentSeriesGroupView.isSelected();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) { }
        });
        tvSeriesSort.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                if (vodInfo != null && !getPlayingSeriesList().isEmpty()) {
                    int firstVisible = mGridView.getFirstVisiblePosition();
                    int lastVisible = mGridView.getLastVisiblePosition();
                    if (vodInfo.playIndex >= 0 && (vodInfo.playIndex < firstVisible || vodInfo.playIndex > lastVisible)) {
                        customSeriesScrollPos(vodInfo.playIndex);
                    }
                }
            } else {
                tvSeriesSort.setTextColor(Color.WHITE);
            }
        });
        seriesGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectSeriesGroup(view, position);
                if (vodInfo != null && !getPlayingSeriesList().isEmpty()) {
                    int targetPos =  position * GroupCount+1;

                    customSeriesScrollPos(targetPos);
                }
                if(currentSeriesGroupView != null) {
                    TextView txtView = currentSeriesGroupView.findViewById(R.id.tvSeriesFlag);
                    txtView.setTextColor(Color.WHITE);
                }
                currentSeriesGroupView = view;
                currentSeriesGroupView.isSelected();
            }
        });

        if(showPreview){
            llPlayerFragmentContainerBlock.requestFocus();
        }else {
            tvPlay.requestFocus();
        }
        setLoadSir(llLayout);
        if (fullWindows) {
            setFullPreview(true);
        }
    }

    //解决类似海贼王的超长动漫 焦点滚动失败的问题
    private void selectSeriesGroup(View selectedView, int position) {
        if (selectedSeriesGroupPosition == position) return;
        View previousView = mSeriesGroupView.getLayoutManager().findViewByPosition(selectedSeriesGroupPosition);
        if (previousView != null) previousView.findViewById(R.id.tvSeriesFlagSelect).setVisibility(View.GONE);
        selectedSeriesGroupPosition = position;
        selectedView.findViewById(R.id.tvSeriesFlagSelect).setVisibility(View.VISIBLE);
    }

    void customSeriesScrollPos(int targetPos)
    {
        if (targetPos < 0) return;
        mGridViewLayoutMgr.scrollToPositionWithOffset(targetPos>10?targetPos - 10:0, 0);
        mGridView.postDelayed(() -> {
            this.smoothScroller.setTargetPosition(targetPos);
            mGridViewLayoutMgr.startSmoothScroll(smoothScroller);
            mGridView.smoothScrollToPosition(targetPos);
        }, 50);
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    private List<Runnable> pauseRunnable = null;

    private void jumpToPlay() {
        List<VodInfo.VodSeries> jumpList = getPlayingSeriesList();
        if (vodInfo != null && !jumpList.isEmpty()) {
            preFlag = vodInfo.playFlag;
            //更新播放地址
            VodInfo.VodSeries jumpSeries = jumpList.get(clampIndex(vodInfo.playIndex, jumpList));
            setTextShow(tvPlayUrl, "播放地址：", jumpSeries.url);
            Bundle bundle = new Bundle();
            //保存历史
            insertVod(firstsourceKey, vodInfo);
        //   insertVod(sourceKey, vodInfo);
            bundle.putString("sourceKey", sourceKey);
//            bundle.putSerializable("VodInfo", vodInfo);
            App.getInstance().setVodInfo(vodInfo);
            if (showPreview) {
                ensurePlayFragment();
                updatePreviewVodInfo();
                App.getInstance().setVodInfo(previewVodInfo);
                if (playFragment != null) playFragment.setData(bundle);
            } else {
                ensurePlayFragment();
                if (playFragment != null) playFragment.setData(bundle);
                enterFullPreview();
            }
        }
    }

    private void updatePreviewVodInfo() {
        if (previewVodInfo == null) {
            previewVodInfo = new VodInfo();
        }
        previewVodInfo.id = vodInfo.id;
        previewVodInfo.name = vodInfo.name;
        previewVodInfo.pic = vodInfo.pic;
        previewVodInfo.sourceKey = vodInfo.sourceKey;
        previewVodInfo.playNote = vodInfo.playNote;
        previewVodInfo.seriesFlags = vodInfo.seriesFlags;
        previewVodInfo.seriesMap = vodInfo.seriesMap;
        previewVodInfo.playerCfg = vodInfo.playerCfg;
        previewVodInfo.playFlag = vodInfo.playFlag;
        previewVodInfo.playIndex = vodInfo.playIndex;
    }

    @SuppressLint("NotifyDataSetChanged")
    void refreshList() {
        List<VodInfo.VodSeries> list = getPlayingSeriesList();
        int listSize = list.size();

        if (listSize <= vodInfo.playIndex) {
            vodInfo.playIndex = 0;
        }

        if (!list.isEmpty()) {
            boolean canSelect = true;
            for (int j = 0; j < listSize; j++) {
                if (list.get(j).selected) {
                    canSelect = false;
                    break;
                }
            }
            if (canSelect && vodInfo.playIndex >= 0 && vodInfo.playIndex < listSize) {
                list.get(vodInfo.playIndex).selected = true;
            }
        }

        Paint pFont = new Paint();
//        pFont.setTypeface(Typeface.DEFAULT );
        Rect rect = new Rect();

        int w = 1;
        for (int i = 0; i < listSize; ++i) {
            String name = list.get(i).name;
            pFont.getTextBounds(name, 0, name.length(), rect);
            if (w < rect.width()) {
                w = rect.width();
            }
        }
        w += 32;
        int screenWidth = getWindowManager().getDefaultDisplay().getWidth() / 3;
        int offset = screenWidth / w;
        if (offset <= 2) offset = 2;
        if (offset > 6) offset = 6;
        mGridViewLayoutMgr.setSpanCount(offset);
        seriesAdapter.setNewData(list.isEmpty() ? null : list);

        setSeriesGroupOptions();

        mGridView.postDelayed(new Runnable() {
            @Override
            public void run() {
//                mGridView.smoothScrollToPosition(vodInfo.playIndex);
                if (vodInfo.playIndex >= 0) customSeriesScrollPos(vodInfo.playIndex);
            }
        }, 100);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void setSeriesGroupOptions(){
        List<VodInfo.VodSeries> list = getPlayingSeriesList();
        int listSize = list.size();
        int offset = mGridViewLayoutMgr.getSpanCount();
        seriesGroupOptions.clear();
        GroupCount=(offset==3 || offset==6)?30:20;
        if(listSize>100 && listSize<=400)GroupCount=60;
        if(listSize>400)GroupCount=120;
        if(listSize > 1) {
            tvSeriesGroup.setVisibility(View.VISIBLE);
            int remainedOptionSize = listSize % GroupCount;
            int optionSize = listSize / GroupCount;

            for(int i = 0; i < optionSize; i++) {
                if(vodInfo.reverseSort)
//                    seriesGroupOptions.add(String.format("%d - %d", i * GroupCount + GroupCount, i * GroupCount + 1));
                    seriesGroupOptions.add(String.format("%d - %d", listSize - (i * GroupCount + 1)+1, listSize - (i * GroupCount + GroupCount)+1));
                else
                    seriesGroupOptions.add(String.format("%d - %d", i * GroupCount + 1, i * GroupCount + GroupCount));
            }
            if(remainedOptionSize > 0) {
                if(vodInfo.reverseSort)
//                    seriesGroupOptions.add(String.format("%d - %d", optionSize * GroupCount + remainedOptionSize, optionSize * GroupCount + 1));
                    seriesGroupOptions.add(String.format("%d - %d", listSize - (optionSize * GroupCount + 1)+1, listSize - (optionSize * GroupCount + remainedOptionSize)+1));
                else
                    seriesGroupOptions.add(String.format("%d - %d", optionSize * GroupCount + 1, optionSize * GroupCount + remainedOptionSize));
            }
//            if(vodInfo.reverseSort) Collections.reverse(seriesGroupOptions);

            selectedSeriesGroupPosition = Math.max(0, Math.min(vodInfo.playIndex / GroupCount, seriesGroupOptions.size() - 1));
            seriesGroupAdapter.notifyDataSetChanged();
        }else {
            tvSeriesGroup.setVisibility(View.GONE);
        }
        if (!mGridViewFlag.hasFocus()) seriesFlagAdapter.notifyDataSetChanged();
        mGridViewQuality.setNextFocusUpId(tvSeriesGroup.getVisibility() == View.VISIBLE ? R.id.mSeriesSortTv : R.id.mGridViewFlag);
    }

    private void updateQualityOptions(JSONObject result) {
        ArrayList<String> options = new ArrayList<>();
        try {
            Object value = result == null ? null : result.opt("url");
            JSONArray urls = value instanceof JSONArray ? (JSONArray) value : value instanceof String ? new JSONArray((String) value) : null;
            if (urls != null) for (int i = 0; i + 1 < urls.length(); i += 2) options.add(urls.optString(i));
        } catch (Throwable th) {
        }
        if (qualityOptions.equals(options)) {
            if (qualityPosition == 0) return;
            qualityPosition = 0;
            qualityAdapter.notifyDataSetChanged();
            return;
        }
        qualityOptions.clear();
        qualityOptions.addAll(options);
        qualityPosition = 0;
        boolean visible = showPreview && options.size() > 1;
        mGridViewQuality.setVisibility(visible ? View.VISIBLE : View.GONE);
        seriesFlagAdapter.notifyDataSetChanged();
        seriesAdapter.notifyDataSetChanged();
        qualityAdapter.setNewData(new ArrayList<>(qualityOptions));
        int up = tvSeriesGroup.getVisibility() == View.VISIBLE ? R.id.mSeriesSortTv : R.id.mGridViewFlag;
        int down = visible ? R.id.mGridViewQuality : R.id.mGridView;
        mGridViewQuality.setNextFocusUpId(up);
        mGridViewQuality.setNextFocusDownId(R.id.mGridView);
        tvSeriesSort.setNextFocusDownId(down);
        mSeriesGroupView.setNextFocusDownId(down);
    }

    private void setTextShow(TextView view, String tag, String info) {
        if (info == null || info.trim().isEmpty()) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setText(Html.fromHtml(getHtml(tag, info)));
    }

    private String removeHtmlTag(String info) {
        if (TextUtils.isEmpty(info))
            return "";
        String text = info.replaceAll("\\[a=cr:(?:\\{.*?\\}|\\[.*?\\])\\/](.*?)\\[\\/a]", "$1");
        text = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
                : Html.fromHtml(text).toString();
        return text.replaceAll("\\s", "");
    }

    private void applyPreviewRoundCorners() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        final float radius = getResources().getDimension(R.dimen.preview_player_radius);
        ViewOutlineProvider provider = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
        llPlayerFragmentContainer.setClipToOutline(true);
        llPlayerFragmentContainer.setOutlineProvider(provider);
        llPlayerFragmentContainerBlock.setClipToOutline(true);
        llPlayerFragmentContainerBlock.setOutlineProvider(provider);
    }

    private void applyThumbPreviewStyle() {
        thumbContainer.setVisibility(showPreview ? View.GONE : View.VISIBLE);
        llPlayerPlace.setVisibility(showPreview ? View.VISIBLE : View.GONE);
        ivThumb.setVisibility(!showPreview ? View.VISIBLE : View.GONE);
        thumbContainer.setBackgroundResource(showPreview ? R.drawable.shape_detail_thumb_bg : R.drawable.shape_detail_thumb_idle_bg);
    }

    private void setPreviewRoundClip(boolean enable) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        llPlayerFragmentContainer.setClipToOutline(enable);
        llPlayerFragmentContainerBlock.setClipToOutline(enable);
        llPlayerFragmentContainer.setBackgroundResource(enable ? R.drawable.preview_player_round : android.R.color.black);
    }


    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.detailResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                if (detailFallbackActive && !detailFallbackLoadingCandidate) {
                    return;
                }
                if (absXml != null && !TextUtils.isEmpty(absXml.sourceKey)
                        && !TextUtils.equals(absXml.sourceKey, sourceKey)
                        && !("push_fallback".equals(absXml.sourceKey) && "push_agent".equals(sourceKey))) {
                    return;
                }
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    boolean fallbackResult = detailFallbackLoadingCandidate;
                    if (detailFallbackLoadingCandidate) {
                        detailFallbackLoadingCandidate = false;
                        llLayout.removeCallbacks(detailFallbackDetailTimeout);
                    }
                    if (fallbackResult) {
                        SourceBean fallbackSource = ApiConfig.get().getSource(sourceKey);
                        String fallbackName = fallbackSource == null ? sourceKey : fallbackSource.getName();
                        Toast.makeText(DetailActivity.this, "站点切换至" + fallbackName, Toast.LENGTH_SHORT).show();
                    }
                    showSuccess();
                    if(!TextUtils.isEmpty(absXml.msg) && !absXml.msg.equals("数据列表")){
                        resetDetailFallback();
                        Toast.makeText(DetailActivity.this, absXml.msg, Toast.LENGTH_SHORT).show();
                        showEmpty();
                        return;
                    }
                    mVideo = absXml.movie.videoList.get(0);
                    mVideo.id = vodId;
                    if (TextUtils.isEmpty(mVideo.name))mVideo.name = vod_name;
                    if (TextUtils.isEmpty(mVideo.name))mVideo.name = "TVBox";
                    vodInfo = new VodInfo();
                    routeSwitchSeries = null;
                    if((mVideo.pic==null || mVideo.pic.isEmpty()) && !vod_picture.isEmpty()){
                        mVideo.pic=vod_picture;
                    }
                    vodInfo.setVideo(mVideo);
                    vodInfo.sourceKey = mVideo.sourceKey;
                    sourceKey = mVideo.sourceKey;

                    tvName.setText(mVideo.name);
                    SourceBean displaySource = ApiConfig.get().getSource(firstsourceKey);
                    if (displaySource == null) {
                        displaySource = ApiConfig.get().getSource(sourceKey);
                    }
                    setTextShow(tvSite, "来源：", displaySource == null ? "" : displaySource.getName());
                    setTextShow(tvYear, "年份：", mVideo.year == 0 ? "" : String.valueOf(mVideo.year));
                    setTextShow(tvArea, "地区：", mVideo.area);
                    setTextShow(tvLang, "语言：", mVideo.lang);
                    if (!firstsourceKey.equals(sourceKey)) {
                    	setTextShow(tvType, "类型：", "[" + ApiConfig.get().getSource(sourceKey).getName() + "] 解析");
                    } else {
                    	setTextShow(tvType, "类型：", mVideo.type);
                    }
                    setTextShow(tvActor, "演员：", removeHtmlTag(mVideo.actor));
                    setTextShow(tvDirector, "导演：", removeHtmlTag(mVideo.director));
                    setTextShow(tvDes, "内容简介：", removeHtmlTag(mVideo.des));
                    if (!TextUtils.isEmpty(mVideo.pic)) {
                        com.github.tvbox.osc.util.ImgUtil.load(DefaultConfig.checkReplaceProxy(mVideo.pic), ivThumb, AutoSizeUtils.mm2px(mContext, 10), AutoSizeUtils.mm2px(mContext, 300), AutoSizeUtils.mm2px(mContext, 400), mVideo.name);
                    } else {
                        ivThumb.setImageDrawable(com.github.tvbox.osc.util.ImgUtil.createTextDrawable(mVideo.name));
                    }

                    if (vodInfo.seriesMap != null && vodInfo.seriesMap.size() > 0) {
                        mGridViewFlag.setVisibility(View.VISIBLE);
                        mGridView.setVisibility(View.VISIBLE);
                        tvPlay.setVisibility(View.VISIBLE);
                        mEmptyPlayList.setVisibility(View.GONE);

                        VodInfo vodInfoRecord = RoomDataManger.getVodInfo(sourceKey, vodId);
                        boolean sameNameRestored = false;
                        if (vodInfoRecord != null) {
                            // 本源有记录时，若其他源的同名记录更新，则以最新者为准（与"以最新播放时长为准"一致）
                            long curUpdate = RoomDataManger.getVodRecordUpdateTime(sourceKey, vodId);
                            VodInfo newerSameName = RoomDataManger.getVodInfoBySameNameNewerThan(sourceKey, vodId, vodInfo.name, curUpdate);
                            if (newerSameName == null && !TextUtils.isEmpty(vod_name)
                                    && !TextUtils.equals(vod_name.trim(), vodInfo.name == null ? "" : vodInfo.name.trim())) {
                                newerSameName = RoomDataManger.getVodInfoBySameNameNewerThan(sourceKey, vodId, vod_name, curUpdate);
                            }
                            if (newerSameName != null) {
                                vodInfoRecord = newerSameName;
                                sameNameRestored = true;   // 触发按内容重映射，防止下标错位
                            }
                        } else {
                            vodInfoRecord = RoomDataManger.getVodInfoBySameName(sourceKey, vodId, vodInfo.name);
                            if (vodInfoRecord == null && !TextUtils.isEmpty(vod_name)
                                    && !TextUtils.equals(vod_name.trim(), vodInfo.name == null ? "" : vodInfo.name.trim())) {
                                vodInfoRecord = RoomDataManger.getVodInfoBySameName(sourceKey, vodId, vod_name);
                            }
                            sameNameRestored = (vodInfoRecord != null);
                        }
                        if (vodInfoRecord != null) {
                            vodInfo.playIndex = Math.max(vodInfoRecord.playIndex, 0);
                            vodInfo.playFlag = vodInfoRecord.playFlag;
                            vodInfo.playerCfg = vodInfoRecord.playerCfg;
                            vodInfo.reverseSort = vodInfoRecord.reverseSort;
                            vodInfo.playNote = vodInfoRecord.playNote == null ? "" : vodInfoRecord.playNote;
                        } else {
                            vodInfo.playIndex = 0;
                            vodInfo.playFlag = null;
                            vodInfo.playerCfg = "";
                            vodInfo.reverseSort = false;
                            vodInfo.playNote = "";
                        }

                        if (vodInfo.reverseSort) {
                            vodInfo.reverse();
                        }

                        if (vodInfo.playFlag == null || !vodInfo.seriesMap.containsKey(vodInfo.playFlag))
                            vodInfo.playFlag = (String) vodInfo.seriesMap.keySet().toArray()[0];

                        // 跨源换源：不同源的集数列表顺序/集数不一致，用历史记住的集数名重新映射到当前源
                        boolean positionResolvedByContent = false;
                        if (sameNameRestored && !TextUtils.isEmpty(vodInfo.playNote)) {
                            String flagBeforeRemap = vodInfo.playFlag;
                            int remapFallback = vodInfo.playIndex;
                            int mapped = remapPlayIndexFromNote(vodInfo.playNote, vodInfo.playIndex);
                            if (mapped >= 0) {
                                vodInfo.playIndex = mapped;
                                positionResolvedByContent = true;
                            } else {
                                vodInfo.playFlag = flagBeforeRemap;
                            }
                        }

                        if (!positionResolvedByContent) {
                            // remap 未给出内容级结果时，才用兜底集重定位；
                            // 否则会把 remap 算对的精确下标覆盖成"就近/裸下标"结果
                            restoreDetailFallbackEpisode();
                        }
                        resetDetailFallback();

                        // 线路回退：remapPlayIndexFromNote 可能改写 playFlag、或该线路列表为 null，
                        // 这里兜底回退到第一条有效线路，避免 seriesMap.get() 返回 null 导致 NPE
                        List<VodInfo.VodSeries> playingSeriesList = vodInfo.seriesMap.get(vodInfo.playFlag);
                        if (playingSeriesList == null || playingSeriesList.isEmpty()) {
                            for (String flagKey : vodInfo.seriesMap.keySet()) {
                                List<VodInfo.VodSeries> candidate = vodInfo.seriesMap.get(flagKey);
                                if (candidate != null && !candidate.isEmpty()) {
                                    vodInfo.playFlag = flagKey;
                                    playingSeriesList = candidate;
                                    break;
                                }
                            }
                        }
                        if (playingSeriesList == null || playingSeriesList.isEmpty()) {
                            // 整部影片没有任何有效线路：走空态，不要继续往下走
                            showEmpty();
                            return;
                        }
                        vodInfo.playIndex = clampIndex(vodInfo.playIndex, playingSeriesList);

                        // 切源前先把实时播放位置落盘，否则迁移读到的是滞后值/0
                        if (playFragment != null) playFragment.saveCurrentProgressNow();
                        syncActualPlayingIntoVodInfo();
                        captureLivePlaybackSnapshot();

                        // syncActualPlayingIntoVodInfo 可能用预览对象的 playIndex 覆盖，
                        // 与当前线路长度未必匹配，迁移前必须重新钳位
                        vodInfo.playIndex = clampIndex(vodInfo.playIndex, playingSeriesList);
                        VodInfo.VodSeries currentEpisode = playingSeriesList.get(vodInfo.playIndex);

                        // 迁移一致性守卫：定位到的集与期望集（同名记录的 playNote / 快照集名）集数号不一致时，
                        // 说明定位可能错位（如"特别篇"导致整体后移），宁可不迁移，也不能把时间盖到错误的集上
                        String expectedName = sameNameRestored && vodInfoRecord != null ? vodInfoRecord.playNote
                                : (fallbackFromValid ? fallbackFromName : "");
                        int expectedNum = TextUtils.isEmpty(expectedName) ? -1 : extractEpisodeNumber(expectedName);
                        int actualNum = TextUtils.isEmpty(currentEpisode.name) ? -1 : extractEpisodeNumber(currentEpisode.name);
                        boolean positionTrusted = expectedNum < 0 || actualNum < 0 || expectedNum == actualNum;

                        // 跨源换源：把旧源记住的剧集内播放时间迁移到当前源对应集数，避免切源后时间记忆丢失
                        if (sameNameRestored) {
                            if (positionTrusted) {
                                migratePlaybackTimeFromOldSource(sourceKey, vodId, vodInfo.playFlag, vodInfo.playIndex,
                                        currentEpisode.name, vodInfoRecord);
                            }
                        } else if (fallbackFromValid) {
                            if (positionTrusted) {
                                migratePlaybackTimeFromOldSource(sourceKey, vodId, vodInfo.playFlag, vodInfo.playIndex,
                                        currentEpisode.name, null);
                            }
                        }
                        
                        int flagScrollTo = 0;
                        for (int j = 0; j < vodInfo.seriesFlags.size(); j++) {
                            VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(j);
                            if (flag.name.equals(vodInfo.playFlag)) {
                                flagScrollTo = j;
                                flag.selected = true;
                            } else
                                flag.selected = false;
                        }
                        //设置播放地址
                        setTextShow(tvPlayUrl, "播放地址：", playingSeriesList.get(vodInfo.playIndex).url);
                        seriesFlagAdapter.setNewData(vodInfo.seriesFlags);
                        mGridViewFlag.scrollToPosition(flagScrollTo);

                        refreshList();
                        if (showPreview) {
                            jumpToPlay();
                            llPlayerFragmentContainer.setVisibility(View.VISIBLE);
                            llPlayerFragmentContainerBlock.setVisibility(View.VISIBLE);
                            toggleSubtitleTextSize();
                        }
                        // startQuickSearch();
                    } else {
                        mGridViewFlag.setVisibility(View.GONE);
                        mGridView.setVisibility(View.GONE);
                        tvSeriesGroup.setVisibility(View.GONE);
                        tvPlay.setVisibility(View.GONE);
                        mEmptyPlayList.setVisibility(View.VISIBLE);
                        handleNoPlayableDetail();
                    }
                } else {
                    if (detailFallbackLoadingCandidate) {
                        detailFallbackLoadingCandidate = false;
                        detailFallbackDetailTimedOut = true;
                        llLayout.removeCallbacks(detailFallbackDetailTimeout);
                        loadNextDetailFallbackSource();
                        return;
                    }
                    handleEmptyDetail(absXml);
                }
            }
        });
        sourceViewModel.detailFallbackSearchResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                onDetailFallbackSearchResult(absXml);
            }
        });
    }

    private String getHtml(String label, String content) {
        if (content == null) {
            content = "";
        }
        return label + "<font color=\"#FFFFFF\">" + content + "</font>";
    }

    private String  vod_picture="";
    private String  vod_name="";
    private void initData() {
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            vod_name=bundle.getString("title", "");
            vod_picture=bundle.getString("picture", "");
            fromCollect = bundle.getBoolean("collect", false);
            Object fallbackCandidates = bundle.getSerializable(EXTRA_DETAIL_FALLBACK_CANDIDATES);
            if (fallbackCandidates instanceof ArrayList) {
                cacheDetailFallbackCandidates(vod_name, (ArrayList<Movie.Video>) fallbackCandidates);
            }
            loadDetail(bundle.getString("id", null), bundle.getString("sourceKey", ""));
        }
    }

    private void loadDetail(String vid, String key) {
        loadDetail(vid, key, false);
    }

    private void loadDetail(String vid, String key, boolean fallback) {
        if (!fallback) {
            resetDetailFallback();
        }
        vodId = vid;
        sourceKey = key;
        firstsourceKey = key;
        if (TextUtils.isEmpty(vid) || vid.startsWith("msearch:") || ApiConfig.get().getSource(sourceKey) == null) {
            handleNoPlayableDetail();
            return;
        }
        if (!fallback) {
            showLoading();
        }
        if (fallback && detailFallbackActive) {
            llLayout.removeCallbacks(detailFallbackDetailTimeout);
            llLayout.postDelayed(detailFallbackDetailTimeout, DETAIL_FALLBACK_DETAIL_TIMEOUT_MS);
        }
        sourceViewModel.getDetail(sourceKey, vodId, fallback && detailFallbackActive);
        boolean isVodCollect = RoomDataManger.isVodCollect(sourceKey, vodId);
        if (isVodCollect) {
            tvCollect.setText("取消收藏");
        } else {
            tvCollect.setText("加入收藏");
        }
    }

    private void handleEmptyDetail(AbsXml data) {
        boolean shouldFinish = data != null && !TextUtils.isEmpty(data.msg);
        if (shouldFinish || fromCollect) {
            resetDetailFallback();
            if (shouldFinish) {
                Toast.makeText(this, data.msg, Toast.LENGTH_SHORT).show();
            }
            finish();
            return;
        }
        handleNoPlayableDetail();
    }

    private void handleNoPlayableDetail() {
        if (detailFallbackActive) {
            detailFallbackLoadingCandidate = false;
            loadNextDetailFallbackSource();
            return;
        }
        startDetailFallback();
    }

    public boolean startDetailFallbackAfterLinesExhausted() {
        return startDetailFallback(false);
    }

    private void startDetailFallbackFromMenu() {
        if (!startDetailFallback(true)) {
            Toast.makeText(this, "暂无可切换的片源", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean startDetailFallback(boolean manual) {
        SourceBean currentSource = ApiConfig.get().getSource(sourceKey);
        if (isFinishing() || currentSource == null || !currentSource.isChangeable()) {
            return false;
        }
        if (detailFallbackActive) {
            Toast.makeText(this, "正在切换片源，请稍候…", Toast.LENGTH_SHORT).show();
            return true;
        }
        // 切源前：立即落盘当前播放进度，并记录实际播放位置快照（供迁移兜底）
        if (playFragment != null) playFragment.saveCurrentProgressNow();
        syncActualPlayingIntoVodInfo();
        captureLivePlaybackSnapshot();
        detailFallbackKeepCurrentDetail = mVideo != null && vodInfo != null
                && vodInfo.seriesMap != null && !vodInfo.seriesMap.isEmpty();
        llLayout.removeCallbacks(detailFallbackDetailTimeout);
        captureDetailFallbackEpisode();
        if (mVideo != null && !TextUtils.isEmpty(mVideo.name)) {
            vod_name = mVideo.name;
        }
        if (TextUtils.isEmpty(vod_name)) {
            return false;
        }
        detailFallbackExcludedSourceKey = sourceKey;
        detailFallbackTitle = vod_name.trim();
        // 每一轮切源都重新开始“已用源”记录：清掉历史轮次累积的记录，只把当前正在播放的源标记为已用。  
        // 不清会让 Set 只增不减，最终所有源都被判定“已用”→ 候选为空 → 点击静默无响应。  
        detailFallbackUsedSourceKeys.remove(detailFallbackTitle);
        addDetailFallbackUsedSource(sourceKey);
        // 预热：把该片名的历史缓存候选先塞进待试队列（加速本轮切源）。  
        // 注意：不再据此 return —— 必须继续执行 startDetailFallback() 发起全网搜索。  
        loadDetailFallbackCache();  
        startDetailFallback();
        return detailFallbackActive;
    }

    private void startDetailFallback() {
        detailFallbackTitle = vod_name == null ? "" : vod_name.trim();
        if (TextUtils.isEmpty(detailFallbackTitle)) {
            showDetailEmpty();
            return;
        }

        for (SourceBean bean : ApiConfig.get().getSourceBeanList()) {
            if (bean.isSearchable() && bean.isChangeable() && !TextUtils.equals(bean.getKey(), detailFallbackExcludedSourceKey) && !isDetailFallbackSourceUsed(bean.getKey())) {
                detailFallbackSourceOrder.add(bean.getKey());
            }
        }
        if (detailFallbackSourceOrder.isEmpty()) {
            if (!detailFallbackKeepCurrentDetail) {
                showDetailEmpty();
            } else {
                Toast.makeText(this, "没有更多可切换的片源", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        detailFallbackActive = true;
        detailFallbackSearching = true;
        detailFallbackSearchCollecting = true;
        detailFallbackSearchTimedOut = false;
        detailFallbackDetailTimedOut = false;
        detailFallbackSearchTimeoutScheduled = false;
        detailFallbackLoadingCandidate = false;
        detailFallbackBatchIndex = 0;
        detailFallbackNextSourceIndex = 0;
        detailFallbackToken = "detail_fallback_" + (++detailFallbackRequestIndex);
        detailFallbackTriedKeys.add(getDetailFallbackKey(sourceKey, vodId));
        scheduleDetailFallbackSearch();
        showLoading();
    }
    
    /**
     * 切源前调用：记录切源前实际正在播放的 源/线路/集。
     * 预览模式下实际播放对象是 previewVodInfo（PlayFragment.mVodInfo 指向它），
     * 播放器里的切集/自动连播只更新它，详情页 vodInfo.playIndex 是过期的，
     * 所以必须读实际对象，并且必须在 loadDetail 覆盖 sourceKey/vodId 之前捕获。
     */
    private void captureLivePlaybackSnapshot() {
        fallbackFromValid = false;
        VodInfo playing = showPreview && previewVodInfo != null ? previewVodInfo : vodInfo;
        if (playing == null || playing.seriesMap == null || TextUtils.isEmpty(playing.playFlag)) {
            return;
        }
        List<VodInfo.VodSeries> list = playing.seriesMap.get(playing.playFlag);
        if (list == null || list.isEmpty()) {
            return;
        }
        int idx = Math.max(0, Math.min(playing.playIndex, list.size() - 1));
        fallbackFromSourceKey = sourceKey == null ? "" : sourceKey;
        fallbackFromVodId = vodId == null ? "" : vodId;
        fallbackFromFlag = playing.playFlag;
        fallbackFromIndex = idx;
        fallbackFromName = list.get(idx).name == null ? "" : list.get(idx).name;
        fallbackFromValid = !TextUtils.isEmpty(fallbackFromName);
    }

    /**
     * 预览模式下把实际播放位置回写详情页 vodInfo，
     * 保证后续 insertVod 存的集数记忆与用户实际看到的一致。
     *
     * 注意：切源时 previewVodInfo 仍是【旧源】对象，其 playFlag/playIndex 是旧源的语义。
     * 只有当旧源的线路在新源里确实存在、且该线路下"下标对应同一集"时才能采纳，
     * 否则会把新源刚按内容重定位好的下标覆盖错（如新源前部多了特别篇）。
     */
    private void syncActualPlayingIntoVodInfo() {
        VodInfo actual = showPreview ? previewVodInfo : null;
        if (actual == null || vodInfo == null || actual == vodInfo) {
            return;
        }
        if (vodInfo.seriesMap == null || vodInfo.seriesMap.isEmpty()) {
            return;
        }
        // 判断是否为跨源同步：actual 的影片标识与当前 vodInfo 不一致 → 下标语义不同，不可直接搬
        boolean crossSource = !TextUtils.isEmpty(actual.sourceKey)
                && !TextUtils.isEmpty(vodInfo.sourceKey)
                && !TextUtils.equals(actual.sourceKey, vodInfo.sourceKey);

        // 只同步「确实有效」的线路：actual.playFlag 必须在 vodInfo.seriesMap 里能取到非空列表
        boolean flagSynced = false;
        if (!TextUtils.isEmpty(actual.playFlag) && vodInfo.seriesMap.containsKey(actual.playFlag)) {
            List<VodInfo.VodSeries> target = vodInfo.seriesMap.get(actual.playFlag);
            if (target != null && !target.isEmpty()) {
                vodInfo.playFlag = actual.playFlag;
                flagSynced = true;
            }
        }

        if (actual.playIndex < 0) {
            return;
        }

        if (!crossSource) {
            // 同源：直接同步即可（播放器切集/自动连播只更新 previewVodInfo）
            vodInfo.playIndex = actual.playIndex;
            return;
        }

        // 跨源：必须做「内容一致性」校验后才能采纳下标
        if (!flagSynced) {
            // 旧源线路在新源不存在，下标毫无参考价值，采纳必然错位
            return;
        }
        String actualName = getSeriesNameSafely(actual, actual.playFlag, actual.playIndex);
        String targetName = getSeriesNameSafely(vodInfo, vodInfo.playFlag, actual.playIndex);
        int actualNum = TextUtils.isEmpty(actualName) ? -1 : extractEpisodeNumber(actualName);
        int targetNum = TextUtils.isEmpty(targetName) ? -1 : extractEpisodeNumber(targetName);
        if (actualNum >= 0 && targetNum >= 0 && actualNum != targetNum) {
            // 下标同名不同集 → 说明两源集序不一致，不能用下标搬
            return;
        }
        vodInfo.playIndex = actual.playIndex;
    }

    private String getSeriesNameSafely(VodInfo info, String flag, int index) {
        if (info == null || info.seriesMap == null || TextUtils.isEmpty(flag) || index < 0) {
            return "";
        }
        List<VodInfo.VodSeries> list = info.seriesMap.get(flag);
        if (list == null || index >= list.size()) {
            return "";
        }
        VodInfo.VodSeries s = list.get(index);
        return s == null || s.name == null ? "" : s.name;
    }
    
    /**
     * 实际在播对象：预览模式下是 previewVodInfo（播放器切集/自动连播只更新它），否则详情页 vodInfo
     */
    private VodInfo getActualPlayingVodInfo() {
        return (showPreview && previewVodInfo != null) ? previewVodInfo : vodInfo;
    }

    private VodInfo.VodSeries getCurrentSeriesOf(VodInfo info) {
        if (info == null || info.seriesMap == null || TextUtils.isEmpty(info.playFlag)) {
            return null;
        }
        List<VodInfo.VodSeries> list = info.seriesMap.get(info.playFlag);
        if (list == null || list.isEmpty()) {
            return null;
        }
        int idx = Math.max(0, Math.min(info.playIndex, list.size() - 1));
        return list.get(idx);
    }

    /**
     * 判断两个条目是否"同一内容的两个版本"：
     * - 两边都提取到集数号：号相同且名字不同 → 同内容（第01集中字 ↔ 第01集国语）
     * - 两边都提取不到集数号：仅当该线路条目 ≤3（电影多版本场景）且名字不同 → 同内容（HD中字 ↔ HD国语）
     * - 一边有号一边没有：视为不同集，不迁移
     * 电视剧正常切集（第01集 → 第02集）因集数号不同，不会命中。
     */
    private boolean isSameContentVariant(VodInfo.VodSeries a, VodInfo.VodSeries b) {
        if (a == null || b == null || TextUtils.isEmpty(a.name) || TextUtils.isEmpty(b.name)) {
            return false;
        }
        if (TextUtils.equals(a.name.trim(), b.name.trim())) {
            return false;
        }
        int aNum = extractEpisodeNumber(a.name);
        int bNum = extractEpisodeNumber(b.name);
        if (aNum >= 0 && bNum >= 0) {
            return aNum == bNum;
        }
        if (aNum < 0 && bNum < 0) {
            List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
            return list != null && list.size() <= 3;
        }
        return false;
    }

    /**
     * 同内容版本之间迁移播放时间。key 拼法必须与 PlayFragment 第 1988 行 progressKey 完全一致。
     * 默认幂等：新版本已有进度则不覆盖（与修改点⑩跨线路迁移规则一致）。
     */
    private void migrateSameContentPlaybackTime(VodInfo playingInfo, VodInfo.VodSeries oldSeries,
                                                String newFlag, int newIndex, VodInfo.VodSeries newSeries) {
        try {
            if (playingInfo == null || oldSeries == null || newSeries == null
                    || TextUtils.isEmpty(playingInfo.playFlag)) {
                return;
            }
            String oldSourceKey = TextUtils.isEmpty(playingInfo.sourceKey) ? sourceKey : playingInfo.sourceKey;
            String oldVodId = TextUtils.isEmpty(playingInfo.id) ? vodId : playingInfo.id;
            long t = PlayProgressManager.get(oldSourceKey, oldVodId, playingInfo.playFlag, playingInfo.playIndex, oldSeries.name);
            if (t > 0) {
                PlayProgressManager.save(sourceKey, vodId, newFlag, newIndex, newSeries.name, t);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }
    
    private void captureDetailFallbackEpisode() {
        detailFallbackEpisode = null;
        detailFallbackEpisodeIndex = -1;
        // 预览模式下实际播放对象是 previewVodInfo，详情页 vodInfo.playIndex 可能已过期
        VodInfo playing = showPreview && previewVodInfo != null ? previewVodInfo : vodInfo;
        if (playing == null || playing.seriesMap == null || TextUtils.isEmpty(playing.playFlag)) {
            return;
        }
        List<VodInfo.VodSeries> seriesList = playing.seriesMap.get(playing.playFlag);
        if (seriesList == null || seriesList.isEmpty()) {
            return;
        }
        detailFallbackEpisodeIndex = Math.max(0, Math.min(playing.playIndex, seriesList.size() - 1));
        detailFallbackEpisode = seriesList.get(detailFallbackEpisodeIndex);
    }

    private void restoreDetailFallbackEpisode() {
        VodInfo.VodSeries fallback = detailFallbackEpisode;
        int fallbackIndex = detailFallbackEpisodeIndex;
        if (fallback == null && fallbackFromValid) {
            // 各切换入口都会留 live 快照；用它合成兜底集，保证任何入口都按内容重定位
            fallback = new VodInfo.VodSeries();
            fallback.name = fallbackFromName;
            fallbackIndex = fallbackFromIndex;
        }
        if (fallback == null || fallbackIndex < 0 || vodInfo == null || vodInfo.seriesMap == null) {
            return;
        }
        String preferredFlag = vodInfo.playFlag;
        List<VodInfo.VodSeries> preferredList = vodInfo.seriesMap.get(preferredFlag);
        int matchedIndex = findMatchingEpisodeIndex(fallback, preferredList);
        if (matchedIndex >= 0) {
            vodInfo.playIndex = matchedIndex;
            return;
        }
        if (vodInfo.seriesFlags != null) {
            for (VodInfo.VodSeriesFlag seriesFlag : vodInfo.seriesFlags) {
                if (seriesFlag == null || TextUtils.isEmpty(seriesFlag.name) || TextUtils.equals(preferredFlag, seriesFlag.name)) {
                    continue;
                }
                List<VodInfo.VodSeries> seriesList = vodInfo.seriesMap.get(seriesFlag.name);
                matchedIndex = findMatchingEpisodeIndex(fallback, seriesList);
                if (matchedIndex >= 0) {
                    vodInfo.playFlag = seriesFlag.name;
                    vodInfo.playIndex = matchedIndex;
                    return;
                }
            }
        }
        for (String flag : vodInfo.seriesMap.keySet()) {
            if (TextUtils.equals(preferredFlag, flag) || containsSeriesFlag(flag)) {
                continue;
            }
            List<VodInfo.VodSeries> seriesList = vodInfo.seriesMap.get(flag);
            matchedIndex = findMatchingEpisodeIndex(fallback, seriesList);
            if (matchedIndex >= 0) {
                vodInfo.playFlag = flag;
                vodInfo.playIndex = matchedIndex;
                return;
            }
        }
        if (preferredList != null && !preferredList.isEmpty()) {
            vodInfo.playIndex = nearestEpisodeIndex(fallback, fallbackIndex, preferredList);
        }
    }

    private boolean containsSeriesFlag(String name) {
        if (vodInfo == null || vodInfo.seriesFlags == null) {
            return false;
        }
        for (VodInfo.VodSeriesFlag flag : vodInfo.seriesFlags) {
            if (flag != null && TextUtils.equals(flag.name, name)) {
                return true;
            }
        }
        return false;
    }

    private void onDetailFallbackSearchResult(AbsXml data) {
        if (!detailFallbackActive || !detailFallbackSearchCollecting || data == null || !detailFallbackBatchToken.equals(data.searchToken)) {
            return;
        }
        detailFallbackPendingSources.remove(data.sourceKey);
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video == null || TextUtils.isEmpty(video.id) || isDetailFallbackSourceUsed(video.sourceKey) || !detailFallbackTitle.equals(video.name == null ? "" : video.name.trim())) {
                    continue;
                }
                String candidateKey = getDetailFallbackKey(video.sourceKey, video.id);
                if (!detailFallbackTriedKeys.contains(candidateKey) && detailFallbackCandidateKeys.add(candidateKey)) {
                    detailFallbackCandidates.add(video);
                    cacheDetailFallbackCandidate(video);
                }
            }
        }
        if (!detailFallbackLoadingCandidate && !detailFallbackCandidates.isEmpty()
                && (!detailFallbackSearchTimedOut || detailFallbackDetailTimedOut)) {
            loadNextDetailFallbackSource();
        }
        if (!detailFallbackLoadingCandidate) {
            if (detailFallbackSearching) {
                scheduleDetailFallbackSearch();
            } else if (detailFallbackPendingSources.isEmpty()) {
                finishDetailFallbackSearchCollection();
            }
        }
    }

    private void scheduleDetailFallbackSearch() {
        if (!detailFallbackActive || !detailFallbackSearching) {
            return;
        }
        if (!detailFallbackPendingSources.isEmpty() || detailFallbackLoadingCandidate) {
            return;
        }
        if (detailFallbackNextSourceIndex >= detailFallbackSourceOrder.size()) {
            detailFallbackSearching = false;
            llLayout.removeCallbacks(detailFallbackTimeout);
            stopDetailFallbackSearchExecutor();
            loadNextDetailFallbackSource();
            return;
        }

        stopDetailFallbackSearchExecutor();
        detailFallbackSearchExecutor = Executors.newFixedThreadPool(DETAIL_FALLBACK_MAX_SEARCH);
        detailFallbackBatchToken = detailFallbackToken + "_batch_" + (++detailFallbackBatchIndex);
        int batchEnd = Math.min(detailFallbackNextSourceIndex + DETAIL_FALLBACK_MAX_SEARCH, detailFallbackSourceOrder.size());
        while (detailFallbackNextSourceIndex < batchEnd) {
            final String searchKey = detailFallbackSourceOrder.get(detailFallbackNextSourceIndex++);
            final String searchTitle = detailFallbackTitle;
            final String searchToken = detailFallbackBatchToken;
            detailFallbackPendingSources.add(searchKey);
            detailFallbackSearchExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    sourceViewModel.getDetailFallbackSearch(searchKey, searchTitle, searchToken);
                }
            });
        }
        if (!detailFallbackSearchTimeoutScheduled) {
            detailFallbackSearchTimeoutScheduled = true;
            llLayout.postDelayed(detailFallbackTimeout, DETAIL_FALLBACK_SEARCH_TIMEOUT_MS);          
        }
        if (!detailFallbackLoadingCandidate && !detailFallbackCandidates.isEmpty()) {  
            loadNextDetailFallbackSource();  
        }
    }

    private void finishDetailFallbackSearchOnTimeout() {
        if (!detailFallbackActive || !detailFallbackSearching) {
            return;
        }
        detailFallbackSearchTimeoutScheduled = false;
        // Keep the current 20 searches alive so late results can be used by the next fallback source.
        detailFallbackSearching = false;
        detailFallbackSearchTimedOut = true;
        detailFallbackNextSourceIndex = detailFallbackSourceOrder.size();
        if (!detailFallbackLoadingCandidate) {
            if (!detailFallbackCandidates.isEmpty()) {
                loadNextDetailFallbackSource();
            } else {
                showDetailFallbackEmptyIfNeeded();
            }
        }
    }

    private void loadNextDetailFallbackSource() {
        while (!detailFallbackCandidates.isEmpty()) {
            Movie.Video video = detailFallbackCandidates.remove(0);
            String candidateKey = getDetailFallbackKey(video.sourceKey, video.id);
            if (isDetailFallbackSourceUsed(video.sourceKey) || !detailFallbackTriedKeys.add(candidateKey)) {
                continue;
            }
            detailFallbackLoadingCandidate = true;
            detailFallbackDetailTimedOut = false;
            addDetailFallbackUsedSource(video.sourceKey);
            vod_name = video.name == null ? "" : video.name;
            vod_picture = video.pic == null ? "" : video.pic;
            loadDetail(video.id, video.sourceKey, true);
            return;
        }
        if (detailFallbackSearching || detailFallbackSearchCollecting) {
            if (detailFallbackPendingSources.isEmpty()) {
                if (detailFallbackSearching) {
                    scheduleDetailFallbackSearch();
                } else {
                    finishDetailFallbackSearchCollection();
                }
            }
            return;
        }
        finishDetailFallbackWithoutResult();
    }

    private void finishDetailFallbackSearchCollection() {
        if (!detailFallbackSearchCollecting) {
            return;
        }
        detailFallbackSearchCollecting = false;
        stopDetailFallbackSearchExecutor();
        OkGo.getInstance().cancelTag(DETAIL_FALLBACK_SEARCH_TAG);
        if (!detailFallbackLoadingCandidate) {
            finishDetailFallbackWithoutResult();
        }
    }

    private void showDetailFallbackEmptyIfNeeded() {
        if (!detailFallbackKeepCurrentDetail) {
            showDetailEmpty();
        } else {
            showSuccess();
        }
    }

    private void finishDetailFallbackWithoutResult() {
        boolean keepCurrentDetail = detailFallbackKeepCurrentDetail;
        resetDetailFallback();
        if (keepCurrentDetail) {
            showSuccess();
        } else {
            showDetailEmpty();
        }
    }

    private void finishDetailFallbackDetailOnTimeout() {
        if (!detailFallbackActive || !detailFallbackLoadingCandidate) {
            return;
        }
        detailFallbackLoadingCandidate = false;
        detailFallbackDetailTimedOut = true;
        OkGo.getInstance().cancelTag("detail");
        loadNextDetailFallbackSource();
    }

    private String getDetailFallbackKey(String key, String id) {
        return (key == null ? "" : key) + "|" + (id == null ? "" : id);
    }

    private boolean loadDetailFallbackCache() {
        List<Movie.Video> cachedCandidates = detailFallbackCache.get(detailFallbackTitle);
        if (cachedCandidates == null || cachedCandidates.isEmpty()) {
            return false;
        }
        for (Movie.Video video : cachedCandidates) {
            SourceBean source = video == null ? null : ApiConfig.get().getSource(video.sourceKey);
            if (video == null || source == null || !source.isChangeable()
                    || TextUtils.equals(video.sourceKey, detailFallbackExcludedSourceKey) || isDetailFallbackSourceUsed(video.sourceKey)) {
                continue;
            }
            String candidateKey = getDetailFallbackKey(video.sourceKey, video.id);
            if (detailFallbackCandidateKeys.add(candidateKey)) {
                detailFallbackCandidates.add(video);
            }
        }
        return false;
    }

    private void cacheDetailFallbackCandidates(String title, List<Movie.Video> candidates) {
        title = title == null ? "" : title.trim();
        if (TextUtils.isEmpty(title) || candidates == null || candidates.isEmpty()) {
            return;
        }
        List<Movie.Video> cachedCandidates = detailFallbackCache.get(title);
        if (cachedCandidates == null) {
            cachedCandidates = new ArrayList<>();
            detailFallbackCache.put(title, cachedCandidates);
        }
        for (Movie.Video video : candidates) {
            if (cachedCandidates.size() >= DETAIL_FALLBACK_MAX_SEARCH || video == null
                    || TextUtils.isEmpty(video.id) || !TextUtils.equals(title, video.name == null ? "" : video.name.trim())) {
                continue;
            }
            String candidateKey = getDetailFallbackKey(video.sourceKey, video.id);
            boolean exists = false;
            for (Movie.Video cachedVideo : cachedCandidates) {
                if (candidateKey.equals(getDetailFallbackKey(cachedVideo.sourceKey, cachedVideo.id))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                cachedCandidates.add(video);
            }
        }
    }

    private void cacheDetailFallbackCandidate(Movie.Video video) {
        List<Movie.Video> cachedCandidates = detailFallbackCache.get(detailFallbackTitle);
        if (cachedCandidates == null) {
            cachedCandidates = new ArrayList<>();
            detailFallbackCache.put(detailFallbackTitle, cachedCandidates);
        }
        String candidateKey = getDetailFallbackKey(video.sourceKey, video.id);
        for (Movie.Video cachedVideo : cachedCandidates) {
            if (candidateKey.equals(getDetailFallbackKey(cachedVideo.sourceKey, cachedVideo.id))) {
                return;
            }
        }
        cachedCandidates.add(video);
    }

    private void addDetailFallbackUsedSource(String sourceKey) {
        if (TextUtils.isEmpty(detailFallbackTitle) || TextUtils.isEmpty(sourceKey)) {
            return;
        }
        Set<String> usedSources = detailFallbackUsedSourceKeys.get(detailFallbackTitle);
        if (usedSources == null) {
            usedSources = new HashSet<>();
            detailFallbackUsedSourceKeys.put(detailFallbackTitle, usedSources);
        }
        usedSources.add(sourceKey);
    }

    private boolean isDetailFallbackSourceUsed(String sourceKey) {
        Set<String> usedSources = detailFallbackUsedSourceKeys.get(detailFallbackTitle);
        return usedSources != null && usedSources.contains(sourceKey);
    }

    private void showDetailEmpty() {
        showEmpty();
        llPlayerFragmentContainer.setVisibility(View.GONE);
        llPlayerFragmentContainerBlock.setVisibility(View.GONE);
    }

    private void resetDetailFallback() {
        detailFallbackActive = false;
        detailFallbackSearching = false;
        detailFallbackSearchCollecting = false;
        detailFallbackSearchTimedOut = false;
        detailFallbackDetailTimedOut = false;
        detailFallbackSearchTimeoutScheduled = false;
        detailFallbackKeepCurrentDetail = false;
        detailFallbackLoadingCandidate = false;
        detailFallbackBatchIndex = 0;
        detailFallbackNextSourceIndex = 0;
        detailFallbackToken = "";
        detailFallbackBatchToken = "";
        detailFallbackTitle = "";
        detailFallbackExcludedSourceKey = "";
        detailFallbackSourceOrder.clear();
        detailFallbackCandidates.clear();
        detailFallbackPendingSources.clear();
        detailFallbackCandidateKeys.clear();
        detailFallbackTriedKeys.clear();
        detailFallbackEpisode = null;
        detailFallbackEpisodeIndex = -1;
        if (llLayout != null) {
            llLayout.removeCallbacks(detailFallbackTimeout);
            llLayout.removeCallbacks(detailFallbackDetailTimeout);
        }
        stopDetailFallbackSearchExecutor();
        OkGo.getInstance().cancelTag(DETAIL_FALLBACK_SEARCH_TAG);
    }

    private void stopDetailFallbackSearchExecutor() {
        if (detailFallbackSearchExecutor != null) {
            detailFallbackSearchExecutor.shutdownNow();
            detailFallbackSearchExecutor = null;
        }
    }

    private boolean isFirstLoad = true;
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_REFRESH) {
            if (event.obj != null) {
                if (event.obj instanceof VodInfo) {
                    syncPlayingVodInfo((VodInfo) event.obj);
                } else if (event.obj instanceof Integer) {
                    int index = (int) event.obj;
                    List<VodInfo.VodSeries> eventList = getPlayingSeriesList();
                    for (int j = 0; j < eventList.size(); j++) {
                        seriesAdapter.getData().get(j).selected = false;
                        seriesAdapter.notifyItemChanged(j);
                    }
                    seriesAdapter.getData().get(index).selected = true;
                    seriesAdapter.notifyItemChanged(index);
                    if(!isFirstLoad)mGridView.setSelection(index);
                    vodInfo.playIndex = index;
                    routeSwitchSeries = seriesAdapter.getData().get(index);
                    //保存历史
                    insertVod(firstsourceKey, vodInfo);
                    isFirstLoad = false;
                } else if (event.obj instanceof JSONObject) {
                    vodInfo.playerCfg = event.obj.toString();
                    //保存历史
                    insertVod(firstsourceKey, vodInfo);
                } else if (event.obj instanceof String) {
                    String url = event.obj.toString();
                    //设置更新播放地址
                    setTvPlayUrl(url);
                }

            }
        } else if (event.type == RefreshEvent.TYPE_PLAY_QUALITY) {
            updateQualityOptions(event.obj instanceof JSONObject ? (JSONObject) event.obj : null);
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_SELECT) {
            if (event.obj != null) {
                Movie.Video video = (Movie.Video) event.obj;
                // 与手动切源一致：先落盘实时进度、同步实际播放位置、留快照
                if (playFragment != null) playFragment.saveCurrentProgressNow();
                syncActualPlayingIntoVodInfo();
                captureLivePlaybackSnapshot();
                vod_name = video.name;
                vod_picture = video.pic;
                loadDetail(video.id, video.sourceKey);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD_CHANGE) {
            if (event.obj != null) {
                String word = (String) event.obj;
                switchSearchWord(word);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_RESULT) {
            try {
                searchData(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                searchData(null);
            }
        }
    }

    private String searchTitle = "";
    private boolean hadQuickStart = false;
    private final List<Movie.Video> quickSearchData = new ArrayList<>();
    private final List<String> quickSearchWord = new ArrayList<>();
    private ExecutorService searchExecutorService = null;
    private ExecutorService detailFallbackSearchExecutor;
    private final List<String> detailFallbackSourceOrder = new ArrayList<>();
    private final List<Movie.Video> detailFallbackCandidates = new ArrayList<>();
    private final HashMap<String, List<Movie.Video>> detailFallbackCache = new HashMap<>();
    private final HashMap<String, Set<String>> detailFallbackUsedSourceKeys = new HashMap<>();
    private final Set<String> detailFallbackPendingSources = new HashSet<>();
    private final Set<String> detailFallbackCandidateKeys = new HashSet<>();
    private final Set<String> detailFallbackTriedKeys = new HashSet<>();
    private VodInfo.VodSeries detailFallbackEpisode;
    private int detailFallbackEpisodeIndex = -1;
    
    // 切源前快照：记录"实际正在播"的源/线路/集，供进度迁移兜底（详见 readOldTimeFromSnapshot）
    private String fallbackFromSourceKey = "";
    private String fallbackFromVodId = "";
    private String fallbackFromFlag = "";
    private int fallbackFromIndex = -1;
    private String fallbackFromName = "";
    private boolean fallbackFromValid = false;
    
    private boolean detailFallbackActive;
    private boolean detailFallbackSearching;
    private boolean detailFallbackSearchCollecting;
    private boolean detailFallbackSearchTimedOut;
    private boolean detailFallbackDetailTimedOut;
    private boolean detailFallbackSearchTimeoutScheduled;
    private boolean detailFallbackKeepCurrentDetail;
    private boolean detailFallbackLoadingCandidate;
    private int detailFallbackRequestIndex;
    private int detailFallbackBatchIndex;
    private int detailFallbackNextSourceIndex;
    private String detailFallbackToken = "";
    private String detailFallbackBatchToken = "";
    private String detailFallbackTitle = "";
    private String detailFallbackExcludedSourceKey = "";
    private final Runnable detailFallbackTimeout = new Runnable() {
        @Override
        public void run() {
            finishDetailFallbackSearchOnTimeout();
        }
    };
    private final Runnable detailFallbackDetailTimeout = new Runnable() {
        @Override
        public void run() {
            finishDetailFallbackDetailOnTimeout();
        }
    };

    private void switchSearchWord(String word) {
        OkGo.getInstance().cancelTag("quick_search");
        quickSearchData.clear();
        searchTitle = word;
        searchResult();
    }

    private void startQuickSearch() {
        initCheckedSourcesForSearch();
        if (hadQuickStart)
            return;
        hadQuickStart = true;
        OkGo.getInstance().cancelTag("quick_search");
        quickSearchWord.clear();
        searchTitle = mVideo.name;
        quickSearchData.clear();
        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle));
        // 分词
//        OkGo.<String>get("http://api.pullword.com/get.php?source=" + URLEncoder.encode(searchTitle) + "&param1=0&param2=0&json=1")
//                .tag("fenci")
//                .execute(new AbsCallback<String>() {
//                    @Override
//                    public String convertResponse(okhttp3.Response response) throws Throwable {
//                        if (response.body() != null) {
//                            return response.body().string();
//                        } else {
//                            throw new IllegalStateException("网络请求错误");
//                        }
//                    }
//
//                    @Override
//                    public void onSuccess(Response<String> response) {
//                        String json = response.body();
//                        try {
//                            for (JsonElement je : new Gson().fromJson(json, JsonArray.class)) {
//                                quickSearchWord.add(je.getAsJsonObject().get("t").getAsString());
//                            }
//                        } catch (Throwable th) {
//                            th.printStackTrace();
//                        }
//                        List<String> words = new ArrayList<>(new HashSet<>(quickSearchWord));
//                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words));
//                    }
//
//                    @Override
//                    public void onError(Response<String> response) {super.onError(response);}
//                });

        searchResult();
    }

    private void searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        searchExecutorService = Executors.newFixedThreadPool(5);
        List<SourceBean> searchRequestList = new ArrayList<>();
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        searchRequestList.remove(home);
        searchRequestList.add(0, home);

        ArrayList<String> siteKey = new ArrayList<>();
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable() || !bean.isQuickSearch()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            siteKey.add(bean.getKey());
        }
        for (String key : siteKey) {
            searchExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    sourceViewModel.getQuickSearch(key, searchTitle);
                }
            });
        }
    }

    private void searchData(AbsXml absXml) {
        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            List<Movie.Video> data = new ArrayList<>();
            for (Movie.Video video : absXml.movie.videoList) {
                // 去除当前相同的影片
                if (video.sourceKey.equals(sourceKey) && video.id.equals(vodId))
                    continue;
                data.add(video);
            }
            quickSearchData.addAll(data);
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, data));
        }
    }

    private void syncPlayingVodInfo(VodInfo playingVodInfo) {
        if (playingVodInfo == null || vodInfo == null || vodInfo.seriesMap == null) {
            return;
        }
        String newFlag = playingVodInfo.playFlag;
        if (TextUtils.isEmpty(newFlag) || !vodInfo.seriesMap.containsKey(newFlag)) {
            return;
        }
        List<VodInfo.VodSeries> newSeriesList = vodInfo.seriesMap.get(newFlag);
        if (newSeriesList == null || newSeriesList.isEmpty()) {
            return;
        }

        String oldFlag = vodInfo.playFlag;
        int oldIndex = vodInfo.playIndex;
        boolean sameFlag = TextUtils.equals(oldFlag, newFlag);
        VodInfo.VodSeries playingSeries = getPlayingSeries(playingVodInfo, newFlag);
        int newIndex = findMatchingEpisodeIndex(playingSeries, newSeriesList);
        vodInfo.playFlag = newFlag;
        vodInfo.playIndex = newIndex;
        if (playingVodInfo.playerCfg != null) {
            vodInfo.playerCfg = playingVodInfo.playerCfg;
        }

        for (VodInfo.VodSeriesFlag flag : vodInfo.seriesFlags) {
            flag.selected = flag.name.equals(newFlag);
        }
        for (List<VodInfo.VodSeries> seriesList : vodInfo.seriesMap.values()) {
            if (seriesList == null) {
                continue;
            }
            for (VodInfo.VodSeries series : seriesList) {
                series.selected = false;
            }
        }
        if (newIndex >= 0) {
            newSeriesList.get(newIndex).selected = true;
            routeSwitchSeries = newSeriesList.get(newIndex);
        }

        seriesFlagAdapter.notifyDataSetChanged();
        if (sameFlag && newIndex >= 0 && oldIndex >= 0 && oldIndex < newSeriesList.size()) {
            if (oldIndex != newIndex) {
                seriesAdapter.notifyItemChanged(oldIndex);
                seriesAdapter.notifyItemChanged(newIndex);
            }
        } else {
            refreshList();
        }
        if (newIndex >= 0) {
            setTvPlayUrl(newSeriesList.get(newIndex).url);
        }

        int flagIndex = -1;
        for (int i = 0; i < vodInfo.seriesFlags.size(); i++) {
            if (vodInfo.seriesFlags.get(i).name.equals(newFlag)) {
                flagIndex = i;
                break;
            }
        }
        if (flagIndex >= 0) {
            mGridViewFlag.scrollToPosition(flagIndex);
            if (mGridViewFlag.hasFocus()) {
                mGridViewFlag.setSelection(flagIndex);
            }
        }
        if (!isFirstLoad && newIndex >= 0) {
            mGridView.setSelection(newIndex);
        }

        insertVod(firstsourceKey, vodInfo);
        isFirstLoad = false;
    }

    private VodInfo.VodSeries getPlayingSeries(VodInfo playingVodInfo, String flag) {
        if (playingVodInfo == null || playingVodInfo.seriesMap == null || TextUtils.isEmpty(flag)) {
            return null;
        }
        List<VodInfo.VodSeries> playingList = playingVodInfo.seriesMap.get(flag);
        if (playingList == null || playingList.isEmpty()) {
            return null;
        }
        int safeIndex = Math.max(0, Math.min(playingVodInfo.playIndex, playingList.size() - 1));
        return playingList.get(safeIndex);
    }

    /**
     * 取当前播放线路的剧集列表：任何一环为空都返回空列表，绝不返回 null。
     */
    private List<VodInfo.VodSeries> getPlayingSeriesList() {
        if (vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag)) {
            return Collections.emptyList();
        }
        List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 把索引钳位到列表合法范围内，越界返回 0（列表为空时返回 0，调用方需自行判空）。
     */
    private int clampIndex(int index, List<?> list) {
        if (list == null || list.isEmpty()) return 0;
        return Math.max(0, Math.min(index, list.size() - 1));
    }

    private boolean isCurrentPreviewPlaying(int position) {
        if (!showPreview || previewVodInfo == null || vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag)) {
            return false;
        }
        if (!TextUtils.equals(vodInfo.playFlag, previewVodInfo.playFlag) || previewVodInfo.playIndex != position) {
            return false;
        }
        List<VodInfo.VodSeries> currentList = vodInfo.seriesMap.get(vodInfo.playFlag);
        if (currentList == null || position < 0 || position >= currentList.size()) {
            return false;
        }
        VodInfo.VodSeries currentSeries = currentList.get(position);
        VodInfo.VodSeries previewSeries = getPlayingSeries(previewVodInfo, previewVodInfo.playFlag);
        return currentSeries != null && previewSeries != null && TextUtils.equals(currentSeries.url, previewSeries.url);
    }

    private int findSameEpisodeIndex(VodInfo.VodSeries currentSeries, List<VodInfo.VodSeries> targetList, int fallbackIndex) {
        if (targetList == null || targetList.isEmpty()) {
            return 0;
        }
        int matchedIndex = findMatchingEpisodeIndex(currentSeries, targetList);
        return matchedIndex >= 0 ? matchedIndex : Math.max(0, Math.min(fallbackIndex, targetList.size() - 1));
    }

    private int findMatchingEpisodeIndex(VodInfo.VodSeries currentSeries, List<VodInfo.VodSeries> targetList) {
        if (targetList == null || targetList.isEmpty()) {
            return -1;
        }
        if (targetList.size() == 1) {
            return 0;
        }
        if (currentSeries == null || TextUtils.isEmpty(currentSeries.name)) {
            return -1;
        }
        int currentEpisode = extractEpisodeNumber(currentSeries.name);
        int matchedIndex = -1;
        int bestScore = 0;
        for (int i = 0; i < targetList.size(); i++) {
            VodInfo.VodSeries targetSeries = targetList.get(i);
            int score = getEpisodeMatchScore(currentSeries.name, currentEpisode, targetSeries == null ? null : targetSeries.name);
            if (score > bestScore) {
                bestScore = score;
                matchedIndex = i;
            }
        }
        return matchedIndex;
    }

    /**
     * 重定位最终兜底：能提取集数号时，取"集数号最接近"的条目（差值相同取靠前的），
     * 避免列表前部有"特别篇"等额外条目导致整体后移时，裸下标指向错误的集；
     * 无法提取集数号时才退回钳位后的原始下标。
     */
    private int nearestEpisodeIndex(VodInfo.VodSeries target, int fallbackIndex, List<VodInfo.VodSeries> list) {
        if (list == null || list.isEmpty()) return 0;
        int clamped = Math.max(0, Math.min(fallbackIndex, list.size() - 1));
        if (target == null || TextUtils.isEmpty(target.name)) return clamped;
        int targetNum = extractEpisodeNumber(target.name);
        if (targetNum < 0) return clamped;
        int bestIndex = -1, bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            VodInfo.VodSeries s = list.get(i);
            if (s == null || TextUtils.isEmpty(s.name)) continue;
            int n = extractEpisodeNumber(s.name);
            if (n < 0) continue;
            int diff = Math.abs(n - targetNum);
            if (diff < bestDiff) { bestDiff = diff; bestIndex = i; }
        }
        return bestIndex >= 0 ? bestIndex : clamped;
    }

    private int getEpisodeMatchScore(String currentName, int currentEpisode, String targetName) {
        if (TextUtils.isEmpty(currentName) || TextUtils.isEmpty(targetName)) {
            return 0;
        }
        if (targetName.equalsIgnoreCase(currentName)) {
            return 100;
        }
        if (currentEpisode >= 0 && extractEpisodeNumber(targetName) == currentEpisode) {
            return 80;
        }
        String currentLower = currentName.toLowerCase(Locale.ROOT);
        String targetLower = targetName.toLowerCase(Locale.ROOT);
        if (currentEpisode < 0 && currentName.length() >= 2 && targetLower.contains(currentLower)) {
            return 70;
        }
        if (currentEpisode < 0 && targetName.length() >= 2 && currentLower.contains(targetLower)) {
            return 60;
        }
        return 0;
    }

    private int extractEpisodeNumber(String name) {
        if (TextUtils.isEmpty(name)) {
            return -1;
        }
        try {
            String text = name.replaceAll("\\[.*?\\]|\\(.*?\\)", "");
            text = text.replaceAll("\\b(19|20)\\d{2}\\b", "");
            text = text.toLowerCase(Locale.ROOT).replaceAll("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4", "");
            Matcher matcher = Pattern.compile("(?i)(?:ep|\\u7b2c|e|[\\-\\.\\s])\\s?(\\d{1,4})").matcher(text);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
            String number = text.replaceAll("\\D+", "");
            if (!TextUtils.isEmpty(number)) {
                return Integer.parseInt(number);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
    
    private void migratePlaybackTimeFromOldSource(String newSourceKey, String newVodId, String newFlag, int newIndex,
                                              String newSeriesName, VodInfo oldRecord) {
    try {
        if (TextUtils.isEmpty(newSourceKey) || TextUtils.isEmpty(newVodId) || TextUtils.isEmpty(newSeriesName)) {
            return;
        }
        long newVal = PlayProgressManager.get(newSourceKey, newVodId, newFlag, newIndex, newSeriesName);
        if (oldRecord == null) {
            return;
        }
        String oldFlag = oldRecord.playFlag;
        String oldSeriesName = oldRecord.playNote;
        if (TextUtils.isEmpty(oldSeriesName)) {
            try {
                List<VodInfo.VodSeries> oldList = oldRecord.seriesMap == null ? null : oldRecord.seriesMap.get(oldFlag);
                if (oldList != null && oldRecord.playIndex >= 0 && oldRecord.playIndex < oldList.size()) {
                    oldSeriesName = oldList.get(oldRecord.playIndex).name;
                }
            } catch (Throwable ignored) {
            }
        }
        if (TextUtils.isEmpty(oldSeriesName)) {
            return;
        }
        long oldTime = PlayProgressManager.get(oldRecord.sourceKey, oldRecord.id, oldFlag, oldRecord.playIndex, oldSeriesName);
        if (oldTime > 0) {
            PlayProgressManager.save(newSourceKey, newVodId, newFlag, newIndex, newSeriesName, oldTime);
        }
    } catch (Throwable th) {
        th.printStackTrace();
    }
}

    private long readOldTimeFromRecord(VodInfo oldRecord) {
        try {
            if (oldRecord == null) {
                return 0;
            }
            String oldFlag = oldRecord.playFlag;
            String oldSeriesName = oldRecord.playNote;
            if (TextUtils.isEmpty(oldSeriesName)) {
                // 注意：oldRecord.seriesMap 未持久化（序列化时被排除），无法回退取集名
                return 0;
            }
            String oldKey = (oldRecord.sourceKey == null ? "" : oldRecord.sourceKey)
                    + (oldRecord.id == null ? "" : oldRecord.id)
                    + (oldFlag == null ? "" : oldFlag) + oldRecord.playIndex + oldSeriesName;
            return readCachedLong(oldKey);
        } catch (Throwable th) {
            return 0;
        }
    }

    private long readOldTimeFromSnapshot() {
        try {
            if (!fallbackFromValid || fallbackFromIndex < 0 || TextUtils.isEmpty(fallbackFromName)) {
                return 0;
            }
            String oldKey = (fallbackFromSourceKey == null ? "" : fallbackFromSourceKey)
                    + (fallbackFromVodId == null ? "" : fallbackFromVodId)
                    + (fallbackFromFlag == null ? "" : fallbackFromFlag)
                    + fallbackFromIndex + fallbackFromName;
            long t = readCachedLong(oldKey);
            if (t > 0) {
                fallbackFromValid = false; // 快照一次性消费，防止残留影响后续切源
            }
            return t;
        } catch (Throwable th) {
            return 0;
        }
    }
    
    /**
     * 同一源内跨线路迁移播放时间（如 hhyun -> hhm3u8）。
     * sourceKey/vodId 不变，仅 playFlag 变化，把旧线路对应集的时间复制到新线路对应集。
     * key 拼法必须与 PlayFragment 第 1988 行 progressKey 完全一致。
     */
    private void migratePlaybackTimeAcrossFlags(String oldFlag, int oldIndex, String oldName,
                                                String newFlag, int newIndex, String newName) {
        try {
            if (TextUtils.isEmpty(oldFlag) || TextUtils.isEmpty(newFlag)
                    || TextUtils.isEmpty(oldName) || TextUtils.isEmpty(newName)
                    || oldIndex < 0 || newIndex < 0) {
                return;
            }
            if (TextUtils.equals(oldFlag, newFlag) && oldIndex == newIndex && TextUtils.equals(oldName, newName)) {
                return;
            }
            long newVal = PlayProgressManager.get(sourceKey, vodId, newFlag, newIndex, newName);
            long t = PlayProgressManager.get(sourceKey, vodId, oldFlag, oldIndex, oldName);
            if (t > 0) {
                PlayProgressManager.save(sourceKey, vodId, newFlag, newIndex, newName, t);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }
    
    private long readCachedLong(String key) {
        if (TextUtils.isEmpty(key)) {
            return 0;
        }
        Object cache = CacheManager.getCache(MD5.string2MD5(key));
        if (cache instanceof Long) {
            return (Long) cache;
        }
        if (cache instanceof String) {
            try {
                return Long.parseLong((String) cache);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
    
    private int remapPlayIndexFromNote(String episodeNote, int fallbackIndex) {
        if (vodInfo == null || vodInfo.seriesMap == null || vodInfo.seriesMap.isEmpty() || TextUtils.isEmpty(episodeNote)) {
            return -1;
        }
        VodInfo.VodSeries probe = new VodInfo.VodSeries();
        probe.name = episodeNote;
        String preferredFlag = vodInfo.playFlag;

        // 线路访问顺序：当前已选线路在前，其余线路在后
        List<String> flagOrder = new ArrayList<>();
        if (!TextUtils.isEmpty(preferredFlag) && vodInfo.seriesMap.containsKey(preferredFlag)) {
            flagOrder.add(preferredFlag);
        }
        for (String flag : vodInfo.seriesMap.keySet()) {
            if (!flagOrder.contains(flag)) {
                flagOrder.add(flag);
            }
        }

        String base = normalizeSeriesLabel(episodeNote);
        for (String flag : flagOrder) {
            List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(flag);
            if (list == null || list.isEmpty()) {
                continue;
            }
            // 单条目线路（电影/唯一版本）：无论叫 "正片"、"HD" 还是 "HD国语"，都定位到 0
            if (list.size() == 1) {
                vodInfo.playFlag = flag;
                return 0;
            }
            int idx = findMatchingEpisodeIndex(probe, list);
            if (idx >= 0) {
                vodInfo.playFlag = flag;
                return idx;
            }
            // 集数名/集数号都匹配不到时，用归一化标签就近匹配
            if (!TextUtils.isEmpty(base)) {
                for (int i = 0; i < list.size(); i++) {
                    VodInfo.VodSeries s = list.get(i);
                    if (s != null && base.equals(normalizeSeriesLabel(s.name))) {
                        vodInfo.playFlag = flag;
                        return i;
                    }
                }
            }
        }
        // 各线路精确匹配都失败时，按集数号就近兜底，避免调用方退回裸下标错位
        int targetNum = extractEpisodeNumber(episodeNote);
        if (targetNum >= 0) {
            int bestFlagPos = -1, bestIndex = -1, bestDiff = Integer.MAX_VALUE;
            for (int pos = 0; pos < flagOrder.size(); pos++) {
                List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(flagOrder.get(pos));
                if (list == null || list.isEmpty() || list.size() == 1) continue;
                for (int i = 0; i < list.size(); i++) {
                    VodInfo.VodSeries s = list.get(i);
                    if (s == null || TextUtils.isEmpty(s.name)) continue;
                    int n = extractEpisodeNumber(s.name);
                    if (n < 0) continue;
                    int diff = Math.abs(n - targetNum);
                    if (diff < bestDiff) { bestDiff = diff; bestIndex = i; bestFlagPos = pos; }
                }
            }
            if (bestIndex >= 0) {
                vodInfo.playFlag = flagOrder.get(bestFlagPos);
                return bestIndex;
            }
        }
        return -1;
    }

    private String normalizeSeriesLabel(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        String t = name.replaceAll("\\[.*?\\]|\\(.*?\\)|（.*?）", "").toLowerCase(Locale.ROOT);
        t = t.replaceAll("\\b(hd|hdr|uhd|4k|2160p|1080p|720p|480p|2k|bluray|remux)\\b", "")
             .replaceAll("国语|粤语|普语|原声|配音|无声|无字幕|中字|字幕|台词|高清|蓝光", "");
        t = t.replaceAll("[\\s._\\-]+", "").trim();
        // 特殊集统一归一化：跨源"特別篇/特别篇/SP/OVA/剧场版"可互认
        if (t.matches(".*(特别篇|特別篇|特别编|剧场版|劇場版|番外|ova|oad|special|sp\\d*).*")) {
            return "sp";
        }
        return t;
    }
    
    private void insertVod(String sourceKey, VodInfo vodInfo) {
        List<VodInfo.VodSeries> list = getPlayingSeriesList();
        if (list.isEmpty()) {
            vodInfo.playNote = "";
        } else {
            vodInfo.playNote = list.get(clampIndex(vodInfo.playIndex, list)).name;
        }
        RoomDataManger.insertVodRecord(sourceKey, vodInfo);
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
    }

    @Override
    protected void onDestroy() {
        resetDetailFallback();
        super.onDestroy();
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        OkGo.getInstance().cancelTag("fenci");
        OkGo.getInstance().cancelTag("detail");
        OkGo.getInstance().cancelTag("quick_search");
        releasePlayFragment();
        EventBus.getDefault().unregister(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_FULL_WINDOWS, fullWindows);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (fullWindows) {
            if (playFragment.onBackPressed())
                return;
            exitFullPreview();
            return;
        }
        if (mGridView != null && mGridView.hasFocus()
                && mGridViewFlag != null && mGridViewFlag.getVisibility() == View.VISIBLE) {
            try {
                if (seriesFlagFocus != null && seriesFlagFocus.isShown()
                        && seriesFlagFocus.requestFocus()) {
                    return;
                }
                if (mGridViewFlag.requestFocus()) {
                    return;
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        if(showPreview && playFragment!=null){
            try {
                playFragment.setPlayTitle(false);
                playFragment.setExitingPreview(true);
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && !fullWindows && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            startDetailFallbackFromMenu();
            return true;
        }
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.dispatchKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.onKeyDown(keyCode,event)) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.onKeyUp(keyCode,event)) {
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    // preview
    VodInfo previewVodInfo = null;
    boolean fullWindows = false;
    private int previewOrientation;
    private boolean previewOrientationChanged;
    ViewGroup.LayoutParams windowsPreview = null;
    ViewGroup.LayoutParams windowsFull = null;

    void toggleFullPreview() {
        setFullPreview(!fullWindows);
    }

    void enterFullPreview() {
        setFullPreview(true);
    }

    void exitFullPreview() {
        boolean needRefreshSeries = previewOrientationChanged;
        setFullPreview(false);
        previewOrientationChanged = false;
        if (needRefreshSeries) {
            refreshSeriesAfterFullPreview();
        } else {
            syncSeriesSelectionAfterFullPreview();
        }
    }

    private void refreshSeriesAfterFullPreview() {
        if (seriesAdapter == null || vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag)) return;
        if (vodInfo.seriesMap.get(vodInfo.playFlag) == null) return;
        mGridView.post(new Runnable() {
            @Override
            public void run() {
                mGridView.getRecycledViewPool().clear();
                mGridView.setAdapter(seriesAdapter);
                refreshList();
            }
        });
    }

    private void syncSeriesSelectionAfterFullPreview() {
        if (seriesAdapter == null || vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag)) return;
        List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
        if (list == null || list.isEmpty()) return;
        if (vodInfo.playIndex >= list.size()) {
            vodInfo.playIndex = list.size() - 1;
        }
        setSeriesGroupOptions();
        mGridView.post(new Runnable() {
            @Override
            public void run() {
                int firstVisible = mGridView.getFirstVisiblePosition();
                int lastVisible = mGridView.getLastVisiblePosition();
                if (vodInfo.playIndex >= 0 && (vodInfo.playIndex < firstVisible || vodInfo.playIndex > lastVisible)) {
                    customSeriesScrollPos(vodInfo.playIndex);
                }
            }
        });
        mSeriesGroupView.post(new Runnable() {
            @Override
            public void run() {
                if (mSeriesGroupView.getVisibility() == View.VISIBLE) {
                    mSeriesGroupView.scrollToPosition(selectedSeriesGroupPosition);
                }
            }
        });
    }

    void setFullPreview(boolean full) {
        if (windowsPreview == null) {
            windowsPreview = llPlayerFragmentContainer.getLayoutParams();
        }
        if (windowsFull == null) {
            windowsFull = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        if (full) {
            previewOrientation = getResources().getConfiguration().orientation;
            previewOrientationChanged = false;
        }
        fullWindows = full;
        if (playFragment != null) {
            playFragment.setAutoSwitchLineEnabled(!fullWindows);
            playFragment.setPreviewMode(!fullWindows);
        }
        llPlayerFragmentContainer.setVisibility(fullWindows || showPreview ? View.VISIBLE : View.GONE);
        llPlayerFragmentContainer.setLayoutParams(fullWindows ? windowsFull : windowsPreview);
        setPreviewRoundClip(!fullWindows);
        llPlayerFragmentContainerBlock.setVisibility(!fullWindows && showPreview ? View.VISIBLE : View.GONE);
        mGridView.setVisibility(fullWindows ? View.GONE : View.VISIBLE);
        mGridViewFlag.setVisibility(fullWindows ? View.GONE : View.VISIBLE);
        if (fullWindows) {
            tvSeriesGroup.setVisibility(View.GONE);
        } else {
            List<VodInfo.VodSeries> list = vodInfo == null || vodInfo.seriesMap == null || TextUtils.isEmpty(vodInfo.playFlag) ? null : vodInfo.seriesMap.get(vodInfo.playFlag);
            tvSeriesGroup.setVisibility(list != null && list.size() > 1 ? View.VISIBLE : View.GONE);
            seriesFlagAdapter.notifyDataSetChanged();
            if (showPreview) mGridView.requestFocus();
            else {
                if (playFragment != null) playFragment.pauseForHidden();
                mGridView.requestFocus();
            }
        }
        toggleSubtitleTextSize();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (fullWindows && newConfig.orientation != previewOrientation) {
            previewOrientation = newConfig.orientation;
            previewOrientationChanged = true;
        }
    }

    void ensurePlayFragment() {
        if (playFragment != null) return;
        playFragment = new PlayFragment();
        getSupportFragmentManager().beginTransaction().add(R.id.previewPlayer, playFragment).commitNowAllowingStateLoss();
        playFragment.setPreviewMode(!fullWindows);
    }

    void releasePlayFragment() {
        if (playFragment == null) return;
        getSupportFragmentManager().beginTransaction().remove(playFragment).commitNowAllowingStateLoss();
        playFragment = null;
    }

    void toggleSubtitleTextSize() {
        int subtitleTextSize  = SubtitleHelper.getTextSize(this);
        if (!fullWindows) {
            subtitleTextSize *= 0.6;
        }
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE, subtitleTextSize));
    }

    public PlayFragment getPlayFragment() {
        return playFragment;
    }

    private void setTvPlayUrl(String url)
    {
        setTextShow(tvPlayUrl, "播放地址：", url);
    }
}
