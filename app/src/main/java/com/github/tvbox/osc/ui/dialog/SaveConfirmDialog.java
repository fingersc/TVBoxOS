package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

/**
 * ★ v99-r50：设置页的「三键」确认框（保存 / 不保存 / 取消）。
 *
 * <p>现有 {@link TipDialog} 只有两个按钮、{@code dialog_confirm} 也是两个，
 * 而"返回时发现未保存"必须能区分三种意图：
 * <ul>
 *   <li>保存：落盘 + 上传 + 退出；</li>
 *   <li>不保存：丢弃草稿（本地自始至终没写过）+ 退出；</li>
 *   <li>取消：留在设置页继续改。</li>
 * </ul>
 */
public class SaveConfirmDialog extends BaseDialog {

    public interface OnListener {
        void save();

        void discard();

        void cancel();
    }

    public SaveConfirmDialog(@NonNull @NotNull Context context, String title, String content,
                             final OnListener listener) {
        super(context);
        setContentView(R.layout.dialog_save_confirm);
        setCanceledOnTouchOutside(true);

        TextView tvTitle = findViewById(R.id.tvSaveTitle);
        TextView tvContent = findViewById(R.id.tvSaveContent);
        tvTitle.setText(title);
        tvContent.setText(content);

        findViewById(R.id.btnSaveOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.save();
                dismiss();
            }
        });
        findViewById(R.id.btnSaveDiscard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.discard();
                dismiss();
            }
        });
        findViewById(R.id.btnSaveCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.cancel();
                dismiss();
            }
        });
        setOnCancelListener(new OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                listener.cancel();
            }
        });
    }
}
