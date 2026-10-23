package com.github.tvbox.osc.util;

import android.content.Context;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

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
                        showIme(et);
                        return true;
                    }
                    return false;
                }
            });
        }
    }

    /** 只抑制「焦点到达自动弹输入法」，不接管 OK 键。 */
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
