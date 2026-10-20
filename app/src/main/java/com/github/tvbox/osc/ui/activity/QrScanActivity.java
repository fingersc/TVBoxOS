package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.sync.AccountContext;
import com.github.tvbox.osc.sync.Crypto;
import com.github.tvbox.osc.sync.QrLoginBridge;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 手机端扫码：扫描电视上的二维码 → 把「WebDAV 配置 + 当前账号密钥」加密后 POST 给电视。
 *
 * <p>传密钥而不是密码：手机端本来就不保存密码，密钥即登录凭据。</p>
 */
public class QrScanActivity extends BaseActivity {

    private SurfaceView surface;
    private TextView tvScanTip;
    private Camera camera;
    private final MultiFormatReader reader = new MultiFormatReader();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean decoding;
    private volatile boolean done;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_qr_scan;
    }

    @Override
    protected void init() {
        surface = findViewById(R.id.surface);
        tvScanTip = findViewById(R.id.tvScanTip);
        if (AccountContext.isGuest()) {
            tvScanTip.setText("请先在本机登录账号，再扫码");
            return;
        }
        requestCamera();
    }

    private void requestCamera() {
        XXPermissions.with(this)
                .permission(Permission.CAMERA)
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        if (all) {
                            startPreview();
                        } else {
                            tvScanTip.setText("未授予相机权限");
                        }
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        tvScanTip.setText("未授予相机权限");
                    }
                });
    }

    @SuppressWarnings("deprecation")
    private void startPreview() {
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            params.setPreviewFormat(ImageFormat.NV21);
            camera.setParameters(params);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(surface.getHolder());
            camera.setPreviewCallback(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera cam) {
                    if (decoding || done) {
                        return;
                    }
                    decoding = true;
                    final byte[] frame = data;
                    final int w = cam.getParameters().getPreviewSize().width;
                    final int h = cam.getParameters().getPreviewSize().height;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            decodeFrame(frame, w, h);
                            decoding = false;
                        }
                    }).start();
                }
            });
            camera.startPreview();
            surface.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    stopCamera();
                }
            });
        } catch (Throwable t) {
            tvScanTip.setText("相机打开失败：" + t.getMessage());
        }
    }

    private void decodeFrame(byte[] data, int w, int h) {
        try {
            String text = decode(data, w, h, false);
            if (text == null) {
                text = decode(data, w, h, true);
            }
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
        } catch (Throwable ignored) {
        }
    }

    private String decode(byte[] data, int w, int h, boolean rotate) {
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                    rotate ? source.rotateCounterClockwise() : source));
            return reader.decodeWithState(bitmap).getText();
        } catch (Throwable t) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private void submit(String qrText) {
        stopCamera();
        tvScanTip.setText("正在发送账号到电视…");
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
                            .readTimeout(8, TimeUnit.SECONDS)
                            .build();
                    Request req = new Request.Builder()
                            .url(parsed)
                            .post(RequestBody.create(MediaType.parse("text/plain; charset=utf-8"), body))
                            .build();
                    try (Response resp = client.newCall(req).execute()) {
                        String text = resp.body() == null ? "" : resp.body().string();
                        msg = text.contains("ok") ? "登录信息已发送，电视将自动登录" : "发送失败：" + text;
                    }
                } catch (Throwable t) {
                    LOG.e("[Sync] qr submit failed", t);
                    msg = "发送失败：" + t.getMessage();
                }
                final String out = msg;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        tvScanTip.setText(out);
                        Toast.makeText(QrScanActivity.this, out, Toast.LENGTH_SHORT).show();
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                finish();
                            }
                        }, 1200);
                    }
                });
            }
        }, "tvbox-qr-submit").start();
    }

    @SuppressWarnings("deprecation")
    private void stopCamera() {
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

    @Override
    protected void onDestroy() {
        stopCamera();
        super.onDestroy();
    }
}
