package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.view.View;
import android.view.animation.BounceInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.sync.HistorySyncManager;
import com.github.tvbox.osc.ui.adapter.HistoryAdapter;
import com.github.tvbox.osc.ui.dialog.ConfirmClearDialog;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
public class HistoryActivity extends BaseActivity {
    private ImageView tvDelete;
    private ImageView tvClear;
    private TextView tvDelTip;
    private TvRecyclerView mGridView;
    public static HistoryAdapter historyAdapter;
    private boolean delMode = false;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_history;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        initView();
        initData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v75：打开历史页先拉一次云端 —— 别的设备刚看完，这里立刻可见（自带 20 秒节流）
        HistorySyncManager.pullNow(new HistorySyncManager.Callback() {
            @Override
            public void onDone(boolean ok, String message) {
                if (ok) {
                    initData();
                }
            }
        });
    }

    private void toggleDelMode() {
    	HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
        historyAdapter.notifyDataSetChanged();
        delMode = !delMode;
        tvDelTip.setVisibility(delMode ? View.VISIBLE : View.GONE);        
    }

    private void initView() {
        EventBus.getDefault().register(this);
        tvDelete = findViewById(R.id.tvDelete);
        tvClear = findViewById(R.id.tvClear);
        tvDelTip = findViewById(R.id.tvDelTip);
        mGridView = findViewById(R.id.mGridView);
        mGridView.setHasFixedSize(true);
        mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, isBaseOnWidth() ? 5 : 6));
        historyAdapter = new HistoryAdapter();
        mGridView.setAdapter(historyAdapter);
        tvDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleDelMode();
            }
        });
        tvClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfirmClearDialog dialog = new ConfirmClearDialog(mContext, "History");
                dialog.show();
            }
        });
        mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_UP) {
                    tvDelete.setFocusable(true);
                    tvClear.setFocusable(true);
                    tvDelete.requestFocus();
                }
                return false;
            }
        });
        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
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
        historyAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (position == -1) return;
                VodInfo vodInfo = historyAdapter.getData().get(position);

                if (vodInfo != null) {
                    if (delMode) {
                        historyAdapter.remove(position);
                        RoomDataManger.deleteVodRecord(vodInfo.sourceKey, vodInfo);
                        // r121：删除历史时一并清理该剧的「日期↔期号」日期表（随历史记录删除被清理）
                        try { com.github.tvbox.osc.util.EpisodeNameMatcher.clearDateTable(vodInfo.name); } catch (Throwable ignored) { }
                        // ★ v99-r69：历史记录是「按片名合并」的，而候选池是「按片名|年份|导演分桶」的
                        //   （一部同名片可能有 2~3 个桶）。删历史必须把该片名下所有桶一起清掉，
                        //   否则会留下无人认领的池，下次进片又被恢复出来。
                    } else {
                        Bundle bundle = new Bundle();
                        bundle.putString("id", vodInfo.id);
                        bundle.putString("sourceKey", vodInfo.sourceKey);
                        SourceBean sourceBean = ApiConfig.get().getSource(vodInfo.sourceKey);
                        if(sourceBean!=null){
                            bundle.putString("title", vodInfo.name);
                            bundle.putString("picture", vodInfo.pic);
                            jumpActivity(DetailActivity.class, bundle);
                        }else {
                            bundle.putString("title", vodInfo.name);
                            if(Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)){
                                jumpActivity(FastSearchActivity.class, bundle);
                            }else {
                                jumpActivity(SearchActivity.class, bundle);
                            }
                        }
                    }
                }
            }
        });
        historyAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                VodInfo vodInfo = historyAdapter.getData().get(position);
                if (vodInfo != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("title", vodInfo.name);
                    if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                        jumpActivity(FastSearchActivity.class, bundle);
                    } else {
                        jumpActivity(SearchActivity.class, bundle);
                    }
                }
                return true;
            }
        });
    }

    private void initData() {
        List<VodInfo> allVodRecord = RoomDataManger.getAllVodRecord(100);
        List<VodInfo> vodInfoList = new ArrayList<>();
        for (VodInfo vodInfo : allVodRecord) {
            if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty())vodInfo.note = "上次看到" + vodInfo.playNote;
            vodInfoList.add(vodInfo);
        }
        historyAdapter.setNewData(vodInfoList);
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH) {
            initData();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        // ★ 同上（CollectActivity）：静态字段持 Adapter 会间接持住 Activity 与 View 树，
        //   不置 null 就每次进出历史页都白留一份。
        historyAdapter = null;
    }

    @Override
    public void onBackPressed() {
        if (delMode) {
            toggleDelMode();
            return;
        }
        super.onBackPressed();
    }
}
