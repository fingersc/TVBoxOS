package com.github.tvbox.osc.ui.adapter;

import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;

import java.util.ArrayList;

/**
 * 筛选弹窗单行候选值适配器。
 *
 * <p>★ 选中态由适配器自己渲染（convert 里按 {@link #selectedPosition} 加粗+变色）：
 * 默认档（每行第一个值）也要高亮 —— "没选过 = 默认档生效" 必须看得见，
 * 之前只靠点击时改样式，默认态永远是素色，用户看不出数据正按默认值出。
 */
public class GridFilterKVAdapter extends BaseQuickAdapter<String, BaseViewHolder> {
    /** 当前生效值下标；-1 = 无（仍按 0 渲染前的初始态）。调用方负责传入生效下标。 */
    private int selectedPosition = -1;

    public GridFilterKVAdapter() {
        super(R.layout.item_grid_filter_value, new ArrayList<>());
    }

    public void setSelectedPosition(int pos) {
        selectedPosition = pos;
    }

    @Override
    protected void convert(BaseViewHolder helper, String item) {
        helper.setText(R.id.filterValue, item);
        TextView valueTv = helper.getView(R.id.filterValue);
        boolean selected = helper.getAdapterPosition() == selectedPosition;
        valueTv.getPaint().setFakeBoldText(selected);
        valueTv.setTextColor(selected
                ? ContextCompat.getColor(mContext, R.color.color_02F8E1)
                : ContextCompat.getColor(mContext, R.color.color_FFFFFF));
    }
}
