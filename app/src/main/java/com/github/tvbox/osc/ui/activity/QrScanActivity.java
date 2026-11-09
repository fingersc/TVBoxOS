package com.github.tvbox.osc.ui.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.sync.AccountContext;
import com.github.tvbox.osc.sync.Crypto;
import com.github.tvbox.osc.sync.QrLoginBridge;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 手机端扫码：扫描电视上的二维码 → 把「WebDAV 配置 + 账号信息」加密后 POST 给电视。
 *
 * <p>v75 修复：相机改为随 Surface 生命周期开关（原来只在 init 打开一次，权限弹窗/
 * 切后台回来就是黑屏）；补连续对焦（原来没设置对焦模式，近距离永远糊）；
 * 预览尺寸按屏幕比例挑选并校正 Surface 宽高比（原来固定全屏拉伸，画面变形）；
 * 解码前拷贝帧数据（原来直接引用相机会复用的 buffer，解码结果不稳定）。</p>
 *
 * <p>r190：新增「手动缩放」（双指捏合 → Camera1 数字变焦，屏幕显示倍率）与「点按对焦」，
 * 并把解码改为「先解取景框中心区域、失败再退回全帧」，便于扫远距离、画面里偏小的二维码。</p>
 *
 * <p>r196：扫码页改为横屏（与全 App 一致）；预览尺寸上限从 1920 放开到设备支持的上限
 * （最高 3840×2160，超 4MP 自动降频解码），只解二维码并开 TRY_HARDER，提升远距离小码的识别率。</p>
 */
public class QrScanActivity extends BaseActivity implements SurfaceHolder.Callback {

    private SurfaceView surface;
    private TextView tvScanTip;
    private Camera camera;
    private final MultiFormatReader reader = new MultiFormatReader();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decodePool = Executors.newSingleThreadExecutor();
    private volatile boolean decoding;
    private volatile boolean done;
    private boolean permissionGranted;
    private boolean surfaceReady;
    private boolean autoFocusFallback;
    private int previewW;
    private int previewH;
    /** 相机预览的显示旋转角度（按 CameraInfo 计算，不同机型可能是 90/270/0） */
    private int displayOrientation = 90;
    private long lastDecodeAt;

    // ---- r190 手动缩放（Camera1 数字变焦）与点按对焦 ----
    private TextView tvZoom;
    private ScaleGestureDetector scaleDetector;
    private boolean zoomSupported;
    private int maxZoom;
    private int[] zoomRatios;
    private int curZoom;
    /** 当前实际变焦倍率（用于手势基准与显示） */
    private float zoomRatio = 1f;
    /** 手势累积的目标倍率（不被索引量化打断，避免小步双击不动） */
    private float accumRatio = 1f;
    private boolean zoomUnsupportedShown;
    private long lastZoomApplyAt;
    // ---- 触摸判定（区分「点按对焦」与「双指缩放」）----
    private float downX, downY;
    private long downTime;
    private boolean potentialTap;
    private boolean scaled;

