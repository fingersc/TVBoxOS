package com.github.tvbox.osc.util;

import com.github.tvbox.osc.api.ApiConfig;

/**
 * 直播配置预热（v77）。
 *
 * <p>切换账号或同步把 {@code live_api_url} 写回本机后，直播配置的缓存 key 会变，
 * 首次进直播页必然走网络；代理源还要等 {@code live_connect_timeout}。
 * 这里在配置变化后后台静默加载一次，用户真正点「直播」时通常已就绪。</p>
 *
 * <p>节流 60 秒，失败不提示、不重试（进直播页仍会自己加载）。</p>
 */
public final class LiveConfigPrefetcher {

    private static final long MIN_GAP_MS = 60 * 1000L;
    private static long lastAt;

    private LiveConfigPrefetcher() {
    }

    public static void prefetch() {
        long now = System.currentTimeMillis();
        synchronized (LiveConfigPrefetcher.class) {
            if (now - lastAt < MIN_GAP_MS) {
                return;
            }
            lastAt = now;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ApiConfig.get().loadLiveConfig(false, new ApiConfig.LoadConfigCallback() {
                        @Override
                        public void success() {
                        }

                        @Override
                        public void error(String msg) {
                        }

                        @Override
                        public void notice(String msg) {
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }, "tvbox-live-prefetch").start();
    }
}
