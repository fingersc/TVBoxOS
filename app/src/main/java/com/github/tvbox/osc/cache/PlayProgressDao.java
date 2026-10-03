package com.github.tvbox.osc.cache;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface PlayProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long save(PlayProgress progress);

    @Query("select * from play_progress where sourceKey = :sourceKey and vodId = :vodId and flag = :flag and playIndex = :playIndex and epName = :epName limit 1")
    PlayProgress get(String sourceKey, String vodId, String flag, int playIndex, String epName);

    @Query("delete from play_progress where sourceKey = :sourceKey and vodId = :vodId and flag = :flag and playIndex = :playIndex and epName = :epName")
    int delete(String sourceKey, String vodId, String flag, int playIndex, String epName);

    @Query("delete from play_progress where sourceKey = :sourceKey and vodId = :vodId")
    int deleteByVod(String sourceKey, String vodId);

    /**
     * 清理"孤儿"进度行：所属影片已不在观看历史（vodRecord）中。
     * 历史记录被裁剪（超出上限自动删除）、同名合并删除、或清空历史后，
     * 这些行已无法再被任何入口读取，只会长期占用数据库体积。
     *
     * @return 实际删除的行数
     */
    @Query("delete from play_progress where not exists (select 1 from vodRecord where vodRecord.sourceKey = play_progress.sourceKey and vodRecord.vodId = play_progress.vodId)")
    int deleteOrphaned();

    @Query("delete from play_progress")
    void deleteAll();
}