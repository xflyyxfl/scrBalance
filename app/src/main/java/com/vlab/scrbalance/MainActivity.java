package com.vlab.scrbalance;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 主界面 - 显示当前状态和快捷操作
 */
public class MainActivity extends AppCompatActivity {

    private AppConfig config;
    private TextView statusText;
    private Button toggleBtn;
    private Button settingsBtn;
    private Button permissionBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        config = new AppConfig(this);

        statusText = findViewById(R.id.statusText);
        toggleBtn = findViewById(R.id.toggleBtn);
        settingsBtn = findViewById(R.id.settingsBtn);
        permissionBtn = findViewById(R.id.permissionBtn);

        toggleBtn.setOnClickListener(v -> toggleOverlay());
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        permissionBtn.setOnClickListener(v -> requestOverlayPermission());

        updateUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    private void updateUI() {
        boolean hasPermission = hasOverlayPermission();
        boolean isEnabled = config.isEnabled();

        if (!hasPermission) {
            statusText.setText(R.string.permission_required);
            permissionBtn.setVisibility(android.view.View.VISIBLE);
            toggleBtn.setEnabled(false);
        } else {
            permissionBtn.setVisibility(android.view.View.GONE);
            toggleBtn.setEnabled(true);
            if (isEnabled) {
                statusText.setText(R.string.service_running);
                toggleBtn.setText(R.string.overlay_disabled);
            } else {
                statusText.setText(R.string.service_stopped);
                toggleBtn.setText(R.string.overlay_enabled);
            }
        }
    }

    private void toggleOverlay() {
        boolean newState = !config.isEnabled();
        config.setEnabled(newState);

        Intent intent = new Intent(this, OverlayService.class);
        if (newState) {
            startService(intent.setAction("ACTION_UPDATE"));
        } else {
            intent.setAction("ACTION_STOP");
            startService(intent);
        }
        updateUI();
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permission_required)
                    .setMessage(R.string.permission_desc)
                    .setPositiveButton(R.string.go_to_settings, (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }
}
