package com.github.tvbox.osc.util;

import android.os.Build;
import android.text.TextUtils;

import com.github.tvbox.osc.bean.SourceBean;

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
 * 多源搜索调度器（公共类）。
 *
 * <p>把「向大量源并发发起搜索」这件事的调度逻辑从具体页面中抽离出来，
 * 供搜索页（FastSearchActivity / SearchActivity）与详情页切源（DetailActivity）共用。
 *
 * <p><b>职责边界</b>：本类只决定「什么时候发哪个源的请求」，不关心请求怎么发。
 * 真正的请求动作由 {@link TaskRunner} 回调给调用方执行。结果回调也由调用方自行观察，
 * 本类只提供 {@link #finish()} 让调用方在收到结果时通知调度器「这个源完成了」。
 *
 * <p><b>三条推进路径</b>（保证所有源最终都会被发到，不会半途而废）：
 * <ol>
 *   <li><b>提交后延时</b>：每个任务提交后 {@link Config#nextBatchSeconds} 秒，若仍未完成则释放槽位推下一个。
 *       防止个别源卡死不返回导致整个队列停滞。</li>
 *   <li><b>站点级超时</b>：每个任务提交后 {@link Config#siteTimeoutSeconds} 秒仍未完成，判定超时并补位。</li>
 *   <li><b>轮询泵</b>：每 {@link Config#pumpSeconds} 秒检查一次，只要还有等待任务就继续推一批。</li>
 * </ol>
 *
 * <p>这套机制最早实现在 FastSearchActivity 中，此处抽出以消除重复并让切源复用。
 */
public class MultiSourceSearcher {

    /** 每批并发提交的任务数。 */
    private static final int BATCH_SIZE = 6;
    /** 线程池上限，按系统版本自适应。 */
    private static final int MAX_THREAD_COUNT =
            Build.VERSION.SDK_INT >= 35 ? 24 : Build.VERSION.SDK_INT >= 30 ? 18 : 12;
    /** 轮询泵间隔（秒）。 */
    private static final int PUMP_SECONDS = 2;
    /** 任务提交后多久释放槽位（秒）。 */
    private static final int NEXT_BATCH_SECONDS = 3;
    /** 单站点超时（秒）。 */
    private static final int SITE_TIMEOUT_SECONDS = 15;

    /** 调用方实现的搜索动作。 */
    public interface TaskRunner {
        /**
         * 执行一次搜索请求。
         *
         * @param sourceKey 源 key
         * @param title     搜索关键词
         * @param token     本次搜索批次的 token（调用方应原样回传给 {@link #finish(String, String)}）
         */
        void runSearch(String sourceKey, String title, String token);
    }

    /** 状态回调，用于调用方更新 UI 或做收尾判断。 */
    public interface Callback {
        /** 所有任务都已派发完毕（但可能还有未返回的）。 */
        void onAllDispatched();

        /** 有一个源已确定完成（返回结果 / 超时 / 异常）。 */
        void onSourceFinished(String sourceKey);
    }

    /** 调度参数。 */
    public static class Config {
        public int batchSize = BATCH_SIZE;
        public int maxThreadCount = MAX_THREAD_COUNT;
        public int pumpSeconds = PUMP_SECONDS;
        public int nextBatchSeconds = NEXT_BATCH_SECONDS;
        public int siteTimeoutSeconds = SITE_TIMEOUT_SECONDS;
    }

    private final Config config;
    private final TaskRunner runner;
    private final Callback callback;
    /** 是否把 type==3 的阻塞型源排到最后再发。 */
    private final boolean blockingLast;

    private ExecutorService executor;
    private ScheduledExecutorService scheduler;

    private final AtomicInteger tokenSeq = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger timedOutCount = new AtomicInteger(0);

    private final Set<String> pendingKeys = Collections.synchronizedSet(new HashSet<String>());
    private final List<Task> waitingTasks = Collections.synchronizedList(new ArrayList<Task>());
    private final Set<String> startedKeys = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> releasedKeys = Collections.synchronizedSet(new HashSet<String>());

    private String currentToken = "";
    private boolean paused = false;
    private boolean allDispatchedNotified = false;

    public MultiSourceSearcher(TaskRunner runner, Callback callback) {
        this(runner, callback, new Config(), true);
    }

    public MultiSourceSearcher(TaskRunner runner, Callback callback, Config config, boolean blockingLast) {
        this.runner = runner;
        this.callback = callback;
        this.config = config == null ? new Config() : config;
        this.blockingLast = blockingLast;
    }

    public boolean isPaused() {
        return paused;
    }

    public int getTotalCount() {
        return totalCount.get();
    }

    public int getRemainingCount() {
        synchronized (pendingKeys) {
            return pendingKeys.size();
        }
    }

    public int getTimedOutCount() {
        return timedOutCount.get();
    }

    /**
     * 开始一批新的搜索。
     *
     * @param sourceKeys 参与搜索的源 key 列表（调用方已按期望顺序排好）
     * @param title      搜索关键词
     */
    public void start(List<String> sourceKeys, String title) {
        shutdown();
        reset();
        if (sourceKeys == null || sourceKeys.isEmpty() || TextUtils.isEmpty(title)) {
            notifyAllDispatched();
            return;
        }
        currentToken = String.valueOf(tokenSeq.incrementAndGet());

        List<Task> blockingTasks = new ArrayList<>();
        for (String key : sourceKeys) {
            if (TextUtils.isEmpty(key)) {
                continue;
            }
            Task task = new Task(key, title, currentToken);
            if (blockingLast && task.blocking) {
                blockingTasks.add(task);
            } else {
                waitingTasks.add(task);
            }
        }
        // 阻塞型源排在最后，避免拖慢整体首屏
        waitingTasks.addAll(blockingTasks);

        synchronized (pendingKeys) {
            for (Task task : waitingTasks) {
                pendingKeys.add(task.sourceKey);
            }
        }
        totalCount.set(pendingKeys.size());
        if (totalCount.get() <= 0) {
            notifyAllDispatched();
            return;
        }

        executor = createExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        startNextBatch(currentToken);
        startPump(currentToken);
    }

    /**
     * 由调用方在收到某个源的结果（或判定失败）时调用。
     *
     * @return true 表示该源此前确实处于「未完成」状态，本次调用生效
     */
    public boolean finish(String sourceKey, String token) {
        if (!isCurrentToken(token)) {
            return false;
        }
        synchronized (pendingKeys) {
            if (TextUtils.isEmpty(sourceKey)) {
                return false;
            }
            if (!pendingKeys.remove(sourceKey)) {
                return false;
            }
        }
        releaseSlotAndStartNext(sourceKey, token);
        if (pendingKeys.isEmpty()) {
            notifyAllDispatched();
        }
        if (callback != null) {
            callback.onSourceFinished(sourceKey);
        }
        return true;
    }

    /** 主动标记某个源超时。 */
    public void timeout(String sourceKey, String token) {
        if (finish(sourceKey, token)) {
            timedOutCount.incrementAndGet();
        }
    }

    /** 暂停：中断所有进行中的请求，保留待发队列以便恢复。 */
    public void pause() {
        try {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
            paused = !pendingKeys.isEmpty();
            if (paused) {
                currentToken = "";
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 恢复：把剩余未完成的源重新排队继续搜。 */
    public void resume() {
        if (!paused) {
            return;
        }
        paused = false;
        List<String> remain;
        synchronized (pendingKeys) {
            remain = new ArrayList<>(pendingKeys);
        }
        if (remain.isEmpty()) {
            notifyAllDispatched();
            return;
        }
        waitingTasks.clear();
        startedKeys.clear();
        releasedKeys.clear();
        allDispatchedNotified = false;
        currentToken = String.valueOf(tokenSeq.incrementAndGet());
        for (String key : remain) {
            waitingTasks.add(new Task(key, lastTitle, currentToken));
        }
        if (executor == null || executor.isShutdown()) {
            executor = createExecutor();
        }
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }
        startNextBatch(currentToken);
        startPump(currentToken);
    }

    /** 停止并释放资源。 */
    public void shutdown() {
        try {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        pendingKeys.clear();
        waitingTasks.clear();
        startedKeys.clear();
        releasedKeys.clear();
        currentToken = "";
        paused = false;
        allDispatchedNotified = false;
        totalCount.set(0);
        timedOutCount.set(0);
    }

    // ==================== 内部实现 ====================

    private String lastTitle = "";

    private void reset() {
        waitingTasks.clear();
        pendingKeys.clear();
        startedKeys.clear();
        releasedKeys.clear();
        paused = false;
        allDispatchedNotified = false;
        totalCount.set(0);
        timedOutCount.set(0);
    }

    private ExecutorService createExecutor() {
        return new ThreadPoolExecutor(
                0, config.maxThreadCount, 30L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
    }

    private void startNextBatch(String token) {
        for (int i = 0; i < config.batchSize; i++) {
            if (!startNextTask(token)) {
                return;
            }
        }
    }

    private boolean startNextTask(String token) {
        if (!isCurrentToken(token)) {
            return false;
        }
        Task task = takeNextTask(token);
        if (task == null) {
            return false;
        }
        if (!submit(task)) {
            startedKeys.remove(task.sourceKey);
            synchronized (waitingTasks) {
                waitingTasks.add(0, task);
            }
            return false;
        }
        return true;
    }

    private Task takeNextTask(String token) {
        synchronized (waitingTasks) {
            while (!waitingTasks.isEmpty()) {
                Task task = waitingTasks.remove(0);
                if (!isPending(task.sourceKey, token) || !startedKeys.add(task.sourceKey)) {
                    continue;
                }
                return task;
            }
        }
        return null;
    }

    private boolean submit(Task task) {
        if (!isPending(task.sourceKey, task.token)) {
            return false;
        }
        if (executor == null || executor.isShutdown()) {
            return false;
        }
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runTask(task);
                }
            });
        } catch (RejectedExecutionException e) {
            return false;
        }
        scheduleAdvance(task.sourceKey, task.token);
        scheduleTimeout(task.sourceKey, task.token);
        return true;
    }

    private void runTask(final Task task) {
        if (!isPending(task.sourceKey, task.token)) {
            return;
        }
        lastTitle = task.title;
        try {
            runner.runSearch(task.sourceKey, task.title, task.token);
        } catch (Throwable th) {
            th.printStackTrace();
            finish(task.sourceKey, task.token);
        }
    }

    /** 提交后 3 秒：若仍未完成，释放槽位推下一个（不等于判定失败）。 */
    private void scheduleAdvance(final String sourceKey, final String token) {
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    if (!isCurrentToken(token)) {
                        return;
                    }
                    if (isPending(sourceKey, token) && releaseSlot(sourceKey, token)) {
                        startNextTask(token);
                    }
                }
            }, config.nextBatchSeconds, TimeUnit.SECONDS);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 站点级超时：判定该源失败并补位。 */
    private void scheduleTimeout(final String sourceKey, final String token) {
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    if (!isCurrentToken(token)) {
                        return;
                    }
                    timeout(sourceKey, token);
                }
            }, config.siteTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 轮询泵：只要还有等待任务就继续推，保证全部源都被发到。 */
    private void startPump(final String token) {
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!isCurrentToken(token)) {
                            return;
                        }
                        if (getWaitingCount() > 0 && !pendingKeys.isEmpty()) {
                            startNextBatch(token);
                        } else if (pendingKeys.isEmpty()) {
                            notifyAllDispatched();
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            }, config.pumpSeconds, config.pumpSeconds, TimeUnit.SECONDS);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private boolean releaseSlot(String sourceKey, String token) {
        if (!isCurrentToken(token) || TextUtils.isEmpty(sourceKey)) {
            return false;
        }
        return releasedKeys.add(sourceKey);
    }

    private void releaseSlotAndStartNext(String sourceKey, String token) {
        if (releaseSlot(sourceKey, token)) {
            startNextTask(token);
        }
    }

    private boolean isPending(String sourceKey, String token) {
        if (!isCurrentToken(token) || TextUtils.isEmpty(sourceKey)) {
            return false;
        }
        synchronized (pendingKeys) {
            return pendingKeys.contains(sourceKey);
        }
    }

    private boolean isCurrentToken(String token) {
        return !TextUtils.isEmpty(token) && token.equals(currentToken);
    }

    private int getWaitingCount() {
        synchronized (waitingTasks) {
            return waitingTasks.size();
        }
    }

    private void notifyAllDispatched() {
        if (allDispatchedNotified) {
            return;
        }
        allDispatchedNotified = true;
        if (callback != null) {
            callback.onAllDispatched();
        }
    }

    /** 单个源的搜索任务。 */
    private class Task {
        final String sourceKey;
        final String title;
        final String token;
        final boolean blocking;

        Task(String sourceKey, String title, String token) {
            this.sourceKey = sourceKey;
            this.title = title;
            this.token = token;
            this.blocking = isBlockingSource(sourceKey);
        }
    }

    /** type==3 为阻塞型源（如需要本地 JS 解析的重型源），排到最后。 */
    private static boolean isBlockingSource(String sourceKey) {
        try {
            SourceBean bean = com.github.tvbox.osc.api.ApiConfig.get().getSource(sourceKey);
            return bean == null || bean.getType() == 3;
        } catch (Throwable th) {
            return true;
        }
    }
}
