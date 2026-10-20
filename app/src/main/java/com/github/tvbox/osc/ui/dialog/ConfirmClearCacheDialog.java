package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.sync.AccountContext;
import com.github.tvbox.osc.util.FastClickCheckUtil;

import org.jetbrains.annotations.NotNull;

/** 清空缓存确认：已登录时提供「同时清空云端」勾选项（默认不勾）。 */
public class ConfirmClearCacheDialog extends BaseDialog {

    public interface Callback {
        void onConfirm(boolean clearCloud);
    }

    public ConfirmClearCacheDialog(@NonNull @NotNull Context context, final Callback callback) {
        super(context);
        setContentView(R.layout.dialog_confirm_clear_cache);
        setCanceledOnTouchOutside(true);
        CheckBox cb = findViewById(R.id.cbClearCloud);
        TextView msg = findViewById(R.id.tvMsg);
        boolean loggedIn = !AccountContext.isGuest();
        cb.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        if (loggedIn) {
            msg.setText("将清空本机的观看历史、播放进度与搜索记录，并重启应用。\n当前账号：" + AccountContext.displayName());
        }
        findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                dismiss();
            }
        });
        final CheckBox cloud = cb;
        findViewById(R.id.btnConfirm).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                dismiss();
                callback.onConfirm(loggedIn && cloud.isChecked());
            }
        });
    }
}
