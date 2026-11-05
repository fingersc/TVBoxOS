package com.github.tvbox.osc.cache;

import androidx.annotation.NonNull;
import androidx.room.Entity;

import java.io.Serializable;

/**
 * 播放进度表：一行对应一集。
 * 主键 = (源, 影片, 线路, 集索引, 集名)，等价于旧 progressKey 拼接串的结构化形式。
 */
@Entity(tableName = "play_progress",
        primaryKeys = {"sourceKey", "vodId", "flag", "playIndex", "epName"})
public class PlayProgress implements Serializable {
    @NonNull
    public String sourceKey;
    @NonNull
    public String vodId;
    @NonNull
    public String flag;
    public int playIndex;
    @NonNull
    public String epName;
    public long position;      // 毫秒
    public long updateTime;    // 最后观看时间
    /** HLC 物理分量（毫秒）；0 表示旧数据，读取时回落到 updateTime。 */
    @androidx.room.ColumnInfo(name = "hlcPhysical", defaultValue = "0")
    public long hlcPhysical;
    /** HLC 逻辑计数。 */
    @androidx.room.ColumnInfo(name = "hlcCounter", defaultValue = "0")
    public int hlcCounter;
    /** 最后写入该条目的设备 id（HLC 平局裁决）。 */
    @androidx.room.ColumnInfo(name = "dev")
    public String dev;
}