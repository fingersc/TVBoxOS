package com.github.tvbox.osc.cache;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
@Dao
public interface VodRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(VodRecord record);

    // 本机「最近观看」按可信时间 updateTime 排序（HLC 只在同步合并/裁剪时使用；
    // 内容未变的重复观看不推进 HLC，否则会被误判成新内容而反复上传）。
    @Query("select * from vodRecord order by updateTime desc, id desc limit :size")
    List<VodRecord> getAll(int size);

    @Query("select hlcPhysical from vodRecord order by hlcPhysical desc, hlcCounter desc limit 1")
    Long maxHlcPhysical();

    @Query("select hlcCounter from vodRecord order by hlcPhysical desc, hlcCounter desc limit 1")
    Integer maxHlcCounter();

    @Query("select * from vodRecord where `sourceKey`=:sourceKey and `vodId`=:vodId")
    VodRecord getVodRecord(String sourceKey, String vodId);

    @Delete
    int delete(VodRecord record);

    @Query("select count(*) from vodRecord")
    int getCount();

    @Query("DELETE FROM vodRecord")
    void deleteAll();

    /**
     * 保留最新指定条数, 其他删除.
     * @param size 保留条数
     * @return
     */
    @Query("DELETE FROM vodRecord where id NOT IN (SELECT id FROM vodRecord ORDER BY updateTime desc, id desc LIMIT :size)")
    int reserver(int size);
}
