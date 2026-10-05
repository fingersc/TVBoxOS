package com.github.tvbox.osc.viewmodel;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.LOG;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 聚合搜索会话：承载「一轮聚合搜索」的全部可变状态与线程池。
 *
 * <p><b>为什么必须独立于 Activity</b>：原实现把这些状态直接放在
 * {@code SearchActivity} 上，{@code onDestroy()} 里无条件 {@code shutdownNow()}。
 * 结果是搜索与「搜索页这个 Activity」死死绑定 —— 一旦 Activity 被系统回收
 * （低内存、旋转、或进详情页后回收），正在跑的聚合搜索连同候选池一起蒸发。
 * 用户看到的正是「进详情页再返回，搜索结果要重跑一遍」。</p>
 *
 * <p><b>本轮改动的两点</b>：</p>
 * <ol>
 *   <li><b>执行体解耦</b>：状态与线程池搬进本类，由 {@link SourceViewModel} 持有
 *       （ViewModel 生命周期长于 Activity，且随 Activity 重建存活）。</li>
 *   <li><b>token 语义修正</b>：token 只标记「一轮搜索」，进详情页
 *       <b>不再自增 token、不再重跑 pending 源</b>，只把派发暂停/降速。
 *       这修掉了「点击时刻快照不完整」的根因 —— 原来 {@code openSearchVideo()}
 *       会 {@code pauseSearchTasks()} 把 token 清空，返回时
 *       {@code resumePausedSearches()} 又用<b>新 token</b> 从头重跑，
 *       导致进详情页那一刻打包给详情页的候选池永远是残缺的。</li>
 * </ol>
 *
 * <p><b>线程安全</b>：所有集合读写都在 {@code synchronized(lock)} 内；
 * 派发/调度回调可能在任意线程到达，故对外暴露的方法均自带同步。</p>
 */
public class SearchSession {

    private static final String TAG = "SearchSession";

    /** 单批并发派发数。维持 6 不动（降线程数不对症，见 v46 结论）。 */
    public static final int SEARCH_THREAD_COUNT = 6;
    public static final int SEARCH_MAX_THREAD_COUNT =
            Build.VERSION.SDK_INT >= 35 ? 24 : Build.VERSION.SDK_INT >= 30 ? 18 : 12;
    public static final int SEARCH_NEXT_BATCH_SECONDS = 3;
    public static final int SEARCH_SITE_TIMEOUT_SECONDS = 15;

    /**
     * 起播保护窗上限（毫秒）。
     *
     * <p>详情页起播时，spider 的 {@code playerContent}（JS 执行）会与搜索的
     * {@code searchContent}（同样是 JS 执行）抢同一条 QuickJS 队列。为让起播
     * 阶段的 JS 先跑完，窗口期内暂停<b>新</b>搜索任务的派发；<b>在途请求不动</b>
     * —— 强行中断会让它们全部超时，反而往 {@code SourceQualityStore} 里写入
     * 一堆假的失败记录。</p>
     *
     * <p>取值依据：本项目 {@code DETAIL_FALLBACK_SOURCE_TIMEOUT_MS = 3500} 是
     * 单源超时基准，5s ≈ 1.5 倍；而 {@code DETAIL_FALLBACK_SEARCH_TIMEOUT_MS = 8000}
     * 是整轮预算，拿来做窗口等于把搜索停掉一整轮，代价过大。
     * 窗口以「首帧到达」事件优先结束（快源通常 2s 内），5s 只是兜底。</p>
     */
    public static final long STARTUP_GUARD_MAX_MS = 5000L;

    /**
     * 进程级单例。
     *
     * <p><b>为什么必须是单例</b>：本项目每个 Activity 都各自
     * {@code new ViewModelProvider(this)}，因此 SearchActivity 与 DetailActivity
     * 拿到的是<b>两个不同的 SourceViewModel</b>。若会话挂在 ViewModel 上，
     * 详情页读到的是一份<b>全新空会话</b> —— 这正是「搜索页候选在涨、
     * 详情页切源数量却不动」的根因（候选池不共享）。</p>
     *
     * <p>会话代表「当前这一轮聚合搜索」，天然是进程级的东西，
     * 与某个 Activity 的存亡无关，故提升为单例（对齐 {@code App} 的既有风格）。</p>
     */
    private static volatile SearchSession instance;

