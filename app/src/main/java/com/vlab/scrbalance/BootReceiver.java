package com.vlab.scrbalance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启动 - 如果用户之前启用了校正，开机后自动恢复服务
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AppConfig config = new AppConfig(context);
            if (config.isEnabled()) {
                Intent serviceIntent = new Intent(context, OverlayService.class);
                serviceIntent.setAction("ACTION_UPDATE");
                context.startService(serviceIntent);
            }
        }
    }
}
