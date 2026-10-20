package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.sync.QrLoginBridge;
import com.github.tvbox.osc.ui.activity.QrScanActivity;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.FastClickCheckUtil;

import org.jetbrains.annotations.NotNull;

/** 电视端扫码登录：显示一次性二维码，手机扫走。 */
public class LoginQrDialog extends BaseDialog {

    public LoginQrDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_login_qr);
        String address = ControlManager.get().getAddress(false);
        String token = QrLoginBridge.newToken();
        String content = address + "qrlogin?t=" + token;
        ((android.widget.ImageView) findViewById(R.id.ivQr))
                .setImageBitmap(QRCodeGen.generateBitmap(content, 520, 520, 2));
        findViewById(R.id.btnScanOnPhone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                dismiss();
                try {
                    getContext().startActivity(new android.content.Intent(getContext(), QrScanActivity.class));
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
