package com.github.tvbox.osc.ui.activity;

import static xyz.doikki.videoplayer.util.PlayerUtils.safeTimeMs;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.IntEvaluator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CountDownTimer;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.Epginfo;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.LiveDayListGroup;
import com.github.tvbox.osc.bean.LiveEpgDate;
import com.github.tvbox.osc.bean.LivePlayerManager;
import com.github.tvbox.osc.bean.LiveSettingGroup;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.player.controller.LiveController;
import com.github.tvbox.osc.ui.adapter.LiveChannelGroupAdapter;
import com.github.tvbox.osc.ui.adapter.LiveChannelItemAdapter;
import com.github.tvbox.osc.ui.adapter.LiveEpgAdapter;
import com.github.tvbox.osc.ui.adapter.LiveEpgDateAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingGroupAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingItemAdapter;
import com.github.tvbox.osc.ui.adapter.MyEpgAdapter;
import com.github.tvbox.osc.ui.dialog.LivePasswordDialog;
import com.github.tvbox.osc.ui.tv.widget.ViewObj;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.live.TxtSubscribe;
import com.google.gson.JsonArray;
import org.apache.commons.lang3.StringUtils;

import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.StringReader;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;
import xyz.doikki.videoplayer.player.VideoView;

/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LivePlayActivity extends BaseActivity {
    public static Context context;
    private VideoView<xyz.doikki.videoplayer.player.AbstractPlayer> mVideoView;
    private View switchChannelSnapshotOverlay;
    private ImageView switchChannelSnapshotImage;
    private TextView tvChannelInfo;
    private TextView tvTime;
    private TextView tvNetSpeed;
    private View tvTopStatus;
    private TextView tvResolution;
    private LinearLayout tvLeftChannelListLayout;
    private TvRecyclerView mChannelGroupView;
    private TvRecyclerView mLiveChannelView;
    private LiveChannelGroupAdapter liveChannelGroupAdapter;
    private LiveChannelItemAdapter liveChannelItemAdapter;

    private LinearLayout tvRightSettingLayout;
    private TvRecyclerView mSettingGroupView;
    private TvRecyclerView mSettingItemView;
    private LiveSettingGroupAdapter liveSettingGroupAdapter;
    private LiveSettingItemAdapter liveSettingItemAdapter;
    private List<LiveSettingGroup> liveSettingGroupList = new ArrayList<>();

    public static  int currentChannelGroupIndex = 0;
    private Handler mHandler = new Handler();
    private int resolutionInfoRetryCount = 0;
    private boolean resolutionInfoPending = false;
    /**
     * 分辨率信息任务是否已在队列中。
     *
     * <p>直播缓冲高频抖动时 {@code playStateChanged} 每秒会投十几次 BUFFERED，
     * 每次都 remove+post 一遍这个任务，实际只在最后一次执行 —— 纯属消息队列表抖动。
     * 有这个标记后，排队期间不再重复投递。</p>
     */
    private boolean resolutionInfoRunScheduled = false;
    /** 上一次已处理过的播放状态；用于挡住同一状态被反复投递（直播抖动场景每秒十几次）。 */
    private int lastHandledPlayState = -1;
    private boolean exitingLivePlay = false;
    private static final long EPG_LOAD_DELAY = 1200L;
    private static final int RESOLUTION_INFO_MAX_RETRY = 10;
    private static final long RESOLUTION_INFO_RETRY_DELAY = 300L;
    private static final String DEFAULT_EPG_ADDRESS = "http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}";
    private static final Pattern CATCHUP_TOKEN_PATTERN = Pattern.compile("(\\$?\\{[^}]*\\})");
    private static final Pattern CATCHUP_TAG_PATTERN = Pattern.compile("\\{([^}]*)\\}");
    private final Runnable mLoadEpgRun = new Runnable() {
        @Override
        public void run() {
            if (channel_Name != null && liveEpgDateAdapter != null && liveEpgDateAdapter.getSelectedIndex() >= 0) {
                getEpg(new Date());
            }
        }
    };
    private boolean firstLiveEpgLoad = true;

    private List<LiveChannelGroup> liveChannelGroupList = new ArrayList<>();
    private int currentLiveChannelIndex = -1;
    private int currentLiveLookBackIndex = -1;
    private int currentLiveChangeSourceTimes = 0;
    private boolean allowLiveSwitchPlayer = true;
    /**
     * 本频道已自动换过几次播放器（换台时归零）。
     *
     * <p>上限 2 次 = 最多三种组合：原播放器 → 另一个硬解引擎 → 软解兜底。
     * 软解放最后，保证「优先硬解」不被一次失败就推翻。</p>
     */
    private int livePlayerFailoverStep = 0;
    private static final int LIVE_PLAYER_MAX_FAILOVER = 2;
    /**
     * 本次起播是否已渲染出首帧。
     *
     * <p>用来区分两类失败：<ul>
     *   <li><b>起播失败</b>——还没渲染出首帧就报错。这类几乎都是源侧问题
     *       （DNS 失败 / 连接被拒 / 404 / 协议不支持），换播放器毫无意义：
     *       同一个 URL 用 IJK 硬解、软解、EXO 去试，结果必然一样。</li>
     *   <li><b>播放中断</b>——已经出画面之后才报错。这才可能是解码器或
     *       流中断的问题，值得换播放器重试。</li>
     * </ul></p>
     *
     * <p>实测（CCTV-2）：同一条坏线路上 EXO→IJK硬解→IJK软解 三次尝试全部失败，
     * 每次还要各空等 3.5s，一共浪费 13.5 秒才换到能播的线路。
     * 区分开之后，起播失败的等待从 3.5s 缩到 800ms。</p>
     */
    private boolean liveFirstFrameShown = false;
    /** 起播失败的重试间隔：源侧失败判定很快（实测 80ms），没必要空等 3.5s。 */
    private static final long LIVE_RETRY_BOOT_FAIL_MS = 800L;
    /** 播放中失败的重试间隔：给网络一点恢复时间，沿用原节奏。 */
    private static final long LIVE_RETRY_PLAYING_FAIL_MS = 3500L;
    private LiveChannelItem currentLiveChannelItem = null;
    private String pendingLiveRefreshChannelName = null;
    private int pendingLiveRefreshSourceIndex = -1;
    private boolean refreshingLiveChannelList = false;
    private int liveConfigRequestId = 0;
    private LivePlayerManager livePlayerManager = new LivePlayerManager();
    private com.github.tvbox.osc.player.controller.LiveController liveController;
    private ArrayList<Integer> channelGroupPasswordConfirmed = new ArrayList<>();

//EPG   by 龍
    private static LiveChannelItem  channel_Name = null;
    private static Hashtable<String, ArrayList<Epginfo>> hsEpg = new Hashtable<>();
    private CountDownTimer countDownTimer;
//    private CountDownTimer countDownTimerRightTop;
    private View ll_right_top_loading;
    private View ll_right_top_huikan;
    private View divLoadEpg;
    private View divLoadEpgDivider;
    private View divLoadEpgleft;
    private LinearLayout divEpg;
    RelativeLayout ll_epg;
    TextView tv_channelnum;
    TextView tip_chname;
    TextView tip_epg1;
    TextView  tip_epg2;
    TextView tv_srcinfo;
    TextView tv_curepg_left;
    TextView tv_nextepg_left;
    private TextView tv_right_top_tipnetspeed;
    private TextView tv_right_top_channel_name;
    private TextView tv_right_top_epg_name;
    private TextView tv_right_top_type;
    private ImageView iv_circle_bg;
    private TextView tv_shownum ;
    private TextView txtNoEpg ;
    private ImageView iv_back_bg;

    private ObjectAnimator objectAnimator;
    public String epgStringAddress ="";

    private TvRecyclerView mEpgDateGridView;
    private TvRecyclerView mRightEpgList;
    private LiveEpgDateAdapter liveEpgDateAdapter;
    private LiveEpgAdapter epgListAdapter;

    private List<LiveDayListGroup> liveDayList = new ArrayList<>();


    //laodao 7day replay
    public static SimpleDateFormat formatDate = new SimpleDateFormat("yyyy-MM-dd");
    public static SimpleDateFormat formatDate1 = new SimpleDateFormat("MM-dd");
    public static String day = formatDate.format(new Date());
    public static Date nowday = new Date();

    private boolean isSHIYI = false;
    private boolean isBack = false;
    private static int shiyi_time_c;//时移时间差值
    public static String playUrl;
    //kenson
    private ImageView imgLiveIcon;
    private FrameLayout liveIconNullBg;
    private TextView liveIconNullText;
    SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd");
    private View backcontroller;
    private CountDownTimer countDownTimer3;
    private final int videoHeight = 1080;
    private TextView tv_currentpos;
    private TextView tv_duration;
    private SeekBar sBar;
    private View iv_playpause;
    private View iv_play;
    private  boolean show = false;
    private static final int postTimeout = 6000;

    // 遥控器数字键输入的要切换的频道号码
    private int selectedChannelNumber = 0;
    private TextView tvSelectedChannel;


    @Override
    protected int getLayoutResID() {
        return R.layout.activity_live_play;
    }

    @Override
    protected void init() {
        context = this;
        epgStringAddress = getConfiguredEpgAddress();

        setLoadSir(findViewById(R.id.live_root));
        mVideoView = findViewById(R.id.mVideoView);
        switchChannelSnapshotOverlay = findViewById(R.id.switchChannelSnapshotOverlay);
        switchChannelSnapshotImage = findViewById(R.id.switchChannelSnapshotImage);

        tvLeftChannelListLayout = findViewById(R.id.tvLeftChannnelListLayout);
        mChannelGroupView = findViewById(R.id.mGroupGridView);
        mLiveChannelView = findViewById(R.id.mChannelGridView);
        tvRightSettingLayout = findViewById(R.id.tvRightSettingLayout);
        mSettingGroupView = findViewById(R.id.mSettingGroupView);
        mSettingItemView = findViewById(R.id.mSettingItemView);
        tvChannelInfo = findViewById(R.id.tvChannel);
        tvTime = findViewById(R.id.tvTime);
        tvNetSpeed = findViewById(R.id.tvNetSpeed);
        tvTopStatus = findViewById(R.id.tv_top_status);
        tvResolution = findViewById(R.id.tvResolution);

        //EPG  findViewById  by 龍
        tip_chname = (TextView)  findViewById(R.id.tv_channel_bar_name);//底部名称
        tv_channelnum = (TextView) findViewById(R.id.tv_channel_bottom_number); //底部数字
        tip_epg1 = (TextView) findViewById(R.id.tv_current_program_time);//底部EPG当前节目信息
        tip_epg2 = (TextView) findViewById(R.id.tv_next_program_time);//底部EPG当下个节目信息
        tv_srcinfo = (TextView) findViewById(R.id.tv_source);//线路状态
        tv_curepg_left = (TextView) findViewById(R.id.tv_current_program);//当前节目
        tv_nextepg_left= (TextView) findViewById(R.id.tv_next_program);//下一节目
        ll_epg = (RelativeLayout) findViewById(R.id.ll_epg);
//        tv_right_top_tipnetspeed = (TextView)findViewById(R.id.tv_right_top_tipnetspeed);
        tv_right_top_channel_name = (TextView)findViewById(R.id.tv_right_top_channel_name);
        tv_right_top_epg_name = (TextView)findViewById(R.id.tv_right_top_epg_name);
//        tv_right_top_type = (TextView)findViewById(R.id.tv_right_top_type);
        iv_circle_bg = (ImageView) findViewById(R.id.iv_circle_bg);
        iv_back_bg = (ImageView) findViewById(R.id.iv_back_bg);
        tv_shownum = (TextView) findViewById(R.id.tv_shownum);
        txtNoEpg = (TextView) findViewById(R.id.txtNoEpg);
        ll_right_top_loading = findViewById(R.id.ll_right_top_loading);
        ll_right_top_huikan = findViewById(R.id.ll_right_top_huikan);
        divLoadEpg = (View) findViewById(R.id.divLoadEpg);
        divLoadEpgDivider = findViewById(R.id.divLoadEpgDivider);
        divLoadEpgleft = (View) findViewById(R.id.divLoadEpgleft);
        divEpg = (LinearLayout) findViewById(R.id.divEPG);
        //右上角图片旋转
        objectAnimator = ObjectAnimator.ofFloat(iv_circle_bg,"rotation", 360.0f);
        objectAnimator.setDuration(postTimeout);
        objectAnimator.setRepeatCount(-1);
        objectAnimator.start();

        //laodao 7day replay
        mEpgDateGridView = findViewById(R.id.mEpgDateGridView);
        Hawk.put(HawkConfig.NOW_DATE, formatDate.format(new Date()));
        day=formatDate.format(new Date());
        nowday=new Date();

        mRightEpgList = (TvRecyclerView) findViewById(R.id.lv_epg);
        //EPG频道名称
        imgLiveIcon = findViewById(R.id.img_live_icon);
        liveIconNullBg = findViewById(R.id.live_icon_null_bg);
        liveIconNullText = findViewById(R.id.live_icon_null_text);
        imgLiveIcon.setVisibility(View.INVISIBLE);
        liveIconNullText.setVisibility(View.INVISIBLE);
        liveIconNullBg.setVisibility(View.INVISIBLE);

        sBar = (SeekBar) findViewById(R.id.pb_progressbar);
        tv_currentpos = (TextView) findViewById(R.id.tv_currentpos);
        backcontroller = (View) findViewById(R.id.backcontroller);
        tv_duration = (TextView) findViewById(R.id.tv_duration);
        iv_playpause = findViewById(R.id.iv_playpause);
        iv_play = findViewById(R.id.iv_play);

        tvSelectedChannel = findViewById(R.id.tv_selected_channel);

        if(show){
            backcontroller.setVisibility(View.VISIBLE);
            ll_epg.setVisibility(View.GONE);

        }else{
            backcontroller.setVisibility(View.GONE);
            ll_epg.setVisibility(View.VISIBLE);
        }


        iv_play.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View arg0) {
                mVideoView.start();
                iv_play.setVisibility(View.INVISIBLE);
                countDownTimer.start();
                iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
            }
        });

        iv_playpause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                if(mVideoView.isPlaying()){
                    mVideoView.pause();
                    countDownTimer.cancel();
                    iv_play.setVisibility(View.VISIBLE);
                    iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.icon_play));
                }else{
                    mVideoView.start();
                    iv_play.setVisibility(View.INVISIBLE);
                    countDownTimer.start();
                    iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
                }
            }
        });
        sBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {


            @Override
            public void onStopTrackingTouch(SeekBar arg0) {

            }

            @Override
            public void onStartTrackingTouch(SeekBar arg0) {

            }

            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromuser) {
                if (!fromuser) {
                    return;
                }
                if(countDownTimer!=null){
                    mVideoView.seekTo(progress);
                    countDownTimer.cancel();
                    countDownTimer.start();
                }
            }


        });
        sBar.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View arg0, int keycode, KeyEvent event) {
                if(event.getAction()==KeyEvent.ACTION_DOWN){
                    if(keycode==KeyEvent.KEYCODE_DPAD_CENTER||keycode==KeyEvent.KEYCODE_ENTER){
                        if(mVideoView.isPlaying()){
                            mVideoView.pause();
                            countDownTimer.cancel();
                            iv_play.setVisibility(View.VISIBLE);
                            iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.icon_play));
                        }else{
                            mVideoView.start();
                            iv_play.setVisibility(View.INVISIBLE);
                            countDownTimer.start();
                            iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
                        }
                    }
                }
                return false;
            }
        });
        initEpgDateView();
        initEpgListView();
        initDayList();
        initVideoView();
        initChannelGroupView();
        initLiveChannelView();
        initSettingGroupView();
        initSettingItemView();
        initLiveChannelList();
        initLiveSettingGroupList();
        Hawk.put(HawkConfig.PLAYER_IS_LIVE,true);
    }
    //获取EPG并存储 // 百川epg  DIYP epg   51zmt epg ------- 自建EPG格式输出格式请参考 51zmt
    private List<Epginfo> epgdata = new ArrayList<>();

    private void showEpg(Date date, ArrayList<Epginfo> arrayList) {
        boolean hasEpg = arrayList != null && arrayList.size() > 0;
        updateEpgPanelState(hasEpg);
        if (hasEpg) {
            epgdata = arrayList;
            epgListAdapter.CanBack(currentLiveChannelItem.getinclude_back());
            epgListAdapter.setNewData(epgdata);
            updateCurrentEpgSelectedIndex();
        }
    }

    private int findCurrentEpgIndex(List<Epginfo> epgList) {
        if (epgList == null || epgList.isEmpty()) return -1;
        Date now = new Date();
        for (int i = epgList.size() - 1; i >= 0; i--) {
            Epginfo epgInfo = epgList.get(i);
            if (epgInfo == null || epgInfo.startdateTime == null || epgInfo.enddateTime == null) {
                continue;
            }
            Date endDateTime = epgInfo.enddateTime;
            if (!endDateTime.after(epgInfo.startdateTime)) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(endDateTime);
                calendar.add(Calendar.DAY_OF_MONTH, 1);
                endDateTime = calendar.getTime();
            }
            if (!now.before(epgInfo.startdateTime) && now.before(endDateTime)) {
                return i;
            }
        }
        return -1;
    }

    private int getCurrentEpgIndexOrSelected() {
        int epgIndex = findCurrentEpgIndex(epgListAdapter.getData());
        if (epgIndex >= 0) return epgIndex;
        epgIndex = epgListAdapter.getSelectedIndex();
        if (epgIndex >= 0 && epgIndex < epgListAdapter.getData().size()) return epgIndex;
        return 0;
    }

    private void updateCurrentEpgSelectedIndex() {
        if (epgListAdapter == null || epgListAdapter.getData() == null || epgListAdapter.getData().isEmpty()) return;
        int epgIndex = findCurrentEpgIndex(epgListAdapter.getData());
        if (epgIndex >= 0) {
            epgListAdapter.setSelectedEpgIndex(epgIndex);
        }
    }

    private void syncCurrentEpgSelection(boolean focus) {
        if (mRightEpgList == null || epgListAdapter == null || epgListAdapter.getData() == null || epgListAdapter.getData().isEmpty()) return;
        int epgIndex = getCurrentEpgIndexOrSelected();
        mRightEpgList.setSelectedPosition(epgIndex);
        mRightEpgList.setSelection(epgIndex);
        epgListAdapter.setSelectedEpgIndex(epgIndex);
        if (focus) {
            epgListAdapter.setFocusedEpgIndex(epgIndex);
            focusEpgPosition(epgIndex);
        } else {
            mRightEpgList.post(new Runnable() {
                @Override
                public void run() {
                    mRightEpgList.smoothScrollToPosition(epgIndex);
                }
            });
        }
    }

    private void updateEpgPanelState(boolean hasEpg) {
        if (hasEpg) {
            txtNoEpg.setVisibility(View.GONE);
            mRightEpgList.setVisibility(View.VISIBLE);
            divLoadEpgDivider.setVisibility(View.VISIBLE);
            if (divEpg.getVisibility() != View.VISIBLE) {
                divLoadEpg.setVisibility(View.VISIBLE);
                divLoadEpgleft.setVisibility(View.GONE);
            }
        } else {
            epgdata = new ArrayList<>();
            epgListAdapter.setNewData(epgdata);
            txtNoEpg.setVisibility(View.GONE);
            mRightEpgList.setVisibility(View.GONE);
            divEpg.setVisibility(View.GONE);
            divLoadEpg.setVisibility(View.GONE);
            divLoadEpgDivider.setVisibility(View.GONE);
            divLoadEpgleft.setVisibility(View.GONE);
            mChannelGroupView.setVisibility(View.VISIBLE);
        }
    }

    private String getFirstPartBeforeSpace(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        int spaceIndex = str.indexOf(' ');
        if (spaceIndex == -1) {
            return str;
        } else {
            return str.substring(0, spaceIndex);
        }
    }

    public void getEpg(Date date) {
        String channelName = channel_Name.getChannelName();
        String channelNameReal = normalizeEpgChannelName(getFirstPartBeforeSpace(channelName));
        @SuppressLint("SimpleDateFormat") SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd");
        timeFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        String epgTagName = channelNameReal;
        if (logoUrl==null || logoUrl.isEmpty()){
            String[] epgInfo = EpgUtil.getEpgInfo(channelNameReal);
            if (epgInfo != null && !epgInfo[1].isEmpty()) {
                epgTagName = epgInfo[1];
            }
            updateChannelIcon(channelName, epgInfo == null ? null : epgInfo[0]);
        }else if(logoUrl.equals("false")){
            updateChannelIcon(channelName, null);
        }else {
            String logo= logoUrl.replace("{name}",epgTagName);
            updateChannelIcon(channelName, logo);
        }
        final String finalEpgTagName = epgTagName;
        epgListAdapter.CanBack(currentLiveChannelItem.getinclude_back());
        if (!hasEpgAddress()) {
            updateEpgPanelState(false);
            return;
        }
        ArrayList<String> epgQueryNames = buildEpgQueryNames(channelName, channelNameReal, finalEpgTagName);
        String url;
        url = buildEpgUrl(epgStringAddress, epgQueryNames.get(0), date, timeFormat);

        String savedEpgKey = channelName + "_" + Objects.requireNonNull(liveEpgDateAdapter.getItem(liveEpgDateAdapter.getSelectedIndex())).getDatePresented();
        if (hsEpg.containsKey(savedEpgKey)){
            showEpg(date, hsEpg.get(savedEpgKey));
            showBottomEpg();
            return;
        }
        updateEpgPanelState(false);
        requestEpg(url, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, 0);
    }

    private String buildEpgUrl(String address, String epgTagName, Date date, SimpleDateFormat timeFormat) {
        if (address.contains("{name}") || address.contains("{date}")) {
            return address.replace("{name}", encodeEpgParam(epgTagName)).replace("{date}", timeFormat.format(date));
        } else if(isXmlEpgAddress(address)){
            return address;
        }else {
            return address + (address.contains("?") ? "&" : "?") + "ch=" + encodeEpgParam(epgTagName) + "&date=" + timeFormat.format(date);
        }
    }

    private String encodeEpgParam(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private ArrayList<String> buildEpgQueryNames(String channelName, String channelNameReal, String epgTagName) {
        ArrayList<String> queryNames = new ArrayList<>();
        addEpgQueryName(queryNames, epgTagName);
        addEpgQueryName(queryNames, channelNameReal);
        addEpgQueryName(queryNames, normalizeEpgChannelName(getFirstPartBeforeSpace(channelName)));
        addEpgQueryName(queryNames, getFirstPartBeforeSpace(channelName));
        addEpgQueryName(queryNames, channelName);
        if (queryNames.isEmpty()) {
            queryNames.add("");
        }
        return queryNames;
    }

    private void addEpgQueryName(ArrayList<String> queryNames, String name) {
        if (name == null) return;
        String trimName = name.trim();
        if (trimName.isEmpty() || queryNames.contains(trimName)) return;
        queryNames.add(trimName);
    }

    private String getConfiguredEpgAddress() {
        String userEpgAddress = Hawk.get(HawkConfig.EPG_URL, "");
        if (userEpgAddress != null && userEpgAddress.trim().length() >= 5) {
            return userEpgAddress.trim();
        }
        return DEFAULT_EPG_ADDRESS;
    }

    private boolean hasEpgAddress() {
        return epgStringAddress != null && !epgStringAddress.trim().isEmpty();
    }

    private void requestEpg(String url, Date date, String channelNameReal, String finalEpgTagName, String savedEpgKey,
                            ArrayList<String> epgQueryNames, SimpleDateFormat timeFormat, int queryIndex) {
        okhttp3.OkHttpClient client = OkGoHelper.getDefaultClient();
        if (client == null) client = com.github.catvod.net.OkHttp.client();
        client.newCall(new okhttp3.Request.Builder().url(url).build()).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex);
                    }
                });
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException {
                if (response.code() != 200) {
                    response.close();
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex);
                        }
                    });
                    return;
                }
                final String body;
                try {
                    body = response.body() != null ? response.body().string() : "";
                } finally {
                    response.close();
                }
                // ★ 解析放在 OkHttp 的回调线程（后台）做，主线程只负责渲染。
                //
                // 一份全天节目单常有几百条 <programme>，XML 逐节点解析 + 每条目两次
                // SimpleDateFormat.parse()，原来整段跑在主线程：换台/切日期时正好撞上
                // 视频解码，表现为切换瞬间画面卡顿几百毫秒。这里把解析挪到后台，
                // 主线程只接一个已经排好序的 ArrayList。
                final ArrayList<Epginfo> parsed = parseEpgBody(body, finalEpgTagName, date);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onEpgRequestResponse(parsed, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex);
                    }
                });
            }
        });
    }

    private void onEpgRequestFailure(Date date, String channelNameReal, String finalEpgTagName, String savedEpgKey,
                                     ArrayList<String> epgQueryNames, SimpleDateFormat timeFormat, int queryIndex) {
        if (!isCurrentEpgRequest(savedEpgKey)) return;
        if (requestNextEpgQueryName(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return;
        }
        if (requestDefaultEpgOnFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return;
        }
        updateEpgPanelState(false);
//        showEpg(date, new ArrayList<>());
//        showBottomEpg();
    }

    /**
     * 把 EPG 报文解析成节目列表。运行在后台线程（OkHttp 回调线程），不得触碰 UI。
     *
     * @return 解析结果；报文为空/格式不认识/解析异常时返回空列表
     */
    private ArrayList<Epginfo> parseEpgBody(String body, String epgTagName, Date date) {
        ArrayList<Epginfo> arrayList = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) {
            return arrayList;
        }
        try {
            if (isXmlEpgResponse(body)) {
                arrayList = parseXmlEpg(body, epgTagName, date);
            } else if (body.contains("epg_data") || body.trim().startsWith("{")) {
                arrayList = parseJsonEpg(body, date);
            }
        } catch (JSONException jSONException) {
            jSONException.printStackTrace();
        } catch (Throwable th) {
            // XML/JSON 解析库对畸形报文会抛各种运行时异常（XmlPullParserException、
            // NumberFormatException、StringIndexOutOfBounds…），原来只 catch 了
            // JSONException，任一条穿透到后台线程就是进程崩溃。这里兜住，当成
            // 「这次没解析出节目」走正常的降级流程。
            LOG.e("parseEpgBody fail: " + th);
        }
        return arrayList;
    }

    private void onEpgRequestResponse(ArrayList<Epginfo> arrayList, Date date, String channelNameReal, String finalEpgTagName,
                                      String savedEpgKey, ArrayList<String> epgQueryNames, SimpleDateFormat timeFormat, int queryIndex) {
        if (!isCurrentEpgRequest(savedEpgKey)) return;
        if (arrayList == null) {
            arrayList = new ArrayList<>();
        }
        if (arrayList.isEmpty() && requestNextEpgQueryName(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return;
        }
        // ★ 空结果不落缓存。
        //
        // 原来无条件 put：某频道第一次拉取失败（EPG 站挂了/频道名没匹配上）就把
        // 空列表写进 hsEpg，而这个缓存只在换台清空列表时才清 —— 于是这个频道
        // 在本次会话内再也不会去重新拉，节目单一直是空的。只有拿到真数据才缓存。
        if (!arrayList.isEmpty()) {
            hsEpg.put(savedEpgKey, arrayList);
        }
        if (!isCurrentEpgRequest(savedEpgKey)) return;
        showEpg(date, arrayList);
        showBottomEpg();
    }

    private boolean requestDefaultEpgOnFailure(Date date, String channelNameReal, String finalEpgTagName, String savedEpgKey,
                                               ArrayList<String> epgQueryNames, SimpleDateFormat timeFormat, int queryIndex) {
        if (DEFAULT_EPG_ADDRESS.equals(epgStringAddress) || epgQueryNames == null || queryIndex >= epgQueryNames.size()) {
            return false;
        }
        String fallbackUrl = buildEpgUrl(DEFAULT_EPG_ADDRESS, epgQueryNames.get(0), date, timeFormat);
        requestEpg(fallbackUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, epgQueryNames.size());
        return true;
    }

    private boolean requestNextEpgQueryName(Date date, String channelNameReal, String finalEpgTagName, String savedEpgKey,
                                            ArrayList<String> epgQueryNames, SimpleDateFormat timeFormat, int queryIndex) {
        if (!isTemplateEpgAddress(epgStringAddress) || epgQueryNames == null || queryIndex + 1 >= epgQueryNames.size()) {
            return false;
        }
        int nextIndex = queryIndex + 1;
        String nextUrl = buildEpgUrl(epgStringAddress, epgQueryNames.get(nextIndex), date, timeFormat);
        LOG.i("echo-epg retry query name:" + epgQueryNames.get(nextIndex));
        requestEpg(nextUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, nextIndex);
        return true;
    }

    private boolean isTemplateEpgAddress(String address) {
        return address != null && (address.contains("{name}") || address.contains("{date}"));
    }

    private boolean isCurrentEpgRequest(String savedEpgKey) {
        if (channel_Name == null || liveEpgDateAdapter == null || liveEpgDateAdapter.getSelectedIndex() < 0) return false;
        String currentEpgKey = channel_Name.getChannelName() + "_" + Objects.requireNonNull(liveEpgDateAdapter.getItem(liveEpgDateAdapter.getSelectedIndex())).getDatePresented();
        return savedEpgKey.equals(currentEpgKey);
    }

    //显示底部EPG
    private boolean isXmlEpgAddress(String address) {
        if (address == null) {
            return false;
        }
        String lowerAddress = address.toLowerCase(Locale.ROOT);
        int queryIndex = lowerAddress.indexOf("?");
        if (queryIndex >= 0) {
            lowerAddress = lowerAddress.substring(0, queryIndex);
        }
        return lowerAddress.endsWith(".xml");
    }

    private boolean isXmlEpgResponse(String response) {
        if (response == null) {
            return false;
        }
        String trimResponse = response.trim();
        return trimResponse.startsWith("<?xml") || trimResponse.startsWith("<tv") || trimResponse.contains("<programme");
    }

    private ArrayList<Epginfo> parseJsonEpg(String response, Date date) throws JSONException {
        ArrayList<Epginfo> epgList = new ArrayList<>();
        JSONObject jsonObject = new JSONObject(response);
        String channelName = jsonObject.optString("channel_name", jsonObject.optString("channel", ""));
        if (isUnavailableEpgText(channelName)) {
            return epgList;
        }
        JSONArray epgArray = findJsonEpgArray(jsonObject);
        if (epgArray == null) {
            return epgList;
        }
        for (int i = 0; i < epgArray.length(); i++) {
            JSONObject item = epgArray.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String title = cleanEpgTitle(item.optString("title", item.optString("name", "")));
            if (TextUtils.isEmpty(title) || isUnavailableEpgText(title)) {
                continue;
            }
            String startText = item.optString("start", item.optString("start_time", item.optString("starttime", "")));
            String endText = item.optString("end", item.optString("end_time", item.optString("endtime", "")));
            Date startDate = parseJsonEpgDate(date, startText);
            Date endDate = parseJsonEpgDate(date, endText);
            if (startDate == null || endDate == null) {
                continue;
            }
            if (!endDate.after(startDate)) {
                endDate = new Date(endDate.getTime() + TimeUnit.DAYS.toMillis(1));
            }
            epgList.add(createXmlEpgInfo(date, title, startDate, endDate, epgList.size()));
        }
        return epgList;
    }

    private JSONArray findJsonEpgArray(JSONObject jsonObject) {
        JSONArray epgArray = jsonObject.optJSONArray("epg_data");
        if (epgArray != null) return epgArray;
        epgArray = jsonObject.optJSONArray("data");
        if (epgArray != null) return epgArray;
        epgArray = jsonObject.optJSONArray("list");
        if (epgArray != null) return epgArray;
        JSONObject dataObject = jsonObject.optJSONObject("data");
        if (dataObject != null) {
            epgArray = dataObject.optJSONArray("epg_data");
            if (epgArray != null) return epgArray;
            epgArray = dataObject.optJSONArray("list");
        }
        return epgArray;
    }

    private Date parseJsonEpgDate(Date date, String timeText) {
        if (timeText == null || timeText.trim().isEmpty()) {
            return null;
        }
        String trimText = timeText.trim();
        String[] fullPatterns = new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"};
        for (String pattern : fullPatterns) {
            try {
                SimpleDateFormat dateFormat = new SimpleDateFormat(pattern, Locale.getDefault());
                dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
                return dateFormat.parse(trimText);
            } catch (ParseException ignored) {
            }
        }
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        dayFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        String dayText = dayFormat.format(date);
        String[] timePatterns = new String[]{"HH:mm:ss", "HH:mm"};
        for (String pattern : timePatterns) {
            try {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd " + pattern, Locale.getDefault());
                dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
                return dateFormat.parse(dayText + " " + trimText);
            } catch (ParseException ignored) {
            }
        }
        return null;
    }

    private String cleanEpgTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.replace(" --免费使用", "")
                .replace("--免费使用", "")
                .trim();
    }

    private boolean isUnavailableEpgText(String text) {
        return text != null && (text.contains("未提供") || text.contains("暂无"));
    }

    private String normalizeEpgChannelName(String channelName) {
        if (channelName == null) {
            return "";
        }
        String trimName = channelName.trim();
        String compactName = trimName.replace("-", "").replace(" ", "");
        Matcher cctvMatcher = Pattern.compile("(?i)^(CCTV\\d+(?:\\+|K)?)(?:[\\u4e00-\\u9fa5].*|$)").matcher(compactName);
        if (cctvMatcher.matches()) {
            return cctvMatcher.group(1).toUpperCase(Locale.ROOT);
        }
        if (compactName.toUpperCase(Locale.ROOT).startsWith("CCTV")) {
            return compactName.toUpperCase(Locale.ROOT);
        }
        return trimName;
    }

    private ArrayList<Epginfo> parseXmlEpg(String xml, String channelName, Date date) {
        ArrayList<Epginfo> epgList = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setIgnoringComments(true);
            factory.setCoalescing(true);
            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignored) {
            }
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            document.getDocumentElement().normalize();

            String targetName = normalizeEpgChannelName(channelName);
            ArrayList<String> channelIds = new ArrayList<>();
            NodeList channelNodes = document.getElementsByTagName("channel");
            for (int i = 0; i < channelNodes.getLength(); i++) {
                Node channelNode = channelNodes.item(i);
                if (channelNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element channelElement = (Element) channelNode;
                String channelId = channelElement.getAttribute("id");
                if (targetName.equals(normalizeEpgChannelName(channelId))) {
                    channelIds.add(channelId);
                    continue;
                }
                NodeList displayNameNodes = channelElement.getElementsByTagName("display-name");
                for (int j = 0; j < displayNameNodes.getLength(); j++) {
                    String displayName = displayNameNodes.item(j).getTextContent();
                    if (targetName.equals(normalizeEpgChannelName(displayName))) {
                        channelIds.add(channelId);
                        break;
                    }
                }
            }

            Date dayStart = getDayStart(date);
            Date dayEnd = new Date(dayStart.getTime() + TimeUnit.DAYS.toMillis(1));
            NodeList programmeNodes = document.getElementsByTagName("programme");
            for (int i = 0; i < programmeNodes.getLength(); i++) {
                Node programmeNode = programmeNodes.item(i);
                if (programmeNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element programmeElement = (Element) programmeNode;
                String programmeChannel = programmeElement.getAttribute("channel");
                if (!channelIds.contains(programmeChannel) && !targetName.equals(normalizeEpgChannelName(programmeChannel))) {
                    continue;
                }

                Date startDate = parseXmlTvDate(programmeElement.getAttribute("start"));
                Date endDate = parseXmlTvDate(programmeElement.getAttribute("stop"));
                if (startDate == null || endDate == null || !endDate.after(startDate)) {
                    continue;
                }
                if (!startDate.before(dayEnd) || !endDate.after(dayStart)) {
                    continue;
                }

                String title = "";
                NodeList titleNodes = programmeElement.getElementsByTagName("title");
                if (titleNodes.getLength() > 0) {
                    title = titleNodes.item(0).getTextContent();
                }
                epgList.add(createXmlEpgInfo(date, title, startDate, endDate, epgList.size()));
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        return epgList;
    }

    private Date getDayStart(Date date) throws ParseException {
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        dayFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        return dayFormat.parse(dayFormat.format(date));
    }

    private Date parseXmlTvDate(String dateText) {
        if (dateText == null || dateText.trim().isEmpty()) {
            return null;
        }
        String trimDate = dateText.trim();
        try {
            return new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault()).parse(trimDate);
        } catch (ParseException ignored) {
        }
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault());
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
            return dateFormat.parse(trimDate);
        } catch (ParseException ignored) {
        }
        return null;
    }

    private Epginfo createXmlEpgInfo(Date epgDate, String title, Date startDate, Date endDate, int index) {
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        Epginfo epgInfo = new Epginfo(epgDate, title, epgDate, timeFormat.format(startDate), timeFormat.format(endDate), index);
        epgInfo.startdateTime = startDate;
        epgInfo.enddateTime = endDate;
        epgInfo.start = timeFormat.format(startDate);
        epgInfo.end = timeFormat.format(endDate);
        epgInfo.originStart = epgInfo.start;
        epgInfo.originEnd = epgInfo.end;
        epgInfo.datestart = Integer.parseInt(epgInfo.start.replace(":", ""));
        epgInfo.dateend = Integer.parseInt(epgInfo.end.replace(":", ""));
        return epgInfo;
    }

    @SuppressLint("SetTextI18n")
    private void showBottomEpg() {
        if (isSHIYI){
            return;
        }
        if (channel_Name.getChannelName() != null) {
            tip_chname.setText(channel_Name.getChannelName());
            tv_channelnum.setText("" + channel_Name.getChannelNum());
            TextView tv_current_program_name = findViewById(R.id.tv_current_program_name);
            TextView tv_next_program_name = findViewById(R.id.tv_next_program_name);
            setDefaultBottomEpg(tv_current_program_name, tv_next_program_name);
            String savedEpgKey = channel_Name.getChannelName() + "_" + Objects.requireNonNull(liveEpgDateAdapter.getItem(liveEpgDateAdapter.getSelectedIndex())).getDatePresented();

            if (hsEpg.containsKey(savedEpgKey)) {
                ArrayList<Epginfo> arrayList = hsEpg.get(savedEpgKey);
                if (arrayList != null && arrayList.size() > 0) {
                    Date date = new Date();
                    int size = arrayList.size() - 1;
                    boolean hasInfo = false;
                    while (size >= 0) {
                        if (date.after((arrayList.get(size)).startdateTime) & date.before((arrayList.get(size)).enddateTime)) {
                            tip_epg1.setText((arrayList.get(size)).start + "-" + (arrayList.get(size)).end);
                            tv_current_program_name.setText((arrayList.get(size)).title);
                            if (size != arrayList.size() - 1) {
                                tip_epg2.setText((arrayList.get(size + 1)).start + "-" + (arrayList.get(size + 1)).end);
                                tv_next_program_name.setText((arrayList.get(size + 1)).title);
                            } else {
                                tip_epg2.setText((arrayList.get(size)).end+"-23:59");
                                tv_next_program_name.setText("精彩节目-暂无节目预告信息");
                            }
                            hasInfo=true;
                            break;
                        } else {
                            size--;
                        }
                    }
                }
                epgListAdapter.CanBack(currentLiveChannelItem.getinclude_back());
                epgListAdapter.setNewData(arrayList);
                updateEpgPanelState(arrayList != null && arrayList.size() > 0);
            } else {
                updateEpgPanelState(false);
            }

            if (countDownTimer != null) {
                countDownTimer.cancel();
            }
            if(!tip_epg1.getText().equals("暂无信息")){
                ll_right_top_loading.setVisibility(View.VISIBLE);
                updateTopStatusVisibility();
                ll_epg.setVisibility(View.VISIBLE);
                countDownTimer = new CountDownTimer(postTimeout, 1000) {//底部epg隐藏时间设定
                    public void onTick(long j) {
                    }
                    public void onFinish() {
                        ll_right_top_loading.setVisibility(View.GONE);
                        ll_right_top_huikan.setVisibility(View.GONE);
                        ll_epg.setVisibility(View.GONE);
                        updateTopStatusVisibility();
                    }
                };
                countDownTimer.start();
            }else {
                ll_right_top_loading.setVisibility(View.GONE);
                ll_right_top_huikan.setVisibility(View.GONE);
                ll_epg.setVisibility(View.GONE);
                updateTopStatusVisibility();
            }
            if (channel_Name == null || channel_Name.getSourceNum() <= 0) {
                ((TextView) findViewById(R.id.tv_source)).setText("1/1");
            } else {
                ((TextView) findViewById(R.id.tv_source)).setText("线路" + (channel_Name.getSourceIndex() + 1) + "/" + channel_Name.getSourceNum());
            }
            tv_right_top_channel_name.setText(channel_Name.getChannelName());
            tv_right_top_epg_name.setText(channel_Name.getChannelName());
        }
    }

    private void setDefaultBottomEpg(TextView currentProgramName, TextView nextProgramName) {
        TimeZone timeZone = TimeZone.getTimeZone("GMT+8:00");
        Calendar currentStart = Calendar.getInstance(timeZone);
        currentStart.set(Calendar.MINUTE, 0);
        currentStart.set(Calendar.SECOND, 0);
        currentStart.set(Calendar.MILLISECOND, 0);
        Calendar currentEnd = (Calendar) currentStart.clone();
        currentEnd.add(Calendar.MINUTE, 59);
        Calendar nextStart = (Calendar) currentEnd.clone();
        nextStart.add(Calendar.MINUTE, 1);
        Calendar nextEnd = (Calendar) nextStart.clone();
        nextEnd.add(Calendar.MINUTE, 59);

        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        timeFormat.setTimeZone(timeZone);
        tip_epg1.setText(timeFormat.format(currentStart.getTime()) + "-" + timeFormat.format(currentEnd.getTime()));
        currentProgramName.setText("精彩节目");
        tip_epg2.setText(timeFormat.format(nextStart.getTime()) + "-" + timeFormat.format(nextEnd.getTime()));
        nextProgramName.setText("暂无节目预告信息");
    }

    private void updateCurrentChannelIcon() {
        if (channel_Name == null || channel_Name.getChannelName() == null) {
            return;
        }
        String channelName = channel_Name.getChannelName();
        String channelNameReal = normalizeEpgChannelName(getFirstPartBeforeSpace(channelName));
        String epgTagName = channelNameReal;
        String iconUrl = null;
        if (!channel_Name.getChannelLogo().isEmpty()) {
            iconUrl = channel_Name.getChannelLogo();
        } else if (logoUrl == null || logoUrl.isEmpty()) {
            String[] epgInfo = EpgUtil.getEpgInfo(channelNameReal);
            if (epgInfo != null) {
                iconUrl = epgInfo[0];
                if (!epgInfo[1].isEmpty()) {
                    epgTagName = epgInfo[1];
                }
            }
        } else if (!logoUrl.equals("false")) {
            iconUrl = logoUrl.replace("{name}", epgTagName);
        }
        updateChannelIcon(channelName, iconUrl);
    }

    @SuppressLint("SetTextI18n")
    private void updateChannelIcon(String channelName, String logoUrl) {
        if (channel_Name == null || channel_Name.getChannelName() == null || !channel_Name.getChannelName().equals(channelName)) {
            return;
        }
        if (StringUtils.isEmpty(logoUrl)) {
            imgLiveIcon.setImageDrawable(null);
            liveIconNullBg.setVisibility(View.VISIBLE);
            liveIconNullText.setVisibility(View.VISIBLE);
            imgLiveIcon.setVisibility(View.INVISIBLE);
            liveIconNullText.setText("" + channel_Name.getChannelNum());
        } else {
            imgLiveIcon.setVisibility(View.VISIBLE);
            com.github.tvbox.osc.util.ImgUtil.load(DefaultConfig.checkReplaceProxy(logoUrl), imgLiveIcon, 1, 0, 0, channel_Name.getChannelName(), ImageView.ScaleType.CENTER_INSIDE);
            liveIconNullBg.setVisibility(View.INVISIBLE);
            liveIconNullText.setVisibility(View.INVISIBLE);
        }
    }


    //频道列表
    @SuppressLint("NotifyDataSetChanged")
    public  void divLoadEpgRight(View view) {
        if (epgListAdapter.getData() == null || epgListAdapter.getData().isEmpty()) {
            updateEpgPanelState(false);
            return;
        }
        mHandler.removeCallbacks(mHideChannelListRun);
        mHandler.postDelayed(mHideChannelListRun, postTimeout);
        mChannelGroupView.setVisibility(View.GONE);
        divEpg.setVisibility(View.VISIBLE);
        mRightEpgList.setVisibility(View.VISIBLE);
        divLoadEpgleft.setVisibility(View.VISIBLE);
        divLoadEpg.setVisibility(View.GONE);
        liveChannelItemAdapter.setFocusedChannelIndex(-1);
        epgListAdapter.notifyDataSetChanged();
        mRightEpgList.post(new Runnable() {
            @Override
            public void run() {
                focusCurrentEpgInMenu();
            }
        });
    }
    //频道列表
    public  void divLoadEpgLeft(View view) {
        mHandler.removeCallbacks(mHideChannelListRun);
        mHandler.postDelayed(mHideChannelListRun, postTimeout);
        mChannelGroupView.setVisibility(View.VISIBLE);
        divEpg.setVisibility(View.GONE);
        divLoadEpgleft.setVisibility(View.GONE);
        divLoadEpg.setVisibility(View.VISIBLE);
        focusCurrentChannelInMenu();
    }


    @Override
    public void onBackPressed() {
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
        } else if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
        } else if( backcontroller.getVisibility() == View.VISIBLE){ //
            backcontroller.setVisibility(View.GONE);
        }else if(isBack){
            isBack= false;
            playPreSource();
        }else {
            mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
            mHandler.removeCallbacks(mUpdateNetSpeedRun);
            exitingLivePlay = true;
            super.onBackPressed();
        }
    }

    private final Runnable mPlaySelectedChannel = new Runnable() {
        @Override
        public void run() {
            int channelNumber = selectedChannelNumber;
            selectedChannelNumber = 0;
            int currentTotal = 0;
            int groupIndex = 0;
            int channelIndex = -1;
            for (LiveChannelGroup group : liveChannelGroupList) {
                int groupChannelCount = group.getLiveChannels().size();
                if (currentTotal + groupChannelCount >= channelNumber) {
                    channelIndex = channelNumber - currentTotal - 1; // 转换为0-based索引
                    break;
                }
                currentTotal += groupChannelCount;
                groupIndex++;
            }
            tvSelectedChannel.setVisibility(View.INVISIBLE);
            tvSelectedChannel.setText("");
            if(channelIndex>=0){
                loadChannelGroupDataAndPlay(groupIndex,channelIndex);
            }else {
                playChannel(currentChannelGroupIndex, currentLiveChannelIndex, false);
            }
        }
    };

    @SuppressLint("SetTextI18n")
    private void numericKeyDown(int digit) {
        selectedChannelNumber = selectedChannelNumber * 10 + digit;
        tvSelectedChannel.setText(Integer.toString(selectedChannelNumber));
        ll_right_top_loading.setVisibility(View.GONE);
        ll_right_top_huikan.setVisibility(View.GONE);
        tvSelectedChannel.setVisibility(View.VISIBLE);

        mHandler.removeCallbacks(mPlaySelectedChannel);
        mHandler.postDelayed(mPlaySelectedChannel, 2500);
    }

    private final Handler mmHandler = new Handler();
    private Runnable mLongPressRunnable;
    private static final long LONG_PRESS_DELAY = 800;

    /**
     * 换台/换源按键的节流：首次按下立即生效，连发（长按）限制最小间隔。
     *
     * <p>遥控器按住上下键时 Android 会以几十毫秒一次的频率重复投递 ACTION_DOWN，
     * 而每一次都会走 {@code release() → setUrl() → start()} 完整起播流程（DNS、
     * 建连、解复用）。原来不做任何节流，按住 2 秒能触发几十次起播：画面疯狂闪烁、
     * 前一次的连接还没断开后一次又来了，退出直播页时还可能有一批在途回调打到已
     * 释放的播放器上。这里首次按下零延迟（保证单击手感），重复事件节流到 400ms。</p>
     */
    private static final long CHANNEL_KEY_THROTTLE_MS = 400L;
    private long lastChannelKeyMs = 0L;

    private boolean allowChannelSwitchKey(KeyEvent event) {
        long now = android.os.SystemClock.uptimeMillis();
        if (event.getRepeatCount() == 0) {
            lastChannelKeyMs = now;
            return true;
        }
        if (now - lastChannelKeyMs < CHANNEL_KEY_THROTTLE_MS) {
            return false;
        }
        lastChannelKeyMs = now;
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isFocusInView(mChannelGroupView)) {
                    focusChannelFromSelectedGroup();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isFocusInView(mLiveChannelView)) {
                    divLoadEpgRight(null);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isFocusInView(mRightEpgList)) {
                    divLoadEpgLeft(null);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !isFocusInView(mLiveChannelView) && !isFocusInView(mRightEpgList)) {
                    focusCurrentGroupInMenu();
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_HELP) {
                showSettingGroup();
            } else if (!isListOrSettingLayoutVisible()) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (!allowChannelSwitchKey(event)) break;
                        if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                            playNext();
                        else
                            playPrevious();
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (!allowChannelSwitchKey(event)) break;
                        if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                            playPrevious();
                        else
                            playNext();
                        break;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (!allowChannelSwitchKey(event)) break;
                        if(isBack){
                            showProgressBars(true);
                        }else{
                            playPreSource();
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (!allowChannelSwitchKey(event)) break;
                        if(isBack){
                            showProgressBars(true);
                        }else{
                            playNextSource();
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        break;
                    default:
                        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                            keyCode -= KeyEvent.KEYCODE_0;
                        } else if ( keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
                            keyCode -= KeyEvent.KEYCODE_NUMPAD_0;
                        } else {
                            break;
                        }
                        numericKeyDown(keyCode);
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (!isListOrSettingLayoutVisible()) {
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) && event.getRepeatCount() == 0) {
                    showChannelList();
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) && event.getRepeatCount() == 0) {
            mLongPressRunnable = new Runnable() {
                @Override
                public void run() {
                    showSettingGroup(); //实现长按调出菜单
                }
            };
            mmHandler.postDelayed(mLongPressRunnable, LONG_PRESS_DELAY);
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (mLongPressRunnable != null) {
                mmHandler.removeCallbacks(mLongPressRunnable);
                mLongPressRunnable = null;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        exitingLivePlay = false;
        if (mVideoView != null) {
            mVideoView.resume();
        }
    }


    @Override
    protected void onPause() {
        super.onPause();
        if (mVideoView != null && !exitingLivePlay) {
            mVideoView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Hawk.put(HawkConfig.PLAYER_IS_LIVE, false);
        hideSwitchChannelSnapshot();
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
        if (liveController != null) {
            liveController.clearListener();
            liveController = null;
        }

        // ★ 原来只摘了 3 个 Runnable。这个页面一共往 mHandler 里投了 12 个周期/延时
        // 任务（EPG、分辨率、换源倒计时、隐藏频道列表、隐藏设置面板、时钟、网速…），
        // 剩下的全都会在 Activity 销毁后继续跑并持有它 —— 退出直播页后定时器仍在
        // 刷新 tvTime/tvNetSpeed、换源倒计时仍会触发 playNextSource() 去起播一个
        // 已经释放的 VideoView。这里一次性清干净。
        mHandler.removeCallbacksAndMessages(null);
        // 长按调出菜单用的独立 Handler，同样要清
        mmHandler.removeCallbacksAndMessages(null);

        // 两个倒计时 / 一个无限循环动画同理，不 cancel 会一直持有 Activity
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        if (countDownTimer3 != null) {
            countDownTimer3.cancel();
            countDownTimer3 = null;
        }
        if (objectAnimator != null) {
            objectAnimator.cancel();
            objectAnimator = null;
        }

        // 静态字段跨实例存活：不置空会让整个 Activity（以及它的 View 树、播放器）
        // 一直被静态引用吊着，退出直播页后内存不释放；下次进来还能读到上一次的
        // channel_Name，导致 EPG 回调串到错误的频道上。
        context = null;
        channel_Name = null;
        hsEpg.clear();
        playUrl = null;
    }

    private void showChannelList() {
        if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
            return;
        }
        if(liveChannelGroupList.isEmpty()) return;
        if (tvLeftChannelListLayout.getVisibility() == View.INVISIBLE) {
            if(currentLiveLookBackIndex>-1){
                mRightEpgList.setSelectedPosition(currentLiveLookBackIndex);
                mRightEpgList.post(new Runnable() {
                    @Override
                    public void run() {
                        mRightEpgList.smoothScrollToPosition(currentLiveLookBackIndex);
                    }
                });
            }
            refreshChannelList(currentChannelGroupIndex);

            mHandler.postDelayed(mFocusCurrentChannelAndShowChannelList, 50);
        }
        else {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
        }
    }

    private int mLastChannelGroupIndex = -1;
    private List<LiveChannelItem> mLastChannelList = new ArrayList<>();

    private void refreshChannelList(int currentChannelGroupIndex) {
        List<LiveChannelItem> newChannels = getLiveChannels(currentChannelGroupIndex);
        // 2. 判断数据是否变化
        if (currentChannelGroupIndex == mLastChannelGroupIndex
                && isSameData(newChannels, mLastChannelList)) {
            return; // 数据未变化，跳过刷新 解决部分直播频道过多时卡顿
        }
        if (currentLiveChannelIndex > -1){
            mLiveChannelView.scrollToPosition(currentLiveChannelIndex);
            mLiveChannelView.setSelection(currentLiveChannelIndex);
        }
        mChannelGroupView.scrollToPosition(currentChannelGroupIndex);
        mChannelGroupView.setSelection(currentChannelGroupIndex);
        mLastChannelGroupIndex = currentChannelGroupIndex;
        mLastChannelList = new ArrayList<>(newChannels);
        liveChannelItemAdapter.setNewData(newChannels);
    }

    // 对比两个列表内容是否相同
    private boolean isSameData(List<LiveChannelItem> list1, List<LiveChannelItem> list2) {
//        return list1.size() == list2.size();
        if (list1 == list2) return true;
        if (list1 == null || list2 == null || list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            if (!list1.get(i).equals(list2.get(i))) {
                return false;
            }
        }
        return true;
    }

    private Runnable mFocusCurrentChannelAndShowChannelList = new Runnable() {
        @Override
        public void run() {
            if (mChannelGroupView.isScrolling() || mLiveChannelView.isScrolling() || mChannelGroupView.isComputingLayout() || mLiveChannelView.isComputingLayout()) {
                mHandler.postDelayed(this, 100);
            } else {
                tvLeftChannelListLayout.setVisibility(View.VISIBLE);
                focusCurrentChannelInMenu();
                ViewObj viewObj = new ViewObj(tvLeftChannelListLayout, (ViewGroup.MarginLayoutParams) tvLeftChannelListLayout.getLayoutParams());
                ObjectAnimator animator = ObjectAnimator.ofObject(viewObj, "marginLeft", new IntEvaluator(), -tvLeftChannelListLayout.getLayoutParams().width, 0);
                animator.setDuration(200);
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        focusCurrentChannelInMenu();
                        mHandler.removeCallbacks(mHideChannelListRun);
                        mHandler.postDelayed(mHideChannelListRun, postTimeout);
                    }
                });
                animator.start();
            }
        }
    };

    private boolean isFocusInView(View view) {
        View focused = getCurrentFocus();
        return focused != null && view != null && (focused == view || isChildOf(view, focused));
    }

    private boolean isChildOf(View parent, View child) {
        View current = child;
        while (current != null) {
            if (current == parent) return true;
            if (!(current.getParent() instanceof View)) return false;
            current = (View) current.getParent();
        }
        return false;
    }

    private void focusRecyclerPosition(TvRecyclerView recyclerView, int position) {
        if (recyclerView == null || position < 0) return;
        recyclerView.scrollToPosition(position);
        recyclerView.setSelection(position);
        requestRecyclerItemFocus(recyclerView, position, 0);
    }

    private void focusCurrentGroupInMenu() {
        if (currentChannelGroupIndex < 0) return;
        mChannelGroupView.setVisibility(View.VISIBLE);
        divEpg.setVisibility(View.GONE);
        divLoadEpgleft.setVisibility(View.GONE);
        divLoadEpg.setVisibility(epgListAdapter != null && epgListAdapter.getData() != null && !epgListAdapter.getData().isEmpty() ? View.VISIBLE : View.GONE);
        liveChannelGroupAdapter.setSelectedGroupIndex(currentChannelGroupIndex);
        liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
        liveChannelGroupAdapter.setFocusedGroupIndex(currentChannelGroupIndex);
        liveChannelItemAdapter.setFocusedChannelIndex(-1);
        epgListAdapter.setFocusedEpgIndex(-1);
        mLiveChannelView.clearFocus();
        mRightEpgList.clearFocus();
        focusRecyclerPosition(mChannelGroupView, currentChannelGroupIndex);
    }

    private void focusEpgPosition(int position) {
        if (mRightEpgList == null || position < 0) return;
        RecyclerView.LayoutManager layoutManager = mRightEpgList.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            int offset = Math.max(0, (mRightEpgList.getHeight() - getResources().getDimensionPixelSize(R.dimen.ts_100)) / 2);
            ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(position, offset);
        } else {
            mRightEpgList.scrollToPosition(position);
        }
        mRightEpgList.setSelection(position);
        requestRecyclerItemFocus(mRightEpgList, position, 0);
    }

    private void requestRecyclerItemFocus(TvRecyclerView recyclerView, int position, int retryCount) {
        recyclerView.post(new Runnable() {
            @Override
            public void run() {
                RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
                if (holder != null) {
                    holder.itemView.requestFocus();
                } else if (retryCount < 3) {
                    requestRecyclerItemFocus(recyclerView, position, retryCount + 1);
                }
            }
        });
    }

    private void focusCurrentChannelInMenu() {
        if (currentChannelGroupIndex < 0 || currentLiveChannelIndex < 0) return;
        if (liveChannelGroupAdapter.getSelectedGroupIndex() != currentChannelGroupIndex) {
            liveChannelGroupAdapter.setSelectedGroupIndex(currentChannelGroupIndex);
            liveChannelItemAdapter.setNewData(getLiveChannels(currentChannelGroupIndex));
            mLastChannelGroupIndex = currentChannelGroupIndex;
            mLastChannelList = new ArrayList<>(getLiveChannels(currentChannelGroupIndex));
        }
        liveChannelGroupAdapter.setFocusedGroupIndex(-1);
        liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
        liveChannelItemAdapter.setFocusedChannelIndex(currentLiveChannelIndex);
        focusRecyclerPosition(mLiveChannelView, currentLiveChannelIndex);
    }

    private void focusChannelFromSelectedGroup() {
        int groupIndex = liveChannelGroupAdapter.getSelectedGroupIndex();
        if (groupIndex < 0) groupIndex = currentChannelGroupIndex;
        if (groupIndex < 0 || groupIndex >= liveChannelGroupList.size()) return;
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, -1);
            return;
        }
        if (mChannelGroupView.getVisibility() != View.VISIBLE) return;
        int channelIndex = groupIndex == currentChannelGroupIndex && currentLiveChannelIndex >= 0 ? currentLiveChannelIndex : 0;
        liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
        liveChannelGroupAdapter.setSelectedGroupIndex(groupIndex);
        liveChannelGroupAdapter.setFocusedGroupIndex(-1);
        liveChannelItemAdapter.setSelectedChannelIndex(groupIndex == currentChannelGroupIndex ? currentLiveChannelIndex : -1);
        liveChannelItemAdapter.setFocusedChannelIndex(channelIndex);
        focusRecyclerPosition(mLiveChannelView, channelIndex);
    }

    private void focusCurrentEpgInMenu() {
        if (mRightEpgList == null || epgListAdapter == null || epgListAdapter.getData() == null || epgListAdapter.getData().isEmpty()) return;
        syncCurrentEpgSelection(true);
    }

    private Runnable mHideChannelListRun = new Runnable() {
        @Override
        public void run() {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) tvLeftChannelListLayout.getLayoutParams();
            if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                ViewObj viewObj = new ViewObj(tvLeftChannelListLayout, params);
                ObjectAnimator animator = ObjectAnimator.ofObject(viewObj, "marginLeft", new IntEvaluator(), 0, -tvLeftChannelListLayout.getLayoutParams().width);
                animator.setDuration(200);
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
                    }
                });
                animator.start();
            }
        }
    };

    private void showChannelInfo() {
        tvChannelInfo.setText(String.format(Locale.getDefault(), "%d %s %s(%d/%d)", currentLiveChannelItem.getChannelNum(),
                currentLiveChannelItem.getChannelName(), currentLiveChannelItem.getSourceName(),
                currentLiveChannelItem.getSourceIndex() + 1, currentLiveChannelItem.getSourceNum()));

        FrameLayout.LayoutParams lParams = new FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            lParams.gravity = Gravity.LEFT;
            lParams.leftMargin = 60;
            lParams.topMargin = 30;
        } else {
            lParams.gravity = Gravity.RIGHT;
            lParams.rightMargin = 60;
            lParams.topMargin = 30;
        }
        tvChannelInfo.setLayoutParams(lParams);

        tvChannelInfo.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideChannelInfoRun);
        mHandler.postDelayed(mHideChannelInfoRun, 3000);
    }

    private Runnable mHideChannelInfoRun = new Runnable() {
        @Override
        public void run() {
            tvChannelInfo.setVisibility(View.INVISIBLE);
        }
    };

    private JsonObject catchup=null;
    private String logoUrl=null;
    private void initLiveObj(){
        catchup = null;
        logoUrl = null;
        int position=ApiConfig.getLiveGroupIndex();
        JsonArray live_groups=Hawk.get(HawkConfig.LIVE_GROUP_LIST,new JsonArray());
        if (live_groups == null || live_groups.size() == 0 || position < 0 || position >= live_groups.size()) {
            return;
        }
        JsonObject livesOBJ = live_groups.get(position).getAsJsonObject();
        String type = livesOBJ.has("type")?livesOBJ.get("type").getAsString():"0";

        if(livesOBJ.has("catchup") && livesOBJ.get("catchup").isJsonObject()){
            catchup = livesOBJ.getAsJsonObject("catchup");
        }
        if(livesOBJ.has("logo")){
            logoUrl = livesOBJ.get("logo").getAsString();
        }
        if(type.equals("3")){
            String py_jar="";
            if(livesOBJ.has("jar")){
                py_jar=livesOBJ.has("jar")?livesOBJ.get("jar").getAsString():"";

            }else if(livesOBJ.has("api")){
                py_jar=livesOBJ.has("api")?livesOBJ.get("api").getAsString():"";
//                String ext = livesOBJ.has("ext")?livesOBJ.get("ext").getAsJsonObject().toString():"";
                String ext="";
                if(livesOBJ.has("ext") && (livesOBJ.get("ext").isJsonObject() || livesOBJ.get("ext").isJsonArray())){
                    ext=livesOBJ.get("ext").toString();
                }else {
                    ext= DefaultConfig.safeJsonString(livesOBJ, "ext", "");
                }
                if(!ext.isEmpty())py_jar=py_jar+"?extend="+ext;
            }
            ApiConfig.get().setLiveJar(py_jar);
        }
    }

    private HashMap<String,String> liveWebHeader()
    {
        return Hawk.get(HawkConfig.LIVE_WEB_HEADER);
    }

    private HashMap<String, String> liveChannelHeader() {
        if (currentLiveChannelItem == null) return liveWebHeader();
        HashMap<String, String> header = new HashMap<>();
        HashMap<String, String> liveHeader = liveWebHeader();
        if (liveHeader != null) header.putAll(liveHeader);
        if (currentLiveChannelItem.getHeaders() != null) {
            header.putAll(currentLiveChannelItem.getHeaders());
        }
        if (!currentLiveChannelItem.getChannelFormat().isEmpty()) {
            header.put(ExoMediaSourceHelper.HEADER_FORMAT, currentLiveChannelItem.getChannelFormat());
        }
        if (header.isEmpty()) return null;
        return header;
    }

    private boolean currentChannelHasCatchup() {
        return currentLiveChannelItem != null && hasCatchupSource(currentLiveChannelItem.getChannelCatchup());
    }

    private JsonObject currentCatchup() {
        if (currentChannelHasCatchup()) return currentLiveChannelItem.getChannelCatchup();
        return catchup;
    }

    private String getCatchupValue(JsonObject catchupObj, String key) {
        if (catchupObj == null || !catchupObj.has(key) || catchupObj.get(key).isJsonNull()) return "";
        try {
            return catchupObj.get(key).getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean hasCatchupSource(JsonObject catchupObj) {
        return !TextUtils.isEmpty(getCatchupValue(catchupObj, "source"));
    }

    private boolean canCurrentChannelCatchup() {
        if (currentLiveChannelItem == null) return false;
        String url = currentLiveChannelItem.getUrl();
        JsonObject catchupObj = currentCatchup();
        if (hasCatchupSource(catchupObj)) {
            String regex = getCatchupValue(catchupObj, "regex");
            if (TextUtils.isEmpty(regex)) return true;
            try {
                return url.contains(regex) || Pattern.compile(regex).matcher(url).find();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return url.contains("/PLTV/");
    }

    private String buildCatchupUrl(String url, Epginfo epg) {
        if (TextUtils.isEmpty(url) || epg == null || epg.startdateTime == null || epg.enddateTime == null) return "";
        JsonObject catchupObj = currentCatchup();
        if (hasCatchupSource(catchupObj)) {
            return formatCatchupUrl(url, catchupObj, epg);
        }
        if (!url.contains("/PLTV/")) return "";
        String source = "?playseek=" + formatCatchupTime(epg.startdateTime, "yyyyMMddHHmmss")
                + "-" + formatCatchupTime(epg.enddateTime, "yyyyMMddHHmmss");
        return appendCatchupUrl(url, "/PLTV/,/TVOD/", source);
    }

    private String formatCatchupUrl(String url, JsonObject catchupObj, Epginfo epg) {
        String source = formatCatchupSource(getCatchupValue(catchupObj, "source"), epg);
        if ("default".equalsIgnoreCase(getCatchupValue(catchupObj, "type"))) return source;
        return appendCatchupUrl(url, getCatchupValue(catchupObj, "replace"), source);
    }

    private String appendCatchupUrl(String url, String replace, String source) {
        String replayUrl = url;
        String[] parts = replace.split(",", 2);
        if (parts.length == 2 && !TextUtils.isEmpty(parts[0])) {
            try {
                replayUrl = replayUrl.replaceAll(parts[0], parts[1]);
            } catch (Throwable ignored) {
            }
        }
        int queryIndex = replayUrl.indexOf('?');
        if (queryIndex >= 0 && queryIndex < replayUrl.length() - 1) source = source.replace("?", "&");
        return replayUrl + source;
    }

    private String formatCatchupSource(String source, Epginfo epg) {
        Matcher matcher = CATCHUP_TOKEN_PATTERN.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(formatCatchupToken(matcher.group(1), epg)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String formatCatchupToken(String token, Epginfo epg) {
        Matcher matcher = CATCHUP_TAG_PATTERN.matcher(token);
        if (!matcher.find()) return "";
        String tag = matcher.group(1);
        if (tag.startsWith("utcend:")) return String.valueOf(epg.enddateTime.getTime() / 1000);
        if (tag.startsWith("utc:")) return String.valueOf(epg.startdateTime.getTime() / 1000);
        int bracketIndex = tag.indexOf(')');
        if (tag.startsWith("(b") && bracketIndex >= 0) return formatCatchupTime(epg.startdateTime, tag.substring(bracketIndex + 1));
        if (tag.startsWith("(e") && bracketIndex >= 0) return formatCatchupTime(epg.enddateTime, tag.substring(bracketIndex + 1));
        return "";
    }

    private String formatCatchupTime(Date time, String pattern) {
        if ("timestamp".equals(pattern)) return String.valueOf(time.getTime() / 1000);
        try {
            return new SimpleDateFormat(pattern, Locale.getDefault()).format(time);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private int getCatchupDurationSeconds(Epginfo epg) {
        if (epg == null || epg.startdateTime == null || epg.enddateTime == null) return 0;
        long duration = Math.max(0, epg.enddateTime.getTime() - epg.startdateTime.getTime()) / 1000;
        return duration > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) duration;
    }

    private void showSwitchChannelSnapshot() {
        if (switchChannelSnapshotImage != null && mVideoView != null) {
            Bitmap bitmap = null;
            try {
                bitmap = mVideoView.doScreenShot();
            } catch (Throwable ignored) {
            }
            if (bitmap != null) {
                switchChannelSnapshotImage.setImageBitmap(bitmap);
                switchChannelSnapshotImage.setVisibility(View.VISIBLE);
            } else {
                switchChannelSnapshotImage.setImageBitmap(null);
                switchChannelSnapshotImage.setVisibility(View.GONE);
            }
        }
        if (switchChannelSnapshotOverlay != null) {
            switchChannelSnapshotOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void hideSwitchChannelSnapshot() {
        // 缓冲抖动时这个方法每秒会被调十几次，而绝大多数时候快照早就 GONE 了。
        // 先判可见性，省掉每轮 setImageBitmap(null) + 两次 setVisibility 引发的重绘。
        if (switchChannelSnapshotOverlay != null
                && switchChannelSnapshotOverlay.getVisibility() != View.GONE) {
            switchChannelSnapshotOverlay.setVisibility(View.GONE);
        }
        if (switchChannelSnapshotImage != null
                && switchChannelSnapshotImage.getVisibility() != View.GONE) {
            switchChannelSnapshotImage.setImageBitmap(null);
            switchChannelSnapshotImage.setVisibility(View.GONE);
        }
    }

    private boolean playChannel(int channelGroupIndex, int liveChannelIndex, boolean changeSource) {
        // 换台 = 全新起播，清掉状态去重标记，保证新频道的首个播放状态不被吞掉
        lastHandledPlayState = -1;
        // 每次起播重新等首帧；失败时据此判定是「起播失败」还是「播放中断」
        liveFirstFrameShown = false;
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
                // 列表被清空后（切源失败/重载配置）仍可能走到换源分支，这里原来直接
                // getSourceNum() → NPE。顺便把「只有一个源」的早退一起判掉。
                || (changeSource && (currentLiveChannelItem == null || currentLiveChannelItem.getSourceNum() == 1))) {
           // showChannelInfo();
            return true;
        }
        ArrayList<LiveChannelItem> groupChannels = getLiveChannels(channelGroupIndex);
        if (groupChannels == null || groupChannels.isEmpty() || liveChannelIndex < 0 || liveChannelIndex >= groupChannels.size()) {
            return false;
        }
        boolean showPreviousFrame = currentLiveChannelItem != null && mVideoView != null && mVideoView.isPlaying();
        int previousLivePlayerType = livePlayerManager.getLivePlayerType();
        allowLiveSwitchPlayer = true;
        // 换台 = 新频道的全新起播：自动换播放器的预算重新给足
        livePlayerFailoverStep = 0;
        if (!changeSource) {
            currentChannelGroupIndex = channelGroupIndex;
            currentLiveChannelIndex = liveChannelIndex;
            currentLiveChannelItem = getLiveChannels(currentChannelGroupIndex).get(currentLiveChannelIndex);
            Hawk.put(HawkConfig.LIVE_CHANNEL, currentLiveChannelItem.getChannelName());
            // 换台 = 新频道的全新起播，换源计数必须归零。
            // 否则：A 频道超时换源累计到 2，用户切到 B 频道时计数未清，B 第一次
            // 超时就被判定「源已试完」，直接跳过 B 剩下几个源、跳到下一个频道。
            currentLiveChangeSourceTimes = 0;
            // 注意：这里不做任何「跳过坏线路」的处理。
            // 线路的顺序由用户自己的服务端下发，客户端无权重排，
            // 排第几就播第几；播不动了按既有的换源流程往后走。
        }

        channel_Name = currentLiveChannelItem;
        currentLiveLookBackIndex=-1;
        epgListAdapter.setSelectedEpgIndex(-1);
        isSHIYI=false;
        isBack = false;
        if (canCurrentChannelCatchup()) {
            currentLiveChannelItem.setinclude_back(true);
        }else {
            currentLiveChannelItem.setinclude_back(false);
        }
        updateCurrentChannelIcon();
        showBottomEpg();
        backcontroller.setVisibility(View.GONE);
        ll_right_top_huikan.setVisibility(View.GONE);
        if(mVideoView!=null){
            if(liveChannelHeader()!=null)LOG.i("echo-"+liveChannelHeader().toString());
            boolean reusePlayer = canReusePlayer(previousLivePlayerType);
            boolean keepExoFrame = reusePlayer && previousLivePlayerType == 2;
            if (showPreviousFrame && !keepExoFrame) {
                showSwitchChannelSnapshot();
            } else {
                hideSwitchChannelSnapshot();
            }
            String liveUrl = currentLiveChannelItem.getUrl();
            if (reusePlayer) {
                mVideoView.setUrl(liveUrl, liveChannelHeader());
                mVideoView.replay(true);
            } else {
                mVideoView.release();
                mVideoView.setUrl(liveUrl,liveChannelHeader());
                mVideoView.start();
            }
            showResolutionAfterChannelSwitch();
        }
        loadEpgAfterChannelStarted();
        return true;
    }

    private boolean canReusePlayer(int previousLivePlayerType) {
        int currentLivePlayerType = livePlayerManager.getLivePlayerType();
        return mVideoView != null
                && mVideoView.getCurrentPlayState() != VideoView.STATE_IDLE
                && previousLivePlayerType == currentLivePlayerType;
    }

    /**
     * 失败后等多久重试。
     *
     * <p><b>还没出过首帧就失败</b> ⟹ 起播失败。绝大多数是源侧问题（DNS 无记录 /
     * 连接被拒 / 404 / 协议不支持），实测 IJK 只用 80ms 就返回，再等 3.5 秒纯属空等。
     * 这里缩到 800ms：<ul>
     *   <li>换引擎的兜底能力仍然保留（codec 不支持时下一个引擎可能就通了），
     *       次数由 {@code livePlayerFailoverStep} 管着，用完自然落到换线路；</li>
     *   <li>一条坏线路上原来要耗 3×3.5s，现在 3×0.8s。</li>
     * </ul></p>
     *
     * <p><b>出过首帧之后才失败</b> ⟹ 播放中断，可能是中途断流或解码器崩了，
     * 保持原来的 3.5 秒节奏，给网络一点恢复时间。</p>
     *
     * <p>线路本身是否可用、谁排前面，由下发线路的服务端决定，客户端不做记忆与重排。</p>
     */
    private long delayForLiveFailure() {
        return liveFirstFrameShown ? LIVE_RETRY_PLAYING_FAIL_MS : LIVE_RETRY_BOOT_FAIL_MS;
    }

    private void loadEpgAfterChannelStarted() {
        mHandler.removeCallbacks(mLoadEpgRun);
        if (!hasEpgAddress()) {
            updateEpgPanelState(false);
            return;
        }
        if (hasCurrentEpgCache()) {
            firstLiveEpgLoad = false;
            return;
        }
        if (firstLiveEpgLoad) {
            firstLiveEpgLoad = false;
            mHandler.postDelayed(mLoadEpgRun, EPG_LOAD_DELAY);
        } else {
            getEpg(new Date());
        }
    }

    private boolean hasCurrentEpgCache() {
        if (channel_Name == null || liveEpgDateAdapter == null || liveEpgDateAdapter.getSelectedIndex() < 0) return false;
        String currentEpgKey = channel_Name.getChannelName() + "_" + Objects.requireNonNull(liveEpgDateAdapter.getItem(liveEpgDateAdapter.getSelectedIndex())).getDatePresented();
        return hsEpg.containsKey(currentEpgKey);
    }

    private void playNext() {
        if (!isCurrentLiveChannelValid()) return;
        Integer[] groupChannelIndex = getNextChannel(1);
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
    }

    private void playPrevious() {
        if (!isCurrentLiveChannelValid()) return;
        Integer[] groupChannelIndex = getNextChannel(-1);
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
    }

    public void playPreSource() {
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.preSource();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

    public void playNextSource() {
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.nextSource();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

    //显示设置列表
    private void showSettingGroup() {
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
        }
        if (tvRightSettingLayout.getVisibility() == View.INVISIBLE) {
            //重新载入默认状态
            ApiConfig.get().refreshLiveApiHistoryItems();
            loadCurrentSourceList();
            liveSettingGroupAdapter.setNewData(getVisibleLiveSettingGroupList());
            liveSettingGroupAdapter.setSelectedGroupIndex(-1);
            int settingGroupIndex = getDefaultSettingGroupIndex();
            selectSettingGroup(settingGroupIndex, false);
            int settingGroupPosition = liveSettingGroupAdapter.findPositionByGroupIndex(settingGroupIndex);
            mSettingGroupView.scrollToPosition(settingGroupPosition < 0 ? 0 : settingGroupPosition);
            int settingItemIndex = currentLiveChannelItem == null ? 0 : currentLiveChannelItem.getSourceIndex();
            if (liveSettingItemAdapter.getData().isEmpty() || settingItemIndex < 0 || settingItemIndex >= liveSettingItemAdapter.getData().size()) {
                settingItemIndex = 0;
            }
            mSettingItemView.scrollToPosition(settingItemIndex);
            mHandler.postDelayed(mFocusAndShowSettingGroup, 50);
        } else {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
        }
    }

    private Runnable mFocusAndShowSettingGroup = new Runnable() {
        @Override
        public void run() {
            if (mSettingGroupView.isScrolling() || mSettingItemView.isScrolling() || mSettingGroupView.isComputingLayout() || mSettingItemView.isComputingLayout()) {
                mHandler.postDelayed(this, 100);
            } else {
                int settingGroupIndex = getDefaultSettingGroupIndex();
                int settingGroupPosition = liveSettingGroupAdapter.findPositionByGroupIndex(settingGroupIndex);
                RecyclerView.ViewHolder holder = mSettingGroupView.findViewHolderForAdapterPosition(settingGroupPosition < 0 ? 0 : settingGroupPosition);
                if (holder != null)
                    holder.itemView.requestFocus();
                tvRightSettingLayout.setVisibility(View.VISIBLE);
                updateTopStatusVisibility();
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) tvRightSettingLayout.getLayoutParams();
                if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
                    ViewObj viewObj = new ViewObj(tvRightSettingLayout, params);
                    ObjectAnimator animator = ObjectAnimator.ofObject(viewObj, "marginRight", new IntEvaluator(), -tvRightSettingLayout.getLayoutParams().width, livePanelEdgeMargin());
                    animator.setDuration(200);
                    animator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
                        }
                    });
                    animator.start();
                }
            }
        }
    };

    private Runnable mHideSettingLayoutRun = new Runnable() {
        @Override
        public void run() {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) tvRightSettingLayout.getLayoutParams();
            if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
                ViewObj viewObj = new ViewObj(tvRightSettingLayout, params);
                ObjectAnimator animator = ObjectAnimator.ofObject(viewObj, "marginRight", new IntEvaluator(), params.rightMargin, -tvRightSettingLayout.getLayoutParams().width);
                animator.setDuration(200);
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        tvRightSettingLayout.setVisibility(View.INVISIBLE);
                        updateTopStatusVisibility();
                        liveSettingGroupAdapter.setSelectedGroupIndex(-1);
                    }
                });
                animator.start();
            }
        }
    };

    private int livePanelEdgeMargin() {
        return 0;
    }

    //laodao 7天Epg数据绑定和展示
    private void initEpgListView() {
        mRightEpgList.setHasFixedSize(true);
        mRightEpgList.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        epgListAdapter = new LiveEpgAdapter();
        mRightEpgList.setAdapter(epgListAdapter);

        mRightEpgList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, postTimeout);
            }
        });
        //电视
        mRightEpgList.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                epgListAdapter.setFocusedEpgIndex(-1);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, postTimeout);
                epgListAdapter.setFocusedEpgIndex(position);
            }

            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if(position==currentLiveLookBackIndex)return;
                Date date = liveEpgDateAdapter.getSelectedIndex() < 0 ? new Date() :
                        liveEpgDateAdapter.getData().get(liveEpgDateAdapter.getSelectedIndex()).getDateParamVal();
                Epginfo selectedData = epgListAdapter.getItem(position);
                if (selectedData == null || selectedData.startdateTime == null || selectedData.enddateTime == null) return;
                Date now = new Date();
                if(new Date().compareTo(selectedData.startdateTime) < 0){
                    return;
                }
                if (now.after(selectedData.enddateTime) && !canCurrentChannelCatchup()) return;
                currentLiveLookBackIndex=position;
                epgListAdapter.setSelectedEpgIndex(position);
                if (now.compareTo(selectedData.startdateTime) >= 0 && now.compareTo(selectedData.enddateTime) <= 0) {
                    mVideoView.release();
                    isSHIYI = false;
                    mVideoView.setUrl(currentLiveChannelItem.getUrl(),liveChannelHeader());
                    mVideoView.start();
                    epgListAdapter.setShiyiSelection(-1, false,timeFormat.format(date));
                    epgListAdapter.notifyDataSetChanged();
                    showProgressBars(false);
                    return;
                }
                String shiyiUrl = currentLiveChannelItem.getUrl();
                if (now.compareTo(selectedData.startdateTime) < 0) {

                } else if (canCurrentChannelCatchup()) {
                    // ★ 先把回看地址算出来、确认非空，再去动播放器。
                    //
                    // 原顺序是先 release() 再算地址：地址拼不出来（模板缺 token、
                    // 节目时间为空等）时播放器已经拆了，而 isSHIYI 也已置 true，
                    // 于是画面永久黑屏；更糟的是 showBottomEpg() 开头
                    // if (isSHIYI) return，底部节目单从此不再刷新，只能退出重进。
                    shiyiUrl = buildCatchupUrl(shiyiUrl, selectedData);
                    if (TextUtils.isEmpty(shiyiUrl)) return;
                    mHandler.removeCallbacks(mHideChannelListRun);
                    mHandler.postDelayed(mHideChannelListRun, 100);
                    mVideoView.release();
                    isSHIYI = true;
                    //mCanSeek=true;
                    playUrl = shiyiUrl;

                    mVideoView.setUrl(playUrl,liveChannelHeader());
                    mVideoView.start();
                    epgListAdapter.setShiyiSelection(position, true, timeFormat.format(date));
                    epgListAdapter.notifyDataSetChanged();
                    mRightEpgList.setSelectedPosition(position);
                    mRightEpgList.post(new Runnable() {
                        @Override
                        public void run() {
                            mRightEpgList.smoothScrollToPosition(position);
                        }
                    });
                    shiyi_time_c = getCatchupDurationSeconds(selectedData);
                    ViewGroup.LayoutParams lp =  iv_play.getLayoutParams();
                    lp.width=videoHeight/7;
                    lp.height=videoHeight/7;
                    sBar = (SeekBar) findViewById(R.id.pb_progressbar);
                    sBar.setMax(safeTimeMs((long) shiyi_time_c * 1000));
                    sBar.setProgress(safeTimeMs(mVideoView.getCurrentPosition()));
                    tv_currentpos.setText(durationToString(safeTimeMs(mVideoView.getCurrentPosition())));
                    tv_duration.setText(durationToString(safeTimeMs((long) shiyi_time_c * 1000)));
                    showProgressBars(true);
                    isBack = true;
                }
            }
        });

        //手机/模拟器
        epgListAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if(position==currentLiveLookBackIndex)return;
                Date date = liveEpgDateAdapter.getSelectedIndex() < 0 ? new Date() :
                        liveEpgDateAdapter.getData().get(liveEpgDateAdapter.getSelectedIndex()).getDateParamVal();
                Epginfo selectedData = epgListAdapter.getItem(position);
                if (selectedData == null || selectedData.startdateTime == null || selectedData.enddateTime == null) return;
                Date now = new Date();
                if(new Date().compareTo(selectedData.startdateTime) < 0){
                    return;
                }
                if (now.after(selectedData.enddateTime) && !canCurrentChannelCatchup()) return;
                currentLiveLookBackIndex=position;
                epgListAdapter.setSelectedEpgIndex(position);
                if (now.compareTo(selectedData.startdateTime) >= 0 && now.compareTo(selectedData.enddateTime) <= 0) {
                    mVideoView.release();
                    isSHIYI = false;
                    mVideoView.setUrl(currentLiveChannelItem.getUrl(),liveChannelHeader());
                    mVideoView.start();
                    epgListAdapter.setShiyiSelection(-1, false,timeFormat.format(date));
                    epgListAdapter.notifyDataSetChanged();
                    showProgressBars(false);
                    return;
                }
                String shiyiUrl = currentLiveChannelItem.getUrl();
                if (now.compareTo(selectedData.startdateTime) < 0) {

                } else if (canCurrentChannelCatchup()) {
                    // ★ 先把回看地址算出来、确认非空，再去动播放器。
                    //
                    // 原顺序是先 release() 再算地址：地址拼不出来（模板缺 token、
                    // 节目时间为空等）时播放器已经拆了，而 isSHIYI 也已置 true，
                    // 于是画面永久黑屏；更糟的是 showBottomEpg() 开头
                    // if (isSHIYI) return，底部节目单从此不再刷新，只能退出重进。
                    shiyiUrl = buildCatchupUrl(shiyiUrl, selectedData);
                    if (TextUtils.isEmpty(shiyiUrl)) return;
                    mHandler.removeCallbacks(mHideChannelListRun);
                    mHandler.postDelayed(mHideChannelListRun, 100);
                    mVideoView.release();
                    isSHIYI = true;
                    //mCanSeek=true;
                    playUrl = shiyiUrl;
                    if(liveChannelHeader()!=null)LOG.i("echo-liveWebHeader :"+ liveChannelHeader().toString());
                    mVideoView.setUrl(playUrl,liveChannelHeader());
                    mVideoView.start();
                    epgListAdapter.setShiyiSelection(position, true,timeFormat.format(date));
                    epgListAdapter.notifyDataSetChanged();
                    mRightEpgList.setSelectedPosition(position);
                    mRightEpgList.post(new Runnable() {
                        @Override
                        public void run() {
                            mRightEpgList.smoothScrollToPosition(position);
                        }
                    });
                    shiyi_time_c = getCatchupDurationSeconds(selectedData);
                    ViewGroup.LayoutParams lp =  iv_play.getLayoutParams();
                    lp.width=videoHeight/7;
                    lp.height=videoHeight/7;
                    sBar = (SeekBar) findViewById(R.id.pb_progressbar);
                    sBar.setMax(safeTimeMs((long) shiyi_time_c * 1000));
                    sBar.setProgress(safeTimeMs(mVideoView.getCurrentPosition()));
                   // long dd = mVideoView.getDuration();
                    tv_currentpos.setText(durationToString(safeTimeMs(mVideoView.getCurrentPosition())));
                    tv_duration.setText(durationToString(safeTimeMs((long) shiyi_time_c * 1000)));
                    showProgressBars(true);
                    isBack = true;
                }
            }
        });
    }
    //laoda 生成7天回放日期列表数据
    private void initDayList() {
        liveDayList.clear();

        LiveDayListGroup daylist = new LiveDayListGroup();
        Date newday= new Date((nowday.getTime()));
        String day = formatDate1.format(newday);
        daylist.setGroupIndex(0);
        daylist.setGroupName(day);
        liveDayList.add(daylist);
    }
    //kens 7天回放数据绑定和展示
    private void initEpgDateView() {
//        return;
        mEpgDateGridView.setHasFixedSize(true);
        mEpgDateGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        liveEpgDateAdapter = new LiveEpgDateAdapter();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        @SuppressLint("SimpleDateFormat") SimpleDateFormat datePresentFormat = new SimpleDateFormat("MM-dd");
        calendar.add(Calendar.DAY_OF_MONTH, 0);
        for (int i = 0; i < 1; i++) {
            Date dateIns = calendar.getTime();
            LiveEpgDate epgDate = new LiveEpgDate();
            epgDate.setIndex(i);
            epgDate.setDatePresented(datePresentFormat.format(dateIns));
            epgDate.setDateParamVal(dateIns);
            liveEpgDateAdapter.addData(epgDate);
//            calendar.add(Calendar.DAY_OF_MONTH, -1);
        }
        mEpgDateGridView.setAdapter(liveEpgDateAdapter);
        mEpgDateGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, postTimeout);
            }
        });

        liveEpgDateAdapter.setSelectedIndex(0);
        mEpgDateGridView.setVisibility(View.GONE);
    }



    private void initVideoView() {
        LiveController controller = new LiveController(this);
        // 持有引用：onDestroy 里要解绑回调，否则 Activity 销毁后播放器最后几个
        // 状态回调仍会打进来（singleTap / changeSource 会去操作已释放的播放器）
        liveController = controller;
        controller.setListener(new LiveController.LiveControlListener() {
            @Override
            public boolean singleTap() {
                showChannelList();
                return true;
            }

            @Override
            public void longPress() {
                if(isBack){  //手机换源和显示时移控制栏
                    showProgressBars(true);
                }else{
                    showSettingGroup();
                }
            }

            @Override
            public void playStateChanged(int playState) {
                // 直播流在 buffering/buffered 之间高频抖动时，同一个状态会被反复投递。
                // 状态没变就无需重排换源倒计时、也无需再收一次换台快照 —— 纯属白干。
                // 换台时由 playChannel() 把它重置为 -1，保证新频道首帧状态不被吞掉。
                if (playState == lastHandledPlayState) {
                    return;
                }
                lastHandledPlayState = playState;
                mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                switch (playState) {
                    case VideoView.STATE_IDLE:
                        // 空闲状态：播放器处于空闲，尚未开始播放。一般不需要自动换源。
                    case VideoView.STATE_PAUSED:
                        // 暂停状态：播放被暂停，通常是用户操作，不触发自动换源
                        break;
                    case VideoView.STATE_PREPARED:
                        // 准备就绪：播放器已经加载好媒体数据，但尚未开始播放。
                    case VideoView.STATE_BUFFERED:
                    case VideoView.STATE_PLAYING:
                        // 播放状态：当播放器缓冲完成或正在正常播放时，表明当前源是可用的，
                        hideSwitchChannelSnapshot();
                        if (resolutionInfoPending && !resolutionInfoRunScheduled) {
                            resolutionInfoRetryCount = 0;
                            mHandler.removeCallbacks(mUpdateResolutionInfoRun);
                            resolutionInfoRunScheduled = true;
                            mHandler.post(mUpdateResolutionInfoRun);
                        }
                        currentLiveChangeSourceTimes = 0;
                        allowLiveSwitchPlayer = true;
                        if (playState == VideoView.STATE_PLAYING && !liveFirstFrameShown) {
                            // 真的出画面了：后续若再失败就按「播放中断」处理
                            // （中途断流 / 解码器问题，值得换播放器重试）
                            liveFirstFrameShown = true;
                        }
                        break;
                    case VideoView.STATE_ERROR:
                    case VideoView.STATE_PLAYBACK_COMPLETED:
                        // 错误或播放结束状态：先判定失败发生在哪个阶段 ——
                        // 起播阶段（还没出画面）直接换线路，播放中才先换播放器
                        hideSwitchChannelSnapshot();
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, delayForLiveFailure());
                        break;
                    case VideoView.STATE_PREPARING:
                    case VideoView.STATE_BUFFERING:
                        // 正在准备或缓冲状态：表示当前源正在加载中
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, (Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5000L);
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void changeSource(int direction) {
                if (direction > 0)
                    if(isBack){  //手机换源和显示时移控制栏
                        showProgressBars(true);
                    }else{
                        playNextSource();
                    }
                else
                    playPreSource();
            }
        });
        controller.setCanChangePosition(false);
        controller.setEnableInNormal(true);
        controller.setGestureEnabled(true);
        controller.setDoubleTapTogglePlayEnabled(false);
        mVideoView.setVideoController(controller);
        mVideoView.setProgressManager(null);
    }

    private boolean switchLivePlayerAndReplay() {
        if (!allowLiveSwitchPlayer || currentLiveChannelItem == null || mVideoView == null) {
            return false;
        }
        // 自动换播放器的预算：先把「另一个硬解引擎」试完，最后才允许落到软解。
        // 用完就不再折腾播放器，交回给换源/换台流程，避免无限重试。
        if (livePlayerFailoverStep >= LIVE_PLAYER_MAX_FAILOVER) {
            allowLiveSwitchPlayer = false;
            return false;
        }
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
        mVideoView.release();
        // 换播放器重播 = 全新一次起播，状态去重标记必须清掉，
        // 否则首个状态若与上次相同会被吞掉，换源倒计时就排不上
        lastHandledPlayState = -1;
        // 重新等首帧：换引擎之后若仍旧秒失败，说明还是源的问题，
        // 下次失败会被判定为「起播失败」而直接换线路，不再继续换引擎。
        liveFirstFrameShown = false;
        if (!livePlayerManager.switchLivePlayer(mVideoView, livePlayerFailoverStep)) {
            allowLiveSwitchPlayer = false;
            return false;
        }
        livePlayerFailoverStep++;
        String retryUrl = isSHIYI && !TextUtils.isEmpty(playUrl) ? playUrl : currentLiveChannelItem.getUrl();
        mVideoView.setUrl(retryUrl, liveChannelHeader());
        mVideoView.start();
        return true;
    }

    private Runnable mConnectTimeoutChangeSourceRun = new Runnable() {
        @Override
        public void run() {
            if (switchLivePlayerAndReplay()) {
                return;
            }
            // 走到这里说明播放器已经不给机会了：从头到尾没出过画面
            // （起播超时 / 坏流 / 一直 buffering），交给换源流程往下一条线路走。
            currentLiveChangeSourceTimes++;
            if (currentLiveChannelItem.getSourceNum() == currentLiveChangeSourceTimes) {
                currentLiveChangeSourceTimes = 0;
                Integer[] groupChannelIndex = getNextChannel(Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false) ? -1 : 1);
                playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
            } else {
                playNextSource();
            }
        }
    };

    private void initChannelGroupView() {
        mChannelGroupView.setHasFixedSize(true);
        mChannelGroupView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveChannelGroupAdapter = new LiveChannelGroupAdapter();
        mChannelGroupView.setAdapter(liveChannelGroupAdapter);
        mChannelGroupView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, postTimeout);
            }
        });

        //电视
        mChannelGroupView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                selectChannelGroup(position, true, -1);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (isNeedInputPassword(position)) {
                    showPasswordDialog(position, -1);
                }
            }
        });

        //手机/模拟器
        liveChannelGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectChannelGroup(position, false, -1);
            }
        });
    }

    private void selectChannelGroup(int groupIndex, boolean focus, int liveChannelIndex) {
        if (focus && tvLeftChannelListLayout.getVisibility() != View.VISIBLE) return;
        mLastChannelGroupIndex=groupIndex;
        if (focus) {
            liveChannelGroupAdapter.setFocusedGroupIndex(groupIndex);
            clearFocusedChannelInMenu();
        }
        if ((groupIndex > -1 && groupIndex != liveChannelGroupAdapter.getSelectedGroupIndex()) || isNeedInputPassword(groupIndex)) {
            liveChannelGroupAdapter.setSelectedGroupIndex(groupIndex);
            if (isNeedInputPassword(groupIndex)) {
                showPasswordDialog(groupIndex, liveChannelIndex);
                return;
            }
            if (focus && liveChannelIndex < 0) {
                loadChannelGroupData(groupIndex);
            } else {
                loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
            }
        }
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.postDelayed(mHideChannelListRun, postTimeout);
        }
    }

    private void clearFocusedChannelInMenu() {
        if (tvLeftChannelListLayout.getVisibility() != View.VISIBLE) return;
        if (mLiveChannelView.isComputingLayout() || mLiveChannelView.isScrolling()) {
            mLiveChannelView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    clearFocusedChannelInMenu();
                }
            }, 50);
            return;
        }
        liveChannelItemAdapter.setFocusedChannelIndex(-1);
    }

    private void initLiveChannelView() {
        mLiveChannelView.setHasFixedSize(true);
        mLiveChannelView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveChannelItemAdapter = new LiveChannelItemAdapter();
        mLiveChannelView.setAdapter(liveChannelItemAdapter);
        mLiveChannelView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, postTimeout);
            }
        });

        //电视
        mLiveChannelView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (position < 0) return;
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, postTimeout);
                liveChannelGroupAdapter.setFocusedGroupIndex(-1);
                liveChannelItemAdapter.setFocusedChannelIndex(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                clickLiveChannel(position);
            }
        });

        //手机/模拟器
        liveChannelItemAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                liveChannelItemAdapter.setSelectedChannelIndex(position);
                clickLiveChannel(position);
            }
        });
    }

    private void clickLiveChannel(int position) {
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.postDelayed(mHideChannelListRun, postTimeout);
        }
        playChannel(liveChannelGroupAdapter.getSelectedGroupIndex(), position, false);
    }

    private void initSettingGroupView() {
        mSettingGroupView.setHasFixedSize(true);
        mSettingGroupView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveSettingGroupAdapter = new LiveSettingGroupAdapter();
        mSettingGroupView.setAdapter(liveSettingGroupAdapter);
        mSettingGroupView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
            }
        });

        //电视
        mSettingGroupView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
                selectVisibleSettingGroup(position, true);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });

        //手机/模拟器
        liveSettingGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectVisibleSettingGroup(position, false);
            }
        });
    }

    private void selectVisibleSettingGroup(int position, boolean focus) {
        if (position < 0 || position >= liveSettingGroupAdapter.getData().size()) return;
        selectSettingGroup(liveSettingGroupAdapter.getData().get(position).getGroupIndex(), focus);
    }

    private void selectSettingGroup(int position, boolean focus) {
        if (focus) {
            liveSettingGroupAdapter.setFocusedGroupIndex(position);
            liveSettingItemAdapter.setFocusedItemIndex(-1);
        }
        if (position == liveSettingGroupAdapter.getSelectedGroupIndex() || position < 0 || position >= liveSettingGroupList.size())
            return;

        liveSettingGroupAdapter.setSelectedGroupIndex(position);
        liveSettingItemAdapter.setNewData(liveSettingGroupList.get(position).getLiveSettingItems());

        switch (position) {
            case 0:
                if (currentLiveChannelItem != null
                        && currentLiveChannelItem.getSourceIndex() >= 0
                        && currentLiveChannelItem.getSourceIndex() < liveSettingItemAdapter.getData().size()) {
                    liveSettingItemAdapter.selectItem(currentLiveChannelItem.getSourceIndex(), true, false);
                }
                break;
            case 1:
                liveSettingItemAdapter.selectItem(livePlayerManager.getLivePlayerScale(), true, true);
                break;
            case 2:
                liveSettingItemAdapter.selectItem(livePlayerManager.getLivePlayerType(), true, true);
                break;
            case 6:
                liveSettingItemAdapter.selectItem(getCurrentLiveApiHistoryIndex(), true, true);
                break;
        }
        int scrollToPosition = liveSettingItemAdapter.getSelectedItemIndex();
        if (scrollToPosition < 0) scrollToPosition = 0;
        mSettingItemView.scrollToPosition(scrollToPosition);
        mHandler.removeCallbacks(mHideSettingLayoutRun);
        mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
    }

    private void initSettingItemView() {
        mSettingItemView.setHasFixedSize(true);
        mSettingItemView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveSettingItemAdapter = new LiveSettingItemAdapter();
        mSettingItemView.setAdapter(liveSettingItemAdapter);
        mSettingItemView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
            }
        });

        //电视
        mSettingItemView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (position < 0) return;
                liveSettingGroupAdapter.setFocusedGroupIndex(-1);
                liveSettingItemAdapter.setFocusedItemIndex(position);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                clickSettingItem(position);
            }
        });

        //手机/模拟器
        liveSettingItemAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                clickSettingItem(position);
            }
        });
    }

    private void clickSettingItem(int position) {
        int settingGroupIndex = liveSettingGroupAdapter.getSelectedGroupIndex();
        if (settingGroupIndex >= 0 && settingGroupIndex < 3 && !isCurrentLiveChannelValid()) {
            return;
        }
        if (settingGroupIndex < 4) {
            if (position == liveSettingItemAdapter.getSelectedItemIndex())
                return;
            liveSettingItemAdapter.selectItem(position, true, true);
        }
        switch (settingGroupIndex) {
            case 0://线路切换
                if (position < 0 || position >= currentLiveChannelItem.getSourceNum()) break;
                currentLiveChannelItem.setSourceIndex(position);
                playChannel(currentChannelGroupIndex, currentLiveChannelIndex,true);
                break;
            case 1://画面比例
                livePlayerManager.changeLivePlayerScale(mVideoView, position);
                break;
            case 2://播放解码
                mVideoView.release();
                // 用户手动选了播放器：以他选的为准，自动降级预算重新给足
                livePlayerFailoverStep = 0;
                allowLiveSwitchPlayer = true;
                livePlayerManager.changeLivePlayerType(mVideoView, position);
                mVideoView.setUrl(currentLiveChannelItem.getUrl(),liveChannelHeader());
                mVideoView.start();
                break;
            case 3://超时换源
                Hawk.put(HawkConfig.LIVE_CONNECT_TIMEOUT, position);
                break;
            case 4://偏好设置
                boolean select = false;
                switch (position) {
                    case 0:
                        select = !Hawk.get(HawkConfig.LIVE_SHOW_TIME, false);
                        Hawk.put(HawkConfig.LIVE_SHOW_TIME, select);
                        showTime();
                        break;
                    case 1:
                        select = !Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false);
                        Hawk.put(HawkConfig.LIVE_SHOW_NET_SPEED, select);
                        showNetSpeed();
                        break;
                    case 2:
                        select = !Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false);
                        Hawk.put(HawkConfig.LIVE_CHANNEL_REVERSE, select);
                        break;
                    case 3:
                        select = !Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false);
                        Hawk.put(HawkConfig.LIVE_CROSS_GROUP, select);
                        break;
                }
                liveSettingItemAdapter.selectItem(position, select, false);
                break;
            case 5://多源切换
                //TODO
                if(position==ApiConfig.getLiveGroupIndex())break;
                String currentChannelName = getPreferredLiveRefreshChannelName();
                int currentSourceIndex = getPreferredLiveRefreshSourceIndex();
                JsonArray live_groups=Hawk.get(HawkConfig.LIVE_GROUP_LIST,new JsonArray());
                if (live_groups == null || position >= live_groups.size()) break;
                liveConfigRequestId++;
                JsonObject livesOBJ = live_groups.get(position).getAsJsonObject();
                liveSettingItemAdapter.selectItem(position, true, true);
                ApiConfig.setLiveGroupIndex(position);
                ApiConfig.get().loadLiveApi(livesOBJ);
                if (ApiConfig.get().getChannelGroupList().isEmpty()) {
                    if (mVideoView != null) mVideoView.release();
                    setEmptyLiveChannelList(false);
                    break;
                }
                refreshLiveChannelListAndPlay(currentChannelName, currentSourceIndex);
                break;
            case 6: {//配置切换
                ArrayList<String> history = Hawk.get(HawkConfig.LIVE_API_HISTORY, new ArrayList<String>());
                if (history.isEmpty() || position < 0 || position >= history.size()) break;
                String value = history.get(position);
                String oldLiveApi = Hawk.get(HawkConfig.LIVE_API_URL, "");
                String configChannelName = getPreferredLiveRefreshChannelName();
                int configSourceIndex = getPreferredLiveRefreshSourceIndex();
                liveSettingItemAdapter.selectItem(position, true, true);
                if (value.equals(oldLiveApi)) break;
                final int requestId = ++liveConfigRequestId;
                Hawk.put(HawkConfig.LIVE_API_URL, value);
                HistoryHelper.setLiveApiHistory(value);
                ApiConfig.get().refreshLiveApiHistoryItems();
                ApiConfig.get().loadLiveConfig(false, new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (requestId != liveConfigRequestId || isFinishing()) return;
                                refreshLiveChannelListAndPlay(configChannelName, configSourceIndex);
                            }
                        });
                    }

                    @Override
                    public void error(String msg) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (requestId != liveConfigRequestId || isFinishing()) return;
                                if (mVideoView != null) mVideoView.release();
                                ApiConfig.get().refreshLiveApiHistoryItems();
                                setEmptyLiveChannelList(false);
                                Toast.makeText(LivePlayActivity.this, msg, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void notice(String msg) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (requestId != liveConfigRequestId || isFinishing()) return;
                                Toast.makeText(LivePlayActivity.this, msg, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
                break;
            }
        }
        mHandler.removeCallbacks(mHideSettingLayoutRun);
        mHandler.postDelayed(mHideSettingLayoutRun, postTimeout);
    }

    private String getPreferredLiveRefreshChannelName() {
        if (currentLiveChannelItem != null) return currentLiveChannelItem.getChannelName();
        return Hawk.get(HawkConfig.LIVE_CHANNEL, "");
    }

    private int getPreferredLiveRefreshSourceIndex() {
        if (currentLiveChannelItem != null) return currentLiveChannelItem.getSourceIndex();
        return -1;
    }

    private void refreshLiveChannelListAndPlay(String channelName, int sourceIndex) {
        refreshingLiveChannelList = true;
        pendingLiveRefreshChannelName = channelName;
        pendingLiveRefreshSourceIndex = sourceIndex;
        currentLiveLookBackIndex = -1;
        currentLiveChangeSourceTimes = 0;
        allowLiveSwitchPlayer = true;
        channelGroupPasswordConfirmed.clear();
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
        mHandler.removeCallbacks(mLoadEpgRun);
        hideSwitchChannelSnapshot();
        if (tvLeftChannelListLayout != null) tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        if (tvRightSettingLayout != null) tvRightSettingLayout.setVisibility(View.INVISIBLE);
        if (liveChannelGroupAdapter != null) {
            liveChannelGroupAdapter.clearGroupState();
        }
        if (liveChannelItemAdapter != null) {
            liveChannelItemAdapter.setFocusedChannelIndex(-1);
            liveChannelItemAdapter.setSelectedChannelIndex(-1);
            liveChannelItemAdapter.setNewData(new ArrayList<LiveChannelItem>());
        }
        initLiveChannelList();
        initLiveSettingGroupList();
    }

    private int getCurrentLiveApiHistoryIndex() {
        ArrayList<String> history = Hawk.get(HawkConfig.LIVE_API_HISTORY, new ArrayList<String>());
        if (history.isEmpty()) return -1;
        String current = Hawk.get(HawkConfig.LIVE_API_URL, "");
        int idx = history.indexOf(current);
        return idx >= 0 ? idx : -1;
    }

    private void initLiveChannelList() {
        if (ApiConfig.get().shouldReloadLiveConfig()) {
            loadLiveConfigOnEnter();
            return;
        }
        List<LiveChannelGroup> list = ApiConfig.get().getChannelGroupList();
        if (list.isEmpty()) {
            loadLiveConfigOnEnter();
            return;
        }
        initLiveObj();
        if (list.size() == 1 && list.get(0).getGroupName().startsWith("http://127.0.0.1")) {
            loadProxyLives(list.get(0).getGroupName());
        } else {
            applyLiveChannelGroups(list);
        }
    }

    private boolean loadingLiveConfigOnEnter = false;

    private void loadLiveConfigOnEnter() {
        if (loadingLiveConfigOnEnter) return;
        loadingLiveConfigOnEnter = true;
        showLoading();
        ApiConfig.get().loadLiveConfig(true, new ApiConfig.LoadConfigCallback() {
            @Override
            public void success() {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        loadingLiveConfigOnEnter = false;
                        initLiveChannelList();
                        initLiveSettingGroupList();
                    }
                });
            }

            @Override
            public void error(String msg) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        loadingLiveConfigOnEnter = false;
                        setEmptyLiveChannelList();
                    }
                });
            }

            @Override
            public void notice(String msg) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(LivePlayActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    public void loadProxyLives(String url) {
        try {
            Uri parsedUrl = Uri.parse(url);
            url = new String(Base64.decode(parsedUrl.getQueryParameter("ext"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
        } catch (Throwable th) {
            if (!url.startsWith("http://127.0.0.1")) {
                setEmptyLiveChannelList();
                return;
            }
        }
        if (!isValidLiveProxyUrl(url)) {
            setEmptyLiveChannelList();
            return;
        }
        if (!refreshingLiveChannelList) {
            showLoading();
        }


        if(url.contains(".py") || url.contains(".js")){
            String finalUrl = url;
            // 外层也要持有引用：这个线程池跑的就是 waitResponse 本体，
            // 任务结束后必须关掉，否则每次刷新直播配置都留一条永不退出的非守护线程。
            final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() {
                            Spider sp = ApiConfig.get().getLiveCSP(finalUrl);
                            String json=sp.liveContent(finalUrl);
                            return json;
                        }
                    });
                    String sortJson = null;
                    try {
                        sortJson = future.get(ApiConfig.get().getLiveConnectTimeoutSeconds(), TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    }
                    // ★ shutdown 原来写在 finally 里、却排在两条 return 之后：
                    // 「解析结果为空」和「分组列表为空」这两个分支会直接 return 掉，
                    // executor 再也没人关，线程永久存活（newSingleThreadExecutor 的
                    // 核心线程不会超时回收）。刷新几次配置就攒几条僵尸线程。
                    // 改成 try/finally 包住整段逻辑，任何出口都保证关闭。
                    try {
                        if (sortJson==null || sortJson.isEmpty()) {
                            // 频道列表为空时，使用默认播放列表
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    setEmptyLiveChannelList();
                                }
                            });
                            return;
                        }
                        JsonArray livesArray = TxtSubscribe.parseToJsonArray(sortJson);

                        ApiConfig.get().loadLives(livesArray);
                        List<LiveChannelGroup> list = ApiConfig.get().getChannelGroupList();
                        if (list.isEmpty()) {
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    setEmptyLiveChannelList();
                                }
                            });
                            return;
                        }
                        final ArrayList<LiveChannelGroup> loadedGroups = new ArrayList<>(list);

                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                applyLiveChannelGroups(loadedGroups);
                            }
                        });
                    } finally {
                        try {
                            executor.shutdown();
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                        try {
                            loadExecutor.shutdown();
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                }
            };
            loadExecutor.execute(waitResponse);
        }else {
            OkGo.<String>get(url).execute(new AbsCallback<String>() {

                @Override
                public String convertResponse(okhttp3.Response response) throws Throwable {
                    assert response.body() != null;
                    return response.body().string();
                }

                @Override
                public void onSuccess(Response<String> response) {
                    JsonArray livesArray = TxtSubscribe.parseToJsonArray(response.body());

                    ApiConfig.get().loadLives(livesArray);
                    List<LiveChannelGroup> list = ApiConfig.get().getChannelGroupList();
                    if (list.isEmpty()) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                setEmptyLiveChannelList();
                            }
                        });
                        return;
                    }
                    final ArrayList<LiveChannelGroup> loadedGroups = new ArrayList<>(list);

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            applyLiveChannelGroups(loadedGroups);
                        }
                    });
                }

                @Override
                public void onError(Response<String> response) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setEmptyLiveChannelList();
                        }
                    });
                }
            });
        }
    }

    private boolean isValidLiveProxyUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lowerUrl = url.trim().toLowerCase(Locale.US);
        return lowerUrl.startsWith("http://")
                || lowerUrl.startsWith("https://")
                || lowerUrl.startsWith("rtsp://")
                || lowerUrl.startsWith("rtmp://")
                || lowerUrl.startsWith("rtp://");
    }

    private void applyLiveChannelGroups(List<LiveChannelGroup> groups) {
        liveChannelGroupList.clear();
        liveChannelGroupList.addAll(groups);
        showSuccess();
        initLiveState();
    }

    private void initLiveState() {
        refreshingLiveChannelList = false;
        String lastChannelName = pendingLiveRefreshChannelName == null ? Hawk.get(HawkConfig.LIVE_CHANNEL, "") : pendingLiveRefreshChannelName;
        int sourceIndex = pendingLiveRefreshSourceIndex;
        pendingLiveRefreshChannelName = null;
        pendingLiveRefreshSourceIndex = -1;

        int lastChannelGroupIndex = -1;
        int lastLiveChannelIndex = -1;
        LiveChannelItem lastLiveChannelItem = null;
        for (LiveChannelGroup liveChannelGroup : liveChannelGroupList) {
            ArrayList<LiveChannelItem> groupChannels = liveChannelGroup.getLiveChannels();
            if (groupChannels == null || groupChannels.isEmpty()) {
                continue;
            }
            for (LiveChannelItem liveChannelItem : groupChannels) {
                if (liveChannelItem.getChannelName().equals(lastChannelName)) {
                    lastChannelGroupIndex = liveChannelGroup.getGroupIndex();
                    lastLiveChannelIndex = liveChannelItem.getChannelIndex();
                    lastLiveChannelItem = liveChannelItem;
                    break;
                }
            }
            if (lastChannelGroupIndex != -1) break;
        }
        if (lastChannelGroupIndex == -1) {
            Integer[] cctv1Channel = getFirstChannelByName("CCTV1");
            if (cctv1Channel != null) {
                lastChannelGroupIndex = cctv1Channel[0];
                lastLiveChannelIndex = cctv1Channel[1];
            } else {
                lastChannelGroupIndex = getFirstNoPasswordChannelGroup();
                if (lastChannelGroupIndex == -1)
                    lastChannelGroupIndex = 0;
                lastLiveChannelIndex = 0;
            }
        }
        if (lastLiveChannelItem != null && sourceIndex >= 0 && lastLiveChannelItem.getSourceNum() > 0) {
            lastLiveChannelItem.setSourceIndex(Math.min(sourceIndex, lastLiveChannelItem.getSourceNum() - 1));
        }

        livePlayerManager.init(mVideoView);
        showTime();
        showNetSpeed();
        tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        tvRightSettingLayout.setVisibility(View.INVISIBLE);
        updateTopStatusVisibility();

        liveChannelGroupAdapter.clearGroupState();
        liveChannelGroupAdapter.setNewData(new ArrayList<>(liveChannelGroupList));
        currentLiveChannelIndex = -1;
        selectChannelGroup(lastChannelGroupIndex, false, lastLiveChannelIndex);
    }

    private boolean isListOrSettingLayoutVisible() {
        return tvLeftChannelListLayout.getVisibility() == View.VISIBLE || tvRightSettingLayout.getVisibility() == View.VISIBLE;
    }

    private boolean hasCurrentLiveChannelSource() {
        return currentLiveChannelItem != null
                && currentLiveChannelItem.getChannelUrls() != null
                && currentLiveChannelItem.getSourceNum() > 0
                && currentLiveChannelItem.getSourceIndex() >= 0
                && currentLiveChannelItem.getSourceIndex() < currentLiveChannelItem.getChannelUrls().size();
    }

    private int getDefaultSettingGroupIndex() {
        if (hasCurrentLiveChannelSource()) return 0;
        return liveSettingGroupList != null && liveSettingGroupList.size() > 6 ? 6 : 0;
    }

    private ArrayList<LiveSettingGroup> getVisibleLiveSettingGroupList() {
        ArrayList<LiveSettingGroup> visibleGroups = new ArrayList<>();
        if (liveSettingGroupList == null) return visibleGroups;
        boolean showChannelOptions = hasCurrentLiveChannelSource();
        for (LiveSettingGroup group : liveSettingGroupList) {
            if (group == null) continue;
            int groupIndex = group.getGroupIndex();
            if (!showChannelOptions && groupIndex >= 0 && groupIndex <= 2) continue;
            visibleGroups.add(group);
        }
        return visibleGroups;
    }

    private void initLiveSettingGroupList() {
        liveSettingGroupList=ApiConfig.get().getLiveSettingGroupList();
        if (liveSettingGroupList.size() < 7) return;
        liveSettingGroupList.get(3).getLiveSettingItems().get(Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1)).setItemSelected(true);
        liveSettingGroupList.get(4).getLiveSettingItems().get(0).setItemSelected(Hawk.get(HawkConfig.LIVE_SHOW_TIME, false));
        liveSettingGroupList.get(4).getLiveSettingItems().get(1).setItemSelected(Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false));
        liveSettingGroupList.get(4).getLiveSettingItems().get(2).setItemSelected(Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false));
        liveSettingGroupList.get(4).getLiveSettingItems().get(3).setItemSelected(Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false));
        int liveGroupIndex = ApiConfig.getLiveGroupIndex();
        if (liveGroupIndex >= 0 && liveGroupIndex < liveSettingGroupList.get(5).getLiveSettingItems().size()) {
            liveSettingGroupList.get(5).getLiveSettingItems().get(liveGroupIndex).setItemSelected(true);
        }
    }

    private void loadCurrentSourceList() {
        ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
        if (currentLiveChannelItem != null && currentLiveChannelItem.getChannelSourceNames() != null) {
            ArrayList<String> currentSourceNames = currentLiveChannelItem.getChannelSourceNames();
            for (int j = 0; j < currentSourceNames.size(); j++) {
                LiveSettingItem liveSettingItem = new LiveSettingItem();
                liveSettingItem.setItemIndex(j);
                liveSettingItem.setItemName(currentSourceNames.get(j));
                liveSettingItemList.add(liveSettingItem);
            }
        }
        liveSettingGroupList.get(0).setLiveSettingItems(liveSettingItemList);
    }

    private void showResolutionAfterChannelSwitch() {
        resolutionInfoPending = true;
        resolutionInfoRetryCount = 0;
        if (tvResolution != null) {
            tvResolution.setText("");
            tvResolution.setVisibility(View.GONE);
        }
        mHandler.removeCallbacks(mHideResolutionInfoRun);
        mHandler.removeCallbacks(mUpdateResolutionInfoRun);
        resolutionInfoRunScheduled = true;
        mHandler.postDelayed(mUpdateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY);
    }


    private final Runnable mHideResolutionInfoRun = new Runnable() {
        @Override
        public void run() {
            if (tvResolution != null) {
                tvResolution.setVisibility(View.GONE);
            }
        }
    };

    private final Runnable mUpdateResolutionInfoRun = new Runnable() {
        @Override
        public void run() {
            resolutionInfoRunScheduled = false;
            if (tvResolution == null || mVideoView == null) {
                return;
            }
            if (mVideoView.getCurrentPlayState() != VideoView.STATE_PREPARED
                    && mVideoView.getCurrentPlayState() != VideoView.STATE_BUFFERED
                    && mVideoView.getCurrentPlayState() != VideoView.STATE_PLAYING) {
                retryOrHideResolutionInfo();
                return;
            }
            int[] videoSize = mVideoView.getVideoSize();
            if (videoSize != null && videoSize.length >= 2 && videoSize[0] > 0 && videoSize[1] > 0) {
                updateResolutionText(videoSize[0], videoSize[1]);
                return;
            }
            retryOrHideResolutionInfo();
        }
    };

    private void updateResolutionText(int width, int height) {
        resolutionInfoPending = false;
        tvResolution.setText(width + " x " + height);
        tvResolution.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideResolutionInfoRun);
    }

    private void retryOrHideResolutionInfo() {
        if (resolutionInfoPending && resolutionInfoRetryCount++ < RESOLUTION_INFO_MAX_RETRY) {
            resolutionInfoRunScheduled = true;
            mHandler.postDelayed(mUpdateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY);
        } else {
            tvResolution.setVisibility(View.GONE);
        }
    }

    void showTime() {
        if (Hawk.get(HawkConfig.LIVE_SHOW_TIME, false) && !isTopStatusObstructed()) {
            // ★ 必须先 remove 再 post。showTime() 在每次显示/隐藏顶部状态、设置面板
            // 开关时都会被调用一次，原实现直接 post，于是每调一次就多一条自续期的
            // 时钟链：N 条链 = 每秒 N 次setText，且永远不会随 Activity 销毁而停。
            mHandler.removeCallbacks(mUpdateTimeRun);
            mHandler.post(mUpdateTimeRun);
            tvTime.setVisibility(View.VISIBLE);
        } else {
            mHandler.removeCallbacks(mUpdateTimeRun);
            tvTime.setVisibility(View.GONE);
        }
    }

    /**
     * 时钟/网速每秒各跑一次，SimpleDateFormat 与 Date 都复用，避免每秒 new 两个对象。
     *
     * <p>注意别叫 timeFormat —— 类里已有一个同名字段（EPG 用的 yyyy-MM-dd），
     * 重名会直接编译不过。</p>
     */
    private final SimpleDateFormat clockTimeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final Date clockTimeScratch = new Date();

    private Runnable mUpdateTimeRun = new Runnable() {
        @Override
        public void run() {
            clockTimeScratch.setTime(System.currentTimeMillis());
            tvTime.setText(clockTimeFormat.format(clockTimeScratch));
            mHandler.postDelayed(this, 1000);
        }
    };

    private void showNetSpeed() {
//        tv_right_top_tipnetspeed.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mUpdateNetSpeedRun);
        if (Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false) && !isTopStatusObstructed()) {
            mHandler.post(mUpdateNetSpeedRun);
            tvNetSpeed.setVisibility(View.VISIBLE);
        } else {
            tvNetSpeed.setVisibility(View.GONE);
        }
    }

    private boolean isTopStatusObstructed() {
        return (tvRightSettingLayout != null && tvRightSettingLayout.getVisibility() == View.VISIBLE)
                || (ll_right_top_loading != null && ll_right_top_loading.getVisibility() == View.VISIBLE)
                || (ll_right_top_huikan != null && ll_right_top_huikan.getVisibility() == View.VISIBLE);
    }

    private void updateTopStatusVisibility() {
        if (isTopStatusObstructed()) {
            tvTopStatus.setVisibility(View.GONE);
        } else {
            showTime();
            showNetSpeed();
            tvTopStatus.setVisibility(tvTime.getVisibility() == View.VISIBLE
                    || tvNetSpeed.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        }
    }

    private Runnable mUpdateNetSpeedRun = new Runnable() {
        @Override
        public void run() {
            if (mVideoView == null) return;
            String speed = PlayerHelper.getDisplaySpeedBps(mVideoView.getTcpSpeed(), true);
            tvNetSpeed.setText(speed);
//            tv_right_top_tipnetspeed.setText(speed);
            mHandler.postDelayed(this, 1000);
        }
    };

    private void showPasswordDialog(int groupIndex, int liveChannelIndex) {
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE)
            mHandler.removeCallbacks(mHideChannelListRun);

        LivePasswordDialog dialog = new LivePasswordDialog(this);
        dialog.setOnListener(new LivePasswordDialog.OnListener() {
            @Override
            public void onChange(String password) {
                // 密码弹窗是异步的：等用户输入完，列表可能已经被重载/清空，
                // groupIndex 成了失效下标，直接 get() 就是 IndexOutOfBounds
                if (groupIndex < 0 || groupIndex >= liveChannelGroupList.size()) {
                    Toast.makeText(App.getInstance(), "分组已失效", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (password.equals(liveChannelGroupList.get(groupIndex).getGroupPassword())) {
                    channelGroupPasswordConfirmed.add(groupIndex);
                    loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
                } else {
                    Toast.makeText(App.getInstance(), "密码错误", Toast.LENGTH_SHORT).show();
                }

                if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE)
                    mHandler.postDelayed(mHideChannelListRun, postTimeout);
            }

            @Override
            public void onCancel() {
                if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                    int groupIndex = liveChannelGroupAdapter.getSelectedGroupIndex();
                    liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
                }
            }
        });
        dialog.show();
    }

    private void loadChannelGroupDataAndPlay(int groupIndex, int liveChannelIndex) {
        liveChannelGroupAdapter.setSelectedGroupIndex(groupIndex);
        loadChannelGroupData(groupIndex);

        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex);
            mChannelGroupView.scrollToPosition(groupIndex);
            mLiveChannelView.scrollToPosition(liveChannelIndex);
        }
    }

    private void loadChannelGroupData(int groupIndex) {
        liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
        if (groupIndex == currentChannelGroupIndex) {
            if (currentLiveChannelIndex > -1)
                mLiveChannelView.scrollToPosition(currentLiveChannelIndex);
            liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
        }
        else {
            mLiveChannelView.scrollToPosition(0);
            liveChannelItemAdapter.setSelectedChannelIndex(-1);
        }
    }

    private boolean isNeedInputPassword(int groupIndex) {
        // 下标可能来自：换配置后残留的旧值、密码弹窗异步回调里已失效的旧值、
        // selectChannelGroup 传入的 -1。原实现直接 get(index)，越界即崩。
        if (liveChannelGroupList == null || groupIndex < 0 || groupIndex >= liveChannelGroupList.size()) {
            return false;
        }
        LiveChannelGroup group = liveChannelGroupList.get(groupIndex);
        return group != null
                && !TextUtils.isEmpty(group.getGroupPassword())
                && !isPasswordConfirmed(groupIndex);
    }

    private boolean isPasswordConfirmed(int groupIndex) {
        for (Integer confirmedNum : channelGroupPasswordConfirmed) {
            if (confirmedNum == groupIndex)
                return true;
        }
        return false;
    }

    private ArrayList<LiveChannelItem> getLiveChannels(int groupIndex) {
        // 调用方遍布换台/换源/列表渲染，且大量直接 .size() / .get(index)。
        // 组下标越界、或该组 channels 从未赋值（源解析失败时会构造出空分组）时，
        // 原实现会分别抛 IndexOutOfBoundsException 和 NPE。这里统一兜底成空列表。
        if (liveChannelGroupList == null || groupIndex < 0 || groupIndex >= liveChannelGroupList.size()) {
            return new ArrayList<>();
        }
        if (isNeedInputPassword(groupIndex)) {
            return new ArrayList<>();
        }
        ArrayList<LiveChannelItem> channels = liveChannelGroupList.get(groupIndex).getLiveChannels();
        return channels == null ? new ArrayList<LiveChannelItem>() : channels;
    }

    private Integer[] getFirstChannelByName(String keyword) {
        if (TextUtils.isEmpty(keyword)) return null;
        String upperKeyword = keyword.toUpperCase(Locale.US);
        for (LiveChannelGroup liveChannelGroup : liveChannelGroupList) {
            if (liveChannelGroup == null || isNeedInputPassword(liveChannelGroup.getGroupIndex())) continue;
            ArrayList<LiveChannelItem> groupChannels = liveChannelGroup.getLiveChannels();
            if (groupChannels == null || groupChannels.isEmpty()) continue;
            for (LiveChannelItem item : groupChannels) {
                if (item == null || TextUtils.isEmpty(item.getChannelName())) continue;
                if (item.getChannelName().toUpperCase(Locale.US).contains(upperKeyword)) {
                    return new Integer[]{liveChannelGroup.getGroupIndex(), item.getChannelIndex()};
                }
            }
        }
        return null;
    }

    private Integer[] getNextChannel(int direction) {
        int channelGroupIndex = currentChannelGroupIndex;
        int liveChannelIndex = currentLiveChannelIndex;

        //跨选分组模式下跳过加密频道分组（遥控器上下键换台/超时换源）
        if (direction > 0) {
            liveChannelIndex++;
            if (liveChannelIndex >= getLiveChannels(channelGroupIndex).size()) {
                liveChannelIndex = 0;
                if (Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false)) {
                    channelGroupIndex = nextPlayableGroupIndex(channelGroupIndex, 1);
                }
            }
        } else {
            liveChannelIndex--;
            if (liveChannelIndex < 0) {
                if (Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false)) {
                    channelGroupIndex = nextPlayableGroupIndex(channelGroupIndex, -1);
                }
                liveChannelIndex = Math.max(0, getLiveChannels(channelGroupIndex).size() - 1);
            }
        }

        Integer[] groupChannelIndex = new Integer[2];
        groupChannelIndex[0] = channelGroupIndex;
        groupChannelIndex[1] = liveChannelIndex;

        return groupChannelIndex;
    }

    /**
     * 跨组换台时，从 fromIndex 沿 direction 找下一个「能进且不是当前分组」的分组。
     *
     * <p><b>为什么必须是有界循环：</b>原先这里是
     * {@code do { ... } while (有密码 || == 当前分组)}。
     * 当「总共只有 1 个分组」或「除当前分组外全部需要密码」时，这个条件<b>恒为真</b>
     * —— 主线程直接死循环，表现为遥控器按一下上下键整机 ANR（超时自动跳台也会走到
     * 同一条路径）。所以改成最多绕一圈，绕不到就留在原组从头播。</p>
     */
    private int nextPlayableGroupIndex(int fromIndex, int direction) {
        int size = liveChannelGroupList == null ? 0 : liveChannelGroupList.size();
        if (size == 0) {
            return currentChannelGroupIndex;
        }
        int fallback = -1;
        for (int step = 1; step <= size; step++) {
            int index = ((fromIndex + direction * step) % size + size) % size;
            LiveChannelGroup group = liveChannelGroupList.get(index);
            if (group == null || group.getLiveChannels() == null || group.getLiveChannels().isEmpty()) {
                continue;
            }
            if (isNeedInputPassword(index)) {
                continue;
            }
            if (fallback < 0) {
                fallback = index;
            }
            if (index != currentChannelGroupIndex) {
                return index;
            }
        }
        // 没有别的可播分组（单分组 / 其余全加密 / 其余全空）→ 留在原组从头播
        return fallback >= 0 ? fallback : currentChannelGroupIndex;
    }

    private int getFirstNoPasswordChannelGroup() {
        if (liveChannelGroupList == null) {
            return -1;
        }
        for (LiveChannelGroup liveChannelGroup : liveChannelGroupList) {
            if (liveChannelGroup == null) {
                continue;
            }
            // getGroupPassword() 在解析失败构造出的分组里可能是 null，
            // 原实现直接 .isEmpty() 会 NPE，导致一进直播页就崩
            if (TextUtils.isEmpty(liveChannelGroup.getGroupPassword()))
                return liveChannelGroup.getGroupIndex();
        }
        return -1;
    }

    private boolean isCurrentLiveChannelValid() {
        if (currentLiveChannelItem == null) {
            Toast.makeText(App.getInstance(), "请先选择频道", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    //计算两个时间相差的秒数
    public static long getTime(String startTime, String endTime)  {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        long eTime = 0;
        try {
            eTime = df.parse(endTime).getTime();
        } catch (ParseException e) {
            e.printStackTrace();
        }
        long sTime = 0;
        try {
            sTime = df.parse(startTime).getTime();
        } catch (ParseException e) {
            e.printStackTrace();
        }
        long diff = (eTime - sTime) / 1000;
        return diff;
    }
    private  String durationToString(int duration) {
        if (duration < 0) {
            duration = 0;
        }
        String result = "";
        int dur = duration / 1000;
        int hour=dur/3600;
        int min = (dur / 60) % 60;
        int sec = dur % 60;
        if(hour>0){
            if (min > 9) {
                if (sec > 9) {
                    result =hour+":"+ min + ":" + sec;
                } else {
                    result =hour+":"+ min + ":0" + sec;
                }
            } else {
                if (sec > 9) {
                    result =hour+":"+ "0" + min + ":" + sec;
                } else {
                    result = hour+":"+"0" + min + ":0" + sec;
                }
            }
        }else{
            if (min > 9) {
                if (sec > 9) {
                    result = min + ":" + sec;
                } else {
                    result = min + ":0" + sec;
                }
            } else {
                if (sec > 9) {
                    result ="0" + min + ":" + sec;
                } else {
                    result = "0" + min + ":0" + sec;
                }
            }
        }
        return result;
    }
    public void showProgressBars( boolean show){

        sBar.requestFocus();
        if(show){
            ll_right_top_huikan.setVisibility(View.VISIBLE);
            updateTopStatusVisibility();
            backcontroller.setVisibility(View.VISIBLE);
            ll_epg.setVisibility(View.GONE);
        }else{
            backcontroller.setVisibility(View.GONE);
            ll_right_top_huikan.setVisibility(View.GONE);
            updateTopStatusVisibility();
            if(!tip_epg1.getText().equals("暂无信息")){
                ll_epg.setVisibility(View.VISIBLE);
            }
        }



        iv_play.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View arg0) {
                mVideoView.start();
                iv_play.setVisibility(View.INVISIBLE);
                countDownTimer.start();
                iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
            }
        });

        iv_playpause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                if(mVideoView.isPlaying()){
                    mVideoView.pause();
                    countDownTimer.cancel();
                    iv_play.setVisibility(View.VISIBLE);
                    iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.icon_play));
                }else{
                    mVideoView.start();
                    iv_play.setVisibility(View.INVISIBLE);
                    countDownTimer.start();
                    iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
                }
            }
        });
        sBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {


            @Override
            public void onStopTrackingTouch(SeekBar arg0) {

            }

            @Override
            public void onStartTrackingTouch(SeekBar arg0) {

            }

            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromuser) {
                if(fromuser){
                    if(countDownTimer!=null){
                        mVideoView.seekTo(progress);
                        countDownTimer.cancel();
                        countDownTimer.start();
                    }
                }
            }
        });
        sBar.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View arg0, int keycode, KeyEvent event) {
                if(event.getAction()==KeyEvent.ACTION_DOWN){
                    if(keycode==KeyEvent.KEYCODE_DPAD_CENTER||keycode==KeyEvent.KEYCODE_ENTER){
                        if(mVideoView.isPlaying()){
                            mVideoView.pause();
                            countDownTimer.cancel();
                            iv_play.setVisibility(View.VISIBLE);
                            iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.icon_play));
                        }else{
                            mVideoView.start();
                            iv_play.setVisibility(View.INVISIBLE);
                            countDownTimer.start();
                            iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
                        }
                    }
                }
                return false;
            }
        });
        if(mVideoView.isPlaying()){
            iv_play.setVisibility(View.INVISIBLE);
            iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.vod_pause));
        }else{
            iv_play.setVisibility(View.VISIBLE);
            iv_playpause.setBackground(ContextCompat.getDrawable(LivePlayActivity.context, R.drawable.icon_play));
        }
        if(countDownTimer3==null){
            countDownTimer3 = new CountDownTimer(postTimeout, 1000) {

                @Override
                public void onTick(long arg0) {

                    if(mVideoView != null){
                        sBar.setProgress(safeTimeMs(mVideoView.getCurrentPosition()));
                        tv_currentpos.setText(durationToString(safeTimeMs(mVideoView.getCurrentPosition())));
                    }

                }

                @Override
                public void onFinish() {
                    if(backcontroller.getVisibility() == View.VISIBLE){
                        backcontroller.setVisibility(View.GONE);
                    }
                }
            };
        }else{
            countDownTimer3.cancel();
        }
        countDownTimer3.start();
    }

    /**
     * 当播放列表为空或加载失败时，设置一个默认的播放列表，保证播放界面不会崩溃
     */
    private void clearLiveChannelList() {
        clearLiveChannelList(true);
    }

    private void clearLiveChannelList(boolean releasePlayer) {
        refreshingLiveChannelList = false;
        pendingLiveRefreshChannelName = null;
        pendingLiveRefreshSourceIndex = -1;
        currentLiveChannelItem = null;
        currentLiveChannelIndex = -1;
        currentLiveLookBackIndex = -1;
        currentLiveChangeSourceTimes = 0;
        // channel_Name / hsEpg 都是静态的，跨 Activity 实例存活。这里只清了实例字段，
        // 于是「切源失败 / 重载配置 → 列表清空」后：在途的 EPG 回调仍能用残留的
        // channel_Name 拼出 key 并命中，一路走到 showEpg() → currentLiveChannelItem
        // 已是 null → NPE 崩溃；hsEpg 里的旧节目单也会串到下一次。两者一起清。
        channel_Name = null;
        hsEpg.clear();
        liveChannelGroupList.clear();
        ApiConfig.get().getChannelGroupList().clear();
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
        mHandler.removeCallbacks(mLoadEpgRun);
        hideSwitchChannelSnapshot();
        if (releasePlayer && mVideoView != null) mVideoView.release();
        showSuccess();
        if (liveChannelGroupAdapter != null) {
            liveChannelGroupAdapter.clearGroupState();
            liveChannelGroupAdapter.setNewData(new ArrayList<LiveChannelGroup>());
        }
        if (liveChannelItemAdapter != null) {
            liveChannelItemAdapter.setFocusedChannelIndex(-1);
            liveChannelItemAdapter.setSelectedChannelIndex(-1);
            liveChannelItemAdapter.setNewData(new ArrayList<LiveChannelItem>());
        }
        if (tvLeftChannelListLayout != null) tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        if (tvRightSettingLayout != null) tvRightSettingLayout.setVisibility(View.INVISIBLE);
    }

    private void setEmptyLiveChannelList() {
        setEmptyLiveChannelList(true);
    }

    private void setEmptyLiveChannelList(boolean releasePlayer) {
        clearLiveChannelList(releasePlayer);
//        Toast.makeText(App.getInstance(), "源异常,请切换到其他源", Toast.LENGTH_SHORT).show();
    }
}
