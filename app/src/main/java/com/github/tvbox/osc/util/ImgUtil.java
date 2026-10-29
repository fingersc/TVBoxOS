package com.github.tvbox.osc.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
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

import me.jessyan.autosize.utils.AutoSizeUtils;

public class ImgUtil {
    /**
     * 封面兜底图 / 占位图缓存的条目上限。
     *
     * <p>这些位图与海报同尺寸（1920×1080 盒子上：首页一张约 0.69 MB、详情页一张约 1.03 MB），
     * 且 {@code load()} 里会先构建占位图/兜底图。原实现是<b>无上限的 HashMap</b>，
     * 「搜索 → 进详情看简介 → 不感兴趣退出 → 再搜下一部」这种全程不起播的用法会不断堆积，
     * 一个会话就能堆到几百 MB，最后 Bitmap.createBitmap 分配失败抛 OOM、或被 LMK 杀进程
     * （表现都是"用着用着突然回到首页"）。
     *
     * <p>修法：容器换成 access-order 的 LinkedHashMap 并限制条目数，最坏占用
     * ≈ 32 × 1 MB ≈ 32 MB，且<b>不再随时间增长</b>。key 用「尺寸 + 圆角 + 配色」，
     * 与位图内容对齐——同规格的兜底/占位图外观完全一致，无需按片名各存一份。
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
        Drawable fallback = createFallbackDrawable(newWidth, newHeight, roundingRadius);
        if (isInvalidImageUrl(url) || isFailedUrl(url)) {
            view.setImageDrawable(fallback);
            return;
        }
        // 加载中占位：透明底 + 与兜底图等大的小电视（图标尺寸一致，避免加载/失败切换时图标跳变）。
        Drawable placeholder = createImagePlaceholderDrawable(newWidth, newHeight, roundingRadius);
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
                .listener(getListener(view, scaleType, fallback, url))
                .apply(options)
                .into(view);
    }

    public static void load(String url, ImageView view, int roundingRadius, int newWidth, int newHeight, String label) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (roundingRadius <= 0) roundingRadius = 1;
        Drawable fallback = createFallbackDrawable(newWidth, newHeight, roundingRadius);
        if (isInvalidImageUrl(url) || isFailedUrl(url)) {
            view.setImageDrawable(fallback);
            return;
        }
        // 加载中占位：透明底 + 与兜底图等大的小电视（图标尺寸一致，避免加载/失败切换时图标跳变）。
        Drawable placeholder = createImagePlaceholderDrawable(newWidth, newHeight, roundingRadius);
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
                .listener(getListener(view, ImageView.ScaleType.CENTER_CROP, fallback, url))
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

    /** 封面兜底图固定底色（深灰蓝），取代原先的随机颜色。 */
    private static final int COVER_FALLBACK_COLOR = 0xFF4A4F58;

    /** 已确认加载失败的封面 URL 上限（LRU，防止无限增长）。 */
    private static final int MAX_FAILED_URLS = 256;

    /** 失败标记有效期：超过该时长再次绑定会重新尝试加载（避免一次网络抖动永久钉死为兜底图）。 */
    private static final long FAILED_URL_TTL_MS = 5 * 60 * 1000L;

