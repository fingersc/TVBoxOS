package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelGroup;

import java.util.ArrayList;


/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LiveChannelGroupAdapter extends BaseQuickAdapter<LiveChannelGroup, BaseViewHolder> {
    private int selectedGroupIndex = -1;
    private int focusedGroupIndex = -1;

    public LiveChannelGroupAdapter() {
        super(R.layout.item_live_channel_group, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelGroup item) {
        TextView tvGroupName = holder.getView(R.id.tvChannelGroupName);
        tvGroupName.setText(item.getGroupName());
        tvGroupName.setSelected(true);
        int groupIndex = item.getGroupIndex();
        holder.itemView.setSelected(groupIndex == selectedGroupIndex);
        if (groupIndex == selectedGroupIndex && groupIndex != focusedGroupIndex) {
            tvGroupName.setTextColor(mContext.getResources().getColor(R.color.color_1890FF));
        } else {
            tvGroupName.setTextColor(Color.WHITE);
        }
    }

    public void setSelectedGroupIndex(int selectedGroupIndex) {
        if (selectedGroupIndex == this.selectedGroupIndex) return;
        int preSelectedGroupIndex = this.selectedGroupIndex;
        this.selectedGroupIndex = selectedGroupIndex;
        notifyGroupChanged(preSelectedGroupIndex);
        notifyGroupChanged(this.selectedGroupIndex);
    }

    public int getSelectedGroupIndex() {
        return selectedGroupIndex;
    }

    public void setFocusedGroupIndex(int focusedGroupIndex) {
        // ★ 只刷新了「新的焦点项」，没刷新「刚失去焦点的那一项」。
        //
        // convert() 的配色条件是 {选中 && 不是当前焦点} → 蓝色，否则白色。焦点从 A
        // 移到 B 时：B 变白（正确，notify 了），但 A 应该从白变回蓝（它是选中项），
        // 却没人通知它刷新，于是 A 一直保持「被聚焦时的白色」，看上去就是选中项
        // 莫名其妙不高亮了。这里把前后两个下标都刷一遍。
        int preFocusedGroupIndex = this.focusedGroupIndex;
        this.focusedGroupIndex = focusedGroupIndex;
        notifyGroupChanged(preFocusedGroupIndex);
        if (this.focusedGroupIndex != -1) {
            notifyGroupChanged(this.focusedGroupIndex);
        } else if (this.selectedGroupIndex != -1) {
            notifyGroupChanged(this.selectedGroupIndex);
        }
    }

    public void clearGroupState() {
        selectedGroupIndex = -1;
        focusedGroupIndex = -1;
    }

    private void notifyGroupChanged(int position) {
        if (position >= 0 && position < getItemCount()) {
            notifyItemChanged(position);
        }
    }
}
