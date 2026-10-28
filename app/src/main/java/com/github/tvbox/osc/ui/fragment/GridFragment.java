package com.github.tvbox.osc.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.BounceInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.adapter.GridFilterKVAdapter;
import com.github.tvbox.osc.ui.dialog.GridFilterDialog;
import com.github.tvbox.osc.ui.tv.widget.LoadMoreView;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class GridFragment extends BaseLazyFragment {
    private MovieSort.SortData sortData = null;
    private TvRecyclerView mGridView;
    private SourceViewModel sourceViewModel;
    private GridFilterDialog gridFilterDialog;
    private GridAdapter gridAdapter;
    private int page = 1;
    private int maxPage = 1;
    private boolean isLoad = false;
    private boolean isRequesting = false;
    private boolean hasActionItems = false;
    private boolean isTop = true;
    private View focusedView = null;
    private float pullRefreshStartX;
    private float pullRefreshStartY;
    private boolean pullRefreshStartAtTop = false;
    private boolean pullRefreshReady = false;
    private int pullRefreshThreshold = 0;
    private View loadSirView = null;

    private static class GridInfo{
        public String sortID="";
        public TvRecyclerView mGridView;
        public GridAdapter gridAdapter;
        public int page = 1;
        public int maxPage = 1;
        public boolean isLoad = false;
        public boolean hasActionItems = false;
        public View focusedView= null;
    }
    Stack<GridInfo> mGrids = new Stack<GridInfo>(); //ui栈

    public static GridFragment newInstance(MovieSort.SortData sortData) {
        return new GridFragment().setArguments(sortData);
    }

    public GridFragment setArguments(MovieSort.SortData sortData) {
        this.sortData = sortData;
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_grid;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TvRecyclerView gridView = view.findViewById(R.id.mGridView);
        if (gridView != null && gridView.getLayoutManager() == null) {
            gridView.setLayoutManager(new V7LinearLayoutManager(mContext, 1, false));
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
    }

    private void changeView(String id,Boolean isFolder){
        if(isFolder){
            this.sortData.flag =style==null?"1":"2"; // 修改sortData.flag
        }else {
            this.sortData.flag ="2"; // 修改sortData.flag
        }
        initView();
        this.sortData.id =id; // 修改sortData.id为新的ID
        initViewModel();
        initData();
    }
    public boolean isFolederMode(){ return (getUITag() =='1'); }
    // 获取当前页面UI的显示模式 ‘0’ 正常模式 '1' 文件夹模式 '2' 显示缩略图的文件夹模式
    public char getUITag(){
        return (sortData == null || sortData.flag == null || sortData.flag.length() ==0 || style!=null) ?  '0' : sortData.flag.charAt(0);
    }
    // 是否允许聚合搜索 sortData.flag的第二个字符为‘1’时允许聚搜
    public boolean enableFastSearch(){  return sortData.flag == null || sortData.flag.length() < 2 || (sortData.flag.charAt(1) == '1'); }
    // 保存当前页面
    private void saveCurrentView(){
        if(this.mGridView == null) return;
        GridInfo info = new GridInfo();
        info.sortID = this.sortData.id;
        info.mGridView = this.mGridView;
        info.gridAdapter = this.gridAdapter;
        info.page = this.page;
        info.maxPage = this.maxPage;
        info.isLoad = this.isLoad;
        info.hasActionItems = this.hasActionItems;
        info.focusedView = this.focusedView;
        this.mGrids.push(info);
    }
    // 丢弃当前页面，将页面还原成上一个保存的页面
    public boolean restoreView(){
        if(mGrids.empty()) return false;
        this.showSuccess();
        ((ViewGroup) mGridView.getParent()).removeView(this.mGridView); // 重父窗口移除当前控件
        GridInfo info = mGrids.pop();// 还原上次保存的控件
        this.sortData.id = info.sortID;
        this.mGridView = info.mGridView;
        this.gridAdapter = info.gridAdapter;
        this.page = info.page;
        this.maxPage = info.maxPage;
        this.isLoad = info.isLoad;
        this.hasActionItems = info.hasActionItems;
        this.focusedView = info.focusedView;
        this.mGridView.setVisibility(View.VISIBLE);
//        if(this.focusedView != null){ this.focusedView.requestFocus(); }
        if(mGridView != null) mGridView.requestFocus();
        return true;
    }

    private ImgUtil.Style style;
    // 更改当前页面
    private void createView() {
        this.saveCurrentView(); // 保存当前页面
        if(mGridView == null){ // 从layout中拿view
            mGridView = findViewById(R.id.mGridView);
        }else{ // 复制当前view
            TvRecyclerView v3 = new TvRecyclerView(this.mContext);
            v3.setSpacingWithMargins(10,10);
            v3.setLayoutParams(mGridView.getLayoutParams());
            v3.setPadding(mGridView.getPaddingLeft(), mGridView.getPaddingTop(), mGridView.getPaddingRight(), mGridView.getPaddingBottom());
            v3.setClipToPadding(mGridView.getClipToPadding());
            ((ViewGroup) mGridView.getParent()).addView(v3);
            mGridView.setVisibility(View.GONE);
            mGridView = v3;
            mGridView.setVisibility(View.VISIBLE);
        }
        mGridView.setHasFixedSize(true);
        style=ImgUtil.initStyle();
        gridAdapter = new GridAdapter(isFolederMode(), style);
        this.page =1;
        this.maxPage =1;
        this.isLoad = false;
    }

    private void initView() {
        this.createView();
        if(isFolederMode()){
            mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        }else{
            int spanCount = isBaseOnWidth() ? 5 : 6;
            if (style != null) {
                spanCount = ImgUtil.spanCountByStyle(style, spanCount);
            }
            if (spanCount == 1) {
                mGridView.setLayoutManager(new V7LinearLayoutManager(mContext, spanCount, false));
            } else {
                mGridView.setLayoutManager(new V7GridLayoutManager(mContext, spanCount));
            }
        }
        mGridView.setAdapter(gridAdapter);

        gridAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                gridAdapter.setEnableLoadMore(true);
                sourceViewModel.getList(sortData, page);
            }
        }, mGridView);
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
        mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_UP) {
                }
                return false;
            }
        });
        gridAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    if (video.action != null) {
                        sourceViewModel.action(video.sourceKey, video.action);
                        return;
                    }
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    if( video.tag !=null && (video.tag.equals("folder") || video.tag.equals("cover"))){
                        focusedView = view;
                        if(("12".indexOf(getUITag()) != -1)){
                            changeView(video.id,video.tag.equals("folder"));
                        }else {
                            changeView(video.id,false);
                        }
                    }
                    else{
                        if (video.id != null && video.id.startsWith("msearch:")) {
                            if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true) && enableFastSearch()) {
                                jumpActivity(FastSearchActivity.class, bundle);
                            } else {
                                jumpActivity(SearchActivity.class, bundle);
                            }
                        } else {
                            bundle.putString("picture", video.pic);
                            jumpActivity(DetailActivity.class, bundle);
                        }
                    }

                }
            }
        });
        gridAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    if (video.id != null && video.id.startsWith("msearch:")) {
                        bundle.putString("picture", video.pic);
                        jumpActivity(DetailActivity.class, bundle);
                    } else {
                        jumpActivity(FastSearchActivity.class, bundle);
                    }
                }
                return true;
            }
        });
        gridAdapter.setLoadMoreView(new LoadMoreView());
        setLoadSir2(mGridView);
        initPullRefresh();
    }

    private void initPullRefresh() {
        pullRefreshThreshold = ViewConfiguration.get(mContext).getScaledTouchSlop() * 6;
        mGridView.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent event) {
                return handlePullRefreshTouch(rv, event);
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent event) {
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            }
        });
        loadSirView = (View) mGridView.getParent();
        if (loadSirView != null) {
            loadSirView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return handlePullRefreshTouch(v, event);
                }
            });
        }
    }

    private void bindPullRefreshTouch(View view) {
        if (view == null || view == mGridView) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handlePullRefreshTouch(v, event);
            }
        });
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                bindPullRefreshTouch(group.getChildAt(i));
            }
        }
    }

    private boolean handlePullRefreshTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pullRefreshStartX = event.getX();
                pullRefreshStartY = event.getY();
                pullRefreshStartAtTop = !view.canScrollVertically(-1);
                pullRefreshReady = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float diffX = Math.abs(event.getX() - pullRefreshStartX);
                float diffY = event.getY() - pullRefreshStartY;
                pullRefreshReady = pullRefreshStartAtTop && diffY > pullRefreshThreshold && diffY > diffX;
                break;
            case MotionEvent.ACTION_UP:
                if (pullRefreshReady) {
                    pullRefreshReady = false;
                    forceRefresh();
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                pullRefreshReady = false;
                break;
        }
        return false;
    }

    @Override
    protected void showEmpty() {
        super.showEmpty();
        bindPullRefreshTouch(loadSirView);
    }

    private void initViewModel() {
        if(sourceViewModel != null) { return;}
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                isRequesting = false;
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    if (page == 1) {
                        showSuccess();
                        isLoad = true;
                        hasActionItems = hasActionVideo(absXml.movie.videoList);
                        gridAdapter.setNewData(absXml.movie.videoList);
                    } else {
                        hasActionItems = hasActionItems || hasActionVideo(absXml.movie.videoList);
                        gridAdapter.addData(absXml.movie.videoList);
                    }
                    page++;
                    maxPage = absXml.movie.pagecount;
                    if (maxPage>0 && page > maxPage) {
                        gridAdapter.loadMoreEnd();
                        gridAdapter.setEnableLoadMore(false);
                        if(page>2)Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                    }else {
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    }
                } else {
                    if (page == 1) {
                        hasActionItems = false;
                        showEmpty();
                    } else if(page > 2){// 只有一页数据时不提示
                        Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                    }
                    gridAdapter.loadMoreEnd();
                    gridAdapter.setEnableLoadMore(false);
                }
            }
        });
        sourceViewModel.actionResult.observe(this, new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject jsonObject) {
                if (jsonObject == null) return;
                String msg = jsonObject.optString("msg");
                if (!msg.isEmpty()) {
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                    forceRefresh();
                }
            }
        });
    }

    public boolean isLoad() {
        return isLoad || !mGrids.empty(); //如果有缓存页的话也可以认为是加载了数据的
    }

    public boolean shouldReloadOnSelect() {
        return !isRequesting && mGrids.empty() && (hasActionItems || !isLoad);
    }

    private void initData() {
        showLoading();
        isRequesting = true;
        isLoad = false;
        hasActionItems = false;
        scrollTop();
        toggleFilterColor();
        sourceViewModel.getList(sortData, page);
    }

    private boolean hasActionVideo(List<Movie.Video> videos) {
        if (videos == null) return false;
        for (Movie.Video video : videos) {
            if (video != null && video.action != null) return true;
        }
        return false;
    }

    private void toggleFilterColor() {
        if (sortData!=null && sortData.filters != null && !sortData.filters.isEmpty()) {
            int count = sortData.filterSelectCount();
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_FILTER_CHANGE, count));
        }
    }

    public boolean isTop() {
        return isTop;
    }

    public void scrollTop() {
        isTop = true;
        if (mGridView == null) return;
        mGridView.scrollToPosition(0);
    }

    public void showFilter() {
        if (!sortData.filters.isEmpty() && gridFilterDialog == null) {
            gridFilterDialog = new GridFilterDialog(mContext);
            setFilterDialogData();
        }
        if (gridFilterDialog != null)
            gridFilterDialog.show();
    }

    public void setFilterDialogData() {
        Context context = getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        assert context != null;
        // 遍历过滤条件数据
        for (MovieSort.SortFilter filter : sortData.filters) {
            View line = inflater.inflate(R.layout.item_grid_filter, gridFilterDialog.filterRoot, false);
            TextView filterNameTv = line.findViewById(R.id.filterName);
            filterNameTv.setText(filter.name);
            TvRecyclerView gridView = line.findViewById(R.id.mFilterKv);
            gridView.setHasFixedSize(true);
            gridView.setLayoutManager(new V7LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
            final GridFilterKVAdapter adapter = new GridFilterKVAdapter();
            gridView.setAdapter(adapter);
            final String key = filter.key;
            final ArrayList<String> values = new ArrayList<>(filter.values.keySet());
            final ArrayList<String> keys = new ArrayList<>(filter.values.values());
            // ★ 默认档高亮：没选过 = 每行第一个值生效（与 spider 数据语义一致），
            //   弹窗里必须把它显示成选中态，否则用户看不出"热门电影/全部"正在生效。
            //   默认值不写进 filterSelect ⇒ 漏斗图标逻辑（filterSelectCount）不受影响，
            //   默认状态仍是白色，只有偏离默认才转橙。
            int effectiveIdx = 0;
            String currentSelection = sortData.filterSelect.get(key);
            if (currentSelection != null) {
                int idx = keys.indexOf(currentSelection);
                if (idx >= 0) effectiveIdx = idx;
            }
            final int initialIdx = effectiveIdx;
            adapter.setSelectedPosition(initialIdx);
            adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
                @Override
                public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                    String selection = keys.get(position);
                    if (position == 0) {
                        // ★ 第一个值 = 默认档：回归默认 = 从 filterSelect 移除。
                        //   选默认值与"取消筛选"是同一件事，请求参数一致、图标同为白色。
                        sortData.filterSelect.remove(key);
                    } else {
                        String currentSelection = sortData.filterSelect.get(key);
                        if (currentSelection == null || !currentSelection.equals(selection)) {
                            sortData.filterSelect.put(key, selection);
                        } else {
                            // 再点一次已选中的非默认值 = 取消，回归默认档
                            sortData.filterSelect.remove(key);
                        }
                    }
                    // 选中态统一交给适配器渲染（含"回归默认"落回第 0 个）。
                    // ★ onItemClick 的参数类型是 BaseQuickAdapter，会把外层的
                    //   GridFilterKVAdapter adapter 遮蔽掉 —— 必须向下转型，
                    //   否则 CI 的 javac 找不到 setSelectedPosition（r95 首包 CI 实测）。
                    GridFilterKVAdapter kvAdapter = (GridFilterKVAdapter) adapter;
                    kvAdapter.setSelectedPosition(sortData.filterSelect.containsKey(key) ? position : 0);
                    kvAdapter.notifyDataSetChanged();
                    forceRefresh();
                }
            });
            adapter.setNewData(values);
            gridFilterDialog.filterRoot.addView(line);
        }
    }

    public void forceRefresh() {
        if (mGridView == null || gridAdapter == null || sourceViewModel == null) return;
        if (isRequesting) return;
        page = 1;
        initData();
    }
}
