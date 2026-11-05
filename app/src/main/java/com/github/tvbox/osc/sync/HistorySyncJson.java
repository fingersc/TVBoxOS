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

    /**
     * ★ 协议 v4（P0-8）：把同步包的<b>精简字段</b>覆盖到本机已有的<b>完整</b> dataJson 上。
     *
     * <p>问题背景：{@code applyLocal} 原先无条件用「本机旧全文」替换合并结果里的 dataJson。
     * 当云端条目胜出（更新的 HLC）时，这样落库会得到「新的时间戳 + 旧的播放内容」——
     * 例如本机停在第 3 集、手机端已看到第 5 集，同步后本机仍显示第 3 集，而且下一次
     * 本机变脏时还会把旧内容再传回云端。</p>
     *
     * <p>修法：以本机全文为底（保住简介/演员等扩展字段），把同步包里的 10 个字段逐项覆盖上去。
     * 这样既不会降级本机数据，也不会丢掉对方更新的播放进度。</p>
     *
     * <p>任一解析失败时返回 {@code slim}（宁可丢扩展字段，不能丢播放进度）。</p>
     */
    public static String overlay(String full, String slim) {
        if (full == null || full.isEmpty()) {
            return slim;
        }
        if (slim == null || slim.isEmpty()) {
            return full;
        }
        if (full.equals(slim)) {
            return full;
        }
        try {
            JsonObject base = GSON.fromJson(full, JsonObject.class);
            JsonObject patch = GSON.fromJson(slim, JsonObject.class);
            if (base == null || patch == null) {
                return slim;
            }
            for (String k : KEEP) {
                if (patch.has(k) && !patch.get(k).isJsonNull()) {
                    base.add(k, patch.get(k));
                }
            }
            return base.toString();
        } catch (Throwable t) {
            return slim;
        }
    }
}
