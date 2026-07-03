package com.vlab.scrbalance;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

/**
 * 悬浮窗覆盖服务 - 在屏幕上方显示半透明校正层
 * 支持亮度校色配置的线性插值，支持旋转纠正
 */
public class OverlayService extends Service {

    private static final String CHANNEL_ID = "scr_balance_channel";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private View overlayLeftView;
    private View overlayRightView;
    private AppConfig config;
    private FoldDetector foldDetector;
    private ContentObserver brightnessObserver;
    private DisplayManager.DisplayListener displayListener;
    private Handler brightnessHandler;

    // 缓存上次显示参数，避免无变化时反复 destroy+recreate 导致闪烁
    private int lastLeftColor = -1;
    private int lastRightColor = -1;
    private int lastLeftOpacity = -1;
    private int lastRightOpacity = -1;
    private int lastScreenWidth = -1;
    private int lastScreenHeight = -1;
    private Runnable sizeCheckRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        config = new AppConfig(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        foldDetector = new FoldDetector(this);
        foldDetector.setCallback(isUnfolded -> {
            if (isUnfolded) {
                updateOverlayForBrightness();
            } else {
                hideOverlay();
            }
        });
        foldDetector.startListening();

        // 监听屏幕亮度变化（事件驱动，无需定时轮询）
        brightnessHandler = new Handler();
        brightnessObserver = new ContentObserver(brightnessHandler) {
            @Override
            public void onChange(boolean selfChange) {
                brightnessHandler.post(() -> updateOverlayForBrightness());
            }
        };
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brightnessObserver);
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, brightnessObserver);

        // 监听屏幕尺寸/旋转变化（事件驱动，替代之前的5秒定时轮询）
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {}
            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    brightnessHandler.post(() -> updateOverlayForScreenSize());
                }
            }
            @Override
            public void onDisplayRemoved(int displayId) {}
        };
        displayManager.registerDisplayListener(displayListener, brightnessHandler);

        // 安全兜底：10秒检查屏幕尺寸变化，仅在尺寸真正变化时才刷新
        sizeCheckRunnable = new Runnable() {
            @Override public void run() {
                Display display = windowManager.getDefaultDisplay();
                android.graphics.Point realSize = new android.graphics.Point();
                display.getRealSize(realSize);
                if (realSize.x != lastScreenWidth || realSize.y != lastScreenHeight) {
                    updateOverlayForScreenSize();
                }
                brightnessHandler.postDelayed(this, 10000);
            }
        };
        brightnessHandler.postDelayed(sizeCheckRunnable, 10000);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        updateOverlayForScreenSize();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "ACTION_UPDATE":
                    invalidateCache(); // 外部触发更新时清除缓存，确保必定刷新
                    updateOverlayForBrightness();
                    break;
                case "ACTION_STOP":
                    stopSelf();
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        foldDetector.stopListening();
        getContentResolver().unregisterContentObserver(brightnessObserver);
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayListener != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        if (sizeCheckRunnable != null) {
            brightnessHandler.removeCallbacks(sizeCheckRunnable);
        }
        brightnessHandler.removeCallbacksAndMessages(null);
        removeOverlay();
        invalidateCache();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_crop)
                .setOngoing(true)
                .build();
    }

    private int getOverlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
    }

    /** 获取当前屏幕亮度(0-255) */
    private int getCurrentBrightness() {
        try {
            return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Exception e) {
            return 128;
        }
    }

    /** 根据亮度+灰度更新覆盖层 - 核心方法
     *  仅在参数/屏幕尺寸真正变化时才刷新overlay，避免无变化时的闪烁 */
    private void updateOverlayForBrightness() {
        if (!config.isEnabled()) {
            removeOverlay();
            invalidateCache();
            return;
        }
        if (!foldDetector.isUnfolded()) {
            removeOverlay();
            invalidateCache();
            return;
        }

        // 检查屏幕尺寸是否变化（旋转时需要重建布局）
        Display display = windowManager.getDefaultDisplay();
        android.graphics.Point realSize = new android.graphics.Point();
        display.getRealSize(realSize);
        boolean screenSizeChanged = (realSize.x != lastScreenWidth || realSize.y != lastScreenHeight);

        // 设置模式：暂停亮度插值，直接使用手动设置值
        if (config.isSettingsMode()) {
            int lc = config.getLeftColor(), rc = config.getRightColor();
            int lo = config.getLeftOpacity(), ro = config.getRightOpacity();
            if (!screenSizeChanged && lc == lastLeftColor && rc == lastRightColor
                    && lo == lastLeftOpacity && ro == lastRightOpacity) {
                return; // 无变化，跳过刷新
            }
            showOverlayWithValues(lc, rc, lo, ro);
            return;
        }

        List<BrightnessProfile> profiles = config.getBrightnessProfiles();
        int currentBrightness = getCurrentBrightness();
        int currentGrayLevel = config.getCurrentGrayLevel();

        int lc, rc, lo, ro;
        if (profiles.isEmpty()) {
            lc = config.getLeftColor(); rc = config.getRightColor();
            lo = config.getLeftOpacity(); ro = config.getRightOpacity();
        } else {
            int[] interpolated = bilinearInterpolate(profiles, currentBrightness, currentGrayLevel);
            lc = interpolated[0]; rc = interpolated[1];
            lo = interpolated[2]; ro = interpolated[3];
        }

        // 参数和尺寸都没变化 → 跳过刷新，避免闪烁
        if (!screenSizeChanged && lc == lastLeftColor && rc == lastRightColor
                && lo == lastLeftOpacity && ro == lastRightOpacity) {
            return;
        }

        showOverlayWithValues(lc, rc, lo, ro);
    }

    /**
     * 2D双线性插值（亮度×灰度）
     * 返回 [leftColor, rightColor, leftOpacity, rightOpacity]
     *
     * 策略：在profiles中找到包围当前(brightness, grayLevel)的4个点（矩形四角），
     * 在矩形内做双线性插值。如果数据不足以构成矩形，降级为1D线性插值或直接取最近点。
     */
    private int[] bilinearInterpolate(List<BrightnessProfile> profiles, int brightness, int grayLevel) {
        // 寻找4个包围点：(blower,glower), (blower,gupper), (bupper,glower), (bupper,gupper)
        BrightnessProfile blower = null, bupper = null;
        int glower = Integer.MAX_VALUE, gupper = Integer.MIN_VALUE;

        // 第一步：找到亮度方向的上下界
        for (BrightnessProfile p : profiles) {
            if (p.brightness <= brightness) {
                if (blower == null || p.brightness > blower.brightness) blower = p;
            }
            if (p.brightness >= brightness) {
                if (bupper == null || p.brightness < bupper.brightness) bupper = p;
            }
        }

        // 第二步：在亮度上下界对应的profiles中，找灰度方向的上下界
        // 收集所有在亮度范围内的profile的灰度值
        for (BrightnessProfile p : profiles) {
            // 只考虑亮度在[blower..bupper]范围内的profiles
            int bMin = blower != null ? blower.brightness : 0;
            int bMax = bupper != null ? bupper.brightness : 255;
            if (p.brightness >= bMin && p.brightness <= bMax) {
                if (p.grayLevel <= grayLevel && p.grayLevel < glower) glower = p.grayLevel;
                if (p.grayLevel >= grayLevel && p.grayLevel > gupper) gupper = p.grayLevel;
            }
        }

        // 如果没有找到灰度界，说明所有profile的grayLevel相同，降级为1D
        if (glower == Integer.MAX_VALUE) glower = grayLevel;
        if (gupper == Integer.MIN_VALUE) gupper = grayLevel;

        // 如果亮度只有一维数据，降级为1D插值
        if (blower == null && bupper == null) {
            BrightnessProfile fallback = profiles.get(0);
            return new int[]{fallback.leftColor, fallback.rightColor, fallback.leftOpacity, fallback.rightOpacity};
        }

        // 灰度维度相同 → 1D线性插值
        if (glower == gupper) {
            return linearInterpolate1D(profiles, brightness, glower);
        }

        // 亮度维度相同 → 按灰度1D插值
        if ((blower == null || bupper == null) || blower.brightness == bupper.brightness) {
            return linearInterpolate1DGray(profiles, grayLevel, blower != null ? blower.brightness : (bupper != null ? bupper.brightness : brightness));
        }

        // 真正的2D双线性插值：找到4个角点
        BrightnessProfile p00 = findClosest(profiles, blower.brightness, glower); // 左下
        BrightnessProfile p01 = findClosest(profiles, blower.brightness, gupper); // 左上
        BrightnessProfile p10 = findClosest(profiles, bupper.brightness, glower); // 右下
        BrightnessProfile p11 = findClosest(profiles, bupper.brightness, gupper); // 右上

        // 如果找不到4个角点，降级
        if (p00 == null || p01 == null || p10 == null || p11 == null) {
            return linearInterpolate1D(profiles, brightness, glower);
        }

        float bRatio = (float)(brightness - blower.brightness) / (bupper.brightness - blower.brightness);
        float gRatio = (float)(grayLevel - glower) / (gupper - glower);

        // 双线性插值：先在亮度方向插值两行，再在灰度方向插值
        int leftColorLow = interpolateColor(p00.leftColor, p10.leftColor, bRatio);
        int leftColorHigh = interpolateColor(p01.leftColor, p11.leftColor, bRatio);
        int leftColor = interpolateColor(leftColorLow, leftColorHigh, gRatio);

        int rightColorLow = interpolateColor(p00.rightColor, p10.rightColor, bRatio);
        int rightColorHigh = interpolateColor(p01.rightColor, p11.rightColor, bRatio);
        int rightColor = interpolateColor(rightColorLow, rightColorHigh, gRatio);

        int leftOpLow = (int)Math.round(p00.leftOpacity + bRatio * (p10.leftOpacity - p00.leftOpacity));
        int leftOpHigh = (int)Math.round(p01.leftOpacity + bRatio * (p11.leftOpacity - p01.leftOpacity));
        int leftOpacity = (int)Math.round(leftOpLow + gRatio * (leftOpHigh - leftOpLow));

        int rightOpLow = (int)Math.round(p00.rightOpacity + bRatio * (p10.rightOpacity - p00.rightOpacity));
        int rightOpHigh = (int)Math.round(p01.rightOpacity + bRatio * (p11.rightOpacity - p01.rightOpacity));
        int rightOpacity = (int)Math.round(rightOpLow + gRatio * (rightOpHigh - rightOpLow));

        return new int[]{leftColor, rightColor, leftOpacity, rightOpacity};
    }

    /** 在profiles中找到最接近指定(brightness, grayLevel)的profile */
    private BrightnessProfile findClosest(List<BrightnessProfile> profiles, int brightness, int grayLevel) {
        BrightnessProfile best = null;
        float bestDist = Float.MAX_VALUE;
        for (BrightnessProfile p : profiles) {
            if (p.brightness == brightness && p.grayLevel == grayLevel) return p;
            float dist = Math.abs(p.brightness - brightness) + Math.abs(p.grayLevel - grayLevel) * 2.55f;
            if (dist < bestDist) { bestDist = dist; best = p; }
        }
        return best;
    }

    /** 1D线性插值（仅亮度维度），在指定grayLevel附近找点 */
    private int[] linearInterpolate1D(List<BrightnessProfile> profiles, int brightness, int grayLevel) {
        // 优先找grayLevel最接近的profiles做1D插值
        BrightnessProfile lower = null, upper = null;
        for (BrightnessProfile p : profiles) {
            if (p.brightness <= brightness) {
                if (lower == null || (Math.abs(p.grayLevel - grayLevel) <= Math.abs(lower.grayLevel - grayLevel) && p.brightness >= lower.brightness)) {
                    lower = p;
                }
            }
            if (p.brightness >= brightness) {
                if (upper == null || (Math.abs(p.grayLevel - grayLevel) <= Math.abs(upper.grayLevel - grayLevel) && p.brightness <= upper.brightness)) {
                    upper = p;
                }
            }
        }

        if (lower == null && upper == null) {
            BrightnessProfile fallback = profiles.get(0);
            return new int[]{fallback.leftColor, fallback.rightColor, fallback.leftOpacity, fallback.rightOpacity};
        }
        if (lower == null) return new int[]{upper.leftColor, upper.rightColor, upper.leftOpacity, upper.rightOpacity};
        if (upper == null) return new int[]{lower.leftColor, lower.rightColor, lower.leftOpacity, lower.rightOpacity};
        if (lower.brightness == upper.brightness) return new int[]{lower.leftColor, lower.rightColor, lower.leftOpacity, lower.rightOpacity};

        float ratio = (float)(brightness - lower.brightness) / (upper.brightness - lower.brightness);
        int leftColor = interpolateColor(lower.leftColor, upper.leftColor, ratio);
        int rightColor = interpolateColor(lower.rightColor, upper.rightColor, ratio);
        int leftOpacity = (int) Math.round(lower.leftOpacity + ratio * (upper.leftOpacity - lower.leftOpacity));
        int rightOpacity = (int) Math.round(lower.rightOpacity + ratio * (upper.rightOpacity - lower.rightOpacity));
        return new int[]{leftColor, rightColor, leftOpacity, rightOpacity};
    }

    /** 1D线性插值（仅灰度维度），在指定brightness附近找点 */
    private int[] linearInterpolate1DGray(List<BrightnessProfile> profiles, int grayLevel, int brightness) {
        BrightnessProfile lower = null, upper = null;
        for (BrightnessProfile p : profiles) {
            if (p.brightness == brightness || Math.abs(p.brightness - brightness) <= 10) {
                if (p.grayLevel <= grayLevel) {
                    if (lower == null || p.grayLevel > lower.grayLevel) lower = p;
                }
                if (p.grayLevel >= grayLevel) {
                    if (upper == null || p.grayLevel < upper.grayLevel) upper = p;
                }
            }
        }

        if (lower == null && upper == null) return linearInterpolate1D(profiles, brightness, grayLevel);
        if (lower == null) return new int[]{upper.leftColor, upper.rightColor, upper.leftOpacity, upper.rightOpacity};
        if (upper == null) return new int[]{lower.leftColor, lower.rightColor, lower.leftOpacity, lower.rightOpacity};
        if (lower.grayLevel == upper.grayLevel) return new int[]{lower.leftColor, lower.rightColor, lower.leftOpacity, lower.rightOpacity};

        float ratio = (float)(grayLevel - lower.grayLevel) / (upper.grayLevel - lower.grayLevel);
        int leftColor = interpolateColor(lower.leftColor, upper.leftColor, ratio);
        int rightColor = interpolateColor(lower.rightColor, upper.rightColor, ratio);
        int leftOpacity = (int) Math.round(lower.leftOpacity + ratio * (upper.leftOpacity - lower.leftOpacity));
        int rightOpacity = (int) Math.round(lower.rightOpacity + ratio * (upper.rightOpacity - lower.rightOpacity));
        return new int[]{leftColor, rightColor, leftOpacity, rightOpacity};
    }

    /** RGB颜色线性插值 */
    private int interpolateColor(int c1, int c2, float ratio) {
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        int r = Math.round(r1 + ratio * (r2 - r1));
        int g = Math.round(g1 + ratio * (g2 - g1));
        int b = Math.round(b1 + ratio * (b2 - b1));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 使用指定颜色和透明度值显示覆盖层
     *  方向逻辑：自动从屏幕尺寸判断横竖 → 用户可通过swap/rotate手动校正
     */
    private void showOverlayWithValues(int leftColor, int rightColor, int leftOpacity, int rightOpacity) {
        removeOverlay();

        // 缓存本次显示参数（用于下次变化检测）
        lastLeftColor = leftColor;
        lastRightColor = rightColor;
        lastLeftOpacity = leftOpacity;
        lastRightOpacity = rightOpacity;

        boolean swap = config.isOverlaySwap();
        int rotate = config.getOverlayRotate(); // 0=自动, 90/180/270=手动

        // 自动判断横竖屏：sw >= sh → 横屏
        Display display = windowManager.getDefaultDisplay();
        android.graphics.Point realSize = new android.graphics.Point();
        display.getRealSize(realSize);
        boolean autoLandscape = realSize.x >= realSize.y;
        lastScreenWidth = realSize.x;
        lastScreenHeight = realSize.y;

        // 应用旋转偏移：90/270翻转横竖判断，180保持但翻转布局
        boolean effectiveLandscape;
        if (rotate == 0) {
            effectiveLandscape = autoLandscape;
        } else if (rotate == 90 || rotate == 270) {
            effectiveLandscape = !autoLandscape; // 翻转横竖
        } else {
            effectiveLandscape = autoLandscape; // 180°同轴
        }

        // 180°时翻转上下/左右
        boolean flip180 = (rotate == 180);

        // 确定颜色分配：swap互换左/右profile
        int firstColor = swap ? rightColor : leftColor;   // 第一区域(左/上)颜色
        int secondColor = swap ? leftColor : rightColor;   // 第二区域(右/下)颜色
        int firstOpacity = swap ? rightOpacity : leftOpacity;
        int secondOpacity = swap ? leftOpacity : rightOpacity;

        // 180°翻转时再互换颜色
        if (flip180) {
            int tc = firstColor; firstColor = secondColor; secondColor = tc;
            int to = firstOpacity; firstOpacity = secondOpacity; secondOpacity = to;
        }

        int firstWithAlpha = applyAlpha(firstColor, firstOpacity);
        int secondWithAlpha = applyAlpha(secondColor, secondOpacity);

        int screenWidth = realSize.x;
        int screenHeight = realSize.y;

        // 分割位置：config值(0-2000, 0.05%/单位) → 像素
        // 横屏：splitPos是宽度方向的分割像素
        // 竖屏：splitPos是高度方向的分割像素
        int splitPx;
        int overlapPx = 2; // 固定2px重叠消除缝隙
        if (effectiveLandscape) {
            splitPx = Math.round(config.getSplitPosition() * screenWidth / 2000f);
        } else {
            splitPx = Math.round(config.getSplitPosition() * screenHeight / 2000f);
        }

        int overlayType = getOverlayType();
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        String mode = config.getMode();

        if (mode.equals("custom")) {
            // 自定义区域模式
            int leftStart = config.getCustomLeftStart() * screenWidth / 100;
            int leftEnd = config.getCustomLeftEnd() * screenWidth / 100;
            int rightStart = config.getCustomRightStart() * screenWidth / 100;
            int rightEnd = config.getCustomRightEnd() * screenWidth / 100;
            int top = config.getCustomTop() * screenHeight / 100;
            int bottom = config.getCustomBottom() * screenHeight / 100;

            overlayLeftView = new View(this);
            overlayLeftView.setBackgroundColor(firstWithAlpha);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    leftEnd - leftStart + overlapPx, bottom - top, overlayType, flags, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = leftStart; lp.y = top;
            windowManager.addView(overlayLeftView, lp);

            overlayRightView = new View(this);
            overlayRightView.setBackgroundColor(secondWithAlpha);
            WindowManager.LayoutParams rp = new WindowManager.LayoutParams(
                    rightEnd - rightStart + overlapPx, bottom - top, overlayType, flags, PixelFormat.TRANSLUCENT);
            rp.gravity = Gravity.TOP | Gravity.LEFT;
            rp.x = rightStart - overlapPx; rp.y = top;
            windowManager.addView(overlayRightView, rp);
        } else if (effectiveLandscape) {
            // 横屏：左半+右半，分割位置可调
            overlayLeftView = addOverlayView(firstWithAlpha, 0, 0, splitPx + overlapPx, screenHeight, overlayType, flags);
            overlayRightView = addOverlayView(secondWithAlpha, splitPx - overlapPx, 0, screenWidth - splitPx + overlapPx, screenHeight, overlayType, flags);
        } else {
            // 竖屏：上半+下半，分割位置可调
            overlayLeftView = addOverlayView(firstWithAlpha, 0, 0, screenWidth, splitPx + overlapPx, overlayType, flags);
            overlayRightView = addOverlayView(secondWithAlpha, 0, splitPx - overlapPx, screenWidth, screenHeight - splitPx + overlapPx, overlayType, flags);
        }
    }

    /** 添加一个覆盖层视图 */
    private View addOverlayView(int color, int x, int y, int w, int h, int overlayType, int flags) {
        View view = new View(this);
        view.setBackgroundColor(color);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, overlayType, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x; lp.y = y;
        windowManager.addView(view, lp);
        return view;
    }

    private void updateOverlayForScreenSize() {
        Display display = windowManager.getDefaultDisplay();
        android.graphics.Point realSize = new android.graphics.Point();
        display.getRealSize(realSize);
        foldDetector.detectByScreenSize(realSize.x);
        updateOverlayForBrightness();
    }

    private void hideOverlay() {
        removeOverlay();
        invalidateCache();
    }

    private void removeOverlay() {
        if (overlayLeftView != null) {
            try { windowManager.removeView(overlayLeftView); } catch (Exception ignored) {}
            overlayLeftView = null;
        }
        if (overlayRightView != null) {
            try { windowManager.removeView(overlayRightView); } catch (Exception ignored) {}
            overlayRightView = null;
        }
    }

    /** 清除缓存参数，使下次 updateOverlayForBrightness 必定刷新 */
    private void invalidateCache() {
        lastLeftColor = -1;
        lastRightColor = -1;
        lastLeftOpacity = -1;
        lastRightOpacity = -1;
        lastScreenWidth = -1;
        lastScreenHeight = -1;
    }

    /**
     * 将颜色应用指定透明度百分比
     * opacity: 0-100
     */
    private int applyAlpha(int color, int opacity) {
        int alpha = Math.round(opacity * 2.55f);
        alpha = Math.max(0, Math.min(255, alpha));
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }
}
