package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.sync.AccountContext;
import com.github.tvbox.osc.sync.AccountPaths;
import com.github.tvbox.osc.sync.AccountStore;
import com.github.tvbox.osc.sync.AccountSwitcher;
import com.github.tvbox.osc.sync.AccountService;
import com.github.tvbox.osc.sync.Crypto;
import com.github.tvbox.osc.sync.HistorySyncManager;
import com.github.tvbox.osc.sync.QrLoginBridge;
import com.github.tvbox.osc.sync.SyncScopes;
import com.github.tvbox.osc.sync.WebDavClient;
import com.github.tvbox.osc.ui.dialog.LoginQrDialog;
import com.github.tvbox.osc.ui.dialog.SwitchAccountDialog;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.TvImeUtil;
import com.orhanobut.hawk.Hawk;

/**
 * 账号页：游客 / 登录 / 扫码 / 存储配置 / 同步设置 / 我的内容入口。
 *
 * <p>首页原「历史」按钮改为「账号」，历史功能作为本页第一项保留。</p>
 */
public class AccountActivity extends BaseActivity implements View.OnClickListener, QrLoginBridge.Listener {

    private TextView tvSyncStatus;
    private EditText etUser;
    private EditText etPass;
    private EditText etPassConfirm;
    private View llPassConfirm;
    private TextView tvLoginHint;
    private LinearLayout llAccounts;
    private View llLoginForm;
    /** 卡片行里的"扫码登录"入口（不是真实账号） */
    private static final String SCAN_ENTRY = "__SCAN_ENTRY__";
    /** 正在切换账号：忽略重复点击与并发的登录请求 */
    private boolean switching;
    /**
     * ★ v99-r27：当前账号那张卡片的视图引用。
     *
     * <p>切账号成功后 {@link #buildAccountCards()} 会 removeAllViews 重建，
     * 所有卡片都没有焦点 ⇒ 新当前账号卡片不会显示聚焦高亮，用户要退出再进才能看到
     * （盒子端实测反馈）。这里记住它，切换完成后把焦点还给这张卡。</p>
     */
    private View currentAccountCard;
    private TextView btnLogin;
    private TextView btnQrLogin;
    /** 首次激活阶段：等待二次输入密码 */
    private boolean activating;
    /** 登录/激活进行中：忽略重复点击，按钮置灰 */
    private boolean loggingIn;
    private String pendingName;
    private byte[] pendingKey;
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
        tvSyncStatus = findViewById(R.id.tvSyncStatus);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        etPassConfirm = findViewById(R.id.etPassConfirm);
        llPassConfirm = findViewById(R.id.llPassConfirm);
        tvLoginHint = findViewById(R.id.tvLoginHint);
        llAccounts = findViewById(R.id.llAccounts);
        llLoginForm = findViewById(R.id.llLoginForm);
        btnLogin = findViewById(R.id.btnLogin);
        btnQrLogin = findViewById(R.id.btnQrLogin);
        etDavUrl = findViewById(R.id.etDavUrl);
        etDavUser = findViewById(R.id.etDavUser);
        etDavPass = findViewById(R.id.etDavPass);
        // ★ v99-r20：TV 端输入法全局统一 —— 焦点到达输入框不自动弹，按 OK 键才唤出
        // （否则遥控器焦点扫过输入框就弹输入法，打断浏览）
        TvImeUtil.clickToIme(etUser, etPass, etPassConfirm, etDavUrl, etDavUser, etDavPass);
        tvScopeHistory = findViewById(R.id.tvScopeHistory);
        tvScopeProgress = findViewById(R.id.tvScopeProgress);
        tvScopeCollect = findViewById(R.id.tvScopeCollect);
        tvScopeSettings = findViewById(R.id.tvScopeSettings);
        tvWifiOnly = findViewById(R.id.tvWifiOnly);
        // v99-r6：二维码入口移到区块右上角 —— 「扫一扫」= 摄像头扫码，「+ 添加账号」= 展开表单
        findViewById(R.id.btnScanEntry).setOnClickListener(this);
        findViewById(R.id.btnAddAccount).setOnClickListener(this);
        findViewById(R.id.btnLogin).setOnClickListener(this);
        findViewById(R.id.btnQrLogin).setOnClickListener(this);
        findViewById(R.id.btnCancelAdd).setOnClickListener(this);
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
        // v75：扫码登录成功后要刷新本页（Dialog 关闭不会触发 Activity.onResume）
        QrLoginBridge.addListener(this);
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    /** 手机扫码把账号登到本机后：立刻刷新页面上的身份、存储配置与同步状态。 */
    @Override
    public void onLoginOk(String name) {
        hideAddForm();
        refreshAll();
        tvSyncStatus.setText("已登录：" + name + "，正在同步…");
    }

