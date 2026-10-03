package com.github.tvbox.osc.cache;

import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.data.AppDataManager;
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
        if (Hawk.get(HawkConfig.HISTORY_MERGE, false) && vodInfo != null) {
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
        record.dataJson = getVodInfoGson().toJson(vodInfo);
        dao.insert(record);
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
    }

    public static List<VodInfo> getAllVodRecord(int limit) {
        VodRecordDao dao = AppDataManager.get().getVodRecordDao();
        Integer index = Hawk.get(HawkConfig.HISTORY_NUM, 0);
        Integer hisNum = HistoryHelper.getHisNum(index);
        List<VodRecord> recordList = dao.getAll(Integer.MAX_VALUE);
        List<VodInfo> vodInfoList = new ArrayList<>();
        boolean historyMerge = Hawk.get(HawkConfig.HISTORY_MERGE, false);
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
        if (dao.getCount() > hisNum) {
            // reserver 会删掉超出条数的旧历史；被删影片的播放进度随即成为孤儿，
            // 必须一并清理，否则"历史里已经没有这部片了，进度却一直留着"。
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
    }

    public static void deleteVodCollect(int id) {
        AppDataManager.get().getVodCollectDao().delete(id);
    }

    public static void deleteVodCollect(String sourceKey, VodInfo vodInfo) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id);
        if (record != null) {
            AppDataManager.get().getVodCollectDao().delete(record);
        }
    }
    
    public static void deleteVodCollectAll() {
        AppDataManager.get().getVodCollectDao().deleteAll();
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

    public static boolean isVodCollect(String sourceKey, String vodId) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodId);
        return record != null;
    }

    public static List<VodCollect> getAllVodCollect() {
        return AppDataManager.get().getVodCollectDao().getAll();
    }
}
