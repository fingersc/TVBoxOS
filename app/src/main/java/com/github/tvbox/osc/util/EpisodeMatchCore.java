package com.github.tvbox.osc.util;

import android.text.TextUtils;

import java.util.List;

/**
 * ★ r145：切源「同集定位」的唯一核心实现（DA/PF 共用）。
 *
 * <p>历史问题：DetailActivity 与 PlayFragment 各有一份 findMatchingEpisodeIndexRaw，
 * 修 bug 时只改一边就会漂移（PF 曾缺 L0 URL 预锚、L1.05 名内日期锚、L1.1 URL 日期锚）。
 * 本类把「原始层栈」收敛为一份，两个入口只负责提供环境参数；任何一层改动只改这里。</p>
 *
 * <p>层序（强证据优先）：L0 URL 日期 + 混合锚点 → L1 findIndex → L1.05 名内日期锚
 * → L1.1 URL 日期锚 → L1.2 混合式完全同名 → L1.6 日期表 → L2a 离线字典
 * → L2b 正片簇秩 → L2c 联网 → L1.5 组内位置 → 同域下标。</p>
 */
public final class EpisodeMatchCore {

    /** 离线字典（DA/PF 各持一个；仅长期无网部署才启用）。 */
    public interface DictResolver {
        int resolve(String currentName, List<String> targetNames);
    }

    /** 联网权威换算（DA/PF 各持超时/主线程守卫）。 */
    public interface OnlineResolver {
        int resolve(String currentName, List<String> targetNames);
    }

