package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.sync.QrLoginBridge;
import com.github.tvbox.osc.ui.activity.QrScanActivity;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.FastClickCheckUtil;

import org.jetbrains.annotations.NotNull;

/** 电视端扫码登录：显示一次性二维码，手机扫走。 */
public class LoginQrDialog extends BaseDialog implements QrLoginBridge.Listener {

    public LoginQrDialog(@NonNull @NotNull Context context) {
        this(context, null, null);
    }

    /**
     * v79：指定目标账号的扫码（切换账号用）。
     * 手机回传的账号若不是这个账号，服务端会回 mismatch 并拒绝切换。
     */
    public LoginQrDialog(@NonNull @NotNull Context context, String targetName, String targetOwner) {
        super(context);
        setContentView(R.layout.dialog_login_qr);
        if (targetOwner != null && !targetOwner.isEmpty()) {
            QrLoginBridge.setExpectedOwner(targetOwner);
            ((android.widget.TextView) findViewById(R.id.tvQrTitle))
                    .setText("扫码登录「" + targetName + "」");
        } else {
            QrLoginBridge.clearExpectedOwner();
        }
        String address = ControlManager.get().getAddress(false);
        String token = QrLoginBridge.newToken();
        String content = address + "qrlogin?t=" + token;

        // v75：二维码与弹窗宽度都按「屏幕短边」换算 —— 手机横屏、电视、平板都不会走形
        ImageView ivQr = findViewById(R.id.ivQr);
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        int size = (int) (shortSide * 0.45f);
        size = Math.max(size, 180);
        size = Math.min(size, 560);
        ViewGroup.LayoutParams lp = ivQr.getLayoutParams();
        lp.width = size;
        lp.height = size;
        ivQr.setLayoutParams(lp);
        ivQr.setImageBitmap(QRCodeGen.generateBitmap(content, size, size, 2));

        // 弹窗宽度不能由二维码决定：太窄会让提示文字和按钮竖着折行，看起来很局促
        View root = findViewById(R.id.llQrRoot);
        int width = Math.max((int) (size * 1.40f), (int) (dm.widthPixels * 0.34f));
        width = Math.min(width, (int) (dm.widthPixels * 0.86f));
        ViewGroup.LayoutParams rlp = root.getLayoutParams();
        rlp.width = width;
        root.setLayoutParams(rlp);

        QrLoginBridge.addListener(this);

        // v99-r6：原来这里有个「我是手机，扫其它设备的码」按钮，跳 QrScanActivity。
        //          现在两个入口已拆开（扫一扫=摄像头扫码，二维码=显示二维码），
        //          本弹窗只负责「显示二维码，让手机来扫」，不再做跳转。
    }

    /** 手机扫码成功后：关闭二维码并提示，避免看起来「没反应」。 */
    @Override
    public void onLoginOk(String name) {
        if (isShowing()) {
            dismiss();
        }
        try {
            Toast.makeText(getContext(), "已登录：" + name, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void dismiss() {
        QrLoginBridge.removeListener(this);
        // v79：弹窗关了就不再限制"期望账号"，避免下一次扫码被误判 mismatch
        QrLoginBridge.clearExpectedOwner();
        super.dismiss();
    }
}
