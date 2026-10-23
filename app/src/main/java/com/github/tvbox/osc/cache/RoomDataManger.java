package com.github.tvbox.osc.cache;

import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.sync.HistorySyncManager;
import com.github.tvbox.osc.sync.SyncScope;
import com.github.tvbox.osc.sync.SyncTombstones;
import com.google.gson.ExclusionStrategy;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import com.orhanobut.hawk.Hawk;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.tvbox.osc.cache.PlayProgressManager;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
public class RoomDataManger {
    static ExclusionStrategy vodInfoStrategy = new ExclusionStrategy() {
        @Override
        public boolean shouldSkipField(FieldAttributes field) {
            if (field.getDeclaringClass() == VodInfo.class && field.getName().equals("seriesFlags")) {
                return true;
            }
            if (field.getDeclaringClass() == VodInfo.class && field.getName().equals("seriesMap")) {
                return true;
            }
            return false;
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    };

    /**
     * Gson 实例是线程安全的，且构建一次开销不低（含 ExclusionStrategy 注册）。
     * 原实现每次调用都 new 一个，而它又被 getAllVodRecord / getVodInfoBySameName 等
     * 在<b>遍历循环内逐条调用</b>，等于对每条历史记录重建一次 Gson。
     * 改为进程内单例。
     */
    private static final Gson VOD_INFO_GSON =
            new GsonBuilder().addSerializationExclusionStrategy(vodInfoStrategy).create();

    private static Gson getVodInfoGson() {
        return VOD_INFO_GSON;
    }

    public static void insertVodRecord(String sourceKey, VodInfo vodInfo) {
        VodRecordDao dao = AppDataManager.get().getVodRecordDao();
        if (Hawk.get(HawkConfig.HISTORY_MERGE, HawkConfig.DEFAULT_HISTORY_MERGE) && vodInfo != null) {
            // 合并同名历史开启：本次保存后同名称的旧源记录会被删除。
            // 若本次保存的位置信息缺失（如跨源映射失败导致集数名未取到），先从同名称的其他源记录接续集数记忆，
            // 避免旧源记忆在合并删除时被清空。
            if (TextUtils.isEmpty(vodInfo.playNote)) {
                VodInfo sameName = getVodInfoBySameName(sourceKey, vodInfo.id, vodInfo.name);
                if (sameName != null && !TextUtils.isEmpty(sameName.playNote)) {
                    vodInfo.playNote = sameName.playNote;
                }
            }
            removeSameNameVodRecords(dao, sourceKey, vodInfo);
        }
        VodRecord record = dao.getVodRecord(sourceKey, vodInfo.id);
        if (record == null) {
            record = new VodRecord();
        }
        record.sourceKey = sourceKey;
        record.vodId = vodInfo.id;
        record.updateTime = System.currentTimeMillis();
        String newJson = getVodInfoGson().toJson(vodInfo);
        // ★ v99-r21：内容实质没变就不标脏。
        //
        //   场景（盒子端实测反馈）：从首页第一条历史继续看同一部片的同一集，退出后
        //   history.bin 也被上传了一次 —— 用户认为"只该传进度"。根因是这里每次都无条件
        //   markDirty(HISTORY)，哪怕 dataJson 与库里完全一致（只是 updateTime 刷新了）。
        //
        //   updateTime 仍照常刷新（首页"最近观看"的排序依据），但内容一致时不上传：
        //   HISTORY 不脏 ⇒ 同步时走 304 条件请求 ⇒ 云端 etag 未变 ⇒ 完全跳过。
        //   真发生了变化（换集 / 换线路 / 片名或海报更新）时 dataJson 必然不同 ⇒ 照常标脏上传。
        boolean contentChanged = !newJson.equals(record.dataJson);
        record.dataJson = newJson;
        dao.insert(record);
        // v98 P2：只脏「历史」——写一条观看记录不该把收藏/设置也拖下水
        if (contentChanged) {
            HistorySyncManager.markDirty(SyncScope.HISTORY);
        }
        // v77：本机只保留当前档位的条数，超出的直接删（不写墓碑，避免传到别的设备误删）
        pruneHistory();
    }

    /** 按「保留历史记录数量」裁剪本机历史（按 updateTime 倒序，保留最新 N 条）。 */
    public static void pruneHistory() {
        try {
            int keep = HistoryHelper.getHisNum(Hawk.get(HawkConfig.HISTORY_NUM, 0));
            if (keep <= 0) {
                return;
            }
            VodRecordDao dao = AppDataManager.get().getVodRecordDao();
            boolean changed = false;
            // ★ v99-r25：「条数上限」指的是【合并去重之后】的条数。
            //   合并开启时先把同名记录收敛为「只保留最新源」，再按 keep 裁剪；
            //   否则"15 条"里可能有多条是同一部片的不同源，实际影片数远少于 15。
            if (Hawk.get(HawkConfig.HISTORY_MERGE, HawkConfig.DEFAULT_HISTORY_MERGE)) {
                changed = dedupeSameName(dao) > 0;
            }
            List<VodRecord> all = dao.getAll(Integer.MAX_VALUE);
            if (all != null && all.size() > keep) {
                for (int i = keep; i < all.size(); i++) {
                    dao.delete(all.get(i));
                }
                // 被裁掉的影片，其播放进度随即成为孤儿，必须一并清理
                PlayProgressManager.deleteOrphaned();
                changed = true;
            }
            if (changed) {
                HistorySyncManager.markDirty(SyncScope.HISTORY);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * ★ v99-r25：把库里的同名历史收敛为「只保留最新源」。
     *
     * <p>原来只有 {@link #insertVodRecord} 时才会删同名旧源，历史里早已存在的重复
     * （合并开关从关到开、或多设备同步带进来的）没人清理 —— 于是「上限按去重后条数」
     * 这件事根本不成立。这里按片名分组，每组保留 {@code updateTime} 最大的一条，
     * 其余删除前先把播放进度迁到保留的那条（跨源集名匹配，避免丢进度）。</p>
     *
     * @return 实际删除的记录数
     */
    private static int dedupeSameName(VodRecordDao dao) {
        List<VodRecord> all = dao.getAll(Integer.MAX_VALUE);
        if (all == null || all.size() < 2) {
            return 0;
        }
        Map<String, VodRecord> winner = new LinkedHashMap<>();
        Map<String, VodInfo> winnerInfo = new LinkedHashMap<>();
        List<VodRecord> losers = new ArrayList<>();
        for (VodRecord r : all) {
            VodInfo info = parseRecordInfo(r);
            String name = getVodRecordName(info);
            if (TextUtils.isEmpty(name)) {
                continue;   // 名字取不到的记录不参与去重（宁可留着也不误删）
            }
            VodRecord cur = winner.get(name);
            if (cur == null) {
                winner.put(name, r);
                winnerInfo.put(name, info);
            } else if (r.updateTime > cur.updateTime) {
                losers.add(cur);
                winner.put(name, r);
                winnerInfo.put(name, info);
            } else {
                losers.add(r);
            }
        }
        int n = 0;
        for (VodRecord r : losers) {
            try {
                VodInfo info = parseRecordInfo(r);
                String name = getVodRecordName(info);
                VodRecord keep = winner.get(name);
                if (keep != null && !TextUtils.equals(keep.sourceKey, r.sourceKey)) {
                    // 删旧源前先把该片全部集的进度搬到新源，否则会丢掉"看到第几集/位置"
                    PlayProgressManager.migrateByVod(r.sourceKey, r.vodId,
                            keep.sourceKey, keep.vodId, collectAllEpisodeNames(winnerInfo.get(name)));
                }
                dao.delete(r);
                n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /** 安全解析历史行的 dataJson；失败返回 null（调用方按"无名"处理）。 */
    private static VodInfo parseRecordInfo(VodRecord record) {
        try {
            if (record != null && !TextUtils.isEmpty(record.dataJson)) {
                VodInfo info = getVodInfoGson().fromJson(record.dataJson,
                        new TypeToken<VodInfo>() {
                        }.getType());
                if (info != null && info.name != null) {
                    info.sourceKey = record.sourceKey;
                    return info;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static VodInfo getVodInfo(String sourceKey, String vodId) {
        VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodId);
        try {
            if (record != null && record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                VodInfo vodInfo = getVodInfoGson().fromJson(record.dataJson, new TypeToken<VodInfo>() {
                }.getType());
                if (vodInfo.name == null)
                    return null;
                return vodInfo;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * ↓换源时跨源找回播放记忆：按视频名称在其他源的历史记录里查找（排除当前 sourceKey+vodId 本身）。
     * 返回的记录带有所在源的 sourceKey/vodId 以及记住的 playFlag/playIndex/playNote。
     * 多个同名记录时优先返回最近更新的一个（getAll 已按 updateTime desc 排序）。
     */
    public static VodInfo getVodInfoBySameName(String sourceKey, String vodId, String name) {
        if (TextUtils.isEmpty(name)) return null;
        String trimName = name.trim();
        VodRecordDao dao = AppDataManager.get().getVodRecordDao();
        List<VodRecord> all = dao.getAll(Integer.MAX_VALUE);
        if (all == null) return null;
        for (VodRecord record : all) {
            if (record == null
                    || (TextUtils.equals(sourceKey, record.sourceKey) && TextUtils.equals(vodId, record.vodId))) {
                continue;
            }
            try {
                if (record.dataJson == null || TextUtils.isEmpty(record.dataJson)) continue;
                VodInfo info = getVodInfoGson().fromJson(record.dataJson, new TypeToken<VodInfo>() {}.getType());
                if (info != null && info.name != null && TextUtils.equals(trimName, info.name.trim())) {
                    info.sourceKey = record.sourceKey;
                    info.id = record.vodId;
                    return info;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }
    
       /** 查询本源某影片记录的更新时间；无记录返回 0。 */
    public static long getVodRecordUpdateTime(String sourceKey, String vodId) {
        VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodId);
        return record == null ? 0 : record.updateTime;
    }

    /**
     * 在同名（其他源）历史记录里找「比 newerThan 更新」的一条。
     * getAll 已按 updateTime desc 排序，遇到不比 newerThan 新的可直接终止。
     * 用于：本源有旧记录、但其他源刚看过同一部片时，以最新者为准。
     */
    public static VodInfo getVodInfoBySameNameNewerThan(String sourceKey, String vodId, String name, long newerThan) {
        if (TextUtils.isEmpty(name)) return null;
        String trimName = name.trim();
        VodRecordDao dao = AppDataManager.get().getVodRecordDao();
        List<VodRecord> all = dao.getAll(Integer.MAX_VALUE);
        if (all == null) return null;
        for (VodRecord record : all) {
            if (record == null
                    || (TextUtils.equals(sourceKey, record.sourceKey) && TextUtils.equals(vodId, record.vodId))) {
                continue;
            }
            if (record.updateTime <= newerThan) {
                return null; // 已排序，后面只会更旧
            }
            try {
                if (record.dataJson == null || TextUtils.isEmpty(record.dataJson)) continue;
                VodInfo info = getVodInfoGson().fromJson(record.dataJson, new TypeToken<VodInfo>() {}.getType());
                if (info != null && info.name != null && TextUtils.equals(trimName, info.name.trim())) {
                    info.sourceKey = record.sourceKey;
                    info.id = record.vodId;
                    return info;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    public static void deleteVodRecord(String sourceKey, VodInfo vodInfo) {
        if (vodInfo == null) return;
        deleteVodRecord(sourceKey, vodInfo.id);
    }

    /**
     * 按「源 + 影片ID」删除观看历史，并同步清空该影片的全部播放进度
     * （看到第几集、各集的播放位置与最后观看时间）。
     * <p>不要求先取到 VodInfo：即使历史行已不存在（例如刚被超限裁剪），
     * 直接按主键清进度也能避免残留。</p>
     */
    public static void deleteVodRecord(String sourceKey, String vodId) {
        if (TextUtils.isEmpty(vodId)) return;
        PlayProgressManager.deleteByVod(sourceKey, vodId);
        VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodId);
        if (record != null) {
            AppDataManager.get().getVodRecordDao().delete(record);
        }
        // v73：同步墓碑（用户主动删除，需传播到其它设备）
        SyncTombstones.addRecord(sourceKey, vodId);
        // v98 P2：删历史会连带清掉该片的进度（上面 deleteByVod），故两个 scope 都要脏
        HistorySyncManager.markDirty(SyncScope.HISTORY, SyncScope.PROGRESS);
    }

    public static List<VodInfo> getAllVodRecord(int limit) {
        VodRecordDao dao = AppDataManager.get().getVodRecordDao();
        Integer index = Hawk.get(HawkConfig.HISTORY_NUM, 0);
        Integer hisNum = HistoryHelper.getHisNum(index);
        List<VodRecord> recordList = dao.getAll(Integer.MAX_VALUE);
        List<VodInfo> vodInfoList = new ArrayList<>();
        boolean historyMerge = Hawk.get(HawkConfig.HISTORY_MERGE, HawkConfig.DEFAULT_HISTORY_MERGE);
        Set<String> historyNames = historyMerge ? new HashSet<String>() : null;
        if (recordList != null) {
            for (VodRecord record : recordList) {
                VodInfo info = null;
                try {
                    if (record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                        info = getVodInfoGson().fromJson(record.dataJson, new TypeToken<VodInfo>() {
                        }.getType());
                        info.sourceKey = record.sourceKey;
//                        SourceBean sourceBean = ApiConfig.get().getSource(info.sourceKey);
                        if (info.name == null)
                            info = null;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                String name = getVodRecordName(info);
                if (info != null) {
                    if (!historyMerge || TextUtils.isEmpty(name) || historyNames.add(name)) {
                        vodInfoList.add(info);
                    }
                }
            }
        }
        // 写入路径（insertVodRecord → pruneHistory）已经做过同名去重，库里不应再有重复；
        // 这里只按条数兜一刀（读路径不做全量 JSON 解析，避免首页刷新时白白耗时）。
        if (dao.getCount() > hisNum) {
            if (dao.reserver(hisNum) > 0) {
                PlayProgressManager.deleteOrphaned();
            }
        }
        int size = Math.min(vodInfoList.size(), Math.min(limit, hisNum));
        return new ArrayList<>(vodInfoList.subList(0, size));
    }

    private static void removeSameNameVodRecords(VodRecordDao dao, String sourceKey, VodInfo vodInfo) {
        String name = getVodRecordName(vodInfo);
        if (TextUtils.isEmpty(name)) return;
        List<String> newEpNames = null;
        for (VodRecord record : dao.getAll(Integer.MAX_VALUE)) {
            if (TextUtils.equals(sourceKey, record.sourceKey) && TextUtils.equals(vodInfo.id, record.vodId)) {
                continue;
            }
            try {
                VodInfo history = getVodInfoGson().fromJson(record.dataJson, new TypeToken<VodInfo>() {
                }.getType());
                if (TextUtils.equals(name, getVodRecordName(history))) {
                    // ★ 删除旧源历史前，必须先把旧源该片「全部集」的进度搬到新源。
                    //
                    // 直接 deleteByVod 会连同其余各集的播放位置与最后观看时间一起抹掉，
                    // 只留下详情回调里迁移过的「当前正在播的那一集」——表现为换源后
                    // 以前看过的集时间记忆全部消失。这正是"切源后播放记录丢失"的根因。
                    if (newEpNames == null) {
                        newEpNames = collectAllEpisodeNames(vodInfo);
                    }
                    PlayProgressManager.migrateByVod(record.sourceKey, record.vodId,
                            sourceKey, vodInfo.id, newEpNames);
                    dao.delete(record);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 收集一部影片在<b>所有线路</b>下的集名，供换源时做跨源集名匹配。 */
    private static List<String> collectAllEpisodeNames(VodInfo vodInfo) {
        List<String> out = new ArrayList<>();
        if (vodInfo == null || vodInfo.seriesMap == null) {
            return out;
        }
        try {
            for (Map.Entry<String, List<VodInfo.VodSeries>> entry : vodInfo.seriesMap.entrySet()) {
                List<VodInfo.VodSeries> list = entry.getValue();
                if (list == null) continue;
                for (VodInfo.VodSeries series : list) {
                    if (series == null || TextUtils.isEmpty(series.name)) continue;
                    String n = series.name.trim();
                    if (!TextUtils.isEmpty(n) && !out.contains(n)) {
                        out.add(n);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String getVodRecordName(VodInfo vodInfo) {
        return vodInfo == null || vodInfo.name == null ? "" : vodInfo.name.trim();
    }

    public static void insertVodCollect(String sourceKey, VodInfo vodInfo) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id);
        if (record != null) {
            return;
        }
        record = new VodCollect();
        record.sourceKey = sourceKey;
        record.vodId = vodInfo.id;
        record.updateTime = System.currentTimeMillis();
        record.name = vodInfo.name;
        record.pic = vodInfo.pic;
        AppDataManager.get().getVodCollectDao().insert(record);
        HistorySyncManager.markDirty(SyncScope.COLLECT);
    }

    public static void deleteVodCollect(int id) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(id);
        AppDataManager.get().getVodCollectDao().delete(id);
        if (record != null) {
            SyncTombstones.addCollect(record.sourceKey, record.vodId);
            HistorySyncManager.markDirty(SyncScope.COLLECT);
        }
    }

    public static void deleteVodCollect(String sourceKey, VodInfo vodInfo) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id);
        if (record != null) {
            AppDataManager.get().getVodCollectDao().delete(record);
            SyncTombstones.addCollect(sourceKey, vodInfo.id);
            HistorySyncManager.markDirty(SyncScope.COLLECT);
        }
    }
    
    public static void deleteVodCollectAll() {
        AppDataManager.get().getVodCollectDao().deleteAll();
    }

    /** 清空收藏并为每条写墓碑（用户在收藏页主动清空 ⇒ 应传播到其它设备）。 */
    public static void deleteVodCollectAllWithTombstones() {
        try {
            List<VodCollect> all = AppDataManager.get().getVodCollectDao().getAll();
            if (all != null) {
                for (VodCollect c : all) {
                    SyncTombstones.addCollect(c.sourceKey, c.vodId);
                }
            }
        } catch (Throwable ignored) {
        }
        AppDataManager.get().getVodCollectDao().deleteAll();
        HistorySyncManager.markDirty(SyncScope.COLLECT);
    }

    /**
     * 删除同一部片在<b>其它源</b>下的历史记录，并把它们的播放进度<b>整体迁到本源</b>后
     * 才清除旧源。等价于把「同名合并历史」这一动作单独开放出来。
     *
     * <p>切源连跳会一站一条地写入历史（每跳一个站成功加载详情就写一次），
     * 既挤占历史上限把其它影片顶掉，也让"最近观看"里塞满同一部片。
     * 切源本来就是同一部片换站，理应只保留一条——故此处不受
     * {@code HawkConfig.HISTORY_MERGE} 开关限制。</p>
     *
     * @return 被合并掉的旧源条数
     */
    public static int mergeSameNameVodRecords(String sourceKey, VodInfo vodInfo) {
        try {
            if (vodInfo == null || TextUtils.isEmpty(vodInfo.id)) {
                return 0;
            }
            VodRecordDao dao = AppDataManager.get().getVodRecordDao();
            int before = 0;
            List<VodRecord> all = dao.getAll(Integer.MAX_VALUE);
            if (all != null) {
                before = all.size();
            }
            removeSameNameVodRecords(dao, sourceKey, vodInfo);
            List<VodRecord> after = dao.getAll(Integer.MAX_VALUE);
            int now = after == null ? 0 : after.size();
            return Math.max(0, before - now);
        } catch (Throwable th) {
            th.printStackTrace();
            return 0;
        }
    }

    /**
     * 清理"已不在观看历史中"的影片所残留的播放进度（看到第几集、各集播放位置与时间）。
     * <p>供设置页「清空缓存」调用：历史记录内的影片其进度会被保留，
     * 只有那些已经从历史里消失（被裁剪 / 合并删除 / 清空）的影片才会被清掉。</p>
     *
     * @return 实际删除的进度行数
     */
    public static int deleteOrphanedPlayProgress() {
        return PlayProgressManager.deleteOrphaned();
    }

    public static void deleteVodRecordAll() {
        PlayProgressManager.deleteAll();
        AppDataManager.get().getVodRecordDao().deleteAll();
    }

    /** 清空历史并为每条写墓碑（用户在历史页主动清空 ⇒ 应传播到其它设备）。 */
    public static void deleteVodRecordAllWithTombstones() {
        try {
            List<VodRecord> all = AppDataManager.get().getVodRecordDao().getAll(Integer.MAX_VALUE);
            if (all != null) {
                for (VodRecord r : all) {
                    SyncTombstones.addRecord(r.sourceKey, r.vodId);
                }
            }
        } catch (Throwable ignored) {
        }
        PlayProgressManager.deleteAll();
        AppDataManager.get().getVodRecordDao().deleteAll();
        HistorySyncManager.markDirty(SyncScope.HISTORY, SyncScope.PROGRESS);
    }

    public static boolean isVodCollect(String sourceKey, String vodId) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodId);
        return record != null;
    }

    public static List<VodCollect> getAllVodCollect() {
        return AppDataManager.get().getVodCollectDao().getAll();
    }
}
