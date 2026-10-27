package com.github.tvbox.osc.player.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.render.IRenderView;
import xyz.doikki.videoplayer.render.MeasureHelper;

public class SurfaceRenderView extends SurfaceView implements IRenderView, SurfaceHolder.Callback {
    /**
     * ★ v99-r82：截图等待上限。
     *
     * <p>{@link PixelCopy} 是异步的，而 {@link IRenderView#doScreenShot()} 是同步返回 Bitmap。
     * 这里用有界等待把两者接起来：等不到就返回 null，调用方（切台快照）本来就有 null 兜底。</p>
     */
    private static final long SCREENSHOT_TIMEOUT_MS = 120L;

    private MeasureHelper mMeasureHelper;

    private AbstractPlayer mMediaPlayer;

    public SurfaceRenderView(Context context) {
        super(context);
    }

    public SurfaceRenderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SurfaceRenderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        mMeasureHelper = new MeasureHelper();
        SurfaceHolder surfaceHolder = getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setFormat(PixelFormat.RGBA_8888);
    }

    @Override
    public void attachToPlayer(@NonNull AbstractPlayer player) {
        this.mMediaPlayer = player;
        SurfaceHolder holder = getHolder();
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            player.setDisplay(holder);
        }
    }

    @Override
    public void setVideoSize(int videoWidth, int videoHeight) {
        if (videoWidth > 0 && videoHeight > 0) {
            mMeasureHelper.setVideoSize(videoWidth, videoHeight);
            requestLayout();
        }
    }

    @Override
    public void setVideoRotation(int degree) {
        mMeasureHelper.setVideoRotation(degree);
        // ★ v99-r82：API 24 以下 SurfaceView 的 surface 不参与 View 变换，
        //   setRotation 只会把 View 的挖洞区域转走、画面本身不转，反而露出黑块。
        //   尺寸按旋转后的比例算（上面那行），画面旋转交给解码器/系统。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setRotation(degree);
        }
    }

    @Override
    public void setScaleType(int scaleType) {
        mMeasureHelper.setScreenScale(scaleType);
        requestLayout();
    }

    @Override
    public View getView() {
        return this;
    }

    /**
     * ★ v99-r82：SurfaceView 取帧（默认渲染方式切到 SurfaceView 后，切台快照依赖它）。
     *
     * <p>历史实现直接 {@code return null}，于是 SurfaceView 下切台只剩一层黑罩、
     * 没有上一帧残影。改走 {@link PixelCopy}（API 24+ 才有）：</p>
     * <ul>
     *   <li>回调必须投递到<b>非主线程</b>——本方法在主线程上阻塞等待，若回调也排主线程会死锁；</li>
     *   <li>超时/失败一律返回 null，不 recycle 位图（PixelCopy 可能仍在往里写）；</li>
     *   <li>API 24 以下没有 PixelCopy：保持原来的 null 行为（5.0/6.x 盒子退化为无快照）。</li>
     * </ul>
     */
    @Override
    public Bitmap doScreenShot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return null;
        }
        SurfaceHolder holder = getHolder();
        Surface surface = holder == null ? null : holder.getSurface();
        if (surface == null || !surface.isValid()) {
            return null;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        HandlerThread thread = null;
        try {
            final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean copied = new AtomicBoolean(false);
            thread = new HandlerThread("tvbox-pixelcopy");
            thread.start();
            PixelCopy.request(surface, bitmap, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int copyResult) {
                    if (copyResult == PixelCopy.SUCCESS) {
                        copied.set(true);
                    }
                    latch.countDown();
                }
            }, new Handler(thread.getLooper()));
            if (!latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return null;
            }
            return copied.get() ? bitmap : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable th) {
            return null;
        } finally {
            if (thread != null) {
                thread.quitSafely();
            }
        }
    }

    @Override
    public void release() {

    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int[] measuredSize = mMeasureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(measuredSize[0], measuredSize[1]);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(holder);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(null);
        }
    }
}
