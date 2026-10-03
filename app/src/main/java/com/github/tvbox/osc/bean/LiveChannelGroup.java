package com.github.tvbox.osc.bean;

import java.util.ArrayList;

public class LiveChannelGroup {
    /**
     * groupIndex : 分组索引号
     * groupName : 分组名称
     * password : 分组密码
     */
    private int groupIndex;
    private String groupName;
    private String groupPassword;
    private ArrayList<LiveChannelItem> liveChannelItems;

    public int getGroupIndex() {
        return groupIndex;
    }

    public void setGroupIndex(int groupIndex) {
        this.groupIndex = groupIndex;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public ArrayList<LiveChannelItem> getLiveChannels() {
        return liveChannelItems;
    }

    public void setLiveChannels(ArrayList<LiveChannelItem> liveChannelItems) {
        this.liveChannelItems = liveChannelItems;
    }

    public String getGroupPassword() {
        // 直播源解析失败/字段缺失时这个字段会是 null，而调用方遍布各处且大量
        // 直接 .isEmpty() / .equals()，在 getter 里统一兜成空串最省事且不会漏
        return groupPassword == null ? "" : groupPassword;
    }

    public void setGroupPassword(String groupPassword) {
        this.groupPassword = groupPassword;
    }
}
