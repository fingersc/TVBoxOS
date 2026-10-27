package xyz.doikki.videoplayer.util;

import android.util.Log;

/**
 * player 模块的日志出口（v99-r89）。
 *
 * <p><b>为什么需要它</b>：app 的调试模式开关在 {@code com.github.tvbox.osc.util.LOG}，
 * 而 player 模块不能反向依赖 app，所以这里过去直接用了 {@code android.util.Log} ——
 * 结果是<b>这 10 条日志完全绕过调试模式门控</b>：用户关掉调试模式，它们照样在正式使用中持续刷 logcat。</p>
 *
 * <p>现在统一从这里出：开关由 app 侧 {@code LOG.setDebugEnabled(boolean)} <b>单向同步</b>过来，
 * 「调试模式」仍然是唯一真相源，player 模块不自建第二个开关。</p>
 *
 * <p>tag 保持 {@code Tvbox-runtime} 不变，现有的排查口径（日志过滤命令、历史日志）可以继续沿用。</p>
 *
 * <p>注意：本类<b>不设"总是输出"的逃生口</b>。播放错误的完整堆栈由 Exo 自己以
 * {@code ExoPlayerImplInternal} 等 tag 打印，那部分不归我们控制、也不受本开关影响，
 * 所以即便调试模式关着，崩溃现场依然可从系统日志里取到。</p>
 */
public final class PlayLog {

    private static final String TAG = "Tvbox-runtime";

    /** 默认关：与 app 的 LOG 一致（app 启动时会立刻同步一次真实值）。 */
    private static volatile boolean debug = false;

    private PlayLog() {
    }

    /** 由 app 的 {@code LOG.setDebugEnabled} 调用，不要单独调用。 */
    public static void setDebugEnabled(boolean enabled) {
        debug = enabled;
    }

    public static boolean isDebugEnabled() {
        return debug;
    }

    public static void i(String msg) {
        if (!debug) return;
        Log.i(TAG, "" + msg);
    }

    public static void i(String msg, Throwable tr) {
        if (!debug) return;
        Log.i(TAG, "" + msg, tr);
    }

    public static void e(String msg) {
        if (!debug) return;
        Log.e(TAG, "" + msg);
    }

    public static void e(String msg, Throwable tr) {
        if (!debug) return;
        Log.e(TAG, "" + msg, tr);
    }
}
