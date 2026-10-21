package com.github.tvbox.osc.sync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * 历史记录在同步包里的精简格式（S2 方案）。
 *
 * <p>历史记录的 dataJson 原本是整包 VodInfo（序列化时已排除 seriesMap / seriesFlags）。
 * 跨设备真正会被读取的只有下面 10 个字段：历史卡片（name/pic/note）、进详情页（id/sourceKey）、
 * 详情页恢复上次位置（playFlag/playIndex/playNote/playerCfg/reverseSort）。
 * 简介、演员、分类、年份等展示字段在详情页会重新从源站拉取，没必要跟着同步。</p>
 *
 * <p>只作用于同步包：本机数据库仍保存完整 VodInfo，本机 UI 不受影响；
 * 接收端存的是精简版（字段够用），再次上传时不会"长胖"。</p>
 */
public final class HistorySyncJson {

    /** 跨设备需要保留的字段（顺序无关）。 */
    static final String[] KEEP = {
            "id", "sourceKey", "name", "pic", "note",
            "playFlag", "playIndex", "playNote", "playerCfg", "reverseSort",
    };

    private static final Gson GSON = new Gson();

    private HistorySyncJson() {
    }

    /** 精简 dataJson；解析失败原样返回（宁可多传，不要丢数据）。 */
    public static String slim(String dataJson) {
        if (dataJson == null || dataJson.isEmpty()) {
            return dataJson;
        }
        try {
            JsonObject src = GSON.fromJson(dataJson, JsonObject.class);
            if (src == null) {
                return dataJson;
            }
            JsonObject out = new JsonObject();
            for (String k : KEEP) {
                if (src.has(k) && !src.get(k).isJsonNull()) {
                    out.add(k, src.get(k));
                }
            }
            // 关键字段缺失说明不是我们的历史格式，保守起见原样返回
            if (!out.has("id") || !out.has("name")) {
                return dataJson;
            }
            return out.toString();
        } catch (Throwable t) {
            return dataJson;
        }
    }
}
