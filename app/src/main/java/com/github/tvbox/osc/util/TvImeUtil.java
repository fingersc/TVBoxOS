package com.github.tvbox.osc.util;

import android.content.Context;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * ★ v99-r20：TV 端输入法唤出原则 —— 全局统一。
 *
 * <p><b>原则：焦点到达输入框时不自动弹出输入法，按 OK 键（方向键确认 / 回车）才唤出。</b>
 * 遥控器操作时，焦点扫过输入框就弹输入法会打断浏览（盒子端实测反馈）。</p>
 *
 * <p>两个入口：</p>
 * <ul>
 *   <li>{@link #clickToIme(EditText...)} —— 抑制自动弹出 + OK 键唤出。绝大多数输入框用这个；
 *       Dialog 内的输入框在 findViewById 之后调用即可；</li>
 *   <li>{@link #suppressAutoIme(EditText...)} —— 只抑制自动弹出，<b>不</b>接管 OK 键。
 *       给 OK 键已有专属语义的输入框用（如搜索页 etSearch：OK = 用当前内容直接搜索，
 *       系统输入法由页面上的自绘键盘切换键唤出）。</li>
 * </ul>
 *
 * <p>注意：{@code setShowSoftInputOnFocus} 是 API 21+；API 19 设备上自动弹出行为保持原样
 * （盒子几乎都在 21+，仅做防崩溃守卫，不做退化模拟）。</p>
 */
public final class TvImeUtil {

    private TvImeUtil() {
    }

    /**
     * 每个输入框「本次聚焦后是否被用户主动唤出过」。
     *
     * <p>焦点兜底会在聚焦后延迟收起输入法，但如果用户在这个窗口内已经按了 OK，
     * 收起动作<b>必须让位</b>，否则会把刚唤出的输入法立刻收掉（用户要的是"点击才唤出"）。</p>
     */
    private static final Map<EditText, Boolean> MANUAL_SHOWN = new WeakHashMap<>();

    /**
     * 焦点到达后检查收起的时刻表（毫秒）。
     *
     * <p>★ v99-r24：原来只有一个 120ms。用户反馈"太慢、希望 1ms 就收"。
     * 但<b>单次 1ms 基本无效</b> —— 那一刻输入法还没来得及弹出来，`hideSoftInput` 无处可收，
     * 之后 IME 才显示，于是照样看到弹窗。所以改成「多次检查」：1ms 抢最快的设备，
     * 40ms / 120ms 兜底那些慢一点的输入法。每一次都会先看 {@link #MANUAL_SHOWN}，
     * 用户已按 OK 就全部跳过。</p>
     */
    private static final long[] SUPPRESS_DELAYS_MS = {1L, 30L, 60L};

    /** 焦点到达不自动弹输入法；按 OK 键 / 回车才唤出。 */
    public static void clickToIme(final EditText... ets) {
        suppressAutoIme(ets);
        if (ets == null) {
            return;
        }
        for (final EditText et : ets) {
            if (et == null) {
                continue;
            }
            et.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN
                            && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        // 用户主动唤出：标记后，焦点兜底的延迟收起会自行跳过
                        MANUAL_SHOWN.put(et, Boolean.TRUE);
                        showIme(et);
                        return true;
                    }
                    return false;
                }
            });
            // ★ v99-r80：触屏设备（手机/平板）没有 OK 键 —— 「按 OK 唤出」永远到不了，
            //   输入法完全唤不出来（r20~r24 只考虑了遥控器路径，属回归）。
            //   补触摸路径：点击输入框 = 用户主动唤出。TV 端 OK 键已被上面的 OnKeyListener
            //   消费（返回 true），不会触发 onClick，遥控器行为不变。
            //   先置 MANUAL_SHOWN 再 showIme：聚焦时排下的 1/30/60ms 延迟收起检查会看到
            //   该标记而全部跳过，不会把刚唤出的键盘立刻收掉（快速点按 <60ms 时也成立）。
            et.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    MANUAL_SHOWN.put(et, Boolean.TRUE);
                    showIme(et);
                }
            });
        }
    }

    /** 只抑制「焦点到达自动弹输入法」，不接管 OK 键（给 OK 键已有专属语义的输入框用）。 */
    public static void suppressAutoIme(final EditText... ets) {
        if (ets == null) {
            return;
        }
        for (final EditText et : ets) {
            if (et == null) {
                continue;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                et.setShowSoftInputOnFocus(false);
            }
            // ★ v99-r22：兜底 —— 光靠 setShowSoftInputOnFocus(false) 挡不住所有输入法。
            //   两条路径：
            //     ① View 层：TextView.onFocusChanged() 按 mShowSoftInputOnFocus 决定是否
            //        showSoftInput ⇒ 设 false 有效；
            //     ② 输入法层：部分 TV 输入法（Gboard / 触宝 / 搜狗 TV 版）自己监听
            //        AccessibilityEvent.TYPE_VIEW_FOCUSED 主动弹出 ⇒ 与 ① 无关，flag 拦不住。
            //   盒子端实测"焦点到达仍唤出"走的就是 ②。
            //   对策：聚焦后延迟 SUPPRESS_DELAY_MS 再收一次；若用户已按 OK（MANUAL_SHOWN）
            //        则跳过，保证"点击才唤出"不被自己收掉。
            et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus) {
                        return;
                    }
                    MANUAL_SHOWN.put(et, Boolean.FALSE);
                    for (final long delay : SUPPRESS_DELAYS_MS) {
                        et.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!Boolean.TRUE.equals(MANUAL_SHOWN.get(et))) {
                                    hideIme(et);
                                }
                            }
                        }, delay);
                    }
                }
            });
        }
    }

    private static void hideIme(EditText et) {
        try {
            InputMethodManager imm = (InputMethodManager)
                    et.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && et.getWindowToken() != null) {
                imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void showIme(EditText et) {
        InputMethodManager imm = (InputMethodManager)
                et.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        }
    }
}
