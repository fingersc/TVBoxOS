package com.github.tvbox.osc.cache;

import android.text.TextUtils;

import com.github.tvbox.osc.data.AppDataManager;

/**
 * 播放进度的列存储门面：代替原 CacheManager + MD5(拼接串) 的进度存储。
 * 所有字段经 null 规范化（null -> ""），保证主键稳定；规范化只在这里做一次。
 */
public class PlayProgressManager {

    private static String s(String v) {
        return v == null ? "" : v;
    }

    /** 保存/覆盖某一集的进度；position <= 0 等价于清除该集记忆（对应完成播放写 0 的旧语义） */
    public static void save(String sourceKey, String vodId, String flag, int playIndex, String epName, long position) {
        try {
            if (TextUtils.isEmpty(vodId) || TextUtils.isEmpty(epName)) {
                return;
            }
            PlayProgressDao dao = AppDataManager.get().getPlayProgressDao();
            if (position <= 0) {
                dao.delete(s(sourceKey), s(vodId), s(flag), playIndex, s(epName));
                return;
            }
            PlayProgress p = new PlayProgress();
            p.sourceKey = s(sourceKey);
            p.vodId = s(vodId);
            p.flag = s(flag);
            p.playIndex = playIndex;
            p.epName = s(epName);
            p.position = position;
            p.updateTime = System.currentTimeMillis();
            dao.save(p);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 读取某一集的进度；无记录返回 0 */
    public static long get(String sourceKey, String vodId, String flag, int playIndex, String epName) {
        try {
            PlayProgress p = AppDataManager.get().getPlayProgressDao()
                    .get(s(sourceKey), s(vodId), s(flag), playIndex, s(epName));
            return p == null ? 0 : p.position;
        } catch (Throwable th) {
            th.printStackTrace();
            return 0;
        }
    }

    public static void delete(String sourceKey, String vodId, String flag, int playIndex, String epName) {
        try {
            AppDataManager.get().getPlayProgressDao().delete(s(sourceKey), s(vodId), s(flag), playIndex, s(epName));
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 把某部影片在「旧源」下的<b>全部集</b>进度整体搬到「新源」，然后清除旧源的行。
     *
     * <p><b>为什么需要：</b>换源时旧源的历史记录会被删除（同名合并 / 超限裁剪），
     * 旧源名下的进度行随即变成读不到的孤儿。而详情回调里的迁移只搬"当前正在播的那一集"，
     * 其余各集的播放位置与最后观看时间会随旧源行一起被清掉——表现为换源后
     * 「以前看过的集，时间记忆全没了」。</p>
     *
     * <p>搬移规则：
     * <ol>
     *   <li>集名在新源里存在（{@code toEpNames} 精确包含）→ 按原样搬到同一集名；</li>
     *   <li>集名对新源不适用（跨源命名不同，如「第5集」↔「20260904」）→ 用
     *       {@link com.github.tvbox.osc.util.EpisodeNameMatcher#findIndex(String, java.util.List)}
     *       在新源集名里找同集，搬到匹配到的集名上；</li>
     *   <li>新源该集已有进度 → <b>不覆盖</b>，保留新源自己记的值；</li>
     *   <li>实在匹配不上 → 该行随旧源一起清除（留着也永远读不到）。</li>
     * </ol></p>
     *
     * @param toEpNames 新源的全部集名（用于跨源集名匹配）；传 null 则只做集名精确搬移
     * @return 实际搬移的行数
     */
    public static int migrateByVod(String fromSourceKey, String fromVodId,
                                   String toSourceKey, String toVodId,
                                   java.util.List<String> toEpNames) {
        try {
            if (TextUtils.isEmpty(fromSourceKey) || TextUtils.isEmpty(fromVodId)
                    || TextUtils.isEmpty(toSourceKey) || TextUtils.isEmpty(toVodId)) {
                return 0;
            }
            if (TextUtils.equals(fromSourceKey, toSourceKey)
                    && TextUtils.equals(fromVodId, toVodId)) {
                return 0;
            }
            PlayProgressDao dao = AppDataManager.get().getPlayProgressDao();
            java.util.List<PlayProgress> rows = dao.getByVod(s(fromSourceKey), s(fromVodId));
            if (rows == null || rows.isEmpty()) {
                return 0;
            }
            int moved = 0;
            for (PlayProgress row : rows) {
                if (row == null || row.position <= 0) {
                    continue;
                }
                String targetEp = row.epName;
                boolean exact = toEpNames != null && toEpNames.contains(targetEp);
                if (!exact && toEpNames != null && !toEpNames.isEmpty()) {
                    int idx = com.github.tvbox.osc.util.EpisodeNameMatcher.findIndex(targetEp, toEpNames);
                    if (idx >= 0) {
                        targetEp = toEpNames.get(idx);
                    }
                }
                // 新源该集已有记忆则以新源为准，不覆盖
                if (get(toSourceKey, toVodId, row.flag, row.playIndex, targetEp) > 0) {
                    continue;
                }
                save(toSourceKey, toVodId, row.flag, row.playIndex, targetEp, row.position);
                moved++;
            }
            dao.deleteByVod(s(fromSourceKey), s(fromVodId));
            return moved;
        } catch (Throwable th) {
            th.printStackTrace();
            return 0;
        }
    }

    /** 删除整部影片所有集的进度（配合删除历史） */
    public static void deleteByVod(String sourceKey, String vodId) {
        try {
            AppDataManager.get().getPlayProgressDao().deleteByVod(s(sourceKey), s(vodId));
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public static void deleteAll() {
        try {
            AppDataManager.get().getPlayProgressDao().deleteAll();
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 清理"孤儿"进度：所属影片已不在观看历史（vodRecord 表）中，因此永远无法再被读到。
     * <p>产生途径有三：历史条数超上限被 {@code VodRecordDao.reserver} 自动裁剪、
     * 开启同名合并时旧源记录被删除、用户清空历史。前两者此前只删历史不删进度，
     * 残留会随使用时间单调增长（每部片每集一行，永不释放）。</p>
     *
     * @return 实际删除的行数
     */
    public static int deleteOrphaned() {
        try {
            return AppDataManager.get().getPlayProgressDao().deleteOrphaned();
        } catch (Throwable th) {
            th.printStackTrace();
            return 0;
        }
    }
}