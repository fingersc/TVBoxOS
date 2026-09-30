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

    @Query("delete from play_progress")
    void deleteAll();
}