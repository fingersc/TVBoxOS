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

    /**
     * ★ v4.2.5：全表最大的可信更新时间（「最近观看」的排序键）。
     *
     * <p>用于判断「这条记录是不是已经排在第一位」—— 已经是第一位时再抬时间不会改变
     * 任何顺序，只会让 history.bin 变掉、白传一次。</p>
     */
    @Query("select max(updateTime) from vodRecord")
    Long maxUpdateTime();

    @Query("select * from vodRecord where `sourceKey`=:sourceKey and `vodId`=:vodId")
    VodRecord getVodRecord(String sourceKey, String vodId);

    /**
     * v4.3 混合版：只推进某条历史的 HLC/可信更新时间，用于「同集播放也要把该片顶到最近观看第一位」。
     * 单行 UPDATE，不改 dataJson、不触发内容比对；是否上传由调用方决定（onStop/启动对账才标脏）。
     */
    @Query("UPDATE vodRecord SET hlcPhysical=:physical, hlcCounter=:counter, dev=:dev, updateTime=:updateTime "
            + "WHERE `sourceKey`=:sourceKey AND `vodId`=:vodId")
    int touchHlc(String sourceKey, String vodId, long physical, int counter, String dev, long updateTime);

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
