package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

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

        // v75：二维码边长按屏幕换算，固定尺寸会在小屏上把提示文字与手机扫码按钮挤出屏幕
        ImageView ivQr = findViewById(R.id.ivQr);
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        int size = Math.min((int) (dm.heightPixels * 0.40f), (int) (dm.widthPixels * 0.34f));
        size = Math.max(size, 180);
        size = Math.min(size, 560);
        ViewGroup.LayoutParams lp = ivQr.getLayoutParams();
        lp.width = size;
        lp.height = size;
        ivQr.setLayoutParams(lp);
        ivQr.setImageBitmap(QRCodeGen.generateBitmap(content, size, size, 2));
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
