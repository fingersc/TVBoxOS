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