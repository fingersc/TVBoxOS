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

    /**
     * 自动降级的候选阶梯，顺序即用户指定的 EXO → IJK硬解 → IJK软解。
     *
     * <p>每项 = {@code {pl, ijk}}，其中 ijk：0=硬解、1=软解。</p>
     *
     * <p>降级时从这个序列里**按序号跳过起播组合自身**、依次往后取候选
     * （见 {@link #nextFailoverTarget}），所以无论从哪儿起播，下一个候选始终遵循
     * 「先 EXO、再硬解、最后才软解」这一条顺序。旧实现是按起播组合分三张表分别推的，
     * 同一个起点在不同表里的后续顺序并不一致。</p>
     */
    private static final int[][] FAILOVER_LADDER = {
            {2, 0},   // 0：EXO（MediaCodec）
            {1, 0},   // 1：IJK 硬解
            {1, 1},   // 2：IJK 软解
    };

    JSONObject defaultPlayerConfig = new JSONObject();
    JSONObject currentPlayerConfig;
    /** 本频道起播时所处的阶梯位置，自动降级以此为准（见 {@link #nextFailoverTarget}）。 */
    private int failoverBaseIndex = 0;

    public void init(VideoView videoView) {
        resetDefaultOnce();
        try {
            // ★ 直播的播放器只认用户自己在直播里选过的值 LIVE_PLAY_TYPE，没有就用 EXO。
            //   不再回退到点播的 PLAY_TYPE —— 那是另一回事：在点播里为了某路源选了 IJK，
            //   不该把直播也拖下水。也不再由直播源配置指定（原 ApiConfig 每次加载配置都会
            //   用 PLAY_TYPE / 配置里的 playerType 覆盖这里，把用户的手动选择冲掉）。
            int pl = Hawk.get(HawkConfig.LIVE_PLAY_TYPE, 2);
            if (pl == 0) {
                pl = 2;
            }
            defaultPlayerConfig.put("pl", pl);
            defaultPlayerConfig.put("ijk", Hawk.get(HawkConfig.IJK_CODEC, IJK_HARD));
            defaultPlayerConfig.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 1));
            defaultPlayerConfig.put("sc", Hawk.get(HawkConfig.LIVE_PLAY_SCALE, 0));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        getDefaultLiveChannelPlayer(videoView);
        LOG.live("livePlayerInit pl=" + defaultPlayerConfig.optInt("pl", 2)
                + " ijk=" + defaultPlayerConfig.optString("ijk", IJK_HARD)
                + " render=" + defaultPlayerConfig.optInt("pr", 1));
    }

    /**
     * v57 一次性复位：把直播的默认播放引擎拉回 EXO。
     *
     * <p>旧实现里 LIVE_PLAY_TYPE 会被两种途径冲掉 —— ① {@code ApiConfig} 每次加载直播配置时
     * 用点播 PLAY_TYPE 覆盖；② 直播源配置里带着 {@code playerType}。于是存下来的 1(IJK) 根本
     * 分不清是用户亲手选的，还是被传导/被配置强塞的（模拟器上很常见：为了能播把点播设成
     * IJK+软解，直播就跟着变成了 IJK 软解）。</p>
     *
     * <p>这里只做**一次**，之后用户在直播里手动选的值照常写入并被尊重，不会再被覆盖。</p>
     */
    private void resetDefaultOnce() {
        if (Hawk.get(HawkConfig.LIVE_PLAY_TYPE_RESET_EXO_V57, false)) {
            return;
        }
        Hawk.put(HawkConfig.LIVE_PLAY_TYPE_RESET_EXO_V57, true);
        Hawk.put(HawkConfig.LIVE_PLAY_TYPE, 2);
        LOG.live("livePlayerDefaultReset -> pl=2 (exo)");
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

    /**
     * 当前组合在设置面板里对应的下标：0=EXO、1=IJK硬解、2=IJK软解。
     *
     * <p>必须与 {@code ApiConfig#initLiveSettings} 里「播放解码」那一项的排列完全一致，
     * 否则面板高亮和实际值会错位。</p>
     */
    public int getLivePlayerType() {
        try {
            int playerType = currentPlayerConfig.getInt("pl");
            String ijkCodec = currentPlayerConfig.getString("ijk");
            if (playerType == 2) {
                return 0;
            }
            return IJK_HARD.equals(ijkCodec) ? 1 : 2;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public int getLivePlayerScale() {
        try {
            return currentPlayerConfig.getInt("sc");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * 用户在设置面板里选了某一项：0=EXO、1=IJK硬解、2=IJK软解。
     *
     * <p>下标顺序与 {@link #getLivePlayerType}、{@code ApiConfig} 的面板列表三者同步。</p>
     */
    public void changeLivePlayerType(VideoView videoView, int playerType) {
        JSONObject playerConfig = currentPlayerConfig;
        try {
            switch (playerType) {
                case 0:
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
                case 1:
                    playerConfig.put("pl", 1);
                    playerConfig.put("ijk", IJK_HARD);
                    break;
                case 2:
                    playerConfig.put("pl", 1);
                    playerConfig.put("ijk", IJK_SOFT);
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
     * 直播起播失败后的自动降级，顺序固定为 EXO → IJK硬解 → IJK软解
     * （见 {@link #FAILOVER_LADDER}）：从序列里跳过本次起播所用的那一档，依次往后取。
     *
     * <pre>
     *   起播 exo     → ijk硬解 → ijk软解
     *   起播 ijk硬解 → exo     → ijk软解
     *   起播 ijk软解 → exo     → ijk硬解
     * </pre>
     *
     * <p><b>为什么按「起播组合」而不是「当前组合」推导：</b>如果每次都看当前状态决定
     * 下一步，链路上会出现「ijk软解 → exo → ijk软解」这种原地打转（第二次降级又回
     * 到已经失败过的组合）。所以进入频道时记下起播位置 {@link #failoverBaseIndex}，
     * 之后每一步都基于它取候选，保证两次降级拿到的是两个不同的候选。</p>
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
                    + " base=" + failoverBaseIndex);
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
        LOG.live("liveSwitchPlayer step=" + step + " base=" + failoverBaseIndex
                + " -> " + nextPlayerType + "(" + nextIjk + ")");

        currentPlayerConfig = playerConfig;
        return true;
    }

    /**
     * 第 step 次降级的目标：沿 {@link #FAILOVER_LADDER} 跳过起播那一档往后数第 step 个；
     * 没有更多候选返回 null。
     */
    private int[] nextFailoverTarget(int step) {
        int nth = 0;
        for (int i = 0; i < FAILOVER_LADDER.length; i++) {
            if (i == failoverBaseIndex) {
                continue;
            }
            if (nth == step) {
                return FAILOVER_LADDER[i];
            }
            nth++;
        }
        return null;
    }

    /** 记下本频道的起播位置，作为自动降级的推导基准。 */
    private void rememberFailoverBase() {
        if (currentPlayerConfig == null) {
            return;
        }
        failoverBaseIndex = ladderIndexOf(currentPlayerConfig.optInt("pl", 2),
                currentPlayerConfig.optString("ijk", IJK_HARD));
    }

    /** 某个组合在阶梯里的下标；不在阶梯上（外部塞进来的配置）返回 -1，此时候选从 EXO 起。 */
    private int ladderIndexOf(int pl, String ijk) {
        boolean soft = !IJK_HARD.equals(ijk);
        for (int i = 0; i < FAILOVER_LADDER.length; i++) {
            if (FAILOVER_LADDER[i][0] == pl && (FAILOVER_LADDER[i][1] == 1) == soft) {
                return i;
            }
        }
        return -1;
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