    public static int resolveRaw(String showName,
                                 String anchorTitle,
                                 String currentName,
                                 String currentUrl,
                                 int playIndex,
                                 List<String> playNames,
                                 List<String> rankSourceNames,
                                 List<String> targetNames,
                                 DictResolver dictResolver,
                                 OnlineResolver onlineResolver) {
        if (targetNames == null || targetNames.isEmpty()) return -1;
        if (targetNames.size() == 1) return 0;
        if (TextUtils.isEmpty(currentName)) return -1;

        // ---------- 第0层：播放流 URL 日期锚 + 混合锚点 → 规范期号（r112）----------
        final AnchorContext anchors = buildAnchorContext(showName, anchorTitle, currentName, currentUrl);
        if (!anchors.nonMain && !TextUtils.isEmpty(anchors.urlDate)
                && anchors.urlAnchorOrd > 0 && anchors.selfOrd > 0
                && anchors.selfOrd != anchors.urlAnchorOrd) {
            int byAnchor0 = EpisodeNameMatcher.findIndexByEpisode(currentName, anchors.urlAnchorOrd, targetNames);
            if (byAnchor0 >= 0) {
                LOG.sw("[FB] urlDateAnchorOrdPre url=" + anchors.urlDate + " self=" + anchors.selfOrd
                        + " ord=" + anchors.urlAnchorOrd + " ep=" + currentName
                        + " -> idx=" + byAnchor0 + " tgt=" + targetNames.get(byAnchor0));
                return byAnchor0;
            }
        }

        // ---------- 第1层：本地同域精确匹配 ----------
        int matchedIndex = EpisodeNameMatcher.findIndex(currentName, targetNames);
        if (matchedIndex >= 0) return matchedIndex;

        // ---------- 第1.05层：当前集名自带播出日期 → 混合锚点 → 目标纯期号落位（r114）----------
        if (!anchors.nonMain && !TextUtils.isEmpty(anchors.nameDate) && anchors.nameAnchorOrd > 0) {
            String anchorShow = TextUtils.isEmpty(anchorTitle) ? showName : anchorTitle;
            // 当前名自带分段时以当前名为准；只有纯日期名才用日期表补分段，避免表与名冲突时反向覆盖。
            int anchorPart = EpisodeNameMatcher.PART_NONE;
            if (EpisodeNameMatcher.extractPart(currentName) == EpisodeNameMatcher.PART_NONE) {
                anchorPart = EpisodeNameMatcher.lookupPartOfDateOrdinal(
                        anchorShow, EpisodeNameMatcher.dateOf(anchors.nameDate), anchors.nameAnchorOrd);
            }
            int byAnchorDate = EpisodeNameMatcher.findIndexByEpisode(
                    currentName, anchors.nameAnchorOrd, targetNames, anchorPart);
            if (byAnchorDate >= 0) {
                LOG.sw("[FB] nameDateAnchor date=" + anchors.nameDate + " ord=" + anchors.nameAnchorOrd
                        + " part=" + anchorPart
                        + " ep=" + currentName + " -> idx=" + byAnchorDate
                        + " tgt=" + targetNames.get(byAnchorDate));
                return byAnchorDate;
            }
        }

        // ---------- 第1.1层：当前播放流 URL 内含播出日期 → 权威日期锚（r111）----------
        if (!anchors.nonMain && !TextUtils.isEmpty(anchors.urlDate)) {
            int byUrlDate = EpisodeNameMatcher.findIndexByDate(anchors.urlDate, targetNames, currentName);
            if (byUrlDate >= 0) {
                LOG.sw("[FB] urlDateAnchor url=" + anchors.urlDate + " ep=" + currentName
                        + " -> idx=" + byUrlDate + " tgt=" + targetNames.get(byUrlDate));
                return byUrlDate;
            }
            if (anchors.urlAnchorOrd > 0) {
                int byAnchor = EpisodeNameMatcher.findIndexByEpisode(currentName, anchors.urlAnchorOrd, targetNames);
                if (byAnchor >= 0) {
                    LOG.sw("[FB] urlDateAnchorOrd url=" + anchors.urlDate + " ord=" + anchors.urlAnchorOrd
                            + " ep=" + currentName + " -> idx=" + byAnchor
                            + " tgt=" + targetNames.get(byAnchor));
                    return byAnchor;
                }
            }
        }

        // ---------- 第1.2层：日期+期号混合式名的「期数式」直配 ----------
        String ordinalForm = EpisodeNameMatcher.hybridOrdinalForm(currentName);
        if (!TextUtils.isEmpty(ordinalForm)
                && EpisodeNameMatcher.extractPart(ordinalForm) != EpisodeNameMatcher.PART_NONE) {
            int exactForm = targetNames.indexOf(ordinalForm);
            if (exactForm >= 0) return exactForm;
        }

        // ---------- 第1.6层：日期表（期号↔日期）→ 查表按形态选条 ----------
        {
            String tblShow = TextUtils.isEmpty(anchorTitle) ? showName : anchorTitle;
            List<String> tblSourceNames = rankSourceNames;
            int byTable = EpisodeNameMatcher.matchByDateTable(
                    tblShow, currentName, tblSourceNames, targetNames);
            if (LOG.isDebugEnabled()) {
                LOG.dbg("[DBG-L16] show=" + tblShow
                        + " cur=" + currentName
                        + " srcSize=" + (tblSourceNames == null ? -1 : tblSourceNames.size())
                        + " src0=" + (tblSourceNames == null || tblSourceNames.isEmpty() ? "" : tblSourceNames.get(0))
                        + " srcHas=" + (tblSourceNames != null && tblSourceNames.contains(currentName))
                        + " tgtSize=" + targetNames.size()
                        + " tgt0=" + targetNames.get(0)
                        + " cross=" + EpisodeNameMatcher.needsCrossDomainResolve(currentName, targetNames)
                        + " byTable=" + byTable);
            }
            if (byTable >= 0) {
                LOG.sw("[FB] dateTableAnchor ep=" + currentName + " -> idx=" + byTable
                        + " tgt=" + targetNames.get(byTable));
                return byTable;
            }
        }

        // ---------- 第2层：跨域 → 权威换算 ----------
        if (EpisodeNameMatcher.needsCrossDomainResolve(currentName, targetNames)) {
            // 2-a 离线字典
            if (dictResolver != null) {
                int dictIndex = dictResolver.resolve(currentName, targetNames);
                if (dictIndex >= 0) return dictIndex;
            }
            // 2-b 离线正片簇秩
            List<String> rankNames = rankSourceNames;
            int rankSourceIndex = rankNames == null ? -1 : rankNames.indexOf(currentName);
            String rankShow = TextUtils.isEmpty(anchorTitle) ? showName : anchorTitle;
            int byRank = EpisodeNameMatcher.alignByMainFeatureRank(rankShow,
                    currentName, rankSourceIndex, rankNames, targetNames);
            if (byRank >= 0) return byRank;
            // 2-c 直连站点
            if (onlineResolver != null) {
                int online = onlineResolver.resolve(currentName, targetNames);
                if (online >= 0) return online;
            }
        }

        // ---------- 第1.5层：同日期多段的组内位置对齐（Tier B 兜底）----------
        if (playIndex >= 0 && playNames != null && !playNames.isEmpty()) {
            int byGroup = EpisodeNameMatcher.alignByGroupPosition(
                    currentName, playIndex, playNames, targetNames);
            if (byGroup >= 0) return byGroup;
        }
        // ---------- 同域纯下标兜底（仅同域）----------
        if (playIndex >= 0 && playNames != null && !playNames.isEmpty()) {
            int byOrder = EpisodeNameMatcher.alignByOrder(
                    playIndex, playNames, targetNames, false);
            if (byOrder >= 0) return byOrder;
        }
        return matchedIndex;
    }