    @Override
    protected void onDestroy() {
        QrLoginBridge.removeListener(this);
        super.onDestroy();
    }

    private void refreshAll() {        AccountContext.load();
        // v99-r6b：页面标题下那行身份说明已删除（用户要求），登录状态改由账号卡片的
        //          「当前账号」高亮体现，不再单独占一行文字
        // ★ v99-r81：refreshAll 在 onResume 也会跑 —— 无条件回填会把「已输入未保存」的内容
        //   冲掉（手机切后台回来必清，实测；典型场景：切到别的应用抄存储地址）。
        //   改为只在输入框为空时回填已保存值：首次进页面照常显示，正在输入的内容不被覆盖。
        if (etDavUrl.getText() == null || etDavUrl.length() == 0) {
            etDavUrl.setText(Hawk.get(HawkConfig.SYNC_WEBDAV_URL, ""));
        }
        if (etDavUser.getText() == null || etDavUser.length() == 0) {
            etDavUser.setText(Hawk.get(HawkConfig.SYNC_WEBDAV_USER, ""));
        }
        if (etDavPass.getText() == null || etDavPass.length() == 0) {
            etDavPass.setText(Hawk.get(HawkConfig.SYNC_WEBDAV_PASS, ""));
        }
        SyncScopes scopes = SyncScopes.load();
        tvScopeHistory.setText("历史 " + (scopes.history ? "开" : "关"));
        tvScopeProgress.setText("进度 " + (scopes.progress ? "开" : "关"));
        tvScopeCollect.setText("收藏 " + (scopes.collect ? "开" : "关"));
        tvScopeSettings.setText("设置 " + (scopes.settings ? "开" : "关"));
        tvWifiOnly.setText("仅 Wi-Fi " + (Hawk.get(HawkConfig.SYNC_WIFI_ONLY, false) ? "开" : "关"));
        // v98 P3：展示 SyncState 的完整状态（时间 + 正常 / 有未同步改动 / 重试中 / 已暂停）
        tvSyncStatus.setText(HistorySyncManager.syncStatusText());
        buildAccountCards();
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
            // 「二维码」：只负责显示二维码（手机扫本机）
            new LoginQrDialog(this).show();
        } else if (id == R.id.btnScanEntry) {
            // 「扫一扫」：用本机摄像头扫手机上的二维码。与「二维码」完全独立，不再互相跳转。
            // 盒子/电视通常没有摄像头 —— 先探一下，否则进去就是黑屏。
            if (hasCamera()) {
                jumpActivity(QrScanActivity.class);
            } else {
                toast("本机没有摄像头，请用手机上的 App 登录后扫码同步");
            }
        } else if (id == R.id.btnAddAccount) {
            showAddForm();
        } else if (id == R.id.btnCancelAdd) {
            hideAddForm();
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
        if (loggingIn) {
            return;
        }
        if (activating) {
            confirmActivate();
            return;
        }
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString();
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            toast("请输入账号名与密码");
            return;
        }
        if (!AccountPaths.isValidName(user)) {
            toast("账号名无效（不能含 / \\ : * ? \" < > |，最长 24 字）");
            return;
        }
        if (pass.length() < AccountPaths.MIN_PASSWORD) {
            toast("密码至少 " + AccountPaths.MIN_PASSWORD + " 位");
            return;
        }
        setBusy(true, "正在登录…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 密钥派生 20 万轮，老盒子上约 1 秒，先给一句「正在计算密钥」
                    postStage("正在计算密钥…");
                    final byte[] key = Crypto.deriveKey(user, pass);
                    postStage("正在验证账号…");
                    final AccountService.Result result = AccountService.check(dav(), user, key);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            handleCheck(user, key, result);
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, null);
                            tvSyncStatus.setText("验证失败：" + t.getMessage());
                        }
                    });
                }
            }
        }, "tvbox-login-check").start();
    }

    private void handleCheck(String user, byte[] key, AccountService.Result result) {
        if (result.status == AccountService.OK) {
            loginWith(user, key);
            return;
        }
        setBusy(false, null);
        if (result.status == AccountService.NOT_FOUND) {
            String dir = accountDirHint(user);
            tvSyncStatus.setText("账号不存在。请管理员在网盘中创建文件夹：" + dir);
            toast("账号不存在，请管理员先创建文件夹");
            return;
        }
        if (result.status == AccountService.WRONG_PASSWORD) {
            tvSyncStatus.setText("密码不正确");
            toast("密码不正确");
            return;
        }
        // NEED_ACTIVATE
        activating = true;
        pendingName = user;
        pendingKey = key;
        llPassConfirm.setVisibility(View.VISIBLE);
        etPassConfirm.setText("");
        btnLogin.setText("确认激活");
        tvSyncStatus.setText("首次使用：请再次输入密码以激活账号。该密码用于加密你的记录，忘记无法找回。");
        etPassConfirm.requestFocus();
    }

    private void confirmActivate() {
        String first = etPass.getText().toString();
        String second = etPassConfirm.getText().toString();
        if (second.length() < AccountPaths.MIN_PASSWORD) {
            toast("密码至少 " + AccountPaths.MIN_PASSWORD + " 位");
            return;
        }
        if (!second.equals(first)) {
            toast("两次输入的密码不一致");
            return;
        }
        if (loggingIn) {
            return;
        }
        setBusy(true, "正在激活账号…");
        final String name = pendingName;
        final byte[] key = pendingKey;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AccountService.activate(dav(), name, key, HistorySyncManager.deviceId());
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            loginWith(name, key);
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, null);
                            tvSyncStatus.setText("激活失败：" + t.getMessage());
                        }
                    });
                }
            }
        }, "tvbox-activate").start();
    }

    private void loginWith(String user, byte[] key) {
        resetActivateUi();
        etPass.setText("");
        etPassConfirm.setText("");
        toast("已登录：" + user);
        setBusy(true, "正在登录…");
        // v76：登录即「切换/加入账号」——保存当前（游客）设置快照、切库、拉取云端
        AccountSwitcher.switchTo(Crypto.userKey(user), user, key,
                new AccountSwitcher.Callback() {
                    @Override
                    public void onDone(boolean ok, String message) {
                        setBusy(false, null);
                        if (ok) {
                            hideAddForm();
                        }
                        refreshAll();
                        tvSyncStatus.setText(message);
                    }
                });
    }

    private void resetActivateUi() {
        activating = false;
        pendingName = null;
        pendingKey = null;
        llPassConfirm.setVisibility(View.GONE);
        btnLogin.setText("登录");
    }

    /** 登录中：显示阶段提示、按钮置灰、忽略重复点击。 */
    private void setBusy(boolean busy, String hint) {
        loggingIn = busy;
        if (hint != null) {
            tvLoginHint.setText(hint);
        }
        tvLoginHint.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnLogin.setAlpha(busy ? 0.5f : 1f);
        btnQrLogin.setAlpha(busy ? 0.5f : 1f);
        // 扫码入口在登录过程中必须禁用，否则会与登录流程抢状态
        btnQrLogin.setEnabled(!busy);
    }

    private void postStage(final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (loggingIn) {
                    tvLoginHint.setText(text);
                }
            }
        });
    }

    // ---------------------------------------------------------------- v76 账号卡片

    /** v99-r6：本机是否有摄像头（盒子/电视一般没有，摄像头扫码入口要先探一下）。 */
    private boolean hasCamera() {
        try {
            return android.hardware.Camera.getNumberOfCameras() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void buildAccountCards() {
        if (llAccounts == null) {
            return;
        }
        llAccounts.removeAllViews();
        currentAccountCard = null;
        addAccountCard("游客", "", true);
        for (AccountStore.Item it : AccountStore.list()) {
            addAccountCard(it.name, it.owner, false);
        }
        // v99-r6：「+ 添加账号」「扫码登录」两个 chip 已移到区块右上角（btnAddAccount / btnScanEntry），
        //          卡片列表这里只保留「游客」与已保存的账号
        // ★ v99-r25：卡片行内焦点边界。
        //   r24 做的是「行尾环回行首」，用户要求改成「端点就地不动」：
        //   第一张按左、最后一张按右，焦点都停在自己身上（不回环、也不跨到 WebDAV 区）。
        int n = llAccounts.getChildCount();
        if (n >= 1) {
            for (int i = 0; i < n; i++) {
                View v = llAccounts.getChildAt(i);
                v.setNextFocusLeftId(i == 0 ? v.getId() : llAccounts.getChildAt(i - 1).getId());
                v.setNextFocusRightId(i == n - 1 ? v.getId() : llAccounts.getChildAt(i + 1).getId());
            }
        }
    }

    /**
     * 账号卡片：两行（名称 + 状态）。
     *
     * <p>v99-r6：原来是一行文字 chip，改为两行是为了显示「当前账号 / 未登录 / 仅本机」，
     * 视觉分级仍沿用原来的两个 drawable（实心高亮的 shape_account_chip_current、细线描边的
     * button_danmu_setting），没有新增资源。</p>
     */
    private void addAccountCard(final String text, final String owner, final boolean guest) {
        boolean isCurrent = guest ? AccountContext.isGuest()
                : (owner != null && owner.equals(AccountContext.ownerKey()));
        boolean isAdd = owner == null;
        boolean isScan = SCAN_ENTRY.equals(owner);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        // ★ v99-r24：必须有 id，nextFocus 才能指向它（动态创建默认 NO_ID）
        card.setId(View.generateViewId());
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(isCurrent ? R.drawable.shape_account_chip_current
                : ((isAdd || isScan) ? R.drawable.shape_account_chip_add : R.drawable.button_danmu_setting));

        TextView name = new TextView(this);
        name.setText(isCurrent ? "✓ " + text : (isAdd ? "+  " + text : text));
        name.setTextColor((isAdd || isScan) ? 0xFF8FA0B8 : 0xFFFFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.ts_22));
        name.setSingleLine(true);
        card.addView(name);

        // 当前账号优先显示「当前账号」，其次是游客的「仅本机」
        String stateText = isCurrent ? "当前账号" : (guest ? "仅本机" : "未登录");
        TextView state = new TextView(this);
        state.setText(stateText);
        state.setTextColor(isCurrent ? 0xFF3FBF7F : 0xFF7D8EA6);
        state.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.ts_18));
        state.setSingleLine(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) getResources().getDimension(R.dimen.vs_6);
        card.addView(state, slp);

        // v99-r6b：高度改 wrap_content —— 固定高度会把第二行「未登录 / 当前账号」压到裁切，
        //          改由 padding 撑开，字号或字体变化也不会再压字
        int padH = (int) getResources().getDimension(R.dimen.vs_20);
        int padV = (int) getResources().getDimension(R.dimen.vs_14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, (int) getResources().getDimension(R.dimen.vs_12), 0);
        card.setLayoutParams(lp);
        card.setPadding(padH, padV, padH, padV);
        card.setMinimumWidth((int) getResources().getDimension(R.dimen.vs_180));
        if (isCurrent) {
            currentAccountCard = card;   // ★ v99-r27：切账号后把焦点还给这张卡
        }
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAccountCardClick(text, owner, guest);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (owner != null && !SCAN_ENTRY.equals(owner)) {
                    confirmRemoveAccount(text, owner);
                }
                return true;
            }
        });
        // ★ v99-r20：遥控器聚焦高亮。当前账号卡片用的 shape_account_chip_current 是静态
        // shape（无 state_focused），聚焦时不像其它卡片（button_danmu_setting 是 selector）那样
        // 变蓝 —— 盒子端实测反馈。聚焦时临时换用同一个 selector，失焦换回身份底色；
        // 「✓ 前缀 + 绿色『当前账号』文字」仍保留身份标识，不新增资源。
        if (isCurrent) {
            card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    card.setBackgroundResource(hasFocus ? R.drawable.button_danmu_setting
                            : R.drawable.shape_account_chip_current);
                }
            });
        }
        llAccounts.addView(card);
    }

    private void onAccountCardClick(String name, String owner, boolean guest) {
        if (SCAN_ENTRY.equals(owner)) {
            // v79：第一层扫码入口 —— 用手机上的账号登录本机（已存在则切，不存在则新增）
            new LoginQrDialog(this).show();
            return;
        }
        if (owner == null) {
            showAddForm();
            return;
        }
        if (guest) {
            if (AccountContext.isGuest()) {
                toast("当前已是游客模式");
                return;
            }
            if (switching) {
                return;
            }
            switching = true;
            setBusy(true, "正在切换到游客…");
            AccountSwitcher.switchToGuest(new AccountSwitcher.Callback() {
                @Override
                public void onDone(boolean ok, String message) {
                    switching = false;
                    setBusy(false, null);
                    refreshAll();
                    tvSyncStatus.setText(message);
                }
            });
            return;
        }
        if (owner.equals(AccountContext.ownerKey())) {
            toast("已经是当前账号");
            return;
        }
        new SwitchAccountDialog(this, name, owner, new SwitchAccountDialog.Listener() {
            @Override
            public void onAuthed(String n, String o, byte[] key) {
                doSwitchAccount(n, o, key);
            }
        }).show();
    }

    /** 展开"添加账号"表单（只有这里才需要填账号密码）。 */
    private void showAddForm() {
        if (llLoginForm == null) {
            return;
        }
        resetActivateUi();
        etPass.setText("");
        etPassConfirm.setText("");
        llLoginForm.setVisibility(View.VISIBLE);
        tvLoginHint.setVisibility(View.GONE);
        tvSyncStatus.setText("账号需先由管理员在网盘中创建文件夹；首次登录会提示再次输入密码");
        etUser.requestFocus();
    }

    private void hideAddForm() {
        if (llLoginForm != null) {
            llLoginForm.setVisibility(View.GONE);
        }
        resetActivateUi();
        etUser.setText("");
        etPass.setText("");
        etPassConfirm.setText("");
        tvLoginHint.setVisibility(View.GONE);
        // v98 P3：展示 SyncState 的完整状态（时间 + 正常 / 有未同步改动 / 重试中 / 已暂停）
        tvSyncStatus.setText(HistorySyncManager.syncStatusText());
    }

    private void doSwitchAccount(final String name, final String owner, byte[] key) {
        if (switching) {
            return;
        }
        switching = true;
        setBusy(true, "正在切换到：" + name + "…");
        AccountSwitcher.switchTo(owner, name, key, new AccountSwitcher.Callback() {
            @Override
            public void onDone(boolean ok, String message) {
                switching = false;
                setBusy(false, null);
                refreshAll();
                // ★ v99-r27：卡片列表刚被重建，焦点已丢失 ⇒ 主动落到新的当前账号卡片上，
                //   否则要退出再进才能看到它的高亮（盒子端实测反馈）。
                if (currentAccountCard != null) {
                    final View card = currentAccountCard;
                    card.post(new Runnable() {
                        @Override
                        public void run() {
                            if (card.isShown() && !card.isFocused()) {
                                card.requestFocus();
                            }
                        }
                    });
                }
                tvSyncStatus.setText(message);
                toast(message);
            }
        });
    }

    private void confirmRemoveAccount(final String name, final String owner) {
        // 用数组持有弹窗：匿名监听器在 new TipDialog(...) 的实参里，直接引用 dialog 会「可能未初始化」
        final TipDialog[] holder = new TipDialog[1];
        holder[0] = new TipDialog(this,
                "移除本机账号「" + name + "」？\n本机保存的历史/收藏/设置会被删除，云端数据不受影响。",
                "移除", "取消", new TipDialog.OnListener() {
            @Override
            public void left() {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                AccountSwitcher.remove(owner, new AccountSwitcher.Callback() {
                    @Override
                    public void onDone(boolean ok, String message) {
                        refreshAll();
                        tvSyncStatus.setText(message);
                        toast(message);
                    }
                });
            }

            @Override
            public void right() {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }

            @Override
            public void cancel() {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        holder[0].show();
    }

    private WebDavClient dav() {
        return new WebDavClient(
                Hawk.get(HawkConfig.SYNC_WEBDAV_URL, ""),
                Hawk.get(HawkConfig.SYNC_WEBDAV_USER, ""),
                Hawk.get(HawkConfig.SYNC_WEBDAV_PASS, ""));
    }

    private String accountDirHint(String name) {
        String root = Hawk.get(HawkConfig.SYNC_WEBDAV_URL, "");
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root + "/" + AccountPaths.normalize(name) + "/";
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
                String result;
                try {
                    new WebDavClient(url, user, pass).test();
                    result = "连接成功";
                } catch (Throwable t) {
                    result = "连接失败：" + t.getMessage();
                }
                final String msg = result;
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
