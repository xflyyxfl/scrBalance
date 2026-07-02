package com.vlab.scrbalance;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
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
    private Handler brightnessHandler;

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

        // 监听屏幕亮度变化
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

        // 监听屏幕尺寸变化（系统旋转时尺寸会变化，无需依赖getRotation）
        // 通过定期刷新覆盖层来响应旋转（亮度变化/设置更新都会触发）
        // 另加一个定时器每5秒检查屏幕尺寸变化（确保旋转不被遗漏）
        brightnessHandler.postDelayed(new Runnable() {
            @Override public void run() {
                updateOverlayForBrightness();
                brightnessHandler.postDelayed(this, 5000);
            }
        }, 5000);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        updateOverlayForScreenSize();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "ACTION_UPDATE":
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
        brightnessHandler.removeCallbacksAndMessages(null); // 移除定时器
        removeOverlay();
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

    /** 根据亮度+灰度更新覆盖层 - 核心方法 */
    private void updateOverlayForBrightness() {
        if (!config.isEnabled()) {
            removeOverlay();
            return;
        }
        if (!foldDetector.isUnfolded()) {
            removeOverlay();
            return;
        }

        // 设置模式：暂停亮度插值，直接使用手动设置值
        if (config.isSettingsMode()) {
            showOverlayWithValues(config.getLeftColor(), config.getRightColor(),
                    config.getLeftOpacity(), config.getRightOpacity());
            return;
        }

        List<BrightnessProfile> profiles = config.getBrightnessProfiles();
        int currentBrightness = getCurrentBrightness();
        int currentGrayLevel = config.getCurrentGrayLevel();

        if (profiles.isEmpty()) {
            // 无亮度配置，使用直接设置值
            showOverlayWithValues(config.getLeftColor(), config.getRightColor(),
                    config.getLeftOpacity(), config.getRightOpacity());
        } else {
            // 有配置，进行2D双线性插值
            int[] interpolated = bilinearInterpolate(profiles, currentBrightness, currentGrayLevel);
            showOverlayWithValues(interpolated[0], interpolated[1], interpolated[2], interpolated[3]);
        }
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

    /**
     * 旋转检测：基于保存的基准尺寸 + getRotation()辅助信号
     *
     * 原理：
     * 1. 用户进入设置时保存(refW, refH)作为基准朝向
     * 2. 运行时对比当前(curW, curH)与基准：
     *    - 尺寸相同 → 同轴(0°或180°)
     *    - 尺寸交叉 → 交叉轴(90°或270°)
     * 3. getRotation()作为辅助区分具体角度：
     *    - 同轴+rot=0 → 0°, rot=2 → 180°
     *    - 交叉轴+rot=1 → 90°CW, rot=3 → 270°CW
     * 4. 若getRotation()不可靠，使用portraitDirection推导fallback
     *
     * 返回值：相对于基准的旋转角度(0, 90, 180, 270)
     */
    private int detectRotation() {
        int refW = config.getRefScreenW();
        int refH = config.getRefScreenH();

        // 无基准(首次运行) → 保存当前尺寸为基准，返回0°
        if (refW == 0 || refH == 0) {
            Display display = windowManager.getDefaultDisplay();
            android.graphics.Point size = new android.graphics.Point();
            display.getRealSize(size);
            config.setRefScreenW(size.x);
            config.setRefScreenH(size.y);
            return 0;
        }

        Display display = windowManager.getDefaultDisplay();
        android.graphics.Point curSize = new android.graphics.Point();
        display.getRealSize(curSize);
        int curW = curSize.x;
        int curH = curSize.y;

        // 2%容差（导航栏等微小变化）
        int tolerance = Math.max(refW, refH) / 50;
        boolean sameAxis = Math.abs(curW - refW) <= tolerance && Math.abs(curH - refH) <= tolerance;
        boolean swappedAxis = Math.abs(curW - refH) <= tolerance && Math.abs(curH - refW) <= tolerance;

        int rot = display.getRotation(); // 0=ROTATION_0, 1=ROTATION_90, 2=ROTATION_180, 3=ROTATION_270

        if (sameAxis && !swappedAxis) {
            // 同轴：0°或180°
            if (rot == 2) return 180;
            return 0; // getRotation()返回0或不可靠时默认0°
        }

        if (swappedAxis) {
            // 交叉轴：90°或270°
            // getRotation()与尺寸交叉吻合时可信
            if (rot == 1) return 90;
            if (rot == 3) return 270;
            // getRotation()不可靠(返回0/2但尺寸交叉)
            // Fallback：两种portraitDirection的默认都是"正常横屏"（左屏在左）
            // 因为用户通常会原路旋转回到横屏
            // cw → 从横屏CW到竖屏 → 回到横屏是270°CW → 左屏在左(normal)
            // ccw → 从横屏CCW到竖屏 → 回到横屏是90°CW → 左屏在左(normal)
            // 返回一个让leftProfileOnLeft=true的角度：
            // portraitDir=cw时, 270° → leftProfileOnLeft=true
            // portraitDir=ccw时, 90° → leftProfileOnLeft=true
            return config.getPortraitDirection().equals("cw") ? 270 : 90;
        }

        // 尺寸完全不符（显示模式变化？）→ 默认0°
        return 0;
    }

    /**
     * 计算覆盖层布局：决定左/右亮度profile对应哪个覆盖半区
     *
     * 旋转布局推导表（基准竖屏为例）：
     * portraitDir | rotation | 横屏左屏位置 | leftProfileOnLeft
     * cw          | 90°      | 右侧         | false(swap)
     * cw          | 270°     | 左侧         | true(normal)
     * ccw         | 90°      | 左侧         | true(normal)
     * ccw         | 270°     | 右侧         | false(swap)
     *
     * 公式: leftProfileOnLeft = (portraitDir=="ccw") == (rotation==90°)
     *
     * 竖屏: leftProfileOnBottom = portraitDir=="cw", 180°时翻转
     */
    private boolean[] computeOverlayLayout(int rotation) {
        int refW = config.getRefScreenW();
        int refH = config.getRefScreenH();
        boolean baselinePortrait = refH > refW;

        Display display = windowManager.getDefaultDisplay();
        android.graphics.Point curSize = new android.graphics.Point();
        display.getRealSize(curSize);
        boolean currentPortrait = curSize.y > curSize.x;

        String portraitDir = config.getPortraitDirection();
        String landscapeSwap = config.getLandscapeSwap();

        // leftProfileOnLeft: 横屏时，左profile是否对应左半区
        // leftProfileOnBottom: 竖屏时，左profile是否对应下半区
        boolean leftProfileOnLeft = true;
        boolean leftProfileOnBottom = portraitDir.equals("cw");

        if (currentPortrait) {
            // 竖屏模式
            if (rotation == 180) {
                leftProfileOnBottom = !leftProfileOnBottom; // 180°翻转上下
            }
        } else {
            // 横屏模式
            // 自动检测时用公式推导
            if (landscapeSwap.equals("auto")) {
                // leftProfileOnLeft = (portraitDir=="ccw") == (rotation==90)
                leftProfileOnLeft = (portraitDir.equals("ccw")) == (rotation == 90);
                // 180°横屏：左右互换
                if (rotation == 180) leftProfileOnLeft = !leftProfileOnLeft;
            } else {
                // 手动设置：normal=左屏在左, swap=左屏在右
                leftProfileOnLeft = landscapeSwap.equals("normal");
            }
        }

        return new boolean[]{leftProfileOnLeft, leftProfileOnBottom};
    }

    /** 使用指定颜色和透明度值显示覆盖层，基于旋转检测判断布局 */
    private void showOverlayWithValues(int leftColor, int rightColor, int leftOpacity, int rightOpacity) {
        removeOverlay();

        // 根据旋转检测和用户配置决定：左profile对应哪个半区
        int rotation = detectRotation();
        boolean[] layout = computeOverlayLayout(rotation);
        boolean leftProfileOnLeft = layout[0];   // 横屏：左profile是否在左半
        boolean leftProfileOnBottom = layout[1];  // 竖屏：左profile是否在下半

        // 根据布局决定实际颜色分配
        int leftWithAlpha, rightWithAlpha;
        Display display = windowManager.getDefaultDisplay();
        android.graphics.Point realSize = new android.graphics.Point();
        display.getRealSize(realSize);
        int screenWidth = realSize.x;
        int screenHeight = realSize.y;
        boolean isLandscape = screenWidth >= screenHeight;

        if (isLandscape) {
            // 横屏：根据leftProfileOnLeft决定颜色分配
            leftWithAlpha = applyAlpha(leftProfileOnLeft ? leftColor : rightColor, leftProfileOnLeft ? leftOpacity : rightOpacity);
            rightWithAlpha = applyAlpha(leftProfileOnLeft ? rightColor : leftColor, leftProfileOnLeft ? rightOpacity : leftOpacity);
        } else {
            // 竖屏：下半区对应leftProfileOnBottom的颜色
            // overlayLeftView = 下半区（或上半区，取决于leftProfileOnBottom）
            leftWithAlpha = applyAlpha(leftProfileOnBottom ? leftColor : rightColor, leftProfileOnBottom ? leftOpacity : rightOpacity);
            rightWithAlpha = applyAlpha(leftProfileOnBottom ? rightColor : leftColor, leftProfileOnBottom ? rightOpacity : leftOpacity);
        }

        int overlapPx = Math.round(config.getSplitOverlap() * screenWidth / 1000f); // 0.1%单位→像素

        int overlayType = getOverlayType();
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        String mode = config.getMode();

        if (mode.equals("custom")) {
            // 自定义区域模式 - 使用百分比坐标（基于当前显示尺寸）
            int leftStart = config.getCustomLeftStart() * screenWidth / 100;
            int leftEnd = config.getCustomLeftEnd() * screenWidth / 100;
            int rightStart = config.getCustomRightStart() * screenWidth / 100;
            int rightEnd = config.getCustomRightEnd() * screenWidth / 100;
            int top = config.getCustomTop() * screenHeight / 100;
            int bottom = config.getCustomBottom() * screenHeight / 100;

            overlayLeftView = new View(this);
            overlayLeftView.setBackgroundColor(leftWithAlpha);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    leftEnd - leftStart + overlapPx, bottom - top, overlayType, flags, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = leftStart; lp.y = top;
            windowManager.addView(overlayLeftView, lp);

            overlayRightView = new View(this);
            overlayRightView.setBackgroundColor(rightWithAlpha);
            WindowManager.LayoutParams rp = new WindowManager.LayoutParams(
                    rightEnd - rightStart + overlapPx, bottom - top, overlayType, flags, PixelFormat.TRANSLUCENT);
            rp.gravity = Gravity.TOP | Gravity.LEFT;
            rp.x = rightStart - overlapPx; rp.y = top;
            windowManager.addView(overlayRightView, rp);
        } else if (isLandscape) {
            // 横屏：两物理屏并排，边界重叠消除缝隙
            int halfW = screenWidth / 2;
            overlayLeftView = addOverlayView(leftWithAlpha, 0, 0, halfW + overlapPx, screenHeight, overlayType, flags);
            overlayRightView = addOverlayView(rightWithAlpha, halfW - overlapPx, 0, halfW + overlapPx, screenHeight, overlayType, flags);
        } else {
            // 竖屏：两物理屏上下堆叠
            // leftWithAlpha = 下半区颜色（当leftProfileOnBottom=true时为左profile色）
            // rightWithAlpha = 上半区颜色
            int halfH = screenHeight / 2;
            if (leftProfileOnBottom) {
                // 左profile在下：下半=leftWithAlpha, 上半=rightWithAlpha
                overlayLeftView = addOverlayView(leftWithAlpha, 0, halfH - overlapPx, screenWidth, halfH + overlapPx, overlayType, flags);
                overlayRightView = addOverlayView(rightWithAlpha, 0, 0, screenWidth, halfH + overlapPx, overlayType, flags);
            } else {
                // 左profile在上：上半=leftWithAlpha, 下半=rightWithAlpha
                overlayLeftView = addOverlayView(leftWithAlpha, 0, 0, screenWidth, halfH + overlapPx, overlayType, flags);
                overlayRightView = addOverlayView(rightWithAlpha, 0, halfH - overlapPx, screenWidth, halfH + overlapPx, overlayType, flags);
            }
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
