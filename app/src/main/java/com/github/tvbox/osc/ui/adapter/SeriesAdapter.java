package com.github.tvbox.osc.ui.adapter;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */
public class SeriesAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {
    private V7GridLayoutManager mGridLayoutManager;
    public SeriesAdapter(V7GridLayoutManager gridLayoutManager) {
        super(R.layout.item_series, new ArrayList<>());
        this.mGridLayoutManager = gridLayoutManager;
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
        TextView tvSeries = helper.getView(R.id.tvSeries);
        if (item.selected) {
            // 选中集：亮黄 + 加粗。原用 color_02F8E1(亮青) 对比度 8.03:1，
            // 低于未选中态白字的 10.86:1，选中项反而不突出（用户反馈）。
            tvSeries.setTextColor(mContext.getResources().getColor(R.color.color_series_selected));
            tvSeries.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        } else {
            tvSeries.setTextColor(Color.WHITE);
            tvSeries.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        }
        helper.setText(R.id.tvSeries, item.name);

        if (getData().size() == 1 && helper.getLayoutPosition() == 0) {
            helper.itemView.setNextFocusUpId(R.id.mGridViewFlag);
        }

        View mSeriesGroupTv = ((Activity) helper.itemView.getContext()).findViewById(R.id.mSeriesGroupTv);
        if (getData().size()>1 && mSeriesGroupTv != null && mSeriesGroupTv.getVisibility() == View.VISIBLE) {
            int spanCount = mGridLayoutManager.getSpanCount();
            int position = helper.getLayoutPosition();
            if (position < spanCount) {
                helper.itemView.setNextFocusUpId(R.id.mSeriesSortTv);
            }
//            int totalCount = getData().size();
//            int remainder = totalCount % spanCount;
//            int lastRowStart = remainder == 0 ? totalCount - spanCount : totalCount - remainder;
//
//            if (position >= lastRowStart) {
//                helper.itemView.setNextFocusDownId(R.id.tvPlay);
//            }
        }
        View mGridViewQuality = ((Activity) helper.itemView.getContext()).findViewById(R.id.mGridViewQuality);
        if (mGridViewQuality != null && mGridViewQuality.getVisibility() == View.VISIBLE && helper.getLayoutPosition() < mGridLayoutManager.getSpanCount()) {
            helper.itemView.setNextFocusUpId(R.id.mGridViewQuality);
        }
    }
}