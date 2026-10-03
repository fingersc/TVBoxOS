package com.github.tvbox.osc.player.controller;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;

import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 直播控制器
 */

public class LiveController extends BaseController {
    private int minFlingDistance = 100;             //最小识别距离
    private int minFlingVelocity = 10;              //最小识别速度

    public LiveController(@NotNull Context context) {
        super(context);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.player_live_control_view;
    }

    // 注：加载圈由基类 BaseController 通过 tag="vod_control_loading" 统一控制，
    // 这里原本另外 findViewById(R.id.loading) 赋给一个从未被读取的字段（该 id 在
    // 布局里并不存在，取到的是 null），属于无效代码，已删除。

    public interface LiveControlListener {
        boolean singleTap();

        void longPress();

        void playStateChanged(int playState);

        void changeSource(int direction);
    }

    private LiveController.LiveControlListener listener = null;

    public void setListener(LiveController.LiveControlListener listener) {
        this.listener = listener;
    }

    /**
     * 解绑回调。Activity 销毁后播放器仍可能投递最后几个状态回调，
     * 不清掉的话这些回调会打到已经 finish 的 Activity 上。
     */
    public void clearListener() {
        this.listener = null;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        // listener 在 setListener 之前（构造完成到 Activity 绑定之间）以及
        // clearListener 之后都是 null，直接调用会 NPE
        if (listener != null && listener.singleTap())
            return true;
        return super.onSingleTapConfirmed(e);
    }

    @Override
    public void onLongPress(MotionEvent e) {
        if (listener != null) {
            listener.longPress();
        }
        super.onLongPress(e);
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        if (listener != null) {
            listener.playStateChanged(playState);
        }
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        if (listener == null || e1 == null || e2 == null) {
            return false;
        }
        // e1/e2 可能为 null（某些设备上多指手势会传空），原实现直接 getX() 会 NPE
        if (e1.getX() - e2.getX() > minFlingDistance && Math.abs(velocityX) > minFlingVelocity) {
            listener.changeSource(-1);          //左滑
        } else if (e2.getX() - e1.getX() > minFlingDistance && Math.abs(velocityX) > minFlingVelocity) {
            listener.changeSource(1);           //右滑
        } else if (e1.getY() - e2.getY() > minFlingDistance && Math.abs(velocityY) > minFlingVelocity) {
        } else if (e2.getY() - e1.getY() > minFlingDistance && Math.abs(velocityY) > minFlingVelocity) {
        }
        return false;
    }
}
