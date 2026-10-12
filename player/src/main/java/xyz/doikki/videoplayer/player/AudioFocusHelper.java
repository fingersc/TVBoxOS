package xyz.doikki.videoplayer.player;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;

/**
 * 音频焦点改变监听
 */
final class AudioFocusHelper implements AudioManager.OnAudioFocusChangeListener {

    private Handler mHandler = new Handler(Looper.getMainLooper());

    private WeakReference<VideoView> mWeakVideoView;

    private AudioManager mAudioManager;

    private boolean mStartRequested = false;
    private boolean mPausedForLoss = false;
    private int mCurrentFocus = 0;

    /**
     * 「暂时失去音频焦点」后延迟多久才真的暂停画面。
     *
     * <p>与 ui 侧（{@code PlayFragment#PAUSE_CONFIRM_DELAY_MS} /
     * {@code LivePlayActivity#LIVE_PAUSE_CONFIRM_DELAY_MS}）同值，
     * 生命周期与音频焦点两条通道口径一致。</p>
     */
    private static final long TRANSIENT_LOSS_PAUSE_DELAY_MS = 300L;

    /** 延迟确认的暂停动作：只有焦点持续丢失超过该窗口才执行。 */
    private final Runnable mPauseForLossRun = new Runnable() {
        @Override
        public void run() {
            final VideoView videoView = mWeakVideoView.get();
            if (videoView == null) {
                return;
            }
            // 只有此刻仍在播放才暂停：期间若已被用户或 Activity 生命周期暂停，
            // 这个延迟任务就不该再插一脚（否则会把「谁暂停的」搅乱）。
            if (videoView.isPlaying()) {
                mPausedForLoss = true;
                videoView.pause();
            }
        }
    };

    AudioFocusHelper(@NonNull VideoView videoView) {
        mWeakVideoView = new WeakReference<>(videoView);
        mAudioManager = (AudioManager) videoView.getContext().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void onAudioFocusChange(final int focusChange) {
        if (mCurrentFocus == focusChange) {
            return;
        }

        //由于onAudioFocusChange有可能在子线程调用，
        //故通过此方式切换到主线程去执行
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                handleAudioFocusChange(focusChange);
            }
        });

        mCurrentFocus = focusChange;
    }

    private void handleAudioFocusChange(int focusChange) {
        final VideoView videoView = mWeakVideoView.get();
        if (videoView == null) {
            return;
        }
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN://获得焦点
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT://暂时获得焦点
                // 焦点回来了：抖动的这一次根本没暂停过，撤掉待执行的暂停即可
                mHandler.removeCallbacks(mPauseForLossRun);
                if (mStartRequested || mPausedForLoss) {
                    videoView.start();
                    mStartRequested = false;
                    mPausedForLoss = false;
                }
                if (!videoView.isMute())//恢复音量
                    videoView.setVolume(1.0f, 1.0f);
                break;
            case AudioManager.AUDIOFOCUS_LOSS://焦点丢失
                // 永久丢失：不存在「等一会儿看看」的意义，保持立即暂停
                if (videoView.isPlaying()) {
                    mPausedForLoss = true;
                    videoView.pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT://焦点暂时丢失
                // ★ 原来是立即 pause()。系统通知音、语音助手、投屏、蓝牙设备切换
                //   都会短暂抢走焦点，几十到几百毫秒就还回来，画面却被实实在在
                //   停了一次 —— 表现就是「播放中一瞬间暂停画面闪过，不影响继续播放」，
                //   点播直播都一样，直播更重（恢复时要重新拉流）。
                //   改成延迟确认：焦点在窗口内还回来就完全不暂停。
                mHandler.removeCallbacks(mPauseForLossRun);
                mHandler.postDelayed(mPauseForLossRun, TRANSIENT_LOSS_PAUSE_DELAY_MS);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK://此时需降低音量
                if (videoView.isPlaying() && !videoView.isMute()) {
                    videoView.setVolume(0.1f, 0.1f);
                }
                break;
        }
    }

    /**
     * Requests to obtain the audio focus
     */
    void requestFocus() {
        if (mCurrentFocus == AudioManager.AUDIOFOCUS_GAIN) {
            return;
        }

        if (mAudioManager == null) {
            return;
        }

        int status = mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (AudioManager.AUDIOFOCUS_REQUEST_GRANTED == status) {
            mCurrentFocus = AudioManager.AUDIOFOCUS_GAIN;
            return;
        }

        mStartRequested = true;
    }

    /**
     * Requests the system to drop the audio focus
     */
    void abandonFocus() {

        if (mAudioManager == null) {
            return;
        }

        mStartRequested = false;
        mAudioManager.abandonAudioFocus(this);
    }
}