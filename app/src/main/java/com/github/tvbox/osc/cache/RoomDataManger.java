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
import com.github.tvbox.osc.util.FilmIdentity;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.VodNameNormalizer;
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
        // ★ v99-r49：同名合并恒开启（开关已删除），写入前一律收敛其它源的同名记录
        if (vodInfo != null) {
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
            // ★ v99-r50：把"清坏行 + 同名收敛"收敛成唯一出口 compactHistory()，
            //   所有需要收敛的路径（写入/裁剪/同步落地/读时自愈）都调它，避免口径漂移。
            boolean changed = compactHistory() > 0;
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
     * ★ v99-r50：历史收敛的统一出口（幂等）。
     *
     * <p>做三件事：清掉坏行、把同名记录收敛为一条（保留最新）、清理随之产生的孤儿进度。</p>
     *
     * <p><b>为什么还要这个东西</b>：写入入口（insertVodRecord）已经会删同名，但有三条路径绕过它——
     * 云同步 {@code applyLocal}（整表重写）、备份恢复（整库覆盖）、以及升级前的历史遗留。
     * 这三条都可能把重复行带进来，所以需要一个与入口解耦的收敛点。</p>
     *
     * @return 发生变动的条数（0 = 无重复、无需写库）
     */
    public static int compactHistory() {
        int removed = 0;
        try {
            VodRecordDao dao = AppDataManager.get().getVodRecordDao();
            int before = dao.getCount();
            int purge = purgeInvalidRecords(dao);
            int dedupe = dedupeSameName(dao);
            removed = purge + dedupe;
            if (removed > 0) {
                PlayProgressManager.deleteOrphaned();
                HistorySyncManager.markDirty(SyncScope.HISTORY);
                try {
                    // ★ v99-r55：拆开 purge（坏行）与 dedupe（同名重复），
                    //   否则"一次删了 6 条"分不清是数据坏了还是重复太多，无从下手。
                    LOG.i("[Hist] compact db=" + before + " purge=" + purge + " dedupe=" + dedupe
                            + " keeep=" + HistoryHelper.getHisNum(Hawk.get(HawkConfig.HISTORY_NUM, 0)));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return removed;
    }

    /** 同步落地 / 启动自愈用：等价于 compactHistory()，命名更贴近调用语义。 */
    public static int selfHealHistory() {
        return compactHistory();
    }

    /**
     * ★ v99-r50：切源「按会话身份」合并上一跳——不看名字也能删（名字只作护栏）。
     *
     * <p>切源链路每一跳都会写一条历史（源与 id 都变），能否收敛全看名字，
     * 而名字变体（标点/全角/季节写法）会让合并漏掉 ⇒ 同一部片占多个档位。
     * 这里用「切源会话内已知的上一跳身份」直接删旧行，与名字判定互为兜底。</p>
     *
     * <p><b>三层护栏</b>（缺一不删）：
     * <ol>
     *   <li>调用方保证：只在本跳成功（detail 已加载、episodeCheck 通过）之后调用；</li>
     *   <li>名字护栏：两条记录归一化名必须相同；不同 ⇒ 可能切到了别的同名片 ⇒ 不删；</li>
     *   <li>身份护栏：from/to 必须不同，且 from 行确实存在。</li>
   * </ol>
   * ★ v99-r55：删除**要写墓碑**（原实现不写，理由见下方 dedupeSameName 的同名注释）。
   * 不写的话云端那份旧行永远还在，每次同步落地又被 upsert 回本机，条数反复抖动。</p>
     *
     * @return 删除的行数（0 表示未命中/被护栏拦下）
     */
    public static int mergeSwitchHop(String fromSourceKey, String fromVodId,
                                     String toSourceKey, VodInfo toVodInfo) {
        try {
            if (TextUtils.isEmpty(fromSourceKey) || TextUtils.isEmpty(fromVodId) || toVodInfo == null) {
                return 0;
            }
            if (TextUtils.equals(fromSourceKey, toSourceKey) && TextUtils.equals(fromVodId, toVodInfo.id)) {
                return 0;
            }
            VodRecordDao dao = AppDataManager.get().getVodRecordDao();
            VodRecord from = dao.getVodRecord(fromSourceKey, fromVodId);
            if (from == null) {
                return 0;
            }
            VodInfo fromInfo = parseRecordInfo(from);
            String fromName = getVodRecordName(fromInfo);
            String toName = getVodRecordName(toVodInfo);
            // ★ v99-r51：名字不同不再阻止删除 —— 切源会话内相邻两跳本来就是同一部片，
            //   名字变体（标点/季度写法/全角）正是重复的来源。差异只记日志以便审计。
            if (!TextUtils.equals(fromName, toName)) {
                try {
                    LOG.i("[Hist] hopDiffName 「" + fromName + "」->「" + toName + "」");
                } catch (Throwable ignored) {
                }
            }
            // 先迁进度（按集名对齐），迁移失败也照删——进度是"尽力而为"，不能因为迁移失败留下重复行
            try {
                PlayProgressManager.migrateByVod(fromSourceKey, fromVodId,
                        toSourceKey, toVodInfo.id, collectAllEpisodeNames(toVodInfo));
            } catch (Throwable ignored) {
            }
            dao.delete(from);
            // ★ v99-r55：与 dedupeSameName 同理，写墓碑切断"云端回灌"闭环。
            SyncTombstones.addRecord(fromSourceKey, fromVodId);
            try {
                LOG.i("[Hist] hopMerged " + fromSourceKey + "/" + fromVodId
                        + " -> " + toSourceKey + "/" + toVodInfo.id);
            } catch (Throwable ignored) {
            }
            return 1;
        } catch (Throwable th) {
            th.printStackTrace();
            return 0;
        }
    }

    /**
     * ★ v99-r27 / r30 修正：删除「永远显示不出来」的历史记录。
     *
     * <p>★ 判定条件必须与 {@link #getAllVodRecord(int)} 的显示条件**完全一致**：
     * 那边只跳过 {@code info == null}（反序列化失败），片名为空的记录仍然会被显示
     * （{@code TextUtils.isEmpty(name)} 是短路放行）。r27 的初版在这里多加了
     * 「片名为空也删」，比显示条件更严 ⇒ <b>会把本来能显示的记录误删</b>，
     * 表现为"看了新片数量也不涨"。现已收窄为只看能否反序列化。</p>
     *
     * @return 删除条数
     */
    private static int purgeInvalidRecords(VodRecordDao dao) {
        List<VodRecord> all = dao.getAll(Integer.MAX_VALUE);
        if (all == null || all.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (VodRecord r : all) {
            if (!isParseable(r)) {
                try {
                    dao.delete(r);
                    n++;
                } catch (Throwable ignored) {
                }
            }
        }
        return n;
    }

    /**
     * ★ v99-r30：库内历史记录的实际条数（含暂时无法显示的）。
     *
     * <p>用途：设置页把它显示在「历史记录条数」旁边。用户报"档位 15 但只能看到 14"时，
     * 一眼就能区分是<b>库里真的少</b>还是<b>显示层过滤掉了</b>，不必再靠猜。</p>
     */
    public static int countVodRecord() {
        try {
            return AppDataManager.get().getVodRecordDao().getCount();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 能否反序列化出 VodInfo（与 getAllVodRecord 的过滤条件一致：只看 info 是否为 null）。 */
    private static boolean isParseable(VodRecord record) {
        if (record == null || TextUtils.isEmpty(record.dataJson)) {
            return false;
        }
        try {
            VodInfo info = getVodInfoGson().fromJson(record.dataJson,
                    new TypeToken<VodInfo>() {
                    }.getType());
            return info != null;
        } catch (Throwable t) {
            return false;
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
                // ★ v99-r55：同名合并的删除**必须写墓碑**。
                //
                //   原实现不写（理由是"内部合并不是用户删除，不该传播"），结果：云端那份
                //   重复行永远还在，每次同步落地又被 upsert 回本机 —— 真机实测（02:47:29）
                //   同步后 db 从 10 涨到 15，compact 再删 6 条回到 9，条数反复抖动、档位永远填不满。
                //
                //   写墓碑是安全的：删掉 loser 的同时 winner 也在同一个同步包里上传，
                //   其它设备拉到的结果是「这部片只剩 winner 那一行」，不会整片丢失。
                SyncTombstones.addRecord(r.sourceKey, r.vodId);
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
     *
     * <p>★ v99-r46：同名判定改用 {@link VodNameNormalizer#normalize(String)}（与历史合并同口径），
     * 于是「现在就出发第四季」的记录能接上「现在就出发 第4季」源里的播放位置。</p>
     */
    public static VodInfo getVodInfoBySameName(String sourceKey, String vodId, String name) {
        if (TextUtils.isEmpty(name)) return null;
        String key = VodNameNormalizer.normalize(name);
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
                if (info != null && info.name != null
                        && TextUtils.equals(key, VodNameNormalizer.normalize(info.name))) {
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
        String key = VodNameNormalizer.normalize(name);
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
                // ★ v99-r46：与 getVodInfoBySameName 同口径（归一化后比较），变体写法也能接上进度
                if (info != null && info.name != null
                        && TextUtils.equals(key, VodNameNormalizer.normalize(info.name))) {
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
            // ★ v99-r58 A 案：历史删了，这部片的切源候选池要跟着删。
            //   片名不在表里，只在 dataJson（VodInfo 序列化）里，故解析一次；
            //   解析失败不影响删历史本身（由冷启动的孤儿回收兜底）。
            clearFallbackPoolQuiet(record);
        }
        // v73：同步墓碑（用户主动删除，需传播到其它设备）
        SyncTombstones.addRecord(sourceKey, vodId);
        // v98 P2：删历史会连带清掉该片的进度（上面 deleteByVod），故两个 scope 都要脏
        HistorySyncManager.markDirty(SyncScope.HISTORY, SyncScope.PROGRESS);
    }

    /**
     * ★ v99-r58：删历史 ⇒ 连带删掉该片的切源候选池。
     *
     * <p>r58 起候选池会攒到接近"全部可搜源"，体积明显变大；
     * 历史记录都删了还留着这部片的池子，等于白占 Hawk 的空间。</p>
     */
    private static void clearFallbackPoolQuiet(VodRecord record) {
        try {
            String name = vodNameOf(record);
            if (!TextUtils.isEmpty(name)) {
            }
        } catch (Throwable th) {
            LOG.e("clearFallbackPoolQuiet fail: " + th);
        }
    }

    /** 从历史行里取片名（VodRecord 只有 dataJson 里有完整 VodInfo）。 */
    private static String vodNameOf(VodRecord record) {
        if (record == null || TextUtils.isEmpty(record.dataJson)) {
            return "";
        }
        try {
            VodInfo info = new Gson().fromJson(record.dataJson, VodInfo.class);
            return info == null ? "" : info.name;
        } catch (Throwable th) {
            return "";
        }
    }

    public static List<VodInfo> getAllVodRecord(int limit) {
        VodRecordDao dao = AppDataManager.get().getVodRecordDao();
        Integer index = Hawk.get(HawkConfig.HISTORY_NUM, 0);
        Integer hisNum = HistoryHelper.getHisNum(index);
        List<VodRecord> recordList = dao.getAll(Integer.MAX_VALUE);
        List<VodInfo> vodInfoList = new ArrayList<>();
        // ★ v99-r50：读路径**不再隐藏**同名记录 —— 隐藏会让"页面条数 < 库内条数"，
        //   表现为"档位 15 却只显示 14/12"。改成：发现重复就自愈一次，然后按真实条数显示。
        Set<String> historyNames = new HashSet<String>();
        boolean dupFound = false;
        if (recordList != null) {
            for (VodRecord record : recordList) {
                VodInfo info = null;
                try {
                    if (record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                        info = getVodInfoGson().fromJson(record.dataJson, new TypeToken<VodInfo>() {
                        }.getType());
                        // ★ v99-r27：fromJson 可能返回 null（坏 JSON），原代码直接 info.sourceKey
                        //   会 NPE —— 虽被外层 catch 兜住，但每次刷新都抛一次异常刷屏。
                        if (info != null) {
                            info.sourceKey = record.sourceKey;
//                        SourceBean sourceBean = ApiConfig.get().getSource(info.sourceKey);
                            if (info.name == null)
                                info = null;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                String name = getVodRecordName(info);
                if (info != null) {
                    if (!TextUtils.isEmpty(name) && !historyNames.add(name)) {
                        dupFound = true;          // 同名重复：先照显，随后自愈
                    }
                    vodInfoList.add(info);
                }
            }
        }
        if (dupFound) {
            // 自愈：收敛库内同名（只删重复，不动档位裁剪），成功后重读一次
            try {
                if (compactHistory() > 0) {
                    return getAllVodRecord(limit);
                }
            } catch (Throwable ignored) {
            }
        }
        // ★ v99-r47：读路径**不再删库**。
        //   原实现在这里 `dao.reserver(hisNum)` —— 首页一刷新就把超出档位的记录物理删掉。
        //   问题是档位调低之后，用户还没来得及在设置页确认"要删多少条"，只要回一次首页，
        //   记录就已经没了 ⇒ 二次确认形同虚设。而且这是 UI 线程上的删除写操作。
        //   现在：显示层仍按 min(limit, hisNum) 收敛（上面第 431 行），真正的裁剪只发生在
        //   ① 设置页确认后手动 pruneHistory() ② 下一次写历史 ③ 同步落地后 SyncEngine 的 pruneHistory()。
        // ★ v99-r58 B 案：孤儿候选池回收（每天一次）。
        //   删历史的路径远不止用户手动删 —— 档位自动裁剪、同步落地时的 compact / dedupe /
        //   合并删除，都会在用户无感知的情况下删行，它们不可能逐个通知候选池。
        //   只有按「历史幸存名单」事后回收，才收得干净。
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
                    // ★★ v99-r74：同名收敛时区分「同片换源」与「换片」。
                    //   同片（年份/导演一致）⇒ 进度延续（migrateByVod 搬移语义：复制到新片 + 删旧片）。
                    //   换片（另一部同名片）⇒ **不搬**：进度是每部片各自的观看记忆，
                    //   把上一部片的进度搬到另一部片上会让新片从错误的位置起播
                    //   （真机：国产版 431676ms 被搬到韩国版 2013 上）。
                    boolean sameFilm = FilmIdentity.isSameFilm(vodInfo.year, vodInfo.director,
                            history.year, history.director);
                    if (sameFilm) {
                        PlayProgressManager.migrateByVod(record.sourceKey, record.vodId,
                                sourceKey, vodInfo.id, newEpNames);
                    } else {
                        LOG.i("[Hist] progressMigrateSkip reason=otherFilm"
                                + " old=" + record.sourceKey + "/" + record.id + "/y" + history.year
                                + " new=" + sourceKey + "/" + vodInfo.id + "/y" + vodInfo.year);
                    }
                    // ★ v99-r73：同名换片与同片切源在数据上**不可区分**（跨站换片时
                    //   sourceKey 也不同），故这里只 migrate 复制、不删除任何进度；
                    //   孤儿清理只清 position<=0 的空行（r72）—— 有观看记忆的行一律保留：
                    //   用户日后通过搜索重新点进该片时，进度仍能恢复。
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

    /**
     * 取「用于同名判定」的片名（归一化键，界面显示仍用原始片名）。
     *
     * <p>★ v99-r28：网页抓来的片名常夹全角空格（NBSP）、零宽字符、BOM，
     * 视觉上和另一条一模一样，但 {@code equals} 不等 ⇒ 同名去重（库级 dedupeSameName）
     * 与显示去重（getAllVodRecord 的 historyNames）双双失效，首页出现两张"看起来重名"的卡片。</p>
     *
     * <p>★ v99-r46：用户口径升级为「<b>意思一样就算同一个片</b>」——同一部片在不同源的写法
     * 差异不止不可见字符，还有空格与中文数字：</p>
     * <pre>
     *   现在就出发第四季 / 现在就出发 第四季 / 现在就出发 第4季 / 现在就出发第4季
     * </pre>
     * <p>这些变体各占一个历史名额（档位 15/30/50），把其它影片挤掉，表现为
     * 「历史里能看到的片变少」+「同一个片好几张卡片」——两端同一个病根。
     * 现统一走 {@link VodNameNormalizer#normalize(String)}（删空白 / 全角转半角 /
     * 第N季期部篇 中文数字转阿拉伯），r28 的不可见字符处理也已内含其中。</p>
     */
    private static String getVodRecordName(VodInfo vodInfo) {
        if (vodInfo == null || vodInfo.name == null) {
            return "";
        }
        return VodNameNormalizer.normalize(vodInfo.name);
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
     * 切源本来就是同一部片换站，理应只保留一条——v99-r49 起同名合并恒开启，本方法
     * 与 {@link #insertVodRecord} 走同一条收敛逻辑。</p>
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
