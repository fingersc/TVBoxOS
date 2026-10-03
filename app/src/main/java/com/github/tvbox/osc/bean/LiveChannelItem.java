package com.github.tvbox.osc.bean;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.Map;

/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LiveChannelItem {
    /**
     * channelIndex : 频道索引号
     * channelNum : 频道名称
     * channelSourceNames : 频道源名称
     * channelUrls : 频道源地址
     * sourceIndex : 频道源索引
     * sourceNum : 频道源总数
     */
    private int channelIndex;
    private int channelNum;
    private String channelName;
    private String channelLogo;
    private String channelEpg;
    private String channelUa;
    private String channelClick;
    private String channelFormat;
    private String channelOrigin;
    private String channelReferer;
    private String channelTvgId;
    private String channelTvgName;
    private JsonObject channelCatchup;
    private Map<String, String> channelHeader;
    private Integer channelParse;
    private ArrayList<String> channelSourceNames;
    private ArrayList<String> channelUrls;
    public int sourceIndex = 0;
    public int sourceNum = 0;
    public boolean include_back = false;

    public void setinclude_back(boolean include_back) {
        this.include_back = include_back;
    }

    public boolean getinclude_back() {
        return include_back;
    }

    public void setChannelIndex(int channelIndex) {
        this.channelIndex = channelIndex;
    }

    public int getChannelIndex() {
        return channelIndex;
    }

    public void setChannelNum(int channelNum) {
        this.channelNum = channelNum;
    }

    public int getChannelNum() {
        return channelNum;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelLogo(String channelLogo) {
        this.channelLogo = channelLogo;
    }

    public String getChannelLogo() {
        return channelLogo == null ? "" : channelLogo;
    }

    public void setChannelEpg(String channelEpg) {
        this.channelEpg = channelEpg;
    }

    public String getChannelEpg() {
        return channelEpg == null ? "" : channelEpg;
    }

    public void setChannelUa(String channelUa) {
        this.channelUa = channelUa;
    }

    public String getChannelUa() {
        return channelUa == null ? "" : channelUa;
    }

    public void setChannelClick(String channelClick) {
        this.channelClick = channelClick;
    }

    public String getChannelClick() {
        return channelClick == null ? "" : channelClick;
    }

    public void setChannelFormat(String channelFormat) {
        this.channelFormat = channelFormat;
    }

    public String getChannelFormat() {
        return channelFormat == null ? "" : channelFormat;
    }

    public void setChannelOrigin(String channelOrigin) {
        this.channelOrigin = channelOrigin;
    }

    public String getChannelOrigin() {
        return channelOrigin == null ? "" : channelOrigin;
    }

    public void setChannelReferer(String channelReferer) {
        this.channelReferer = channelReferer;
    }

    public String getChannelReferer() {
        return channelReferer == null ? "" : channelReferer;
    }

    public void setChannelTvgId(String channelTvgId) {
        this.channelTvgId = channelTvgId;
    }

    public String getChannelTvgId() {
        return channelTvgId == null ? "" : channelTvgId;
    }

    public void setChannelTvgName(String channelTvgName) {
        this.channelTvgName = channelTvgName;
    }

    public String getChannelTvgName() {
        return channelTvgName == null ? "" : channelTvgName;
    }

    public void setChannelCatchup(JsonObject channelCatchup) {
        this.channelCatchup = channelCatchup;
    }

    public JsonObject getChannelCatchup() {
        return channelCatchup == null ? new JsonObject() : channelCatchup;
    }

    public boolean hasCatchup() {
        return channelCatchup != null && channelCatchup.entrySet().size() > 0;
    }

    public void setChannelHeader(Map<String, String> channelHeader) {
        this.channelHeader = channelHeader;
    }

    public Map<String, String> getChannelHeader() {
        return channelHeader == null ? new HashMap<String, String>() : channelHeader;
    }

    public void setChannelParse(Integer channelParse) {
        this.channelParse = channelParse;
    }

    public int getChannelParse() {
        return channelParse == null ? 0 : channelParse.intValue();
    }

    public Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>(getChannelHeader());
        if (!getChannelUa().isEmpty()) headers.put("User-Agent", getChannelUa());
        if (!getChannelOrigin().isEmpty()) headers.put("Origin", getChannelOrigin());
        if (!getChannelReferer().isEmpty()) headers.put("Referer", getChannelReferer());
        return headers;
    }

    public ArrayList<String> getChannelUrls() {
        return channelUrls;
    }

    public void setChannelUrls(ArrayList<String> channelUrls) {
        this.channelUrls = channelUrls == null ? new ArrayList<String>() : channelUrls;
        sourceNum = this.channelUrls.size();
        // ★ 换一组源后必须把下标拨回合法区间。
        //
        // sourceIndex 是「当前选到第几条源」，sourceNum 是「现在有几条源」。直播源
        // 配置可以在 Activity 活着时被整体换掉（切换直播接口、重载配置），此时
        // 旧的 sourceIndex 可能已经 >= 新的 sourceNum。原代码只更新 sourceNum，
        // 于是 getUrl() 立刻 IndexOutOfBounds —— 表现为换完配置一进直播就崩。
        if (sourceNum <= 0) {
            sourceIndex = 0;
        } else if (sourceIndex >= sourceNum || sourceIndex < 0) {
            sourceIndex = 0;
        }
    }

    public void preSource() {
        // 只有一条源（或压根没有）时没有可切换的余地，保持 0，避免取到 -1
        if (sourceNum <= 1) {
            sourceIndex = 0;
            return;
        }
        sourceIndex--;
        if (sourceIndex < 0) sourceIndex = sourceNum - 1;
    }

    public void nextSource() {
        if (sourceNum <= 1) {
            sourceIndex = 0;
            return;
        }
        sourceIndex++;
        if (sourceIndex == sourceNum) sourceIndex = 0;
    }

    public void setSourceIndex(int sourceIndex) {
        // 调用方（换源、恢复上次源）给的下标可能越界，这里统一钳位
        if (sourceNum <= 0) {
            this.sourceIndex = 0;
        } else if (sourceIndex < 0 || sourceIndex >= sourceNum) {
            this.sourceIndex = 0;
        } else {
            this.sourceIndex = sourceIndex;
        }
    }

    public int getSourceIndex() {
        return sourceIndex;
    }

    public String getUrl() {
        // 直播源随时可能被整体替换，这里是最容易被越界打到的热点，兜底返回空串，
        // 让上层的「地址为空 → 换下一条源」逻辑接管，而不是直接崩。
        if (channelUrls == null || channelUrls.isEmpty()) {
            return "";
        }
        if (sourceIndex < 0 || sourceIndex >= channelUrls.size()) {
            return "";
        }
        String url = channelUrls.get(sourceIndex);
        return url == null ? "" : url;
    }

    public int getSourceNum() {
        return sourceNum;
    }

    public ArrayList<String> getChannelSourceNames() {
        return channelSourceNames;
    }

    public void setChannelSourceNames(ArrayList<String> channelSourceNames) {
        this.channelSourceNames = channelSourceNames;
    }

    public String getSourceName() {
        // 源名数组与源地址数组长度常常不一致（m3u 里只有部分条目带源名），
        // 原来按下标硬取会 IndexOutOfBounds，崩在换源提示上。
        if (channelSourceNames == null || channelSourceNames.isEmpty()) {
            return "";
        }
        if (sourceIndex < 0 || sourceIndex >= channelSourceNames.size()) {
            return "";
        }
        String name = channelSourceNames.get(sourceIndex);
        return name == null ? "" : name;
    }

    public boolean isEmptyCatchup() {
        return channelCatchup == null || channelCatchup.entrySet().size() == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LiveChannelItem that = (LiveChannelItem) o;
        return Objects.equals(channelName, that.channelName)
                && Objects.equals(getUrl(), that.getUrl());
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelName, getUrl());
    }
}