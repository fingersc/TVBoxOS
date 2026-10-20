package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.sync.AccountContext;
import com.github.tvbox.osc.sync.Crypto;
import com.github.tvbox.osc.sync.HistorySyncManager;
import com.github.tvbox.osc.sync.SyncScopes;
import com.github.tvbox.osc.sync.WebDavClient;
import com.github.tvbox.osc.ui.dialog.LoginQrDialog;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

/**
 * 账号页：游客 / 登录 / 扫码 / 存储配置 / 同步设置 / 我的内容入口。
 *
 * <p>首页原「历史」按钮改为「账号」，历史功能作为本页第一项保留。</p>
 */
public class AccountActivity extends BaseActivity implements View.OnClickListener {

    private TextView tvIdentity;
    private TextView tvSyncStatus;
    private EditText etUser;
    private EditText etPass;
    private EditText etDavUrl;
    private EditText etDavUser;
    private EditText etDavPass;
    private TextView tvScopeHistory;
    private TextView tvScopeProgress;
    private TextView tvScopeCollect;
    private TextView tvScopeSettings;
    private TextView tvWifiOnly;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_account;
    }

    @Override
    protected void init() {
        tvIdentity = findViewById(R.id.tvIdentity);
        tvSyncStatus = findViewById(R.id.tvSyncStatus);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        etDavUrl = findViewById(R.id.etDavUrl);
        etDavUser = findViewById(R.id.etDavUser);
        etDavPass = findViewById(R.id.etDavPass);
        tvScopeHistory = findViewById(R.id.tvScopeHistory);
        tvScopeProgress = findViewById(R.id.tvScopeProgress);
        tvScopeCollect = findViewById(R.id.tvScopeCollect);
        tvScopeSettings = findViewById(R.id.tvScopeSettings);
        tvWifiOnly = findViewById(R.id.tvWifiOnly);
        findViewById(R.id.btnLogin).setOnClickListener(this);
        findViewById(R.id.btnQrLogin).setOnClickListener(this);
        findViewById(R.id.btnLogout).setOnClickListener(this);
        findViewById(R.id.btnHistory).setOnClickListener(this);
        findViewById(R.id.btnCollect).setOnClickListener(this);
        findViewById(R.id.btnTestDav).setOnClickListener(this);
        findViewById(R.id.btnSaveDav).setOnClickListener(this);
        findViewById(R.id.btnSyncNow).setOnClickListener(this);
        tvScopeHistory.setOnClickListener(this);
        tvScopeProgress.setOnClickListener(this);
        tvScopeCollect.setOnClickListener(this);
        tvScopeSettings.setOnClickListener(this);
        tvWifiOnly.setOnClickListener(this);
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void refreshAll() {
        AccountContext.load();
        if (AccountContext.isGuest()) {
            tvIdentity.setText("游客模式：记录仅保存在本机，不参与同步");
        } else {
            tvIdentity.setText("已登录：" + AccountContext.displayName()
                    + "（切换账号需重新输入密码或扫码）");
        }
        etDavUrl.setText(Hawk.get(HawkConfig.SYNC_WEBDAV_URL, ""));
        etDavUser.setText(Hawk.get(HawkConfig.SYNC_WEBDAV_USER, ""));
        etDavPass.setText(Hawk.get(HawkConfig.SYNC_WEBDAV_PASS, ""));
        SyncScopes scopes = SyncScopes.load();
        tvScopeHistory.setText("历史 " + (scopes.history ? "开" : "关"));
        tvScopeProgress.setText("进度 " + (scopes.progress ? "开" : "关"));
        tvScopeCollect.setText("收藏 " + (scopes.collect ? "开" : "关"));
        tvScopeSettings.setText("设置 " + (scopes.settings ? "开" : "关"));
        tvWifiOnly.setText("仅 Wi-Fi " + (Hawk.get(HawkConfig.SYNC_WIFI_ONLY, false) ? "开" : "关"));
        tvSyncStatus.setText(AccountContext.isGuest()
                ? "登录后可跨设备同步"
                : "上次同步：" + HistorySyncManager.lastSyncText());
    }

    @Override
    public void onClick(View v) {
        FastClickCheckUtil.check(v);
        int id = v.getId();
        if (id == R.id.btnHistory) {
            jumpActivity(HistoryActivity.class);
        } else if (id == R.id.btnCollect) {
            jumpActivity(CollectActivity.class);
        } else if (id == R.id.btnLogin) {
            doLogin();
        } else if (id == R.id.btnQrLogin) {
            new LoginQrDialog(this).show();
        } else if (id == R.id.btnLogout) {
            doLogout();
        } else if (id == R.id.btnTestDav) {
            testDav();
        } else if (id == R.id.btnSaveDav) {
            saveDav();
        } else if (id == R.id.btnSyncNow) {
            doSyncNow();
        } else if (id == R.id.tvScopeHistory || id == R.id.tvScopeProgress
                || id == R.id.tvScopeCollect || id == R.id.tvScopeSettings) {
            toggleScope(id);
        } else if (id == R.id.tvWifiOnly) {
            Hawk.put(HawkConfig.SYNC_WIFI_ONLY, !Hawk.get(HawkConfig.SYNC_WIFI_ONLY, false));
            refreshAll();
        }
    }

    private void toggleScope(int id) {
        SyncScopes scopes = SyncScopes.load();
        if (id == R.id.tvScopeHistory) {
            scopes.history = !scopes.history;
        } else if (id == R.id.tvScopeProgress) {
            scopes.progress = !scopes.progress;
        } else if (id == R.id.tvScopeCollect) {
            scopes.collect = !scopes.collect;
        } else {
            scopes.settings = !scopes.settings;
        }
        scopes.save();
        refreshAll();
    }

    private void doLogin() {
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString();
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            toast("请输入用户名与密码");
            return;
        }
        tvSyncStatus.setText("正在登录…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final byte[] key = Crypto.deriveKey(user, pass);
                    final String owner = Crypto.userKey(user);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            AccountContext.login(owner, user, key);
                            etPass.setText("");
                            refreshAll();
                            toast("已登录：" + user);
                            HistorySyncManager.syncNow(new HistorySyncManager.Callback() {
                                @Override
                                public void onDone(boolean ok, String message) {
                                    tvSyncStatus.setText(message);
                                }
                            });
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            tvSyncStatus.setText("登录失败：" + t.getMessage());
                        }
                    });
                }
            }
        }, "tvbox-login").start();
    }

    /** 上次提示"有未同步改动"的时间：5 秒内再次点击视为确认。 */
    private long logoutWarnAt;

    private void doLogout() {
        if (AccountContext.isGuest()) {
            toast("当前是游客模式");
            return;
        }
        // 有未同步改动：先试一次，成功即退；失败则二次确认（改动保留在本机，下次登录补传）
        if (HistorySyncManager.needsSyncBeforeExit()) {
            if (System.currentTimeMillis() - logoutWarnAt < 5000) {
                performLogout();
                return;
            }
            tvSyncStatus.setText("正在同步未上传的改动…");
            final boolean[] done = {false};
            final Handler handler = new Handler(Looper.getMainLooper());
            final Runnable timeout = new Runnable() {
                @Override
                public void run() {
                    if (!done[0]) {
                        done[0] = true;
                        logoutWarnAt = System.currentTimeMillis();
                        tvSyncStatus.setText("同步未完成，改动已保留在本机");
                        toast("同步未完成，再点一次「退出登录」确认（改动下次登录会补传）");
                    }
                }
            };
            handler.postDelayed(timeout, 2500);
            HistorySyncManager.syncNow(new HistorySyncManager.Callback() {
                @Override
                public void onDone(boolean ok, String message) {
                    handler.removeCallbacks(timeout);
                    if (!done[0]) {
                        done[0] = true;
                        performLogout();
                    }
                }
            });
            return;
        }
        performLogout();
    }

    private void performLogout() {
        AccountContext.logout();
        refreshAll();
        toast("已退出，回到游客模式");
    }

    private void testDav() {
        final String url = etDavUrl.getText().toString().trim();
        final String user = etDavUser.getText().toString().trim();
        final String pass = etDavPass.getText().toString();
        if (TextUtils.isEmpty(url)) {
            toast("请填写服务器地址");
            return;
        }
        tvSyncStatus.setText("正在测试连接…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String msg;
                try {
                    new WebDavClient(url, user, pass).test();
                    msg = "连接成功";
                } catch (Throwable t) {
                    msg = "连接失败：" + t.getMessage();
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        tvSyncStatus.setText(msg);
                        toast(msg);
                    }
                });
            }
        }, "tvbox-davtest").start();
    }

    private void saveDav() {
        String url = etDavUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            toast("请填写服务器地址");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("地址需以 http:// 或 https:// 开头");
            return;
        }
        Hawk.put(HawkConfig.SYNC_WEBDAV_URL, url);
        Hawk.put(HawkConfig.SYNC_WEBDAV_USER, etDavUser.getText().toString().trim());
        Hawk.put(HawkConfig.SYNC_WEBDAV_PASS, etDavPass.getText().toString());
        toast("存储配置已保存");
        LOG.i("[Sync] storage saved");
    }

    private void doSyncNow() {
        if (AccountContext.isGuest()) {
            toast("请先登录");
            return;
        }
        tvSyncStatus.setText("正在同步…");
        HistorySyncManager.syncNow(new HistorySyncManager.Callback() {
            @Override
            public void onDone(boolean ok, String message) {
                tvSyncStatus.setText(message);
                toast(message);
            }
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
