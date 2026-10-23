package com.github.tvbox.osc.ui.adapter;

import com.github.tvbox.osc.util.LOG;
import android.text.TextUtils;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    private static final String TAG = "FastSearchAdapter";

    public FastSearchAdapter() {
        super(R.layout.item_search, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        helper.setText(R.id.tvName, item.name);
        helper.setText(R.id.tvSite, ApiConfig.get().getSource(item.sourceKey).getName());
        // ★ v99-r32：备注为空时用「类型 · 年份」补位。
        //   聚合搜索里同一个源可能返回多个同名影片，而卡片上只显示 片名/源名/备注/封面 四项 ——
        //   同源同名时片名与源名都相同，只剩备注和封面可区分。备注常为空（源没给），
        //   这时补上类型与年份，用户就能凭它区分，不必只依赖封面。
        String note = item.note;
        if (TextUtils.isEmpty(note)) {
            StringBuilder sb = new StringBuilder();
            if (!TextUtils.isEmpty(item.type)) {
                sb.append(item.type.trim());
            }
            if (item.year > 0) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(item.year);
            }
            note = sb.toString();
        }
        helper.setVisible(R.id.tvNote, !TextUtils.isEmpty(note));
        helper.setText(R.id.tvNote, TextUtils.isEmpty(note) ? "" : note);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.load(item.pic, ivThumb, AutoSizeUtils.mm2px(mContext, 10), AutoSizeUtils.mm2px(mContext, 214), AutoSizeUtils.mm2px(mContext, 280), item.name);
        } else {
            LOG.d("empty image for item: " + item.name);
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        }
    }
}