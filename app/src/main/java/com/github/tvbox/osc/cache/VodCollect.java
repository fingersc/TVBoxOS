package com.github.tvbox.osc.cache;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "vodCollect")
public class VodCollect implements Serializable {
    @PrimaryKey(autoGenerate = true)
    private int id;
    @ColumnInfo(name = "vodId")
    public String vodId;
    @ColumnInfo(name = "updateTime")
    public long updateTime;
    @ColumnInfo(name = "sourceKey")
    public String sourceKey;
    @ColumnInfo(name = "name")
    public String name;
    @ColumnInfo(name = "pic")
    public String pic;
    /** HLC 物理分量（毫秒）；0 表示旧数据，读取时回落到 updateTime。 */
    @ColumnInfo(name = "hlcPhysical", defaultValue = "0")
    public long hlcPhysical;
    /** HLC 逻辑计数。 */
    @ColumnInfo(name = "hlcCounter", defaultValue = "0")
    public int hlcCounter;
    /** 最后写入该条目的设备 id（HLC 平局裁决）。 */
    @ColumnInfo(name = "dev")
    public String dev;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}