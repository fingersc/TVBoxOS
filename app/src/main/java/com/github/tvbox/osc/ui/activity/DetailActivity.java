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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.FilmIdentity;
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
    /**
     * ★ v99-r57：后台（静默预热 / 主搜索超时移交的剩余源）专用请求 tag。
     *
     * <p>必须与 {@link #DETAIL_FALLBACK_SEARCH_TAG} 分开：主搜索收尾会
     * {@code cancelTag(DETAIL_FALLBACK_SEARCH_TAG)}，同 tag 的后台请求会被一起取消。</p>
     */
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
     * ★ v99-r57：正在加载候选详情（{@code detailFallbackLoadingCandidate}）时的补位并发上限。
     *
     * <p><b>为什么必须有这一档</b>：改动前 {@link #scheduleDetailFallbackSearch()} 开头是
     * {@code if (detailFallbackLoadingCandidate) return;} —— 加载详情期间<b>一律不补位</b>，
     * 且没有重试。真机实测（2026-10-25 04:10，冷启动）101 个源只发出 25 个就永久停摆：
     * <pre>
     * 04:10:12.553  dispatch n=20
     * 04:10:12.744~13.190  dispatch n=1 ×5        （累计 25）
     * 04:10:13.224  loadDetail -> api_23           ← 进入 loadingCandidate
     * 04:10:15~38   pick ×8（2 个站来回）           ← 期间 dispatch 0 次
     * </pre>
     * 于是候选池被锁死在 2，用户只能在这 2 个站之间打转。</p>
     *
     * <p><b>取值 8 的依据</b>（设备：小米盒子3增强版，无有线网口、纯 802.11ac 2×2）：
     * 加载窗口实测约 2.4s（loadDetail 04:10:13.224 → pick 04:10:15.644），
     * 该窗口内并发 8 可问约 {@code 2.4s ÷ 0.8s(中位) × 8 ≈ 24} 个源（改前是 <b>0</b>）。
     * 8 = 空闲 20 的 40%，对 WiFi 与主线程回调频率的压力均可控；窗口一过即回到 20。</p>
     */
    private static final int DETAIL_FALLBACK_MAX_SEARCH_BUSY = 8;
    /**
     * ★ v99-r57：忙时被挡下的补位，延迟这么久后重试一次。
     *
     * <p>保证「没问过的源最终一定会被问到」—— 补位不再依赖"结果回调恰好落在空闲时刻"
     * 这种运气（第二次能发 65 个只是碰巧，不是设计）。</p>
     */
    private static final long DETAIL_FALLBACK_BUSY_REFILL_DELAY_MS = 300L;
    /**
     * ★ v99-r58：攒池引擎的<b>后台并发档</b>（取代原 {@code DETAIL_FALLBACK_PREHEAT_MAX_SEARCH}）。
     *
     * <p>r58 把「切源主搜索」与「静默预热」合并成一条常驻队列，唯一的变量就是并发档位。
     * 后台档 5 是与<b>前台播放</b>并存的那档：请求都是一次性小报文，但并发太高仍会与视频流
     * 抢 WiFi（设备：小米盒子3增强版，纯 802.11ac 2×2，无有线口），故维持原预热的值 5。</p>
     */
    private static final int DETAIL_FALLBACK_POOL_CONC_BACKGROUND = 5;
    /**
     * ★ v66：攒池启动延迟（详情加载成功后）。
     *
     * <p>错开「起播高峰」：详情返回 → 解析 → 拉流起播的前 1.5 秒是带宽最紧的
     * 窗口，此刻扎进去会拖慢首帧。等首帧出来、播放进入稳态再慢慢补，
     * 用户点切源时池子通常已经攒了一部分。</p>
     */
    private static final long DETAIL_FALLBACK_PREHEAT_DELAY_MS = 1500L;
    /**
     * ★ v99-r58：攒池任务的<b>兜底硬闸</b>（取代原 {@code DETAIL_FALLBACK_PREHEAT_TIMEOUT_MS}）。
     *
     * <p>攒池是尽力而为的后台优化，不能无限期挂着：到点还没问完就停止派发、掐断在途，
     * 已收回来的候选照常入池。退出详情页（彻底复位）仍会立刻掐断，不会在后台空跑。</p>
     *
     * <p><b>取值 120s（用户拍板）</b>。真机实测（2026-10-25 05:23，101 个源，并发 5）：
     * 20s 只问到 28/101，反推平均单源占用 {@code 5 × 20s ÷ 28 ≈ 3.6s}，
     * 走完 101 个源需要 {@code 101 ÷ 5 × 3.6 ≈ 73s}。取 120s 是为了覆盖更差的网络，
     * 正常情况下由队列跑空自然收尾，硬闸只是异常慢网的保险。</p>
     */
    private static final long DETAIL_FALLBACK_POOL_FILL_TIMEOUT_MS = 120000L;
    /**
     * ★ v99-r58：单部影片候选池的<b>条数软上限</b>（用户拍板 40）。
     *
     * <p><b>为什么必须加上限</b>：r58 让池子趋向"问满全部源"，热门片可能攒到五六十条。
     * 落盘是精简三元组（约 40 字节/条）尚可控，但
     * {@link #detailFallbackCache} 里存的是完整 {@code Movie.Video}，且
     * {@link #FALLBACK_CACHE_MAX_ENTRIES}(40) 是<b>部数</b>上限 —— 两者相乘才决定真实占用。
     * 取 40：一圈切源根本用不到这么多站（超过 40 说明该片到处都有，多存无益），
     * 超出部分按 {@link #detailFallbackSourceScore} 从低到高剔除。</p>
     */
    private static final int DETAIL_FALLBACK_POOL_MAX_CANDIDATES = 40;
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
    // 抽出去是为了让 RoomDataManger（删除历史连带清池）能直接依赖仓储层，
    // 而不是反向依赖 UI 层的 Activity。
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
    /**
     * 当前详情页是否为「索引源」（只提供影片资料、不提供任何播放线路的源，例如豆瓣影视库）。
     *
     * <p>由 spider 在 {@code detailContent} 里以 {@code action=indexOnly} 自报，见
     * {@link #applyIndexOnlyUi(boolean)}。为 false 时下面所有新增逻辑一律不生效，
     * 行为与本补丁之前逐字节一致。
     */
    private boolean indexOnlyDetail = false;
    /** 剧集区空占位里的提示文案控件；索引源下改写成「点搜索片源」。 */
    private TextView tvEmptyPlaylistTip;
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
        tvEmptyPlaylistTip = findViewById(R.id.tvEmptyPlaylistTip);
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
                // ★ 索引源没有播放线路：「立即播放」在这里的含义是"我要马上看"，
                //   即去全网自动找一个能播的站跳过去（走切源流程；到目标站后由用户起播）。
                //   不能落到下面的 jumpToPlay()：它剧集列表为空会静默 return，
                //   而 showPreview 为真时还会先 enterFullPreview() 进一个空播放器黑屏。
                if (indexOnlyDetail) {
                    LOG.sw("[IDX] 立即播放 -> 切源 key=" + sourceKey + " id=" + vodId);
                    startDetailFallbackFromMenu();
                    return;
                }
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
                openQuickSearchDialog();
            }
        });
        tvChangeSource.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                // ★ 索引源下这个按钮的文案是「搜索片源」：改为弹聚合搜索，由用户自己挑站点。
                //   （"自动帮你挑一个站跳过去"放在「立即播放」上，见 tvPlay 的索引源分支。）
                //   非索引源一律走原来的切源，行为不变。
                if (indexOnlyDetail) {
                    LOG.sw("[IDX] quick search dialog from 搜索片源");
                    openQuickSearchDialog();
                    return;
                }
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
                // ★ seriesMap 可能为 null（索引源 / 详情没带回任何线路）。
                //   原写法直接 .size() 会 NPE：过去空详情会被自动切走、用户点不到这，
                //   现在索引源页面会原地停住并可交互，这条路径从此真的会被点到，故补判空。
                if (vodInfo != null && vodInfo.seriesMap != null && vodInfo.seriesMap.size() > 0) {
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
                        // ★ v99-r10/r11：换线路与换源同义（都为更快的播放）——
                        //   旧线路「最近 N 条」进度迁到新线路（覆盖新线路原有），然后旧线路整个抛弃。
                        //   否则同一集会同时留下「旧线路 + 新线路」两条记录，
                        //   而裁剪是按「每片最多 N 条记录」算的，等于白占一半名额。
                        //   ★ 迁移要在 saveCurrentProgressNow() 之后：当前这一集的进度是刚写的，
                        //     必须能被当作「最近的一条」一起迁走。
                        try {
                            List<String> epNames = new ArrayList<>();
                            for (VodInfo.VodSeries s : newSeriesList) {
                                if (s != null && !TextUtils.isEmpty(s.name)) {
                                    epNames.add(s.name);
                                }
                            }
                            PlayProgressManager.migrateByFlag(sourceKey, vodId, actualOldFlag, newFlag, epNames);
                        } catch (Throwable th) {
                            LOG.e("migrateByFlag fail", th);
                        }
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
        // v84：容器里装的是 SurfaceView，clipToOutline 会把画面裁成黑块（Surface 不参与 outline 裁剪）。
        //       圆角交给上面那层透明的 Block 视图即可，容器保持不裁剪。
        llPlayerFragmentContainer.setClipToOutline(false);
        llPlayerFragmentContainer.setOutlineProvider(null);
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
        // v98 P1-r2：容器装的是 SurfaceView，clipToOutline 会把画面裁成黑块。
        //       此前 applyPreviewRoundCorners() 在 init 时已关掉，但这里 setFullPreview()
        //       每次切源都会重新启用 ⇒ 切源后黑色遮罩一直都在。容器永远不裁。
        llPlayerFragmentContainer.setClipToOutline(false);
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
                    // ★ v66：锚的兜底设定 —— 有些入口（如收藏）进详情时 vod_name 为空，
                    //   首次详情返回后用真实片名补设锚（只设一次，切源后不再改）。
                    //   注意用 fallbackResult 区分：切源内的详情返回不重设锚。
                    if (!fallbackResult && TextUtils.isEmpty(detailFallbackAnchorTitle)) {
                        detailFallbackAnchorTitle = mVideo.name == null ? "" : mVideo.name.trim();
                    }
                    // ★ v66：记录「基准资源」快照（同站多资源择优用，见 detailFallbackRefVideo）。
                    //   同样只在首轮（非切源内的详情返回）记录一次。
                    if (!fallbackResult && detailFallbackRefVideo == null) {
                        detailFallbackRefVideo = snapshotReference(mVideo);
                        // ★ v68：诊断日志 —— 基准是否记上是同站择优是否生效的总闸门，
                        //   红牛案例的 ref=none 正是缺这条链路的证据。
                        LOG.sw("[FB] refRecorded id=" + (mVideo.id == null ? "?" : mVideo.id)
                                + " source=" + mVideo.sourceKey
                                + " pic=" + (TextUtils.isEmpty(mVideo.pic) ? "-" : shortHash(mVideo.pic))
                                + " year=" + mVideo.year);
                    }
                    // ★ v66：约一次静默预热（延迟错开起播高峰；切源内的详情返回不约）。
                    //   预热把候选池提前攒厚，用户点切源时就是厚池。
                    if (!fallbackResult) {
                        scheduleDetailFallbackPoolFill();
                    }
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

                    // ★ 索引源识别必须在剧集区分支之前：下面 seriesMap 为空时会走
                    //   handleNoPlayableDetail()，那是给「播放源」设计的坏死判定 ——
                    //   对索引源必须先自报身份，否则资料页会被自动切走。
                    applyIndexOnlyUi("indexOnly".equals(mVideo.action));

                    if (vodInfo.seriesMap != null && vodInfo.seriesMap.size() > 0) {
                        // ★ v67：首轮记录基准集数（同站多资源择优的兜底信号，见字段注释）
                        if (!fallbackResult && detailFallbackRefEpisodes <= 0) {
                            detailFallbackRefEpisodes = countSeriesEpisodes(vodInfo);
                        }
                        // ★ v67：切源集数一致性校验 —— 元数据贫瘠的同站多条选错兜底。
                        //   两条同名剧封面/演员/年份/类型全同或全空时择优全 0 只能取第一条，
                        //   而第一条可能正是另一部（虎牙《人世间》153010 实测：20 集 vs 58 集）。
                        //   详情已回来、集数悬殊 ⇒ 把该候选记死并自动续切下一站，不渲染错剧集。
                        //   ★ v99-r67：原「三重门」（会话内 + 该站多候选 + 不足基准一半）已改：
                        //   门去掉（单候选站也要判）、阈值收到 1/4、失败只记软不记死。
                        if (fallbackResult && detailFallbackRefEpisodes > 0) {
                            int nowEpisodes = countSeriesEpisodes(vodInfo);
                            int sameStation = countSameStationCandidates(sourceKey);
                            if (shouldRejectByEpisodes(nowEpisodes)) {
                                LOG.sw("[FB] episodeMismatch source=" + sourceKey + " id=" + vodId
                                        + " now=" + nowEpisodes + " ref=" + detailFallbackRefEpisodes
                                        + " candidates=" + sameStation
                                        + " " + describeSeriesShape(vodInfo)
                                        + " => soft + next");
                                // ★★ v99-r67：只记软失败（本圈跳过），**不记死**。
                                //   集数不符只是「内容可能不完整」（未更新完 / 删减版），
                                //   与「年份矛盾」这种身份级证据不同；记死会让一个
                                //   连载中的正确源在该片里永久消失（会话内 + 落盘 24h 都受影响）。
                                detailFallbackSoftTriedKeys.add(sourceKey);
                                detailFallbackLoadingCandidate = false;
                                llLayout.removeCallbacks(detailFallbackDetailTimeout);
                                // ★ v99-r66：这条连跳此前<b>不占预算</b>（直接 loadNext 就返回），
                                //   真机实测 1.7 秒内连跳 3 个站、一次点击总共触发 7 次切源。
                                //   现在与 detailTimeout 走同一个预算：超限即提示并收尾。
                                if (!consumeFailoverBudget("episodeMismatch")) {
                                    return;
                                }
                                loadNextDetailFallbackSource();
                                return;
                            }
                            // ★ v99-r66：集数不等于基准却放行的（loose=1），此前混在 pass 里看不出来 ——
                            //   而那正是「切到了集数不符的片」的唯一记录。
                            LOG.sw("[FB] episodeCheck source=" + sourceKey + " id=" + vodId
                                    + " now=" + nowEpisodes + " ref=" + detailFallbackRefEpisodes
                                    + " candidates=" + sameStation
                                    + (nowEpisodes == detailFallbackRefEpisodes ? "" : " loose=1")
                                    + " " + describeSeriesShape(vodInfo)
                                    + " => pass");
                        }
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
                        // ★ v99-r78：同名 ≠ 同片 —— 恢复播放状态前必须过同片判据。
                        //   真机案例（2026-10-26「人世间」）：2022 正剧（李路/58集/剧情）与
                        //   2024 版（现代都市/82集·合集行）同名。点开 2024 版时本段按片名
                        //   恢复了 2022 版的播放位置（第3集）⇒ 详情页处处找不到「第3集」，
                        //   连环提示「没有当前这一集」。r74 的拦截挂在迁移侧（migrateSkip），
                        //   但本段只恢复 UI 状态、不写库，迁移可能根本不被调用
                        //   （remap 失败 ⇒ positionTrusted=false）⇒ 必须在此独立判定。
                        //   vodInfo 的 year/director 已由 setVideo(mVideo) 填充（详情解析侧，
                        //   非空可信）；库记录侧由写入时的同一链路保证。
                        if (vodInfoRecord != null
                                && !FilmIdentity.isSameFilm(vodInfo.year, vodInfo.director,
                                        vodInfoRecord.year, vodInfoRecord.director)) {
                            LOG.sw("[FB] sameNameRestoreSkip reason=otherFilm"
                                    + " cur=" + vodInfo.id + "/y" + vodInfo.year
                                    + " rec=" + vodInfoRecord.id + "/y" + vodInfoRecord.year
                                    + " => 换片，不恢复播放状态");
                            vodInfoRecord = null;
                            sameNameRestored = false;
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
                        // ★ v47 异步补位 —— 准备阶段。
                        //   本次跨域换算若因「主线程禁止同步联网」被放弃（见
                        //   tryResolveCrossDomainNow），落点就只剩"按序兜底"这一猜，
                        //   需要在 jumpToPlay() 之后交给异步通道补正。
                        //   ⚠ 旧源集名必须**在此刻**抓：紧接的 resetDetailFallbackKeepCache()
                        //   会走 resetDetailFallback(true) 把 detailFallbackEpisode 快照清空。
                        final String backfillFromName =
                                (detailFallbackEpisode != null && !TextUtils.isEmpty(detailFallbackEpisode.name))
                                        ? detailFallbackEpisode.name
                                        : (fallbackFromValid ? fallbackFromName : "");
                        // 读后即清：避免陈旧标志外溢到下一次落点
                        final boolean backfillNeeded = crossDomainSyncSkipped;
                        crossDomainSyncSkipped = false;
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

                        // ★★ v99-r76：该源到底有没有「当前正在看的那一集」？
                        //   口径（用户定稿）：**日期对不上就不是同一集** ⇒ 不该在这个源上就近猜一集播放。
                        //   · expectedName 为空 ⇒ 没有"当前这一集"的概念（首次进入/无快照）⇒ 放行
                        //   · 用户指定的源（搜索页点卡片 / 历史点卡片，fallbackResult=false）
                        //     ⇒ 暂停播放 + 提示「该源没有当前这一集」，不去乱切别的源
                        //   · 切源队列（fallbackResult=true）⇒ 软失败续切下一个源（独立预算 6 次）
                        //   · 预算耗尽 ⇒ 提示「没有其他源包含这一集」并停止（不播任何内容）
                        if (!TextUtils.isEmpty(expectedName)
                                && !EpisodeNameMatcher.isSameEpisodeStrict(expectedName, currentEpisode.name)) {
                            LOG.sw("[FB] alignNoEpisode source=" + sourceKey + " id=" + vodId
                                    + " expected=" + expectedName + " actual=" + currentEpisode.name
                                    + " userPicked=" + (fallbackResult ? 0 : 1)
                                    + " alignFailover=" + detailFallbackAlignFailover);
                            detailFallbackLoadingCandidate = false;
                            llLayout.removeCallbacks(detailFallbackDetailTimeout);
                            if (!fallbackResult) {
                                // 用户指定的源 ⇒ 暂停并提示，不擅自换到别的源
                                Toast.makeText(DetailActivity.this,
                                        "该源没有当前这一集", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (detailFallbackAlignFailover >= DETAIL_FALLBACK_MAX_ALIGN_FAILOVER) {
                                Toast.makeText(DetailActivity.this,
                                        "没有其他源包含这一集", Toast.LENGTH_SHORT).show();
                                finishDetailFallbackWithoutResult();
                                return;
                            }
                            detailFallbackAlignFailover++;
                            detailFallbackSoftTriedKeys.add(sourceKey);
                            loadNextDetailFallbackSource();
                            return;
                        }

                        // 跨源换源：把旧源记住的剧集内播放时间迁移到当前源对应集数，避免切源后时间记忆丢失
                        if (sameNameRestored) {
                            if (positionTrusted) {
                                migratePlaybackTimeFromOldSource(sourceKey, vodId, vodInfo.playFlag, vodInfo.playIndex,
                                        currentEpisode.name, vodInfoRecord,
                                        PlayProgressManager.collectAllEpisodeNames(vodInfo),
                                        PlayProgressManager.collectEpisodeCoords(vodInfo, vodInfo.playFlag));
                            }
                        } else if (fallbackFromValid) {
                            if (positionTrusted) {
                                migratePlaybackTimeFromOldSource(sourceKey, vodId, vodInfo.playFlag, vodInfo.playIndex,
                                        currentEpisode.name, null,
                                        PlayProgressManager.collectAllEpisodeNames(vodInfo),
                                        PlayProgressManager.collectEpisodeCoords(vodInfo, vodInfo.playFlag));
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
                        // ★ v47 异步补位 —— 触发点。
                        //   放在 jumpToPlay() 之后：此刻 playFlag / playIndex 才是最终落点，
                        //   而且"是否已起播"可以被 applyOnlineResolvedIndex 判到。
                        //   其余门控（在线开关 / 必须跨域 / 当前名须含日期 / 目标列表≥2）
                        //   全部由 tryOnlineCrossDomainResolve 自己承担，此处只答"该不该补"。
                        if (backfillNeeded && !TextUtils.isEmpty(backfillFromName)) {
                            LOG.sw("[FB] onlineBackfill schedule from=" + backfillFromName
                                    + " -> " + vodInfo.playFlag + "/" + vodInfo.playIndex);
                            tryOnlineCrossDomainResolve(backfillFromName, vodInfo.playFlag,
                                    playingSeriesList, vodInfo.playIndex);
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
                                    detailFallbackCacheVideo(detailFallbackAnchorTitle, sourceKey));
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
                                detailFallbackCacheVideo(detailFallbackAnchorTitle, sourceKey));
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
            // ★ v66：设定候选池的「片名锚」——进详情这一刻的片名原文，之后不随切源
            //   漂移（详见 detailFallbackAnchorTitle 注释）。空片名兜底为空串。
            if (!TextUtils.isEmpty(vod_name)) {
                detailFallbackAnchorTitle = vod_name.trim();
                // ★ v99-r56 取证：写法的不同写法应落到同一个池键（LOG.sw = TVBox-switch）
                LOG.sw("[FB] poolKey " + ShowTitleKey.familyKey(vod_name)
                        + " <- " + detailFallbackAnchorTitle);
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
        // ★ 索引源（豆瓣这类只有影片资料、没有播放线路的源）：这不是「坏死源」。
        //   原逻辑会调 startDetailFallback() 发起全网搜索并切到别的站，
        //   于是海报/评分/简介刚渲染出来就被另一个站的详情整页替换 ——
        //   而用户点开豆瓣想看的就是那份资料。
        //   停在本页，剧集区换成占位提示，找片源交给按钮行的「搜索片源」。
        if (indexOnlyDetail) {
            LOG.sw("[IDX] index-only detail, stay. key=" + sourceKey + " id=" + vodId);
            showIndexOnlyPlaceholder();
            return;
        }
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

    /**
     * 弹聚合搜索窗。
     *
     * <p>原本是 {@code tvQuickSearch} 匿名内部类里的点击体，原样抽成成员方法，
     * 供两个入口共用（索引源下的「搜索片源」与普通源下的「快速搜索」）。
     * 逻辑一字未改，只是换了位置，行为零变化。
     */
    private void openQuickSearchDialog() {
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

    /**
     * 索引源页面适配（只改文案/可见性；三处按钮的点击分支另在各自回调里判 indexOnlyDetail）。
     *
     * <p>索引源下按钮行变成两个找片入口：
     * <ul>
     *   <li>「搜索片源」（原「切源」位）→ 弹聚合搜索窗，各站命中列出来由用户挑；</li>
     *   <li>「立即播放」（原位保留）→ 走切源，后台全网找一个站自动跳过去。</li>
     * </ul>
     * 「快速搜索」在索引源下隐藏：它和「搜索片源」是同一个弹窗，完全重复，
     * 而这一行已经有五个按钮。别的源不受影响（GONE 只针对当前这一页）。
     *
     * <p>★ 向后兼容：不认识 {@code action=indexOnly} 的源（含原先的豆瓣 jar）
     * 传进来的是 false，文案与可见性复位成原样，三处点击逻辑也各自走原分支，
     * 行为与本补丁之前完全一致。每次详情返回都会调用一次，因此从豆瓣切到
     * 别的站时按钮会自动还原。
     */
    private void applyIndexOnlyUi(boolean on) {
        indexOnlyDetail = on;
        if (tvChangeSource != null) {
            tvChangeSource.setText(on ? "搜索片源" : "切源");
        }
        if (tvQuickSearch != null) {
            tvQuickSearch.setVisibility(on ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * 索引源详情页的剧集区占位。
     *
     * <p>与切源失败时的空态区别在于文案：切源失败是"暂时没有"，这里是"本来就没有"，
     * 并直接告诉用户下一步该点哪里。隐藏线路/剧集是为了让遥控器焦点落在按钮行上
     * （GONE 的 View 会被焦点系统跳过，布局里也没有指向这些控件的 nextFocusRight
     * 引用，不会出现焦点丢失）。
     *
     * <p>「立即播放」刻意**不隐藏**：它在索引源下改走切源（见 tvPlay 的 indexOnlyDetail
     * 分支），是按钮行上的第二个找片入口，删掉就少一条路。
     */
    private void showIndexOnlyPlaceholder() {
        mGridViewFlag.setVisibility(View.GONE);
        mGridView.setVisibility(View.GONE);
        tvSeriesGroup.setVisibility(View.GONE);
        mEmptyPlayList.setVisibility(View.VISIBLE);
        if (tvEmptyPlaylistTip != null) {
            tvEmptyPlaylistTip.setText("该源只提供影片资料 · 搜索片源＝自己挑，立即播放＝自动找");
        }
    }

    public boolean startDetailFallbackAfterLinesExhausted() {
        return startDetailFallback(false);
    }

    private void startDetailFallbackFromMenu() {
        detailFallbackDenyMsg = "";
        // ★ v99-r65：手动标志必须在调用期间为真、调用结束立刻复位。
        //   深层（无参 startDetailFallback）读它决定「要不要自己弹提示」；
        //   若不复位，之后自动续切进入深层方法时会误以为还是手动，从而漏掉 showDetailEmpty()。
        boolean started;
        detailFallbackManualSwap = true;
        try {
            started = startDetailFallback(true);
        } finally {
            detailFallbackManualSwap = false;
        }
        if (!started && !isFinishing() && !isDestroyed()) {
            // ★ v99-r65：文案由失败原因决定（原来三种原因共用一句「暂无可切换的片源」）。
            //   只有这里弹 Toast —— 深层分支把手动路径弹窗交给菜单统一处理，避免叠两个。
            String msg = TextUtils.isEmpty(detailFallbackDenyMsg)
                    ? "没有可切换的片源" : detailFallbackDenyMsg;
            LOG.sw("[FB] swapDeny msg=" + msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
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
        detailFallbackManualSwap = manual;
        detailFallbackDenyMsg = "";
        if (isFinishing()) {
            return false;
        }
        SourceBean currentSource = ApiConfig.get().getSource(sourceKey);
        if (currentSource == null) {
            // 原来这个分支和「片名缺失」「没有可搜源」共用一句「暂无可切换的片源」，
            // 且一个日志都不打 —— 真机上只能靠反推。
            detailFallbackDenyMsg = "当前源已不在配置里，请刷新源列表";
            LOG.sw("[FB] swapDeny reason=sourceGone key=" + sourceKey
                    + " manual=" + (manual ? 1 : 0));
            return false;
        }
        // ★★ v99-r65（用户选乙）：不再用 changeable=0 禁止「从该源切走」。
        //
        //   背景（真机 22:00 日志）：当前源「奶酪」在配置里是 changeable=0，点换源连点三次
        //   全部被这里拦下、一个日志都没有，而当时池子里明明有 6 个可用候选、32 个源全问过；
        //   换成「剧圈」当当前源后立刻就能切。用户看到的就是「明明有源却说没有」。
        //
        //   这个标志在房内**只有这一处使用**，从不参与「选目标」的过滤，却被拿来当起点闸门
        //   ⇒ 等于把用户困在一个播放不顺的源上。改为只记录、不阻断：
        //   作为「当前源」允许切走；是否要跳过一个 changeable=0 的站作为**目标**，
        //   是另一个话题（现在也没做过滤），不在本次范围。
        if (!currentSource.isChangeable()) {
            LOG.sw("[FB] swapAllow reason=changeable0 key=" + sourceKey
                    + "（v99-r65 起不再阻断，仅记录）");
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
            detailFallbackDenyMsg = "片名缺失，无法匹配其它片源";
            LOG.sw("[FB] swapDeny reason=noVodName manual=" + (manual ? 1 : 0));
            return false;
        }
        detailFallbackTitle = vod_name.trim();
        if (TextUtils.isEmpty(detailFallbackTitle)) {
            detailFallbackDenyMsg = "片名缺失，无法匹配其它片源";
            LOG.sw("[FB] swapDeny reason=blankTitle manual=" + (manual ? 1 : 0));
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
        // ★ v68：换片判定必须排除「首次切源」。
        //
        // detailFallbackCycleTitle 初值是空串，用户第一次点切源时它与任何片名都
        // 「不相等」⇒ 旧逻辑无条件走进换片分支，把首轮详情刚记录的基准资源
        // （refVideo/refEpisodes）、死站列表、圈记录全部清掉。后果正是虎牙/红牛
        // 案例的日志三证：所有站的 sameStationPick 全是 ref=none、scores 全 0、
        // episodeCheck 从不出现（refEpisodes=0）——同站多资源择优与集数校验
        // 双重失效，只能取第一条（另一部同名剧）。
        //
        // 修：cycleTitle 为空 = 首次切源 ⇒ 只记录片名，不做任何清理；
        // cycleTitle 非空且归一化片名变了 = 真换片 ⇒ 才走清理分支。
        // ★ v68：判定必须在赋值前算（赋值后 cycleTitle 就「相等」了，恒 false）。
        boolean reallySwitchFilm =
                needResetForFilmSwitch(detailFallbackCycleTitle, normalizedTitle);
        if (!TextUtils.equals(detailFallbackCycleTitle, normalizedTitle)) {
            detailFallbackCycleTitle = normalizedTitle;
            if (reallySwitchFilm) {
            // ★ v99-r66：这条分支会清空圈/死站/软失败/基准 —— 是「切源行为突然从第 0 名重来」的
            //   唯一解释，但此前一个日志都没有（r65 日志里 idx 突然回 0 查不出原因）。
            LOG.sw("[FB] filmSwitch title=" + detailFallbackCycleTitle + "（清空 cycle/dead/soft/ref）");
            detailFallbackCycleKeys.clear();
            detailFallbackNewCycle = true;
            // ★ 换片必须清 DeadKeys / SoftTriedKeys：
            // 「某站没有 A 片」不代表「没有 B 片」，跨片沿用拉黑列表会让新片的
            // 可切站点凭空变少（表现为「这部片只有两三个源能切」）。
            detailFallbackDeadKeys.clear();
            detailFallbackSoftTriedKeys.clear();
            // ★ v65：换片重新判据 —— 新片的池子厚薄与上一部片无关。
            detailFallbackPoolRefreshed = false;
            // ★ v66：换片 ⇒ 锚跟着换成新片的当前写法（此后该片的池/搜索/持久化
            //   全部以新锚为准，直到下一次换片）。
            detailFallbackAnchorTitle = TextUtils.isEmpty(vod_name) ? "" : vod_name.trim();
            // ★ v66：换片 ⇒ 预热重新判据（新片值得再预热一轮）。
            detailFallbackPoolTried = false;
            // ★ v66：换片 ⇒ 基准资源置空，等新片首轮详情返回后重新记录。
            detailFallbackRefVideo = null;
            // ★ v67：换片 ⇒ 基准集数置零（新片的集数与上一部片无关）。
            detailFallbackRefEpisodes = 0;
            detailFallbackCycleCount = 0;
            // ★ v69：换片 ⇒ 本圈冻结顺序作废。
            //   得分是「站点级」的（换片不清分），新片沿用旧片的站点排序重新定一圈，
            //   「哪几站有这部片」由候选池隔离，不受影响。
            detailFallbackCycleFrozenOrder.clear();
            detailFallbackTimedOutKey = null;
            detailFallbackTimedOutSource = null;
            }
        }

        detailFallbackActive = true;
        // 手动发起切源 → 重置失败续切预算。自动续切不清零，那正是要限制的连跳。
        detailFallbackFailoverCount = 0;
        // ★ 每次切源前，把搜索会话候选池的「最新增量」并入本地缓存。
        //   搜索是边跑边补的（单一真相源在 SearchSession），而本地 detailFallbackCache
        //   是进页面那一刻的副本；不重新拉取的话，切源能在几个站之间轮转，
        //   但「可切站点总数」永远停在进页面时的数量 —— 这正是要修的现象。
        refreshFallbackCandidatesFromSession();
        // ★ v65：池子「明显不完整」时补一轮全网搜索。
        //
        // 背景：loadNextDetailFallbackFromCache 的判据是「缓存非空就只轮转、不联网」，
        // 于是候选池会永远停在进页面时的数量 —— 用户从搜索页带了残缺池进来
        // （聚合搜索边跑边补，点进详情时往往只回来几个源），或从播放历史直接进来
        // （池子只剩 SearchSession 里该片的几个写法），表现就是「切源只有两三个站」。
        // v65 的 sameFamily 放宽让候选更容易进池，但池子「源头」残缺时仍会卡住，
        // 故在此补一道：可用候选不足可搜源一半时，派一轮全网搜索增量并入池。
        //
        // 时序：本轮仍立刻从现有池轮转（用户不等搜索），搜索结果边回流边合并，
        // 后续再点切源时池子已变大；一次进页只补一轮（detailFallbackPoolRefreshed）。
        if (!detailFallbackPoolRefreshed && detailFallbackPoolLooksThin()) {
            detailFallbackPoolRefreshed = true;
            LOG.i("[FB] pool thin, dispatch full-net search to enrich pool");
            startDetailFallback();
        }
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
            // ★ v69：换片后的第一圈 ⇒ 冻结顺序作废，按新片自己的得分重新定序
            detailFallbackCycleFrozenOrder.clear();
            detailFallbackNewCycle = false;
            // ★ v70：入口源（此刻正在播的源）直接计入本圈「已轮过」。
            // 之前它只有被选中才会进 cycleKeys，而第一跳按规则跳过当前源（防原地重载）
            // ⇒ 它留在「未轮过」区间；切走之后就成了普通候选，而它的得分往往靠前
            //（用户正在用它看片）⇒ 第二跳常切回入口源，表现为「切源前几个出现重复」。
            // 与 isCurrentPlayingSource 同一 key 口径，见 currentPlayingSourceKey()。
            String entryKey = currentPlayingSourceKey();
            if (!TextUtils.isEmpty(entryKey)) {
                detailFallbackCycleKeys.add(entryKey);
            }
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
        Movie.Video video = detailFallbackCacheVideo(detailFallbackAnchorTitle, nextSource, nextId);
        if (video != null) {
            vod_name = video.name == null ? "" : video.name;
            vod_picture = video.pic == null ? "" : video.pic;
        }
        // ★ 先刷页面，再发请求。站点的信息在点下切源这一刻就已从本地缓存选定，
        // 没有任何理由等详情报文回来才显示。不先刷的话，用户点完只能对着旧源发呆
        // 7~8 秒（详情 RTT + 解析 + 起播），以为没生效而反复点击。
        showDetailFallbackIndicators(nextSource, video);
        markDetailFallbackInflight(nextSource, nextId);
        markSwitchFrom();
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
        List<Movie.Video> cachedCandidates = poolOf(detailFallbackAnchorTitle);
        return cachedCandidates != null && !cachedCandidates.isEmpty();
    }

    /**
     * 从缓存里取出「本圈还没轮转到、且全局没试过」的下一个源。
     * 取不到时先尝试开新圈（清空 CycleKeys），仍然取不到才返回 ""。
     */
    private String pollNextCycledSource() {
        List<Movie.Video> cachedCandidates = poolOf(detailFallbackAnchorTitle);
        if (cachedCandidates == null || cachedCandidates.isEmpty()) {
            return "";
        }
        // ★ 把缓存整理成「每个站点一条」的有序候选表：按站点质量分降序。
        //
        // 之前直接遍历缓存原序（= 各源返回先后），于是「下一个切谁」取决于网络快慢，
        // 与站点质量无关；质量排序只在整表层面做了一次，进到轮转里就失效了。
        // 质量分相同（都是冷启动的 NEUTRAL）时按显示名排，保证同一部片每次进入
        // 切源顺序稳定可复现，不会「这次先切 A、下次先切 B」。
        //
        // ★ v69：取的是「本圈顺序」而不是每次现排的一份，详见 currentCycleOrder 注释。
        List<String> orderedSources = currentCycleOrder(cachedCandidates);
        if (orderedSources.isEmpty()) {
            detailFallbackPickedVideoId = "";
            return "";
        }
        // ★ 第一轮只挑「别的站」：本圈内还有没轮过的其它站点时，跳过当前正在播放的源。
        //   点切源却切回自己 = 页面原地重载一遍，用户只会以为没生效。
        String picked = scanOrderedSources(orderedSources, false);
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
        // ★ v69：开新圈 ⇒ 冻结顺序作废，按此刻最新的得分重新定一整圈。
        //   这就是「循环一圈之后，下一圈应该从得分最高的站开始」的落点。
        detailFallbackCycleFrozenOrder.clear();
        detailFallbackCycleCount++;
        // ★ v99-r66：这条原本是 LOG.i（tag 不是 TVBox-switch）⇒ 被真机 logcat 过滤掉，
        //   于是「有没有开新圈」永远查不到（r65 日志里 newCycle 计数为 0，无从判断）。
        LOG.sw("[FB] newCycle #" + detailFallbackCycleCount
                + " pool=" + orderedSources.size());
        List<String> newOrder = currentCycleOrder(cachedCandidates);
        picked = scanOrderedSources(newOrder, false);
        if (TextUtils.isEmpty(picked)) {
            // ★ v70：圈尾兜底顺序调整（用户拍板「跳过当前最高分切第二名」）。
            // v69 这里先「切回当前源」再开新圈，圈尾会多出一次原地重载，
            // 用户看到的又是一次「重复」。改为先开新圈取「非当前源里分数最高的」；
            // 只有新圈里连非当前源都选不出来（实际只剩当前源可用）才允许回到当前源，
            // 避免用户彻底切不动。
            picked = scanOrderedSources(newOrder, true);
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
        // ★ v69：起点恒为 0 —— 从头扫 == 取「本圈还没轮过、且不是当前源」里分数最高的。
        //
        // 改前这里取的是「上一跳的下一个」（环上接续）：列表排得再准，
        // 下一跳也只会落在「上一圈末尾的后面」—— 排序决定的是排列，
        // 接续决定的是落点，两者一叠加，走完一圈后看到的顺序就与得分毫无关系。
        // 这正是「循环一圈后再切源，感觉没按得分顺序切」的根因。
        int startIdx = 0;
        // ★ v99-r15（P2-2）：记录每个源为什么被跳过。
        //
        //   此前这里只有 4 个静默的 continue，用户报「为什么切到第 5 名」时，
        //   日志里只有最终 picked 那一条，前 4 个各因为什么被跳过无从回答。
        //   现在把原因收集起来，随 pollPick 一起打出来（全部跳过时也打）。
        //
        //   四类原因（与下面四个 continue 一一对应）：
        //     cycled = 本圈已轮转过（cycleKeys）
        //     soft   = 本圈已试过但被判软失败（softTriedKeys）
        //     cur    = 当前正在播放的源（allowCurrent=false 时跳过）
        //     novid  = 该站取不到可用的候选 videoId（全是 MV/预告/被拉黑）
        //
        // ★ v99-r66：补上 cycled —— r15 漏了这条，而它恰恰是「跳过源」最常见的原因
        //   （入口源在开圈时就进了 cycleKeys），导致 pollPick 行里看不到任何 skip 字段。
        final int SKIP_LOG_MAX = 6;
        List<String> skipCycled = new ArrayList<>();
        List<String> skipSoft = new ArrayList<>();
        List<String> skipCur = new ArrayList<>();
        List<String> skipNovid = new ArrayList<>();
        for (int step = 0; step < total; step++) {
            int index = (startIdx + step) % total;
            String cycleKey = orderedSources.get(index);
            // 圈内已轮转过 → 跳过（本圈不重复站点）
            if (detailFallbackCycleKeys.contains(cycleKey)) {
                if (skipCycled.size() < SKIP_LOG_MAX) {
                    skipCycled.add(cycleKey);
                }
                continue;
            }
            // 软失败：本圈已试过的站点（开新圈时该集合会被清空）。
            // ★ 粒度必须与写入侧一致（都是站点 key，见 finishDetailFallbackDetailOnTimeout）。
            if (detailFallbackSoftTriedKeys.contains(cycleKey)) {
                if (skipSoft.size() < SKIP_LOG_MAX) {
                    skipSoft.add(cycleKey);
                }
                continue;
            }
            if (!allowCurrent && isCurrentPlayingSource(cycleKey)) {
                if (skipCur.size() < SKIP_LOG_MAX) {
                    skipCur.add(cycleKey);
                }
                continue;
            }
            // ★ v99-r15（P2-1）：删掉此处原先的「整站被拉黑」判断。
            //
            //   那是一句<b>恒为 false 的死判断</b>：detailFallbackDeadKeys 里存的是
            //   getDetailFallbackKey(sourceKey, videoId) 形式的 "源key|视频id"（见
            //   写入侧 finishDetailFallbackDetailOnTimeout），而这里比较的 cycleKey
            //   是纯 sourceKey —— contains(纯key) 永远不命中。
            //
            //   功能上不影响行为（真正生效的粗粒度过滤在 detailFallbackFirstAliveId
            //   内部，它按正确粒度逐个候选过滤；整站全死时返回空串、外层 continue），
            //   但留着它有两个害处：① 注释声称「整站被拉黑」会误导后来人以为这道关卡
            //   在起作用；② 一旦有人调整判断顺序（先判 dead 再取 id），缺陷立刻暴露。
            //   故连同注释一并删除。
            //
            // ★ 取该站「最优」的未拉黑候选：某站第 1 条是 MV/预告、后面才是正片时，
            //   只认第一条会让该站的正片永远轮不到（表现为「明明有片却切不过去」）。
            //   ★ v66：同站多资源时按与「基准资源」的相似度择优（含年份硬矛盾排除），
            //   全部无信号时退回第一条 —— 详见 detailFallbackFirstAliveId 内注释。
            String videoId = detailFallbackFirstAliveId(cycleKey);
            if (TextUtils.isEmpty(videoId)) {
                if (skipNovid.size() < SKIP_LOG_MAX) {
                    skipNovid.add(cycleKey);
                }
                continue;
            }
            detailFallbackCycleKeys.add(cycleKey);
            detailFallbackSoftTriedKeys.add(cycleKey);
            detailFallbackPickedVideoId = videoId;
            LOG.sw("[FB] pollPick source=" + cycleKey
                    + " name=" + detailFallbackDisplayName(cycleKey)
                    + " id=" + videoId
                    + " idx=" + index + "/" + total
                    + " allowCurrent=" + allowCurrent
                    + " cycleKeys=" + detailFallbackCycleKeys.size()
                    // ★ v99-r15（P2-2）：本轮跳过了谁、为什么
                    + buildSkipReasonLog(skipSoft, "soft", skipSoft.size())
                    + buildSkipReasonLog(skipCur, "cur", skipCur.size())
                    + buildSkipReasonLog(skipNovid, "novid", skipNovid.size())
                    + buildSkipReasonLog(skipCycled, "cycled", skipCycled.size()));
            return cycleKey;
        }
        // ★ v99-r15（P2-2）：一个都没选上时也必须打日志 —— 这正是最需要排查的场景
        //   （用户点切源却没反应），此前它是完全静默的。
        LOG.sw("[FB] pollPick none idx=0/" + total
                + " allowCurrent=" + allowCurrent
                + " cycleKeys=" + detailFallbackCycleKeys.size()
                + buildSkipReasonLog(skipSoft, "soft", skipSoft.size())
                + buildSkipReasonLog(skipCur, "cur", skipCur.size())
                + buildSkipReasonLog(skipNovid, "novid", skipNovid.size())
                + buildSkipReasonLog(skipCycled, "cycled", skipCycled.size()));
        return "";
    }

    /**
     * ★ v99-r15（P2-2）：把一类跳过原因拼成日志片段。
     *
     * <p>格式：{@code soft=a,b,c(+3)}；没有跳过该类时返回空串（不占日志宽度）。</p>
     *
     * @param names  已收集的来源 key（最多 {@code SKIP_LOG_MAX} 条）
     * @param label  原因标签
     * @param actual 实际跳过总数（用于显示被截断的 "+N"）
     */
    private String buildSkipReasonLog(List<String> names, String label, int actual) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" skip").append(label).append('=');
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(names.get(i));
        }
        if (actual > names.size()) {
            sb.append("(+").append(actual - names.size()).append(')');
        }
        return sb.toString();
    }

    /**
     * ★ v69：本圈实际使用的切源顺序。
     *
     * <p><b>背景</b>：改前每一跳都按最新得分整表重排，而扫描起点又是「上一跳的下一个」，
     * 于是「排好序」只影响环上的相邻关系，不影响下一跳落在哪 —— 表现为
     * 「循环一圈后再切源，顺序跟得分完全无关」。</p>
     *
     * <p><b>本方法定下两件事</b>：
     * <ol>
     *   <li><b>起点归零</b>：返回的列表本身就是「该切的先后」，
     *       {@link #scanOrderedSources} 从头扫即可等价于「取未轮过里最好的」；</li>
     *   <li><b>圈内冻结</b>（用户拍板：一圈内顺序要稳定，不要每切一次就整体重排）：
     *       本圈<b>已经轮过的部分</b>保持原相对顺序不再变动；
     *       <b>还没轮到的部分</b>仍按最新得分重排，池子新回流的站也按得分插进来。</li>
     * </ol>
     *
     * <p><b>为什么不整圈完全冻结</b>：完全冻结会把本圈内新回流的站点挡在圈外，
     * 要等下一圈才轮得到，等于让「边搜边补」白补。只冻已轮过部分，
     * 既保住顺序稳定，又不漏新站。</p>
     */
    private List<String> currentCycleOrder(List<Movie.Video> cachedCandidates) {
        List<String> fresh = buildFallbackCycleOrder(cachedCandidates);
        if (fresh.isEmpty()) {
            detailFallbackCycleFrozenOrder.clear();
            return fresh;
        }
        if (detailFallbackCycleFrozenOrder.isEmpty()) {
            // 首圈 / 换片 / 开新圈后的第一次：整份按得分降序定下来
            detailFallbackCycleFrozenOrder.addAll(fresh);
        } else {
            // 本圈内：已轮过的保持原相对顺序；未轮过的按最新得分重排（新站自动归位）
            List<String> cycled = new ArrayList<>();
            for (String k : detailFallbackCycleFrozenOrder) {
                if (detailFallbackCycleKeys.contains(k)
                        || detailFallbackSoftTriedKeys.contains(k)) {
                    cycled.add(k);
                }
            }
            List<String> pending = new ArrayList<>();
            for (String k : fresh) {
                if (!cycled.contains(k)) {
                    pending.add(k);
                }
            }
            detailFallbackCycleFrozenOrder.clear();
            detailFallbackCycleFrozenOrder.addAll(cycled);
            detailFallbackCycleFrozenOrder.addAll(pending);
        }
        logCycleOrder(detailFallbackCycleFrozenOrder);
        return detailFallbackCycleFrozenOrder;
    }

    /**
     * ★ v69：把本圈的切源顺序（含各站得分）打进日志，
     * 让「排序到底生效没有」可以直接从 logcat 判定，不用再靠感觉。
     *
     * <p>与 {@code pollPick} 同一条日志通道（TVBox-switch）；带 {@code *}
     * 的项表示本圈已经轮过。分数复用 {@link #buildFallbackCycleOrder} 建的
     * 快照（{@link #detailFallbackCycleOrderSnapshot}），不额外读 Hawk。</p>
     */
    private void logCycleOrder(List<String> orderedSources) {
        try {
            if (orderedSources == null || orderedSources.isEmpty()) {
                return;
            }
            SourceQualityStore.Snapshot snap = detailFallbackCycleOrderSnapshot;
            StringBuilder sb = new StringBuilder();
            sb.append("[FB] cycleOrder cycle=").append(detailFallbackCycleCount)
                    .append(" n=").append(orderedSources.size());
            for (int i = 0; i < orderedSources.size(); i++) {
                String k = orderedSources.get(i);
                sb.append(" | ").append(i).append(':')
                        .append(detailFallbackDisplayName(k));
                if (snap != null) {
                    sb.append('=').append(
                            String.format(java.util.Locale.US, "%.3f", snap.get(k)));
                }
                if (detailFallbackCycleKeys.contains(k)) {
                    sb.append('*');
                }
            }
            LOG.sw(sb.toString());
        } catch (Throwable th) {
            LOG.e("logCycleOrder fail: " + th);
        }
    }

    /**
     * 取某站点缓存里第一条「未被拉黑」的候选影片 id。
     *
     * <p>黑名单粒度是「源|影片id」（写于详情成功但无剧集处）。若这里只取该站第一条，
     * 那么第一条被拉黑后，同站的其它正片候选就再也轮不到。故按 id 粒度在站内跳过。</p>
     */
    private String detailFallbackFirstAliveId(String sourceKey) {
        List<Movie.Video> cachedCandidates = poolOf(detailFallbackAnchorTitle);
        if (cachedCandidates == null || TextUtils.isEmpty(sourceKey)) {
            return "";
        }
        // ★ v66：同站多资源择优。同一片名在同一站可能有多个资源（如《人世间》
        //   两部剧，详情/封面都不同），「取第一条」会切到另一部去。改为按与
        //   「基准资源」（detailFallbackRefVideo，进详情时看的那条）的相似度
        //   选最优：封面相同是最强信号，演员/年份/类型/导演/备注一致加分；
        //   全部零分（基准为空或字段都缺）时退回第一条，不会比旧行为差。
        String firstAlive = "";
        int bestScore = 0;
        StringBuilder pickLog = new StringBuilder();
        for (Movie.Video video : cachedCandidates) {
            if (video == null || !TextUtils.equals(video.sourceKey, sourceKey)
                    || TextUtils.isEmpty(video.id)) {
                continue;
            }
            if (detailFallbackDeadKeys.contains(getDetailFallbackKey(sourceKey, video.id))) {
                continue;
            }
            int score = referenceMatchScore(detailFallbackRefVideo, video);
            if (pickLog.length() > 0) {
                pickLog.append(' ');
            }
            pickLog.append(video.id).append(':').append(score);
            if (score < 0) {
                continue;   // 硬矛盾（年份冲突）：排除
            }
            // ★★ v99-r73：兜底赋值移到硬矛盾排除之后 —— 旧实现先赋兜底、后排除，
            //   导致「全站候选都被判 -1」时仍会把一条**已判为另一部片**的候选兜底选中
            //   （真机：scores=[29946:-1] picked=29946 ⇒ 切到韩国版 2013）。
            if (TextUtils.isEmpty(firstAlive)) {
                firstAlive = video.id;   // 第一条非矛盾候选 = 兜底
            }
            if (score > bestScore) {
                bestScore = score;
                firstAlive = video.id;
                if (bestScore >= 100) {
                    break;   // 封面命中，不必再扫
                }
            }
        }
        // ★ v67：同站多资源择优的诊断日志（sw 通道，release 也输出）。
        //   「切到同站另一部同名剧」排障必备：该站有几条同名候选、每条与基准的
        //   得分、最终选了谁、基准是什么。日志里一眼看出是「择优没生效」
        //   （scores 全 0 或 ref=none）还是「元数据贫瘠无从区分」。
        if (!TextUtils.isEmpty(firstAlive)) {
            LOG.sw("[FB] sameStationPick source=" + sourceKey
                    + " ref=" + describeReference(detailFallbackRefVideo)
                    + " scores=[" + pickLog + "]"
                    + " picked=" + firstAlive + " best=" + bestScore);
        }
        return firstAlive;
    }

    /** 基准资源的日志摘要（不打全串，避免日志爆炸）。 */
    private static String describeReference(Movie.Video ref) {
        if (ref == null) {
            return "none";
        }
        return "id=" + ref.id + " year=" + ref.year
                + " pic=" + (TextUtils.isEmpty(ref.pic) ? "-" : shortHash(ref.pic))
                + " actor=" + (TextUtils.isEmpty(ref.actor) ? "-" : ref.actor.length() + "字")
                + " type=" + (ref.type == null ? "-" : ref.type);
    }

    private static String shortHash(String s) {
        return Integer.toHexString(s.hashCode() & 0xffff);
    }

    /**
     * ★ v67：集数校验判据（抽成方法以便端到端验证）。
     *
     * <p>三重门全部满足才判「悬殊」：该站有多个候选（有得选，单候选的站没得换）、
     * 新源集数已知、新源集数不足基准的一半。阈值取「一半」是保守值 ——
     * 删减版通常只少几集（58→50 不会触发），而另一部同名剧往往量级不同
     * （58→20 必触发）。</p>
     */
    private boolean shouldRejectByEpisodes(int nowEpisodes) {
        // ★★ v99-r67：两处改动（用户定稿「所有源都必须过校验」）。
        //   ① 去掉 `sameStation > 1` 门 —— 单候选站的错片此前完全漏网
        //      （真机 iqiyi：只有 1 集「正片」vs 基准 51 集，`candidates=1` ⇒ 直接放行）。
        //   ② 阈值从 1/2 收紧到 1/4 —— 1/2 会把「连载中的正确源」误杀：
        //      ref 58 集、某站只更新到 20 集 ⇒ 20*2=40 < 58 判错片。真机比值：
        //      错片 1/51=0.02 命中；连载 20/58=0.34、删减 30/58=0.52 都不命中。
        // ★ v99-r69：补「多集」方向。真机：ref=1 集（韩国版）而候选 51 集（国产版）时被放行，
        //   用户切过去看到的是另一部片。上界取 6 倍以免误杀"单集被拆成几段"的正常情况。
        if (nowEpisodes <= 0 || detailFallbackRefEpisodes <= 0) {
            return false;
        }
        return nowEpisodes * 4 < detailFallbackRefEpisodes
                || nowEpisodes > detailFallbackRefEpisodes * 6;
    }

    /** ★ v67：统计一部片的总集数（seriesMap 各线路的剧集数之和）。 */
    /**
     * ★★ v99-r66：该「源 × 片」的集数。
     *
     * <p><b>旧实现是错的</b>：把所有线路的 {@code list.size()} <b>相加</b>，而
     * {@code seriesMap} 的 key 就是播放线路 ⇒ 多线路源的集数被乘了线路数。真机实测
     * （《人世间》58 集）：2 线路算出 116、10 线路算出 580。后果是集数校验里的
     * 「新源集数不足基准一半」永远不成立，校验形同虚设。</p>
     *
     * <p>现改为<b>取各线路条目数的最大值</b>（一集一条时它就等于集数）。
     * 按用户拍板：<b>不解析条目名里的集号</b> —— 电视剧的集数与条目数一致，
     * 需要解析的长剧合集包很少见，不为此引入不靠谱的启发式。</p>
     */
    private static int countSeriesEpisodes(VodInfo vodInfo) {
        if (vodInfo == null || vodInfo.seriesMap == null) {
            return 0;
        }
        int best = 0;
        for (List<VodInfo.VodSeries> list : vodInfo.seriesMap.values()) {
            if (list != null && list.size() > best) {
                best = list.size();
            }
        }
        return best;
    }

    /**
     * ★ v99-r66：集数校验日志用的「源形态」，形如 {@code L2[58,58] maxEp=58}。
     *
     * <p>必须把线路数与各线路条目数一起打出来 —— 否则再出现「now 与 ref 不符」时，
     * 还是分不清是「多线路被乘错」「合集条目被压扁」还是「真的是错片」。</p>
     */
    private static String describeSeriesShape(VodInfo vodInfo) {
        if (vodInfo == null || vodInfo.seriesMap == null || vodInfo.seriesMap.isEmpty()) {
            return "L0[]";
        }
        StringBuilder sb = new StringBuilder("L").append(vodInfo.seriesMap.size()).append('[');
        boolean first = true;
        for (List<VodInfo.VodSeries> list : vodInfo.seriesMap.values()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(list == null ? 0 : list.size());
            first = false;
        }
        return sb.append("] maxEp=").append(countSeriesEpisodes(vodInfo)).toString();
    }

    /**
     * ★ v67：候选池里某站「未被拉黑」的候选条数（同站多资源判定的依据）。
     *
     * <p>用锚 key 读池（与轮转/择优同一口径）。只有 1 条时说明没得选，
     * 集数校验不启动（避免把唯一候选记死后无源可切）。</p>
     */
    private int countSameStationCandidates(String sourceKey) {
        if (TextUtils.isEmpty(sourceKey)) {
            return 0;
        }
        List<Movie.Video> cachedCandidates = poolOf(detailFallbackAnchorTitle);
        if (cachedCandidates == null) {
            return 0;
        }
        int n = 0;
        for (Movie.Video video : cachedCandidates) {
            if (video == null || !TextUtils.equals(video.sourceKey, sourceKey)
                    || TextUtils.isEmpty(video.id)) {
                continue;
            }
            if (detailFallbackDeadKeys.contains(getDetailFallbackKey(sourceKey, video.id))) {
                continue;
            }
            n++;
        }
        return n;
    }

    /**
     * ★ v66：候选与「基准资源」的相似度打分（用于同站多资源择优）。
     *
     * <p><b>返回 -1 = 硬矛盾</b>（两边都有年份且不同）——同一部片跨站的年份一致是
     * 常态，年份冲突几乎必然是另一部（如两部《人世间》一新一旧），调用方应排除。</p>
     *
     * <p><b>为什么不能要求字段全等</b>：同一部剧在不同站的元数据完整度与叫法
     * 都不同 —— A 站演员列全（雷佳音,辛柏青,宋佳…）、B 站只列前两个；
     * 类型叫 {@code 剧情/家庭} 与 {@code 剧情,家庭} 甚至 {@code 国产剧}。
     * 全等判定会让「更全的站」与「不全的站」互相丢分，同一部被误判。
     * 故演员/类型/导演一律走 {@link #metaRelated} 的<b>相容匹配</b>
     * （按分隔符拆集合，一方是另一方的子集即算匹配），「不全」不再丢分。</p>
     *
     * <p>权重设计：封面（{@code pic} URL 完全相同）是<b>最强</b>信号 —— 同站两部
     * 不同剧海报必然不同，而同一部片跨站常用同一张海报；片名/演员次之；
     * 年份/类型/导演辅助。</p>
     */
    private static int referenceMatchScore(Movie.Video ref, Movie.Video v) {
        if (ref == null || v == null) {
            return 0;
        }
        // 硬矛盾：两边都有年份且不同 ⇒ 排除（见方法注释）
        if (ref.year > 0 && v.year > 0 && ref.year != v.year) {
            return -1;
        }
        int score = 0;
        if (!TextUtils.isEmpty(ref.pic) && ref.pic.equals(v.pic)) {
            score += 100;
        }
        if (!TextUtils.isEmpty(ref.name)
                && ref.name.trim().equals(v.name == null ? "" : v.name.trim())) {
            score += 20;
        }
        if (metaRelated(ref.actor, v.actor)) {
            score += 20;
        }
        if (ref.year > 0 && ref.year == v.year) {
            score += 15;
        }
        if (metaRelated(ref.type, v.type)) {
            score += 10;
        }
        if (metaRelated(ref.director, v.director)) {
            score += 10;
        }
        // ★ v99-r73：地区交叉 +5（用户定稿：演员/导演/类型/地区的交叉口径用于信息匹配打分）。
        //   弱信号（不同地区的同片几乎不会出现，但同地区不同片很常见），只作末位辅助。
        if (metaRelated(ref.area, v.area)) {
            score += 5;
        }
        return score;
    }

    /**
     * 两个元数据字段是否「相容」：按常见分隔符拆成标签集合后，
     * 一方是另一方的<b>子集</b>（含相等）即算匹配。
     *
     * <p>容忍三种现实差异：①完整度（演员列全 vs 列不全）；②分隔符与顺序
     * （{@code 雷佳音,辛柏青} vs {@code 辛柏青/雷佳音}）；③叫法一致但组合不同
     * （{@code 剧情} vs {@code 剧情/家庭}）。任一侧为空返回 false（不猜）。</p>
     */
    /**
     * ★ v99-r74：演员/导演/类型/地区的交叉匹配 —— 实现已收敛到 {@link FilmIdentity}，
     * 避免 DetailActivity 与 RoomDataManger 各留一份口径（那是最容易分叉的地方）。
     */
    private static boolean metaRelated(String a, String b) {
        return FilmIdentity.metaRelated(a, b);
    }

    /** 按常见分隔符拆元数据字段为标签集合（统一 trim，滤空）。 */
    private static Set<String> splitMetaTags(String s) {
        Set<String> out = new HashSet<>();
        for (String p : s.split("[/,，、|;；\\s]+")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * ★ v66：把一条视频浅拷贝成「基准资源」快照。
     *
     * <p>只带择优用得上的字段。必须拷贝而不是持有引用：{@code mVideo} 会在切源后
     * 被整体替换，基准要始终指向「进详情时看的那条」。</p>
     */
    private static Movie.Video snapshotReference(Movie.Video v) {
        if (v == null) {
            return null;
        }
        Movie.Video r = new Movie.Video();
        r.id = v.id;
        r.sourceKey = v.sourceKey;
        r.name = v.name;
        r.pic = v.pic;
        r.type = v.type;
        r.lang = v.lang;
        r.area = v.area;
        r.year = v.year;
        r.state = v.state;
        r.note = v.note;
        r.actor = v.actor;
        r.director = v.director;
        return r;
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
        // ★ v69：快照是「单次调用内有效」的日志副产品（见字段注释）。
        //   每次进入本方法先作废，避免池子为空时残留上一份、把日志打到错误的分数上。
        detailFallbackCycleOrderSnapshot = null;
        Map<String, Movie.Video> byKey = new LinkedHashMap<>();
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
            // ★ v99-r15（P1-4）：显示名去重已移到排序之后。
            //
            //   原先在这里就去重（保留先出现的那个），但此刻还没有质量分 ——
            //   「保留先出现的」实际取决于聚合搜索哪个站先返回，纯粹是网络快慢、
            //   随机。两个 key 不同、显示名相同的站（多个「量子资源」很常见）
            //   于是可能丢掉真正能用的那个，表现为「明明有片却切不过去」。
            //
            //   顺带修掉一处注释与实现的矛盾：原注释写「通常是质量更好的，
            //   见下方排序」，但排序在去重之后才发生，这个前提根本不成立。
            byKey.put(video.sourceKey, video);
        }
        if (byKey.isEmpty()) {
            return new ArrayList<>();
        }
        final List<String> keys = new ArrayList<>(byKey.keySet());
        // 质量分降序；同分按显示名升序，保证顺序稳定可复现
        try {
            final SourceQualityStore.Snapshot snapshot = SourceQualityStore.snapshot(keys);
            // ★ v69：留给 logCycleOrder 复用，避免排序之外再读一遍 Hawk
            detailFallbackCycleOrderSnapshot = snapshot;
            if (!snapshot.hasAnyHistory()) {
                // 冷启动：所有源都是中性分，排序不会改变任何顺序，省下这轮 O(n log n)
                // （探索加成在无历史时恒为 0，见 Snapshot.exploreBonus）
                return keys;
            }
            // ★ P2-2：把显示名一次性预取成 Map，避免比较器内 O(n log n) 次
            //   ApiConfig.get().getSource() 查表（每次比较都要查两遍）。
            // ★ v99-r15：这张表现在还兼作「排序后按名去重」的查找表。
            final Map<String, String> nameByKey = new LinkedHashMap<>(keys.size() * 2);
            for (String k : keys) {
                nameByKey.put(k, detailFallbackDisplayName(k));
            }
            java.util.Collections.sort(keys, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    // ★ v99-r15（P1-2）：用「质量分 + 探索加成」排序，
                    //   让样本少的新源也有机会进前列（否则永远 0.5 排尾）。
                    //   加成只依赖构造时定死的 samples/lnTotal ⇒ 纯函数，传递性有保证。
                    int byQuality = Double.compare(snapshot.sortScore(b), snapshot.sortScore(a));
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
            // ★ v99-r15（P1-4）：同名站去重放在排序之后 —— 保留分数更高的那个。
            //   走到这里 keys 已按 sortScore 降序，所以「第一次出现」就是最高分。
            //   同分时上面已按显示名升序稳定排序，故同分同名仍按 key 字典序稳定。
            List<String> deduped = new ArrayList<>(keys.size());
            Set<String> seenNames = new java.util.HashSet<>();
            for (String k : keys) {
                String name = nameByKey.get(k);
                if (name == null || name.isEmpty()) {
                    // 取不到显示名的不参与去重（宁可留着一个，也不要误删）
                    deduped.add(k);
                    continue;
                }
                if (seenNames.add(name)) {
                    deduped.add(k);
                }
            }
            return deduped;
        } catch (Throwable th) {
            LOG.e("buildFallbackCycleOrder sort fail: " + th);
        }
        return keys;
    }

    /**
     * ★ v68：是否应当按「换片」重置切源状态（基准资源/死站/圈/锚全部清空）。
     *
     * <p><b>首次切源不算换片</b>：{@code cycleTitle} 初值为空串，与任何片名都
     * 「不相等」。旧逻辑把这种「不相等」一律当换片处理，于是用户第一次点切源时
     * 就把首轮详情刚记录的基准资源（{@link #detailFallbackRefVideo}）清掉了 ——
     * 红牛/虎牙案例的同站择优失效（所有站 {@code ref=none}）正是由此而来。</p>
     *
     * <p><b>片名漂移也不算换片</b>：切源过程中 {@code vod_name} 会被替换成新站
     * 写法（{@code 人世间} → {@code 人世间TV版}），normalize 剥不掉版号类修饰，
     * 严格相等会误判换片、二次清掉基准。故用 {@link ShowTitleKey#sameFilmTitle}
     * 做同片判定（形态词算同片修饰；序号/数字/季数不同仍算换片）。</p>
     */
    private static boolean needResetForFilmSwitch(String cycleTitle, String normalizedTitle) {
        if (TextUtils.isEmpty(cycleTitle)) {
            return false;   // 首次切源：只记录片名，不清理
        }
        return !ShowTitleKey.sameFilmTitle(cycleTitle, normalizedTitle);
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
        List<Movie.Video> cachedCandidates = poolOf(title);
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
            List<Movie.Video> cachedCandidates = poolOf(title);
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
            // ★ v99-r65：这条也是「静默 return」，手动路径要把原因交给菜单去弹。
            detailFallbackDenyMsg = "片名缺失，无法匹配其它片源";
            LOG.sw("[FB] swapDeny reason=blankTitleInner manual="
                    + (detailFallbackManualSwap ? 1 : 0));
            if (!detailFallbackManualSwap) {
                showDetailEmpty();
            }
            return;
        }
        // 方案D：二次进同一部片，先从 Hawk 恢复上次的候选池（可能一次网络都不发）

        // ★ v99-r58：队列已在跑（后台档攒池中）时<b>绝不重建</b>。
        //   重建会清空并重排 sourceOrder，而 nextIndex 是老队列的下标 —— 两者一旦错位，
        //   要么漏掉一批源、要么把同一批源再问一遍。
        String probeKey = "";
        if (!detailFallbackPoolFilling) {
            SourceQualityStore.Snapshot fbSnapshot = prepareDetailFallbackSourceOrder();
            if (detailFallbackSourceOrder.isEmpty()) {
                // ★ v99-r65：同样把原因交出去。手动路径由菜单统一弹一次，这里不弹，
                //   否则「没有可切换的片源」会叠成两个 Toast。
                detailFallbackDenyMsg = "没有可切换的片源";
                LOG.sw("[FB] swapDeny reason=noSearchableSource manual="
                        + (detailFallbackManualSwap ? 1 : 0));
                if (!detailFallbackManualSwap) {
                    if (!detailFallbackKeepCurrentDetail) {
                        showDetailEmpty();
                    } else {
                        Toast.makeText(this, "没有更多可切换的片源", Toast.LENGTH_SHORT).show();
                    }
                }
                return;
            }
            // ★ 探路要单独朝「最稳的站」打一枪，就必须把它从队列里摘掉：
            //   否则同一个站点会被搜两次（探路一次 + 队列一次），白扔一个请求不说，
            //   还让最稳的那个站重复占用一个并发名额。探路没命中时再补回队尾（见
            //   requeueProbeSource），不会因此漏掉该站。
            probeKey = pickBestProbeSource(fbSnapshot);
            if (!TextUtils.isEmpty(probeKey)) {
                detailFallbackSourceOrder.remove(probeKey);
            }
        }

        // ★ v99-r58：攒池队列可能正从后台档延续下来。此时<b>不能</b>重建队列/重置令牌，
        //   否则在途请求回来时令牌已变，会被守卫当成陌生请求丢掉 ——
        //   这正是 r57 真机「池子锁死 30」的直接成因。
        final boolean reuseFilling = detailFallbackPoolFilling;
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
        if (!reuseFilling) {
            detailFallbackBatchIndex = 1;
            detailFallbackToken = "detail_fallback_" + (++detailFallbackRequestIndex);
            // 批令牌是「这条结果属于本任务还是上个任务」的唯一凭据
            // （见 onDetailFallbackSearchResult 的守卫），必须在首次派发前定好。
            detailFallbackBatchToken = detailFallbackToken + "_batch_" + detailFallbackBatchIndex;
            detailFallbackNextSourceIndex = 0;
        }
        detailFallbackBatchStartMs = System.currentTimeMillis();
        // 重开 8s 前台窗口（记账 / 提前消费 / 并发档位都看它）
        detailFallbackPoolFrontWindowClosed = false;
        if (llLayout != null) {
            llLayout.removeCallbacks(detailFallbackTimeout);
            llLayout.postDelayed(detailFallbackTimeout, DETAIL_FALLBACK_SEARCH_TIMEOUT_MS);
            detailFallbackSearchTimeoutScheduled = true;
        }
        LOG.sw("[FB] startSearch title=" + detailFallbackTitle
                + " sources=" + detailFallbackSourceOrder.size()
                + " reuse=" + (reuseFilling ? 1 : 0));
        // 提速到前台档（已在跑则只提档不重建队列）；ensurePoolFilling 内部会处理派发与硬闸
        ensurePoolFilling(DETAIL_FALLBACK_MAX_SEARCH, "switch");
        // 方案A：探路。队列已复用时不探 —— 队列本身正在覆盖全部源，
        // 再朝同一个站单独打一枪等于把它搜两次。
        if (!reuseFilling) {
            startDetailFallbackProbe(probeKey);
        }
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
        // ★ 跨源采纳裸下标的前提是「两侧该下标确实指向同一集」—— 本方法开头的注释
        //   就是这么写的（"否则会把新源刚按内容重定位好的下标覆盖错"）。
        //   但原实现用的守卫 positionTrustedAcrossDomain 是为**播放时间迁移**设计的，
        //   它对跨域**一律放行**（跨域数值不可比，放行是为了"切对集却丢时间"）。
        //   拿它当"下标可否采纳"的守卫属于错配：
        //
        //   实测《我家那闺女2026》旧源=最大资源 / 新源=豪华：
        //     旧源 idx16 = 20260906第2期上   新源 idx16 = 加更版第2期
        //   两侧在**同一期内「上/下」的排列顺序相反**（旧源 下→上，新源 上→下），
        //   裸下标必然错位；而跨域放行让这个错位畅通无阻
        //   ⇒ 用户可见的现象就是「第2期上 切到了 加更版第2期」。
        //
        //   改为要求**内容级同集证据**（sameEpisode：同名、或同域 score≥80、或同日期同分段）。
        //   无证据就不搬 —— 保留 restoreDetailFallbackEpisode 按内容定位的结果。
        if (!EpisodeNameMatcher.sameEpisode(actualName, targetName)) {
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
        // ★ v99-r58：守卫从「本轮切源状态」（active/collecting）换成「攒池任务状态」。
        //   旧守卫在切源成功后必然失效 —— active 被 reset 置 false、batchToken 被清空，
        //   于是批1 之后所有回包都被当成陌生请求丢掉（真机：101 源只 dispatch 一次，池子锁死 30）。
        if (!detailFallbackPoolFilling || !detailFallbackBatchToken.equals(data.searchToken)) {
            return;
        }
        // 方案E：每个源返回，记录命中/失败统计（耗时按该源自己的发出时刻算，
        // 滑动窗口下"批次开始时刻"已无意义）
        Long dispatchedAt = detailFallbackSourceStartMs.remove(data.sourceKey);
        long searchCostMs = dispatchedAt == null
                ? (detailFallbackBatchStartMs > 0 ? System.currentTimeMillis() - detailFallbackBatchStartMs : 0L)
                : System.currentTimeMillis() - dispatchedAt;
        // ★ v99-r58：<b>只有前台窗口内的回包才记账</b>。
        //   后台档是与播放并存的低优先级请求，它的耗时/成败混进 SourceQualityStore 会
        //   拉低那些「只是当时后台在跑」的站点分数，进而打乱
        //   sortDetailFallbackSourceOrderByQuality 的顺序 —— 这是合并两条腿时最容易漏的回归。
        if (!detailFallbackPoolFrontWindowClosed) {
            recordFallbackStat(data.sourceKey,
                    data.movie != null && data.movie.videoList != null && !data.movie.videoList.isEmpty(),
                    searchCostMs);
        }
        detailFallbackPendingSources.remove(data.sourceKey);
        boolean cachedAny = false;
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video == null || TextUtils.isEmpty(video.id)
                        || !isSameFallbackTitle(detailFallbackAnchorTitle, video.name)) {
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
        // ★ v99-r58：只在<b>前台窗口内</b>安排 —— 后台档是纯攒池，没人在等画面，
        //   抢先放片只会把用户正在看的画面顶掉。
        if (cachedAny && detailFallbackSearchCollecting && detailFallbackSearching
                && !detailFallbackPoolFrontWindowClosed && !detailFallbackEarlyConsumeScheduled) {
            detailFallbackEarlyConsumeScheduled = true;
            llLayout.postDelayed(detailFallbackEarlyConsume, DETAIL_FALLBACK_FIRST_CONSUME_DELAY_MS);
        }
        // ★ v99-r58：只要攒池任务活着就继续补位，不再区分「是否还在本轮切源会话内」。
        if (detailFallbackSearching || detailFallbackPoolFilling) {
            scheduleDetailFallbackSearch();
        } else if (detailFallbackPendingSources.isEmpty()) {
            finishDetailFallbackSearchCollection();
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
        // ★ v99-r58：守卫换成任务级标志。旧写法要求 active && searching，
        //   而这两个正是「一次切源轮次」的状态，切源一成功就为 false ⇒ 补位永久停摆。
        if (!detailFallbackPoolFilling) {
            return;
        }
        // ★ v99-r60：起播保护窗内不派发新搜索。
        //
        //   <p>v42 起 FastSearchActivity.startNextSearchBatch() 开头就查
        //   isStartupGuardActive() 并 return —— 起播前 5s（或首帧到达前）让路，
        //   免得搜索把起播挤到队尾。但 r57/r58 新加的攒池这条腿<b>从来没查过</b>：
        //   起播前那几秒搜索页在让路、我们的填充在全速派发，v42 的保护被绕过去了。</p>
        //
        //   <p>窗口结束时由 PlayFragment 的 onFirstFrame 回调补一次派发（见
        //   ensurePlayFragment），所以这里直接返回是安全的 —— 不会把任务挂住。</p>
        //
        //   <p>但 onFirstFrame 只在「首帧真的到达」时来；若首帧迟迟不来，
        //   保护窗也会在 {@code STARTUP_GUARD_MAX_MS}（5s）后自动过期。
        //   后台档没有切源轮次，8s / 120s 那两个定时器都太慢，所以这里额外挂一个
        //   300ms 的短重试（复用忙时补位的那套，自带「同时只挂一个」节流，
        //   且 run() 里会把标志复位 ⇒ 窗口没关就自动续挂，不会挂死）。</p>
        if (SearchSession.getShared().isStartupGuardActive()) {
            // ★ v99-r61：被挡下也要留痕（v42 起这条路径全程静默，r60-A 因此无法验证）。
            //   300ms 重查天然把频率压在 ~3 条/秒，不会刷屏。
            // ★ v99-r62：源已全部派完（next 已到 size、pending 还在飞）时，挡不挡毫无区别，
            //   这种打点纯噪音（真机 16 条里 7 条是 next=101/101）⇒ 只在确实还有源可派时才记。
            if (detailFallbackNextSourceIndex < detailFallbackSourceOrder.size()) {
                LOG.sw("[FB] guardBlocked task=t" + detailFallbackPoolTaskId
                        + " next=" + detailFallbackNextSourceIndex + "/" + detailFallbackSourceOrder.size()
                        + " cap=" + detailFallbackPoolConcurrency);
            }
            scheduleBusyRefill();
            return;
        }
        // ★ v99-r60（丙-单腿）：后台档下，若搜索页有一轮覆盖本部片的搜索正在跑 ⇒ 不派发，
        //   让搜索页当唯一填充腿。
        //
        //   <p><b>为什么只压后台档、不压切源轮次</b>：切源时用户在等，
        //   {@code DETAIL_FALLBACK_MAX_SEARCH}=20 的那一发 burst 就是切源 0.5~0.9s
        //   回来的原因（真机实测）；压掉它切源会变慢。而后台档本来就是 5 并发、
        //   比搜索页的 6 并发还低，压掉不掉速。</p>
        //
        //   <p><b>为什么必须把已搜源折进 askedKeys</b>：压制期间我们自己不发请求，
        //   askedKeys 就不会长。而 r59 的跨实例跳过判据正是 askedKeys 的覆盖度 ——
        //   不折的话重进同一部片会判定「没问过」而从头问 101 个，r59 白做。</p>
        // ★ v99-r61（丙-单腿，第二层）：切源轮次也压制，但带一道安全阀 ——
        //   只有「池子里已经有可用的候选」时才压。
        //
        //   <p><b>为什么不再区分切源档</b>：r60 真机显示切源档那一发 20 并发的 burst
        //   有 15/20 跟会话腿重复（asked=80 = 会话75 ∪ 我们20），而且<b>任何一次切源</b>
        //   都会触发它（切源轮次 searching=true 不压制）。幸福伽菜子那一发恰好没东西可派
        //   只是因为用户切得晚，属于侥幸、不是保证。</p>
        //
        //   <p><b>为什么必须留安全阀</b>：{@link #loadNextDetailFallbackSource} 在
        //   {@code searching=true} 且池空时，只会调一次 {@code scheduleDetailFallbackSearch()}
        //   然后 return —— 压制住它就<b>没有任何回调会再把它拉起来</b>（300ms 重查只调
        //   派发、不调取源），切源要干等 8s 前台窗口超时。池非空时取源能直接走 pick，
        //   不存在这个死等，所以那种情况下压掉是纯赚。</p>
        final boolean suppressible = sessionCoversThisTitle()
                && (!detailFallbackSearching || detailFallbackCacheEntryUsable());
        if (suppressible) {
            Set<String> sessionSearched =
                    SearchSession.getShared().searchedKeysFor(detailFallbackAnchorTitle);
            if (!sessionSearched.isEmpty()) {
                int before = detailFallbackPoolAskedKeys.size();
                detailFallbackPoolAskedKeys.addAll(sessionSearched);
                if (detailFallbackPoolAskedKeys.size() != before) {
                }
            }
            LOG.sw("[FB] poolSuppress reason=liveSearch"
                    + " task=t" + detailFallbackPoolTaskId
                    + " asked=" + detailFallbackPoolAskedKeys.size()
                    + "/" + detailFallbackSourceOrder.size()
                    + " sessionSearched=" + sessionSearched.size()
                    + " poolSize=" + detailFallbackUsableCandidateCount());
            // 轮次随时可能结束（全部搜完 / 用户离开搜索页），挂个短重查。
            // 不挂的话没人会再调进来，这条腿就永远不动了。
            scheduleBusyRefill();
            return;
        }
        // 并发 = 当前档位；加载详情期间再降到忙时档（8），保护详情页请求。
        int cap = detailFallbackPoolConcurrency;
        if (detailFallbackLoadingCandidate && cap > DETAIL_FALLBACK_MAX_SEARCH_BUSY) {
            cap = DETAIL_FALLBACK_MAX_SEARCH_BUSY;
        }
        // 全部源都已发出 + 无在途 → 攒池任务结束
        if (detailFallbackNextSourceIndex >= detailFallbackSourceOrder.size()
                && detailFallbackPendingSources.isEmpty()) {
            finishPoolFillingAllAsked();
            return;
        }
        ensureDetailFallbackSearchExecutor();

        // ★ v99-r60：搜索页已经搜过的源（快照，一次调用取一次）。
        //   切源轮次不压制整条腿，但不能再把搜索页搜过的源重发一遍。
        final Set<String> sessionSearched =
                SearchSession.getShared().searchedKeysFor(detailFallbackAnchorTitle);
        final boolean useSessionSkip = !sessionSearched.isEmpty();

        List<String> dispatched = new ArrayList<>();
        boolean skippedAny = false;
        boolean skippedSession = false;
        while (detailFallbackPendingSources.size() < cap
                && detailFallbackNextSourceIndex < detailFallbackSourceOrder.size()) {
            final String searchKey = detailFallbackSourceOrder.get(detailFallbackNextSourceIndex++);
            // ★ v99-r59：上次（或上一进程）已经问过的源直接跳过 —— 不发请求、不占并发名额。
            //   这正是「续跑」而不是「重跑」：上次问到 next=60 时退出，
            //   这次只发剩下的 41 个，前 60 个一个请求都不重发。
            if (detailFallbackPoolAskedKeys.contains(searchKey)) {
                skippedAny = true;
                continue;
            }
            // ★ v99-r60：搜索页已经搜完的这个源也跳过 —— 两条腿搜同一批源
            //   是本轮要消灭的东西（真机 210 次请求 vs 101 次就够）。
            //   只认「已跑完」不认「在途」：在途那几个源随时可能被搜索页丢弃
            //   （轮次取消 / Activity 销毁），我们自己问就不会漏。
            if (useSessionSkip && sessionSearched.contains(searchKey)) {
                skippedAny = true;
                skippedSession = true;
                continue;
            }
            detailFallbackPoolAskedKeys.add(searchKey);
            final String searchTitle = detailFallbackAnchorTitle;
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
        // ★ v99-r61：切源档因会话跳过的键也要折进已问集合。
        //   压制分支本来就折，但派发循环这条路径以前不折 ⇒ 用户切源后立刻退出
        //   （来不及再触发一次压制）时，这批键不会落盘，下次进片又会重问一遍。
        //   必须折在 while <b>之后</b>：提前折会让 askedKeys 先命中、skipS 日志
        //   再也区分不出「因会话跳过」，两种跳过原因就混在一起了。
        if (skippedSession) {
            int before = detailFallbackPoolAskedKeys.size();
            detailFallbackPoolAskedKeys.addAll(sessionSearched);
            if (detailFallbackPoolAskedKeys.size() != before) {
                }
        }
        if (!dispatched.isEmpty()) {
            // ★ v99-r58：日志改走 LOG.sw（LOG.i 的 tag 会被真机 logcat 过滤掉），
            //   并把 r57 的 busy=0/1 换成更有信息量的 cap / front —— 合并成单队列后，
            //   「并发档位 + 是否在前台窗口」才是判断行为的关键。
            LOG.sw("[FB] dispatch task=t" + detailFallbackPoolTaskId
                    + " n=" + dispatched.size()
                    + " inflight=" + detailFallbackPendingSources.size()
                    + " cap=" + cap
                    + " front=" + (detailFallbackPoolFrontWindowClosed ? 0 : 1)
                    + " next=" + detailFallbackNextSourceIndex + "/" + detailFallbackSourceOrder.size()
                    // ★ v99-r60：skip 保留 r59 的总口径（有没有跳过），另加 skipS
                    //   专指「因搜索页已搜过而跳过」—— 两种跳过原因必须能分开，
                    //   否则算不出丙到底省了多少请求。
                    + " skip=" + (skippedAny ? 1 : 0)
                    + " skipS=" + (skippedSession ? 1 : 0));
        } else if (skippedAny) {
            // ★ v99-r59：剩下的源<b>全都问过了</b>，一个请求都没发就扫完了队列表。
            //   必须当场收尾：此时既没有在途请求、也没有结果回调会再调进来，
            //   任务会一直挂在 detailFallbackPoolFilling=true 上，下次进片还会走到这里。
            //   （不能改走 scheduleBusyRefill —— 那要多等 300ms，且同样没人触发它。）
            if (detailFallbackNextSourceIndex >= detailFallbackSourceOrder.size()
                    && detailFallbackPendingSources.isEmpty()) {
                finishPoolFillingAllAsked();
                // 任务已结束，直接返回：否则会继续走到下面的 8s 定时器补挂，
                // 留下一个只会空转的定时器（stopPoolFilling 已经摘过它了）。
                return;
            }
        } else if (detailFallbackLoadingCandidate) {
            // 名额被占满、一个都没发出 ⇒ 稍后重试补位，避免"这一轮就这么算了"
            scheduleBusyRefill();
        }
        if (!detailFallbackSearchTimeoutScheduled) {
            detailFallbackSearchTimeoutScheduled = true;
            llLayout.postDelayed(detailFallbackTimeout, DETAIL_FALLBACK_SEARCH_TIMEOUT_MS);
        }
    }

    /**
     * 全部源都已扫过且无在途 ⇒ 攒池任务收尾。
     *
     * <p>★ v99-r59 从 {@link #scheduleDetailFallbackSearch} 里抽出来，因为现在有<b>两个</b>入口：</p>
     * <ol>
     *   <li>方法顶部的常规收尾（有在途请求回来触发）；</li>
     *   <li>dispatch 循环「跳过已问源」后一次请求都没发就扫完了表 ——
     *       这种时候没有在途请求、也没有回调会再进来，只能当场收尾，
     *       否则任务永远挂在 {@code detailFallbackPoolFilling=true} 上。</li>
     * </ol>
     */
    private void finishPoolFillingAllAsked() {
        // stopPoolFilling 会把 searching 置 false，故先记下本轮是否需要驱动取源
        final boolean driveSwitch = detailFallbackSearching;
        stopPoolFilling("allAsked");
        if (driveSwitch) {
            loadNextDetailFallbackSource();
        }
    }

    /**
     * ★ v99-r60：搜索页是否有一轮覆盖<b>本部片</b>的搜索正在跑。
     *
     * <p>title 必须严格相等：注册表是跨轮次、跨影片累积的，若不比对片名，
     * 上一部片的存活标志会让这一部片的填充腿被白压一整轮。</p>
     */
    private boolean sessionCoversThisTitle() {
        if (TextUtils.isEmpty(detailFallbackAnchorTitle)) {
            return false;
        }
        return SearchSession.getShared().hasLiveSearch(detailFallbackAnchorTitle);
    }

    /**
     * 已问源集合是否已覆盖当前全部可搜源。
     *
     * <p>★ v99-r59：这是「跑完不重跑」的唯一判据。三个设计要点：</p>
     * <ul>
     *   <li><b>不需要额外的源集合指纹</b>：源扩容时新源的 key 不在集合里 ⇒ 覆盖度判假 ⇒
     *       允许重跑；源缩小时判真直接跳过也正确（没有新源可问）。</li>
     *   <li><b>不能用候选数代替</b>：池子小恰恰说明源站确实没资源，那种片每次都重跑
     *       101 个源是纯浪费；池子满 40 是上限截断、不等于问完了。</li>
     *   <li><b>集合在彻底复位（换片/退出）时清空</b>，所以不会串片。</li>
     * </ul>
     */
    private boolean isAllSearchableAsked() {
        if (detailFallbackPoolAskedKeys.isEmpty() || detailFallbackSourceOrder.isEmpty()) {
            return false;
        }
        for (String key : detailFallbackSourceOrder) {
            if (!detailFallbackPoolAskedKeys.contains(key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * ★ v99-r57：忙时被挡下的补位，延迟 {@link #DETAIL_FALLBACK_BUSY_REFILL_DELAY_MS} 重试。
     *
     * <p>保证「没问过的源最终一定会被问到」，补位不再依赖"结果回调恰好落在空闲时刻"
     * 这种运气。一次进页只会挂一个重试（{@code detailFallbackBusyRefillScheduled} 节流）。</p>
     */
    private void scheduleBusyRefill() {
        if (detailFallbackBusyRefillScheduled || llLayout == null) {
            return;
        }
        if (detailFallbackNextSourceIndex >= detailFallbackSourceOrder.size()) {
            return;
        }
        detailFallbackBusyRefillScheduled = true;
        llLayout.postDelayed(detailFallbackBusyRefill, DETAIL_FALLBACK_BUSY_REFILL_DELAY_MS);
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


    /**
     * 8s {@link #DETAIL_FALLBACK_SEARCH_TIMEOUT_MS} 到点：<b>只是前台窗口结束</b>。
     *
     * <p>★ v99-r58：这里<b>不再结束攒池</b>。历史的两种写法都有问题：</p>
     * <ol>
     *   <li>把游标推到底 → 等于宣布"剩下的源都问过了"，那几十个源再也不会被问；</li>
     *   <li>r57 移交给后台队列 → 已出发的在途请求会因 token 失效被丢掉，要重新问一遍。</li>
     * </ol>
     * 现在攒池本身就是跨轮常驻的一条队列：到点只解除与「本轮切源」的耦合，
     * 降为后台并发继续把剩下的源问完，直到 {@link #DETAIL_FALLBACK_POOL_FILL_TIMEOUT_MS}。</p>
     */
    private void finishDetailFallbackSearchOnTimeout() {
        if (!detailFallbackPoolFilling || !detailFallbackSearching) {
            return;
        }
        detailFallbackSearchTimeoutScheduled = false;
        detailFallbackSearchTimedOut = true;
        // 前台窗口关闭：不再驱动切源、不再记账、并发降为后台档。
        detailFallbackSearching = false;
        detailFallbackSearchCollecting = false;
        detailFallbackPoolFrontWindowClosed = true;
        detailFallbackPoolConcurrency = DETAIL_FALLBACK_POOL_CONC_BACKGROUND;
        LOG.sw("[FB] poolFrontWindowEnd task=t" + detailFallbackPoolTaskId
                + " conc=" + detailFallbackPoolConcurrency
                + " next=" + detailFallbackNextSourceIndex + "/" + detailFallbackSourceOrder.size()
                + " pending=" + detailFallbackPendingSources.size()
                + " poolSize=" + detailFallbackUsableCandidateCount());
        if (!detailFallbackLoadingCandidate) {
            loadNextDetailFallbackSource();
        }
        // 继续把剩下的源问完（cap 已降为后台档）
        scheduleDetailFallbackSearch();
    }

    /**
     * ★ v99-r58：攒池任务的<b>唯一收尾口</b>（全部问完 / 120s 硬闸 / 彻底复位）。
     *
     * <p>负责「停派发 + 掐在途 + 落盘 + 打点」，<b>不碰 UI</b> —— 是否要驱动取源由调用方
     * 按「是否还在本轮切源会话内」决定。</p>
     */
    private void stopPoolFilling(String reason) {
        if (!detailFallbackPoolFilling) {
            return;
        }
        detailFallbackPoolFilling = false;
        detailFallbackSearching = false;
        detailFallbackSearchCollecting = false;
        detailFallbackPoolFrontWindowClosed = true;
        if (llLayout != null) {
            llLayout.removeCallbacks(detailFallbackTimeout);
            llLayout.removeCallbacks(detailFallbackPoolFillTimeout);
            llLayout.removeCallbacks(detailFallbackBusyRefill);
        }
        detailFallbackBusyRefillScheduled = false;
        stopDetailFallbackSearchExecutor();
        try {
            OkGo.getInstance().cancelTag(DETAIL_FALLBACK_SEARCH_TAG);
        } catch (Throwable ignored) {
        }
        LOG.sw("[FB] poolFillStop reason=" + reason
                + " task=t" + detailFallbackPoolTaskId
                // ★ v99-r59：asked 报「已问源集合的大小」而不是游标下标。
                //   游标只数本实例扫过的位置，而集合是跨实例恢复 + 本实例累积的真实口径；
                //   续跑（上次问到 60 退出、这次只发剩下 41 个）时游标会是 101/101，
                //   但集合也是 101/101 —— 两者在「全部问完」时一致，在别处集合更有意义。
                + " asked=" + detailFallbackPoolAskedKeys.size()
                + "/" + detailFallbackSourceOrder.size()
                + " next=" + detailFallbackNextSourceIndex
                + "/" + detailFallbackSourceOrder.size()
                + " pending=" + detailFallbackPendingSources.size()
                + " poolSize=" + detailFallbackUsableCandidateCount());
        detailFallbackPendingSources.clear();
        detailFallbackSourceStartMs.clear();
        // ★ v99-r59：已问集合的账要落到它<b>自己那部片</b>的 key 上。
        //   不能用 detailFallbackAnchorTitle —— 换片时锚标题已经翻到新片了（见字段注释）。
        String askedTitle = TextUtils.isEmpty(detailFallbackAskedTitle)
                ? detailFallbackAnchorTitle : detailFallbackAskedTitle;
    }

    // ==================== ★ v99-r58：攒池引擎（单队列常驻） ====================

    /**
     * 攒池引擎的<b>唯一入口</b>：保证这条队列正以「至少 {@code concurrency} 档」在跑。
     *
     * <p><b>原来的两条腿去哪了</b>：原先「切源主搜索」和「静默预热」各跑一份全量
     * （100 / 101 个源，集合几乎完全重叠），既多发一倍请求，出问题也分不清是哪条腿。
     * 现在只有一份 {@code order / nextIndex / pending / token}，唯一变量是并发档位。</p>
     *
     * <p><b>已在跑时只调档、绝不重建队列</b>：重建会把在途请求变成"没身份证明的陌生人"
     * —— 它们回来时令牌已变，被 {@link #onDetailFallbackSearchResult} 的守卫丢弃，
     * 这正是 r57 真机「池子锁死 30」的直接成因。</p>
     *
     * @param concurrency 目标并发档（20 前台 / 8 忙时 / 5 后台）
     * @param reason      仅用于日志：preheat = 详情进页后台攒池；switch = 用户点切源提速
     */
    private void ensurePoolFilling(int concurrency, String reason) {
        if (llLayout == null) {
            return;
        }
        if (TextUtils.isEmpty(detailFallbackAnchorTitle)) {
            LOG.sw("[FB] poolFillSkip reason=" + reason + " emptyAnchor=1");
            return;
        }
        if (detailFallbackPoolFilling) {
            if (concurrency > detailFallbackPoolConcurrency) {
                LOG.sw("[FB] poolFillSpeed reason=" + reason
                        + " conc=" + detailFallbackPoolConcurrency + "->" + concurrency
                        + " task=t" + detailFallbackPoolTaskId
                        + " next=" + detailFallbackNextSourceIndex + "/" + detailFallbackSourceOrder.size());
                detailFallbackPoolConcurrency = concurrency;
            }
            llLayout.removeCallbacks(detailFallbackPoolFillTimeout);
            llLayout.postDelayed(detailFallbackPoolFillTimeout, DETAIL_FALLBACK_POOL_FILL_TIMEOUT_MS);
            scheduleDetailFallbackSearch();
            return;
        }
        if (detailFallbackSourceOrder.isEmpty()) {
            LOG.sw("[FB] poolFillSkip reason=" + reason + " emptyOrder=1");
            return;
        }
        // ★ v99-r59：判据从「实例标志 detailFallbackPoolCompleted」换成
        //   「持久化的已问源集合是否覆盖全部可搜源」。
        //   旧判据挂在实例字段上，而每次进详情页都是新的 Activity 实例
        //   （真机日志 4 部片 4 次 create/destroy）⇒ 该标志恒为 false ⇒
        //   「跑完不重跑」从上线起一次都没生效过（poolFillSkip reason=done 零次）。
        //   ★ 也不能用「候选数是否达到上限 40」代替：池子小恰恰说明源站确实没资源，
        //     那种片每次都重跑 101 个源是纯浪费；而池子满 40 是上限截断、不等于问完了。
        //   ★ 不需要额外的「源集合指纹」：源扩容时新源的 key 不在已问集合里，
        //     覆盖度自然判假允许重跑；源缩小时判真直接跳过也正确（没有新源可问）。
        if (isAllSearchableAsked()) {
            LOG.sw("[FB] poolFillSkip reason=" + reason
                    + " allAskedPersist=1"
                    + " asked=" + detailFallbackPoolAskedKeys.size()
                    + "/" + detailFallbackSourceOrder.size()
                    + " poolSize=" + detailFallbackUsableCandidateCount());
            return;
        }
        detailFallbackPoolFilling = true;
        detailFallbackPoolConcurrency = concurrency;
        detailFallbackPoolFrontWindowClosed = false;
        // ★ v99-r59：这本账从这一刻起归当前这部片（换片落盘时靠它找 key）
        detailFallbackAskedTitle = detailFallbackAnchorTitle == null
                ? "" : detailFallbackAnchorTitle.trim();
        detailFallbackPoolTaskId = ++detailFallbackRequestIndex;
        detailFallbackNextSourceIndex = 0;
        detailFallbackPendingSources.clear();
        detailFallbackSourceStartMs.clear();
        detailFallbackToken = "detail_fallback_" + detailFallbackPoolTaskId;
        detailFallbackBatchToken = detailFallbackToken + "_batch_1";
        detailFallbackBatchStartMs = System.currentTimeMillis();
        llLayout.removeCallbacks(detailFallbackPoolFillTimeout);
        llLayout.postDelayed(detailFallbackPoolFillTimeout, DETAIL_FALLBACK_POOL_FILL_TIMEOUT_MS);
        LOG.sw("[FB] poolFillStart reason=" + reason
                + " conc=" + concurrency
                + " task=t" + detailFallbackPoolTaskId
                + " order=" + detailFallbackSourceOrder.size()
                + " poolSize=" + detailFallbackUsableCandidateCount());
        scheduleDetailFallbackSearch();
    }

    /**
     * 详情加载成功后预约一次后台攒池（延迟 {@link #DETAIL_FALLBACK_PREHEAT_DELAY_MS}）。
     * 一次进页只约一次，由 {@link #detailFallbackPoolTried} 节流。
     */
    private void scheduleDetailFallbackPoolFill() {
        if (detailFallbackPoolTried || detailFallbackPoolFilling) {
            return;
        }
        if (TextUtils.isEmpty(detailFallbackAnchorTitle)) {
            LOG.sw("[FB] poolFillSkip reason=emptyAnchor");
            return;
        }
        detailFallbackPoolTried = true;
        if (llLayout != null) {
            llLayout.removeCallbacks(detailFallbackPoolStart);
            llLayout.postDelayed(detailFallbackPoolStart, DETAIL_FALLBACK_PREHEAT_DELAY_MS);
            LOG.sw("[FB] poolFillScheduled delay=" + DETAIL_FALLBACK_PREHEAT_DELAY_MS
                    + " title=" + detailFallbackAnchorTitle);
        }
    }

    /** 后台档启动：建队列 → 以 {@link #DETAIL_FALLBACK_POOL_CONC_BACKGROUND} 起跑。 */
    private void startPoolFillingBackground() {
        if (isFinishing() || isDestroyed()) {
            LOG.sw("[FB] poolFillSkip reason=activityGone");
            return;
        }
        if (detailFallbackPoolFilling) {
            LOG.sw("[FB] poolFillSkip reason=alreadyFilling");
            return;
        }
        // ★ v99-r59：后台攒池这条路径原先<b>不恢复 Hawk</b> —— 只有切源入口
        //   （startDetailFallback）会调 restoreDetailFallbackCache。结果是：
        //   用户进片后没切源、1.5s 后后台攒池直接起跑时，磁盘上的候选池和
        //   「已问源集合」都读不回来 ⇒ r59 的跳过判据失去依据，照样从头问 101 个源。
        //   放在 alreadyFilling 之后，避免与切源入口的恢复重复做一遍。
        if (prepareDetailFallbackSourceOrder() == null || detailFallbackSourceOrder.isEmpty()) {
            LOG.sw("[FB] poolFillSkip reason=noSearchableSource");
            return;
        }
        ensurePoolFilling(DETAIL_FALLBACK_POOL_CONC_BACKGROUND, "preheat");
    }

    /**
     * 构建攒池队列：取全部可搜源，按站点质量从高到低排序。
     *
     * <p><b>为什么要抽出来</b>：这段原先写在 {@link #startDetailFallback()} 里（只有切源才建队列），
     * 而 r57 的预热用的是 <b>ApiConfig 原序</b>。两条腿两套顺序 ⇒ 同一部片的站位不一致，
     * 且 {@code nextIndex} 无法跨路复用。合并后统一走<b>质量序</b>：先问好站，
     * 正是切源最需要的。</p>
     *
     * @return 本次排序用的快照（供探路复用，省 n 次重复读分）；源数不足 / 异常时为 {@code null}
     */
    private SourceQualityStore.Snapshot prepareDetailFallbackSourceOrder() {
        detailFallbackSourceOrder.clear();
        try {
            List<SourceBean> sourceBeanList = ApiConfig.get().getSourceBeanList();
            if (sourceBeanList != null) {
                for (SourceBean bean : sourceBeanList) {
                    // 与搜索页（FastSearchActivity/SearchActivity）保持一致的筛选条件：
                    // 只要求 isSearchable。此前多加了 isChangeable，导致「可搜但不可换」的源
                    // 被排除，切源池比搜索结果的源少。
                    // 一圈 = 包括当前源在内全部轮转：不排除当前源，也不按「已用」过滤。
                    if (bean != null && bean.isSearchable()) {
                        detailFallbackSourceOrder.add(bean.getKey());
                    }
                }
            }
        } catch (Throwable th) {
            LOG.e("[FB] buildPoolOrder fail: " + th);
        }
        if (detailFallbackSourceOrder.isEmpty()) {
            return null;
        }
        return sortDetailFallbackSourceOrderByQuality();
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
                Movie.Video video = detailFallbackCacheVideo(detailFallbackAnchorTitle, nextSource, videoId);
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
                markSwitchFrom();
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
     * 判断某个搜索结果是不是「同一部片」（切源候选口径）。
     *
     * <p>先做严格相等，再退回归一化相等 —— 各资源站对同一部片的写法常有差异
     * （多余空格、全角/半角、{@code ·}/ {@code /} / {@code -} 等分隔符、
     * 以及 {@code 【} {@code 全集} 之类不影响判定的符号）。只认严格相等时，
     * 这些站明明搜到了也进不了候选池，结果就是「能切的站只剩两三个」。</p>
     *
     * <p><b>★ v65：口径从 {@code same} 升级为 {@code sameFamily}</b> ——
     * 在归一化相等之上，额外容忍「画质/状态/再制作版本」类命名变体
     * （HD/4K/完结/全集/重制版/修复版…），因此同一部片能切到更多站。
     * 但三条硬红线一个没放：<b>序号</b>（第一季≠第二季）、<b>语言</b>（国语版
     * 候选里不出现粤语版）、<b>数字</b>（神雕侠侣1995≠2005、庆余年≠庆余年2），
     * 详见 {@link ShowTitleKey#sameFamily} 的注释。</p>
     */
    private boolean isSameFallbackTitle(String title, String name) {
        return ShowTitleKey.sameFamily(title, name);
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
        final String title = detailFallbackAnchorTitle;
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
                        || !isSameFallbackTitle(detailFallbackAnchorTitle, video.name)) {
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
     * ★ v65：当前候选池是否「明显不完整」。
     *
     * <p>判据 = 可用候选站点数 &lt; 可搜源站点数的一半。可用数用
     * {@link #detailFallbackUsableCandidateCount()}（与轮转同一口径），
     * 可搜源数按 {@code isSearchable()} 统计（与切源全网搜索的派发范围一致）。</p>
     *
     * <p>两个保护：</p>
     * <ul>
     *   <li>可搜源 &lt; 4 时不触发 —— 单源/极小配置下「一半」没有意义，
     *       且这种配置下全网搜索也捞不到更多站；</li>
     *   <li>ApiConfig 异常时保守返回 false —— 补搜是优化，不能拖垮主流程。</li>
     * </ul>
     */
    private boolean detailFallbackPoolLooksThin() {
        if (TextUtils.isEmpty(detailFallbackAnchorTitle)) {
            return false;
        }
        int searchable = 0;
        try {
            List<SourceBean> sourceBeanList = ApiConfig.get().getSourceBeanList();
            if (sourceBeanList != null) {
                for (SourceBean bean : sourceBeanList) {
                    if (bean != null && bean.isSearchable()) {
                        searchable++;
                    }
                }
            }
        } catch (Throwable th) {
            return false;
        }
        if (searchable < 4) {
            return false;
        }
        return detailFallbackUsableCandidateCount() < searchable / 2;
    }

    /**
     * 缓存里当前可用的候选数（去掉不可换源、去重后的真实可选数量）。
     * 用于「抢跳门槛」判断：池子太薄时抢跳会导致原地打转。
     */
    private int detailFallbackUsableCandidateCount() {
        List<Movie.Video> cachedCandidates = poolOf(detailFallbackAnchorTitle);
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
                // ★ v99-r58：守卫换成任务级标志 —— 切源轮次结束不再掐断慢源看护，
                //   否则慢源会永远占着 pending 名额（没有别的路径再把它摘掉）。
                if (!detailFallbackPoolFilling) {
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
                    // ★ v99-r57：不再因 loadingCandidate 而跳过补位；scheduleDetailFallbackSearch
                    //   内部已按忙时上限降级，名额占满时会自己安排延迟重试。
                    scheduleDetailFallbackSearch();
                }
            }
        }, DETAIL_FALLBACK_SOURCE_TIMEOUT_MS);
    }

    // ==================== 方案D：缓存持久化 ====================

    /**
     * 当前可搜源的 key 集合；取不到（null / 空 / 抛异常）时返回 {@code null}，
     * 调用方必须按「不剪」处理 —— 见 {@link #pruneDeadAskedKeys}。
     */
    private Set<String> currentSearchableSourceKeys() {
        try {
            List<SourceBean> sourceBeanList = ApiConfig.get().getSourceBeanList();
            if (sourceBeanList == null || sourceBeanList.isEmpty()) {
                return null;
            }
            Set<String> keys = new HashSet<String>();
            for (SourceBean bean : sourceBeanList) {
                if (bean != null && bean.isSearchable() && bean.getKey() != null) {
                    keys.add(bean.getKey());
                }
            }
            return keys.isEmpty() ? null : keys;
        } catch (Throwable th) {
            return null;
        }
    }

    /** 把候选池落盘到 Hawk，供下次进同一部片直接复用。 */
    /** 候选 → 落盘三元组（slim）。 */
    private static String[] slimOf(Movie.Video v) {
        return new String[]{v.sourceKey, v.id, v.name == null ? "" : v.name};
    }

    /** ★ v99-r67：落盘时保留的基准演员表长度（够做交叉匹配，又不至于把 SP 撑大）。 */
    private static final int REF_ACTOR_DIGEST_MAX = 60;


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
     *
     * @param snapshot {@link #sortDetailFallbackSourceOrderByQuality()} 返回的快照。
     *                 非 null 时直接复用（<b>0 次 Hawk 读</b>）；null（源数不足或
     *                 排序异常）时回退逐条 {@link #detailFallbackSourceScore}。
     *
     * <p><b>★ v63 P1</b>：排序后「最高分」就是 {@code detailFallbackSourceOrder}
     * 的第一个合格项，探路本不需要再算一遍分数。此前这里对每个源单独
     * {@code score()}，与建快照的那 n 次读取完全重复（实测 100 源多读 100 次）。</p>
     */
    private String pickBestProbeSource(SourceQualityStore.Snapshot snapshot) {
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
            double score = snapshot != null ? snapshot.get(key) : detailFallbackSourceScore(key);
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
     *
     * <p><b>★ v63 P1：返回本次快照</b>，供 {@link #pickBestProbeSource} 复用。
     * 此前探路要再对每个源调一次 {@code score()}，与这里完全重复的 n 次读取
     * （实测 100 源：切源一次共 200 次读，其中 100 次是白读的）。
     *
     * @return 本次排序用的快照；源数不足 / 异常时为 {@code null}（调用方回退原逻辑）
     */
    private SourceQualityStore.Snapshot sortDetailFallbackSourceOrderByQuality() {
        if (detailFallbackSourceOrder.size() <= 1) {
            return null;
        }
        try {
            final SourceQualityStore.Snapshot snapshot =
                    SourceQualityStore.snapshot(detailFallbackSourceOrder);
            if (!snapshot.hasAnyHistory()) {
                // 冷启动：全是中性分，排序结果 == 原顺序，直接省掉这一轮。
                // ★ v63：仍然返回快照 —— 分数虽全为中性，但探路复用它即可零读取完成。
                return snapshot;
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
            return snapshot;
        } catch (Throwable th) {
            // 排序失败不影响主流程：保持原序（等价于「无质量数据」的冷启动行为）
            LOG.e("sortDetailFallbackSourceOrderByQuality fail: " + th);
            return null;
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

    /**
     * ★ v70：当前正在播放的源 key，与 {@link #isCurrentPlayingSource} 完全同一口径
     * （sourceKey → previewVodInfo → vodInfo 依次兜底）。
     * 供开新圈时把入口源计入「本圈已轮过」，防止第二跳切回入口源。
     */
    private String currentPlayingSourceKey() {
        if (!TextUtils.isEmpty(sourceKey)) {
            return sourceKey;
        }
        if (previewVodInfo != null && !TextUtils.isEmpty(previewVodInfo.sourceKey)) {
            return previewVodInfo.sourceKey;
        }
        if (vodInfo != null && !TextUtils.isEmpty(vodInfo.sourceKey)) {
            return vodInfo.sourceKey;
        }
        return "";
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
        if (TextUtils.isEmpty(detailFallbackAnchorTitle) || sourceViewModel == null) {
            return;
        }
        try {
            List<Movie.Video> latest =
                    SearchSession.getShared().getCandidates(detailFallbackAnchorTitle);
            if (latest != null && !latest.isEmpty()) {
                cacheDetailFallbackCandidates(detailFallbackAnchorTitle, latest);
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
        List<Movie.Video> cachedCandidates = poolOf(title);
        if (cachedCandidates == null) {
            cachedCandidates = new ArrayList<>();
            poolPut(title, cachedCandidates);
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
                            }
            }
        }
        // ★ v99-r62：<b>这个复数入口也必须裁</b>。
        //   r58~r61 只在单数入口 {@link #cacheDetailFallbackCandidate} 里调 trim，
        //   而「会话候选合并」（refreshFallbackCandidatesFromSession）与「进页恢复候选」
        //   走的都是这个复数入口 ⇒ 上限形同虚设，真机实测池子顶到 41（>40）。
        //   上限只挂在部分写入口上，它就只是「大多数时候成立」，不是上限。
        trimDetailFallbackPool(title);
    }

    private void cacheDetailFallbackCandidate(Movie.Video video) {
        List<Movie.Video> cachedCandidates = poolOf(detailFallbackAnchorTitle);
        if (cachedCandidates == null) {
            cachedCandidates = new ArrayList<>();
            poolPut(detailFallbackAnchorTitle, cachedCandidates);
        }
        String candidateKey = getDetailFallbackKey(video.sourceKey, video.id);
        for (Movie.Video cachedVideo : cachedCandidates) {
            if (candidateKey.equals(getDetailFallbackKey(cachedVideo.sourceKey, cachedVideo.id))) {
                return;
            }
        }
        cachedCandidates.add(video);
        trimDetailFallbackPool(detailFallbackAnchorTitle);
    }

    /**
     * ★ v99-r58：候选池条数软上限（{@link #DETAIL_FALLBACK_POOL_MAX_CANDIDATES}）。
     *
     * <p><b>为什么必须有</b>：r58 会把池子问满，热门片可能攒到五六十条。
     * 落盘侧已经是精简三元组尚可接受，但内存里存的是完整 {@code Movie.Video}，
     * 而 {@link #FALLBACK_CACHE_MAX_ENTRIES} 管的是<b>部数</b> ——
     * 真实占用 = 部数 × 每部条数，必须两个维度都设闸。</p>
     *
     * <p>按 {@link #detailFallbackSourceScore} 降序保留：被挤掉的一定是历史表现最差的站点，
     * 而一圈切源根本轮不到第 40 个之后。</p>
     */
    private void trimDetailFallbackPool(String title) {
        List<Movie.Video> candidates = poolOf(title);
        if (candidates == null || candidates.size() <= DETAIL_FALLBACK_POOL_MAX_CANDIDATES) {
            return;
        }
        try {
            java.util.Collections.sort(candidates, new Comparator<Movie.Video>() {
                @Override
                public int compare(Movie.Video a, Movie.Video b) {
                    // 降序：质量高的排前面（保留段在头部）
                    return Double.compare(detailFallbackSourceScore(b.sourceKey), detailFallbackSourceScore(a.sourceKey));
                }
            });
            int overflow = candidates.size() - DETAIL_FALLBACK_POOL_MAX_CANDIDATES;
            for (int i = 0; i < overflow; i++) {
                candidates.remove(candidates.size() - 1);
            }
            } catch (Throwable th) {
            LOG.e("[FB] trimPool fail: " + th);
        }
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
        // 本轮切源结束：不再由本轮驱动取源。
        detailFallbackSearching = false;
        detailFallbackSearchCollecting = false;
        // ★ v99-r58：攒池是<b>跨轮任务</b>。旧实现无条件清 batchToken / sourceOrder /
        //   pendingSources / nextIndex 并摘定时器 —— 注释写着"保留仍在跑的批处理"，
        //   实际全清。后果：首批之后结果回调被令牌守卫拦、单源超时被 !searching 拦、
        //   8s 定时器被摘 ⇒ 补位永久停摆（真机：101 源只 dispatch 一次，池子锁死 30）。
        //   现在切源收尾只把攒池降回后台档，队列本身毫发无损。
        // ★ v99-r59（P3）：彻底复位时<b>先把攒池收尾、再清游标</b>。
        //   旧顺序是「先 nextIndex=0 / sourceOrder.clear() / pendingSources.clear()，
        //   再 stopPoolFilling("reset")」，于是 poolFillStop 打出来的 asked / pending
        //   全是清零之后的假值 —— 真机日志里连续三条 asked=0/0 pending=0，
        //   而那一刻实际是 next=60/101 pending=5。「退出时攒到哪了」彻底不可观测，
        //   而这正是判断「已问集合要不要持久化」的唯一依据。
        //   stopPoolFilling 自身不依赖这些待清字段（它只读 anchorTitle 和已问集合），
        //   提前调用是安全的；它内部的落盘也会在下面这次 persist 之前先把数据写下去。
        if (!keepCache) {
            stopPoolFilling("reset");
        }
        final boolean keepFilling = keepCache && detailFallbackPoolFilling;
        if (keepFilling) {
            detailFallbackPoolFrontWindowClosed = true;
            detailFallbackPoolConcurrency = DETAIL_FALLBACK_POOL_CONC_BACKGROUND;
            LOG.sw("[FB] resetKeepCache task=t" + detailFallbackPoolTaskId
                    + " conc=" + detailFallbackPoolConcurrency
                    + " next=" + detailFallbackNextSourceIndex + "/" + detailFallbackSourceOrder.size()
                    + " pending=" + detailFallbackPendingSources.size()
                    + " poolSize=" + detailFallbackUsableCandidateCount());
        } else {
            detailFallbackBatchIndex = 0;
            detailFallbackNextSourceIndex = 0;
            detailFallbackToken = "";
            detailFallbackBatchToken = "";
            detailFallbackSourceOrder.clear();
            detailFallbackPendingSources.clear();
            detailFallbackSourceStartMs.clear();
        }
        detailFallbackSearchTimedOut = false;
        detailFallbackDetailTimedOut = false;
        detailFallbackSearchTimeoutScheduled = false;
        detailFallbackKeepCurrentDetail = false;
        detailFallbackLoadingCandidate = false;
        // 方案D：复位前把候选池落盘，供下次进同一部片直接复用
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
            llLayout.removeCallbacks(detailFallbackBusyRefill);
            // 后台攒池的启动定时器<b>只在「彻底复位」时摘</b>（防退出页面后还在起跑 / 落盘）。
            // 切源收尾走的是 keepCache=true，攒池必须让它继续。
            if (!keepCache) {
                llLayout.removeCallbacks(detailFallbackPoolStart);
            }
        }
        detailFallbackEarlyConsumeScheduled = false;
        detailFallbackBusyRefillScheduled = false;
        if (!keepCache) {
            detailFallbackPoolTried = false;
            // ★ v99-r59（P3）：stopPoolFilling("reset") 已提前到清游标之前调用（见上），
            //   这里不再重复 —— 重复调用会因为 detailFallbackPoolFilling 已被置 false 而空转。
            // ★ 已问集合必须跟着换片一起清：它是「本部片」的账。不清的话下一部片
            //   restore 回来时会叠在旧片的已问集合上，isAllSearchableAsked() 直接判真，
            //   新片的攒池会被整个跳过 —— 一部片都搜不到。
            detailFallbackPoolAskedKeys.clear();
            detailFallbackAskedTitle = "";
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

    /**
     * ★ v99-r56：候选池的「读键」——一律走 {@link ShowTitleKey#familyKey}，不用片名原文。
     *
     * <p><b>为什么要换键</b>（真机实例，2026-10-25）：同一部片《药屋少女的呢喃第三季》
     * 在历史里有两条写法（无空格 / 有空格）。切源时 {@code vod_name} 会被新站的写法覆盖，
     * 于是第二次点进去时锚变成了另一种写法 ⇒ 缓存与磁盘各是一个桶，实测
     * <b>第一次池子 5 个站、第二次 13 个站，交集只有 2 个</b>。用户看到的就是
     * 「同一部片，两次进来能切的站点完全不一样」。</p>
     *
     * <p>{@code familyKey} 把「语言 / 序号 / 主干」三段分别编码，空格与中文数字差异
     * 都被吃掉 ⇒ 两种写法落到同一个键，候选池合并。</p>
     *
     * <p><b>搜索词不受影响</b>：{@link #detailFallbackAnchorTitle} 仍是片名原文
     * （要发给站点搜索，不能带 {@code |季4} 这类结构），只有「存取池子」用本键。</p>
     */
    private List<Movie.Video> poolOf(String title) {
        String key = ShowTitleKey.familyKey(title);
        if (TextUtils.isEmpty(key)) {
            return null;
        }
        return detailFallbackCache.get(key);
    }

    /** {@link #poolOf} 的写入侧，键口径必须一致（详见方法注释）。 */
    private void poolPut(String title, List<Movie.Video> candidates) {
        String key = ShowTitleKey.familyKey(title);
        if (TextUtils.isEmpty(key)) {
            return;
        }
        detailFallbackCache.put(key, candidates);
    }

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
    /** ★ v99-r51：切源会话的"上一跳身份"。只在"开始切源"时写，切源成功路径不会覆写它。 */
    private String switchFromSourceKey = "";
    private String switchFromVodId = "";

    /**
     * ★ v99-r52：记录"这一跳是从哪来的"。
     *
     * <p>必须放在「加载新源详情之前」这一行，而不是 captureLivePlaybackSnapshot 里：
     * 那个方法在 {@code startDetailFallback} 里被调用时 {@code detailFallbackActive} 还是 false
     * （它在后面第 1662 行才置 true），而切源成功后又会再调一次（那时 sourceKey 已是新源）。
     * 放在 loadDetail(fallback=true) 之前，是唯一"一定执行且一定拿到旧身份"的位置。</p>
     */
    private void markSwitchFrom() {
        switchFromSourceKey = sourceKey == null ? "" : sourceKey;
        switchFromVodId = vodId == null ? "" : vodId;
        LOG.sw("[FB] markFrom " + switchFromSourceKey + "/" + switchFromVodId);
    }
    private String fallbackFromFlag = "";
    private int fallbackFromIndex = -1;
    private String fallbackFromName = "";
    private boolean fallbackFromValid = false;

    /**
     * ★ v47：本次跨域换算是否因「主线程禁止同步联网」而被主动放弃。
     *
     * <p>{@link #tryResolveCrossDomainNow} 里的联网是可阻塞调用（EpisodeOnlineResolver
     * 的三个 {@code *Within} 内部是 {@code future.get}，其 Javadoc 明写「禁止在主线程调用」），
     * 因此主线程一律直接放弃。放弃之后落点只剩「按序兜底」这一猜，
     * 需要由异步通道（{@link #tryOnlineCrossDomainResolve}）在落点之后补正。</p>
     *
     * <p>本标志就是「需不需要补位」的判据 —— 比 {@code isMatchTrusted} 更贴合：
     * 后者只对「当前集名带<b>期号</b>」有意义，而这条路径的当前集名带的是<b>日期</b>
     * （{@code leadingOrdinalOf} 遇日期恒返回 -1），会被判成"可信"而把补位全部掐掉。</p>
     */
    private boolean crossDomainSyncSkipped = false;

    /**
     * ★ 补位结果的最长可应用延迟 —— 取值集中在
     * {@link EpisodeOnlineResolver#ASYNC_APPLY_DEADLINE_MS}（与 PlayFragment 共用一份），
     * 调参只改那一处。
     */
    private static final long BACKFILL_MAX_APPLY_DELAY_MS = EpisodeOnlineResolver.ASYNC_APPLY_DEADLINE_MS;

    private boolean detailFallbackActive;
    private boolean detailFallbackSearching;
    private boolean detailFallbackSearchCollecting;
    private boolean detailFallbackSearchTimedOut;
    private boolean detailFallbackDetailTimedOut;
    private boolean detailFallbackSearchTimeoutScheduled;
    /** ★ v99-r57：忙时补位重试是否已挂起（一次只挂一个）。 */
    private boolean detailFallbackBusyRefillScheduled;
    private boolean detailFallbackKeepCurrentDetail;
    /**
     * ★ v99-r65：本次切源是否由用户手动点「换源」触发。
     *
     * <p>决定两件事：① 失败时要不要弹提示（自动续切不能弹）；
     * ② 深层方法（无参 {@code startDetailFallback()}）里的「没有可搜源」分支
     * 要不要自己弹 —— 手动路径由菜单统一弹一次，避免双 Toast 叠在一起。</p>
     */
    private boolean detailFallbackManualSwap = false;
    /**
     * ★ v99-r65：本次切源失败的具体原因（给菜单用）。空串 = 用兜底文案。
     *
     * <p>此前三种完全不同的失败原因（当前源查不到 / 片名缺失 / 没有可搜源）共用一句
     * 「暂无可切换的片源」，用户无法判断是"真的没有"还是"被拦住了"；
     * 而这条路径<b>一行日志都没有</b>，出问题只能靠反推（真机奶酪源那次就是）。</p>
     */
    private String detailFallbackDenyMsg = "";
    /**
     * ★ v99-r76：因「该源没有当前这一集」而续切的次数（<b>独立预算</b>，不占用通用 failover 预算 ——
     * 「找有对应集的源」比「起播失败重试」需要更多次，用户定稿为 6 次）。
     */
    private int detailFallbackAlignFailover = 0;
    /** ★ v99-r76：因「该源没有当前这一集」而续切的最大次数（用户定稿 6）。 */
    private static final int DETAIL_FALLBACK_MAX_ALIGN_FAILOVER = 6;
    private boolean detailFallbackLoadingCandidate;
    private int detailFallbackRequestIndex;
    private int detailFallbackBatchIndex;
    private int detailFallbackNextSourceIndex;
    private String detailFallbackToken = "";
    private String detailFallbackBatchToken = "";
    private String detailFallbackTitle = "";
    /**
     * ★ v66：候选池的「片名锚」—— 首轮进入时的片名原文，之后<b>不随切源漂移</b>。
     *
     * <p><b>为什么需要它</b>：{@link #detailFallbackTitle} 会跟着 {@code vod_name}
     * 被替换成新源的写法（切到 {@code 庆余年HD} 站后它就变成「庆余年HD」）。而候选池
     * 的 key、{@code refreshFallbackCandidatesFromSession} 的读取 title、
     * persist/restore 的 Hawk key 全都用它 ⇒ 切源后池子「裂键」：新 title 下池为空
     * ⇒ usable=false ⇒ 触发（补）全网搜索 ⇒ 回流再按新 title 做 sameFamily 过滤
     * ⇒ 与旧 title 互不为前缀的同族站（重制版/4K…）被滤掉 ⇒ <b>池子级联收缩，
     * 每切一刀少一圈</b>。用户看到的就是「切源候选远比搜索结果少」。</p>
     *
     * <p>锚在 initData（首次进详情）时设定，只在换片（cycleTitle 变化）时重置。
     * 显示用的 {@link #detailFallbackTitle} 照旧随切源漂移（页面上要显示新源写法），
     * 但一切「池/搜索/持久化」语义都改锚不动。</p>
     */
    private String detailFallbackAnchorTitle = "";
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
    /**
     * ★ v65：本轮进页会话是否已为「池子太薄」补过一轮全网搜索。
     *
     * <p>节流字段：一次进页只补一轮，避免用户连点切源反复触发全网搜索；
     * 换片名时重置（新片重新判据）。</p>
     */
    private boolean detailFallbackPoolRefreshed = false;
    /** 当前在途详情请求的「源|影片id」键；超时回调据此精确记软失败，避免整站连坐。 */
    private String detailFallbackTimedOutKey;
    /** 当前在途详情请求的站点 key（与 {@link #detailFallbackTimedOutKey} 配套，供软失败按站粒度记录）。 */
    private String detailFallbackTimedOutSource;
    // ===== ★ v99-r58：攒池引擎（单队列常驻） =====
    /**
     * 攒池引擎是否正在跑 —— <b>任务级</b>标志，不随切源轮次复位。
     *
     * <p><b>为什么必须独立</b>：每条攒池相关的守卫原先都挂在
     * {@link #detailFallbackActive} / {@link #detailFallbackSearching} 上，而这两个是
     * 「一次切源轮次」的状态。切源成功后必走 {@code resetDetailFallbackKeepCache()}，
     * 它无条件把 {@code active=false / searching=false / batchToken=""} 并
     * {@code pendingSources.clear()} ⇒ 首批 20 个源之后：结果回调被 token 守卫拦、
     * 单源超时被 {@code !searching} 拦、8s 定时器被摘。真机实测 101 个源
     * <b>只派发一次</b>，池子锁死在 30，32 次切源全在这 30 个源里转。</p>
     */
    private boolean detailFallbackPoolFilling = false;
    /** 当前并发档位：20（前台）/ 8（加载详情中）/ {@link #DETAIL_FALLBACK_POOL_CONC_BACKGROUND}（后台）。 */
    private int detailFallbackPoolConcurrency = DETAIL_FALLBACK_POOL_CONC_BACKGROUND;
    /**
     * 8s {@link #DETAIL_FALLBACK_SEARCH_TIMEOUT_MS} 前台窗口是否已关闭。
     *
     * <p>关闭后：<b>不再记账</b>（后台慢速回包混进 SourceQualityStore 会污染站点排序）、
     * <b>不安排提前消费</b>（没人等着看画面）、并发降到后台档，但攒池继续。</p>
     */
    private boolean detailFallbackPoolFrontWindowClosed = true;
    /**
     * ★ v99-r59：本部片已经问过哪些源（{@code sourceKey} 集合，含问失败的）。
     *
     * <p><b>它是跨实例的</b>：进页时从 Hawk 恢复（{@link #restoreDetailFallbackCache}），
     * 派发时逐个加入，任务收尾 / 彻底复位时落盘（{@link #persistDetailFallbackCache}）。
     * r58 用实例字段 {@code detailFallbackPoolCompleted} 记「问完了」，
     * 而每次进详情页都是新的 Activity 实例 ⇒ 那个标志恒为 false，判据从未生效。</p>
     *
     * <p>换片（{@code resetDetailFallback(false)}）时清空 —— 它是「本部片」的账，
     * 不清就会串片：下一部片 restore 回来叠在旧片集合上，{@link #isAllSearchableAsked}
     * 直接判真，新片的攒池会被整个跳过。</p>
     */
    private final Set<String> detailFallbackPoolAskedKeys = new HashSet<>();
    /**
     * ★ v99-r59：当前已问集合<b>属于哪部片</b>（与 {@link #detailFallbackAnchorTitle} 同口径）。
     *
     * <p><b>为什么必须单独记</b>：换片时 {@code initData()} 是<b>先把</b>
     * {@code detailFallbackAnchorTitle} 翻到新片、<b>再</b>走
     * {@code loadDetail → resetDetailFallback(false)}。也就是说进到复位方法里时，
     * 锚标题已经是新片，而内存里的已问集合还是<b>上部片</b>的账。
     * 不清的话新片进来 {@link #isAllSearchableAsked()} 直接判真，
     * <b>整部片一个源都不搜</b>（所以彻底复位时必须连带清空）。</p>
     *
     * <p>★ v99-r73：已问集合不再落盘（r58/r59 的候选池持久化整体回退）——
     * 落盘会丢候选元数据，进而引发同站择优打平、选错片（r67~r72 整串连锁）。
     * 进程内判据（poolSuppress 的 liveSearch 并入 / 攒池跳过门）保留不变。</p>
     */
    private String detailFallbackAskedTitle = "";
    /** 攒池任务编号，日志用它串起同一任务的一生（启动/提速/降速/停止）。 */
    private int detailFallbackPoolTaskId = 0;
    /** 是否已预约过后台攒池（一次进页只约一次，换片重置）。 */
    private boolean detailFallbackPoolTried = false;
    /**
     * ★ v66：切源择优的「基准资源」—— 首轮进详情时用户看的那条视频快照。
     *
     * <p>同站多资源时（如《人世间》在同一站有两部，详情/封面都不同），
     * {@link #detailFallbackFirstAliveId} 用它挑与基准最像的一条。基准在首轮
     * 详情成功返回时记录（{@code !fallbackResult} 的路径），之后<b>不随切源更新</b>；
     * 换片时置空重新记录。</p>
     */
    private Movie.Video detailFallbackRefVideo = null;
    /**
     * ★ v67：基准资源的总集数（首轮详情 seriesMap 就绪时记录，换片重置）。
     *
     * <p>「同站多资源择优」的兜底信号：当两条同名剧的元数据贫瘠到无从区分
     * （封面/演员/年份/类型全同或全空，打分全是 0）时，<b>集数是详情级最强信号</b> ——
     * 另一部同名剧的集数通常与正片悬殊（如《人世间》58 集 vs 某条目 20 集）。
     * 详情返回时校验：悬殊 ⇒ 把该候选记死 + 自动续切下一站。</p>
     */
    private int detailFallbackRefEpisodes = 0;

    /** 已开过的圈数，仅用于日志观察轮转是否正常推进。 */
    private int detailFallbackCycleCount;
    /**
     * ★ v69：本圈实际使用的切源顺序（「圈内冻结」的载体）。
     *
     * <p><b>为什么需要它</b>：本圈的轮转顺序在<b>开圈那一刻</b>按站点质量分定下来，
     * 之后本圈内「已经轮过的部分」不再变动 —— 否则每切一次源都整表重排，
     * 用户看到的顺序会一路漂移，与「按得分从高到低切一遍」的预期对不上。</p>
     *
     * <p><b>冻结的粒度</b>：只冻「已经轮过的部分」的相对顺序；
     * 还没轮到的部分仍按最新得分重排，池子里新回流的站点也会按得分插进
     * 未轮过区间 —— 不会因为冻结而错过好站（详见 {@link #currentCycleOrder}）。</p>
     *
     * <p>开新圈 / 换片 / 首次进入时清空，由 {@link #currentCycleOrder} 重建。</p>
     */
    private final List<String> detailFallbackCycleFrozenOrder = new ArrayList<>();
    /**
     * ★ v69：{@link #buildFallbackCycleOrder} 本次建的质量分快照。
     *
     * <p><b>仅供日志</b>：{@link #logCycleOrder} 要用分数，而排序时快照已经建好了，
     * 再建一次就是重复的 n 次 Hawk 读（v63 才优化掉的老毛病）。
     * 单次调用内有效，<b>不参与任何决策</b>。</p>
     */
    private SourceQualityStore.Snapshot detailFallbackCycleOrderSnapshot = null;
    /**
     * 最近一次选源命中的<b>具体候选影片 id</b>。
     *
     * <p>同一个站点在缓存里可能有多条同名候选（MV/预告在前、正片在后）。
     * 选源时已按「未被拉黑」挑出具体一条，后续 loadDetail / 站点指示必须沿用
     * 这一条，不能各自再去取「该站第一条」——否则拉黑了 MV 之后仍会反复切到 MV。
     */
    private String detailFallbackPickedVideoId = "";
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

    /**
     * ★ v99-r57：忙时补位重试。
     *
     * <p>补位被 {@code detailFallbackLoadingCandidate} 挡下时挂一次，
     * {@link #DETAIL_FALLBACK_BUSY_REFILL_DELAY_MS} 后重试，保证"没问过的源不会被漏掉"。</p>
     */
    private final Runnable detailFallbackBusyRefill = new Runnable() {
        @Override
        public void run() {
            detailFallbackBusyRefillScheduled = false;
            if (detailFallbackPoolFilling) {
                scheduleDetailFallbackSearch();
            }
        }
    };

    // ===== ★ v99-r58：攒池引擎的两个定时器 =====
    /** 后台档启动（详情成功回调里 postDelayed，错开起播高峰）。 */
    private final Runnable detailFallbackPoolStart = new Runnable() {
        @Override
        public void run() {
            startPoolFillingBackground();
        }
    };
    /** 120s 硬闸：停止派发 + 掐断在途 + 落盘。已在 {@link #stopPoolFilling} 内统一收尾。 */
    private final Runnable detailFallbackPoolFillTimeout = new Runnable() {
        @Override
        public void run() {
            stopPoolFilling("hardTimeout");
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
            // ★ v99-r35：落点落在【衍生条目】上时纠正为正片；纠正不到就不落（-1）。
            //
            //   实测（披荆斩棘2026，logcat 第 3 跳）：
            //     旧源「20260815第1期上」→ 金鹰点播「预热直播第1期」 ✗
            //   该列表里「预热直播第1期」排在「第1期：初舞台（上）」之前，两者期号同为 1，
            //   模糊打分相同 ⇒ 取了先出现的衍生条目。预热直播是预热栏目，不是正片。
            //
            //   取不到正片就返回 -1（保持原集不动）：切错集用户毫无察觉，保持不动他立刻能发现。
            // ★ v99-r39：当前集【本身也是衍生栏目】时不做这个纠正。
            //   实测（披荆斩棘2026 hhzy↔jisu）：当前在播「聚乐部第2期」「宿舍日记第2期」
            //   —— 两者本身就是衍生栏目，上面却把落点「第2期一公挑战赛（上）」判成衍生
            //   再纠正过去，等于把「衍生 → 正片」当成修复，实际是切错了内容。
            //   用户本来就在看衍生内容时，保持落到目标的衍生产物才是正确的。
            // ★ v99-r42：落点是「同日期确定性命中」时不做这条纠正。
            //   真实源里「只给日期不写内容」的列表（{@code 第20260831期}）对上写了内容的
            //   列表（{@code 第20260831期加更版}）时，落点会被判成衍生、而同期号正片根本
            //   不存在 ⇒ 按原逻辑直接 -1，上层再退回按原下标就近落地 = 切错集。
            //   同日期且目标源那天只有这一条，是"那天就只发了这一集"的确定性证据。
            if (resolved >= 0 && resolved < targetList.size()
                    && EpisodeNameMatcher.isNonMainEntryName(targetList.get(resolved).name)
                    && !EpisodeNameMatcher.isNonMainEntryName(currentSeries.name)
                    && !EpisodeNameMatcher.isDateCertainLanding(
                            currentSeries.name, targetList.get(resolved).name, tgtNames)) {
                int mainIdx = EpisodeNameMatcher.findMainCounterpart(currentSeries.name, tgtNames);
                if (mainIdx >= 0 && mainIdx < targetList.size()) {
                    resolved = mainIdx;
                } else {
                    resolved = -1;
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

        // ---------- 第1.2层：日期+期号混合式名的"期数式"直配 ----------
        // 采集源普遍有 `20260906第2期上`（日期打头 + 期号 + 分段）这种写法，
        // 最大资源整库如此。parse 是日期优先 ⇒ 整名被判成日期域 ⇒ 上面第1层的
        // 同域匹配直接被 gating 掉（实测 score 全 0、findIndex 返回 -1），
        // 只能退化到跨域换算或裸下标兜底 —— 曾实测切到「加更版第2期」。
        // 但名字里本来就带着与目标源**完全同形**的 `第2期上` —— 确定性证据，必须先试。
        //
        // ★ 只做确定性命中，这一层排在很前面，必须零误判：
        //   ① 提取出的形态必须带**显式分段**（上/中/下）—— 分段是唯一能把
        //      「第2期上 / 第2期下 / 加更版第2期」区分开的判据（三者期号都是 2）；
        //   ② 目标列表里必须存在与之**完全同名**的一条 —— 不用模糊打分，
        //      因为实测 `企划第2期` 会被模糊匹配到诱饵 `第2期`、
        //      `大放送第1期` 会被匹配到 `第1期`、`第4期` 会撞上孤立诱饵 `第4期`。
        //   两条同时满足才采纳；否则原样放行给下面的层处理（零回归风险）。
        String ordinalForm = EpisodeNameMatcher.hybridOrdinalForm(currentName);
        if (!TextUtils.isEmpty(ordinalForm)
                && EpisodeNameMatcher.extractPart(ordinalForm) != EpisodeNameMatcher.PART_NONE) {
            int exactForm = targetNames.indexOf(ordinalForm);
            if (exactForm >= 0) {
                return exactForm;
            }
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
            // ★ v47：主线程不做同步联网换算 —— 它是 future.get 阻塞调用（见
            //   EpisodeOnlineResolver 的 isMainThread 守卫与其类注释「查询必须在后台线程调用」）。
            //   本方法的实际调用链含 detailResult 的 LiveData 观察者（主线程），
            //   基线曾因此实测独占主线程 2528ms→7355ms（MIUI Scout WARNING→HANG）。
            //   这里**主动放弃并记下标志**，落点之后由 tryOnlineCrossDomainResolve 异步补正；
            //   顺带省掉一次「必然被底层守卫拒绝」的无效调用。
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                crossDomainSyncSkipped = true;
                return -1;
            }
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
    /**
     * 最近一次「在线校正」实际改写的落点，格式 {@code playFlag|playIndex}。
     *
     * <p>★ v99-r40：自激循环熔断。链路是
     * {@code applyOnlineResolvedIndex → setTvPlayUrl → syncPlayingVodInfo → tryOnlineCrossDomainResolve → applyOnlineResolvedIndex}。
     * 校正落地后播放对象就是目标集，{@code syncPlayingVodInfo} 回传的 {@code landedIndex}
     * 会等于刚写进去的 {@code playIndex}，于是守卫（flag 相同 && playIndex==landedIndex）
     * 恒成立，解析结果也恒等于该下标 ⇒ 每轮都"再校正到同一集"并再次重驱播放，
     * 实测 58 秒内重放了 2608 次（约 45 次/秒），播放永远起不来。</p>
     *
     * <p>只有<b>成功改写过</b>才记账，因此不会挡掉正常解析：</p>
     * <ul>
     *   <li>解析未命中 → 不 apply → 不记账，下次照常解析；</li>
     *   <li>用户手动换集/换线路 → {@code landedIndex} 变化 → key 不同 → 照常解析。</li>
     * </ul>
     */
    private String lastOnlineAppliedKey = null;

    private void tryOnlineCrossDomainResolve(final String currentName, final String targetFlag,
                                             final List<VodInfo.VodSeries> targetList, final int landedIndex) {
        if (TextUtils.isEmpty(currentName)) return;
        if (targetList == null || targetList.size() < 2) return;
        // ★ v99-r40：这个落点已经是在线校正的结果了，再查一次只会得到同一个答案。
        if (lastOnlineAppliedKey != null && lastOnlineAppliedKey.equals(targetFlag + "|" + landedIndex)) {
            return;
        }
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
        final long scheduledAtMs = System.currentTimeMillis();
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
                        applyOnlineResolvedIndex(targetFlag, targetList, landedIndex, resolved, scheduledAtMs);
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
                                          int landedIndex, int index, long scheduledAtMs) {
        if (isFinishing()) return;
        if (vodInfo == null || targetList == null || index < 0 || index >= targetList.size()) return;
        if (!TextUtils.equals(vodInfo.playFlag, targetFlag)) return;
        if (vodInfo.playIndex != landedIndex) return;
        // ★ v99-r40：解析结果就是当前所在集 ⇒ 无需改写、更无需重驱播放。
        //   这一条直接切断自激循环（详见 lastOnlineAppliedKey 的注释）：
        //   循环态的正是 index == landedIndex == playIndex。
        if (index == vodInfo.playIndex) return;

        // ★ 迟到的结果**不再丢弃** —— 只要用户还停在本次落点上（上面两条校验），
        //   就应用并重驱。
        //
        //   为什么不再"已起播就丢弃"：切源的锚点取自**实际播放对象**
        //   （captureLivePlaybackSnapshot 用的是 previewVodInfo，不是 vodInfo），
        //   丢弃等于让"错的那一集"继续既当播放内容、又当下一次切源的锚点，
        //   错误会一路带下去。用户明确表态：只要最终落到正确的集，
        //   中间跳一下可以接受。
        //
        //   只留一条安全网：迟到过久（网络重试等异常）才放弃，
        //   避免用户已经看了很久才被突然拽走。
        if (scheduledAtMs > 0 && System.currentTimeMillis() - scheduledAtMs > BACKFILL_MAX_APPLY_DELAY_MS) {
            LOG.sw("[FB] onlineBackfill drop(stale) -> " + targetFlag + "/" + index);
            return;
        }
        final boolean wasPlaying = isPreviewPlaying();

        vodInfo.playIndex = index;
        // ★ v99-r40：记账，供 tryOnlineCrossDomainResolve 熔断同一落点的重复解析。
        lastOnlineAppliedKey = targetFlag + "|" + index;
        for (int i = 0; i < targetList.size(); i++) {
            VodInfo.VodSeries s = targetList.get(i);
            if (s != null) s.selected = (i == index);
        }
        routeSwitchSeries = targetList.get(index);
        seriesAdapter.notifyDataSetChanged();
        setTvPlayUrl(targetList.get(index).url);
        LOG.sw("[FB] onlineBackfill applied -> " + targetFlag + "/" + index
                + " name=" + targetList.get(index).name + " wasPlaying=" + wasPlaying);
        // 重驱播放，让画面真正落到修正后的那一集。
        // 已在播时会看到一次跳动 —— 这是刻意接受的代价：落到正确的集之后，
        // 播放内容与后续切源的锚点才同时变对。
        if (wasPlaying) {
            Toast.makeText(DetailActivity.this,
                    "已校正到 " + targetList.get(index).name, Toast.LENGTH_SHORT).show();
        }
        jumpToPlay();
    }

    /**
     * 预览播放器是否已经在播。
     *
     * <p>现在只用来决定「校正时要不要给用户一个 Toast 说明」——
     * 起播后再校正会有一次可见的跳动，配一句话说明就不会显得莫名其妙。
     * <b>不再用它决定要不要应用补位</b>（见 {@link #applyOnlineResolvedIndex}）。</p>
     *
     * <p>判不出时返回 true —— 宁可多弹一句说明，也不要让用户面对无解释的跳动。</p>
     */
    private boolean isPreviewPlaying() {
        try {
            if (playFragment == null) return false;
            return playFragment.getPlayer() != null && playFragment.getPlayer().isPlaying();
        } catch (Throwable th) {
            return true;
        }
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
    /**
     * ★ v99-r43：就近落点 —— 重写为「日期锚优先，无锚不猜」。
     *
     * <p><b>旧实现的三个 bug</b>（真机日志 2026-10-24 实证）：</p>
     * <ol>
     *   <li>{@code extractOrdinal} 对日期式名字返回的就是日期本身（{@code 20260830上} → 20260830），
     *       拿它和期号（{@code 第2期} → 2）比数值距离纯属噪声 —— 实测把
     *       {@code 20260906第2期上} 配到 {@code 20260821闺女面对面上}（日期最小的那条）；</li>
     *   <li>跨域时 {@code score} 恒 0，于是走 {@code sc > bestScore} 的"取第一个同分者"，
     *       实测 {@code 第2期} 落到列表里第一个日期域条目 {@code 第20260522期}（恰好是第1期）；</li>
     *   <li>两者都不命中时退化为 {@code clamped(fallbackIndex)}，而 fallbackIndex 是<b>源侧下标</b>，
     *       跨源列表构成不同时本身就是错的。</li>
     * </ol>
     *
     * <p><b>新口径</b>（用户口径：{@code 20260808} ≡ {@code 第20260808期} ≡ {@code 20260808期}，
     * 日期即真值）：目标名带日期时，只在<b>候选也带日期</b>的集合里按日期差取唯一最近；
     * 一天多条（平手）或候选都没有日期（纯期号列表）时<b>不做任何猜测</b>，保持原下标，
     * 由上层 {@code sanitizeMainFeatureFallback} 矫正正片属性。宁可不切，不可切错。</p>
     *
     * <p><b>★ v99-r45：纯期号目标列表的兜底</b>。目标名不带日期（{@code 第2期}）而候选也全是
     * 期号式时，按「同期号 + 同分段 + 正片」找一条 —— 这正是真机日志里
     * 「{@code 第2期} 落到 {@code 第2期下}」的缺口：日期锚用不上，旧实现直接退回
     * {@code clamped(源下标)}，而源下标跨源没有意义。仍找不到就保持原位。</p>
     */
    private int nearestEpisodeIndex(VodInfo.VodSeries target, int fallbackIndex, List<VodInfo.VodSeries> list) {
        if (list == null || list.isEmpty()) return Math.max(0, fallbackIndex);
        int clamped = Math.max(0, Math.min(fallbackIndex, list.size() - 1));
        if (target == null || TextUtils.isEmpty(target.name)) return clamped;
        int targetDate = EpisodeNameMatcher.dateOf(target.name);
        if (targetDate > 0) {
            // ★ v99-r45：先按「同日期 + 同分段 + 正片」精确命中。
            //   实测（真机 2026-10-24）：目标源当天拆成上/下且【下排在前面】时，
            //   旧实现按日期差挑唯一最近，上/下两天都是差 0 ⇒ 平手不采纳 ⇒
            //   退回 clamped(源下标)，而源下标恰好指向「下」—— 用户看到的就是切错段。
            int targetPartN = EpisodeNameMatcher.normalizePart(EpisodeNameMatcher.extractPart(target.name));
            for (int i = 0; i < list.size(); i++) {
                VodInfo.VodSeries s = list.get(i);
                if (s == null || TextUtils.isEmpty(s.name)) continue;
                if (EpisodeNameMatcher.dateOf(s.name) != targetDate) continue;
                if (EpisodeNameMatcher.normalizePart(EpisodeNameMatcher.extractPart(s.name)) != targetPartN) continue;
                if (EpisodeNameMatcher.isNonMainEntryName(s.name)) continue;
                return i;                                  // 同日期同分段的正片 ⇒ 就是它
            }
            int bestIndex = -1, bestDiff = Integer.MAX_VALUE, tie = 0;
            for (int i = 0; i < list.size(); i++) {
                VodInfo.VodSeries s = list.get(i);
                if (s == null || TextUtils.isEmpty(s.name)) continue;
                int d = EpisodeNameMatcher.dateOf(s.name);
                if (d <= 0) continue;                       // 候选没有日期 ⇒ 无法按锚比较
                int diff = Math.abs(d - targetDate);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestIndex = i;
                    tie = 1;
                } else if (diff == bestDiff) {
                    tie++;
                }
            }
            // 只有"唯一最近"才采纳；同一天多条并列 ⇒ 锚不足以定案，保持原位
            return (bestIndex >= 0 && tie == 1) ? bestIndex : clamped;
        }
        // 目标名没有日期（纯期号式）：按「同期号 + 同分段 + 正片」找一条
        int targetOrd = EpisodeNameMatcher.extractOrdinal(target.name);
        int targetPart = EpisodeNameMatcher.extractPart(target.name);
        if (targetOrd > 0) {
            int samePart = -1, anyPart = -1;
            for (int i = 0; i < list.size(); i++) {
                VodInfo.VodSeries s = list.get(i);
                if (s == null || TextUtils.isEmpty(s.name)) continue;
                if (EpisodeNameMatcher.extractOrdinal(s.name) != targetOrd) continue;
                if (EpisodeNameMatcher.isNonMainEntryName(s.name)) continue;   // 衍生不参与
                if (anyPart < 0) anyPart = i;
                if (EpisodeNameMatcher.normalizePart(EpisodeNameMatcher.extractPart(s.name))
                        == EpisodeNameMatcher.normalizePart(targetPart)) {
                    samePart = i;
                    break;                                  // 同期号同分段的正片 ⇒ 就是它
                }
            }
            if (samePart >= 0) return samePart;
            // 同期号只有一条正片（目标源没拆分段）也认
            if (anyPart >= 0 && countMainOfOrdinal(list, targetOrd) == 1) return anyPart;
        }
        // 没有任何可用锚：保持原位
        return clamped;
    }

    /** 统计列表里指定期号的【正片】条目数（nearestEpisodeIndex 的唯一性判据）。 */
    private int countMainOfOrdinal(List<VodInfo.VodSeries> list, int ordinal) {
        int n = 0;
        for (VodInfo.VodSeries s : list) {
            if (s == null || TextUtils.isEmpty(s.name)) continue;
            if (EpisodeNameMatcher.extractOrdinal(s.name) != ordinal) continue;
            if (EpisodeNameMatcher.isNonMainEntryName(s.name)) continue;
            n++;
        }
        return n;
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
                                              String newSeriesName, VodInfo oldRecord,
                                              java.util.List<String> newEpNames,
                                              java.util.Map<String, PlayProgressManager.EpCoord> newEpCoords) {
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
        // ★★ v99-r74：换片（点了另一部同名片）时**不迁移进度**。
        //   本方法假设「新旧是同一部片」（同片换源要延续进度），但换片时该前提不成立。
        //   真机实证：看《隐秘而伟大》国产版（ref=28134, 2020）→ 搜索页点韩国版
        //   （ref=29946, 2013）⇒ 国产版的 431676ms（7 分 11 秒）被搬到了韩国版上。
        //   判据用跨站稳定的字段（年份 + 导演），见 {@link FilmIdentity#isSameFilm}。
        if (detailFallbackRefVideo != null
                && !FilmIdentity.isSameFilm(detailFallbackRefVideo.year, detailFallbackRefVideo.director,
                        oldRecord.year, oldRecord.director)) {
            LOG.sw("[FB] migrateSkip reason=otherFilm"
                    + " ref=" + detailFallbackRefVideo.id + "/refYear=" + detailFallbackRefVideo.year
                    + " old=" + oldRecord.id + "/oldYear=" + oldRecord.year
                    + " => 换片，不迁移进度");
            return;
        }
        long oldTime = PlayProgressManager.get(oldRecord.sourceKey, oldRecord.id, oldFlag, oldRecord.playIndex, oldSeriesName);
        LOG.sw("[FB] migrate " + oldRecord.sourceKey + "/" + oldRecord.id + " ep=" + oldSeriesName
                + " -> " + newSourceKey + "/" + newVodId + " ep=" + newSeriesName
                + " oldTime=" + oldTime + " newVal=" + newVal);
        // ★★ v99-r75：同片 ⇒ **整片搬移**（统一到切源时的既有口径：
        //   「每部片只保留最新 10 集记录，并且都随着迁移到新源里去」）。
        //   · 旧源先裁到最新 10 集（KEEP_PROGRESS_EPISODES）—— 只搬有价值的那些
        //   · migrateByVod 逐集搬到新源（新源 save 时会自动裁到 10 集）
        //   · 已搬走的行从旧源删除 ⇒ 同一部片不再在多个源各留一份「当前集」进度
        //     （真机：同一部片 28134 的进度散在 feifancj / 730acd68 / 73b5c89a 三个源）
        PlayProgressManager.trimByVod(oldRecord.sourceKey, oldRecord.id,
                com.github.tvbox.osc.sync.HistorySyncManager.KEEP_PROGRESS_EPISODES);
        // ★ v99-r77：坐标换算到新源（线路 + 该线路内的集下标）。
        //   旧实现沿用旧源的 flag/下标 ⇒ 搬过去的行在新源**永远读不到**
        //   （真机 2026-10-26：同片连切 4 站，migrateVod 的 moved 逐跳
        //   1→2→2→9→10，库里堆出 9 条坐标错乱的行，有效进度被 trimByVod
        //   的 10 条配额挤掉 ⇒ 表现为「切源后进度归零」）。
        int movedRows = PlayProgressManager.migrateByVod(oldRecord.sourceKey, oldRecord.id,
                newSourceKey, newVodId, newEpNames, newEpCoords);
        LOG.sw("[FB] migrateVod " + oldRecord.sourceKey + "/" + oldRecord.id
                + " -> " + newSourceKey + "/" + newVodId + " moved=" + movedRows
                + " eps=" + (newEpNames == null ? 0 : newEpNames.size()));
        if (oldTime > 0) {
            // ★ v99-r71：取证日志 —— 与 PlayFragment.getSavedProgress 的 progressRead 成对。
            //   两边 key 必须完全一致（源/片id/线路/集下标/集名），任何一处不一致都会导致
            //   "迁移写入了、起播读不到"⇒ 从 0 播几秒就把迁移值覆盖掉。
            LOG.sw("[FB] progressWrite key=" + newSourceKey + "/" + newVodId + "/" + newFlag
                    + "/" + newIndex + "/" + newSeriesName + " => " + oldTime);
            // 新源该集已有记忆（整片搬移时可能已搬到）⇒ 以新源为准，不覆盖
            long nowVal = PlayProgressManager.get(newSourceKey, newVodId, newFlag, newIndex, newSeriesName);
            if (nowVal <= 0) {
                PlayProgressManager.save(newSourceKey, newVodId, newFlag, newIndex, newSeriesName, oldTime);
            }
            // ★ v99-r75：旧源这一条已复制到新源 ⇒ 删掉，避免同一部片在多个源各留一份。
            //   （打墓碑 + markDirty ⇒ 同步侧能正确知道"这条被迁走了"）
            PlayProgressManager.delete(oldRecord.sourceKey, oldRecord.id,
                    oldFlag, oldRecord.playIndex, oldSeriesName);
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
        // ★ v99-r54：切源会话内必须「先删上一跳，再写本跳」——顺序错了就全白做。
        //
        //   旧顺序（r50~r53）是 insertVodRecord() 在前、mergeSwitchHop() 在后。而
        //   insertVodRecord() 内部第一件事就是 removeSameNameVodRecords()——按片名把其它源的
        //   同名行全删掉，上一跳那一行**在这里就已经没了**。等 mergeSwitchHop() 拿着
        //   (fromSourceKey, fromVodId) 去查库，永远查不到 ⇒ 真机日志 3/3 全是 ret=0。
        //   也就是说 hopMerge 从来没真正生效过，切源收敛一直只靠"片名归一化"这一条腿，
        //   名字一旦对不上（变体写法）就漏，库里留下多源各一行 ⇒ 历史上限被同一部片占掉多格。
        //
        //   把删除提前之后：删除者是明确的「上一跳身份」（不依赖名字，不受写法变体影响），
        //   随后的 insertVodRecord() 只会再删"除上一跳之外的历史遗留同名行"。
        //   一删一插严格配对 ⇒ 切源不再让历史总数净减少。
        //
        // ★ v99-r53：身份判定必须用 (sourceKey, vodId) 这一对，不能只比 vodId。
        //   实测（真机 02:11）：暴风资源 与 api_23 其实是同一个后端，换源后 vodId 完全相同
        //   （都是 165275）。旧写法把这种跳当成"同一跳"而跳过合并，于是库里留下
        //   (暴风,165275) 与 (api_23,165275) 两条同名记录。
        if (!TextUtils.isEmpty(switchFromVodId) && !TextUtils.isEmpty(switchFromSourceKey)) {
            boolean sameSwitchHop = TextUtils.equals(switchFromSourceKey, sourceKey)
                    && TextUtils.equals(switchFromVodId, vodInfo.id);
            if (!sameSwitchHop) {
                int dbBeforeHop = RoomDataManger.countVodRecord();
                int hopMerged = RoomDataManger.mergeSwitchHop(switchFromSourceKey, switchFromVodId,
                        sourceKey, vodInfo);
                LOG.sw("[FB] hopMerge ret=" + hopMerged + " " + switchFromSourceKey + "/" + switchFromVodId
                        + " -> " + sourceKey + "/" + vodInfo.id + " db=" + dbBeforeHop
                        + "->" + RoomDataManger.countVodRecord());
            }
            switchFromSourceKey = "";       // 本跳已消费，防重复
            switchFromVodId = "";
        }
        if (detailFallbackActive) {
            int merged = RoomDataManger.mergeSameNameVodRecords(sourceKey, vodInfo);
            if (merged > 0) {
                LOG.sw("[FB] mergeSameName removed=" + merged + " keep=" + sourceKey + "/" + vodInfo.id);
            }
        }
        // ★ v99-r54 取证：每次写历史都打印库内条数演变。用户报"档位 15 却只有 14 条"，
        //   靠这一行就能区分是「本次写入净减少」还是「库里本来就这么少」，不必再靠猜。
        int dbBeforeWrite = RoomDataManger.countVodRecord();
        RoomDataManger.insertVodRecord(sourceKey, vodInfo);
        LOG.sw("[FB] histWrite " + sourceKey + "/" + vodInfo.id
                + " db=" + dbBeforeWrite + "->" + RoomDataManger.countVodRecord()
                + " tier=" + HistoryHelper.getHisNum(Hawk.get(HawkConfig.HISTORY_NUM, 0)));
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
        // v99-r3：预览/全屏切换后量一次真实层级（仅调试模式）。
        // 「预览框里一块位置固定的黑长方形」到底是谁，看这份日志就能定性：
        //  · container 尺寸不等于 fragmentRoot / video 尺寸 ⇒ 容器没跟着变大（黑底露出）
        //  · video 尺寸不等于 container ⇒ SurfaceView 没跟着重新布局（洞/画面错位）
        dumpPreviewLayout("setFullPreview(" + full + ")");
        dumpViewTree("setFullPreview(" + full + ")");
    }

    /**
     * v99-r3 取证：把预览链路的实测尺寸 / 屏幕位置 / 可见性打进日志（仅调试模式）。
     *
     * <p>布局在 setFullPreview 返回时往往还没走完 measure/layout，所以延后 600ms 再量一次。</p>
     */
    private void dumpPreviewLayout(final String tag) {
        if (!LOG.isDebugEnabled()) {
            return;
        }
        llPlayerFragmentContainer.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder sb = new StringBuilder("[preview] ").append(tag);
                    sb.append(rect("container", llPlayerFragmentContainer));
                    sb.append(rect("block", llPlayerFragmentContainerBlock));
                    View fragRoot = playFragment == null ? null : playFragment.getView();
                    sb.append(rect("fragRoot", fragRoot));
                    View vv = fragRoot == null ? null : fragRoot.findViewById(R.id.mVideoView);
                    sb.append(rect("video", vv));
                    sb.append(" full=").append(fullWindows).append(" showPreview=").append(showPreview);
                    LOG.i(sb.toString());
                } catch (Throwable t) {
                    LOG.e("[preview] dump failed", t);
                }
            }
        }, 600L);
    }

    /**
     * v99-r3 取证（二）：**扫描整棵视图树**，把「可见且够大」的视图全打出来。
     *
     * <p>为什么必须扫整棵树：那块黑长方形会**盖住控制条的时间标**（用户实测），
     * 说明它的 z 序在控制条之上，是浮在上层的视图，而不是"容器背景露出来"。
     * 只 dump 已知的几个视图抓不到它 —— 只能整棵树扫。</p>
     *
     * <p>拿到日志后直接找 `宽x高` 接近 547x273、`@(0,0~20)` 的那一行，它的 id 就是答案。</p>
     */
    private void dumpViewTree(final String tag) {
        if (!LOG.isDebugEnabled()) {
            return;
        }
        final View root = getWindow().getDecorView();
        root.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder sb = new StringBuilder();
                    // 第 4 个参数是"已打印条数"计数器（int[]，跨递归共享）
                    walkTree(root, sb, 0, new int[1]);
                    LOG.longI("[viewtree] " + tag, sb.toString());
                } catch (Throwable t) {
                    LOG.e("[viewtree] walk failed", t);
                }
            }
        }, 600L);
    }

    private void walkTree(View v, StringBuilder sb, int depth, int[] shown) {
        if (v == null || depth > 10 || shown[0] > 120) {
            return;
        }
        if (v.getVisibility() == View.VISIBLE && v.getWidth() >= 80 && v.getHeight() >= 80) {
            shown[0]++;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            String id;
            try {
                id = v.getId() == View.NO_ID ? "-" : getResources().getResourceEntryName(v.getId());
            } catch (Throwable t) {
                id = "#" + v.getId();
            }
            sb.append('\n').append(v.getClass().getSimpleName()).append('#').append(id)
                    .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                    .append("@(").append(loc[0]).append(',').append(loc[1]).append(')')
                    .append(" bg=").append(bgOf(v));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walkTree(g.getChildAt(i), sb, depth + 1, shown);
            }
        }
    }

    /** 背景简述：ColorDrawable 给 #AARRGGBB，其余给类名（够判断是不是黑色）。 */
    private static String bgOf(View v) {
        try {
            Drawable d = v.getBackground();
            if (d == null) {
                return "null";
            }
            if (d instanceof ColorDrawable) {
                return "#" + Integer.toHexString(((ColorDrawable) d).getColor()).toUpperCase();
            }
            return d.getClass().getSimpleName();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 视图的实测尺寸 + 屏幕坐标 + 可见性（不可见返回 ——）。 */
    private static String rect(String name, View v) {
        if (v == null) {
            return " " + name + "=null";
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        String vis;
        switch (v.getVisibility()) {
            case View.VISIBLE:
                vis = "VISIBLE";
                break;
            case View.INVISIBLE:
                vis = "INVISIBLE";
                break;
            default:
                vis = "GONE";
                break;
        }
        return " " + name + "=" + v.getWidth() + "x" + v.getHeight()
                + "@(" + loc[0] + "," + loc[1] + ")" + vis;
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
                // ★ v99-r60：起播保护窗一关就把攒池的派发补上。
                //   scheduleDetailFallbackSearch() 在窗口期内是直接返回的，
                //   若没有人在这里补一次，任务会一直停在「该派发却没派发」的状态，
                //   直到下一次切源或结果回调才动。
                scheduleDetailFallbackSearch();
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