    /**
     * ★ r146(P3)：日期锚上下文一次性提取，供 L0/L1.05/L1.1 共用。
     * 原实现把 `EpisodeDict.extractDate(currentUrl)` 算两次、`isNonMainEntryName` 算三次；
     * 这里只做同值缓存，不改变任何层的判定顺序与结果。
     */
    private static final class AnchorContext {
        final boolean nonMain;
        final String nameDate;
        final String urlDate;
        final int nameAnchorOrd;
        final int urlAnchorOrd;
        final int selfOrd;

        AnchorContext(boolean nonMain, String nameDate, String urlDate,
                      int nameAnchorOrd, int urlAnchorOrd, int selfOrd) {
            this.nonMain = nonMain;
            this.nameDate = nameDate;
            this.urlDate = urlDate;
            this.nameAnchorOrd = nameAnchorOrd;
            this.urlAnchorOrd = urlAnchorOrd;
            this.selfOrd = selfOrd;
        }
    }

    private static AnchorContext buildAnchorContext(String showName, String anchorTitle,
                                                    String currentName, String currentUrl) {
        boolean nonMain = EpisodeNameMatcher.isNonMainEntryName(currentName);
        String nameDate = EpisodeDict.extractDate(currentName);
        String urlDate = "";
        if (TextUtils.isEmpty(nameDate) && !nonMain && currentUrl != null && !currentUrl.isEmpty()) {
            urlDate = EpisodeDict.extractDate(currentUrl);
        }
        String showKey = TextUtils.isEmpty(anchorTitle) ? showName : anchorTitle;
        int nameAnchorOrd = TextUtils.isEmpty(nameDate)
                ? 0 : EpisodeNameMatcher.ordinalOfDateFromAnchor(showKey, nameDate);
        int urlAnchorOrd = TextUtils.isEmpty(urlDate)
                ? 0 : EpisodeNameMatcher.ordinalOfDateFromAnchor(showKey, urlDate);
        int selfOrd = EpisodeNameMatcher.extractOrdinal(currentName);
        return new AnchorContext(nonMain, nameDate, urlDate, nameAnchorOrd, urlAnchorOrd, selfOrd);
    }

    private EpisodeMatchCore() {
    }
}
