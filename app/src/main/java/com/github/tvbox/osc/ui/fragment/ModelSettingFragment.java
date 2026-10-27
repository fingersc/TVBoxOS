package com.github.tvbox.osc.ui.fragment;

import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.provider.OpenableColumns;
import android.os.Handler;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.api.DanmakuApi;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.LocalFileActivity;
import com.github.tvbox.osc.ui.adapter.ApiHistoryDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.AboutDialog;
import com.github.tvbox.osc.ui.dialog.ApiDialog;
import com.github.tvbox.osc.ui.dialog.ApiHistoryDialog;
import com.github.tvbox.osc.ui.dialog.BackupDialog;
import com.github.tvbox.osc.ui.dialog.DanmuApiDialog;
import com.github.tvbox.osc.ui.dialog.SearchRemoteTvDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.ui.dialog.XWalkInitDialog;
import com.github.tvbox.osc.sync.HistorySyncManager;
import com.github.tvbox.osc.sync.SyncScope;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.SourceQualityStore;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.FileCallback;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;

import org.greenrobot.eventbus.EventBus;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.HttpUrl;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class ModelSettingFragment extends BaseLazyFragment {
    private static final int REQUEST_LOCAL_CONFIG = 1001;
    private TextView tvMediaCodec;
    private TextView tvParseWebView;
    private TextView tvPlay;
    private TextView tvRender;
    private TextView tvScale;
    private TextView tvApi;
    private TextView tvApiLine;
    private View llApi;
    private View llApiHistory;
    private View llApiLine;
    private TextView tvHomeApi;
    private TextView tvDns;
    private TextView tvHomeRec;
    private TextView tvHistoryNum;
    private TextView tvSearchView;
    private TextView tvShowPreviewText;
    private TextView tvFastSearchText;
    private TextView tvm3u8AdText;
    private TextView tvAutoSwitchLineText;
    private TextView tvRecStyleText;
    private TextView tvIjkCachePlay;
    private TextView tvHomeDefaultShow;
    private ApiDialog apiDialog;
    private boolean selectLocalLive;
    private TextView tvDanmuOpenText;
    private TextView tvDanmuApiText;

    // ================== v99-r49：设置页草稿制 ==================
    // 用户在本页的所有改动先落在 draft 里，保存确认后才写回 Hawk。
    // 未保存 ⇒ 不生效、不上传（Hawk 始终是"已保存值"，同步层看不到差异）。
    private final java.util.LinkedHashMap<String, Object> draft = new java.util.LinkedHashMap<>();
    /** API 类改动是唯一"立即生效"的例外（要让候选列表立刻是新 API 的），这些键要能回滚。 */
    private final java.util.LinkedHashMap<String, Object> apiSnapshot = new java.util.LinkedHashMap<>();
    private final java.util.HashSet<String> apiAbsent = new java.util.HashSet<>();
    /** 保存后自动返回首页时置位，避免"未保存提醒"重复弹。 */
    private boolean suppressLeavePrompt = false;
    /** 跳转其它页面（导入本地配置）期间不丢弃草稿。 */
    private boolean leavingForResult = false;
    /** 供 SettingActivity 的返回键转发使用（Fragment 生命周期内有效）。 */
    public static ModelSettingFragment leaveGuard;

    /** parseJson 会改写的键：API 改动要能整体回滚。 */
    private static final String[] API_DEPENDENT_KEYS = {
            HawkConfig.API_URL, HawkConfig.LIVE_API_URL, HawkConfig.API_LINE_SOURCE,
            HawkConfig.API_LINE_LIST, HawkConfig.HOME_API, HawkConfig.DOH_URL,
            HawkConfig.DOH_JSON, HawkConfig.EPG_URL, HawkConfig.LIVE_WEB_HEADER,
            HawkConfig.LIVE_CONNECT_TIMEOUT,
    };

    /** 键 → 中文名（保存/离开弹窗的改动清单用）。 */
    private static final java.util.LinkedHashMap<String, String> SETTING_NAMES = new java.util.LinkedHashMap<>();
    static {
        SETTING_NAMES.put(HawkConfig.API_URL, "配置地址");
        SETTING_NAMES.put(HawkConfig.LIVE_API_URL, "直播接口");
        SETTING_NAMES.put(HawkConfig.HOME_API, "首页站源");
        SETTING_NAMES.put(HawkConfig.HOME_REC, "首页推荐");
        SETTING_NAMES.put(HawkConfig.HOME_REC_STYLE, "首页多行");
        SETTING_NAMES.put(HawkConfig.SEARCH_VIEW, "搜索展示");
        SETTING_NAMES.put(HawkConfig.FAST_SEARCH_MODE, "聚合搜索");
        SETTING_NAMES.put(HawkConfig.DEFAULT_LOAD_LIVE, "下次进入");
        SETTING_NAMES.put(HawkConfig.PLAY_TYPE, "播放器");
        SETTING_NAMES.put(HawkConfig.IJK_CODEC, "IJK解码方式");
        SETTING_NAMES.put(HawkConfig.PLAY_RENDER, "渲染方式");
        SETTING_NAMES.put(HawkConfig.PLAY_SCALE, "画面缩放");
        SETTING_NAMES.put(HawkConfig.SHOW_PREVIEW, "窗口预览");
        SETTING_NAMES.put(HawkConfig.M3U8_PURIFY, "去广告");
        SETTING_NAMES.put(HawkConfig.AUTO_SWITCH_LINE, "自动换线");
        SETTING_NAMES.put(HawkConfig.DOH_URL, "安全DNS");
        SETTING_NAMES.put(HawkConfig.PARSE_WEBVIEW, "嗅探Webview");
        SETTING_NAMES.put(HawkConfig.DANMU_OPEN, "弹幕");
        SETTING_NAMES.put(HawkConfig.DANMU_API, "弹幕地址");
        SETTING_NAMES.put(HawkConfig.IJK_CACHE_PLAY, "IJK缓存");
        SETTING_NAMES.put(HawkConfig.HISTORY_NUM, "历史记录");
    }

    public static ModelSettingFragment newInstance() {
        return new ModelSettingFragment().setArguments();
    }

    public ModelSettingFragment setArguments() {
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_model;
    }

    @Override
    public void onResume() {
        super.onResume();
        leaveGuard = this;
        leavingForResult = false;
    }

    /**
     * ★ v99-r49：按 Home 键 / 被系统回收 = 未保存的草稿直接丢弃（用户口径）。
     * 跳转其它页面（导入本地配置）期间不清，用 leavingForResult 挡住。
     */
    @Override
    public void onStop() {
        super.onStop();
        if (leaveGuard == this) {
            leaveGuard = null;
        }
        if (!leavingForResult && !suppressLeavePrompt && !draft.isEmpty()) {
            discardDraft();
        }
    }

    /** 升级后一次性提示：同名合并已固定开启（旧键为关闭态的用户会看到历史被合并）。 */
    private void notifyLegacyMergeOnce() {
        try {
            boolean hasLegacy = Hawk.contains(HawkConfig.LEGACY_HISTORY_MERGE);
            boolean legacy = hasLegacy && Hawk.get(HawkConfig.LEGACY_HISTORY_MERGE, true);
            if (hasLegacy && !legacy) {
                Toast.makeText(mContext,
                        "同名合并已固定开启：历史里重复的同一部影片已合并为一条（播放进度已迁移）",
                        Toast.LENGTH_LONG).show();
            }
            if (hasLegacy) {
                Hawk.delete(HawkConfig.LEGACY_HISTORY_MERGE);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void init() {
        notifyLegacyMergeOnce();
        captureBaseline();
        refreshUi();
        findViewById(R.id.llParseWebVew).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean useSystem = !cur(HawkConfig.PARSE_WEBVIEW, true);
                draftPut(HawkConfig.PARSE_WEBVIEW, useSystem);
                tvParseWebView.setText(cur(HawkConfig.PARSE_WEBVIEW, true) ? "系统自带" : "XWalkView");
                if (!useSystem) {
                    Toast.makeText(mContext, "注意: XWalkView只适用于部分低Android版本，Android5.0以上推荐使用系统自带", Toast.LENGTH_LONG).show();
                    XWalkInitDialog dialog = new XWalkInitDialog(mContext);
                    dialog.setOnListener(new XWalkInitDialog.OnListener() {
                        @Override
                        public void onchange() {
                        }
                    });
                    dialog.show();
                }
            }
        });
        findViewById(R.id.llBackup).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                BackupDialog dialog = new BackupDialog(mActivity);
                dialog.show();
            }
        });
        findViewById(R.id.llAbout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                AboutDialog dialog = new AboutDialog(mActivity);
                dialog.show();
            }
        });
        findViewById(R.id.llWp).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                if (!ApiConfig.get().wallpaper.isEmpty())
                    OkGo.<File>get(ApiConfig.get().wallpaper).execute(new FileCallback(requireActivity().getFilesDir().getAbsolutePath(), "wp") {
                        @Override
                        public void onSuccess(Response<File> response) {
                            ((BaseActivity) requireActivity()).changeWallpaper(true);
                        }

                        @Override
                        public void onError(Response<File> response) {
                            super.onError(response);
                        }

                        @Override
                        public void downloadProgress(Progress progress) {
                            super.downloadProgress(progress);
                        }
                    });
            }
        });
        findViewById(R.id.llWpRecovery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                File wp = new File(requireActivity().getFilesDir().getAbsolutePath() + "/wp");
                if (wp.exists())
                    wp.delete();
                ((BaseActivity) requireActivity()).changeWallpaper(true);
            }
        });
        findViewById(R.id.llHomeApi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                List<SourceBean> sites = ApiConfig.get().getSwitchSourceBeanList();
                if (sites.size() > 0) {
                    SelectDialog<SourceBean> dialog = new SelectDialog<>(mActivity);
                    dialog.setTip("请选择首页数据源");
                    int select = sites.indexOf(ApiConfig.get().getHomeSourceBean());
                    if (select<0) select = 0;
                    dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<SourceBean>() {
                        @Override
                        public void click(SourceBean value, int pos) {
                            dialog.dismiss();
                            ApiConfig.get().setSourceBean(value);
                            tvHomeApi.setText(ApiConfig.get().getHomeSourceBean().getName());
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HOME_SOURCE_CHANGE));
                        }

                        @Override
                        public String getDisplay(SourceBean val) {
                            return val.getName();
                        }
                    }, new DiffUtil.ItemCallback<SourceBean>() {
                        @Override
                        public boolean areItemsTheSame(@NonNull @NotNull SourceBean oldItem, @NonNull @NotNull SourceBean newItem) {
                            return oldItem == newItem;
                        }

                        @Override
                        public boolean areContentsTheSame(@NonNull @NotNull SourceBean oldItem, @NonNull @NotNull SourceBean newItem) {
                            return oldItem.getKey().equals(newItem.getKey());
                        }
                    }, sites, select);
                    dialog.show();
                }
            }
        });
        findViewById(R.id.llDns).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int dohUrl = cur(HawkConfig.DOH_URL, 0);

                SelectDialog<String> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择安全DNS");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<String>() {
                    @Override
                    public void click(String value, int pos) {
                        tvDns.setText(OkGoHelper.dnsHttpsList.get(pos));
                        draftPut(HawkConfig.DOH_URL, pos);
//                        String url = OkGoHelper.getDohUrl(pos);
//                        OkGoHelper.dnsOverHttps.setUrl(url.isEmpty() ? null : HttpUrl.get(url));
                        OkGoHelper.reloadDns();
                        IjkMediaPlayer.toggleDotPort(pos > 0);
                    }

                    @Override
                    public String getDisplay(String val) {
                        return val;
                    }
                }, new DiffUtil.ItemCallback<String>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                        return oldItem.equals(newItem);
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                        return oldItem.equals(newItem);
                    }
                }, OkGoHelper.dnsHttpsList, dohUrl);
                dialog.show();
            }
        });
        findViewById(R.id.llApi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                apiDialog = new ApiDialog(mActivity);
                ApiDialog dialog = apiDialog;
                // ★ v99-r79：弹窗初始值读**草稿优先**（cur），否则保存前重开弹窗会显示被还原的旧值
                dialog.setInitialValues(cur(HawkConfig.API_URL, ""),
                        cur(HawkConfig.LIVE_API_URL, cur(HawkConfig.API_URL, "")));
                EventBus.getDefault().register(dialog);
                dialog.setOnListener(new ApiDialog.OnListener() {
                    @Override
                    public void onchange(String api, String liveApi) {
                        // ★ v99-r79：两个地址统一走草稿（旧实现直播直接落 Hawk、点播走草稿，两路不一致
                        //   ⇒ 首次安装填点播后重开弹窗"点播空、地址跑到直播栏"，放弃保存时点播还丢了）
                        draftPutApiPair(api, liveApi);
                        if (!TextUtils.isEmpty(api) && !HistoryHelper.isApiLineHistory(api)) {
                            HistoryHelper.clearApiLineList();
                        }
                        if (!TextUtils.isEmpty(api)) {
                            tvApi.setText(api);
                            refreshApiLineText();
                        }
                    }

                    @Override
                    public void onLocalConfig(boolean live) {
                        openLocalConfig(live);
                    }
                });
                dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        ((BaseActivity) mActivity).hideSysBar();
                        EventBus.getDefault().unregister(dialog);
                        apiDialog = null;
                    }
                });
                dialog.show();
            }
        });

        findViewById(R.id.llApiHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<String> history = cur(HawkConfig.API_HISTORY, new ArrayList<String>());
                if (history.isEmpty())
                    return;
                String current = cur(HawkConfig.API_URL, "");
                int idx = 0;
                if (history.contains(current))
                    idx = history.indexOf(current);
                ApiHistoryDialog dialog = new ApiHistoryDialog(mActivity);
                dialog.setTip("历史配置列表");
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String value) {
                        draftPut(HawkConfig.API_URL, value);
                        draftPut(HawkConfig.LIVE_API_URL, value);
                        if (!HistoryHelper.isApiLineHistory(value)) {
                            HistoryHelper.clearApiLineList();
                        }
                        HistoryHelper.setLiveApiHistory(value);
                        tvApi.setText(value);
                        refreshApiLineText();
                        dialog.dismiss();
                    }

                    @Override
                    public void del(String value, ArrayList<String> data) {
                        Hawk.put(HawkConfig.API_HISTORY, data);
                    }
                }, history, idx);
                dialog.show();
            }
        });

        findViewById(R.id.llApiLine).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<String> apiLines = cur(HawkConfig.API_LINE_LIST, new ArrayList<String>());
                if (apiLines.isEmpty()) {
                    Toast.makeText(mContext, "线路列表为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                String current = cur(HawkConfig.API_URL, "");
                int idx = 0;
                for (int i = 0; i < apiLines.size(); i++) {
                    if (current.equals(HistoryHelper.getApiLineUrl(apiLines.get(i)))) {
                        idx = i;
                        break;
                    }
                }
                SelectDialog<String> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("线路选择");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<String>() {
                    @Override
                    public void click(String value, int pos) {
                        String newApi = HistoryHelper.getApiLineUrl(value);
                        if (newApi.isEmpty()) {
                            return;
                        }
                        if (!HistoryHelper.isApiLineHistory(newApi)) {
                            // 快照由 draftPut 内部在首次 API 改动时记录，这里不再前置副作用
                        }
                        draftPut(HawkConfig.API_URL, newApi);
                        draftPut(HawkConfig.LIVE_API_URL, newApi);
                        HistoryHelper.setLiveApiHistory(newApi);
                        tvApi.setText(newApi);
                        refreshApiLineText();
                        dialog.dismiss();
                    }

                    @Override
                    public String getDisplay(String val) {
                        return HistoryHelper.getApiLineName(val);
                    }
                }, SelectDialogAdapter.stringDiff, apiLines, idx);
                dialog.show();
            }
        });


        findViewById(R.id.llMediaCodec).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<IJKCode> ijkCodes = ApiConfig.get().getIjkCodes();
                if (ijkCodes == null || ijkCodes.size() == 0)
                    return;
                FastClickCheckUtil.check(v);

                int defaultPos = 0;
                String ijkSel = cur(HawkConfig.IJK_CODEC, "硬解码");
                for (int j = 0; j < ijkCodes.size(); j++) {
                    if (ijkSel.equals(ijkCodes.get(j).getName())) {
                        defaultPos = j;
                        break;
                    }
                }

                SelectDialog<IJKCode> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择IJK解码");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<IJKCode>() {
                    @Override
                    public void click(IJKCode value, int pos) {
                        value.selected(true);
                        tvMediaCodec.setText(value.getName());
                    }

                    @Override
                    public String getDisplay(IJKCode val) {
                        return val.getName();
                    }
                }, new DiffUtil.ItemCallback<IJKCode>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull IJKCode oldItem, @NonNull @NotNull IJKCode newItem) {
                        return oldItem == newItem;
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull IJKCode oldItem, @NonNull @NotNull IJKCode newItem) {
                        return oldItem.getName().equals(newItem.getName());
                    }
                }, ijkCodes, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.llScale).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = cur(HawkConfig.PLAY_SCALE, 0);
                ArrayList<Integer> players = new ArrayList<>();
                players.add(0);
                players.add(1);
                players.add(2);
                players.add(3);
                players.add(4);
                players.add(5);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择默认画面缩放");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        draftPut(HawkConfig.PLAY_SCALE, value);
                        tvScale.setText(PlayerHelper.getScaleName(value));
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return PlayerHelper.getScaleName(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, players, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.llPlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int playerType = cur(HawkConfig.PLAY_TYPE, 2);
                int defaultPos = 0;
                ArrayList<Integer> players = PlayerHelper.getExistPlayerTypes();
                ArrayList<Integer> renders = new ArrayList<>();
                for(int p = 0; p<players.size(); p++) {
                    renders.add(p);
                    if (players.get(p) == playerType) {
                        defaultPos = p;
                    }
                }
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择默认播放器");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Integer thisPlayerType = players.get(pos);
                        draftPut(HawkConfig.PLAY_TYPE, thisPlayerType);
                        tvPlay.setText(PlayerHelper.getPlayerName(thisPlayerType));
                        PlayerHelper.init();
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        Integer playerType = players.get(val);
                        return PlayerHelper.getPlayerName(playerType);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, renders, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.llRender).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = cur(HawkConfig.PLAY_RENDER, 1);
                ArrayList<Integer> renders = new ArrayList<>();
                renders.add(0);
                renders.add(1);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择默认渲染方式");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        draftPut(HawkConfig.PLAY_RENDER, value);
                        tvRender.setText(PlayerHelper.getRenderName(value));
                        PlayerHelper.init();
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return PlayerHelper.getRenderName(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, renders, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.llHomeRec).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = cur(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC);
                ArrayList<Integer> types = new ArrayList<>();
                types.add(0);
                types.add(1);
                types.add(2);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择首页列表数据");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        draftPut(HawkConfig.HOME_REC, value);
                        tvHomeRec.setText(getHomeRecName(value));
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return getHomeRecName(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.llSearchView).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = cur(HawkConfig.SEARCH_VIEW, 0);
                ArrayList<Integer> types = new ArrayList<>();
                types.add(0);
                types.add(1);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择搜索视图");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        draftPut(HawkConfig.SEARCH_VIEW, value);
                        tvSearchView.setText(getSearchView(value));
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return getSearchView(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });
        // ★ v72：调试模式开关已移至「关于」弹窗（版本号同一行），旧 llDebug 行与本
        //   DevMode 回调一并移除；SettingActivity 的连按彩蛋逻辑未动（callback 为 null 安全）。
        findViewById(R.id.showPreview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                draftPut(HawkConfig.SHOW_PREVIEW, !cur(HawkConfig.SHOW_PREVIEW, true));
                tvShowPreviewText.setText(cur(HawkConfig.SHOW_PREVIEW, true) ? "开启" : "关闭");
            }
        });
        findViewById(R.id.llHistoryNum).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = cur(HawkConfig.HISTORY_NUM, 0);
                ArrayList<Integer> types = new ArrayList<>();
                types.add(0);
                types.add(1);
                types.add(2);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("保留历史记录数量");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(final Integer value, int pos) {
                        final int oldIndex = cur(HawkConfig.HISTORY_NUM, 0);
                        // ★ v99-r47：先改变档位（显示层立刻按新档位收敛），再决定要不要提醒裁剪
                        draftPut(HawkConfig.HISTORY_NUM, value);
                        tvHistoryNum.setText(HistoryHelper.getHistoryNumName(value));
                        // 档位是账号级设置 ⇒ 标脏让它 1 分钟内推出去（否则要等别的 dirty 顺风车）
                        HistorySyncManager.markDirty(SyncScope.SETTINGS);
                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
                        if (value.intValue() == oldIndex) {
                            return;
                        }
                        // ★ v99-r47：真实裁剪必须用户确认后才执行。
                        //   只有「新档位容不下现存记录」时才需要提醒；调高、或库里本来就没那么多，
                        //   都不会删东西，不必打扰。
                        final int keep = HistoryHelper.getHisNum(value);
                        final int count = RoomDataManger.countVodRecord();
                        if (count <= keep) {
                            return;
                        }
                        final TipDialog[] holder = new TipDialog[1];
                        holder[0] = new TipDialog(mActivity,
                                "保留历史记录数量改为 " + keep + " 条\n"
                                        + "当前有 " + count + " 条，将删除最早的 " + (count - keep) + " 条\n"
                                        + "这些影片的播放进度也会一并清除，不可恢复",
                                "取消", "确定删除", new TipDialog.OnListener() {
                            @Override
                            public void left() {
                                holder[0].dismiss();
                            }

                            @Override
                            public void right() {
                                holder[0].dismiss();
                                RoomDataManger.pruneHistory();
                                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
                                LOG.i("history trim by user confirm: keep=" + keep + " before=" + count);
                            }

                            @Override
                            public void cancel() {
                                // 点外部关闭 = 不裁剪（记录保留，下一次写历史时自然按新档位裁）
                            }
                        });
                        holder[0].show();
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return HistoryHelper.getHistoryNumName(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.showFastSearch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                draftPut(HawkConfig.FAST_SEARCH_MODE, !cur(HawkConfig.FAST_SEARCH_MODE, true));
                tvFastSearchText.setText(cur(HawkConfig.FAST_SEARCH_MODE, true) ? "开启" : "关闭");
            }
        });
        findViewById(R.id.m3u8Ad).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean is_purify=cur(HawkConfig.M3U8_PURIFY, false);
                draftPut(HawkConfig.M3U8_PURIFY, !is_purify);
                tvm3u8AdText.setText(!is_purify ? "开启" : "关闭");
            }
        });
        findViewById(R.id.danmuOpen).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                // v99-r49：草稿化——只改显示，保存时才写 Hawk 并广播给播放页
                boolean open = !cur(HawkConfig.DANMU_OPEN, DanmuHelper.isOpen());
                draftPut(HawkConfig.DANMU_OPEN, open);
                tvDanmuOpenText.setText(open ? "开启" : "关闭");
            }
        });
        findViewById(R.id.danmuApi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                DanmuApiDialog dialog = new DanmuApiDialog(mActivity);
                dialog.setOnListener(new DanmuApiDialog.OnListener() {
                    @Override
                    public void onChange(String api) {
                        refreshDanmuApiText();
                    }
                });
                dialog.show();
            }
        });
        findViewById(R.id.autoSwitchLine).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean enable = !cur(HawkConfig.AUTO_SWITCH_LINE, true);
                draftPut(HawkConfig.AUTO_SWITCH_LINE, enable);
                tvAutoSwitchLineText.setText(enable ? "开启" : "关闭");
            }
        });
        findViewById(R.id.llHomeRecStyle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                draftPut(HawkConfig.HOME_REC_STYLE, !cur(HawkConfig.HOME_REC_STYLE, false));
                tvRecStyleText.setText(cur(HawkConfig.HOME_REC_STYLE, false) ? "是" : "否");
            }
        });

        // v99-r49：「历史合并」开关已删除（合并恒开启），该格改为「保存设置」
        View saveView = findViewById(R.id.llSaveSettings);
        if (saveView != null) {
            saveView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    showSaveDialog("本次涉及的修改内容");
                }
            });
        }

        //下次进入
        findViewById(R.id.tvHomeLive).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                draftPut(HawkConfig.DEFAULT_LOAD_LIVE, !cur(HawkConfig.DEFAULT_LOAD_LIVE, false));
                tvHomeDefaultShow.setText(cur(HawkConfig.DEFAULT_LOAD_LIVE, false) ? "直播" : "点播");
            }
        });

        findViewById(R.id.llIjkCachePlay).setOnClickListener((view -> onClickIjkCachePlay(view)));
        findViewById(R.id.llClearCache).setOnClickListener((view -> onClickClearCache(view)));
    }


    /** v99-r49：统一按「草稿优先」渲染设置页所有值。保存/丢弃后调用一次即可。 */
    private void refreshUi() {
        tvFastSearchText = findViewById(R.id.showFastSearchText);
        tvFastSearchText.setText(cur(HawkConfig.FAST_SEARCH_MODE, true) ? "开启" : "关闭");
        tvm3u8AdText = findViewById(R.id.m3u8AdText);
        tvm3u8AdText.setText(cur(HawkConfig.M3U8_PURIFY, false) ? "开启" : "关闭");
        tvDanmuOpenText = findViewById(R.id.danmuOpenText);
        tvDanmuOpenText.setText(cur(HawkConfig.DANMU_OPEN, DanmuHelper.isOpen()) ? "开启" : "关闭");
        tvDanmuApiText = findViewById(R.id.danmuApiText);
        refreshDanmuApiText();
        tvAutoSwitchLineText = findViewById(R.id.autoSwitchLineText);
        tvAutoSwitchLineText.setText(cur(HawkConfig.AUTO_SWITCH_LINE, true) ? "开启" : "关闭");
        tvRecStyleText = findViewById(R.id.showRecStyleText);
        tvRecStyleText.setText(cur(HawkConfig.HOME_REC_STYLE, false) ? "是" : "否");
        tvShowPreviewText = findViewById(R.id.showPreviewText);
        tvShowPreviewText.setText(cur(HawkConfig.SHOW_PREVIEW, true) ? "开启" : "关闭");
        tvParseWebView = findViewById(R.id.tvParseWebView);
        tvMediaCodec = findViewById(R.id.tvMediaCodec);
        tvPlay = findViewById(R.id.tvPlay);
        tvRender = findViewById(R.id.tvRenderType);
        tvScale = findViewById(R.id.tvScaleType);
        llApi = findViewById(R.id.llApi);
        llApiHistory = findViewById(R.id.llApiHistory);
        llApiLine = findViewById(R.id.llApiLine);
        tvApi = findViewById(R.id.tvApi);
        tvApiLine = findViewById(R.id.tvApiLine);
        tvHomeApi = findViewById(R.id.tvHomeApi);
        tvDns = findViewById(R.id.tvDns);
        tvHomeRec = findViewById(R.id.tvHomeRec);
        tvHistoryNum = findViewById(R.id.tvHistoryNum);
        tvSearchView = findViewById(R.id.tvSearchView);
        tvIjkCachePlay = findViewById(R.id.tvIjkCachePlay);
        tvMediaCodec.setText(cur(HawkConfig.IJK_CODEC, "硬解码"));
        tvParseWebView.setText(cur(HawkConfig.PARSE_WEBVIEW, true) ? "系统自带" : "XWalkView");
        tvApi.setText(cur(HawkConfig.API_URL, ""));
        refreshApiLineText();

        tvDns.setText(OkGoHelper.dnsHttpsList.get(cur(HawkConfig.DOH_URL, 0)));
        tvHomeRec.setText(getHomeRecName(cur(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC)));
        tvHistoryNum.setText(HistoryHelper.getHistoryNumName(cur(HawkConfig.HISTORY_NUM, 0)));
        tvSearchView.setText(getSearchView(cur(HawkConfig.SEARCH_VIEW, 0)));
        tvHomeApi.setText(ApiConfig.get().getHomeSourceBean().getName());
        tvScale.setText(PlayerHelper.getScaleName(cur(HawkConfig.PLAY_SCALE, 0)));
        tvPlay.setText(PlayerHelper.getPlayerName(cur(HawkConfig.PLAY_TYPE, 2)));
        tvRender.setText(PlayerHelper.getRenderName(cur(HawkConfig.PLAY_RENDER, 1)));
        tvIjkCachePlay.setText(cur(HawkConfig.IJK_CACHE_PLAY, false) ? "开启" : "关闭");
        tvHomeDefaultShow = findViewById(R.id.tvHomeText);
        tvHomeDefaultShow.setText(cur(HawkConfig.DEFAULT_LOAD_LIVE, false) ? "直播" : "点播");
        applyGuestBackupState();
    }

    /** ★ v99-r50：数据备份只服务游客（游客态 markDirty 天然不上传）；登录态置灰。 */
    private void applyGuestBackupState() {
        View v = findViewById(R.id.llBackup);
        if (v == null) {
            return;
        }
        boolean guest = com.github.tvbox.osc.sync.AccountContext.isGuest();
        v.setEnabled(guest);
        v.setClickable(guest);
        v.setAlpha(guest ? 1f : 0.4f);
    }

    // ------------------------------------------------------------------ 草稿层
    /** 进页面时的原值快照：判断"改没改"、生成"旧→新"都要用它（不能拿 Hawk 比，API 类会临时落盘）。 */
    private final java.util.LinkedHashMap<String, Object> baseline = new java.util.LinkedHashMap<>();

    private void captureBaseline() {
        baseline.clear();
        for (String k : SETTING_NAMES.keySet()) {
            baseline.put(k, Hawk.contains(k) ? Hawk.get(k, (Object) null) : null);
        }
        for (String k : API_DEPENDENT_KEYS) {
            if (!baseline.containsKey(k)) {
                baseline.put(k, Hawk.contains(k) ? Hawk.get(k, (Object) null) : null);
            }
        }
    }

    private boolean changed(String key) {
        if (!draft.containsKey(key)) {
            return false;
        }
        Object old = baseline.get(key);
        Object now = draft.get(key);
        if (old == null) {
            return now != null;
        }
        return !old.equals(now);
    }

    private boolean hasChanges() {
        for (String k : draft.keySet()) {
            if (changed(k)) {
                return true;
            }
        }
        return false;
    }

    /** 读草稿，没有则回落到已保存值。 */
    @SuppressWarnings("unchecked")
    private <T> T cur(String key, T defValue) {
        if (draft.containsKey(key)) {
            Object v = draft.get(key);
            if (v != null) {
                try {
                    return (T) v;
                } catch (Throwable ignored) {
                }
            }
        }
        return Hawk.get(key, defValue);
    }

    /** 写草稿。API 类键例外：临时落盘 → 立即解析 → 立刻还原"用户设置类"键。 */
    private void draftPut(String key, Object value) {
        if (isApiDependent(key)) {
            snapshotApiKeys();
            Hawk.put(key, value);
            draft.put(key, value);
            reloadApiForDraft();
            return;
        }
        draft.put(key, value);
    }

    private static boolean isApiDependent(String key) {
        for (String k : API_DEPENDENT_KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ★ v99-r79：点播 + 直播两个地址**一次快照、一次临时落盘、一次重载**的成对草稿入口。
     * 逐键 draftPut 会对每个 API 键各触发一次配置重载；且两个地址必须同进同出草稿
     * ——旧实现直播真落盘、点播走草稿，放弃保存时两路状态不一致。
     * 空值不写（沿用旧口径：本弹窗不支持"清空地址"）。
     */
    private void draftPutApiPair(String api, String liveApi) {
        boolean apiTouched = !TextUtils.isEmpty(api);
        boolean liveTouched = !TextUtils.isEmpty(liveApi);
        if (!apiTouched && !liveTouched) {
            return;
        }
        if (apiTouched) {
            draft.put(HawkConfig.API_URL, api);
        }
        if (liveTouched) {
            draft.put(HawkConfig.LIVE_API_URL, liveApi);
        }
        snapshotApiKeys();
        if (apiTouched) {
            Hawk.put(HawkConfig.API_URL, api);
        }
        if (liveTouched) {
            Hawk.put(HawkConfig.LIVE_API_URL, liveApi);
        }
        reloadApiForDraft();
    }

    /** API 改动前的现场（只记一次）。 */
    private void snapshotApiKeys() {
        if (!apiSnapshot.isEmpty() || !apiAbsent.isEmpty()) {
            return;
        }
        for (String k : API_DEPENDENT_KEYS) {
            if (Hawk.contains(k)) {
                apiSnapshot.put(k, Hawk.get(k, (Object) null));
            } else {
                apiAbsent.add(k);
            }
        }
    }

    /**
     * 还原"用户设置类"键（API_URL/LIVE_API_URL/DOH/EPG…），
     * 但**保留 API_LINE_LIST** —— 它是解析产物，"线路选择"的候选列表要靠它。
     */
    private void restoreApiSettingsOnly() {
        for (String k : API_DEPENDENT_KEYS) {
            if (HawkConfig.API_LINE_LIST.equals(k)) {
                continue;
            }
            if (apiSnapshot.containsKey(k)) {
                Hawk.put(k, apiSnapshot.get(k));
            } else if (apiAbsent.contains(k)) {
                Hawk.delete(k);
            }
        }
        apiSnapshot.clear();
        apiAbsent.clear();
    }

    /** 用当前 Hawk[API_URL] 重新解析；成功后还原设置类键并刷新依赖 UI。 */
    private void reloadApiForDraft() {
        try {
            ApiConfig.get().loadConfig(true, new ApiConfig.LoadConfigCallback() {
                @Override
                public void success() {
                    boolean touched = !apiSnapshot.isEmpty() || !apiAbsent.isEmpty();
                    if (touched) {
                        restoreApiSettingsOnly();
                    }
                    refreshApiDependentUi();
                }

                @Override
                public void error(String msg) {
                    boolean touched = !apiSnapshot.isEmpty() || !apiAbsent.isEmpty();
                    if (touched) {
                        restoreApiSettingsOnly();
                    }
                    refreshApiDependentUi();
                    if (mContext != null) {
                        Toast.makeText(mContext, "配置解析失败：" + msg, Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void notice(String msg) {
                }
            }, mActivity);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void refreshApiDependentUi() {
        if (mActivity == null) {
            return;
        }
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (tvApi != null) {
                    tvApi.setText(cur(HawkConfig.API_URL, ""));
                }
                refreshApiLineText();
                if (tvHomeApi != null) {
                    try {
                        tvHomeApi.setText(ApiConfig.get().getHomeSourceBean().getName());
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /** 丢弃草稿（不保存 / Home 键离开）。API 早已还原，这里只需按旧配置重新解析一次。 */
    private void discardDraft() {
        boolean apiTouched = !draft.isEmpty() && (draft.containsKey(HawkConfig.API_URL)
                || draft.containsKey(HawkConfig.LIVE_API_URL));
        draft.clear();
        if (apiTouched) {
            // ★ v99-r79：先**同步**还原 API 键快照，再重载。
            //   旧实现假设「Hawk 里是旧值」，但那依赖配置重载回调已跑完——若用户在重载返回前
            //   就点了「不保存」，这里会把**新地址**载入内存，随后早先那次重载完成才还原 Hawk 键
            //   ⇒ Hawk 正确而内存停留在新配置，直到下次重载/重启。
            boolean touched = !apiSnapshot.isEmpty() || !apiAbsent.isEmpty();
            if (touched) {
                restoreApiSettingsOnly();
            }
            reloadApiForDraft();      // Hawk 里此刻必是旧值 ⇒ 内存态回到旧配置
        }
        captureBaseline();
        refreshUi();
    }

    // ------------------------------------------------------------------ 保存
    /** 三键确认框：save=落盘并上传；discard=丢弃；cancel=留在本页。 */
    private void showSaveDialog(String title) {
        String content = buildChangeSummary();
        if (content.length() == 0) {
            Toast.makeText(mContext, "没有需要保存的改动", Toast.LENGTH_SHORT).show();
            return;
        }
        new com.github.tvbox.osc.ui.dialog.SaveConfirmDialog(mActivity, title, content,
                new com.github.tvbox.osc.ui.dialog.SaveConfirmDialog.OnListener() {
                    @Override
                    public void save() {
                        doSave();
                    }

                    @Override
                    public void discard() {
                        discardDraft();
                        if (mActivity != null) {
                            mActivity.finish();
                        }
                    }

                    @Override
                    public void cancel() {
                    }
                }).show();
    }

    /** 改动清单：「名称：旧 → 新」，长串（地址）只给省略形态。 */
    private String buildChangeSummary() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (java.util.Map.Entry<String, Object> e : draft.entrySet()) {
            String key = e.getKey();
            if (!changed(key)) {
                continue;
            }
            String name = SETTING_NAMES.containsKey(key) ? SETTING_NAMES.get(key) : key;
            String from = describeValue(key, baseline.get(key));
            String to = describeValue(key, e.getValue());
            sb.append('\n').append("· ").append(name).append("：").append(from).append(" → ").append(to);
            n++;
        }
        if (n == 0) {
            return "";
        }
        return sb.toString();
    }

    private String describeValue(String key, Object v) {
        if (v == null) {
            return "无";
        }
        try {
            if (HawkConfig.API_URL.equals(key) || HawkConfig.LIVE_API_URL.equals(key)
                    || HawkConfig.API_LINE_SOURCE.equals(key)) {
                return shortUrl(String.valueOf(v));
            }
            if (v instanceof Boolean) {
                return ((Boolean) v) ? "开启" : "关闭";
            }
            if (HawkConfig.HISTORY_NUM.equals(key)) {
                return HistoryHelper.getHistoryNumName((Integer) v);
            }
            if (HawkConfig.HOME_REC.equals(key)) {
                return getHomeRecName((Integer) v);
            }
            if (HawkConfig.SEARCH_VIEW.equals(key)) {
                return getSearchView((Integer) v);
            }
            if (HawkConfig.PLAY_SCALE.equals(key)) {
                return PlayerHelper.getScaleName((Integer) v);
            }
            if (HawkConfig.PLAY_TYPE.equals(key)) {
                return PlayerHelper.getPlayerName((Integer) v);
            }
            if (HawkConfig.PLAY_RENDER.equals(key)) {
                return PlayerHelper.getRenderName((Integer) v);
            }
            if (HawkConfig.DOH_URL.equals(key)) {
                Integer i = (Integer) v;
                return i >= 0 && i < OkGoHelper.dnsHttpsList.size() ? OkGoHelper.dnsHttpsList.get(i) : "默认";
            }
            if (HawkConfig.DANMU_API.equals(key)) {
                String s = String.valueOf(v);
                return TextUtils.isEmpty(s) ? "默认" : "已自定义";
            }
        } catch (Throwable ignored) {
        }
        return String.valueOf(v);
    }

    /** 地址只显示首尾，避免长串撑爆弹窗。 */
    private static String shortUrl(String url) {
        if (url == null || url.length() <= 24) {
            return url;
        }
        return url.substring(0, 12) + "…" + url.substring(url.length() - 8);
    }

    /** 真正落盘：写 Hawk + 裁剪 + 广播 + 标脏上传（+ 配置类变更触发重载）。 */
    private void doSave() {
        boolean apiChanged = false;
        boolean homeApiChanged = false;
        boolean historyNumChanged = false;
        for (java.util.Map.Entry<String, Object> e : draft.entrySet()) {
            String k = e.getKey();
            Hawk.put(k, e.getValue());               // API 类也在此正式落盘
            if (HawkConfig.API_URL.equals(k) || HawkConfig.LIVE_API_URL.equals(k)) {
                apiChanged = true;
            }
            if (HawkConfig.HOME_API.equals(k)) {
                homeApiChanged = true;
            }
            if (HawkConfig.HISTORY_NUM.equals(k)) {
                historyNumChanged = true;
            }
        }
        draft.clear();
        apiSnapshot.clear();
        apiAbsent.clear();
        captureBaseline();

        // 档位变了 ⇒ 此刻才真正裁剪（清单里已列明）
        if (historyNumChanged) {
            try {
                RoomDataManger.pruneHistory();
            } catch (Throwable ignored) {
            }
        }
        // 弹幕开关：保存后才广播给播放页
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, DanmuHelper.isOpen()));
        if (homeApiChanged) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HOME_SOURCE_CHANGE));
        }
        // 标脏并立即同步 —— 设置上传的唯一入口
        try {
            HistorySyncManager.markDirty(SyncScope.SETTINGS);
            com.github.tvbox.osc.sync.SyncScheduler.requestNow(
                    com.github.tvbox.osc.sync.AccountContext.ownerKey(), "settings-save", true, null);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        refreshUi();
        if (apiChanged) {
            suppressLeavePrompt = true;
            Toast.makeText(mContext, "设置已保存，配置已切换，即将重新加载!", Toast.LENGTH_SHORT).show();
            restartAppAfterConfigChanged();
        } else {
            Toast.makeText(mContext, "设置已保存", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ 离开拦截
    /** 返回键：有未保存改动则拦下弹三键框。返回 true 表示已消费本次返回键。 */
    public boolean handleBackPress() {
        if (suppressLeavePrompt) {
            suppressLeavePrompt = false;
            return false;
        }
        if (!hasChanges()) {
            return false;
        }
        showSaveDialog("有未保存的修改，是否保存？\n不保存将丢失当前修改！");
        return true;
    }

    private void restartAppAfterConfigChanged() {
        Toast.makeText(mContext, "配置已切换,即将重新加载!", Toast.LENGTH_SHORT).show();
        clearConfigSwitchCache();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mActivity != null && !mActivity.isFinishing()) {
                    mActivity.onBackPressed();
                }
            }
        }, 2500);
    }

    private void clearConfigSwitchCache() {
        try {
            SourceViewModel.clearRuntimeCache();
//            FileUtils.clearSpiderCacheFiles();
        } catch (Exception e) {
            LOG.i("echo-clear-config-switch-cache-error:" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void restartAppAfterCacheCleared() {
        Toast.makeText(mContext, "缓存已清空,即将回到主页!", Toast.LENGTH_LONG).show();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                restartApp();
            }
        }, 2500);
    }

    private void refreshApiLineText() {
        if (tvApiLine == null) return;
        ArrayList<String> apiLines = cur(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        String current = cur(HawkConfig.API_URL, "");
        boolean showLine = HistoryHelper.isApiLineUrl(current);
        if (llApiLine != null) {
            llApiLine.setVisibility(showLine ? View.VISIBLE : View.GONE);
        }
        updateApiRowWeight(showLine);
        String lineName = "";
        if (showLine) {
            for (String apiLine : apiLines) {
                if (current.equals(HistoryHelper.getApiLineUrl(apiLine))) {
                    lineName = HistoryHelper.getApiLineName(apiLine);
                    break;
                }
            }
        }
        tvApiLine.setText(lineName);
    }

    private void refreshDanmuApiText() {
        if (tvDanmuApiText == null) return;
        if (DanmakuApi.isUseDefault()) {
            tvDanmuApiText.setText("默认");
            return;
        }
        String custom = cur(HawkConfig.DANMU_API, "");
        if (!custom.isEmpty()) {
            tvDanmuApiText.setText("自定义");
            return;
        }
        String config = ApiConfig.get().getDanmaku();
        tvDanmuApiText.setText(config.isEmpty() ? "默认" : "接口");
    }

    private void updateApiRowWeight(boolean showLine) {
        if (llApi == null) return;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) llApi.getLayoutParams();
        params.weight = showLine ? 1.0f : 3.08f;
        llApi.setLayoutParams(params);
        if (llApiHistory != null) {
            LinearLayout.LayoutParams historyParams = (LinearLayout.LayoutParams) llApiHistory.getLayoutParams();
            int margin = showLine ? getResources().getDimensionPixelSize(R.dimen.vs_5) : 0;
            historyParams.rightMargin = margin;
            historyParams.setMarginEnd(margin);
            llApiHistory.setLayoutParams(historyParams);
        }
    }

    private void restartApp() {
        if (mContext == null) return;
        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(mContext.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            System.exit(0);
        }
    }

    private void onClickIjkCachePlay(View v) {
        FastClickCheckUtil.check(v);
        draftPut(HawkConfig.IJK_CACHE_PLAY, !cur(HawkConfig.IJK_CACHE_PLAY, false));
        tvIjkCachePlay.setText(cur(HawkConfig.IJK_CACHE_PLAY, false) ? "开启" : "关闭");
    }

    private void openLocalConfig(boolean live) {
        selectLocalLive = live;
        leavingForResult = true;
        if (!XXPermissions.isGranted(mContext, Permission.Group.STORAGE)) {
            Toast.makeText(getContext(), "请选择文件前需要先授予存储权限", Toast.LENGTH_SHORT).show();
            XXPermissions.with(mActivity)
                    .permission(Permission.Group.STORAGE)
                    .request(new OnPermissionCallback() {
                        @Override
                        public void onGranted(List<String> permissions, boolean all) {
                            if (all) {
                                Toast.makeText(getContext(), "已获得存储权限", Toast.LENGTH_SHORT).show();
                                openLocalFileActivity(selectLocalLive);
                            }
                        }

                        @Override
                        public void onDenied(List<String> permissions, boolean never) {
                            if (never) {
                                Toast.makeText(getContext(), "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                                XXPermissions.startPermissionActivity(mActivity, permissions);
                            } else {
                                Toast.makeText(getContext(), "获取存储权限失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
            return;
        }
        openLocalFileActivity(live);
    }

    private void openLocalFileActivity(boolean live) {
        Intent intent = new Intent(mContext, LocalFileActivity.class);
        intent.putExtra(LocalFileActivity.EXTRA_LIVE, live);
        startActivityForResult(intent, REQUEST_LOCAL_CONFIG);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        leavingForResult = false;
        if (requestCode != REQUEST_LOCAL_CONFIG || resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        String api = localConfigToApi(data.getData());
        if (api == null || api.isEmpty()) {
            Toast.makeText(getContext(), "读取本地配置失败", Toast.LENGTH_SHORT).show();
            return;
        }
        if (apiDialog != null) {
            apiDialog.setLocalApi(api, selectLocalLive);
        }
    }

    private String localConfigToApi(Uri uri) {
        String path = getPathFromUri(uri);
        if (path == null || path.isEmpty()) {
            path = copyUriToLocalConfig(uri);
        }
        if (path == null || path.isEmpty()) {
            return "";
        }
        String storageRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.startsWith(storageRoot)) {
            return "clan://localhost/" + path.substring(storageRoot.length()).replaceFirst("^/+", "");
        }
        path = copyUriToLocalConfig(uri);
        if (path != null && path.startsWith(storageRoot)) {
            return "clan://localhost/" + path.substring(storageRoot.length()).replaceFirst("^/+", "");
        }
        return "";
    }

    private String getPathFromUri(Uri uri) {
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return uri.getPath();
            }
            if (DocumentsContract.isDocumentUri(mContext, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                    String[] split = docId.split(":");
                    if (split.length > 1 && "primary".equalsIgnoreCase(split[0])) {
                        return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + split[1];
                    }
                }
                if ("com.android.providers.downloads.documents".equals(uri.getAuthority()) && docId.startsWith("raw:")) {
                    return docId.substring(4);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String copyUriToLocalConfig(Uri uri) {
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = mContext.getContentResolver().openInputStream(uri);
            if (input == null) return "";
            File dir = new File(FileUtils.getExternalCachePath(), "config");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, getDisplayName(uri));
            output = new FileOutputStream(file);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return file.getAbsolutePath();
        } catch (Throwable th) {
            th.printStackTrace();
            return "";
        } finally {
            try {
                if (output != null) output.close();
            } catch (Throwable ignored) {
            }
            try {
                if (input != null) input.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private String getDisplayName(Uri uri) {
        String name = "local_config.json";
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String displayName = cursor.getString(index);
                    if (displayName != null && !displayName.isEmpty()) {
                        name = displayName;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return name;
    }

    private void onClickClearCache(View v) {
        FastClickCheckUtil.check(v);
        // v73：已登录时先确认「是否同时清空云端」，游客模式直接清本机
        new com.github.tvbox.osc.ui.dialog.ConfirmClearCacheDialog(mActivity,
                new com.github.tvbox.osc.ui.dialog.ConfirmClearCacheDialog.Callback() {
                    @Override
                    public void onConfirm(boolean clearCloud) {
                        if (clearCloud) {
                            com.github.tvbox.osc.sync.HistorySyncManager.clearCloud(
                                    new com.github.tvbox.osc.sync.HistorySyncManager.Callback() {
                                        @Override
                                        public void onDone(boolean ok, String message) {
                                            startLocalClear();
                                        }
                                    });
                        } else {
                            startLocalClear();
                        }
                    }
                }).show();
    }

    private void startLocalClear() {
        String cachePath = FileUtils.getCachePath();
        File cacheDir = new File(cachePath);
        new Thread(() -> {
            try {
                ApiConfig.get().clearSpiderCache();
                if(cacheDir.exists())FileUtils.cleanDirectory(cacheDir);
                FileUtils.clearSpiderCacheFiles();
                // ① 清理"已不在观看历史中"的影片残留的播放记录：
                //    包含看到第几集、以及每一集的播放位置与最后观看时间。
                //    仍在历史记录内的影片，其播放记录会被完整保留。
                RoomDataManger.deleteOrphanedPlayProgress();
                // ② 清理无 vodId 的直链播放（推送/外部播放器）残留的进度，
                //    这类播放不写观看历史，因此永远不可能属于"历史记录内"，
                //    只能集中存放在 Room 的 cache 表，一并清掉。
                //    表内其余内容是可再生的字幕缓存，清除后会自动重新生成。
                CacheManager.deleteAll();
                // ③ ★ v69：清掉「运行期沉淀」出来的持久化数据 —— 只清运行态，
                //    <b>不动任何用户设置</b>（源地址、解码器、字幕样式等一概保留）。
                //
                //    此前「清空缓存」只清文件缓存与数据库里的孤儿/直链进度，
                //    而下面这几样是 App 跑久了自己攒出来的运行态数据，
                //    用户没有任何单独入口能清掉它们。
                //    · 站点质量统计（起播/搜索打点累积，直接决定切源与搜索的排序）。
                //      一旦某批站点的历史因网络波动失真，只能卸载重装才能重置 —— 现在不用了。
                SourceQualityStore.clearAll();
                //    ★ v99-r73：切源候选池<b>已不再落盘</b>（r58/r59 回退）⇒ 这里没有可清的
                //      持久化副本，调用点一并删除（否则编译报"找不到符号"）。
                //    · 观看历史 + 全部播放进度（含「上次看到第几集 / 第几分钟」）。
                //      历史记着某部剧的播放时间，与「清干净运行期数据」的要求直接冲突，
                //      按用户拍板：一并清掉（已覆盖上面的 deleteOrphanedPlayProgress）。
                //      ★ 收藏（VodCollect）是用户主动攒的资产，不在清理范围内。
                RoomDataManger.deleteVodRecordAll();
                //    · 搜索历史与上次搜索的关键词
                Hawk.delete(HawkConfig.SEARCH_HISTORY);
                Hawk.delete(HawkConfig.LAST_SEARCH_KEYWORD);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (mActivity != null) {
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            restartAppAfterCacheCleared();
                        }
                    });
                }
            }
        }).start();
    }

    public static SearchRemoteTvDialog loadingSearchRemoteTvDialog;
    public static List<String> remoteTvHostList;
    public static boolean foundRemoteTv;

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    String getHomeRecName(int type) {
        if (type == 1) {
            return "站点推荐";
        } else if (type == 2) {
            return "观看历史";
        } else {
            return "豆瓣热播";
        }
    }

    String getSearchView(int type) {
        if (type == 0) {
            return "文字列表";
        } else {
            return "缩略图";
        }
    }
}
