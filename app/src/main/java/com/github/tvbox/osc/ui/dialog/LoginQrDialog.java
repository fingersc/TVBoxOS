package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.sync.QrLoginBridge;
import com.github.tvbox.osc.ui.tv.QRCodeGen;

import org.jetbrains.annotations.NotNull;

/** 电视端扫码登录：显示一次性二维码，手机扫走。 */
public class LoginQrDialog extends BaseDialog implements QrLoginBridge.Listener {

    private final String targetName;
    private final String targetOwner;
    private TextView tvQrTitle;
    private ImageView ivQr;
    private int qrSize;

    public LoginQrDialog(@NonNull @NotNull Context context) {
        this(context, null, null);
    }

    /**
     * v79：指定目标账号的扫码（切换账号用）。
     * 手机回传的账号若不是这个账号，服务端会回 mismatch 并拒绝切换。
     */
    public LoginQrDialog(@NonNull @NotNull Context context, String targetName, String targetOwner) {
        super(context);
        this.targetName = targetName;
        this.targetOwner = targetOwner;
        setContentView(R.layout.dialog_login_qr);
        tvQrTitle = findViewById(R.id.tvQrTitle);
        ivQr = findViewById(R.id.ivQr);

        // v75：二维码与弹窗宽度都按「屏幕短边」换算 —— 手机横屏、电视、平板都不会走形
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        int size = (int) (shortSide * 0.45f);
        size = Math.max(size, 180);
        size = Math.min(size, 560);
        qrSize = size;
        ViewGroup.LayoutParams lp = ivQr.getLayoutParams();
        lp.width = size;
        lp.height = size;
        ivQr.setLayoutParams(lp);

        // 弹窗宽度不能由二维码决定：太窄会让提示文字和按钮竖着折行，看起来很局促
        View root = findViewById(R.id.llQrRoot);
        int width = Math.max((int) (size * 1.40f), (int) (dm.widthPixels * 0.34f));
        width = Math.min(width, (int) (dm.widthPixels * 0.86f));
        ViewGroup.LayoutParams rlp = root.getLayoutParams();
        rlp.width = width;
        root.setLayoutParams(rlp);

        refreshQr();
        QrLoginBridge.addListener(this);

        // v99-r6：原来这里有个「我是手机，扫其它设备的码」按钮，跳 QrScanActivity。
        //          现在两个入口已拆开（扫一扫=摄像头扫码，二维码=显示二维码），
        //          本弹窗只负责「显示二维码，让手机来扫」，不再做跳转。
    }

    /** r195：生成/刷新一次性二维码（首次显示、登录失败重试都走这里）。 */
    private void refreshQr() {
        if (targetOwner != null && !targetOwner.isEmpty()) {
            QrLoginBridge.setExpectedOwner(targetOwner);
            tvQrTitle.setText("扫码登录「" + targetName + "」");
        } else {
            QrLoginBridge.clearExpectedOwner();
            tvQrTitle.setText("扫码登录");
        }
        String address = ControlManager.get().getAddress(false);
        String token = QrLoginBridge.newToken();
        String content = address + "qrlogin?t=" + token;
        ivQr.setAlpha(1f);
        ivQr.setImageBitmap(QRCodeGen.generateBitmap(content, qrSize, qrSize, 2));
    }

    /**
     * r195：电视已受理、正在后台校验存储 —— 先给个反馈，
     * 避免用户看着二维码没变化以为没扫上又去重扫（此时旧 token 已消费）。
     */
    @Override
    public void onLoginPending() {
        if (tvQrTitle != null) {
            tvQrTitle.setText("正在登录，请稍候…");
        }
        if (ivQr != null) {
            ivQr.setAlpha(0.35f);
        }
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

    /** r195：后台校验/切换失败：提示原因并换一张新二维码，允许直接重扫。 */
    @Override
    public void onLoginFailed(String message) {
        try {
            Toast.makeText(getContext(),
                    message == null || message.isEmpty() ? "扫码登录失败" : message,
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
        if (isShowing()) {
            refreshQr();
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
