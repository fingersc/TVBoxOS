package com.github.tvbox.osc.player;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.ImgUtil;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.ui.widget.DanmakuView;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoView;

public class MyVideoView extends VideoView implements DrawHandler.Callback {
    public interface DanmuSeekListener {
        void onDanmuSeek(long position);
    }

    private DanmakuView danmuView;
    private ImageView artworkView;
    private DanmuSeekListener danmuSeekListener;

    /**
     * ★ v64：最近一次主动拖动进度条的时刻（{@code SystemClock.elapsedRealtime()}）；
     * 0 表示从未拖动。供 {@link #sinceLastSeekMs()} 使用 ——
     * 拖动进度条后播放器必然重新缓冲一轮，那是用户主动行为，不应计入源卡顿统计。
     */
    private long lastSeekAtMs = 0L;

    public MyVideoView(@NonNull Context context) {
        super(context, null);
    }

    public MyVideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public MyVideoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public AbstractPlayer getMediaPlayer() {
        return mMediaPlayer;
    }

    public void setArtwork(String url) {
        if (TextUtils.isEmpty(url)) {
            clearArtwork();
            return;
        }
        if (artworkView == null) {
            artworkView = new ImageView(getContext());
            artworkView.setBackgroundColor(android.graphics.Color.BLACK);
            artworkView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            artworkView.setClickable(false);
            artworkView.setFocusable(false);
            int index = mRenderView == null ? 0 : Math.min(1, mPlayerContainer.getChildCount());
            mPlayerContainer.addView(artworkView, index, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }
        artworkView.setVisibility(VISIBLE);
        ImgUtil.load(url, artworkView, 0, 0, 0, "", ImageView.ScaleType.FIT_CENTER);
    }

    public void clearArtwork() {
        if (artworkView != null) {
            artworkView.setVisibility(GONE);
            artworkView.setImageDrawable(null);
        }
    }

    public int[] getVideoSize() {
        return mVideoSize;
    }

    public long getPlaybackPosition() {
        return mCurrentPosition;
    }

    @Override
    public void seekTo(long pos) {
        // ★ v64：先记时间戳再拖。dkplayer 的 VideoView 里只有「外部主动 seek」会走到这里
        // （onPrepared 恢复断点走的是 mMediaPlayer.seekTo，不经过本方法），恰好只要主动拖动。
        lastSeekAtMs = SystemClock.elapsedRealtime();
        super.seekTo(pos);
        if (danmuSeekListener != null) {
            danmuSeekListener.onDanmuSeek(pos);
        } else if (haveDanmu()) {
            danmuView.seekTo(pos);
        }
    }

    /**
     * ★ v64：距最近一次主动拖动过去了多少毫秒。
     *
     * <p>从未拖动时返回 {@code Long.MAX_VALUE}（等效于「永远不在宽限窗口内」），
     * 而不是 0 —— 0 会被调用方误判成「刚刚拖过」。</p>
     */
    public long sinceLastSeekMs() {
        if (lastSeekAtMs <= 0L) {
            return Long.MAX_VALUE;
        }
        return SystemClock.elapsedRealtime() - lastSeekAtMs;
    }

    @Override
    public void resume() {
        super.resume();
        if (haveDanmu()) danmuView.resume();
    }

    @Override
    public void start() {
        super.start();
        if (haveDanmu()) danmuView.resume();
    }

    @Override
    public void pause() {
        super.pause();
        if (haveDanmu()) danmuView.pause();
    }

    @Override
    public void release() {
        super.release();
        if (haveDanmu()) danmuView.release();
    }

    private boolean haveDanmu() {
        return danmuView != null && danmuView.isPrepared();
    }

    public void setDanmuView(DanmakuView view) {
        danmuView = view;
        if (danmuView != null) danmuView.setCallback(this);
    }

    public DanmakuView getDanmuView() {
        return danmuView;
    }

    public void setDanmuSeekListener(DanmuSeekListener listener) {
        danmuSeekListener = listener;
    }

    @Override
    public void prepared() {
        post(() -> {
            if (danmuView == null) return;
            if (isPlaying() && danmuView.isPrepared()) {
                danmuView.start(getCurrentPosition());
            }
        });
    }

    @Override
    public void updateTimer(DanmakuTimer timer) {
    }

    @Override
    public void danmakuShown(BaseDanmaku danmaku) {
    }

    @Override
    public void drawingFinished() {
    }
}
