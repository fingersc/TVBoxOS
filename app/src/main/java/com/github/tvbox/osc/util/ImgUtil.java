package com.github.tvbox.osc.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.FitCenter;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class ImgUtil {
    /**
     * 文字兜底图缓存的条目上限（配合「首字 key」使用，见 {@link #createTextDrawable}）。
     *
     * <p>原实现是<b>无上限的 HashMap</b>，且 key 用的是<b>完整片名</b>，而 value 是一张
     * 与海报同尺寸的 ARGB_8888 位图（1920×1080 盒子上：首页一张约 0.69 MB、
     * 详情页一张约 1.03 MB）。更要命的是 {@code load()} 里<b>无条件</b>先建兜底图
     * ——哪怕海报 URL 正常、图片加载成功，也会为每个见过的片名留一张常驻位图。
     * 全仓唯一的清理点是 {@code PlayFragment.play()}，<b>不起播就永远不清</b>；
     * 「搜索 → 进详情看简介 → 不感兴趣退出 → 再搜下一部」这种用法全程不起播，
     * 一个会话就能堆到几百 MB，最后 Bitmap.createBitmap 分配失败抛 OOM、或被 LMK 杀进程
     * （表现都是"用着用着突然回到首页"）。
     *
     * <p>修法两条：① key 从「完整片名」降为「首字」（位图上本来就只画首字，key 与内容对齐）；
     * ② 容器换成 access-order 的 LinkedHashMap 并限制条目数。叠加后最坏占用
     * ≈ 32 × 0.69 MB ≈ 22 MB，且<b>不再随时间增长</b>。
     */
    private static final int MAX_CACHE_ENTRIES = 32;

    private static final Map<String, Drawable> drawableCache = new LinkedHashMap<String, Drawable>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Drawable> eldest) {
            // 只丢弃引用，不 recycle 位图：被淘汰的 Drawable 可能仍挂在某个 ImageView 上，
            // 回收会直接引发 "Canvas: trying to use a recycled bitmap" 崩溃。
            // Android 8+ 由 NativeAllocationRegistry 在 GC 时回收像素内存，丢引用即可。
            return size() > MAX_CACHE_ENTRIES;
        }
    };
    public static int defaultWidth = 244;
    public static int defaultHeight = 320;

    public static class Style {
        public float ratio;
        public String type;

        public Style(float ratio, String type) {
            this.ratio = ratio;
            this.type = type;
        }
    }

    public static boolean isBase64Image(String picUrl) {
        return picUrl != null && picUrl.startsWith("data:image");
    }

    public static Style initStyle() {
        String bStyle = ApiConfig.get().getHomeSourceBean().getStyle();
        if (!bStyle.isEmpty()) {
            try {
                JSONObject jsonObject = new JSONObject(bStyle);
                return new Style((float) jsonObject.getDouble("ratio"), jsonObject.getString("type"));
            } catch (JSONException ignored) {
            }
        }
        return null;
    }

    public static int spanCountByStyle(Style style, int defaultCount) {
        int spanCount = defaultCount;
        if ("rect".equals(style.type)) {
            if (style.ratio >= 1.7) {
                spanCount = 3;
            } else if (style.ratio >= 1.3) {
                spanCount = 4;
            }
        } else if ("list".equals(style.type)) {
            spanCount = 1;
        }
        return spanCount;
    }

    public static int getStyleDefaultWidth(Style style) {
        int styleDefaultWidth = 280;
        if (style.ratio < 1) styleDefaultWidth = 214;
        if (style.ratio > 1.7) styleDefaultWidth = 380;
        return styleDefaultWidth;
    }

    public static Bitmap decodeBase64ToBitmap(String base64Str) {
        String base64Data = base64Str.substring(base64Str.indexOf(",") + 1);
        byte[] decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
    }

    public static void load(String url, ImageView view, int roundingRadius) {
        load(url, view, roundingRadius, 0, 0, null);
    }

    public static void load(String url, ImageView view, int roundingRadius, int newWidth, int newHeight) {
        load(url, view, roundingRadius, newWidth, newHeight, null);
    }

    public static void load(String url, ImageView view, int roundingRadius, int newWidth, int newHeight, String label, ImageView.ScaleType scaleType) {
        view.setScaleType(scaleType);
        if (roundingRadius <= 0) roundingRadius = 1;
        Drawable fallback = createTextDrawable(TextUtils.isEmpty(label) ? "TVBox" : label, newWidth, newHeight, roundingRadius);
        Drawable placeholder = createImagePlaceholderDrawable(newWidth, newHeight, roundingRadius);
        if (isInvalidImageUrl(url)) {
            view.setImageDrawable(fallback);
            return;
        }
        RequestOptions options = new RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .dontAnimate()
                .transform(new FitCenter(), new RoundedCorners(roundingRadius));
        if (newWidth > 0 && newHeight > 0) {
            options = options.override(newWidth, newHeight);
        }
        Glide.with(App.getInstance())
                .asBitmap()
                .load(getUrl(url))
                .placeholder(placeholder)
                .error(fallback)
                .listener(getListener(view, scaleType, fallback))
                .apply(options)
                .into(view);
    }

    public static void load(String url, ImageView view, int roundingRadius, int newWidth, int newHeight, String label) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (roundingRadius <= 0) roundingRadius = 1;
        Drawable fallback = createTextDrawable(TextUtils.isEmpty(label) ? "TVBox" : label, newWidth, newHeight, roundingRadius);
        Drawable placeholder = createImagePlaceholderDrawable(newWidth, newHeight, roundingRadius);
        if (isInvalidImageUrl(url)) {
            view.setImageDrawable(fallback);
            return;
        }
        RequestOptions options = new RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .dontAnimate()
                .transform(new CenterCrop(), new RoundedCorners(roundingRadius));
        if (newWidth > 0 && newHeight > 0) {
            options = options.override(newWidth, newHeight);
        }
        Glide.with(App.getInstance())
                .asBitmap()
                .load(getUrl(url))
                .placeholder(placeholder)
                .error(fallback)
                .listener(getListener(view, ImageView.ScaleType.CENTER_CROP, fallback))
                .apply(options)
                .into(view);
    }

    public static void loadUrl(String url, ImageView view) {
        load(url, view, 10);
    }

    public static void loadVideoScreenshot(String uri, ImageView imageView, long frameTimeMicros) {
        RequestOptions requestOptions = RequestOptions.frameOf(frameTimeMicros * 1000)
                .transform(new CenterCrop(), new RoundedCorners(10));
        Glide.with(App.getInstance())
                .load(uri)
                .skipMemoryCache(true)
                .apply(requestOptions)
                .into(imageView);
    }

    public static int getRandomColor() {
        Random random = new Random();
        return Color.argb(255, random.nextInt(256), random.nextInt(256), random.nextInt(256));
    }

    public static Drawable createTextDrawable(String text) {
        return createTextDrawable(text, 0, 0, AutoSizeUtils.mm2px(App.getInstance(), 10));
    }

    private static Drawable createTextDrawable(String text, int width, int height, float cornerRadius) {
        if (TextUtils.isEmpty(text)) text = "TVBox";
        if (width <= 0) width = 180;
        if (height <= 0) height = 240;
        if (cornerRadius <= 0) cornerRadius = 1;
        // ★ key 必须与实际画上去的内容对齐：位图上只画首字，就用首字做 key。
        //   旧实现用完整片名做 key —— 一万部片子就是一万张位图，可画出来的东西毫无区别
        //   （同首字、同尺寸、同圆角的兜底图长完全一样），纯属白占内存。
        text = text.substring(0, 1);
        String key = text + "_" + width + "x" + height + "_" + (int) cornerRadius;
        if (drawableCache.containsKey(key)) return drawableCache.get(key);
        int randomColor = getRandomColor();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(randomColor);
        paint.setStyle(Paint.Style.FILL);
        RectF rectF = new RectF(0, 0, width, height);
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(60);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float x = width / 2f;
        float y = (height - fontMetrics.bottom - fontMetrics.top) / 2f;
        canvas.drawText(text, x, y, paint);
        Drawable drawable = new BitmapDrawable(App.getInstance().getResources(), bitmap);
        drawableCache.put(key, drawable);
        return drawable;
    }

    private static Drawable createImagePlaceholderDrawable(int width, int height, float cornerRadius) {
        if (width <= 0) width = 180;
        if (height <= 0) height = 240;
        if (cornerRadius <= 0) cornerRadius = 1;
        String key = "placeholder_" + width + "x" + height + "_" + (int) cornerRadius;
        if (drawableCache.containsKey(key)) return drawableCache.get(key);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Bitmap icon = BitmapFactory.decodeResource(App.getInstance().getResources(), com.github.tvbox.osc.R.drawable.icon_img_placeholder);
        if (icon != null) {
            float left = (width - icon.getWidth()) / 2f;
            float top = (height - icon.getHeight()) / 2f;
            canvas.drawBitmap(icon, left, top, null);
        }

        Drawable drawable = new BitmapDrawable(App.getInstance().getResources(), bitmap);
        drawableCache.put(key, drawable);
        return drawable;
    }

    public static void clearCache() {
        drawableCache.clear();
    }

    /**
     * 系统内存告急时由 {@code App.onTrimMemory / onLowMemory} 调用。
     *
     * <p>兜底图的重建成本极低（建一张位图 + 画个圆角矩形 + 写一个字），
     * 内存紧张时整体丢弃最划算。原实现是全仓唯一<b>不响应任何内存回调</b>的缓存
     * （Glide 自己会响应，这个 Map 不会），系统越紧张它越一毛不拔。
     */
    public static void trimMemory() {
        clearCache();
    }

    public static void clearMemoryCache() {
        clearCache();
        try {
            Glide.get(App.getInstance()).clearMemory();
        } catch (Throwable th) {
            LOG.i("echo-img-clear-memory-cache-error:" + th.getMessage());
        }
    }

    private static Object getUrl(String url) {
        if (url.startsWith("data:")) return url;
        String header = null;
        String referer = null;
        String ua = null;
        String cookie = null;
        if (url.contains("@Headers=")) {
            header = url.split("@Headers=")[1].split("@")[0];
            try {
                header = URLDecoder.decode(header, "UTF-8");
            } catch (UnsupportedEncodingException ignored) {
            }
        }
        if (url.contains("@Cookie=")) cookie = url.split("@Cookie=")[1].split("@")[0];
        if (url.contains("@User-Agent=")) ua = url.split("@User-Agent=")[1].split("@")[0];
        if (url.contains("@Referer=")) referer = url.split("@Referer=")[1].split("@")[0];
        url = url.split("@")[0];
        if (TextUtils.isEmpty(url)) return null;

        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        Map<String, String> headers = new HashMap<>();
        if (!TextUtils.isEmpty(header)) {
            try {
                JsonObject jsonInfo = new Gson().fromJson(header, JsonObject.class);
                for (String key : jsonInfo.keySet()) {
                    putHeader(headers, key, jsonInfo.get(key).getAsString());
                }
            } catch (Throwable ignored) {
            }
        }
        putHeader(headers, "Cookie", cookie);
        if (!TextUtils.isEmpty(ua)) putHeader(headers, "User-Agent", ua);
        if (!TextUtils.isEmpty(referer)) putHeader(headers, "Referer", referer);
        for (Map.Entry<String, String> entry : headers.entrySet()) builder.setHeader(entry.getKey(), entry.getValue());
        return new GlideUrl(url, builder.build());
    }

    public static Object getImageModel(String url) {
        return getUrl(url);
    }

    private static boolean isInvalidImageUrl(String url) {
        if (TextUtils.isEmpty(url)) return true;
        url = url.trim();
        if (TextUtils.isEmpty(url)) return true;
        return hasEmptyProxyParam(url, "img");
    }

    private static boolean hasEmptyProxyParam(String url, String key) {
        if (!url.startsWith("proxy://") && !url.contains("/proxy?")) return false;
        int queryIndex = url.indexOf('?');
        String query = queryIndex >= 0 ? url.substring(queryIndex + 1) : url.substring("proxy://".length());
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex < 0) continue;
            if (key.equals(pair.substring(0, eqIndex)) && TextUtils.isEmpty(pair.substring(eqIndex + 1))) {
                return true;
            }
        }
        return false;
    }

    private static void putHeader(Map<String, String> headers, String key, String value) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) return;
        headers.put(key, value.trim());
    }

    private static RequestListener<Bitmap> getListener(final ImageView view, final ImageView.ScaleType scaleType, final Drawable fallback) {
        return new RequestListener<Bitmap>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                view.setScaleType(scaleType);
                view.setImageDrawable(fallback);
                return true;
            }

            @Override
            public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                view.setScaleType(scaleType);
                return false;
            }
        };
    }
}
