package com.github.tvbox.osc.base;

import android.app.Activity;
import androidx.multidex.MultiDexApplication;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.sync.HistorySyncManager;
import com.github.tvbox.osc.sync.SyncSettings;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.EpisodeResolveInitializer;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;
import com.github.catvod.crawler.JsLoader;

import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;

    private static P2PClass p;
    public static String burl;
    private static String dashData;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initParams();
        // 综艺跨源集匹配：装配直连查询（含磁盘缓存）与节目名→slug 映射（失败静默，不影响启动）。
        // 默认链路 = 本地同域匹配 → 跨域直连 → 按序兜底；离线字典默认关闭，
        // 如需长期无网部署可调用 EpisodeResolveInitializer.setOfflineDictEnabled(true)。
        EpisodeResolveInitializer.init(this);
        // r121：载入持久化的「期号↔日期」日期表（关闭重开后复用；文件不存在则空载，静默）。
        try {
            com.github.tvbox.osc.util.EpisodeNameMatcher.initDateTablePersistence(
                    new java.io.File(getFilesDir(), "ep_date_table.tsv").getAbsolutePath());
        } catch (Throwable ignored) {
        }
        // 后台补齐"新上线综艺"的节目名→slug 映射（缓存 24h 内不联网；失败静默）
        try {
            final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newSingleThreadExecutor();
            EpisodeResolveInitializer.refreshSlugMapAsync(pool);
            pool.shutdown();
        } catch (Throwable ignored) {
        }
        // OKGo
        OkGoHelper.init(); //台标获取
        EpgUtil.init();
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        // v73：恢复账号（决定用哪个数据库文件）+ 启动同步触发器（冷启动 10s / 前台 30min 兜底）
        HistorySyncManager.start();
        // v76：老用户升级后把当前登录账号播种进账号清单
        com.github.tvbox.osc.sync.AccountStore.migrateIfNeeded();
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
        QuickJSLoader.init();
        FileUtils.cleanPlayerCache();
    }

    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        // v73：修复被早期版本写坏的设置类型（集合/浮点被存成 String），必须早于任何读取
        SyncSettings.repairCorruptedTypes();
        // v81：调试开关改为**持久化**。
        // 原先每次启动都强制置 false，导致「清理后台再进」抓冷启动日志时永远是关的，
        // 反而让排查冷启动问题没法取证。现在记住用户的选择（默认仍是关）。
        boolean debugOpen = BuildConfig.DEBUG || Hawk.get(HawkConfig.DEBUG_OPEN, false);
        LOG.setDebugEnabled(debugOpen);
        com.github.tvbox.osc.player.ExoPlayer.setExoLogEnabled(debugOpen);
        OkGoHelper.applyHttpDebug(debugOpen);
        Hawk.put(HawkConfig.PLAYER_IS_LIVE, false);
        if (!Hawk.contains(HawkConfig.PLAY_TYPE)) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2);
        } else if (Hawk.get(HawkConfig.PLAY_TYPE, 2) == 0) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2);
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
    }

    // ★ 内存告急时主动让出「文字兜底图」缓存。
    //   原实现全仓没有任何 onTrimMemory / onLowMemory 钩子：Glide 自己会响应系统回调
    //   裁剪它的缓存，而 ImgUtil.drawableCache 不会 —— 系统越紧张它越一毛不拔，
    //   最后反而成了把进程推向 OOM / LMK 的那一份。兜底图重建成本极低，直接整体丢弃。
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            ImgUtil.trimMemory();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImgUtil.trimMemory();
    }


    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    public Activity getCurrentActivity() {
        return AppManager.getInstance().currentActivity();
    }

    public void setDashData(String data) {
        dashData = data;
    }
    public String getDashData() {
        return dashData;
    }
}