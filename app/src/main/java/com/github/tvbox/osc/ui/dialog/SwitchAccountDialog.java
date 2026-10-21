package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.sync.AccountPaths;
import com.github.tvbox.osc.sync.AccountService;
import com.github.tvbox.osc.sync.Crypto;
import com.github.tvbox.osc.sync.WebDavClient;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.jetbrains.annotations.NotNull;

/**
 * 切换账号前的认证（v76）：只做「密码是否正确」的校验，通过后把派生的密钥交回调用方。
 *
 * <p>扫码走 {@link LoginQrDialog}：手机登录的是哪个账号，就切到哪个账号。</p>
 */
public class SwitchAccountDialog extends BaseDialog {

    public interface Listener {
        void onAuthed(String name, String owner, byte[] key);
    }

    private final String name;
    private final String owner;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView tvStatus;
    private EditText etPass;
    private boolean checking;

    public SwitchAccountDialog(@NonNull @NotNull Context context, String name, String owner,
                               Listener listener) {
        super(context);
        this.name = name;
        this.owner = owner;
        this.listener = listener;
        setContentView(R.layout.dialog_switch_account);
        ((TextView) findViewById(R.id.tvSwitchTitle)).setText("切换到「" + name + "」");
        ((TextView) findViewById(R.id.btnSwitchQr)).setText("扫码登录此账号");
        tvStatus = findViewById(R.id.tvSwitchStatus);
        etPass = findViewById(R.id.etSwitchPass);

        findViewById(R.id.btnSwitchOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                doCheck();
            }
        });
        findViewById(R.id.btnSwitchQr).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                dismiss();
                // v79：扫码登录"这个账号"，服务端会校验手机账号是否一致
                new LoginQrDialog(getContext(), name, owner).show();
            }
        });
        findViewById(R.id.btnSwitchCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                dismiss();
            }
        });
        etPass.requestFocus();
    }

    private void doCheck() {
        if (checking) {
            return;
        }
        final String pass = etPass.getText().toString();
        if (TextUtils.isEmpty(pass)) {
            tvStatus.setText("请输入密码");
            return;
        }
        if (pass.length() < AccountPaths.MIN_PASSWORD) {
            tvStatus.setText("密码至少 " + AccountPaths.MIN_PASSWORD + " 位");
            return;
        }
        checking = true;
        tvStatus.setText("正在验证…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                // deriveKey 抛的是受检异常，必须一起放进 try（v76 编译修复）
                byte[] keyTmp;
                AccountService.Result resultTmp;
                try {
                    keyTmp = Crypto.deriveKey(name, pass);
                    resultTmp = AccountService.check(new WebDavClient(
                            Hawk.get(HawkConfig.SYNC_WEBDAV_URL, ""),
                            Hawk.get(HawkConfig.SYNC_WEBDAV_USER, ""),
                            Hawk.get(HawkConfig.SYNC_WEBDAV_PASS, "")), name, keyTmp);
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            checking = false;
                            tvStatus.setText("验证失败：" + t.getMessage());
                        }
                    });
                    return;
                }
                final byte[] key = keyTmp;
                final AccountService.Result result = resultTmp;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        if (result.status == AccountService.OK) {
                            dismiss();
                            listener.onAuthed(name, owner, key);
                        } else if (result.status == AccountService.NOT_FOUND) {
                            tvStatus.setText("账号不存在，请检查账号文件夹是否还在");
                        } else if (result.status == AccountService.WRONG_PASSWORD) {
                            tvStatus.setText("密码不正确");
                        } else {
                            tvStatus.setText("该账号尚未激活，请先在登录区完成首次激活");
                        }
                    }
                });
            }
        }, "tvbox-switch-check").start();
    }
}
