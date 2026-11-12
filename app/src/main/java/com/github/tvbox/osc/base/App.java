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
        com.github.tvbox.osc.util.BootTrace.mark("appStart");
        initParams();
        com.github.tvbox.osc.util.BootTrace.mark("paramsDone");
        // r216：日期表的**路径**在这里就设好（只是 new 一个 File，零 IO），
        //   真正读盘交给下面的后台线程。路径必须先于任何 UI 动作就位，
        //   否则"清空历史"这类操作会在路径还没设好时被跳过、随后又被磁盘表读回来。
        try {
            com.github.tvbox.osc.util.EpisodeNameMatcher.initDateTablePersistence(
                    new java.io.File(getFilesDir(), "ep_date_table.tsv").getAbsolutePath());
        } catch (Throwable ignored) {
        }
        // ★ r216：与首屏无关的磁盘 IO 全部移出主线程（P1）。
        //   · EpisodeResolveInitializer.init：综艺跨源匹配的磁盘缓存/映射表；
        //   · EpisodeNameMatcher 日期表：原来是主线程同步读整个 TSV；
        //   · EpgUtil：assets/epg_data.json 有 104KB，只服务直播台标/EPG；
        //   · FileUtils.cleanPlayerCache：递归删 ijk/thunder 缓存目录。
        //   这几项都只服务冷路径，且类内部都做了"没准备好就退化"的处理。
        BOOT_IO.execute(new Runnable() {
            @Override
            public void run() {
                // 综艺跨源集匹配：装配直连查询（含磁盘缓存）与节目名→slug 映射（失败静默）。
                try {
                    EpisodeResolveInitializer.init(App.this);
                } catch (Throwable ignored) {
                }
                // r121：载入持久化的「期号↔日期」日期表（r216：惰性载入，这里只预热）
                try {
                    com.github.tvbox.osc.util.EpisodeNameMatcher.warmUp();
                } catch (Throwable ignored) {
                }
                // 直播台标/EPG（r216：改由后台预热，getEpgInfo 有同步兜底）
                try {
                    EpgUtil.warmUp();
                } catch (Throwable ignored) {
                }
                com.github.tvbox.osc.util.BootTrace.mark("bootIoDone");
                // 后台补齐"新上线综艺"的节目名→slug 映射（缓存 24h 内不联网；失败静默）
                try {
                    final java.util.concurrent.ExecutorService pool =
                            java.util.concurrent.Executors.newSingleThreadExecutor();
                    EpisodeResolveInitializer.refreshSlugMapAsync(pool);
                    pool.shutdown();
                } catch (Throwable ignored) {
                }
            }
        });
        // OKGo
        OkGoHelper.init(); //台标获取
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        // v73：恢复账号（决定用哪个数据库文件）+ 启动同步触发器（冷启动 10s / 前台 30min 兜底）
        HistorySyncManager.start();
        // v76：老用户升级后把当前登录账号播种进账号清单
        com.github.tvbox.osc.sync.AccountStore.migrateIfNeeded();
        // ★ r216：公共设备开关 —— 必须在 start() 之后（此时才知道上次登录的是谁）、
        //   在首屏加载配置之前（否则会先按上一个账号的设置加载一遍）。
        //   不开开关时这一行只有一次 Hawk 读，零副作用。
        com.github.tvbox.osc.sync.PublicDeviceGuard.onProcessStart();
        com.github.tvbox.osc.util.BootTrace.mark("accountReady");
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
        // ★ r216：播放器缓存清理是纯磁盘垃圾回收，放到后台并排在其它后台任务之后，
        //   避免与首屏解析/渲染抢磁盘 IO。
        BOOT_IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    FileUtils.cleanPlayerCache();
                } catch (Throwable ignored) {
                }
                com.github.tvbox.osc.util.BootTrace.mark("cacheCleaned");
            }
        });
        com.github.tvbox.osc.util.BootTrace.mark("appDone");
    }

    /**
     * ★ r216：启动期后台 IO 线程。
     *
     * <p>单线程 + 顺序执行：这些任务都是磁盘 IO，并发起来只会互相拖慢（盒子上的 flash
     * 随机读性能有限），串行反而更快，也不会和首屏的配置解析抢带宽。</p>
     */
    private static final java.util.concurrent.ExecutorService BOOT_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "tvbox-boot-io");
                    t.setDaemon(true);
                    return t;
                }
            });

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