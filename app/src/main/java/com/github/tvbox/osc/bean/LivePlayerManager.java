package com.github.tvbox.osc.bean;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;
import com.orhanobut.hawk.Hawk;

import org.json.JSONException;
import org.json.JSONObject;

import xyz.doikki.videoplayer.player.VideoView;

public class LivePlayerManager {
    /** IJK 硬解码（走 mediacodec）；与 ApiConfig 内置解码组的组名保持一致。 */
    private static final String IJK_HARD = "硬解码";
    private static final String IJK_SOFT = "软解码";

    JSONObject defaultPlayerConfig = new JSONObject();
    JSONObject currentPlayerConfig;
    /** 本频道起播时所用的播放器组合，自动降级以此为准（见 {@link #nextFailoverTarget}）。 */
    private int failoverBasePl = 2;
    private String failoverBaseIjk = IJK_HARD;

    public void init(VideoView videoView) {
        try {
            defaultPlayerConfig.put("pl", Hawk.get(HawkConfig.LIVE_PLAY_TYPE, Hawk.get(HawkConfig.PLAY_TYPE, 2)));
            if (defaultPlayerConfig.optInt("pl", 2) == 0) {
                defaultPlayerConfig.put("pl", 2);
            }
            defaultPlayerConfig.put("ijk", Hawk.get(HawkConfig.IJK_CODEC, IJK_HARD));
            defaultPlayerConfig.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 0));
            defaultPlayerConfig.put("sc", Hawk.get(HawkConfig.LIVE_PLAY_SCALE, 0));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        getDefaultLiveChannelPlayer(videoView);
        LOG.live("livePlayerInit pl=" + defaultPlayerConfig.optInt("pl", 2)
                + " ijk=" + defaultPlayerConfig.optString("ijk", IJK_HARD)
                + " render=" + defaultPlayerConfig.optInt("pr", 0));
    }

    public void getDefaultLiveChannelPlayer(VideoView videoView) {
        PlayerHelper.updateCfg(videoView, defaultPlayerConfig);
        try {
            currentPlayerConfig = new JSONObject(defaultPlayerConfig.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        rememberFailoverBase();
    }

    public int getLivePlayerType() {
        int playerTypeIndex = 2;
        try {
            int playerType = currentPlayerConfig.getInt("pl");
            String ijkCodec = currentPlayerConfig.getString("ijk");
            switch (playerType) {
                case 1:
                    if (IJK_HARD.equals(ijkCodec))
                        playerTypeIndex = 0;
                    else
                        playerTypeIndex = 1;
                    break;
                case 2:
                    playerTypeIndex = 2;
                    break;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return playerTypeIndex;
    }

    public int getLivePlayerScale() {
        try {
            return currentPlayerConfig.getInt("sc");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public void changeLivePlayerType(VideoView videoView, int playerType) {
        JSONObject playerConfig = currentPlayerConfig;
        try {
            switch (playerType) {
                case 0:
                    playerConfig.put("pl", 1);
                    playerConfig.put("ijk", IJK_HARD);
                    break;
                case 1:
                    playerConfig.put("pl", 1);
                    playerConfig.put("ijk", IJK_SOFT);
                    break;
                case 2:
                    // ★ 选 EXO 时不要再动 ijk 字段。
                    //
                    // 历史实现在这里把 ijk 写成「软解码」并写进 Hawk，两个后果：
                    //   1) IJK_CODEC 是全局设置 —— 在直播里选一次 EXO，点播的解码
                    //      方式也被悄悄改成了软解；
                    //   2) 之后自动降级切回 IJK 时读到的就是软解，硬解被关掉，
                    //      而用户从头到尾没表达过要软解。
                    // EXO 走的是 MediaCodec，跟 ijk 这个字段没关系，保持原值即可。
                    playerConfig.put("pl", 2);
                    break;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        PlayerHelper.updateCfg(videoView, playerConfig);

        try {
            int pl = playerConfig.getInt("pl");
            defaultPlayerConfig.put("pl", pl);
            Hawk.put(HawkConfig.LIVE_PLAY_TYPE, pl);
            // 只有真的在 IJK 上时才把解码方式落到全局设置，避免无关的软解污染
            if (pl == 1) {
                String ijk = playerConfig.optString("ijk", IJK_HARD);
                defaultPlayerConfig.put("ijk", ijk);
                Hawk.put(HawkConfig.IJK_CODEC, ijk);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        LOG.live("livePlayerManual type=" + playerType + " -> pl=" + playerConfig.optInt("pl", 2)
                + " ijk=" + playerConfig.optString("ijk", IJK_HARD));

        currentPlayerConfig = playerConfig;
    }

    /**
     * 直播起播失败后的自动降级，顺序遵循「硬解优先」。
     *
     * <p>两个引擎都能走硬解（IJK 靠 mediacodec 选项，EXO 用 MediaCodec 渲染器），
     * 所以「换一个引擎」本身就是一次有价值的重试 —— 在不牺牲硬解的前提下换个实现。
     * 软解只在硬解都试过之后才动用，是兜底而不是默认。</p>
     *
     * <pre>
     *   起播 ijk硬解 → exo(硬解) → ijk软解
     *   起播 ijk软解 → ijk硬解   → exo(硬解)
     *   起播 exo     → ijk硬解   → ijk软解
     * </pre>
     *
     * <p><b>为什么按「起播组合」而不是「当前组合」推导：</b>如果每次都看当前状态决定
     * 下一步，链路上会出现「ijk软解 → exo → ijk软解」这种原地打转（第二次降级又回
     * 到已经失败过的组合）。所以进入频道时记下起播组合 {@link #failoverBasePl}，
     * 之后每一步都基于它查表，保证两次降级拿到的是两个不同的候选。</p>
     *
     * <p>另外：自动降级<b>不写回 Hawk</b>。它只是针对这一路源的临时尝试，不该把
     * 用户在设置里选定的解码方式改掉（尤其不该在失败重试中把硬解改成软解）。</p>
     *
     * @param step 第几次自动降级（0 起）；由调用方计数并设上限
     */
    public boolean switchLivePlayer(VideoView videoView, int step) {
        JSONObject playerConfig = currentPlayerConfig;
        if (playerConfig == null) {
            LOG.live("liveSwitchPlayer: skip empty player config");
            return false;
        }
        // step==0 表示这是一条新的降级链路（换台 / 手动改过播放器 / 首次起播），
        // 基准就取"当前配置"；step>0 时沿用链路起点的基准，避免推导漂移。
        if (step <= 0) {
            rememberFailoverBase();
        }
        int[] target = nextFailoverTarget(step);
        if (target == null) {
            LOG.live("liveSwitchPlayer: no more candidate step=" + step
                    + " base=" + failoverBasePl + "(" + failoverBaseIjk + ")");
            return false;
        }
        int nextPlayerType = target[0];
        String nextIjk = target[1] == 0 ? IJK_HARD : IJK_SOFT;
        try {
            playerConfig.put("pl", nextPlayerType);
            playerConfig.put("ijk", nextIjk);
        } catch (JSONException e) {
            LOG.live("liveSwitchPlayer error: " + e.getMessage());
            return false;
        }
        PlayerHelper.updateCfg(videoView, playerConfig);
        LOG.live("liveSwitchPlayer step=" + step + " base=" + failoverBasePl + "(" + failoverBaseIjk
                + ") -> " + nextPlayerType + "(" + nextIjk + ")");

        currentPlayerConfig = playerConfig;
        return true;
    }

    /** 第 step 次降级的目标：{pl, ijk(0=硬解 1=软解)}；没有更多候选返回 null。 */
    private int[] nextFailoverTarget(int step) {
        if (failoverBasePl == 1 && IJK_HARD.equals(failoverBaseIjk)) {
            // ijk硬解 → exo(硬解) → ijk软解
            if (step == 0) return new int[]{2, 0};
            if (step == 1) return new int[]{1, 1};
            return null;
        }
        if (failoverBasePl == 1) {
            // ijk软解 → ijk硬解（硬解优先）→ exo
            if (step == 0) return new int[]{1, 0};
            if (step == 1) return new int[]{2, 1};
            return null;
        }
        if (failoverBasePl == 2) {
            // exo → ijk硬解 → ijk软解
            if (step == 0) return new int[]{1, 0};
            if (step == 1) return new int[]{1, 1};
            return null;
        }
        return null;
    }

    /** 记下本频道的起播组合，作为自动降级的推导基准。 */
    private void rememberFailoverBase() {
        if (currentPlayerConfig == null) {
            return;
        }
        failoverBasePl = currentPlayerConfig.optInt("pl", 2);
        failoverBaseIjk = currentPlayerConfig.optString("ijk", IJK_HARD);
    }

    public void changeLivePlayerScale(@NonNull VideoView videoView, int playerScale){
        videoView.setScreenScaleType(playerScale);
        Hawk.put(HawkConfig.LIVE_PLAY_SCALE, playerScale);

        JSONObject playerConfig = currentPlayerConfig;
        try {
            playerConfig.put("sc", playerScale);
            defaultPlayerConfig.put("sc", playerScale);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        currentPlayerConfig = playerConfig;
    }
}
