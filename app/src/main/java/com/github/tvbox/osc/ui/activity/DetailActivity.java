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
import com.github.tvbox.osc.util.EpisodeDict;
import com.github.tvbox.osc.util.EpisodeNameMatcher;
import com.github.tvbox.osc.util.EpisodeOnlineResolver;
import com.github.tvbox.osc.util.EpisodeResolveInitializer;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.ShowTitleKey;
import com.github.tvbox.osc.util.SourceQualityStore;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.viewmodel.SearchSession;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.jessyan.autosize.utils.AutoSizeUtils;

import android.graphics.Paint;

import com.github.tvbox.osc.cache.PlayProgressManager;

import java.util.Collections;
import java.util.Comparator;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */

public class DetailActivity extends BaseActivity {
    private static final String STATE_FULL_WINDOWS = "detail_full_windows";
    private static final String DETAIL_FALLBACK_SEARCH_TAG = "detail_fallback_search";
    // ★ P2-4：EXTRA_DETAIL_FALLBACK_CANDIDATES 已删除。旧的「候选快照」Intent 通道
    //   （限 20 条、严格 Equals 匹配、序列化后可能超 Binder 1MB）已被下方标题路径取代，
    //   是后者的真子集，故读写两侧一并移除，彻底消除 TransactionTooLargeException 风险面。
    /**
     * 候选池读取键（标题）。
     *
     * <p>取代了旧的「候选快照」通道：旧通道传的是「点击搜索结果那一刻的快照」，
     * 而聚合搜索当时仍在分批跑，快照必然残缺，表现为「切源只有两三个站，
     * 返回一趟再进才变多」。</p>
     *
     * <p>现在只传一个标题，详情页凭它去
     * {@code SourceViewModel.getSearchSession()} 的候选池读取<b>当前最新</b>集合。
     * 池子由搜索会话单一持有、边搜边补，因此进详情页时看到的就是完整候选，
     * 且后续新增的也会自然可见。</p>
     */
    public static final String EXTRA_DETAIL_FALLBACK_TITLE = "detailFallbackTitle";
    /**
     * 全网搜索时的<b>并发上限</b>（滑动窗口大小），不再是"每批固定发这么多个"。
     *
     * <p><b>历史包袱</b>：原实现是"凑满 20 个发一批，等这批全部回来或超时才发下一批"。
     * 实测日志（09:02:08 → 09:02:16）显示第 1、2 批各耗满 3.5s —— 批次要等<b>最慢</b>的
     * 那个源，哪怕同批 19 个源 243ms 就失败了也照样干等 3.5s。结果是：40 个源花了 7s 才
     * 试完，第一个命中要到第 3 批，进入详情页后<b>空白 8.1s</b> 才有内容。</p>
     *
     * <p><b>现在</b>：改为固定并发上限的滑动窗口 —— 只要有源返回（成功/失败/超时）腾出名额，
     * 立刻补发列表里的下一个源。于是：
     * <ul>
     *   <li>不再为最慢的源陪跑，快源能持续把列表往前推；</li>
     *   <li>所有源的总发完时间 ≈ ⌈列表长度 ÷ 并发⌉ × 平均单源耗时，而不是"批次数 × 3.5s"。</li>
     * </ul>
     * 上限取 20：与上一版（锁步分批时的批大小）<b>完全相同</b>，也就是这台设备与网络
     * 已经跑通过的并发量，不引入新的带宽风险；变快的部分全部来自「不再等最慢的源」。</p>
     */
    private static final int DETAIL_FALLBACK_MAX_SEARCH = 20;
    private static final long DETAIL_FALLBACK_SEARCH_TIMEOUT_MS = 8000L;
    /**
     * 「提前消费」等待窗口：第一个候选进缓存后等这么久就立刻放起来。
     *
     * <p><b>这是空白期的真正主因</b>。原流程规定：搜索阶段（collecting）只负责把候选池灌满，
     * <b>第一个候选要等搜索阶段结束才被消费</b>。而搜索阶段只有两条结束路径：
     * <ol>
     *   <li>全部源发完（旧实现 = 5 批 × 3.5s ≈ 17.5s）；</li>
     *   <li>{@link #DETAIL_FALLBACK_SEARCH_TIMEOUT_MS} 全局超时（8s）。</li>
     * </ol>
     * 实测日志里 {@code pollPick}（第一个候选被选中的那一刻）发生在 startSearch 之后
     * <b>8.1s</b> —— 正好是第 2 条路径，也就是"干等了 8 秒全局超时"。这 8 秒就是用户看到的
     * "只有背景"，和源快慢基本无关。</p>
     *
     * <p>现在：第一个候选落进缓存后再等 1.2s（让另外几个快源也落地，
     * 便于 {@code pollNextCycledSource()} 按质量挑，而不是闭眼拿第一个回来的），
     * 就提前放起来。搜索阶段在后台继续跑，照样把整圈缓存补满 —— 两者互不冲突。</p>
     */
    private static final long DETAIL_FALLBACK_FIRST_CONSUME_DELAY_MS = 1200L;
    private static final long DETAIL_FALLBACK_DETAIL_TIMEOUT_MS = 6000L;
    // ===== 方案A：探路请求 =====
    private static final String DETAIL_FALLBACK_PROBE_TAG = "detail_fallback_probe";
    private static final long DETAIL_FALLBACK_PROBE_TIMEOUT_MS = 2500L;
    // ===== 方案B：按源超时（替代 8s 全局超时）=====
    private static final long DETAIL_FALLBACK_SOURCE_TIMEOUT_MS = 3500L;

    /**
     * 一次切源里，因「候选不可用」而<b>自动续切</b>的次数上限。
     *
     * <p>续切触发点有三：详情为空、详情无剧集、详情超时。网络差时（DNS 失败、
     * 整片站点不可达）这三个条件会连续成立，状态机于是一路往下试，
     * 表现为用户点一次切源却「连跳好几个站」，且期间无法中断。</p>
     *
     * <p>达到上限即停止并保留当前画面，由用户决定是否再点一次——
     * 连跳十几个站对用户毫无价值，只会让人以为应用失控。</p>
     */
    private static final int DETAIL_FALLBACK_MAX_FAILOVER = 3;
    // ===== 方案D：缓存持久化 =====
    private static final String HAWK_FALLBACK_CACHE_PREFIX = "fb_cache_";
    private static final long FALLBACK_CACHE_TTL_MS = 24 * 60 * 60 * 1000L;
    /** 候选池写入顺序索引（LRU 用）。 */
    private static final String HAWK_FALLBACK_CACHE_INDEX = "fb_cache_index";
    /**
     * 候选池最多保留多少部片。
     *
     * <p>每部片一个 Hawk key，若只靠 24h TTL 被动清理，看过的片子多了就会在
     * Hawk（SharedPreferences）里堆出成百上千条 key，读写都变慢。这里按写入
     * 顺序保留最近这么多部，超出即删最旧的。</p>
     */
    private static final int FALLBACK_CACHE_MAX_ENTRIES = 40;
    // ===== 方案E：站点命中率统计 =====
    // 已统一到 SourceQualityStore（key 前缀 fb_stat_，6 字段格式），与搜索页/播放页共用。
    // 不再在本类里维护自有前缀，避免两套格式互相覆盖。
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
    /** 「正在切换片源…」提示最长驻留时长；超时自动关闭，避免候选源全挂时弹窗永久残留。 */
    private static final long SWITCHING_SOURCE_TOAST_TIMEOUT_MS = 8000L;
    /** 切源进行中的提示弹窗实例（同一会话内复用，避免连点堆叠）。 */
    private Toast switchingSourceToast = null;
    private final Runnable hideSwitchingSourceToast = new Runnable() {
        @Override
        public void run() {
            dismissSwitchingSourceToast();
        }
    };
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
                String clickedFlag = seriesFlagAdapter.getData().get(position).name;
                if (vodInfo != null && !vodInfo.playFlag.equals(clickedFlag)) {
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
                    // 清理旧线路的集选中态
                    if (oldSeriesList != null && oldIndex >= 0 && oldSeriesList.size() > oldIndex) {
                        oldSeriesList.get(oldIndex).selected = false;
                    }

                    // ---------------- 选源：跳过"没有这一集"的站点 ----------------
                    // 需求：点击的目标源若不含当前集，不应静默切到第0集（会覆盖播放记录），
                    // 而应从点击位置开始沿线路轮询，找到第一条真正含有该集的源；
                    // 全部都没有才放弃切源，保持原线路与进度不变。
                    FlagMatch hit = findFirstFlagWithEpisode(currentSeries, position);
                    if (hit == null) {
                        // 所有线路都没有这一集：放弃切源，还原选中态，明确告知用户
                        updateFlagSelectionUi(oldFlag);
                        android.widget.Toast.makeText(DetailActivity.this,
                                "该剧集在所切换的源中未找到，已保持当前播放",
                                android.widget.Toast.LENGTH_SHORT).show();
                        seriesFlagFocus = itemView;
                        return;
                    }
                    final String newFlag = hit.flag;
                    final int matchedIndex = hit.index;

                    updateFlagSelectionUi(newFlag);
                    vodInfo.playFlag = newFlag;
                    List<VodInfo.VodSeries> newSeriesList = vodInfo.seriesMap.get(newFlag);
                    if (newSeriesList != null && !newSeriesList.isEmpty()
                            && matchedIndex >= 0 && matchedIndex < newSeriesList.size()) {
                        // 保守落地：matchedIndex 一定 >= 0（findFirstFlagWithEpisode 只返回命中的线路），
                        // 走 safeLandPlayIndex 统一入口，语义与"匹配失败不落地"保持一致。
                        if (!safeLandPlayIndex(matchedIndex, newSeriesList, false)) {
                            // 理论上不可达：命中线路必然带合法下标。真发生则放弃切源，保持原状态。
                            updateFlagSelectionUi(oldFlag);
                            vodInfo.playFlag = actualOldFlag;
                            seriesFlagFocus = itemView;
                            return;
                        }
                        // 跨域（日期↔期数）且本地未命中时，异步联网精确重定位（成功后自动刷新播放地址）
                        tryOnlineCrossDomainResolve(currentSeries == null ? "" : currentSeries.name, newFlag,
                                newSeriesList, vodInfo.playIndex);
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
            // 方向5：起播在即 → 开起播保护窗。窗口内聚合搜索只暂停「新派发」，
            // 在途请求不动；首帧到达（PlayFragment.markPlaybackStarted）即恢复。
            SearchSession.getShared().beginStartupGuard("jumpToPlay");
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
                        // 切源成功：保留候选池与仍在跑的批处理，只复位本轮状态机。
                        // 用 resetDetailFallback() 会 cancelTag 把批处理掐死，
                        // 导致缓存永远只有第一个源 → 下次点击只能切回它。
                        resetDetailFallbackKeepCache();

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
                        //
                        // ★ 跨域安全：旧源是期数式（第2期上）、新源是日期式（20260411上）时，
                        //   两者的 extractEpisodeNumber 量纲不同（2 vs 20260411），直接比数值恒不相等，
                        //   会把本来正确的"跨域定位"误判为错位 → 整条时间迁移被跳过 → 用户看到"切对了集但时间归零"。
                        //   改用 EpisodeNameMatcher.positionTrustedAcrossDomain：跨域一律放行，同域才严格比序号。
                        String expectedName = sameNameRestored && vodInfoRecord != null ? vodInfoRecord.playNote
                                : (fallbackFromValid ? fallbackFromName : "");
                        boolean positionTrusted = EpisodeNameMatcher.positionTrustedAcrossDomain(
                                expectedName, currentEpisode.name);

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
                        // ★ 详情判空（movie 有值但 seriesMap 为空）也要先把站点指示刷出来。
                        // 切源时这是常见情况：某些源的详情接口返回结构不符合预期，
                        // 但它的播放线路其实是好的。老逻辑直接走 handleNoPlayableDetail()
                        // 自动续切下一个源，页面一个字节都不更新 —— 用户看到画面在变、
                        // 文字却纹丝不动，必然以为"没切成功"而反复点击。
                        //
                        // 此刻剧集区已被 mEmptyPlayList 占位（点不到集数/线路），
                        // 所以只改「来源」不会造成"来源与剧集不一致"的误导。
                        if (detailFallbackActive || detailFallbackLoadingCandidate) {
                            showDetailFallbackIndicators(sourceKey,
                                    detailFallbackCacheVideo(detailFallbackTitle, sourceKey));
                            // 详情**已成功返回**、但该源没有可播剧集 → 这是「确认不可用」，
                            // 不是网络抖动。记入 DeadKeys，本圈与后续各圈都跳过它，
                            // 免得一圈又一圈地反复试同一个空站（这正是轮转"停不下来"的原因之一）。
                            // 注意判定条件：必须是 detailFallbackActive（切源会话内），
                            // 避免把正常进详情页的空结果也记成拉黑。
                            // ★ 写入粒度必须与轮转侧的读取粒度一致：轮转按「源|影片id」
                            //   精确匹配（见 scanOrderedSources / detailFallbackFirstAliveId），这里若只写
                            //   sourceKey，黑名单永远命中不了 —— 空站会被一圈又一圈地
                            //   反复试，这正是「切源停不下来 / 只在两三个站来回」的根因。
                            LOG.sw("[FB] dead source=" + sourceKey + " id=" + vodId
                                    + " reason=noSeries");
                            detailFallbackDeadKeys.add(getDetailFallbackKey(sourceKey, vodId));
                        }
                        handleNoPlayableDetail();
                    }
                } else {
                    if (detailFallbackLoadingCandidate) {
                        // ★ 先刷站点指示，再复位 ← 顺序不能反：
                        // detailFallbackLoadingCandidate 是「本次响应属于切源请求」的唯一凭据，
                        // 必须先据它把页面画出来，复位后再判断就晚了。
                        showDetailFallbackIndicators(sourceKey,
                                detailFallbackCacheVideo(detailFallbackTitle, sourceKey));
                        detailFallbackLoadingCandidate = false;
                        detailFallbackDetailTimedOut = true;
                        llLayout.removeCallbacks(detailFallbackDetailTimeout);
                        if (!consumeFailoverBudget("emptyDetail")) {
                            return;
                        }
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
            // ★ P2-4：旧的「候选快照」Intent 通道已移除。
            //   它是「点击搜索结果那一刻的残缺快照」（聚合搜索仍在分批跑），
            //   且限 20 条、用严格 Equals 匹配，是下方标题路径的**真子集**。
            //   保留它只会白白承担 TransactionTooLargeException 风险，
            //   故写入侧（FastSearchActivity.putDetailFallbackCandidates）与
            //   此处读取侧一并删除；兼容性无损失 —— 标题路径拿到的永远是更全的集合。
            // ★ 新路径：从搜索会话的单一候选池读取最新集合（无快照残缺问题）
            String fallbackTitle = bundle.getString(EXTRA_DETAIL_FALLBACK_TITLE, "");
            if (!TextUtils.isEmpty(fallbackTitle)) {
                List<Movie.Video> poolCandidates = SearchSession.getShared().getCandidates(fallbackTitle);
                if (!poolCandidates.isEmpty()) {
                    // 同上：进来那一刻读到的池子是既有结果，不算「新命中」。
                    // 真正的「新命中」由 refreshFallbackCandidatesFromSession（切源前）
                    // 与 onDetailFallbackSearchResult（后台回流）置脏。
                    cacheDetailFallbackCandidates(vod_name, poolCandidates, false);
                }
            }
            loadDetail(bundle.getString("id", null), bundle.getString("sourceKey", ""));
        }
    }

    private void loadDetail(String vid, String key) {
        loadDetail(vid, key, false);
    }

    /**
     * 页面上是否已有可显示的详情内容（剧集列表非空）。
     *
     * <p>用于区分两种"加载中"：有内容时切源不打扰用户（不盖 Loading），
     * 无内容时必须有指示，否则就是"只有背景"的空页。</p>
     */
    private boolean hasRenderedDetail() {
        return mVideo != null && vodInfo != null
                && vodInfo.seriesMap != null && !vodInfo.seriesMap.isEmpty();
    }