    /** 取得进程级单例会话。 */
    public static SearchSession getInstance() {
        if (instance == null) {
            synchronized (SearchSession.class) {
                if (instance == null) {
                    instance = new SearchSession(null);
                }
            }
        }
        return instance;
    }

    private final Object lock = new Object();

    /**
     * 触发搜索所需的目标 ViewModel。
     *
     * <p>用 {@link java.lang.ref.WeakReference} 持有：单例会话生命周期长于任何
     * Activity，若强引用其 ViewModel，SearchActivity 销毁后无法回收（内存泄漏）。
     * 搜索<b>只在 Activity 活跃期触发</b>（此时 ViewModel 必被强引用持有），
     * 弱引用不会失效；详情页只<b>读</b>候选池，不需要它。</p>
     */
    private java.lang.ref.WeakReference<SourceViewModel> viewModelRef;

    private ExecutorService searchExecutorService;
    private ScheduledExecutorService searchTimeoutExecutor;
    private final AtomicInteger allRunCount = new AtomicInteger(0);
    private final Set<String> pendingSearchKeys = Collections.synchronizedSet(new HashSet<String>());
    private final List<SearchTask> waitingSearchTasks = Collections.synchronizedList(new ArrayList<SearchTask>());
    private final Set<String> startedSearchKeys = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> releasedSearchKeys = Collections.synchronizedSet(new HashSet<String>());
    private final AtomicInteger searchTokenSeq = new AtomicInteger(0);
    private final AtomicInteger totalSearchCount = new AtomicInteger(0);

    private String currentSearchToken = "";
    private String searchTitle = "";

    /**
     * 「暂停派发」标志。区别于旧的 {@code searchPaused}：旧标志表示
     * 「整轮搜索被掐断、token 已失效」，需要靠返回时重跑恢复；本标志只表示
     * 「暂时不再派发新任务」，token 与 pending 集合<b>全部保留</b>，
     * 恢复时原地续跑，不重头来。
     */
    private boolean dispatchingPaused = false;

    /** 起播保护窗是否生效中。 */
    private boolean startupGuardActive = false;
    private final Handler guardHandler = new Handler(Looper.getMainLooper());
    private final Runnable startupGuardExpire = new Runnable() {
        @Override
        public void run() {
            releaseStartupGuard("timeout");
        }
    };