    // ---- r197 曝光（电视屏过亮→预览过曝→二维码黑白对比被冲掉，自动压暗）----
    private volatile boolean expSupported;
    private int expMin;
    private int expMax;
    private volatile int expCur;
    private float expStep;
    private long lastExpCheckAt;
    private int lastExpP95 = -1;
    private int expNoEffect;
    private volatile int expP95 = -1;
    private final int[] expHist = new int[256];

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_qr_scan;
    }

    @Override
    protected void init() {
        surface = findViewById(R.id.surface);
        tvScanTip = findViewById(R.id.tvScanTip);
        // r196：只解二维码格式（电视登录码）并开 TRY_HARDER，小码/远距离时扫描更彻底；
        // 顺带避免 1D 阅读器把电视画面里的横条误判成条码。
        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE));
        reader.setHints(hints);
        if (AccountContext.isGuest()) {
            setTip("请先在本机登录账号，再扫码");
            return;
        }
        View frame = findViewById(R.id.scanFrame);
        // r196：跟随全 App 的横屏。取景框按「屏幕短边」换算，横竖屏都不会溢出；
        // 原来按宽算，横屏时会等于屏宽（1280~1920），远大于屏高，直接撑出屏幕。
        int shortSide = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // r197：取景框上下各贴着一条提示文字，矮屏上给它们留出空间，避免把框挤出屏幕
        int side = (int) (shortSide * 0.68f);
        side = Math.min(side, screenH - dp(150));
        LinearLayout.LayoutParams flp = (LinearLayout.LayoutParams) frame.getLayoutParams();
        flp.width = side;
        flp.height = side;
        frame.setLayoutParams(flp);
        tvZoom = findViewById(R.id.tvZoom);
        setupZoomAndTouch(side);
        surface.getHolder().addCallback(this);
        if (hasCameraPermission()) {
            permissionGranted = true;
            openCameraIfReady();
        } else {
            requestCamera();
        }
    }

    private boolean hasCameraPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCamera() {
        XXPermissions.with(this)
                .permission(Permission.CAMERA)
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        if (all) {
                            permissionGranted = true;
                            openCameraIfReady();
                        } else {
                            setTip("未授予相机权限");
                        }
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        setTip("未授予相机权限");
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (AccountContext.isGuest() || done) {
            return;
        }
        // 「仅本次允许」的相机权限会在退到后台后被系统收回，回来时需要重新申请
        if (!hasCameraPermission()) {
            permissionGranted = false;
            requestCamera();
            return;
        }
        permissionGranted = true;
        openCameraIfReady();
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeCamera();
    }

    // ---------------------------------------------------------------- Surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        openCameraIfReady();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceReady = true;
        if (camera != null) {
            try {
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            } catch (Throwable ignored) {
            }
        } else {
            openCameraIfReady();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        closeCamera();
    }

    // ---------------------------------------------------------------- 相机

    private void openCameraIfReady() {
        if (camera != null || done || !permissionGranted || !surfaceReady) {
            return;
        }
        try {
            camera = Camera.open();
        } catch (Throwable t) {
            setTip("相机打开失败：" + t.getMessage());
            return;
        }
        try {
            Camera.Parameters params = camera.getParameters();
            params.setPreviewFormat(ImageFormat.NV21);
            applyFocusMode(params);
            Camera.Size size = choosePreviewSize(params.getSupportedPreviewSizes());
            if (size != null) {
                params.setPreviewSize(size.width, size.height);
                previewW = size.width;
                previewH = size.height;
            }
            camera.setParameters(params);
            initZoomForCamera();
            initExposureForCamera();
            camera.setDisplayOrientation(calcDisplayOrientation());
            camera.setPreviewDisplay(surface.getHolder());
            camera.setPreviewCallback(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera cam) {
                    onFrame(data);
                }
            });
            camera.startPreview();
            applySurfaceAspect();
            applyCenterMetering();
            if (autoFocusFallback) {
                startAutoFocusLoop();
            }
        } catch (Throwable t) {
            LOG.e("[Sync] camera preview failed", t);
            setTip("相机初始化失败：" + t.getMessage());
            closeCamera();
        }
    }

    /** 标准算法：后摄 (sensorOrientation - 屏幕旋转 + 360) % 360；前摄还要镜像一次。 */
    private int calcDisplayOrientation() {
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(0, info);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
                default:
                    degrees = 0;
                    break;
            }
            int result;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (info.orientation + degrees) % 360;
                result = (360 - result) % 360;
            } else {
                result = (info.orientation - degrees + 360) % 360;
            }
            displayOrientation = result;
            return result;
        } catch (Throwable t) {
            displayOrientation = 90;
            return 90;
        }
    }

    private void applyFocusMode(Camera.Parameters params) {
        autoFocusFallback = false;
        try {
            List<String> modes = params.getSupportedFocusModes();
            if (modes == null) {
                return;
            }
            if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                autoFocusFallback = true;
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * r196：优先挑与屏幕同比例的预览尺寸，同比例里取「面积最大」的一档，上限 3840×2160（约 8.3MP）。
     *
     * <p>原来给 >1920 的尺寸加罚分，等于把大预览挡在门外。远距离二维码在 1080p 里只剩几个像素，
     * 换到 1440p 是 1.33 倍线性/1.78 倍面积，4K 预览则是 2 倍线性/4 倍面积——这是纯算法补不回来的
     * 真实像素。上限用于控制 onPreviewFrame 每帧拷贝 + ZXing 解码的开销（超过 4MP 会降频解码）。</p>
     */
    private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        int vw = getResources().getDisplayMetrics().widthPixels;
        int vh = getResources().getDisplayMetrics().heightPixels;
        double target = (double) Math.max(vw, vh) / Math.min(vw, vh);
        long maxArea = 3840L * 2160L;
        Camera.Size bestMatch = null;
        Camera.Size bestAny = null;
        long bestMatchArea = -1;
        long bestAnyArea = -1;
        for (Camera.Size s : sizes) {
            long area = (long) s.width * s.height;
            if (area > maxArea || Math.max(s.width, s.height) > 3840) {
                continue;
            }
            if (area > bestAnyArea) {
                bestAnyArea = area;
                bestAny = s;
            }
            double ratio = (double) Math.max(s.width, s.height) / Math.min(s.width, s.height);
            if (Math.abs(ratio - target) <= 0.15 && area > bestMatchArea) {
                bestMatchArea = area;
                bestMatch = s;
            }
        }
        if (bestMatch != null) {
            return bestMatch;
        }
        if (bestAny != null) {
            return bestAny;
        }
        // 兜底：所有尺寸都超上限时，仍挑面积最大的，避免 previewW/H 为空导致按错误尺寸解码
        long bestArea = -1;
        Camera.Size fallback = null;
        for (Camera.Size s : sizes) {
            long area = (long) s.width * s.height;
            if (area > bestArea) {
                bestArea = area;
                fallback = s;
            }
        }
        return fallback;
    }

    /** 预览比例与屏幕不一致时加黑边居中，而不是拉伸画面。 */
    private void applySurfaceAspect() {
        if (previewW <= 0 || previewH <= 0) {
            return;
        }
        int vw = getResources().getDisplayMetrics().widthPixels;
        int vh = getResources().getDisplayMetrics().heightPixels;
        // 预览被旋转 90/270 显示时，显示宽高互换
        boolean swap = displayOrientation == 90 || displayOrientation == 270;
        float aspect = swap ? (float) previewH / (float) previewW
                : (float) previewW / (float) previewH;
        // r196：先按「宽度铺满」算高度，超出屏幕时再改成「高度铺满」——
        // 横屏正常态两者一致（1920×1080 正好铺满），异常姿态下也不会把 Surface 撑出屏幕。
        int w = vw;
        int h = Math.round(vw / aspect);
        if (h > vh) {
            h = vh;
            w = Math.round(vh * aspect);
        }
        if (surface.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
            if (lp.width == w && lp.height == h) {
                return;
            }
            lp.width = w;
            lp.height = h;
            lp.gravity = Gravity.CENTER;
            surface.setLayoutParams(lp);
        }
    }

    private void startAutoFocusLoop() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (camera == null || done) {
                    return;
                }
                try {
                    camera.autoFocus(null);
                } catch (Throwable ignored) {
                }
                main.postDelayed(this, 1500);
            }
        }, 800);
    }

    // ---------------------------------------------------------------- 缩放 / 对焦

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- 曝光（r197）

    /** r197：底部提示与取景框绑定后，空文本会留一条空底块，统一用可见性控制。 */
    private void setTip(String text) {
        if (tvScanTip == null) {
            return;
        }
        tvScanTip.setText(text == null ? "" : text);
        tvScanTip.setVisibility(text == null || text.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    }

    /** 相机打开后读一次曝光能力（补偿范围/步长/当前值）；不支持补偿时只靠测光区。 */
    private void initExposureForCamera() {
        try {
            Camera.Parameters p = camera.getParameters();
            expMin = p.getMinExposureCompensation();
            expMax = p.getMaxExposureCompensation();
            expStep = p.getExposureCompensationStep();
            expCur = p.getExposureCompensation();
            expSupported = expMax > expMin;
        } catch (Throwable t) {
            expSupported = false;
        }
        lastExpCheckAt = 0;
        lastExpP95 = -1;
        expNoEffect = 0;
        LOG.i("[QrScan] preview " + previewW + "x" + previewH + ", zoomMax=" + maxZoom
                + ", exp=[" + expMin + "," + expMax + "] step=" + expStep);
        if (!expSupported) {
            LOG.i("[QrScan] exposure compensation unsupported, rely on metering area only");
        }
    }

    /**
     * r197：把测光区收到画面中心（取景框/二维码所在处）。
     *
     * <p>电视是暗环境里的高亮发光体，整幅测光会让 AE 偏向暗部，屏幕区域直接过曝，
     * 二维码的黑白模块对比被压平。中心测光让 AE 以二维码为主体曝光。</p>
     */
    private void applyCenterMetering() {
        if (camera == null) {
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            if (p.getMaxNumMeteringAreas() > 0) {
                List<Camera.Area> areas = new ArrayList<>();
                areas.add(new Camera.Area(new Rect(-400, -400, 400, 400), 1000));
                p.setMeteringAreas(areas);
                camera.setParameters(p);
            }
        } catch (Throwable t) {
            LOG.e("[QrScan] set metering area failed", t);
        }
    }

    /**
     * r197：按中心区域亮度自适应曝光补偿。p95 太高说明白色模块接近裁切，往负方向压；
     * 压过头（p95 太低）再往 0 回。只在变化时调参数，且每次重新 getParameters，避免被缩放覆盖。
     */
    private void maybeAdjustExposure(byte[] data, int w, int h) {
        if (!expSupported || data == null || w <= 0 || h <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastExpCheckAt < 500) {
            return;
        }
        lastExpCheckAt = now;
        int len = Math.min(data.length, w * h);
        int x0 = w / 4, x1 = w - w / 4, y0 = h / 4, y1 = h - h / 4;
        int[] hist = expHist;
        Arrays.fill(hist, 0);
        int total = 0;
        for (int yy = y0; yy < y1; yy += 8) {
            int row = yy * w;
            for (int xx = x0; xx < x1; xx += 8) {
                int idx = row + xx;
                if (idx < len) {
                    hist[data[idx] & 0xff]++;
                    total++;
                }
            }
        }
        if (total < 64) {
            return;
        }
        int p95 = percentile(hist, total, 0.95f);
        expP95 = p95;
        // 设备忽略补偿：连续多次调整后 p95 仍不动，就别再反复 setParameters 折腾了
        if (lastExpP95 >= 0 && Math.abs(p95 - lastExpP95) < 5) {
            expNoEffect++;
            if (expNoEffect >= 3) {
                expSupported = false;
                LOG.i("[QrScan] exposure adjust has no effect on this device, stop");
                return;
            }
        } else {
            expNoEffect = 0;
        }
        lastExpP95 = p95;
        int target = expCur;
        if (p95 >= 240 && expCur > expMin) {
            target = expCur - 1;
        } else if (p95 <= 170 && expCur < 0) {
            target = expCur + 1;
        }
        if (target != expCur) {
            expCur = target;
            requestApplyExposure();
        }
    }

    private int percentile(int[] hist, int total, float frac) {
        int need = Math.max(1, Math.round(total * frac));
        int acc = 0;
        for (int i = 0; i < hist.length; i++) {
            acc += hist[i];
            if (acc >= need) {
                return i;
            }
        }
        return 255;
    }

    private void requestApplyExposure() {
        main.post(exposureRunnable);
    }

    private final Runnable exposureRunnable = new Runnable() {
        @Override
        public void run() {
            if (camera == null || done) {
                return;
            }
            try {
                Camera.Parameters p = camera.getParameters();
                if (p.getExposureCompensation() == expCur) {
                    return;
                }
                p.setExposureCompensation(expCur);
                camera.setParameters(p);
                LOG.i("[QrScan] exposure p95=" + expP95 + " -> exp=" + expCur
                        + " (" + (expCur * expStep) + "EV)");
            } catch (Throwable t) {
                expSupported = false;
                LOG.e("[QrScan] apply exposure failed", t);
            }
        }
    };

    /** r196：横屏下把倍率提示放到取景框右侧（底部有提示条，放下面会被盖住）。 */
    private void positionZoomTip(int frameSide) {
        if (tvZoom == null) {
            return;
        }
        int screenW = getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams zp = (FrameLayout.LayoutParams) tvZoom.getLayoutParams();
        zp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        zp.leftMargin = screenW / 2 + frameSide / 2 + dp(14);
        tvZoom.setLayoutParams(zp);
    }

    private void setupZoomAndTouch(int frameSide) {
        positionZoomTip(frameSide);
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (!zoomSupported) {
                    return false;
                }
                accumRatio = zoomRatio;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaled = true;
                if (!zoomSupported) {
                    return false;
                }
                float maxRatio = maxZoomRatio();
                accumRatio *= detector.getScaleFactor();
                if (accumRatio < 1f) {
                    accumRatio = 1f;
                }
                if (accumRatio > maxRatio) {
                    accumRatio = maxRatio;
                }
                int idx = ratioToIndex(accumRatio);
                if (idx != curZoom) {
                    curZoom = idx;
                    zoomRatio = zoomRatios[idx] / 100f;
                    requestApplyZoom();
                    updateZoomTip();
                }
                return true;
            }
        });
        surface.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                scaleDetector.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        downTime = System.currentTimeMillis();
                        potentialTap = true;
                        scaled = false;
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        potentialTap = false;
                        if (!zoomSupported) {
                            showZoomUnsupportedOnce();
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (potentialTap) {
                            float dx = e.getX() - downX;
                            float dy = e.getY() - downY;
                            if (dx * dx + dy * dy > (float) slop * slop) {
                                potentialTap = false;
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (potentialTap && !scaled && System.currentTimeMillis() - downTime < 350) {
                            focusAt(e.getX(), e.getY());
                        }
                        potentialTap = false;
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        potentialTap = false;
                        break;
                }
                return true;
            }
        });
    }

    private void showZoomUnsupportedOnce() {
        if (zoomUnsupportedShown) {
            return;
        }
        zoomUnsupportedShown = true;
        Toast.makeText(this, "本机相机不支持手动缩放", Toast.LENGTH_SHORT).show();
    }

    private void initZoomForCamera() {
        zoomSupported = false;
        maxZoom = 0;
        zoomRatios = null;
        curZoom = 0;
        zoomRatio = 1f;
        accumRatio = 1f;
        Camera c = camera;
        if (c != null) {
            try {
                Camera.Parameters p = c.getParameters();
                if (p.isZoomSupported()) {
                    List<Integer> ratios = p.getZoomRatios();
                    int mz = p.getMaxZoom();
                    if (ratios != null && !ratios.isEmpty() && mz > 0 && mz < ratios.size()) {
                        zoomRatios = new int[ratios.size()];
                        for (int i = 0; i < ratios.size(); i++) {
                            zoomRatios[i] = ratios.get(i);
                        }
                        maxZoom = mz;
                        zoomSupported = true;
                    }
                }
            } catch (Throwable t) {
                zoomSupported = false;
            }
        }
        updateZoomTip();
    }

    private float maxZoomRatio() {
        if (zoomRatios != null && maxZoom > 0 && maxZoom < zoomRatios.length) {
            return zoomRatios[maxZoom] / 100f;
        }
        return 1f;
    }

    private int ratioToIndex(float ratio) {
        if (zoomRatios == null || zoomRatios.length == 0) {
            return 0;
        }
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < zoomRatios.length; i++) {
            float d = Math.abs(zoomRatios[i] / 100f - ratio);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return best;
    }

    private final Runnable zoomApplyRunnable = new Runnable() {
        @Override
        public void run() {
            lastZoomApplyAt = System.currentTimeMillis();
            applyZoomToCamera();
        }
    };

    private void requestApplyZoom() {
        long now = System.currentTimeMillis();
        if (now - lastZoomApplyAt >= 40) {
            lastZoomApplyAt = now;
            applyZoomToCamera();
        } else {
            main.removeCallbacks(zoomApplyRunnable);
            main.postDelayed(zoomApplyRunnable, 50);
        }
    }

    private void applyZoomToCamera() {
        Camera c = camera;
        if (c == null) {
            return;
        }
        try {
            Camera.Parameters p = c.getParameters();
            p.setZoom(curZoom);
            c.setParameters(p);
        } catch (Throwable t) {
            LOG.e("[Sync] setZoom failed", t);
        }
    }

    private void updateZoomTip() {
        if (tvZoom == null) {
            return;
        }
        if (!zoomSupported || curZoom <= 0 || zoomRatios == null || curZoom >= zoomRatios.length) {
            tvZoom.setVisibility(View.GONE);
            return;
        }
        tvZoom.setText(String.format(java.util.Locale.US, "%.1f×", zoomRatios[curZoom] / 100f));
        tvZoom.setVisibility(View.VISIBLE);
    }

    /** 点按处对焦：设置对焦/测光区域并触发一次自动对焦（不合适/不支持时静默忽略）。 */
    private void focusAt(float x, float y) {
        Camera c = camera;
        if (c == null) {
            return;
        }
        try {
            Camera.Parameters p = c.getParameters();
            if (p.getMaxNumFocusAreas() > 0) {
                Rect area = buildFocusRect(x, y);
                List<Camera.Area> areas = new ArrayList<>();
                areas.add(new Camera.Area(area, 800));
                p.setFocusAreas(areas);
                if (p.getMaxNumMeteringAreas() > 0) {
                    p.setMeteringAreas(areas);
                }
                c.setParameters(p);
            }
            c.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera cam) {
                    // 连续对焦模式无需恢复；AUTO 兜底模式由 startAutoFocusLoop 继续轮询
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 触摸点(相对预览视图) → Camera1 的 -1000..1000 对焦区域坐标。 */
    private Rect buildFocusRect(float x, float y) {
        int w = surface.getWidth() > 0 ? surface.getWidth() : 1;
        int h = surface.getHeight() > 0 ? surface.getHeight() : 1;
        int half = 180;
        int cx = (int) (x / w * 2000f - 1000f);
        int cy = (int) (y / h * 2000f - 1000f);
        cx = clamp(cx, -1000 + half, 1000 - half);
        cy = clamp(cy, -1000 + half, 1000 - half);
        return new Rect(cx - half, cy - half, cx + half, cy + half);
    }

    private int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void closeCamera() {
        try {
            if (camera != null) {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
                camera = null;
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 解码

    private void onFrame(byte[] data) {
        if (decoding || done || data == null) {
            return;
        }
        // r197：先按中心亮度自适应曝光（自带 500ms 节流，与解码节流无关）
        maybeAdjustExposure(data, previewW, previewH);
        long now = System.currentTimeMillis();
        // 大预览帧拷贝/解码更重，按像素量降频，避免拖慢预览：
        // >4MP（4K/1440p）每 700ms 解一次，>2.5MP 每 400ms，其余 250ms。
        int pixels = previewW * previewH;
        int minInterval = pixels > 4000000 ? 700 : (pixels > 2500000 ? 400 : 250);
        if (now - lastDecodeAt < minInterval) {
            return;
        }
        lastDecodeAt = now;
        decoding = true;
        final int w = previewW > 0 ? previewW : 640;
        final int h = previewH > 0 ? previewH : 480;
        // 相机复用同一个 buffer；只拷 Y 平面（NV21 前 w*h 字节）即可——
        // PlanarYUVLuminanceSource 只用亮度，高分辨率下能省掉 1/3 的拷贝量
        final byte[] frame = Arrays.copyOf(data, Math.min(data.length, w * h));
        try {
            decodePool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        decodeFrame(frame, w, h);
                    } finally {
                        decoding = false;
                    }
                }
            });
        } catch (Throwable t) {
            decoding = false;
        }
    }

    private void decodeFrame(byte[] data, int w, int h) {
        // QR 的三点定位本身对旋转不敏感，不需要把帧再旋转：省两次解码开销
        String text = decode(data, w, h);
        if (text != null) {
            done = true;
            final String result = text;
            main.post(new Runnable() {
                @Override
                public void run() {
                    submit(result);
                }
            });
        }
    }

    /** 先解「取景框」中心区域（去掉四周背景，二值化更稳），失败再退回全帧，兼顾不漏扫。 */
    private String decode(byte[] data, int w, int h) {
        String text = decodeRegion(data, w, h, 0.72f);
        if (text == null) {
            text = decodeRegion(data, w, h, 1.0f);
        }
        return text;
    }

    /** 以整帧中心为准裁出 frac 比例的矩形区域解码；frac>=1 即整帧。 */
    private String decodeRegion(byte[] data, int w, int h, float frac) {
        try {
            int cw = Math.round(w * frac);
            int ch = Math.round(h * frac);
            if (cw <= 0 || cw > w) {
                cw = w;
            }
            if (ch <= 0 || ch > h) {
                ch = h;
            }
            int left = (w - cw) / 2;
            int top = (h - ch) / 2;
            PlanarYUVLuminanceSource source =
                    new PlanarYUVLuminanceSource(data, w, h, left, top, cw, ch, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            return reader.decodeWithState(bitmap).getText();
        } catch (Throwable t) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private void submit(String qrText) {
        closeCamera();
        setTip("正在发送账号到电视…");
        final String url = qrText == null ? "" : qrText.trim();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
                    if (parsed == null) {
                        throw new IllegalArgumentException("二维码内容无法识别");
                    }
                    String token = parsed.queryParameter("t");
                    if (token == null) {
                        throw new IllegalArgumentException("二维码缺少令牌");
                    }
                    byte[] key = Crypto.deriveKey(token, "tvbox-qr-login");
                    JSONObject o = new JSONObject();
                    o.put("url", Hawk.get(HawkConfig.SYNC_WEBDAV_URL, ""));
                    o.put("davUser", Hawk.get(HawkConfig.SYNC_WEBDAV_USER, ""));
                    o.put("davPass", Hawk.get(HawkConfig.SYNC_WEBDAV_PASS, ""));
                    o.put("owner", AccountContext.ownerKey());
                    o.put("name", AccountContext.displayName());
                    byte[] k = AccountContext.key();
                    o.put("keyHex", k == null ? "" : QrLoginBridge.bytesToHex(k));
                    byte[] cipher = Crypto.encrypt(key, o.toString().getBytes("UTF-8"));
                    String body = QrLoginBridge.bytesToHex(cipher);

                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(8, TimeUnit.SECONDS)
                            // r195：电视端改为「先回执、后台校验」，正常几百毫秒即返回；
                            // 这里留 12s 只是兜底，避免极端网络下把仍在处理的请求误判成失败。
                            .readTimeout(12, TimeUnit.SECONDS)
                            .build();
                    Request req = new Request.Builder()
                            .url(parsed)
                            .post(RequestBody.create(MediaType.parse("text/plain; charset=utf-8"), body))
                            .build();
                    try (Response resp = client.newCall(req).execute()) {
                        String text = resp.body() == null ? "" : resp.body().string();
                        if (text.contains("ok")) {
                            msg = "已发送，电视正在登录…";
                        } else if (text.contains("no_dir")) {
                            msg = "电视上没有这个账号：请确认电视与本机用的是同一份网盘";
                        } else if (text.contains("mismatch")) {
                            msg = "电视要登录的不是这个账号：请先在手机上切换到目标账号再扫";
                        } else {
                            msg = "发送失败：" + text;
                        }
                    }
                } catch (java.net.SocketTimeoutException te) {
                    LOG.e("[Sync] qr submit timeout", te);
                    msg = "电视响应超时：可能仍在登录，请稍后看电视是否已登录";
                } catch (Throwable t) {
                    LOG.e("[Sync] qr submit failed", t);
                    msg = "发送失败：" + t.getMessage();
                }
                final String out = msg;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        setTip(out);
                        Toast.makeText(QrScanActivity.this, out, Toast.LENGTH_SHORT).show();
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                finish();
                            }
                        }, 800);
                    }
                });
            }
        }, "tvbox-qr-submit").start();
    }

    @Override
    protected void onDestroy() {
        done = true;
        closeCamera();
        decodePool.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