    private void loadDetail(String vid, String key, boolean fallback) {
        if (!fallback) {
            resetDetailFallback();
        }
        vodId = vid;
        sourceKey = key;
        firstsourceKey = key;
        if (TextUtils.isEmpty(vid) || vid.startsWith("msearch:") || ApiConfig.get().getSource(sourceKey) == null) {
            // ★ 进入即无可用详情（聚合搜索的 msearch: 结果、源已失效/被删等）：
            //   这里必然要走"切源"流程去找可播放源。而切源链路为了不遮挡正在播放的
            //   播放器，刻意不显示全屏 Loading（见 startDetailFallback 注释）。
            //   但此时页面上<b>本来就没有任何内容</b>——既不显示 Loading、页面内的
            //   "正在切换片源…"提示又写在没有内容的 tvPlayUrl 行上，用户看到的就是
            //   "只有背景"的空页，直到首个可用源加载完成（实测最长约 9s）。
            //   故：无内容时补一个加载指示；内容就绪后由 showSuccess() 自动收起。
            if (!hasRenderedDetail()) {
                showLoading();
            }
            handleNoPlayableDetail();
            return;
        }
        if (!fallback) {
            showLoading();
        } else if (!hasRenderedDetail() && !isLoading()) {
            // 切源模式但有内容时不打扰；无内容时（如首次拉取候选源、上一轮落空清空页面后）
            // 必须给指示，否则用户面对空页无从判断应用是否在工作。
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
            if (!consumeFailoverBudget("noPlayableDetail")) {
                return;
            }
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

    /**
     * 切源进行中的提示弹窗（带超时自关）。
     *
     * <p>原实现直接 {@code Toast.LENGTH_SHORT} 显示「正在切换片源，请稍候…」：
     * 若候选源全部不可用（DNS 失败 / 整片站点不可达），切源状态机在 3 次续切预算
     * 耗尽后停止，但该 Toast 早已消失，用户看不到任何结果；反过来若用户在
     * {@code DETAIL_FALLBACK_MAX_FAILOVER} 轮续切期间连续点按，旧 Toast 还没走完
     * 就被不断顶掉，屏幕上会闪过一串同样的文字，看起来像应用卡死重复响应。</p>
     *
     * <p>改为「先关旧的再显示新的」+ 超时强制关闭：同一次切源会话内只保留一个弹窗，
     * 且其生命周期不超过 {@link #SWITCHING_SOURCE_TOAST_TIMEOUT_MS}。</p>
     */
    private void showSwitchingSourceToast() {
        if (switchingSourceToast != null) {
            switchingSourceToast.cancel();
            switchingSourceToast = null;
        }
        if (llLayout != null) {
            llLayout.removeCallbacks(hideSwitchingSourceToast);
        }
        switchingSourceToast = Toast.makeText(this, "正在切换片源，请稍候…", Toast.LENGTH_LONG);
        switchingSourceToast.show();
        if (llLayout != null) {
            llLayout.postDelayed(hideSwitchingSourceToast, SWITCHING_SOURCE_TOAST_TIMEOUT_MS);
        }
    }

    private void dismissSwitchingSourceToast() {
        if (llLayout != null) {
            llLayout.removeCallbacks(hideSwitchingSourceToast);
        }
        if (switchingSourceToast != null) {
            switchingSourceToast.cancel();
            switchingSourceToast = null;
        }
    }

    /**
     * 切源入口（菜单点击 / 播放器线路耗尽）。
     *
     * 设计：
     *   1. 第一次点切源（缓存为空）→ 发起一次全网搜索，结果同时写入 detailFallbackCache；
     *   2. 之后每次点切源 → 不做任何网络搜索，直接从缓存里按「圈」轮转下一个源；
     *   3. 「一圈」= 把缓存里的源（含当前正在播放的源）全部轮一遍，走完开新圈；
     *   4. 只有当缓存为空（首次搜索一个都没命中）时才重新发起全网搜索。
     *
     * @return true 表示本次点击已被受理（正在切 / 已切），false 表示完全没得切
     */
    private boolean startDetailFallback(boolean manual) {
        SourceBean currentSource = ApiConfig.get().getSource(sourceKey);
        if (isFinishing() || currentSource == null || !currentSource.isChangeable()) {
            return false;
        }
        if (detailFallbackActive) {
            showSwitchingSourceToast();
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
        detailFallbackTitle = vod_name.trim();
        if (TextUtils.isEmpty(detailFallbackTitle)) {
            return false;
        }

        // 换片名 = 换了一套缓存/圈记录，重新从「第一圈」开始。
        //
        // ★ 必须用「归一化片名」比较，不能用原始串：
        // 各资源站返回的 vod_name 常有细微差异（多余空格、全角/半角、别名后缀），
        // 而切源过程中 vod_name 会被替换成新源的写法。若直接比原始串，
        // 切一次源就会被判定为"换片" → 清空 CycleKeys + DeadKeys + 重置为第一圈
        // → 表现为「再点切源又切回同一个站点」。
        String normalizedTitle = normalizeFallbackTitle(detailFallbackTitle);
        if (!TextUtils.equals(detailFallbackCycleTitle, normalizedTitle)) {
            detailFallbackCycleTitle = normalizedTitle;
            detailFallbackCycleKeys.clear();
            detailFallbackNewCycle = true;
            // ★ 换片必须清 DeadKeys / SoftTriedKeys：
            // 「某站没有 A 片」不代表「没有 B 片」，跨片沿用拉黑列表会让新片的
            // 可切站点凭空变少（表现为「这部片只有两三个源能切」）。
            detailFallbackDeadKeys.clear();
            detailFallbackSoftTriedKeys.clear();
            detailFallbackCycleCount = 0;
            detailFallbackCycleStart = 0;
            detailFallbackCycleStartOfNext = 0;
            detailFallbackCycleStartKey = "";
            detailFallbackTimedOutKey = null;
            detailFallbackTimedOutSource = null;
        }

        detailFallbackActive = true;
        // 手动发起切源 → 重置失败续切预算。自动续切不清零，那正是要限制的连跳。
        detailFallbackFailoverCount = 0;
        // ★ 每次切源前，把搜索会话候选池的「最新增量」并入本地缓存。
        //   搜索是边跑边补的（单一真相源在 SearchSession），而本地 detailFallbackCache
        //   是进页面那一刻的副本；不重新拉取的话，切源能在几个站之间轮转，
        //   但「可切站点总数」永远停在进页面时的数量 —— 这正是要修的现象。
        refreshFallbackCandidatesFromSession();
        boolean accepted = loadNextDetailFallbackFromCache();
        // 只有「这一圈确实没得切、且也没转成全网搜索」时才复位状态；
        // 一旦进入全网搜索（detailFallbackSearching/Collecting 为真）或已发起 loadDetail
        // （detailFallbackLoadingCandidate 为真），就交给异步回调收尾，绝不能在这里复位，
        // 否则会把刚发起的搜索/加载直接掐掉，表现为「点了没反应」。
        if (!accepted && !detailFallbackLoadingCandidate
                && !detailFallbackSearching && !detailFallbackSearchCollecting) {
            resetDetailFallback();
        }
        return detailFallbackActive;
    }

    /**
     * 缓存轮转型切源：不发全网搜索，只从 detailFallbackCache 里按圈取下一个候选。
     * 缓存为空且从未搜索过 → 退化成一次全网搜索（startDetailFallback()）。
     *
     * @return true 表示已经发起 loadDetail（或已转入全网搜索流程）
     */
    private boolean loadNextDetailFallbackFromCache() {
        if (!detailFallbackCacheEntryUsable()) {
            // 缓存为空 → 只有这种情况才真正发起一次全网搜索
            if (!detailFallbackSearchCollecting) {
                startDetailFallback();
            }
            return detailFallbackActive;
        }

        // 「开新圈」只在两处发生：换片名（startDetailFallback(boolean) 里置 NewCycle），
        // 或 pollNextCycledSource 发现本圈已转完（它自己会清）。
        // 【绝不能】在这里无条件 clear —— 那会让每次点击都从池子第一个重新开始，
        // 表现为「点多少次都切回同一个站点」。这正是一直切回同一站点的根因。
        if (detailFallbackNewCycle) {
            detailFallbackCycleKeys.clear();
            detailFallbackCycleStartKey = "";
            detailFallbackNewCycle = false;
        }

        // 缓存轮转：直接从缓存里按圈取下一个，零网络开销
        String nextSource = pollNextCycledSource();
        LOG.sw("[FB] pick picked=" + nextSource + " poolSize=" + detailFallbackUsableCandidateCount()
                + " cycleKeys=" + detailFallbackCycleKeys.size() + " newCycle=" + detailFallbackNewCycle
                + " dead=" + detailFallbackDeadKeys.size() + " soft=" + detailFallbackSoftTriedKeys.size());
        if (TextUtils.isEmpty(nextSource)) {
            // 一圈内确实没有可切的源了（所有源都试过且都失败），提示并结束
            if (detailFallbackKeepCurrentDetail && mVideo != null && vodInfo != null) {
                Toast.makeText(this, "没有更多可切换的片源", Toast.LENGTH_SHORT).show();
            }
            return false;
        }

        detailFallbackLoadingCandidate = true;
        detailFallbackDetailTimedOut = false;
        // ★ 沿用选源时挑中的具体候选 id（同一站可能有多条，第一条未必是正片）
        String nextId = detailFallbackPickedVideoId;
        Movie.Video video = detailFallbackCacheVideo(detailFallbackTitle, nextSource, nextId);
        if (video != null) {
            vod_name = video.name == null ? "" : video.name;
            vod_picture = video.pic == null ? "" : video.pic;
        }
        // ★ 先刷页面，再发请求。站点的信息在点下切源这一刻就已从本地缓存选定，
        // 没有任何理由等详情报文回来才显示。不先刷的话，用户点完只能对着旧源发呆
        // 7~8 秒（详情 RTT + 解析 + 起播），以为没生效而反复点击。
        showDetailFallbackIndicators(nextSource, video);
        markDetailFallbackInflight(nextSource, nextId);
        loadDetail(nextId, nextSource, true);
        return true;
    }

    /**
     * 切源过程中刷新「站点指示」：来源名 / 海报 / 解析标记 / 播放地址占位。
     *
     * <p>只碰「仅凭候选信息即可确定」的四项，<b>刻意不动</b>线路列表
     * （{@code seriesFlagAdapter}）、剧集列表（{@code refreshList()}）、起播
     * （{@code jumpToPlay()}）—— 那三样强依赖详情报文里的 {@code seriesMap}，
     * 详情没回来时是空数据，提前刷只会把页面清空。
     *
     * <p><b>不会造成「来源与线路/剧集不一致」的误导</b>：切源期间剧集区会被
     * {@code mEmptyPlayList} 占位或保持隐藏，用户点不到集数与线路；等详情真正
     * 成功返回时，{@code refreshList()} / {@code seriesFlagAdapter.setNewData()}
     * 会把线路与剧集一并刷成新源的，三者重新一致。
     *
     * @param sourceKey 命中的站点 key
     * @param video     候选（可为 null，此时只更新站点名与地址占位）
     */
    private void showDetailFallbackIndicators(String sourceKey, Movie.Video video) {
        if (TextUtils.isEmpty(sourceKey) || isFinishing() || isDestroyed()) {
            return;
        }
        try {
            SourceBean indicatorSource = ApiConfig.get().getSource(sourceKey);
            String indicatorName = indicatorSource == null ? sourceKey : indicatorSource.getName();
            setTextShow(tvSite, "来源：", indicatorName);
            setTextShow(tvType, "类型：", "[" + indicatorName + "] 解析");
            // 地址先置「获取中」：旧源地址留在那里会让人误以为没切
            setTextShow(tvPlayUrl, "播放地址：", "获取中…");
            if (video != null && !TextUtils.isEmpty(video.pic)) {
                com.github.tvbox.osc.util.ImgUtil.load(
                        DefaultConfig.checkReplaceProxy(video.pic), ivThumb,
                        AutoSizeUtils.mm2px(mContext, 10), AutoSizeUtils.mm2px(mContext, 300),
                        AutoSizeUtils.mm2px(mContext, 400), video.name);
            }
            LOG.i("[FB] indicators source=" + indicatorName
                    + " hasPic=" + (video != null && !TextUtils.isEmpty(video.pic)));
        } catch (Throwable th) {
            LOG.e("showDetailFallbackIndicators fail: " + th);
        }
    }

    /** 缓存里是否还有该片名的可用候选（不含是否已轮转的判断）。 */
    private boolean detailFallbackCacheEntryUsable() {
        List<Movie.Video> cachedCandidates = detailFallbackCache.get(detailFallbackTitle);
        return cachedCandidates != null && !cachedCandidates.isEmpty();
    }

    /**
     * 从缓存里取出「本圈还没轮转到、且全局没试过」的下一个源。
     * 取不到时先尝试开新圈（清空 CycleKeys），仍然取不到才返回 ""。
     */
    private String pollNextCycledSource() {
        List<Movie.Video> cachedCandidates = detailFallbackCache.get(detailFallbackTitle);
        if (cachedCandidates == null || cachedCandidates.isEmpty()) {
            return "";
        }
        // ★ 把缓存整理成「每个站点一条」的有序候选表：按站点质量分降序。
        //
        // 之前直接遍历缓存原序（= 各源返回先后），于是「下一个切谁」取决于网络快慢，
        // 与站点质量无关；质量排序只在整表层面做了一次，进到轮转里就失效了。
        // 质量分相同（都是冷启动的 NEUTRAL）时按显示名排，保证同一部片每次进入
        // 切源顺序稳定可复现，不会「这次先切 A、下次先切 B」。
        List<String> orderedSources = buildFallbackCycleOrder(cachedCandidates);
        if (orderedSources.isEmpty()) {
            detailFallbackPickedVideoId = "";
            return "";
        }
        // ★ 第一轮只挑「别的站」：本圈内还有没轮过的其它站点时，跳过当前正在播放的源。
        //   点切源却切回自己 = 页面原地重载一遍，用户只会以为没生效。
        String picked = scanOrderedSources(orderedSources, false);
        if (TextUtils.isEmpty(picked) && orderedSources.size() > 1) {
            // 其它站本圈都轮过了 → 允许回到当前源（一圈的终点本来就是回到起点）
            picked = scanOrderedSources(orderedSources, true);
        }
        if (!TextUtils.isEmpty(picked)) {
            return picked;
        }
        // 本圈内都被试过了。整池只有一个站点时开新圈没有意义（只会原地锁死），
        // 直接返回空，让上层提示「没有更多片源」。
        if (orderedSources.size() <= 1) {
            detailFallbackPickedVideoId = "";
            return "";
        }
        // 否则开新圈（允许重复轮转到同一批源，但按顺序，不随机）。
        //
        // ★ 开新圈必须同时清 SoftTriedKeys —— 它的语义就是「本圈已试过的站点」，
        // 不清的话新圈里每一个站点都还被判为「试过」，等于开新圈立刻死空。
        detailFallbackCycleKeys.clear();
        detailFallbackSoftTriedKeys.clear();
        detailFallbackCycleCount++;
        detailFallbackCycleStart = detailFallbackCycleStartOfNext;
        LOG.i("[FB] newCycle #" + detailFallbackCycleCount
                + " pool=" + orderedSources.size()
                + " start=" + detailFallbackCycleStart
                + " startKey=" + detailFallbackCycleStartKey);
        picked = scanOrderedSources(orderedSources, false);
        if (TextUtils.isEmpty(picked)) {
            picked = scanOrderedSources(orderedSources, true);
        }
        if (TextUtils.isEmpty(picked)) {
            detailFallbackPickedVideoId = "";
        }
        return picked;
    }

    /**
     * 本圈轮转的实际扫描：从起点绕一圈找下一个可切站点。
     *
     * @param allowCurrent 是否允许选中「当前正在播放的源」。
     *                     传 false 时跳过它，保证用户点切源一定能看到别的站。
     */
    private String scanOrderedSources(List<String> orderedSources, boolean allowCurrent) {
        int total = orderedSources.size();
        int startIdx = resolveCycleStartIndex(orderedSources);
        for (int step = 0; step < total; step++) {
            int index = (startIdx + step) % total;
            String cycleKey = orderedSources.get(index);
            // 圈内已轮转过 → 跳过（本圈不重复站点）
            if (detailFallbackCycleKeys.contains(cycleKey)) {
                continue;
            }
            // 软失败：本圈已试过的站点（开新圈时该集合会被清空）。
            // ★ 粒度必须与写入侧一致（都是站点 key，见 finishDetailFallbackDetailOnTimeout）。
            if (detailFallbackSoftTriedKeys.contains(cycleKey)) {
                continue;
            }
            if (!allowCurrent && isCurrentPlayingSource(cycleKey)) {
                continue;
            }
            // 整站被拉黑（历史遗留的粗粒度记录，兼容）
            if (detailFallbackDeadKeys.contains(cycleKey)) {
                continue;
            }
            // ★ 取该站第一条「未被拉黑」的候选：某站第 1 条是 MV/预告、后面才是正片时，
            //   只认第一条会让该站的正片永远轮不到（表现为「明明有片却切不过去」）。
            String videoId = detailFallbackFirstAliveId(cycleKey);
            if (TextUtils.isEmpty(videoId)) {
                continue;
            }
            detailFallbackCycleKeys.add(cycleKey);
            detailFallbackSoftTriedKeys.add(cycleKey);
            detailFallbackPickedVideoId = videoId;
            // 记下本圈选到哪了，供开新圈时接续。用 key 而不是下标：池子会按质量分重排，
            // 下标会漂移，key 才能稳定指向「上一圈末尾的下一个」。
            detailFallbackCycleStartOfNext = (index + 1) % total;
            detailFallbackCycleStartKey = orderedSources.get(detailFallbackCycleStartOfNext);
            LOG.sw("[FB] pollPick source=" + cycleKey
                    + " name=" + detailFallbackDisplayName(cycleKey)
                    + " id=" + videoId
                    + " idx=" + index + "/" + total
                    + " allowCurrent=" + allowCurrent
                    + " cycleKeys=" + detailFallbackCycleKeys.size());
            return cycleKey;
        }
        return "";
    }

    /** 本圈扫描起点：优先用记录的站点 key 定位，key 已不在池子里才退回下标。 */
    private int resolveCycleStartIndex(List<String> orderedSources) {
        if (!TextUtils.isEmpty(detailFallbackCycleStartKey)) {
            int idx = orderedSources.indexOf(detailFallbackCycleStartKey);
            if (idx >= 0) {
                return idx;
            }
        }
        int fallback = detailFallbackCycleStart;
        if (fallback < 0 || fallback >= orderedSources.size()) {
            fallback = 0;
        }
        return fallback;
    }

    /**
     * 取某站点缓存里第一条「未被拉黑」的候选影片 id。
     *
     * <p>黑名单粒度是「源|影片id」（写于详情成功但无剧集处）。若这里只取该站第一条，
     * 那么第一条被拉黑后，同站的其它正片候选就再也轮不到。故按 id 粒度在站内跳过。</p>
     */
    private String detailFallbackFirstAliveId(String sourceKey) {
        List<Movie.Video> cachedCandidates = detailFallbackCache.get(detailFallbackTitle);
        if (cachedCandidates == null || TextUtils.isEmpty(sourceKey)) {
            return "";
        }
        for (Movie.Video video : cachedCandidates) {
            if (video == null || !TextUtils.equals(video.sourceKey, sourceKey)
                    || TextUtils.isEmpty(video.id)) {
                continue;
            }
            if (detailFallbackDeadKeys.contains(getDetailFallbackKey(sourceKey, video.id))) {
                continue;
            }
            return video.id;
        }
        return "";
    }

    /**
     * 把缓存整理成「每站一条、按质量降序」的轮转顺序。
     *
     * <p>做三件事，缺一不可：
     * <ol>
     *   <li><b>按站点折叠</b>：同站多条同名结果只留一条（取源时本来就只认第一条）；</li>
     *   <li><b>按显示名精确去重</b>：显示名一字不差的源只保留一个。这里是
     *       {@code String.equals} 的<b>完全相等</b>判定，绝不做包含/前缀/相似度匹配——
     *       {@code 非凡资源} / {@code 非凡采集} / {@code 🍯HG·🐝非凡资源} 这类
     *       「局部名字重叠」的站点必须全部保留；</li>
     *   <li><b>按质量分降序</b>：质量分取自 {@link SourceQualityStore}，让切源与
     *       搜索页共用同一套站点优劣判断。</li>
     * </ol>
     */
    private List<String> buildFallbackCycleOrder(List<Movie.Video> cachedCandidates) {
        Map<String, Movie.Video> byKey = new LinkedHashMap<>();
        // 显示名去重只在本次构建内有效，必须放局部：
        // 早先用的是实例字段，一旦两条路径重入（探路回调与轮转并发）就会互相污染，
        // 表现为「偶尔莫名其妙少一个站」。
        Set<String> dedupedNames = new HashSet<>();
        for (Movie.Video video : cachedCandidates) {
            if (video == null || TextUtils.isEmpty(video.id) || TextUtils.isEmpty(video.sourceKey)) {
                continue;
            }
            SourceBean source = ApiConfig.get().getSource(video.sourceKey);
            if (source == null || !source.isSearchable()) {
                continue;
            }
            if (byKey.containsKey(video.sourceKey)) {
                continue;
            }
            // 显示名完全相同的站点只保留先出现的那一个（通常是质量更好的，见下方排序）
            String displayName = source.getName();
            if (!TextUtils.isEmpty(displayName)
                    && dedupedNames.contains(displayName)) {
                continue;
            }
            if (!TextUtils.isEmpty(displayName)) {
                dedupedNames.add(displayName);
            }
            byKey.put(video.sourceKey, video);
        }
        if (byKey.isEmpty()) {
            return new ArrayList<>();
        }
        final List<String> keys = new ArrayList<>(byKey.keySet());
        // 质量分降序；同分按显示名升序，保证顺序稳定可复现
        try {
            final SourceQualityStore.Snapshot snapshot = SourceQualityStore.snapshot(keys);
            if (snapshot.isEmpty()) {
                // 冷启动：所有源都是中性分，排序不会改变任何顺序，省下这轮 O(n log n)
                return keys;
            }
            // ★ P2-2：把显示名一次性预取成 Map，避免比较器内 O(n log n) 次
            //   ApiConfig.get().getSource() 查表（每次比较都要查两遍）。
            final Map<String, String> nameByKey = new LinkedHashMap<>(keys.size() * 2);
            for (String k : keys) {
                nameByKey.put(k, detailFallbackDisplayName(k));
            }
            java.util.Collections.sort(keys, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    int byQuality = Double.compare(snapshot.get(b), snapshot.get(a));
                    if (byQuality != 0) {
                        return byQuality;
                    }
                    String na = nameByKey.get(a);
                    String nb = nameByKey.get(b);
                    if (na == null) {
                        na = "";
                    }
                    if (nb == null) {
                        nb = "";
                    }
                    return na.compareTo(nb);
                }
            });
        } catch (Throwable th) {
            LOG.e("buildFallbackCycleOrder sort fail: " + th);
        }
        return keys;
    }

    /** 取站点显示名，取不到就退回 key，保证排序键永不为 null。 */
    private String detailFallbackDisplayName(String sourceKey) {
        try {
            SourceBean source = ApiConfig.get().getSource(sourceKey);
            if (source != null && !TextUtils.isEmpty(source.getName())) {
                return source.getName();
            }
        } catch (Throwable ignored) {
            // 取不到不是错误，退回 key 即可
        }
        return sourceKey == null ? "" : sourceKey;
    }

    private Movie.Video detailFallbackCacheVideo(String title, String sourceKey) {
        List<Movie.Video> cachedCandidates = detailFallbackCache.get(title);
        if (cachedCandidates == null) {
            return null;
        }
        for (Movie.Video video : cachedCandidates) {
            if (video != null && TextUtils.equals(video.sourceKey, sourceKey) && !TextUtils.isEmpty(video.id)) {
                return video;
            }
        }
        return null;
    }

    /**
     * 按「站点 + 具体候选 id」精确取缓存条目。
     *
     * <p>选源时挑中的是具体一条候选（可能是该站的第 2、3 条），显示海报/站点指示
     * 必须沿用同一条，不能各自再取「该站第一条」，否则海报与实际播放的条目对不上。</p>
     */
    private Movie.Video detailFallbackCacheVideo(String title, String sourceKey, String videoId) {
        if (!TextUtils.isEmpty(videoId)) {
            List<Movie.Video> cachedCandidates = detailFallbackCache.get(title);
            if (cachedCandidates != null) {
                for (Movie.Video video : cachedCandidates) {
                    if (video != null && TextUtils.equals(video.sourceKey, sourceKey)
                            && TextUtils.equals(video.id, videoId)) {
                        return video;
                    }
                }
            }
        }
        return detailFallbackCacheVideo(title, sourceKey);
    }

    /**
     * 全网搜索型切源：这次搜索覆盖「全部可搜可换的源」（含当前正在播放的源），
     * 结果会通过 onDetailFallbackSearchResult 累积进 detailFallbackCache，
     * 供后续点击切源时零网络地轮转。
     */
    private void startDetailFallback() {
        // 片名必须先解析出来——探路/恢复缓存都依赖它
        detailFallbackTitle = vod_name == null ? "" : vod_name.trim();
        if (TextUtils.isEmpty(detailFallbackTitle)) {
            showDetailEmpty();
            return;
        }
        // 方案D：二次进同一部片，先从 Hawk 恢复上次的候选池（可能一次网络都不发）
        restoreDetailFallbackCache(detailFallbackTitle);

        detailFallbackSourceOrder.clear();
        for (SourceBean bean : ApiConfig.get().getSourceBeanList()) {
            // 与搜索页（FastSearchActivity/SearchActivity）保持一致的筛选条件：
            // 只要求 isSearchable。此前多加了 isChangeable，导致「可搜但不可换」的源
            // 被排除，切源池比搜索结果的源少。
            // 一圈 = 包括当前源在内全部轮转：不排除当前源，也不按「已用」过滤。
            if (bean.isSearchable()) {
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
        // ★ 按站点质量排序：让「响应快、起播稳」的站点排在前面，切源与搜索用同一套分数
        // （数据来自 SourceQualityStore，与搜索页/播放页共用）。
        //
        // 排序必须稳定：同分（含全部无历史的中性分 0.5）保持 ApiConfig 原序不变，
        // 否则每次排序结果都可能不同，「一圈」的轮转顺序就失去可预测性。
        sortDetailFallbackSourceOrderByQuality();
        // ★ 探路要单独朝「最稳的站」打一枪，就必须把它从批次队列里摘掉：
        //   否则同一个站点会被搜两次（探路一次 + 批处理一次），白扔一个请求不说，
        //   还让最稳的那个站重复占用一个并发名额。探路没命中时再补回队尾（见
        //   requeueProbeSource），不会因此漏掉该站。
        String probeKey = pickBestProbeSource();
        if (!TextUtils.isEmpty(probeKey)) {
            detailFallbackSourceOrder.remove(probeKey);
        }

        detailFallbackActive = true;
        detailFallbackSearching = true;
        detailFallbackSearchCollecting = true;
        detailFallbackSearchTimedOut = false;
        detailFallbackDetailTimedOut = false;
        detailFallbackSearchTimeoutScheduled = false;
        detailFallbackLoadingCandidate = false;
        // 清掉上一轮可能残留的「提前消费」预约，避免旧预约抢在本轮前面放片
        detailFallbackEarlyConsumeScheduled = false;
        if (llLayout != null) {
            llLayout.removeCallbacks(detailFallbackEarlyConsume);
        }
        detailFallbackBatchIndex = 0;
        detailFallbackNextSourceIndex = 0;
        detailFallbackToken = "detail_fallback_" + (++detailFallbackRequestIndex);
        // 批令牌仍要每轮轮转：滑动窗口下不再分"批"，但它仍是「这条结果/这次超时
        // 属于本轮还是上轮」的唯一凭据（见 onDetailFallbackSearchResult 的守卫）。
        // 必须在首次派发之前定好 —— 线程池在 keepCache 复位后会存活到下一轮，
        // 令牌若跟着池一起复用就会漏更新，上一轮的迟到结果会被当成本轮结果收下。
        detailFallbackBatchToken = detailFallbackToken + "_batch_" + (++detailFallbackBatchIndex);
        detailFallbackBatchStartMs = System.currentTimeMillis();
        LOG.i("[FB] startSearch title=" + detailFallbackTitle + " sources=" + detailFallbackSourceOrder.size() + " token=" + detailFallbackToken);
        // 新的一轮搜索前先把上一轮残留的派发线程收掉：
        //  1) 上一轮的结果会因 token 轮转被 onDetailFallbackSearchResult 的守卫全部丢弃，
        //     留着跑没有意义；
        //  2) 线程池现在是长生命周期复用的（原来每批都新建池），若不收掉，上一轮那些
        //     阻塞在 type==3 爬虫 searchContent 上的任务会占住线程名额，让本轮新派发排队。
        stopDetailFallbackSearchExecutor();
        scheduleDetailFallbackSearch();
        // 方案A：批处理已铺开，再朝历史命中率最高的站点单独打一枪。
        // 必须放在 detailFallbackActive/Token 就绪之后，否则会被守卫直接拦掉。
        startDetailFallbackProbe(probeKey);
        // ★ 切源不盖全屏 Loading。
        // showLoading() 会用 LoadSir 的全屏遮罩盖住整个页面（含正在播放的播放器），
        // 在候选频繁失败时表现为「一直卡在 Loading」——这正是「点了像卡死」的根源。
        // 改为页面内轻量提示：内容与播放器保持可见，用户能立刻看到反馈。
        showDetailFallbackTip();
    }

    /**
     * 切源期间的状态提示：写在页面内的「播放地址」行上，不弹 Toast、不盖 Loading。
     *
     * <p>为什么不用 Toast：切源是连续动作（用户可能连点），Toast 会排队堆叠，
     * 后一条盖住前一条，体感像是"卡住了"。写进页面则始终只有一条，且位置固定。
     */
    private void showDetailFallbackTip() {
        try {
            if (!isFinishing() && !isDestroyed()) {
                setTextShow(tvPlayUrl, "播放地址：", "正在切换片源…");
            }
        } catch (Throwable th) {
            LOG.e("showDetailFallbackTip fail: " + th);
        }
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
        // ★ 跨域安全：期数式与日期式的 extractEpisodeNumber 量纲不同，
        //   直接比数值必然"不相同"，会把本来正确的跨域同集判定为错位而拒绝采纳下标。
        //   positionTrustedAcrossDomain 跨域一律放行，同域才严格比序号。
        if (!EpisodeNameMatcher.positionTrustedAcrossDomain(actualName, targetName)) {
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
        // ★ 同时捕获旧源剧集名列表：切源流程加载新源详情后 vodInfo 会被替换，
        //   届时 getPlayingSeriesList() 返回的是新源列表，离线秩对齐必须用旧源口径。
        detailFallbackSourceNames = seriesNames(seriesList);
    }

    /**
     * 取"旧源"口径的剧集名列表，供离线秩对齐（{@link #findMatchingEpisodeIndex} 第2-c层）使用。
     *
     * <p>切源（detail fallback）流程里 vodInfo 已被替换成新源，此时用
     * {@link #captureDetailFallbackEpisode()} 捕获的旧源列表；非切源场景
     * （线路切换等）vodInfo 仍是旧源，直接用当前播放列表。</p>
     */
    private List<String> getRankSourceList(String currentName) {
        if (!TextUtils.isEmpty(currentName) && detailFallbackSourceNames != null
                && detailFallbackSourceNames.contains(currentName)) {
            return detailFallbackSourceNames;
        }
        return seriesNames(getPlayingSeriesList());
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
            // 保守落地：所有线路都没匹配上该集时，宁可保持原下标（仍在合法范围内），
            // 也不做"就近猜测"把可能错位的下标写成正式结果。
            // 注意这里传入的是"已钳位过的原下标"，属于合法值，safeLand 会正常写入，
            // 但绝不接受 findMatchingEpisodeIndex 返回的 -1。
            // ★ 正片保护：原下标若恰好落在特辑/花絮等非正片条目上（实测
            //   「第5期上」兜底到「20260508泳池特辑」），就近回退到正片条目。
            vodInfo.playIndex = EpisodeNameMatcher.sanitizeMainFeatureFallback(
                    fallback == null ? null : fallback.name,
                    nearestEpisodeIndex(fallback, fallbackIndex, preferredList),
                    seriesNames(preferredList));
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
        if (data == null) {
            return;
        }
        // ===== 方案A：探路结果单独处理（与批次互不干扰）=====
        if (detailFallbackProbePending && !TextUtils.isEmpty(detailFallbackProbeToken)
                && detailFallbackProbeToken.equals(data.searchToken)) {
            handleDetailFallbackProbeResult(data);
            return;
        }
        if (!detailFallbackActive || !detailFallbackSearchCollecting || !detailFallbackBatchToken.equals(data.searchToken)) {
            return;
        }
        // 方案E：每个源返回，记录命中/失败统计（耗时按该源自己的发出时刻算，
        // 滑动窗口下"批次开始时刻"已无意义）
        Long dispatchedAt = detailFallbackSourceStartMs.remove(data.sourceKey);
        long searchCostMs = dispatchedAt == null
                ? (detailFallbackBatchStartMs > 0 ? System.currentTimeMillis() - detailFallbackBatchStartMs : 0L)
                : System.currentTimeMillis() - dispatchedAt;
        recordFallbackStat(data.sourceKey,
                data.movie != null && data.movie.videoList != null && !data.movie.videoList.isEmpty(),
                searchCostMs);
        detailFallbackPendingSources.remove(data.sourceKey);
        boolean cachedAny = false;
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video == null || TextUtils.isEmpty(video.id)
                        || !isSameFallbackTitle(detailFallbackTitle, video.name)) {
                    continue;
                }
                // 全网搜索的职责是「建立这一圈完整的缓存」，所以这里不再按「已用源」过滤，
                // 只要片名精确命中就全部纳入（含当前正在播放的源，使一圈能真正闭合）。
                cacheDetailFallbackCandidate(video);
                cachedAny = true;
            }
        }
        // ★ 提前消费：一旦本批结果里出现了可用候选，就安排一次「不再死等搜索收尾」的放片。
        // 见 DETAIL_FALLBACK_FIRST_CONSUME_DELAY_MS 的说明 —— 这是空白期的主因。
        if (cachedAny && detailFallbackSearchCollecting && !detailFallbackEarlyConsumeScheduled) {
            detailFallbackEarlyConsumeScheduled = true;
            llLayout.postDelayed(detailFallbackEarlyConsume, DETAIL_FALLBACK_FIRST_CONSUME_DELAY_MS);
        }
        if (!detailFallbackLoadingCandidate) {
            if (detailFallbackSearching) {
                scheduleDetailFallbackSearch();
            } else if (detailFallbackPendingSources.isEmpty()) {
                finishDetailFallbackSearchCollection();
            }
        }
    }

    /**
     * 滑动窗口补发全网搜索。
     *
     * <p>原实现是"整批 20 个一起发，等这批全部回来或超时（3.5s）才发下一批"，
     * 批次要等<b>最慢</b>的那个源，快源白等（详见 {@link #DETAIL_FALLBACK_MAX_SEARCH}）。
     * 现在改为：在途数 &lt; 并发上限就立刻补发下一个源；一个源返回（成功/失败/超时）后
     * 由 {@link #onDetailFallbackSearchResult} / {@link #scheduleDetailFallbackSourceTimeout}
     * 再次调进来补位。</p>
     *
     * <p>收尾条件：<b>所有源都已发出</b>且<b>没有在途请求</b>。二者缺一不可，
     * 否则会把还没试的源跳过。</p>
     */
    private void scheduleDetailFallbackSearch() {
        if (!detailFallbackActive || !detailFallbackSearching) {
            return;
        }
        if (detailFallbackLoadingCandidate) {
            return;
        }
        // 全部源都已发出 + 无在途 → 搜索阶段结束
        if (detailFallbackNextSourceIndex >= detailFallbackSourceOrder.size()
                && detailFallbackPendingSources.isEmpty()) {
            detailFallbackSearching = false;
            llLayout.removeCallbacks(detailFallbackTimeout);
            stopDetailFallbackSearchExecutor();
            loadNextDetailFallbackSource();
            return;
        }
        ensureDetailFallbackSearchExecutor();

        List<String> dispatched = new ArrayList<>();
        while (detailFallbackPendingSources.size() < DETAIL_FALLBACK_MAX_SEARCH
                && detailFallbackNextSourceIndex < detailFallbackSourceOrder.size()) {
            final String searchKey = detailFallbackSourceOrder.get(detailFallbackNextSourceIndex++);
            final String searchTitle = detailFallbackTitle;
            final String searchToken = detailFallbackBatchToken;
            detailFallbackPendingSources.add(searchKey);
            // 逐源记录发出时刻，统计耗时不再用"批次开始时刻"（滑动窗口下批次无意义）
            detailFallbackSourceStartMs.put(searchKey, System.currentTimeMillis());
            // 方案B：每个源各挂一个超时，卡死的站点不再拖累其它源
            scheduleDetailFallbackSourceTimeout(searchKey, searchToken);
            detailFallbackSearchExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    sourceViewModel.getDetailFallbackSearch(searchKey, searchTitle, searchToken);
                }
            });
            dispatched.add(searchKey);
        }
        if (!dispatched.isEmpty()) {
            LOG.i("[FB] dispatch token=" + detailFallbackBatchToken + " n=" + dispatched.size()
                    + " inflight=" + detailFallbackPendingSources.size() + " keys=" + dispatched);
        }
        if (!detailFallbackSearchTimeoutScheduled) {
            detailFallbackSearchTimeoutScheduled = true;
            llLayout.postDelayed(detailFallbackTimeout, DETAIL_FALLBACK_SEARCH_TIMEOUT_MS);
        }
    }

    /**
     * 懒建搜索线程池：整轮搜索只建一次，滑动窗口全程复用。
     *
     * <p>原实现每批都 {@code shutdownNow()} 再新建，池子反复重建；现在只在需要时建一次。
     * 注意<b>不在这里轮转</b> {@link #detailFallbackBatchToken} —— 该池在
     * {@link #resetDetailFallbackKeepCache()} 之后会存活到下一轮切源，
     * 若令牌跟着池走就永远不更新，上一轮的迟到结果会被误认成本轮结果。
     * 令牌由 {@link #startDetailFallback()} 在派发前统一轮转。</p>
     */
    private void ensureDetailFallbackSearchExecutor() {
        if (detailFallbackSearchExecutor == null || detailFallbackSearchExecutor.isShutdown()) {
            detailFallbackSearchExecutor = Executors.newFixedThreadPool(DETAIL_FALLBACK_MAX_SEARCH);
        }
    }

    private void finishDetailFallbackSearchOnTimeout() {
        if (!detailFallbackActive || !detailFallbackSearching) {
            return;
        }
        detailFallbackSearchTimeoutScheduled = false;
        // 超时后不再等剩下的批次；但已收集到的结果已经进了 detailFallbackCache，下一圈仍可用。
        detailFallbackSearching = false;
        detailFallbackSearchTimedOut = true;
        detailFallbackNextSourceIndex = detailFallbackSourceOrder.size();
        if (!detailFallbackLoadingCandidate) {
            loadNextDetailFallbackSource();
        }
    }

    /**
     * 统一取源入口：无论从「首次全网搜索结束」还是「后续缓存轮转」进来，
     * 都只从 detailFallbackCache 里按圈取下一个候选，保证两条路径行为一致。
     */
    /**
     * 「失败续切」预算：候选不可用（详情为空 / 无剧集 / 超时）而继续跳下一个站之前调用。
     *
     * <p>不加限制时，网络差的场景下这三个条件会连续成立，一次手动切源就能连跳十几个站，
     * 页面不停变化又无法中断，用户只会认为应用失控。达到上限即收尾并保留当前画面。</p>
     *
     * @param reason 触发续切的原因，仅用于诊断日志
     * @return true 表示允许继续续切；false 表示已达上限、调用方应直接返回
     */
    private boolean consumeFailoverBudget(String reason) {
        if (detailFallbackFailoverCount >= DETAIL_FALLBACK_MAX_FAILOVER) {
            LOG.sw("[FB] failover LIMIT reached reason=" + reason
                    + " count=" + detailFallbackFailoverCount
                    + " dead=" + detailFallbackDeadKeys.size()
                    + " soft=" + detailFallbackSoftTriedKeys.size());
            Toast.makeText(this, "多个片源暂时不可用，请稍后重试", Toast.LENGTH_SHORT).show();
            finishDetailFallbackWithoutResult();
            return false;
        }
        detailFallbackFailoverCount++;
        LOG.sw("[FB] failover #" + detailFallbackFailoverCount + " reason=" + reason
                + " from=" + sourceKey
                + " dead=" + detailFallbackDeadKeys.size()
                + " soft=" + detailFallbackSoftTriedKeys.size());
        return true;
    }

    private void loadNextDetailFallbackSource() {
        if (!detailFallbackLoadingCandidate && detailFallbackCacheEntryUsable()) {
            String nextSource = pollNextCycledSource();
            if (!TextUtils.isEmpty(nextSource)) {
                String videoId = detailFallbackPickedVideoId;
                if (TextUtils.isEmpty(videoId)) {
                    finishDetailFallbackWithoutResult();
                    return;
                }
                Movie.Video video = detailFallbackCacheVideo(detailFallbackTitle, nextSource, videoId);
                detailFallbackLoadingCandidate = true;
                detailFallbackDetailTimedOut = false;
                if (video != null) {
                    vod_name = video.name == null ? "" : video.name;
                    vod_picture = video.pic == null ? "" : video.pic;
                }
                // 同 loadNextDetailFallbackFromCache：站点信息已定，先行刷新页面
                showDetailFallbackIndicators(nextSource, video);
                markDetailFallbackInflight(nextSource, videoId);
                LOG.sw("[FB] loadDetail -> " + nextSource + " id=" + videoId
                        + " failover=" + detailFallbackFailoverCount);
                loadDetail(videoId, nextSource, true);
                return;
            }
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
        LOG.i("[FB] searchCollectionDone poolSize=" + detailFallbackUsableCandidateCount() + " loading=" + detailFallbackLoadingCandidate);
        if (detailFallbackLoadingCandidate) {
            return;
        }
        // 收集阶段结束：SourceOrder 里可能还留着「缓存轮转」剩下的候选，
        // 先把它消费掉；确实一个都没有才收尾。
        loadNextDetailFallbackSource();
    }

    private void showDetailFallbackEmptyIfNeeded() {
        boolean keepCurrentDetail = detailFallbackKeepCurrentDetail;
        resetDetailFallback();
        if (!keepCurrentDetail) {
            showDetailEmpty();
        } else {
            showSuccess();
        }
    }

    /**
     * 本轮转没有任何可切的源：保留当前详情页画面（而不是把页面清空），并复位状态。
     */
    private void finishDetailFallbackWithoutResult() {
        showDetailFallbackEmptyIfNeeded();
    }

    private void finishDetailFallbackDetailOnTimeout() {
        if (!detailFallbackActive || !detailFallbackLoadingCandidate) {
            return;
        }
        detailFallbackLoadingCandidate = false;
        detailFallbackDetailTimedOut = true;
        // 方案C：详情超时只是网络抖动，记为「软失败」——本圈跳过，下圈可重试，
        // 绝不写进 deadKeys 永久拉黑（这是「越切越少」的根因之一）。
        // ★ 记「源|影片id」精确键，与轮转裁剪后的粒度一致；
        // 记 sourceKey 会把整站连坐，害得同站其它正片再也轮不到。
        // ★ 软失败的读取侧按「站点 key」判断（见 scanOrderedSources），
        //   这里必须写同一个粒度。写「源|影片id」会让记录永远命中不了，
        //   超时站点在本圈仍可能被反复选中（表现为同一个站来回切）。
        String timedOutSource = detailFallbackTimedOutSource;
        if (TextUtils.isEmpty(timedOutSource)) {
            timedOutSource = sourceKey;
        }
        detailFallbackSoftTriedKeys.add(timedOutSource);
        detailFallbackTimedOutKey = null;
        detailFallbackTimedOutSource = null;
        OkGo.getInstance().cancelTag("detail");
        if (!consumeFailoverBudget("detailTimeout")) {
            return;
        }
        loadNextDetailFallbackSource();
    }

    /**
     * 发详情请求前记下本次候选键，供超时回调精确记软失败。
     *
     * @param inflightSourceKey 本次要请求的站点
     * @param videoId           本次要请求的具体候选 id（必须是选源时挑中的那一条，
     *                           不能在这里重新取「该站第一条」，否则与拉黑判断脱节）
     */
    private void markDetailFallbackInflight(String inflightSourceKey, String videoId) {
        if (TextUtils.isEmpty(inflightSourceKey)) {
            detailFallbackTimedOutKey = null;
            detailFallbackTimedOutSource = null;
            return;
        }
        detailFallbackTimedOutSource = inflightSourceKey;
        detailFallbackTimedOutKey = getDetailFallbackKey(inflightSourceKey, videoId);
    }

    private String getDetailFallbackKey(String key, String id) {
        return (key == null ? "" : key) + "|" + (id == null ? "" : id);
    }

    /**
     * 归一化片名，用于判断「是否换了一部片子」。
     *
     * <p>各资源站返回的 {@code vod_name} 常有细微差异：多余空格、全角/半角、
     * 分隔符（{@code / · -}）、别名后缀等，而切源过程中 {@code vod_name} 会被
     * 替换成新源的写法。若直接比原始串，切一次源就会被误判为"换片"，
     * 进而清空轮转记录、重置回第一圈 —— 表现为「再点切源又切回同一个站点」。
     *
     * <p>做法：只保留汉字/字母/数字，统一转小写，抹平上述差异。
     *
     * @return 归一化结果；若全是符号/emoji 导致结果为空，退回原始 trim 串，
     *         避免所有此类片子都归一到 "" 而互相串味
     */
    private String normalizeFallbackTitle(String title) {
        // ★ v43：逻辑已提到公共工具 ShowTitleKey，供 SearchSession 候选池、
        //   EpisodeOnlineResolver 缓存、EpisodeDict 字典三处共用同一口径。
        //   本方法保留为薄转发，避免改动大量既有调用点。
        return ShowTitleKey.normalize(title);
    }

    /**
     * 判断某个搜索结果是不是「同一部片」。
     *
     * <p>先做严格相等，再退回归一化相等 —— 各资源站对同一部片的写法常有差异
     * （多余空格、全角/半角、{@code ·}/ {@code /} / {@code -} 等分隔符、
     * 以及 {@code 【} {@code 全集} 之类不影响判定的符号）。只认严格相等时，
     * 这些站明明搜到了也进不了候选池，结果就是「能切的站只剩两三个」。</p>
     *
     * <p>归一化只抹掉符号与大小写，汉字/字母/数字序列必须完全一致才算同一部片，
     * 所以 {@code 花开锦绣} 与 {@code 花开锦绣2}、{@code 花开锦绣 第二季}
     * 依然会被区分开，不会误把别的片切进来。</p>
     */
    private boolean isSameFallbackTitle(String title, String name) {
        return ShowTitleKey.same(title, name);
    }


    // ==================== 方案A：探路请求 ====================

    /**
     * 探路：朝「历史命中率最高」的站点单独打一枪搜索。
     * 与 20 个一组的批处理**并行**执行、互不干扰，谁先回来谁先切。
     * 命中 → 立刻抢跳（门槛 1）；未命中 → 什么都不做，交给批处理兜底。
     */
    private void startDetailFallbackProbe(String probeKey) {
        if (!detailFallbackActive) {
            return;
        }
        if (TextUtils.isEmpty(probeKey)) {
            return;
        }
        detailFallbackProbeSourceKey = probeKey;
        detailFallbackProbeToken = detailFallbackToken + "_probe_" + System.currentTimeMillis();
        detailFallbackProbeStartMs = System.currentTimeMillis();
        detailFallbackProbePending = true;
        LOG.sw("[FB] probeStart source=" + probeKey + " title=" + detailFallbackTitle + " token=" + detailFallbackProbeToken);
        llLayout.removeCallbacks(detailFallbackProbeTimeout);
        llLayout.postDelayed(detailFallbackProbeTimeout, DETAIL_FALLBACK_PROBE_TIMEOUT_MS);
        final String title = detailFallbackTitle;
        final String token = detailFallbackProbeToken;
        OkGo.getInstance().cancelTag(DETAIL_FALLBACK_PROBE_TAG);
        sourceViewModel.getDetailFallbackSearch(probeKey, title, token);
    }

    /** 探路结果：命中就抢跳，没命中就静默放弃（批处理会继续）。 */
    private void handleDetailFallbackProbeResult(AbsXml data) {
        detailFallbackProbePending = false;
        llLayout.removeCallbacks(detailFallbackProbeTimeout);
        boolean hit = false;
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video == null || TextUtils.isEmpty(video.id)
                        || !isSameFallbackTitle(detailFallbackTitle, video.name)) {
                    continue;
                }
                cacheDetailFallbackCandidate(video);
                hit = true;
            }
        }
        recordFallbackStat(data.sourceKey, hit, System.currentTimeMillis() - detailFallbackProbeStartMs);
        LOG.sw("[FB] probeResult source=" + data.sourceKey + " hit=" + hit + " cost=" + (System.currentTimeMillis() - detailFallbackProbeStartMs) + "ms poolSize=" + detailFallbackUsableCandidateCount());
        if (!hit) {
            // 探路没命中：把被摘出的那个源补回队列，否则它这一轮就被整个漏掉了
            requeueProbeSource();
            return;
        }
        // 探路命中就抢跳——但有个前提：缓存里得有**至少 2 个**可用候选。
        // 否则池子里只有探路这一颗苗，切过去之后用户再点切源还是它（「一直切回同一站点」）。
        if (!detailFallbackLoadingCandidate && detailFallbackUsableCandidateCount() >= 2) {
            loadNextDetailFallbackSource();
        }
        // 池子还不够厚 → 不抢跳，安静地等批处理把候选补齐，由收集结束统一决定跳转。
    }

    /**
     * 缓存里当前可用的候选数（去掉不可换源、去重后的真实可选数量）。
     * 用于「抢跳门槛」判断：池子太薄时抢跳会导致原地打转。
     */
    private int detailFallbackUsableCandidateCount() {
        List<Movie.Video> cachedCandidates = detailFallbackCache.get(detailFallbackTitle);
        if (cachedCandidates == null || cachedCandidates.isEmpty()) {
            return 0;
        }
        // ★ 与 scanOrderedSources 保持同一口径：按「还有可用候选的站点」计数。
        // 两者必须一致，否则这里会算出比实际可轮转站点更多的数字，
        // 导致「本圈已转完」被误判为「还有候选」，反之亦然。
        Set<String> seen = new HashSet<>();
        for (Movie.Video video : cachedCandidates) {
            if (video == null || TextUtils.isEmpty(video.id) || TextUtils.isEmpty(video.sourceKey)) {
                continue;
            }
            SourceBean source = ApiConfig.get().getSource(video.sourceKey);
            if (source == null || !source.isSearchable()) {
                continue;
            }
            // ★ 与 scanOrderedSources 同口径：只看「还有未被拉黑候选」的站点。
            //   否则会把「所有候选都被拉黑」的站也算进可用数，抢跳门槛被虚高，
            //   结果是抢跳到一个切不过去的站。
            if (detailFallbackDeadKeys.contains(getDetailFallbackKey(video.sourceKey, video.id))) {
                continue;
            }
            seen.add(video.sourceKey);
        }
        return seen.size();
    }

    /** 探路超时：放弃探路，批处理继续走，不打扰用户。 */
    private void finishDetailFallbackProbeOnTimeout() {
        if (!detailFallbackProbePending) {
            return;
        }
        detailFallbackProbePending = false;
        recordFallbackStat(detailFallbackProbeSourceKey, false,
                System.currentTimeMillis() - detailFallbackProbeStartMs);
        // 超时 = 该站没搜到，把它补回队列交给批处理兜底
        requeueProbeSource();
    }

    /**
     * 探路未命中 / 超时后，把当初摘出去的那个源补回搜索队列尾部。
     *
     * <p>当初摘出它是为了「同一站点不搜两次」（见 startDetailFallback 的注释）。
     * 但探路失败时若不补回，就等于这一轮把最稳的站整个跳过了 —— 省一次重复请求
     * 反而变成漏一个源。补到队尾即可：批处理本来就是滑动窗口，队尾还有机会发出。</p>
     */
    private void requeueProbeSource() {
        final String key = detailFallbackProbeSourceKey;
        detailFallbackProbeSourceKey = "";
        if (TextUtils.isEmpty(key) || !detailFallbackActive) {
            return;
        }
        if (detailFallbackLoadingCandidate) {
            return;
        }
        // 搜索阶段已彻底收尾 → 不再为它单开一轮（候选池已经定下来了）
        if (!detailFallbackSearching && !detailFallbackSearchCollecting) {
            return;
        }
        detailFallbackSourceOrder.add(key);
        if (!detailFallbackSearching) {
            detailFallbackSearching = true;
        }
        ensureDetailFallbackSearchExecutor();
        scheduleDetailFallbackSearch();
    }

    // ==================== 方案B：按源超时 ====================

    /**
     * 给单个源挂超时。到点若还没回来，就把它从 pending 里摘掉；
     * 腾出的名额<b>立刻补发</b>下一个源（滑动窗口，不再等整批）。
     */
    private void scheduleDetailFallbackSourceTimeout(final String searchKey, final String searchToken) {
        llLayout.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!detailFallbackActive || !detailFallbackSearching) {
                    return;
                }
                if (!searchToken.equals(detailFallbackBatchToken)) {
                    return;
                }
                // 该源仍未返回 → 认为是慢源，摘掉它
                if (detailFallbackPendingSources.remove(searchKey)) {
                    detailFallbackSourceStartMs.remove(searchKey);
                    recordFallbackStat(searchKey, false, DETAIL_FALLBACK_SOURCE_TIMEOUT_MS);
                    // 腾出名额 → 立刻补发下一个（若已无剩余源，则由本次调用收尾）
                    if (!detailFallbackLoadingCandidate) {
                        scheduleDetailFallbackSearch();
                    }
                }
            }
        }, DETAIL_FALLBACK_SOURCE_TIMEOUT_MS);
    }

    // ==================== 方案D：缓存持久化 ====================

    /** 把候选池落盘到 Hawk，供下次进同一部片直接复用。 */
    private void persistDetailFallbackCache(String title) {
        title = title == null ? "" : title.trim();
        List<Movie.Video> candidates = detailFallbackCache.get(title);
        if (TextUtils.isEmpty(title) || candidates == null || candidates.isEmpty()) {
            return;
        }
        // ★ 候选池没变化就不落盘。切源收尾（含「切源成功」这条高频路径）每次都会走
        //   复位，无条件写等于每切一次源就做一次 Hawk 序列化写。
        if (!detailFallbackCacheDirty) {
            return;
        }
        List<String[]> slim = new ArrayList<>();
        for (Movie.Video v : candidates) {
            if (v != null && !TextUtils.isEmpty(v.id) && !TextUtils.isEmpty(v.sourceKey)) {
                slim.add(new String[]{v.sourceKey, v.id, v.name == null ? "" : v.name});
            }
        }
        if (slim.isEmpty()) {
            return;
        }
        try {
            Hawk.put(HAWK_FALLBACK_CACHE_PREFIX + title,
                    new FallbackCacheBox(slim, System.currentTimeMillis()));
            trimFallbackCacheIndex(title);
            detailFallbackCacheDirty = false;
        } catch (Throwable th) {
            LOG.e("persistDetailFallbackCache fail: " + th);
        }
    }

    /**
     * 候选池缓存的条数上限管理（LRU）。
     *
     * <p>每部片一个 Hawk key，且只有这部片再次被打开且已过期时才会删自己那条，
     * 于是 key 只增不减。看过的片子多了，Hawk 里会堆出成百上千条，读写都变慢。
     * 这里维护一张「写入顺序」索引，超出 {@link #FALLBACK_CACHE_MAX_ENTRIES} 就删最旧的。</p>
     */
    private void trimFallbackCacheIndex(String title) {
        try {
            List<String> raw = Hawk.get(HAWK_FALLBACK_CACHE_INDEX);
            List<String> index = raw == null ? new ArrayList<String>() : new ArrayList<String>(raw);
            index.remove(title);
            index.add(title);
            while (index.size() > FALLBACK_CACHE_MAX_ENTRIES) {
                String oldest = index.remove(0);
                Hawk.delete(HAWK_FALLBACK_CACHE_PREFIX + oldest);
            }
            Hawk.put(HAWK_FALLBACK_CACHE_INDEX, index);
        } catch (Throwable th) {
            LOG.e("trimFallbackCacheIndex fail: " + th);
        }
    }

    /**
     * 从 Hawk 恢复候选池（带 24h TTL）。
     *
     * <p><b>★ v43：恢复不再置脏</b>。此前逐条走 {@link #cacheDetailFallbackCandidate}
     * 会把 {@code detailFallbackCacheDirty} 置真 → 紧接着的持久化把
     * {@code savedAt} 刷新成「现在」→ <b>TTL 被无限续期</b>，过期的站点候选
     * （该片已下架）被长期保留，切源反复切到「详情能出但列表为空」的站。
     * 现在恢复路径直接建列表并放入缓存，<b>不碰 dirty 标志</b> ——
     * 只有本轮真的搜到新候选才值得回写。</p>
     */
    private void restoreDetailFallbackCache(String title) {
        title = title == null ? "" : title.trim();
        if (TextUtils.isEmpty(title)) {
            return;
        }
        try {
            FallbackCacheBox box = Hawk.get(HAWK_FALLBACK_CACHE_PREFIX + title);
            if (box == null || box.entries == null || box.entries.isEmpty()) {
                return;
            }
            if (System.currentTimeMillis() - box.savedAt > FALLBACK_CACHE_TTL_MS) {
                Hawk.delete(HAWK_FALLBACK_CACHE_PREFIX + title);
                return;
            }
            List<Movie.Video> restored = new ArrayList<>(box.entries.size());
            Set<String> seenKeys = new HashSet<>(Math.max(16, box.entries.size() * 2));
            for (String[] e : box.entries) {
                if (e == null || e.length < 2 || TextUtils.isEmpty(e[0]) || TextUtils.isEmpty(e[1])) {
                    continue;
                }
                String entryKey = getDetailFallbackKey(e[0], e[1]);
                if (!seenKeys.add(entryKey)) {
                    continue;
                }
                Movie.Video v = new Movie.Video();
                v.sourceKey = e[0];
                v.id = e[1];
                v.name = e.length > 2 && e[2] != null ? e[2] : title;
                restored.add(v);
            }
            if (restored.isEmpty()) {
                return;
            }
            // 并入内存缓存：与已有条目按 key 增量合并，不置 dirty（见方法注释）。
            List<Movie.Video> cachedCandidates = detailFallbackCache.get(title);
            if (cachedCandidates == null) {
                detailFallbackCache.put(title, restored);
            } else {
                Set<String> existing = new HashSet<>(Math.max(16, cachedCandidates.size() * 2));
                for (Movie.Video c : cachedCandidates) {
                    existing.add(getDetailFallbackKey(c.sourceKey, c.id));
                }
                for (Movie.Video v : restored) {
                    if (existing.add(getDetailFallbackKey(v.sourceKey, v.id))) {
                        cachedCandidates.add(v);
                    }
                }
            }
        } catch (Throwable th) {
            LOG.e("restoreDetailFallbackCache fail: " + th);
        }
    }

    /** Hawk 里存的精简缓存（只留 sourceKey/id/name，避免序列化整个 Movie.Video）。 */
    private static class FallbackCacheBox {
        final List<String[]> entries;
        final long savedAt;

        FallbackCacheBox(List<String[]> entries, long savedAt) {
            this.entries = entries;
            this.savedAt = savedAt;
        }
    }

    // ==================== 方案E：站点命中率统计 ====================
    //
    // ★ 已统一到 SourceQualityStore（与搜索页 FastSearchActivity/SearchActivity、
    //   播放页 PlayFragment 共用同一份数据，key 前缀都是 fb_stat_，6 字段格式）。
    //
    // 此前这里有一套自有的 recordFallbackStat/detailFallbackSourceScore，用**同一个
    // fb_stat_ 前缀**却只写 3 个字段（hitCnt,failCnt,totalMs），而 SourceQualityStore
    // 写 6 个字段（...,playOkCnt,playFailCnt,firstFrameMsSum）。两边互相覆盖：
    //   - 搜索页写 6 字段 → 切源只读前 3 个，歪打正着读到部分数据；
    //   - 切源写 3 字段 → 搜索页读后 3 个全为 0，播放质量历史被抹掉。
    // 结果就是「质量排序」形同虚设。现统一走 SourceQualityStore，一套读写、一处格式。

    /** 记录某站点的一次搜索命中/失败与耗时（写入 SourceQualityStore）。 */
    private void recordFallbackStat(String sourceKey, boolean hit, long elapsedMs) {
        if (TextUtils.isEmpty(sourceKey)) {
            return;
        }
        try {
            SourceQualityStore.recordSearch(sourceKey, hit, Math.max(0L, elapsedMs));
        } catch (Throwable th) {
            LOG.e("recordFallbackStat fail: " + th);
        }
    }

    /**
     * 站点质量分（切源选站用），委托给 {@link SourceQualityStore}。
     *
     * <p>与搜索页用的是同一套数据与同一套打分，保证「切源时优先挑的站点」
     * 就是「搜索结果里排在前面、且实测起播快的站点」。
     */
    private double detailFallbackSourceScore(String sourceKey) {
        try {
            return SourceQualityStore.score(sourceKey);
        } catch (Throwable th) {
            return 0.5;
        }
    }

    /**
     * 挑探路站点。
     *
     * 唯一硬性规则：**必须排除当前正在播放的源**。
     * 探路的目的是「找一个不一样的源」，打自己等于白费一次请求，
     * 而且命中后会把用户「切」回原来那个站。
     *
     * 其余情况按历史命中率取最优；全部无历史时自然落到列表里第一个非当前源
     * （不做人为轮换 —— 保持选择可预测，切源顺序才稳定）。
     */
    private String pickBestProbeSource() {
        String best = "";
        double bestScore = -1;
        for (String key : detailFallbackSourceOrder) {
            if (isCurrentPlayingSource(key)) {
                continue;                       // 硬性规则：排除当前源
            }
            SourceBean bean = ApiConfig.get().getSource(key);
            if (bean == null || !bean.isSearchable()) {
                continue;
            }
            double score = detailFallbackSourceScore(key);
            if (score > bestScore) {
                bestScore = score;
                best = key;
            }
        }
        return TextUtils.isEmpty(best) ? "" : best;
    }

    /**
     * 把 {@link #detailFallbackSourceOrder} 按站点质量分从高到低重排。
     *
     * <p><b>为什么需要</b>：这个列表决定了「一圈」的轮转顺序，也就是用户按切源时
     * 依次试到哪些站。原先是 ApiConfig 的配置顺序，与站点实际快慢完全无关；
     * 排在后面的好站点要等前面一堆慢站超时才会被轮到。
     *
     * <p><b>与搜索的一致性</b>：分数取自 {@link SourceQualityStore#snapshot}，
     * 与搜索页（FastActivity/SearchActivity）和播放页（PlayFragment 的起播打点）
     * 用的是同一份数据、同一套权重，因此「切源优先试的站」就是「搜索排前面的站」。
     *
     * <p><b>★ P1-2 修正说明</b>：上面这句此前是<b>不成立</b>的 —— 搜索页走
     * {@code snapshotForSearch}（命中 0.35 / 速度 0.45 / 播放 0.20），
     * 本页走 {@code snapshot}（播放 0.30 / 速度 0.55 / 命中 0.15），
     * 两套权重口径不同，导致「搜索第 1 名」进详情页可能排到第 5。
     * 现已统一为<b>播放优先</b>单一口径（见 {@code SourceQualityStore} 类注释），
     * 本段描述从「声称」变为「事实」。
     *
     * <p><b>稳定性</b>：用 {@code List.sort}（TimSort，稳定排序）。所有站点在
     * 冷启动时都是中性分 0.5，此时排序结果 == 原始顺序，不会打乱既有行为。
     *
     * <p><b>性能</b>：一次性批量取分（{@code snapshot}），比较器内不再逐次读 Hawk。
     * 源数量在百级，排序开销可忽略。
     */
    private void sortDetailFallbackSourceOrderByQuality() {
        if (detailFallbackSourceOrder.size() <= 1) {
            return;
        }
        try {
            final SourceQualityStore.Snapshot snapshot =
                    SourceQualityStore.snapshot(detailFallbackSourceOrder);
            if (snapshot.isEmpty()) {
                // 冷启动：全是中性分，排序结果 == 原顺序，直接省掉这一轮
                return;
            }
            java.util.Collections.sort(detailFallbackSourceOrder,
                    new Comparator<String>() {
                        @Override
                        public int compare(String a, String b) {
                            // 降序：分数高的排前面。
                            // 用 Double.compare 保证同分返回 0 → TimSort 保持原相对顺序。
                            return Double.compare(snapshot.get(b), snapshot.get(a));
                        }
                    });
            LOG.i("[FB] sourceOrder sorted by quality, n=" + detailFallbackSourceOrder.size()
                    + " head=" + detailFallbackSourceOrder.subList(0, Math.min(3, detailFallbackSourceOrder.size())));
        } catch (Throwable th) {
            // 排序失败不影响主流程：保持原序（等价于「无质量数据」的冷启动行为）
            LOG.e("sortDetailFallbackSourceOrderByQuality fail: " + th);
        }
    }

    /**
     * 判定某个源是不是「当前正在播放的源」。
     * sourceKey 是当前详情源；播放记录里的源也一并比对（从搜索页进入时两者可能不同步）。
     */
    private boolean isCurrentPlayingSource(String key) {
        if (TextUtils.isEmpty(key)) {
            return false;
        }
        if (TextUtils.equals(key, sourceKey)) {
            return true;
        }
        // 兜底：与「实际在播」的对象比对。预览模式下真正在播的是 previewVodInfo，
        // 而 sourceKey / vodInfo 可能已经指向别的源（从搜索页进入时尤其明显）。
        if (previewVodInfo != null && !TextUtils.isEmpty(previewVodInfo.sourceKey)
                && TextUtils.equals(key, previewVodInfo.sourceKey)) {
            return true;
        }
        if (vodInfo != null && !TextUtils.isEmpty(vodInfo.sourceKey)
                && TextUtils.equals(key, vodInfo.sourceKey)) {
            return true;
        }
        return false;
    }

    // 原 sortSourcesByHitRate(List<String>) 已删除：
    // 它的作用只是"把当前这批 20 个按键分降序重排"，而整张列表在搜索开始时
    // 已经由 sortDetailFallbackSourceOrderByQuality() 用同一份分数
    // （SourceQualityStore）全量降序排过，逐批再排一次是冗余的。
    // 改为滑动窗口后"批"的概念消失，这个方法的语义也就不存在了。

    /**
     * 把搜索会话候选池的「当前最新」集合并入本地缓存。
     *
     * <p><b>为什么每次切源都要调</b>：聚合搜索是边跑边补的，
     * 单一真相源在 {@code SearchSession}（进程级单例，与搜索页共享）。
     * 而本地 {@code detailFallbackCache} 只是进页面那一刻的副本。
     * 若只在 {@code initData()} 读一次，切源就只能在「进页面时已搜到的那些站」
     * 之间轮转 —— 后台后续搜到的源永远进不来，表现为
     * 「切源数量不随时间增加」。</p>
     *
     * <p>{@code cacheDetailFallbackCandidates} 内部按 sourceKey+id 去重，
     * 重复调用只做增量合并，安全。</p>
     */
    private void refreshFallbackCandidatesFromSession() {
        if (TextUtils.isEmpty(detailFallbackTitle) || sourceViewModel == null) {
            return;
        }
        try {
            List<Movie.Video> latest =
                    SearchSession.getShared().getCandidates(detailFallbackTitle);
            if (latest != null && !latest.isEmpty()) {
                cacheDetailFallbackCandidates(detailFallbackTitle, latest);
            }
        } catch (Throwable th) {
            // 会话不可用时不影响切源主流程
            th.printStackTrace();
        }
    }

    /**
     * 把一批候选并入本地缓存（按 sourceKey+id 增量去重）。
     *
     * <p><b>★ v43：新增 {@code markDirty} 参数</b>。此前本方法总是把
     * {@code detailFallbackCacheDirty} 置真，导致<b>从 Hawk 恢复缓存时也会置脏</b>
     * —— 恢复→置脏→下次 {@code persistDetailFallbackCache} 回写并刷新时间戳，
     * 于是 TTL 被无限续期，早已下架该片的站点候选被长期保留，
     * 切源时反复切到「详情能出但列表为空」的站。</p>
     *
     * <p>现在「恢复到内存」（{@code markDirty=false}）与「搜索新命中」
     * （{@code markDirty=true}）语义分开。</p>
     *
     * <p><b>★ v43：去重改为 Set 索引</b>。原实现对每条候选线性扫描已缓存列表，
     * 是 O(n²)；候选池可达「40 部片 × 每片数十条」，且跑在主线程。改为维护
     * {@code Set<sourceKey|id>} 后降到 O(n)。</p>
     */
    private void cacheDetailFallbackCandidates(String title, List<Movie.Video> candidates) {
        cacheDetailFallbackCandidates(title, candidates, true);
    }

    private void cacheDetailFallbackCandidates(String title, List<Movie.Video> candidates, boolean markDirty) {
        title = title == null ? "" : title.trim();
        if (TextUtils.isEmpty(title) || candidates == null || candidates.isEmpty()) {
            return;
        }
        List<Movie.Video> cachedCandidates = detailFallbackCache.get(title);
        if (cachedCandidates == null) {
            cachedCandidates = new ArrayList<>();
            detailFallbackCache.put(title, cachedCandidates);
        }
        // 一次性建立已有候选的 key 索引，避免逐条线性扫描（O(n²) → O(n)）。
        Set<String> existingKeys = new HashSet<>(Math.max(16, cachedCandidates.size() * 2));
        for (Movie.Video cachedVideo : cachedCandidates) {
            existingKeys.add(getDetailFallbackKey(cachedVideo.sourceKey, cachedVideo.id));
        }
        for (Movie.Video video : candidates) {
            if (video == null
                    || TextUtils.isEmpty(video.id) || !isSameFallbackTitle(title, video.name)) {
                continue;
            }
            String candidateKey = getDetailFallbackKey(video.sourceKey, video.id);
            if (existingKeys.add(candidateKey)) {
                cachedCandidates.add(video);
                if (markDirty) {
                    detailFallbackCacheDirty = true;
                }
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
        detailFallbackCacheDirty = true;
    }

    private void showDetailEmpty() {
        showEmpty();
        llPlayerFragmentContainer.setVisibility(View.GONE);
        llPlayerFragmentContainerBlock.setVisibility(View.GONE);
    }

    private void resetDetailFallback() {
        resetDetailFallback(false);
    }

    /**
     * 切源成功后的收尾：保留 detailFallbackCache 与仍在跑的批处理，
     * 只把「当前这一轮」的状态机复位。下次点切源可直接续用候选池。
     * 用 resetDetailFallback() 会 cancelTag 掐死批处理，导致候选池永远补不满。
     */
    private void resetDetailFallbackKeepCache() {
        resetDetailFallback(true);
    }

    /**
     * @param keepCache true = 保留缓存与批处理（切源成功路径）
     *                  false = 彻底复位（退出页面 / 换片 / 出错）
     */
    private void resetDetailFallback(boolean keepCache) {
        detailFallbackFailoverCount = 0;
        detailFallbackActive = false;
        // 切源这一轮结束（成功 / 放弃 / 出错），提示弹窗必须同步收起
        dismissSwitchingSourceToast();
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
        // 方案D：复位前把候选池落盘，供下次进同一部片直接复用
        persistDetailFallbackCache(detailFallbackTitle);
        detailFallbackSourceOrder.clear();
        detailFallbackPendingSources.clear();
        detailFallbackSourceStartMs.clear();
        // 方案A：清理探路状态
        detailFallbackProbePending = false;
        detailFallbackProbeSourceKey = "";
        detailFallbackProbeToken = "";
        llLayout.removeCallbacks(detailFallbackProbeTimeout);
        // 注意：这里不清 detailFallbackCycleKeys / detailFallbackSoftTriedKeys / detailFallbackTitle，
        // 因为「一圈」跨越多轮点击；清掉会让候选池每轮重置回起点，导致只有两三个源来回循环。
        // 新圈由 pollNextCycledSource() 在本圈转尽时统一开启（它会自己清这两个集合）。
        detailFallbackEpisode = null;
        detailFallbackEpisodeIndex = -1;
        detailFallbackSourceNames = null;
        if (llLayout != null) {
            llLayout.removeCallbacks(detailFallbackTimeout);
            llLayout.removeCallbacks(detailFallbackDetailTimeout);
            llLayout.removeCallbacks(detailFallbackEarlyConsume);
        }
        detailFallbackEarlyConsumeScheduled = false;
        if (!keepCache) {
            // 只有「彻底复位」才掐断批处理；切源成功后的收尾必须让它继续跑完，
            // 否则候选池永远只有第一个源 —— 这正是「一直切回同一站点」的根因。
            stopDetailFallbackSearchExecutor();
            OkGo.getInstance().cancelTag(DETAIL_FALLBACK_SEARCH_TAG);
            cancelDetailFallbackProbe();
        }
    }

    /** 取消探路请求（tag 与批处理不同，需单独取消）。 */
    private void cancelDetailFallbackProbe() {
        detailFallbackProbePending = false;
        llLayout.removeCallbacks(detailFallbackProbeTimeout);
        OkGo.getInstance().cancelTag(DETAIL_FALLBACK_PROBE_TAG);
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
    /**
     * 候选池（内存层），按归一化片名索引。
     *
     * <p><b>★ 本轮补上内存层 LRU</b>：此前这里是普通 {@code HashMap}，只增不删，
     * 全靠 {@code onDestroy()} 整体清空兜底。搜索执行体解耦后，
     * 「Activity 销毁 = 清空」这道闸门对长会话不再可靠 —— 连续浏览多部片会让
     * 内存里堆积成百上千条完整 {@code Movie.Video}（含简介全文），
     * 对 2GB 设备是不必要的风险。</p>
     *
     * <p><b>★ v43：accessOrder 改为 true</b>。此前第三个参数误写 {@code false}
     * （插入序 = FIFO），与本注释声称的 LRU 语义相反 —— 连续浏览超过 40 部片后，
     * 被淘汰的是「最早插入的」而不是「最久未用的」，用户最近常看的片反而先被清掉，
     * 表现为「之前切过源的片，过一会儿回来切源池又变空」。
     * 改为 {@code true} 后 {@code get} 会触发访问序重排。</p>
     *
     * <p><b>访问线程</b>：本结构目前<b>仅被主线程访问</b>（切源状态机跑在主线程，
     * 详见 {@code detailFallbackSearchResult.observe(this, ...)} 的主线程投递保证）。
     * 若将来把持久化 I/O 移出主线程（见 P2-3），<b>必须同时</b>做不可变快照或加锁，
     * 否则会出现 {@code ConcurrentModificationException}。</p>
     */
    private final LinkedHashMap<String, List<Movie.Video>> detailFallbackCache =
            new LinkedHashMap<String, List<Movie.Video>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, List<Movie.Video>> eldest) {
                    return size() > FALLBACK_CACHE_MAX_ENTRIES;
                }
            };
    private final Set<String> detailFallbackPendingSources = new HashSet<>();
    /**
     * 滑动窗口下每个在途源的发出时刻（key = sourceKey）。
     *
     * <p>原来记录耗实用的是"批次发出时刻"，但滑动窗口里批次边界已不存在：
     * 一个源可能是第 1 批发的，3s 后才收到结果，而批次时刻是它刚发出时的时刻。
     * 逐源记录才能得到真实的单源响应耗时，喂给 SourceQualityStore 的分数才准。</p>
     */
    private final HashMap<String, Long> detailFallbackSourceStartMs = new HashMap<>();
    /**
     * 是否已经为「提前消费」排过一次延时放片（一轮只排一次）。
     * @see #DETAIL_FALLBACK_FIRST_CONSUME_DELAY_MS
     */
    private boolean detailFallbackEarlyConsumeScheduled;

    /**
     * 本轮切源已发生的「失败续切」次数，达到 {@link #DETAIL_FALLBACK_MAX_FAILOVER} 即停止。
     * 每次<b>手动</b>发起切源时清零；自动续切不清零，正是为了限制连跳。
     */
    private int detailFallbackFailoverCount;

    /**
     * 提前消费：候选池里已经有可用候选时，不再等搜索阶段收尾就直接放起来。
     *
     * <p>只做一次判断，真正的选源/加载交给 {@link #loadNextDetailFallbackSource()}，
     * 与「搜索结束」「详情超时」两条既有路径共用同一段逻辑，避免多出一条选源分支。</p>
     */
    private final Runnable detailFallbackEarlyConsume = new Runnable() {
        @Override
        public void run() {
            detailFallbackEarlyConsumeScheduled = false;
            // 已复位 / 已在放另一个候选 → 什么都不做
            if (!detailFallbackActive || detailFallbackLoadingCandidate) {
                return;
            }
            if (!detailFallbackCacheEntryUsable()) {
                return;
            }
            loadNextDetailFallbackSource();
        }
    };
    /**
     * 本「一圈」内已经轮转到过的**站点 key**。
     *
     * <p>一圈的定义：把缓存里的站点全部轮转一遍后重新开始。
     * 跨圈会清空，所以下一圈可以重新轮到同一个站点（允许全站循环，但不允许同圈重复）。
     *
     * <p>注意粒度是 **sourceKey**，不是「sourceKey|影片id」：同一个站点在缓存里
     * 可能有多条匹配，用后者当标记会让它们被当成不同候选，一圈内重复切到同一个站。
     */
    private final Set<String> detailFallbackCycleKeys = new HashSet<>();
    /** 是否为「一圈」的第一次切源（决定要不要发起全网搜索）。 */
    private boolean detailFallbackNewCycle = true;
    private VodInfo.VodSeries detailFallbackEpisode;
    private int detailFallbackEpisodeIndex = -1;
    /** 切源前捕获的旧源剧集名列表（供离线秩对齐用，见 captureDetailFallbackEpisode）。 */
    private List<String> detailFallbackSourceNames = null;
    
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
    /** 当前 CycleKeys / TriedKeys 归属的片名；片名一变说明换剧了，圈记录必须重置。 */
    private String detailFallbackCycleTitle = "";
    // ===== 方案A：探路状态 =====
    private String detailFallbackProbeSourceKey = "";
    private String detailFallbackProbeToken = "";
    private long detailFallbackProbeStartMs;
    private boolean detailFallbackProbePending;
    /** 本批搜索的发出时刻，用于统计每个源的响应耗时。 */
    private long detailFallbackBatchStartMs;
    private final Runnable detailFallbackProbeTimeout = new Runnable() {
        @Override
        public void run() {
            finishDetailFallbackProbeOnTimeout();
        }
    };
    // ===== 方案C：TriedKeys 分级 =====
    /** 确认不可用（详情成功但无地址 / 明确空）：永久排除。 */
    private final Set<String> detailFallbackDeadKeys = new HashSet<>();
    /** 软失败（超时 / 网络错误）：只本圈跳过，下圈可重试。 */
    private final Set<String> detailFallbackSoftTriedKeys = new HashSet<>();
    /** 当前在途详情请求的「源|影片id」键；超时回调据此精确记软失败，避免整站连坐。 */
    private String detailFallbackTimedOutKey;
    /** 当前在途详情请求的站点 key（与 {@link #detailFallbackTimedOutKey} 配套，供软失败按站粒度记录）。 */
    private String detailFallbackTimedOutSource;
    /** 已开过的圈数，仅用于日志观察轮转是否正常推进。 */
    private int detailFallbackCycleCount;
    /** 本圈轮转的起始下标；开新圈时接续上一圈末尾，避免跨圈撞回头（仅作 key 缺失时的兜底）。 */
    private int detailFallbackCycleStart;
    /** 本圈刚选中的下一个起点下标，开新圈时赋给 cycleStart。 */
    private int detailFallbackCycleStartOfNext;
    /**
     * 本圈轮转的起始「站点 key」。
     *
     * <p><b>为什么不用下标</b>：轮转顺序是按站点质量分排出来的，分数会随每次
     * 搜索打点更新，池子一重排，同一个下标就指向了另一个站 —— 用它记录
     * 「上一圈停在哪」会漂移。改为记 key，下标只在 key 已不在池子里时兜底。</p>
     */
    private String detailFallbackCycleStartKey = "";
    /**
     * 最近一次选源命中的<b>具体候选影片 id</b>。
     *
     * <p>同一个站点在缓存里可能有多条同名候选（MV/预告在前、正片在后）。
     * 选源时已按「未被拉黑」挑出具体一条，后续 loadDetail / 站点指示必须沿用
     * 这一条，不能各自再去取「该站第一条」——否则拉黑了 MV 之后仍会反复切到 MV。
     */
    private String detailFallbackPickedVideoId = "";
    /** 候选池自上次落盘以来是否新增过候选；无变化就跳过 Hawk 写入（切源收尾会频繁复位）。 */
    private boolean detailFallbackCacheDirty;
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
        // 第3层：跨域（日期↔期数）且本地未命中时，异步联网精确重定位
        tryOnlineCrossDomainResolve(playingSeries == null ? "" : playingSeries.name, newFlag,
                newSeriesList, newIndex >= 0 ? newIndex : Math.max(0, Math.min(oldIndex, newSeriesList.size() - 1)));
        vodInfo.playFlag = newFlag;
        // 保守落地：匹配到才写 playIndex；匹配不到（-1）保持原下标不动，
        // 绝不交给 clampIndex 把 -1 伪造成第 0 集（那会静默覆盖播放记录）。
        //
        // ★ 注意：oldIndex 是【旧列表】的下标，直接塞进【新列表】是错的——
        //   两源排序不同，编号一致也未必同集（旧源 36 条、新源 23 条时偏差更大）。
        //   仅在"两列表构成一致"（同域、同序）时才可以按下标平移；
        //   跨域/异构成列表时保持 -1 语义，交给上层保守处理（不写 playIndex）。
        if (newIndex < 0) {
            boolean sameShape = sameFlag
                    && playingSeries != null
                    && newSeriesList.size() == getPlayingSeriesList().size();
            if (sameShape) {
                newIndex = Math.max(0, Math.min(oldIndex, newSeriesList.size() - 1));
            }
        }
        if (newIndex < 0) {
            // 保底：仍找不到就维持旧下标（同一 flag 下至少不跳到无关联的集）
            newIndex = Math.max(0, Math.min(oldIndex, newSeriesList.size() - 1));
        }
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
     *
     * <p><b>注意：本方法只做范围收敛，不做任何"语义转换"</b>。
     * 传入 7 就返回 7（只要在范围内），传入 -1 会返回 0。
     * 因此<b>绝不能用它来消化"匹配失败"</b>——那会把 -1 静默变成第 0 集，
     * 覆盖用户的播放记录。匹配失败请用 {@link #safeLandPlayIndex} 走保守落地。</p>
     */
    private int clampIndex(int index, List<?> list) {
        if (list == null || list.isEmpty()) return 0;
        return Math.max(0, Math.min(index, list.size() - 1));
    }

    /**
     * <b>保守落地</b>：切源/切线路时把"内容匹配得到的新下标"安全地写回 {@code playIndex}。
     *
     * <p><b>为什么需要它</b>：{@link #findMatchingEpisodeIndex} 在链路全灭时返回 -1。
     * 早期调用方直接把它交给 {@link #clampIndex}，结果是：
     * <ul>
     *   <li>传 -1 → 被夹成 0 → <b>切到第 0 集</b>；</li>
     *   <li>或先写 -1 再被别处 clamp → 同样是第 0 集；</li>
     * </ul>
     * 两种情况都会<b>静默覆盖播放记录</b>，用户以为"切源成功"，其实跑到了毫不相干的一集。</p>
     *
     * <p><b>保守语义</b>（用户明确选择的方案）：
     * <ul>
     *   <li>{@code newIndex >= 0}：正常写入，返回 true；</li>
     *   <li>{@code newIndex < 0}：<b>不改动</b> {@code playIndex}（保持原有下标不动），
     *       并按需 Toast 告知"未找到对应剧集"，返回 false，由调用方决定是否放弃整次切换。</li>
     * </ul>
     * 即"宁可不动，也不乱动"——匹配不到时保持现状，比切到错集安全得多。</p>
     *
     * @param newIndex 内容匹配得到的下标，-1 表示匹配失败
     * @param list     目标列表（用于边界校验）
     * @param notify   匹配失败时是否 Toast 提示用户
     * @return true 表示已写入合法下标；false 表示匹配失败、未做任何改动
     */
    private boolean safeLandPlayIndex(int newIndex, List<?> list, boolean notify) {
        if (vodInfo == null) return false;
        if (newIndex >= 0 && list != null && !list.isEmpty() && newIndex < list.size()) {
            vodInfo.playIndex = newIndex;
            return true;
        }
        // 匹配失败：保持原样，绝不用 clampIndex 把 -1 伪造成第 0 集
        if (notify) {
            android.widget.Toast.makeText(DetailActivity.this,
                    "该剧集在所切换的源中未找到，已保持当前播放",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
        return false;
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
        if (matchedIndex >= 0) {
            return matchedIndex;
        }
        // 裸下标兜底前做正片保护：当前集是正片时，绝不落在特辑/花絮等非正片条目上
        // （实测曾把「第5期上」兜底到下标恰为「20260508泳池特辑」的位置）
        return EpisodeNameMatcher.sanitizeMainFeatureFallback(
                currentSeries == null ? null : currentSeries.name,
                fallbackIndex, seriesNames(targetList));
    }

    /** 一次"选线路 + 定位集"的搜索结果。 */
    private static final class FlagMatch {
        final String flag;
        final int index;

        FlagMatch(String flag, int index) {
            this.flag = flag;
            this.index = index;
        }
    }

    /**
     * 在某条线路上定位与 {@code currentSeries} 对应的集。
     *
     * <p>返回值语义：
     * <ul>
     *   <li>{@code >= 0} —— 找到了，索引可用；</li>
     *   <li>{@code -1}   —— 该线路确实<b>没有</b>这一集（不是"不知道"，
     *       而是所有本地/在线手段都查过了，且明确判定不存在）。</li>
     * </ul>
     * 关键区别：单集列表直接返回 0（只有一集时只能是它），
     * 而<b>多集列表匹配不上时返回 -1，绝不 clamp 成 0</b>——
     * 这正是之前"匹配失败被静默伪装成第0集"的根源。</p>
     */
    private int locateEpisodeOnFlag(VodInfo.VodSeries currentSeries, List<VodInfo.VodSeries> list) {
        if (list == null || list.isEmpty()) {
            return -1;
        }
        if (list.size() == 1) {
            return 0;
        }
        if (currentSeries == null || TextUtils.isEmpty(currentSeries.name)) {
            return -1;
        }
        return findMatchingEpisodeIndex(currentSeries, list);
    }

    /**
     * 从用户点击的线路开始，沿线路列表轮询，找到第一条<b>确实含有当前集</b>的线路。
     *
     * <p>需求来源：当目标源没有这一集时，不应静默切到"第 0 集"并覆盖播放记录，
     * 而应当<b>跳过该站点、继续尝试下一个站点</b>，直到找到真正含有该集的源；
     * 全都找不到才放弃切源（保持原线路与进度不变）。</p>
     *
     * <p>轮询顺序：先 {@code startPos}（用户点的那个），再 {@code startPos+1 …} 绕回开头，
     * 保证"用户点的线路"优先级最高，符合直觉。</p>
     *
     * @param currentSeries 当前在播的集（旧源写法）
     * @param startPos      用户点击的线路下标
     * @return 命中的线路与集索引；全部线路都没有该集时返回 null
     */
    private FlagMatch findFirstFlagWithEpisode(VodInfo.VodSeries currentSeries, int startPos) {
        if (vodInfo == null || vodInfo.seriesFlags == null
                || vodInfo.seriesFlags.isEmpty() || currentSeries == null
                || TextUtils.isEmpty(currentSeries.name)) {
            return null;
        }
        int n = vodInfo.seriesFlags.size();
        if (n <= 0) {
            return null;
        }
        int start = Math.max(0, Math.min(startPos, n - 1));
        for (int k = 0; k < n; k++) {
            int pos = (start + k) % n;               // 从点击位置开始绕圈
            VodInfo.VodSeriesFlag f = vodInfo.seriesFlags.get(pos);
            if (f == null || TextUtils.isEmpty(f.name)) {
                continue;
            }
            List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(f.name);
            int idx = locateEpisodeOnFlag(currentSeries, list);
            if (idx >= 0) {
                return new FlagMatch(f.name, idx);
            }
        }
        return null;
    }

    /**
     * 更新线路按钮的选中态（切换线路时视觉同步）。
     *
     * @param selectFlag 新的选中线路名
     */
    private void updateFlagSelectionUi(String selectFlag) {
        if (vodInfo == null || vodInfo.seriesFlags == null || seriesFlagAdapter == null) {
            return;
        }
        for (int i = 0; i < vodInfo.seriesFlags.size(); i++) {
            VodInfo.VodSeriesFlag f = vodInfo.seriesFlags.get(i);
            if (f == null) continue;
            boolean sel = TextUtils.equals(f.name, selectFlag);
            f.selected = sel;
            View v = mGridViewFlag == null || mGridViewFlag.getLayoutManager() == null
                    ? null : mGridViewFlag.getLayoutManager().findViewByPosition(i);
            if (v != null) {
                int vis = sel ? View.VISIBLE : View.GONE;
                v.findViewById(R.id.tvSeriesFlagSelect).setVisibility(vis);
            }
        }
    }

    /**
     * 跨源定位"同一集"的索引。
     *
     * <p><b>分层策略（有网优先）</b>：
     * <ol>
     *   <li><b>第1层 本地同域匹配</b>：两侧命名方式相同（都是日期或都是"第N期"）时，
     *       毫秒级精确命中，含前导零（第8集 ↔ 第08集、001集）与多字少字容错；</li>
     *   <li><b>第2层 跨域直连</b>：一侧日期、一侧期数时，本地原理上无法换算，
     *       交给 {@link EpisodeOnlineResolver} 用站点权威数据回答（缓存命中 0ms，
     *       未命中约 800ms，超预算立即放弃）；</li>
     *   <li><b>第3层 本地按序兜底</b>：以上都没结果时，按旧源下标对齐——
     *       该猜测仅在"两源列表构成一致"时成立，故作为最后手段。</li>
     * </ol>
     * 全程任何失败都返回 -1，交由调用方 {@code clampIndex} 兜底，<b>绝不阻塞切源</b>。</p>
     *
     * @return 目标源下标；无法可靠匹配返回 -1
     */
    private int findMatchingEpisodeIndex(VodInfo.VodSeries currentSeries, List<VodInfo.VodSeries> targetList) {
        int resolved = findMatchingEpisodeIndexRaw(currentSeries, targetList);
        if (currentSeries == null || TextUtils.isEmpty(currentSeries.name)
                || targetList == null || targetList.isEmpty()) {
            return resolved;
        }
        try {
            List<String> srcNames = getPlayingSeriesList() == null
                    ? null : seriesNames(getPlayingSeriesList());
            List<String> tgtNames = seriesNames(targetList);
            // ★ 冷启动探路：目标源是"全无名日期式"（第YYYYMMDD期，每条都不带内容词）时，
            //   本地无法区分正片与期内衍生条目，首播基准会认错、整体偏移一期。
            //   先从其它可用源把「第N期 = 某日期」建起来，再回目标源精确落位。
            //   实测（歌手2026 冷启动，每轮清空事实表）：正确 11/错 22 → 正确 30/错 3。
            //
            //   ★ 触发条件是「落点不可信」而不是「没落上」——冷启动的典型症状恰恰是
            //   **错切到了一个存在的条目**（第8期被算成第20260703期），locate 返回 >=0，
            //   所以只在 resolved < 0 时探路会完全错过这类最需要纠正的场景。
            if (!EpisodeNameMatcher.isMatchTrusted(currentSeries.name,
                    resolved >= 0 && resolved < targetList.size()
                            ? targetList.get(resolved).name : null,
                    srcNames, tgtNames)) {
                int retry = probeFromOtherSources(currentSeries, targetList, srcNames);
                if (retry >= 0 && retry < targetList.size()) {
                    resolved = retry;
                }
            }
            // 跨源事实记忆：本次成功落位就是一条"期号↔日期"被两侧证实的强证据。
            // 记下来，后续切到任何源都能直接查表命中，不必再依赖脆弱的周更快照推算。
            if (resolved >= 0 && resolved < targetList.size()) {
                // ★ 传入两侧列表，让事实层做"序关系守卫"——
                //   冷启动时的错切若被记成事实，会永久污染后续每一次切源。
                EpisodeNameMatcher.learnCrossDomainFact(
                        currentSeries.name, targetList.get(resolved).name,
                        srcNames, tgtNames);
            }
        } catch (Throwable ignored) {
            // 记忆/探路失败绝不影响主流程
        }
        return resolved;
    }

    /**
     * 冷启动探路：把 {@code vodInfo.seriesMap} 里其它线路的集名列表收集起来交给匹配器。
     *
     * <p>注意必须传<b>完整列表</b>而不是只传探中的那一条 ——
     * {@code rankConsistent} 依赖列表的正片序列规模做序关系校验，
     * 单元素列表会让它把正确结果也判成不可信（已实测，见
     * {@link EpisodeNameMatcher#probeFactFromOtherSources} 的说明）。</p>
     */
    private int probeFromOtherSources(VodInfo.VodSeries currentSeries,
                                      List<VodInfo.VodSeries> targetList,
                                      List<String> srcNames) {
        try {
            if (srcNames == null || srcNames.isEmpty() || vodInfo == null
                    || vodInfo.seriesMap == null || vodInfo.seriesMap.isEmpty()) {
                return -1;
            }
            // 只探「期号式 → 日期式」这一方向：源侧不带期号时建不出事实，跳过
            if (EpisodeNameMatcher.leadingOrdinalOf(currentSeries.name) <= 0) {
                return -1;
            }
            List<List<String>> others = new ArrayList<>();
            for (Map.Entry<String, List<VodInfo.VodSeries>> e : vodInfo.seriesMap.entrySet()) {
                if (e.getKey() == null || e.getKey().equals(vodInfo.playFlag)) {
                    continue;
                }
                List<VodInfo.VodSeries> l = e.getValue();
                if (l == null || l.isEmpty() || l == targetList) {
                    continue;
                }
                others.add(seriesNames(l));
            }
            if (others.isEmpty()) {
                return -1;
            }
            return EpisodeNameMatcher.probeFactFromOtherSources(
                    currentSeries.name, srcNames, seriesNames(targetList), others);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int findMatchingEpisodeIndexRaw(VodInfo.VodSeries currentSeries, List<VodInfo.VodSeries> targetList) {
        if (targetList == null || targetList.isEmpty()) {
            return -1;
        }
        if (targetList.size() == 1) {
            return 0;
        }
        if (currentSeries == null || TextUtils.isEmpty(currentSeries.name)) {
            return -1;
        }
        final List<String> targetNames = seriesNames(targetList);
        final String currentName = currentSeries.name;

        // ---------- 第1层：本地同域精确匹配 ----------
        // 含"前导零 / 多字少字"等同域变体，全部在此解决，不联网。
        int matchedIndex = EpisodeNameMatcher.findIndex(currentName, targetNames);
        if (matchedIndex >= 0) {
            return matchedIndex;
        }

        // ---------- 第1.5层：同日期多段的组内位置对齐 ----------
        // 场景：旧源把某天切成"上/中/下"，新源写成"上/无后缀/下"。无后缀≡上，
        // 于是新源的同日组语义变成[上,上,下]，"中"用名字匹配会落空。
        // 两侧同日组条数相同时，按组内位置（第 N 段对第 N 段）定位即可。
        // 必须在跨域联网之前做：这是纯本地、零延迟、且确定性的判断。
        List<VodInfo.VodSeries> playList = getPlayingSeriesList();
        int playIndex = indexOfSeries(playList, currentSeries);
        if (playIndex < 0 && vodInfo != null && vodInfo.playIndex >= 0 && vodInfo.playIndex < playList.size()) {
            playIndex = vodInfo.playIndex;
        }
        if (playIndex >= 0 && !playList.isEmpty()) {
            int byGroup = EpisodeNameMatcher.alignByGroupPosition(
                    currentName, playIndex, seriesNames(playList), targetNames);
            if (byGroup >= 0) {
                return byGroup;
            }
        }

        // ---------- 第2层：跨域 → 权威换算 ----------
        // 严格门控：只有真正跨域（日期 ↔ 期数）才走这一步；同域失败属正常无对应，不联网。
        if (EpisodeNameMatcher.needsCrossDomainResolve(currentName, targetNames)) {
            // 2-a 离线字典：仅在"长期无网"部署下打开时抢占（零延迟、无需联网）
            int dictIndex = resolveByOfflineDict(currentName, targetNames);
            if (dictIndex >= 0) {
                return dictIndex;
            }
            // 2-b 离线正片秩对齐（★ 切到特辑 bug 的主修复，现为首选）：
            //     用"正片簇序"在本地把期数与日期两种命名域对齐——零延迟、零外部依赖，
            //     不受直连站点波动影响。此前的顺序是"联网优先、离线兜底"，实测
            //     直连站点（zyshow.net）波动大（约半数首请求超时/重置），导致每次
            //     跨域切源都要先白等联网预算才落到离线层；且站点完全不可达时
            //     裸下标兜底会把「第5期上」静默切到「20260508泳池特辑」这类错位条目。
            //     簇序对齐自带周更快照校验（±3 天）与伪正片过滤，不确定时返回 -1，
            //     此时才轮到下面的联网权威换算。
            List<String> rankSourceNames = getRankSourceList(currentName);
            int rankSourceIndex = rankSourceNames.indexOf(currentName);
            int byRank = EpisodeNameMatcher.alignByMainFeatureRank(
                    currentName, rankSourceIndex, rankSourceNames, targetNames);
            if (byRank >= 0) {
                return byRank;
            }
            // 2-c 直连站点：离线对齐失败时的权威修正（缓存命中 0ms，未命中约 800ms）
            int online = tryResolveCrossDomainNow(currentName, targetNames);
            if (online >= 0) {
                return online;
            }
        }

        // ---------- 第3层：本地按序兜底（猜测，仅在列表构成一致时成立）----------
        List<VodInfo.VodSeries> sourceList = getPlayingSeriesList();
        int sourceIndex = indexOfSeries(sourceList, currentSeries);
        if (sourceIndex < 0 && vodInfo != null && vodInfo.playIndex >= 0 && vodInfo.playIndex < sourceList.size()) {
            sourceIndex = vodInfo.playIndex;
        }
        if (sourceIndex >= 0 && !sourceList.isEmpty()) {
            // 跨域时按序对齐的"同下标"没有依据（源里可能混入预告/花絮/特别篇），
            // 此处仅允许同域兜底，跨域交给上面的直连层。
            int aligned = EpisodeNameMatcher.alignByOrder(
                    sourceIndex, seriesNames(sourceList), targetNames, false);
            if (aligned >= 0) {
                return aligned;
            }
        }
        return matchedIndex;
    }

    /**
     * 跨域同步换算：给直连一个有限的等待预算，能拿到就说，拿不到立刻放行。
     *
     * <p>之所以允许短暂阻塞：切源本身是同步决策，必须当场决定播哪一集。
     * 预算内返回的是站点权威映射，远优于"同下标"猜测；缓存命中时开销为 0。
     * 超时（无网/慢网）立即返回 -1，退回第3层兜底，用户几乎无感。</p>
     *
     * <p><b>两个方向都要处理</b>：</p>
     * <ul>
     *   <li><b>正向</b>（当前名有日期，目标源是期数式）：
     *       先按日期直接落位（覆盖日期式与 {@code 第YYYYMMDD期} 式）；
     *       不行再用日期查期数找 {@code 第N期}；最后再用日期兜一次。</li>
     *   <li><b>反向</b>（当前名是期数式、无日期，目标源是日期式）：
     *       用期数查日期，再在目标源里按日期定位。</li>
     * </ul>
     * <p>早期实现只覆盖了正向的"查期数"一步，反向（{@code 第1期上} → {@code 20260404上}）
     * 会因 {@link #extractDateFromName} 取不到日期而整条链路失效，
     * 只能退到"按位置猜"导致错配。</p>
     *
     * @return 目标源下标；未命中/超时返回 -1
     */
    private int tryResolveCrossDomainNow(String currentName, List<String> targetNames) {
        try {
            if (!EpisodeOnlineResolver.OnlineResolveConfig.isEnabled()) return -1;
            String showName = vod_name == null ? "" : vod_name.trim();
            if (TextUtils.isEmpty(showName)) return -1;

            long budget = EpisodeResolveInitializer.getCrossDomainTimeoutMs();
            // 预算为 0：不做阻塞等待，改由异步回调后置修正（见 applyOnlineResolvedIndex）
            if (budget <= 0) return -1;

            // 正向：当前名含日期 → 查期数
            final String date = extractDateFromName(currentName);
            if (!TextUtils.isEmpty(date)) {
                // ① 优先按日期直接落位：目标源若是日期式（20260404上 / 第20260404期），
                //    这一步就够了，且能保持正片/非正片口径一致。
                int byDate = EpisodeNameMatcher.findIndexByDate(date, targetNames, currentName);
                if (byDate >= 0) {
                    return byDate;
                }
                // ② 目标源是"第N期"式：优先用<b>目标源自己的日期锚</b>换算。
                //    站点(zyshow)的"第N期"按自然周编号，与源侧"按播出次数"口径不同，
                //    直接套站点期数会错配（20260411→第2期上）。日期锚不依赖外部口径。
                int byAnchor = EpisodeNameMatcher.findIndexByDateAnchor(date, targetNames, currentName);
                if (byAnchor >= 0) {
                    return byAnchor;
                }
                // ③ 目标源自身无日期可用时，才退回站点期数（尽力而为）
                int episode = EpisodeOnlineResolver.resolveWithin(showName, date, budget);
                if (episode <= 0) return -1;
                int byEpisode = EpisodeNameMatcher.findIndexByEpisode(currentName, episode, targetNames);
                if (byEpisode >= 0) {
                    return byEpisode;
                }
                // ④ 兜底：目标源写「第YYYYMMDD期」时 parse() 判为日期域，
                //    findIndexByEpisode 会整条跳过；此处用源名自带日期再落一次。
                return EpisodeNameMatcher.findIndexByDate(date, targetNames, currentName);
            }

            // 反向：当前名是期数式（无日期）→ 查日期
            return resolveBackwardCrossDomain(showName, currentName, targetNames, budget);
        } catch (Throwable ignored) {
            // 任何异常都静默降级，绝不因匹配逻辑影响切源
            return -1;
        }
    }

    /**
     * 反向跨域换算：当前名是<b>期数式</b>（如 {@code 第1期上}，不含日期），
     * 而目标源是<b>日期式</b>（如 {@code 20260404上}）时，
     * 用"期数 → 播出日期"反查，再在目标源里按日期定位。
     *
     * <p><b>为什么必须有这条路</b>：正向换算需要 {@code currentName} 里含日期，
     * 但期数式名字没有日期，{@link #extractDateFromName} 返回空，
     * 整条联网链路直接失效，只能退到按位置猜。
     * 而两源集数往往差异很大（如旧源 36 条、新源 23 条），按比例一算就偏，
     * 用户看到"切源后跑到毫不相干的一集"。</p>
     *
     * <p><b>anchorDate 怎么取</b>：反向扫描需要一个起点日期来限定窗口。
     * 优先取<b>目标列表里第一条可识别的日期</b>——它必然是这季节目的
     * 某一期播出日，从它出发前后扫描最容易命中。</p>
     *
     * <p><b>命中后如何落位</b>：拿到日期 {@code D} 后，
     * 在目标列表里找首个日期等于 {@code D} 的条目（{@link EpisodeNameMatcher#findIndexByDate}），
     * 而不是回头再用期数匹配——因为目标源本身就没有期数。</p>
     *
     * @param showName    节目名
     * @param currentName 当前集名（期数式）
     * @param targetNames 目标源集名列表（日期式）
     * @param budget      联网等待预算（毫秒）
     * @return 目标源下标；未命中/超时返回 -1
     */
    private int resolveBackwardCrossDomain(String showName, String currentName,
                                           List<String> targetNames, long budget) {
        if (targetNames == null || targetNames.isEmpty()) return -1;
        // 当前名必须能解析出期数，否则反向无从谈起。
        // ★ 不能只看 domain == DOMAIN_ORDINAL：`第20260612期` 这类"期数式里嵌了日期"
        //   的写法会被 parse 按「日期优先」判成 DOMAIN_DATE，于是这条路径整体失效 ——
        //   而它恰恰是跨源对齐最常见的形态（实测 feifan / 360zy 侧全是这种写法）。
        //   因此这里改为用 extractOrdinal：只要名字里能解出真实期号即可，与 domain 无关。
        int curOrdinal = EpisodeNameMatcher.extractOrdinal(currentName);
        if (curOrdinal <= 0) return -1;
        // 目标列表必须是日期域主导，才是我们要处理的反向场景
        if (!EpisodeNameMatcher.isDateDominated(targetNames)) return -1;

        String anchor = EpisodeNameMatcher.firstDate(targetNames);
        if (TextUtils.isEmpty(anchor)) return -1;

        // 反向查询比正向慢（需逐天探测），给预算留出更宽裕的余量
        long reverseBudget = Math.max(budget, 1800L);
        // ★ 多日期查询：一个「期」可能跨越两天（上/下分段分两天播，如
        //   第2期上=20260411、第2期下=20260412）。只取单个日期会丢掉分段归属，
        //   于是"第2期上"可能被对到 20260412上。
        java.util.List<String> dates = EpisodeOnlineResolver.resolveDatesWithin(
                showName, curOrdinal, anchor, reverseBudget);
        if (dates != null && !dates.isEmpty()) {
            // 按「日期顺序 + 分集后缀」精确落位（第N期上→第1天、第N期下→第2天…）
            int byDates = EpisodeNameMatcher.findIndexByDates(dates, targetNames, currentName);
            if (byDates >= 0) {
                return byDates;
            }
            // 分段无法区分时，退回单日期（保持旧行为：优先正片口径）
            for (String d : dates) {
                int byDate = EpisodeNameMatcher.findIndexByDate(d, targetNames, currentName);
                if (byDate >= 0) {
                    return byDate;
                }
            }
            return -1;
        }
        // 兼容回退：多日期查询不可用时，仍走原来的单日期路径
        String date = EpisodeOnlineResolver.resolveDateWithin(
                showName, curOrdinal, anchor, reverseBudget);
        if (TextUtils.isEmpty(date)) return -1;
        // 带入 currentName 保持正片/非正片口径一致：
        // 当前是正片时，不会落到同日期但实为特辑/加更的条目上（那属于错配）。
        return EpisodeNameMatcher.findIndexByDate(date, targetNames, currentName);
    }

    /**
     * 离线字典换算（<b>默认不参与链路</b>）：把"当前集的播出日期"换算成"期数"，
     * 再去目标源里找对应的"第N期"。仅跨域（日期→期数）时有意义。
     *
     * <p>保留此实现是为了兼容"确实长期无网"的部署：通过
     * {@link EpisodeResolveInitializer#setOfflineDictEnabled(boolean)} 打开后，
     * 可用本地 {@code variety_dict.json} 顶替直连。默认关闭时本方法恒返回 -1，
     * 链路顺序不受影响。</p>
     *
     * @return 目标源下标；未命中返回 -1
     */
    private int resolveByOfflineDict(String currentName, List<String> targetNames) {
        if (!EpisodeResolveInitializer.isOfflineDictEnabled()) return -1;
        if (!EpisodeDict.isReady() || TextUtils.isEmpty(currentName) || targetNames == null) return -1;
        String showName = vod_name == null ? "" : vod_name.trim();
        if (TextUtils.isEmpty(showName)) return -1;
        if (!EpisodeNameMatcher.needsCrossDomainResolve(currentName, targetNames)) return -1;
        String date = extractDateFromName(currentName);
        if (TextUtils.isEmpty(date)) return -1;
        int episode = EpisodeDict.lookup(showName, date);
        if (episode <= 0) {
            // 节目名在字典里可能有别名后缀，退一步按"日期精确命中"扫全部键
            episode = EpisodeDict.lookupBySeriesName(showName, currentName);
        }
        if (episode <= 0) return -1;
        int byEpisode = EpisodeNameMatcher.findIndexByEpisode(currentName, episode, targetNames);
        if (byEpisode >= 0) {
            return byEpisode;
        }
        // 目标源若写「第YYYYMMDD期」，findIndexByEpisode 会跳过（判为日期域）；
        // 这里用当前名自带的日期兜一次，覆盖该写法。
        return EpisodeNameMatcher.findIndexByDate(date, targetNames, currentName);
    }

    /** 从任意集名中提取 8 位播出日期（YYYYMMDD），供跨域换算复用。 */
    private static String extractDateFromName(String name) {
        return EpisodeDict.extractDate(name);
    }

    /**
     * 跨域直连的<b>异步后置修正</b>入口。
     *
     * <p>适用场景：{@link #tryResolveCrossDomainNow} 的同步预算用尽（或预算被设为 0）后，
     * 仍然想让正确答案最终生效。此时先按旧源下标落地播出（保证"切源不断流"），
     * 直连结果回来后再静默改写到正确集。</p>
     *
     * <p>仅在真正跨域（日期 ↔ 第N期）时发起；同域（含前导零、多字少字）一律本地解决，不联网。</p>
     *
     * @param currentName  发起查询时"正在播的那一集"的集名（旧源写法）
     * @param targetFlag   目标线路名
     * @param landedIndex  本次切源后本地匹配落地的下标；用于回调时确认用户未再操作
     */
    private void tryOnlineCrossDomainResolve(final String currentName, final String targetFlag,
                                             final List<VodInfo.VodSeries> targetList, final int landedIndex) {
        if (TextUtils.isEmpty(currentName)) return;
        if (targetList == null || targetList.size() < 2) return;
        if (!EpisodeOnlineResolver.OnlineResolveConfig.isEnabled()) return;
        final List<String> targetNames = seriesNames(targetList);
        // 严格门控：只有真正跨域（日期↔期数）才联网；同域（含前导零、多字少字）一律本地解决。
        if (!EpisodeNameMatcher.needsCrossDomainResolve(currentName, targetNames)) return;
        final String showName = vod_name == null ? "" : vod_name.trim();
        if (TextUtils.isEmpty(showName)) return;
        final String date = extractDateFromName(currentName);
        if (TextUtils.isEmpty(date)) return;
        final Executor executor = detailFallbackSearchExecutor != null
                ? detailFallbackSearchExecutor : searchExecutorService;
        EpisodeOnlineResolver.resolveAsync(showName, date, executor, new EpisodeOnlineResolver.Callback() {
            @Override
            public void onResult(final int episode) {
                if (episode <= 0) return; // 静默降级
                // 先按期数找（目标源为 第N期 式）；找不到再用日期兜
                // （目标源可能是 第YYYYMMDD期 式，findIndexByEpisode 会跳过）
                int idx = EpisodeNameMatcher.findIndexByEpisode(currentName, episode, targetNames);
                if (idx < 0) {
                    idx = EpisodeNameMatcher.findIndexByDate(date, targetNames, currentName);
                }
                if (idx < 0) return;
                final int resolved = idx;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyOnlineResolvedIndex(targetFlag, targetList, landedIndex, resolved);
                    }
                });
            }
        });
    }

    /**
     * 在线查询命中后回主线程应用。
     *
     * <p>防串台校验：只有当播放状态仍停留在本次切源落地的位置
     * （线路 == {@code targetFlag} 且下标 == {@code landedIndex}）时才允许改写。
     * 用户若已手动切线路或选集，说明他已有新意图，本次异步结果一律丢弃。</p>
     */
    private void applyOnlineResolvedIndex(String targetFlag, List<VodInfo.VodSeries> targetList,
                                          int landedIndex, int index) {
        if (isFinishing()) return;
        if (vodInfo == null || targetList == null || index < 0 || index >= targetList.size()) return;
        if (!TextUtils.equals(vodInfo.playFlag, targetFlag)) return;
        if (vodInfo.playIndex != landedIndex) return;

        vodInfo.playIndex = index;
        for (int i = 0; i < targetList.size(); i++) {
            VodInfo.VodSeries s = targetList.get(i);
            if (s != null) s.selected = (i == index);
        }
        routeSwitchSeries = targetList.get(index);
        seriesAdapter.notifyDataSetChanged();
        setTvPlayUrl(targetList.get(index).url);
    }

    private int indexOfSeries(List<VodInfo.VodSeries> list, VodInfo.VodSeries series) {
        if (list == null || series == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            VodInfo.VodSeries s = list.get(i);
            if (s == series) return i;
            if (s != null && series.url != null && series.url.equals(s.url)) return i;
        }
        return -1;
    }

    private List<String> seriesNames(List<VodInfo.VodSeries> list) {
        List<String> names = new ArrayList<>();
        if (list == null) return names;
        for (VodInfo.VodSeries s : list) {
            names.add(s == null || s.name == null ? "" : s.name);
        }
        return names;
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
        // ★ 跨域安全：目标名与新列表若分属不同命名域（期数 ↔ 日期），
        //   直接比 ordinal 数值没有意义（|20260411 - 2| 恒为巨值，会挑到任意条目）。
        //   此时改用 score（含跨域判等）挑最佳，而不是比数值距离。
        int targetNum = extractEpisodeNumber(target.name);
        boolean targetIsDate = EpisodeNameMatcher.dateOf(target.name) > 0;
        boolean targetHasNum = targetNum >= 0;
        int bestIndex = -1, bestDiff = Integer.MAX_VALUE, bestScore = 0;
        for (int i = 0; i < list.size(); i++) {
            VodInfo.VodSeries s = list.get(i);
            if (s == null || TextUtils.isEmpty(s.name)) continue;
            boolean sameDomain = (EpisodeNameMatcher.dateOf(s.name) > 0) == targetIsDate;
            if (sameDomain && targetHasNum) {
                int n = extractEpisodeNumber(s.name);
                if (n < 0) continue;
                int diff = Math.abs(n - targetNum);
                if (diff < bestDiff) { bestDiff = diff; bestIndex = i; }
            } else {
                int sc = EpisodeNameMatcher.score(target.name, s.name);
                if (sc > bestScore) { bestScore = sc; bestIndex = i; }
            }
        }
        return bestIndex >= 0 ? bestIndex : clamped;
    }

    private int getEpisodeMatchScore(String currentName, int currentEpisode, String targetName) {
        // 统一委托 EpisodeNameMatcher：日期/第N期分域比较，避免 20260809期 与 第8期 无法互认
        return EpisodeNameMatcher.score(currentName, targetName);
    }

    private int extractEpisodeNumber(String name) {
        // 统一委托 EpisodeNameMatcher：日期优先识别，避免 8 位日期被当成集数号
        return EpisodeNameMatcher.extractOrdinal(name);
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
        LOG.sw("[FB] migrate " + oldRecord.sourceKey + "/" + oldRecord.id + " ep=" + oldSeriesName
                + " -> " + newSourceKey + "/" + newVodId + " ep=" + newSeriesName
                + " oldTime=" + oldTime + " newVal=" + newVal);
        if (oldTime > 0) {
            PlayProgressManager.save(newSourceKey, newVodId, newFlag, newIndex, newSeriesName, oldTime);
            // ★ 防丢：仅当新集名确实是旧集名的"同一集"时才写（同集名，或由匹配器判定同集）。
            //   名称不同且无法互认时，额外保底写一份"按旧集名"的记录——
            //   这样即便本次落点有偏差，用户回到该集仍能拿回时间，不会凭空丢记忆。
            if (!TextUtils.equals(oldSeriesName, newSeriesName)
                    && EpisodeNameMatcher.findIndex(oldSeriesName,
                        java.util.Collections.singletonList(newSeriesName)) < 0) {
                long existing = PlayProgressManager.get(newSourceKey, newVodId, newFlag, newIndex, oldSeriesName);
                if (existing <= 0) {
                    PlayProgressManager.save(newSourceKey, newVodId, newFlag, newIndex, oldSeriesName, oldTime);
                }
            }
        }
    } catch (Throwable th) {
        th.printStackTrace();
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
        // ★ 跨域安全：episodeNote 与目标线路分属不同命名域（期数 ↔ 日期）时，
        //   比 ordinal 数值距离毫无意义（|20260411 - 2| 恒为巨值），改用 score 判等。
        {
            boolean noteIsDate = EpisodeNameMatcher.dateOf(episodeNote) > 0;
            int targetNum = extractEpisodeNumber(episodeNote);
            boolean targetHasNum = targetNum >= 0;
            int bestFlagPos = -1, bestIndex = -1, bestDiff = Integer.MAX_VALUE, bestScore = 0;
            for (int pos = 0; pos < flagOrder.size(); pos++) {
                List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(flagOrder.get(pos));
                if (list == null || list.isEmpty() || list.size() == 1) continue;
                for (int i = 0; i < list.size(); i++) {
                    VodInfo.VodSeries s = list.get(i);
                    if (s == null || TextUtils.isEmpty(s.name)) continue;
                    boolean sameDomain = (EpisodeNameMatcher.dateOf(s.name) > 0) == noteIsDate;
                    if (sameDomain && targetHasNum) {
                        int n = extractEpisodeNumber(s.name);
                        if (n < 0) continue;
                        int diff = Math.abs(n - targetNum);
                        if (diff < bestDiff) { bestDiff = diff; bestIndex = i; bestFlagPos = pos; }
                    } else {
                        int sc = EpisodeNameMatcher.score(episodeNote, s.name);
                        if (sc > bestScore) { bestScore = sc; bestIndex = i; bestFlagPos = pos; }
                    }
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
        // 切源连跳：一站一条地写历史，会挤占历史上限（把其它影片的记录顶掉），
        // 也让"最近观看"里塞满同一部片。切源本来就是同一部片换站，只保留当前这一条。
        // 旧源的播放进度会在此之前被整体迁到本源，不会因合并而丢失。
        if (detailFallbackActive) {
            int merged = RoomDataManger.mergeSameNameVodRecords(sourceKey, vodInfo);
            if (merged > 0) {
                LOG.sw("[FB] mergeSameName removed=" + merged + " keep=" + sourceKey + "/" + vodInfo.id);
            }
        }
        RoomDataManger.insertVodRecord(sourceKey, vodInfo);
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
    }

    @Override
    protected void onDestroy() {
        resetDetailFallback();
        super.onDestroy();
        // ★ 兜底静音：详情页销毁意味着这一轮播放彻底结束（返回首页 / 退出播放页），
        //   绝不能把声音留在后台。
        //
        //   为什么需要它：正常的暂停链是 DetailActivity.onPause → PlayFragment.onPause
        //   → VideoView.pause()。但历史上有过一个残留布尔标记（exitingPreview）会在
        //   某些返回路径上让这一链断掉，表现就是「退回 TVBox 首页后仍有声音」。
        //   主链路已在 PlayFragment.onPause() 修好，这里再加一道与任何标记都无关的
        //   兜底：只要 Activity 要销毁了，就先把播放器停掉，再去 release。
        //   顺序很重要 —— 必须在 releasePlayFragment() 之前，否则 Fragment 已经
        //   被移除、mVideoView 已置空，就没有对象可停了。
        try {
            if (playFragment != null) {
                playFragment.stopPlaybackForDetach();
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
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
        dismissSwitchingSourceToast();
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
        // 方向5：把「首帧到达」事件接到搜索会话的起播保护窗上 ——
        // 首帧一出就让聚合搜索恢复派发（不等 5s 兜底）。
        playFragment.setStartupGuardListener(new PlayFragment.StartupGuardListener() {
            @Override
            public void onFirstFrame() {
                SearchSession.getShared().endStartupGuardOnFirstFrame();
            }
        });
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
