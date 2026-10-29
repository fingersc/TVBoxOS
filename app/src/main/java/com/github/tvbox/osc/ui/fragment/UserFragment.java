package com.github.tvbox.osc.ui.fragment;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.BounceInterpolator;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.ui.activity.CollectActivity;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.AccountActivity;
import com.github.tvbox.osc.ui.activity.LivePlayActivity;
import com.github.tvbox.osc.ui.activity.PushActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.adapter.HomeHotVodAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class UserFragment extends BaseLazyFragment implements View.OnClickListener {
    private LinearLayout tvLive;
    private LinearLayout tvSearch;
    private LinearLayout tvSetting;
    private LinearLayout tvHistory;
    private LinearLayout tvCollect;
    private LinearLayout tvPush;
    public static HomeHotVodAdapter homeHotVodAdapter;
    private List<Movie.Video> homeSourceRec;
    public static TvRecyclerView tvHotList;
    /**
     * ★ v99-r64：记住「本实例创建的这一份」。
     *
     * <p>两个静态引用会在 {@link #onDestroy()} 里置 null 释放（adapter 持 mContext、
     * tvHotList 持整棵 View 树）。但 ViewPager 换页时**新旧两个实例会并存**，
     * 旧实例的 onDestroy 可能晚于新实例的 init 执行 ⇒ 把新实例正在用的引用清掉，
     * 新实例下一次 onFragmentResume 就踩空 NPE（真机 21:18:08 崩在 HomeActivity 切页）。</p>
     *
     * <p>所以释放时必须先确认「静态引用的还是我这一份」，不是我的就不许动。</p>
     */
    private HomeHotVodAdapter myHomeHotVodAdapter;
    private TvRecyclerView myHotList;
    private SourceViewModel sourceViewModel;

    public static UserFragment newInstance() {
        return new UserFragment();
    }

    public static UserFragment newInstance(List<Movie.Video> recVod) {
        return new UserFragment().setArguments(recVod);
    }

    public UserFragment setArguments(List<Movie.Video> recVod) {
        this.homeSourceRec = recVod;
        return this;
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        // ★ v99-r64：视图引用可能为 null，两个来源：
        //   ① 另一个已销毁的实例把静态清了（见 onDestroy 的归属判定）；
        //   ② 本实例的视图刚被重建、init() 还没轮到（init 只跑第一次可见）。
        //   这里就地自愈：能从当前 rootView 取回就取回，取不回说明视图还没好，直接返回。
        if (tvHotList == null) {
            tvHotList = findViewById(R.id.tvHotList);
            if (tvHotList != null) {
                myHotList = tvHotList;
            }
        }
        if (tvHotList == null) {
            LOG.sw("[home] resume skip reason=noHotList");
            return;
        }
        if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) {
            tvHotList.setVisibility(View.VISIBLE);
            tvHotList.setHasFixedSize(true);
            int spanCount = 5;
            if(style!=null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1)spanCount=ImgUtil.spanCountByStyle(style,spanCount);
            tvHotList.setLayoutManager(new V7GridLayoutManager(this.mContext, spanCount));
            int paddingLeft = -tvHotList.mHorizontalSpacingWithMargins / 2 + getResources().getDimensionPixelSize(R.dimen.vs_6);
            int paddingTop = getResources().getDimensionPixelSize(R.dimen.vs_20);
            int paddingRight = -tvHotList.mHorizontalSpacingWithMargins / 2 + getResources().getDimensionPixelSize(R.dimen.vs_6);
            int paddingBottom = getResources().getDimensionPixelSize(R.dimen.vs_20);
            tvHotList.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
        } else {
            tvHotList.setVisibility(View.VISIBLE);
            tvHotList.setLayoutManager(new V7LinearLayoutManager(this.mContext, V7LinearLayoutManager.HORIZONTAL, false));
            int paddingLeft = -tvHotList.mHorizontalSpacingWithMargins / 2 + getResources().getDimensionPixelSize(R.dimen.vs_6);
            int paddingTop = getResources().getDimensionPixelSize(R.dimen.vs_20);
            int paddingRight = -tvHotList.mHorizontalSpacingWithMargins / 2 + getResources().getDimensionPixelSize(R.dimen.vs_6);
            int paddingBottom = getResources().getDimensionPixelSize(R.dimen.vs_20);
            tvHotList.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
        }
        // ★ v99-rXX：历史行刷新抽成独立方法，供 onFragmentResume 与 RefreshEvent 两条路径复用。
        reloadHistoryRow();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TvRecyclerView hotList = view.findViewById(R.id.tvHotList);
        if (hotList != null && hotList.getLayoutManager() == null) {
            hotList.setLayoutManager(new V7LinearLayoutManager(mContext, V7LinearLayoutManager.HORIZONTAL, false));
        }
    }

    private void jumpSearch(Movie.Video vod) {
        Intent newIntent;
        if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
            newIntent = new Intent(mContext, FastSearchActivity.class);
        } else {
            newIntent = new Intent(mContext, SearchActivity.class);
        }
        newIntent.putExtra("title", vod.name);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mActivity.startActivity(newIntent);
    }

    private ImgUtil.Style style;
    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        tvLive = findViewById(R.id.tvLive);
        tvSearch = findViewById(R.id.tvSearch);
        tvSetting = findViewById(R.id.tvSetting);
        tvCollect = findViewById(R.id.tvFavorite);
        tvHistory = findViewById(R.id.tvHistory);
        tvPush = findViewById(R.id.tvPush);
        tvLive.setOnClickListener(this);
        tvSearch.setOnClickListener(this);
        tvSetting.setOnClickListener(this);
        tvHistory.setOnClickListener(this);
        tvPush.setOnClickListener(this);
        tvCollect.setOnClickListener(this);
        tvLive.setOnFocusChangeListener(focusChangeListener);
        tvSearch.setOnFocusChangeListener(focusChangeListener);
        tvSetting.setOnFocusChangeListener(focusChangeListener);
        tvHistory.setOnFocusChangeListener(focusChangeListener);
        tvPush.setOnFocusChangeListener(focusChangeListener);
        tvCollect.setOnFocusChangeListener(focusChangeListener);
        tvHotList = findViewById(R.id.tvHotList);
        myHotList = tvHotList;      // ★ v99-r64：记下「这份是我的」，onDestroy 据此判定归属
        // ★ v99-rXX：adapter 创建 + 点击/长按监听绑定抽成 ensureHomeAdapter()，
        //   首次可见与「静态引用被释放后自愈重建」共用同一份逻辑。
        ensureHomeAdapter();

        tvHotList.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.05f).scaleY(1.05f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        tvHotList.setAdapter(homeHotVodAdapter);

        initHomeHotVod(homeHotVodAdapter);
        sourceViewModel.actionResult.observe(this, new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject jsonObject) {
                if (jsonObject == null) return;
                String msg = jsonObject.optString("msg");
                if (!msg.isEmpty()) Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * ★ v99-rXX：确保首页 adapter 存在并绑定到 tvHotList（幂等）。
     *
     * <p>首次可见由 {@link #init()} 调用；加固 B：静态 adapter 可能被兄弟实例的 onDestroy
     * 释放后不再重建（init 只跑第一次可见）⇒ {@link #reloadHistoryRow()} 也会调用本方法
     * 重建，避免「被清后永久 noAdapter」导致首页历史行再也不刷新。</p>
     */
    private void ensureHomeAdapter() {
        if (homeHotVodAdapter == null) {
            if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && homeSourceRec != null) {
                style = ImgUtil.initStyle();
            }
            String tvRate = "";
            if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 0) {
                tvRate = "豆瓣热播";
            } else if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
                tvRate = homeSourceRec != null ? "站点推荐" : "豆瓣热播";
            }
            homeHotVodAdapter = new HomeHotVodAdapter(style, tvRate);
            myHomeHotVodAdapter = homeHotVodAdapter;   // ★ v99-r64：同上，归属标记
            installHomeAdapterListeners();
            LOG.sw("[home] adapter created");
        }
        if (tvHotList != null && tvHotList.getAdapter() != homeHotVodAdapter) {
            tvHotList.setAdapter(homeHotVodAdapter);
        }
    }

    /** ★ v99-rXX：给当前 homeHotVodAdapter 绑定点击/长按监听（adapter 为 null 时直接跳过）。 */
    private void installHomeAdapterListeners() {
        if (homeHotVodAdapter == null) return;
        homeHotVodAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));

                if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && homeSourceRec != null && vod.action != null) {
                    sourceViewModel.action(vod.sourceKey, vod.action);
                    return;
                }

                if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2) && HawkConfig.hotVodDelete) {
                    homeHotVodAdapter.remove(position);
                    RoomDataManger.deleteVodRecord(vod.sourceKey, vod.id);
                    // ★ v99-r69：删历史 = 删这部片名下所有候选池桶（历史合并、池分桶，数量不对称）
                    Toast.makeText(mContext, "已删除当前记录", Toast.LENGTH_SHORT).show();
                } else if (vod.id != null && vod.id.startsWith("msearch:")) {
                    jumpSearch(vod);
                } else {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", vod.id);
                    bundle.putString("sourceKey", vod.sourceKey);
                    bundle.putString("title", vod.name);
                    bundle.putString("picture", vod.pic);
                    jumpActivity(DetailActivity.class, bundle);
                }
            }
        });

        homeHotVodAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty()) return false;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                // Additional Check if : Home Rec 0=豆瓣, 1=推荐, 2=历史
                assert vod != null;
                if (vod.id != null && vod.id.startsWith("msearch:")) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", vod.id);
                    bundle.putString("sourceKey", vod.sourceKey);
                    bundle.putString("title", vod.name);
                    bundle.putString("picture", vod.pic);
                    jumpActivity(DetailActivity.class, bundle);
                } else if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2)) {
                    HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
                    homeHotVodAdapter.notifyDataSetChanged();
                } else {
                    Bundle bundle = new Bundle();
                    bundle.putString("title", vod.name);
                    jumpActivity(FastSearchActivity.class, bundle);
                }
                return true;
            }
        });
    }

    /**
     * ★ v99-rXX：刷新首页「最近观看」行（仅 HOME_REC==2 历史模式生效）。
     *
     * <p>首页历史行原先只有 {@link #onFragmentResume()} 一条刷新路径，而它依赖
     * {@code BaseLazyFragment.dispatchUserVisibleHint()} —— 该函数在
     * {@code currentVisibleState == isVisible} 时直接短路返回，从播放返回、尤其停留在同一页时
     * 可能不触发重填 ⇒ 首页行保持旧数据。现在本方法同时被 onFragmentResume 和
     * {@link #onHistoryRefresh(RefreshEvent)}（insertVod 写库那一刻就会 post
     * RefreshEvent.TYPE_HISTORY_REFRESH）调用，保证「写完即更新」。</p>
     *
     * <p>加固 B：进入时先 {@link #ensureHomeAdapter()}，若 adapter 曾被抓释放则重建并重绑。</p>
     */
    private void reloadHistoryRow() {
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) != 2) {
            return;
        }
        ensureHomeAdapter();   // 加固 B：adapter 被释放时重建 + 重新绑定/装监听
        // ★ v99-r26：上限交给「历史记录条数」档位决定（15/30/50），不再写死 20 ——
        //   否则把档位调到 30/50 时首页永远只显示 20 部，档位等于白设。
        //   getAllVodRecord 内部会做 min(limit, hisNum)，传 MAX_VALUE 即"按档位"。
        List<VodInfo> allVodRecord = RoomDataManger.getAllVodRecord(Integer.MAX_VALUE);
        List<Movie.Video> vodList = new ArrayList<>();
        for (VodInfo vodInfo : allVodRecord) {
            Movie.Video vod = new Movie.Video();
            vod.id = vodInfo.id;
            vod.sourceKey = vodInfo.sourceKey;
            vod.name = vodInfo.name;
            vod.pic = vodInfo.pic;
            if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty())
                vod.note = "上次看到" + vodInfo.playNote;
            vodList.add(vod);
        }
        if (homeHotVodAdapter != null) {
            homeHotVodAdapter.setNewData(vodList);
        } else {
            LOG.sw("[home] resume skip reason=noAdapter");
        }
    }

    private void initHomeHotVod(HomeHotVodAdapter adapter) {
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
            if (homeSourceRec != null) {
                adapter.setNewData(homeSourceRec);
                return;
            }
        } else if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2) {
            return;
        }
        setDouBanData(adapter);
    }

    private void setDouBanData(HomeHotVodAdapter adapter) {
        try {
            Calendar cal = Calendar.getInstance();
            int year = cal.get(Calendar.YEAR);
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DATE);
            String today = String.format("%d%d%d", year, month, day);
            String requestDay = Hawk.get("home_hot_day", "");
            if (requestDay.equals(today)) {
                String json = Hawk.get("home_hot", "");
                if (!json.isEmpty()) {
                    ArrayList<Movie.Video> hotMovies = loadHots(json);
                    if (hotMovies != null && hotMovies.size() > 0) {
                        adapter.setNewData(hotMovies);
                        return;
                    }
                }
            }
            String doubanUrl = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=" + year + "," + year;
            OkGo.<String>get(doubanUrl)
                    .headers("User-Agent", UA.randomOne())
                    .execute(new AbsCallback<String>() {
                        @Override
                        public void onSuccess(Response<String> response) {
                            String netJson = response.body();
                            Hawk.put("home_hot_day", today);
                            Hawk.put("home_hot", netJson);
                            mActivity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    adapter.setNewData(loadHots(netJson));
                                }
                            });
                        }

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            return response.body().string();
                        }
                    });
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private ArrayList<Movie.Video> loadHots(String json) {
        ArrayList<Movie.Video> result = new ArrayList<>();
        try {
            JsonObject infoJson = new Gson().fromJson(json, JsonObject.class);
            JsonArray array = infoJson.getAsJsonArray("data");
            int limit = Math.min(array.size(), 25);
            for (int i = 0; i < limit; i++) {  // 改用索引循环
                JsonElement ele = array.get(i);
                JsonObject obj = ele.getAsJsonObject();
                Movie.Video vod = new Movie.Video();
                vod.name = obj.get("title").getAsString();
                vod.note = obj.get("rate").getAsString();
                if (!vod.note.isEmpty()) vod.note += " 分";
                vod.pic = obj.get("cover").getAsString()
                        + "@User-Agent=" + UA.randomOne()
                        + "@Referer=https://www.douban.com/";

                result.add(vod);
            }
        } catch (Throwable th) {

        }
        return result;
    }

    private View.OnFocusChangeListener focusChangeListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus)
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            else
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
        }
    };

    @Override
    public void onClick(View v) {
    	
    	// takagen99: Remove Delete Mode
        HawkConfig.hotVodDelete = false;
    
        FastClickCheckUtil.check(v);
        if (v.getId() == R.id.tvLive) {
            jumpActivity(LivePlayActivity.class);
        } else if (v.getId() == R.id.tvSearch) {
            jumpActivity(SearchActivity.class);
        } else if (v.getId() == R.id.tvSetting) {
            jumpActivity(SettingActivity.class);
        } else if (v.getId() == R.id.tvHistory) {
            // v73：首页「历史」改为「账号」，历史入口收进账号页
            jumpActivity(AccountActivity.class);
        } else if (v.getId() == R.id.tvPush) {
            jumpActivity(PushActivity.class);
        } else if (v.getId() == R.id.tvFavorite) {
            jumpActivity(CollectActivity.class);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_CONNECTION) {
        }
    }

    /**
     * ★ v99-rXX：订阅历史刷新事件。{@code DetailActivity.insertVod(...)} 每次写入历史后都会
     * post {@code RefreshEvent.TYPE_HISTORY_REFRESH}（该事件原本只被 HistoryActivity / CollectActivity
     * 消费，首页历史行被漏掉）。这里补上，写库那一刻就把首页「最近观看」行刷新好，
     * 不再依赖返回首页时那套容易被跳过的可见性分发。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onHistoryRefresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH) {
            reloadHistoryRow();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        // ★ homeHotVodAdapter 持 mContext；tvHotList 更是直接持住 RecyclerView 整棵 View 树
        //   （连同它上面正在显示的十几张海报 Drawable）。二者都是静态字段，原来从不置 null
        //   ⇒ 首页 Fragment 一重建，旧的就永久滞留。置 null 释放；外部引用（HomeActivity）
        //   已全部加判空。
        //
        // ★★ v99-r64：但**不能无条件清**。ViewPager 换页时新旧两个实例并存，
        //   旧实例的 onDestroy 可能晚于新实例的 init ⇒ 会把新实例正在用的引用清掉，
        //   新实例下一次 onFragmentResume 就 NPE（真机 21:18:08 崩在 HomeActivity 切页：
        //   setUserVisibleHint → dispatchUserVisibleHint → tvHotList.setVisibility(null)）。
        //   所以只在「静态引用的确实是我这一份」时才释放；不是我的，说明已被新实例接管，不许动。
        if (myHotList != null && tvHotList == myHotList) {
            tvHotList = null;
        }
        if (myHomeHotVodAdapter != null && homeHotVodAdapter == myHomeHotVodAdapter) {
            homeHotVodAdapter = null;
        }
        myHotList = null;
        myHomeHotVodAdapter = null;
    }
}
