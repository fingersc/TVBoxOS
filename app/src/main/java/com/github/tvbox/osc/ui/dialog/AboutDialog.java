package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.player.ExoPlayer;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.orhanobut.hawk.Hawk;

import org.jetbrains.annotations.NotNull;

public class AboutDialog extends BaseDialog {

    private TextView tvDebugOpen;

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_about);
        // 版本号：CI 会把构建号连同两个换行 sed 进布局的 tvVersion，这里用
        // BuildConfig.VERSION_NAME（同一构建号、无换行尾缀）覆盖，避免版本行被
        // 撑高、与右侧调试模式文字基线错位；本地直接编译则显示 1.0.0。
        ((TextView) findViewById(R.id.tvVersion)).setText(BuildConfig.VERSION_NAME);
        ((TextView) findViewById(R.id.tvDisclaimer)).setText(DISCLAIMER);
        tvDebugOpen = (TextView) findViewById(R.id.tvDebugOpen);
        refreshDebugText();
        findViewById(R.id.llAboutDebug).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean open = !Hawk.get(HawkConfig.DEBUG_OPEN, false);
                Hawk.put(HawkConfig.DEBUG_OPEN, open);
                // 三路日志全部即时生效，无需重启：
                // 应用自身 LOG 全通道 / ExoPlayer 内部日志 / OkHttp 日志拦截器
                LOG.setDebugEnabled(open);
                ExoPlayer.setExoLogEnabled(open);
                OkGoHelper.applyHttpDebug(open);
                refreshDebugText();
            }
        });
    }

    private void refreshDebugText() {
        boolean open = Hawk.get(HawkConfig.DEBUG_OPEN, false) || BuildConfig.DEBUG;
        tvDebugOpen.setText(open ? "调试模式 已打开" : "调试模式 已关闭");
    }

    private static final String DISCLAIMER =
            "本软件只提供聚合展示功能，所有资源来自网上, 软件不参与任何制作, 上传, 储存, 下载等内容."
                    + " 软件仅供学习参考, 请于安装后24小时内删除。\n\n"
                    + "打包分发请保留出处\n"
                    + "https://github.com/CatVodTVOfficial/TVBoxOSC\n"
                    + "https://github.com/q215613905/TVBoxOS";
}