    /** 候选池：标题 -> 候选列表。单一真相源，替代原先「打包进 Intent 的快照」。 */
    private final java.util.LinkedHashMap<String, List<Movie.Video>> candidatePool =
            new java.util.LinkedHashMap<String, List<Movie.Video>>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, List<Movie.Video>> eldest) {
                    return size() > CANDIDATE_POOL_MAX_TITLES;
                }
            };

    /**
     * 候选池最多保留多少部片（内存层 LRU）。
     *
     * <p>与落盘层的 {@code FALLBACK_CACHE_MAX_ENTRIES = 40} 对齐。此前内存层
     * <b>没有任何上限</b>，全靠 {@code DetailActivity.onDestroy()} 整体清空兜底；
     * 搜索执行体解耦后这道闸门消失，必须显式设限，否则长会话下
     * 「每访问一部新片多一个 key」会无界增长。</p>
     */
    public static final int CANDIDATE_POOL_MAX_TITLES = 40;

    public SearchSession(SourceViewModel viewModel) {
        setViewModel(viewModel);
    }

    /**
     * 绑定/解绑触发搜索用的 ViewModel。
     *
     * <p>由「搜索发起方」（{@code SearchActivity} 经
     * {@code SourceViewModel.getSearchSession()}）调用，把自己注册进来 ——
     * 保证「谁在搜，就用谁的 {@code getSearch} 发请求、结果就回投到谁观察的
     * LiveData」。</p>
     *
     * <p><b>★ 只读方（详情页）绝不能调这个</b>：详情页只读候选池、不发起搜索。
     * 若它也来注册，会把触发通道换成详情页的 ViewModel，导致后台续跑的搜索
     * 把结果投进详情页的 {@code searchResult}（无人观察）→ 搜索页不再更新。
     * 只读方一律用 {@link #getInstance()}。</p>
     */
    public void setViewModel(SourceViewModel viewModel) {
        synchronized (lock) {
            this.viewModelRef = viewModel == null ? null
                    : new java.lang.ref.WeakReference<>(viewModel);
        }
    }

    /**
     * 只读获取（不注册触发通道）。
     *
     * <p>供详情页这类「只读候选池」的场景使用，语义上等同于
     * {@link #getInstance()}，单独命名是为了在调用点一眼看出
     * 「这里不该动触发通道」。</p>
     */
    public static SearchSession getShared() {
        return getInstance();
    }

    /** 当前可用的触发通道；已被回收或无绑定则返回 null。 */
    private SourceViewModel currentViewModel() {
        synchronized (lock) {
            return viewModelRef == null ? null : viewModelRef.get();
        }
    }

    // ==================== 生命周期 ====================

    /** 置空但不销毁线程池（Activity 重建后复用）。 */
    public void attachSearchTitle(String title) {
        synchronized (lock) {
            this.searchTitle = title == null ? "" : title;
        }
    }

    public String getSearchTitle() {
        synchronized (lock) {
            return searchTitle;
        }
    }

    public String getCurrentToken() {
        synchronized (lock) {
            return currentSearchToken;
        }
    }

    public int getAllRunCount() {
        return allRunCount.get();
    }

    public int getTotalSearchCount() {
        return totalSearchCount.get();
    }

    public boolean isDispatchingPaused() {
        synchronized (lock) {
            return dispatchingPaused;
        }
    }

    /** 是否还有一轮搜索正在进行（有待派发 / 在途）。 */
    public boolean isRunning() {
        synchronized (lock) {
            return !TextUtils.isEmpty(currentSearchToken) && allRunCount.get() > 0;
        }
    }

    /**
     * 本轮搜索是否「已经结束、但结果还在」。
     *
     * <p>用于 Activity 重建时判断：同一关键词的搜索结果已经产出，
     * 不该重跑（重跑会让用户等待，还会把已有列表清空重排）。</p>
     */
    public boolean isRoundFinishedWithResults(String title) {
        synchronized (lock) {
            if (TextUtils.isEmpty(currentSearchToken) || allRunCount.get() > 0) {
                return false;
            }
            if (title == null || !title.equals(searchTitle)) {
                return false;
            }
            return !candidatePool.isEmpty();
        }
    }

    /**
     * 本轮搜索是否「可复用」：正在跑，或已跑完且结果还在，且关键词一致。
     */
    public boolean canReuseRound(String title) {
        if (title == null) {
            return false;
        }
        synchronized (lock) {
            if (TextUtils.isEmpty(currentSearchToken) || !title.equals(searchTitle)) {
                return false;
            }
        }
        return isRunning() || isRoundFinishedWithResults(title);
    }

    // ==================== 一轮搜索的起点 ====================

    /**
     * 开启一轮全新的聚合搜索。
     *
     * <p>与旧 {@code searchResult()} 的区别：旧实现会先 {@code shutdownNow()} 再重建，
     * 本方法同样如此（这是「用户主动发起新搜索」的合法场景），但<b>不再被
     * 进详情页这种无关事件触发</b>。</p>
     */
    public void startNewRound(String title, List<SourceBean> sourceList) {
        synchronized (lock) {
            shutdownPoolLocked();
            pendingSearchKeys.clear();
            waitingSearchTasks.clear();
            startedSearchKeys.clear();
            releasedSearchKeys.clear();
            allRunCount.set(0);
            totalSearchCount.set(0);
            dispatchingPaused = false;
            clearStartupGuardLocked();
            searchTitle = title == null ? "" : title;
            currentSearchToken = String.valueOf(searchTokenSeq.incrementAndGet());
        }

        ArrayList<SearchTask> tasks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String roundToken;
        synchronized (lock) {
            roundToken = currentSearchToken;
        }
        for (SourceBean bean : sourceList) {
            if (bean == null || !bean.isSearchable()) {
                continue;
            }
            String key = bean.getKey();
            if (TextUtils.isEmpty(key) || !seen.add(key)) {
                continue;
            }
            tasks.add(new SearchTask(key, searchTitle, roundToken, isBlocking(bean)));
        }
        if (tasks.isEmpty()) {
            return;
        }
        synchronized (lock) {
            for (SearchTask task : tasks) {
                pendingSearchKeys.add(task.sourceKey);
            }
            allRunCount.set(tasks.size());
            totalSearchCount.set(tasks.size());
            searchExecutorService = createExecutor();
            searchTimeoutExecutor = Executors.newSingleThreadScheduledExecutor();
            waitingSearchTasks.addAll(tasks);
        }
        // ★ 快派发（非阻塞源）放到锁外：getSearch() 可能同步回调进来，
        //   持锁调用外部代码有死锁风险（本类方法大多会再取锁）。
        startFastSearchTasks(tasks);
        startNextBatch(roundToken);
    }

    /**
     * 暂停「新任务的派发」，但<b>保留 token 与 pending 集合</b>。
     *
     * <p>用于进详情页 / 起播保护窗。<b>不做</b> {@code shutdownNow()} —— 在途请求
     * 继续跑完，其结果照常回流候选池。</p>
     */
    public void pauseDispatch() {
        synchronized (lock) {
            dispatchingPaused = true;
        }
    }

    /**
     * 恢复派发（原地续跑，不自增 token、不重跑已完成项）。
     *
     * <p><b>★ 为什么是「补满在途槽」而不是「再发一批 6 个」</b>：</p>
     *
     * <p>暂停期间，在途任务完成时会走 {@code scheduleAdvance → startNextTask}，
     * 但被 {@code dispatchingPaused} 挡下。而它的槽位在 {@code releaseSlot} 里
     * <b>已经被标记释放</b> —— 即「该位置空出来了，但没人补位」。
     * 若不区分地一律再发 6 个，会把并发数顶到 6 + 残留空位，
     * 或在残留空位很多时仍然补不满。</p>
     *
     * <p>正确做法：按「已派发 − 已释放 = 真实在途数」，只补足差额。</p>
     */
    public void resumeDispatch() {
        String token;
        int inFlight;
        synchronized (lock) {
            if (!dispatchingPaused) {
                return;
            }
            dispatchingPaused = false;
            token = currentSearchToken;
            inFlight = inFlightCountLocked();
        }
        // 补满到单批并发数；暂停期间被挡下的推进在这里一次性补齐。
        int need = SEARCH_THREAD_COUNT - inFlight;
        for (int i = 0; i < need; i++) {
            if (!startNextTask(token)) {
                break;
            }
        }
    }

    /** 真实在途任务数 = 已派发 key − 已释放 key（均在同一把锁内读取）。 */
    private int inFlightCountLocked() {
        int released = 0;
        for (String key : startedSearchKeys) {
            if (releasedSearchKeys.contains(key)) {
                released++;
            }
        }
        return startedSearchKeys.size() - released;
    }

    /** 彻底取消本轮搜索（用户重新搜索 / 退出搜索页）。 */
    public void cancelRound() {
        synchronized (lock) {
            shutdownPoolLocked();
            pendingSearchKeys.clear();
            waitingSearchTasks.clear();
            startedSearchKeys.clear();
            releasedSearchKeys.clear();
            allRunCount.set(0);
            dispatchingPaused = false;
            clearStartupGuardLocked();
            currentSearchToken = "";
        }
    }

    // ==================== 起播保护窗（方向 5） ====================

    /**
     * 开启起播保护窗：窗口内暂停新任务派发，首帧到达或超时后自动恢复。
     *
     * @param reason 触发来源（仅诊断用）
     */
    public void beginStartupGuard(final String reason) {
        synchronized (lock) {
            if (startupGuardActive) {
                return;             // 已在窗口内，不重复计时
            }
            startupGuardActive = true;
        }
        // ★ 走免门控通道（LOG.sw）：release 包上 LOG.longI 会被 VERBOSE 门控吞掉，
        //   而「起播保护窗何时开/关」是现场排障的关键节点，必须任何构建都可见。
        LOG.sw("[GUARD] begin reason=" + reason + " maxMs=" + STARTUP_GUARD_MAX_MS);
        guardHandler.removeCallbacks(startupGuardExpire);
        guardHandler.postDelayed(startupGuardExpire, STARTUP_GUARD_MAX_MS);
        pauseDispatch();
    }

    /** 首帧到达 → 立刻结束保护窗（不等 5s 兜底）。 */
    public void endStartupGuardOnFirstFrame() {
        releaseStartupGuard("firstFrame");
    }

    private void releaseStartupGuard(String reason) {
        boolean wasActive;
        synchronized (lock) {
            wasActive = startupGuardActive;
            startupGuardActive = false;
            if (!wasActive) {
                return;
            }
        }
        guardHandler.removeCallbacks(startupGuardExpire);
        LOG.sw("[GUARD] end reason=" + reason);
        resumeDispatch();
    }

    private void clearStartupGuardLocked() {
        startupGuardActive = false;
        guardHandler.removeCallbacks(startupGuardExpire);
    }

    // ==================== 结果回流 ====================

    /**
     * 一轮搜索中某个源返回时的处理入口。
     *
     * <p>负责：代际校验 → 记账 → <b>增量写入候选池</b>（替代旧的「点击时快照」）
     * → 推进下一批。</p>
     *
     * @return true 表示该结果是当前有效代际、已被消费
     */
    public boolean onSourceResult(AbsXml absXml) {
        if (absXml == null) {
            return false;
        }
        String token = absXml.searchToken;
        String sourceKey = absXml.sourceKey;
        if (!isCurrentToken(token)) {
            return false;               // 旧轮次迟到结果，丢弃（方向 6）
        }
        if (!markSourceFinished(sourceKey, token)) {
            return false;               // 重复回调或非 pending 源
        }
        if (absXml.movie != null && absXml.movie.videoList != null) {
            mergeIntoCandidatePool(absXml.movie.videoList);
        }
        // ★ 免门控进度日志：现场只需 adb logcat -s TVBox-switch 就能看到
        //   「还有几个源在搜 / 候选池当前规模」，用来判断搜索是否真的在推进。
        LOG.sw("[SEARCH] result src=" + sourceKey
                + " remain=" + allRunCount.get()
                + " pool=" + poolSizeLocked());
        releaseSlotAndAdvance(sourceKey, token);
        return true;
    }

    /** 候选池当前总候选数（所有片名求和），仅用于诊断。 */
    private int poolSizeLocked() {
        synchronized (lock) {
            int n = 0;
            for (List<Movie.Video> list : candidatePool.values()) {
                if (list != null) {
                    n += list.size();
                }
            }
            return n;
        }
    }

    /**
     * 把命中的视频增量并入候选池。
     *
     * <p><b>这是修掉「切源只有两三个站」的核心</b>：旧实现把候选池在
     * 「点击搜索结果」那一刻打包成快照塞进 Intent，而聚合搜索当时还在分批跑，
     * 快照必然残缺。现在候选池是单一真相源，边搜边补，详情页随时读到的是
     * <b>当前最新</b>的完整集合。</p>
     */
    private void mergeIntoCandidatePool(List<Movie.Video> videos) {
        synchronized (lock) {
            for (Movie.Video video : videos) {
                if (video == null || TextUtils.isEmpty(video.id) || TextUtils.isEmpty(video.name)) {
                    continue;
                }
                String title = video.name.trim();
                List<Movie.Video> list = candidatePool.get(title);
                if (list == null) {
                    list = new ArrayList<>();
                    candidatePool.put(title, list);
                }
                String key = (video.sourceKey == null ? "" : video.sourceKey) + "|" + video.id;
                boolean exists = false;
                for (Movie.Video cached : list) {
                    String cachedKey = (cached.sourceKey == null ? "" : cached.sourceKey) + "|" + cached.id;
                    if (key.equals(cachedKey)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    list.add(video);
                }
            }
        }
    }

    /** 读取某部片的候选池（只读副本，调用方不得修改）。 */
    public List<Movie.Video> getCandidates(String title) {
        if (title == null) {
            return new ArrayList<>();
        }
        synchronized (lock) {
            List<Movie.Video> list = candidatePool.get(title.trim());
            return list == null ? new ArrayList<Movie.Video>() : new ArrayList<>(list);
        }
    }

    public int getCandidateCount(String title) {
        if (title == null) {
            return 0;
        }
        synchronized (lock) {
            List<Movie.Video> list = candidatePool.get(title.trim());
            return list == null ? 0 : list.size();
        }
    }

    public void clearCandidates() {
        synchronized (lock) {
            candidatePool.clear();
        }
    }

    // ==================== 内部：派发 ====================

    private ExecutorService createExecutor() {
        return new ThreadPoolExecutor(0, SEARCH_MAX_THREAD_COUNT, 30L,
                TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
    }

    private void shutdownPoolLocked() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
                JsLoader.stopAll();
            }
            if (searchTimeoutExecutor != null) {
                searchTimeoutExecutor.shutdownNow();
                searchTimeoutExecutor = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void startNextBatch(String token) {
        for (int i = 0; i < SEARCH_THREAD_COUNT; i++) {
            if (!startNextTask(token)) {
                return;
            }
        }
    }

    private boolean startNextTask(String token) {
        if (!isCurrentToken(token)) {
            return false;
        }
        synchronized (lock) {
            if (dispatchingPaused) {
                return false;           // 起播保护窗 / 进详情页期间不派发新任务
            }
        }
        SearchTask task = takeNextTask(token);
        if (task == null) {
            return false;
        }
        if (!submitTask(task)) {
            startedSearchKeys.remove(task.sourceKey);
            synchronized (waitingSearchTasks) {
                waitingSearchTasks.add(0, task);
            }
            return false;
        }
        return true;
    }

    private SearchTask takeNextTask(String token) {
        synchronized (waitingSearchTasks) {
            while (!waitingSearchTasks.isEmpty()) {
                SearchTask task = waitingSearchTasks.remove(0);
                if (!isSourcePending(task.sourceKey, token)
                        || !startedSearchKeys.add(task.sourceKey)) {
                    continue;
                }
                return task;
            }
        }
        return null;
    }

    private boolean submitTask(SearchTask task) {
        if (!isSourcePending(task.sourceKey, task.searchToken)) {
            return false;
        }
        ExecutorService executor;
        ScheduledExecutorService scheduler;
        synchronized (lock) {
            executor = searchExecutorService;
            scheduler = searchTimeoutExecutor;
        }
        if (executor == null || executor.isShutdown()) {
            return false;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            return false;
        }
        scheduleAdvance(task.sourceKey, task.searchToken, scheduler);
        scheduleTimeout(task.sourceKey, task.searchToken, scheduler);
        return true;
    }

    private void scheduleAdvance(final String sourceKey, final String searchToken,
                                ScheduledExecutorService scheduler) {
        if (scheduler == null) {
            return;
        }
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentToken(searchToken)) {
                    return;
                }
                if (isSourcePending(sourceKey, searchToken)
                        && releaseSlot(sourceKey, searchToken)) {
                    startNextTask(searchToken);
                }
            }
        }, SEARCH_NEXT_BATCH_SECONDS, TimeUnit.SECONDS);
    }

    private void scheduleTimeout(final String sourceKey, final String searchToken,
                                 ScheduledExecutorService scheduler) {
        if (scheduler == null) {
            return;
        }
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentToken(searchToken)) {
                    return;
                }
                if (markSourceFinished(sourceKey, searchToken)) {
                    releaseSlotAndAdvance(sourceKey, searchToken);
                }
            }
        }, SEARCH_SITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 快派发：{@code type != 3} 的源不阻塞（不需要 JS 引擎），直接发出，
     * 不等批窗口。
     *
     * <p><b>必须在锁外调用</b>：{@code getSearch()} 可能同步触发回调，
     * 而回调路径会再次进入本类的同步方法。</p>
     */
    private void startFastSearchTasks(List<SearchTask> tasks) {
        for (SearchTask task : tasks) {
            if (task.blocking) {
                continue;
            }
            if (!startedSearchKeys.add(task.sourceKey)) {
                continue;
            }
            ScheduledExecutorService scheduler;
            synchronized (lock) {
                scheduler = searchTimeoutExecutor;
            }
            if (!sourceViewModelSubmit(task)) {
                startedSearchKeys.remove(task.sourceKey);
                continue;
            }
            scheduleTimeout(task.sourceKey, task.searchToken, scheduler);
        }
    }

    private boolean sourceViewModelSubmit(SearchTask task) {
        SourceViewModel viewModel = currentViewModel();
        if (viewModel == null) {
            return false;
        }
        try {
            viewModel.getSearch(task.sourceKey, task.title, task.searchToken);
            return true;
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }

    private void releaseSlotAndAdvance(String sourceKey, String searchToken) {
        if (releaseSlot(sourceKey, searchToken)) {
            startNextTask(searchToken);
        }
    }

    // ==================== 内部：状态判定 ====================

    public boolean isCurrentToken(String token) {
        synchronized (lock) {
            return !TextUtils.isEmpty(token) && token.equals(currentSearchToken);
        }
    }

    private boolean isSourcePending(String sourceKey, String token) {
        if (!isCurrentToken(token) || TextUtils.isEmpty(sourceKey)) {
            return false;
        }
        synchronized (pendingSearchKeys) {
            return pendingSearchKeys.contains(sourceKey);
        }
    }

    private boolean markSourceFinished(String sourceKey, String token) {
        if (!isCurrentToken(token)) {
            return false;
        }
        synchronized (pendingSearchKeys) {
            if (TextUtils.isEmpty(sourceKey) || !pendingSearchKeys.remove(sourceKey)) {
                return false;
            }
            allRunCount.set(pendingSearchKeys.size());
            return true;
        }
    }

    private boolean releaseSlot(String sourceKey, String token) {
        if (!isCurrentToken(token) || TextUtils.isEmpty(sourceKey)) {
            return false;
        }
        return releasedSearchKeys.add(sourceKey);
    }

    private static boolean isBlocking(SourceBean bean) {
        return bean == null || bean.getType() == 3;
    }

    /** 搜索任务：每个源一个，只做一件事 —— 触发该源的搜索。 */
    private class SearchTask implements Runnable {
        private final String sourceKey;
        private final String title;
        private final String searchToken;
        private final boolean blocking;

        private SearchTask(String sourceKey, String title, String searchToken, boolean blocking) {
            this.sourceKey = sourceKey;
            this.title = title;
            this.searchToken = searchToken;
            this.blocking = blocking;
        }

        @Override
        public void run() {
            if (!isSourcePending(sourceKey, searchToken)) {
                return;
            }
            try {
                SourceViewModel viewModel = currentViewModel();
                if (viewModel != null) {
                    viewModel.getSearch(sourceKey, title, searchToken);
                }
            } catch (Throwable th) {
                th.printStackTrace();
                if (markSourceFinished(sourceKey, searchToken)) {
                    releaseSlotAndAdvance(sourceKey, searchToken);
                }
            }
        }
    }
}
