package com.vlab.scrbalance;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 悬浮窗覆盖服务 - 在屏幕上方显示半透明校正层
 */
public class OverlayService extends Service {

    private static final String CHANNEL_ID = "scr_balance_channel";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private View overlayLeftView;
    private View overlayRightView;
    private AppConfig config;
    private FoldDetector foldDetector;

    @Override
    public void onCreate() {
        super.onCreate();
        config = new AppConfig(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        foldDetector = new FoldDetector(this);
        foldDetector.setCallback(isUnfolded -> {
            if (isUnfolded) {
                showOverlay();
            } else {
                hideOverlay();
            }
        });
        foldDetector.startListening();

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        updateOverlayForScreenSize();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "ACTION_UPDATE":
                    updateOverlay();
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

    private void showOverlay() {
        if (!config.isEnabled()) return;
        removeOverlay();

        int opacity = config.getOpacity();
        String mode = config.getMode();
        int leftWithAlpha = applyAlpha(config.getLeftColor(), opacity);
        int rightWithAlpha = applyAlpha(config.getRightColor(), opacity);

        WindowManager.LayoutParams baseParams = new WindowManager.LayoutParams(
                0, 0,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        baseParams.gravity = Gravity.TOP | Gravity.LEFT;

        Display display = windowManager.getDefaultDisplay();
        android.graphics.Point realSize = new android.graphics.Point();
        display.getRealSize(realSize);
        int screenWidth = realSize.x;
        int screenHeight = realSize.y;

        if (mode.equals("custom")) {
            int leftStart = config.getCustomLeftStart() * screenWidth / 100;
            int leftEnd = config.getCustomLeftEnd() * screenWidth / 100;
            int rightStart = config.getCustomRightStart() * screenWidth / 100;
            int rightEnd = config.getCustomRightEnd() * screenWidth / 100;
            int top = config.getCustomTop() * screenHeight / 100;
            int bottom = config.getCustomBottom() * screenHeight / 100;

            overlayLeftView = new View(this);
            overlayLeftView.setBackgroundColor(leftWithAlpha);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(baseParams);
            lp.x = leftStart; lp.y = top;
            lp.width = leftEnd - leftStart; lp.height = bottom - top;
            windowManager.addView(overlayLeftView, lp);

            overlayRightView = new View(this);
            overlayRightView.setBackgroundColor(rightWithAlpha);
            WindowManager.LayoutParams rp = new WindowManager.LayoutParams(baseParams);
            rp.x = rightStart; rp.y = top;
            rp.width = rightEnd - rightStart; rp.height = bottom - top;
            windowManager.addView(overlayRightView, rp);
        } else {
            int halfWidth = screenWidth / 2;

            overlayLeftView = new View(this);
            overlayLeftView.setBackgroundColor(leftWithAlpha);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(baseParams);
            lp.x = 0; lp.y = 0; lp.width = halfWidth; lp.height = screenHeight;
            windowManager.addView(overlayLeftView, lp);

            overlayRightView = new View(this);
            overlayRightView.setBackgroundColor(rightWithAlpha);
            WindowManager.LayoutParams rp = new WindowManager.LayoutParams(baseParams);
            rp.x = halfWidth; rp.y = 0; rp.width = halfWidth; rp.height = screenHeight;
            windowManager.addView(overlayRightView, rp);
        }
    }

    private void updateOverlayForScreenSize() {
        Display display = windowManager.getDefaultDisplay();
        android.graphics.Point realSize = new android.graphics.Point();
        display.getRealSize(realSize);
        foldDetector.detectByScreenSize(realSize.x);
        if (foldDetector.isUnfolded() && config.isEnabled()) {
            showOverlay();
        }
    }

    private void updateOverlay() {
        if (foldDetector.isUnfolded()) {
            showOverlay();
        }
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
        int alpha = Math.round(opacity * 2.55f); // 0-100 -> 0-255
        alpha = Math.max(0, Math.min(255, alpha));
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }
}