    /**
     * 已确认加载失败的封面 URL → 失败时间戳（access-order LRU）。
     *
     * <p>用于"加载机制"修正：某张封面一旦确认加载失败，其卡片在滑动回收复用、
     * 再次绑定到列表/网格时直接显示失败兜底图，而不再重新走「透明占位→失败底色」
     * 这段流程，从而消除一遍遍"透明底→底色"的闪烁；成功加载会清除该标记，
     * 且标记超过 {@link #FAILED_URL_TTL_MS} 后失效以便重试。
     */
    private static final Map<String, Long> failedUrls = new LinkedHashMap<String, Long>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_FAILED_URLS;
        }
    };

    private static boolean isFailedUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        synchronized (failedUrls) {
            Long ts = failedUrls.get(url);
            return ts != null && (System.currentTimeMillis() - ts) < FAILED_URL_TTL_MS;
        }
    }

    private static void markFailedUrl(String url) {
        if (TextUtils.isEmpty(url)) return;
        synchronized (failedUrls) {
            failedUrls.put(url, System.currentTimeMillis());
        }
    }

    private static void clearFailedUrl(String url) {
        if (TextUtils.isEmpty(url)) return;
        synchronized (failedUrls) {
            failedUrls.remove(url);
        }
    }

    /** 外部调用方（详情、列表等）使用的兜底封面：默认尺寸 + 固定色 + 小电视。 */
    public static Drawable createFallbackDrawable() {
        return createFallbackDrawable(0, 0, AutoSizeUtils.mm2px(App.getInstance(), 10));
    }

    /**
     * 封面加载失败 / 图片 URL 无效时的兜底图：固定底色 {@link #COVER_FALLBACK_COLOR}
     * + 居中小电视图标（{@code icon_img_placeholder}），不再绘制片名首字。
     */
    private static Drawable createFallbackDrawable(int width, int height, float cornerRadius) {
        if (width <= 0) width = 180;
        if (height <= 0) height = 240;
        if (cornerRadius <= 0) cornerRadius = 1;
        String key = "fallback_" + width + "x" + height + "_" + (int) cornerRadius;
        if (drawableCache.containsKey(key)) return drawableCache.get(key);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(COVER_FALLBACK_COLOR);
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(0, 0, width, height), cornerRadius, cornerRadius, bgPaint);

        // 小电视图标按短边 80% 居中缩放叠放，视觉更饱满且仍不溢出（80% 短边 < 长边）。
        Bitmap icon = BitmapFactory.decodeResource(App.getInstance().getResources(), com.github.tvbox.osc.R.drawable.icon_img_placeholder);
        if (icon != null) {
            float box = Math.min(width, height) * 0.8f;
            RectF dst = new RectF((width - box) / 2f, (height - box) / 2f, (width + box) / 2f, (height + box) / 2f);
            Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            iconPaint.setFilterBitmap(true);
            canvas.drawBitmap(icon, null, dst, iconPaint);
        }

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

        // 与 createFallbackDrawable 使用同一"短边 80% 居中"尺寸，保证加载中/失败两态图标大小一致。
        Bitmap icon = BitmapFactory.decodeResource(App.getInstance().getResources(), com.github.tvbox.osc.R.drawable.icon_img_placeholder);
        if (icon != null) {
            float box = Math.min(width, height) * 0.8f;
            RectF dst = new RectF((width - box) / 2f, (height - box) / 2f, (width + box) / 2f, (height + box) / 2f);
            Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            iconPaint.setFilterBitmap(true);
            canvas.drawBitmap(icon, null, dst, iconPaint);
        }

        Drawable drawable = new BitmapDrawable(App.getInstance().getResources(), bitmap);
        drawableCache.put(key, drawable);
        return drawable;
    }

    public static void clearCache() {
        drawableCache.clear();
        synchronized (failedUrls) {
            failedUrls.clear();
        }
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
        // ★ v99-r31：Referer 兜底。
        //   原来只有 URL 显式带 @Referer= 才加 Referer，而绝大多数采集站的图床都校验防盗链：
        //   没有 Referer 直接 403 ⇒ Glide 走 error ⇒ 聚合搜索里出现"只有中间一个字"的卡片。
        //   兜底策略：用图片自身域名作为 Referer（等价于"同站引用"，绝大多数图床放行）。
        //   显式 @Referer= 仍然优先（源自己声明的更准）。
        if (TextUtils.isEmpty(referer)) {
            referer = hostReferer(url);
        }
        if (!TextUtils.isEmpty(referer)) putHeader(headers, "Referer", referer);
        for (Map.Entry<String, String> entry : headers.entrySet()) builder.setHeader(entry.getKey(), entry.getValue());
        return new GlideUrl(url, builder.build());
    }

    /** 取 scheme://host/ 作为兜底 Referer；解析不出来返回 null。 */
    private static String hostReferer(String url) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            String scheme = u.getScheme();
            String host = u.getHost();
            if (!TextUtils.isEmpty(scheme) && !TextUtils.isEmpty(host)
                    && (scheme.startsWith("http"))) {
                return scheme + "://" + host + "/";
            }
        } catch (Throwable ignored) {
        }
        return null;
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

    private static RequestListener<Bitmap> getListener(final ImageView view, final ImageView.ScaleType scaleType, final Drawable fallback, final String url) {
        return new RequestListener<Bitmap>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                // 记录失败，后续复用该卡片时直接显示兜底图，不再重复"透明占位→底色"闪烁。
                markFailedUrl(url);
                view.setScaleType(scaleType);
                view.setImageDrawable(fallback);
                return true;
            }

            @Override
            public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                // 加载成功则清除历史失败标记（网络恢复后仍可正常显示）。
                clearFailedUrl(url);
                view.setScaleType(scaleType);
                return false;
            }
        };
    }
}