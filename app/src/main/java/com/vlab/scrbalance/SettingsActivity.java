package com.vlab.scrbalance;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private AppConfig config;

    /** 通知OverlayService实时刷新覆盖层 */
    private void notifyOverlayUpdate() {
        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction("ACTION_UPDATE");
        startService(intent);
    }

    /** 绑定 +/- 按钮到 SeekBar */
    private void bindButtons(int minusId, int plusId, int barId, int valueId, int step, int max) {
        SeekBar bar = findViewById(barId);
        TextView tv = findViewById(valueId);
        Button minusBtn = findViewById(minusId);
        Button plusBtn = findViewById(plusId);

        minusBtn.setOnClickListener(v -> {
            int p = Math.max(0, bar.getProgress() - step);
            bar.setProgress(p);
        });
        plusBtn.setOnClickListener(v -> {
            int p = Math.min(max, bar.getProgress() + step);
            bar.setProgress(p);
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());
        config = new AppConfig(this);
        initAll();
    }

    private void initAll() {
        initBrightnessProfiles();
        initOpacity();
        initMode();
        initAutoFold();
        initColor(true);
        initColor(false);
        initCustomArea();
        findViewById(R.id.resetBtn).setOnClickListener(v -> { config.resetDefaults(); notifyOverlayUpdate(); recreate(); });
    }

    /** 获取当前屏幕亮度百分比 */
    private int getCurrentBrightnessPercent() {
        try {
            int brightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            return (int) Math.round(brightness * 100.0 / 255.0);
        } catch (Exception e) {
            return 50;
        }
    }

    /** 获取当前屏幕亮度值(0-255) */
    private int getCurrentBrightnessValue() {
        try {
            return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Exception e) {
            return 128;
        }
    }

    private void initBrightnessProfiles() {
        // 显示当前亮度
        TextView currentBrightness = findViewById(R.id.currentBrightness);
        int brightnessPercent = getCurrentBrightnessPercent();
        currentBrightness.setText(getString(R.string.current_brightness, brightnessPercent));

        // "添加亮度配置"按钮
        findViewById(R.id.addProfileBtn).setOnClickListener(v -> showAddProfileDialog());

        // 刷新配置列表
        refreshProfileList();
    }

    /** 显示添加亮度配置对话框 - 用SeekBar选择亮度百分比 */
    private void showAddProfileDialog() {
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(40, 20, 40, 20);

        TextView label = new TextView(this);
        label.setText("选择亮度百分比(当前: " + getCurrentBrightnessPercent() + "%)");
        label.setTextSize(14);
        label.setTextColor(0xFF333333);
        dialogLayout.addView(label);

        SeekBar brightnessBar = new SeekBar(this);
        brightnessBar.setMax(100);
        brightnessBar.setProgress(getCurrentBrightnessPercent());
        dialogLayout.addView(brightnessBar);

        TextView brightnessValue = new TextView(this);
        brightnessValue.setText(getCurrentBrightnessPercent() + "%");
        brightnessValue.setTextSize(16);
        brightnessValue.setGravity(Gravity.CENTER);
        brightnessValue.setTextColor(0xFF333333);
        dialogLayout.addView(brightnessValue);

        brightnessBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                brightnessValue.setText(p + "%");
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.add_brightness_profile))
                .setView(dialogLayout)
                .setPositiveButton("保存", (d, w) -> {
                    int selectedPercent = brightnessBar.getProgress();
                    int brightnessValue255 = (int) Math.round(selectedPercent * 255.0 / 100.0);
                    // 保存当前设置值作为该亮度下的配置
                    BrightnessProfile profile = new BrightnessProfile(
                            brightnessValue255,
                            config.getLeftColor(),
                            config.getRightColor(),
                            config.getLeftOpacity(),
                            config.getRightOpacity()
                    );
                    config.addOrUpdateBrightnessProfile(profile);
                    refreshProfileList();
                    notifyOverlayUpdate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 刷新亮度配置列表 */
    private void refreshProfileList() {
        LinearLayout profileList = findViewById(R.id.profileList);
        profileList.removeAllViews();

        List<BrightnessProfile> profiles = config.getBrightnessProfiles();
        for (BrightnessProfile profile : profiles) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 4, 0, 4);

            // 亮度百分比
            TextView brightnessText = new TextView(this);
            brightnessText.setText(profile.brightnessPercent() + "%");
            brightnessText.setTextSize(14);
            brightnessText.setTextColor(0xFF333333);
            brightnessText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f));
            row.addView(brightnessText);

            // 左色预览
            View leftPreview = new View(this);
            leftPreview.setBackgroundColor(applyAlphaPreview(profile.leftColor, profile.leftOpacity));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(30, 30);
            lp.setMargins(4, 0, 4, 0);
            leftPreview.setLayoutParams(lp);
            row.addView(leftPreview);

            // 右色预览
            View rightPreview = new View(this);
            rightPreview.setBackgroundColor(applyAlphaPreview(profile.rightColor, profile.rightOpacity));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(30, 30);
            rp.setMargins(4, 0, 8, 0);
            rightPreview.setLayoutParams(rp);
            row.addView(rightPreview);

            // 透明度信息
            TextView opacityText = new TextView(this);
            opacityText.setText("L" + profile.leftOpacity + "% R" + profile.rightOpacity + "%");
            opacityText.setTextSize(12);
            opacityText.setTextColor(0xFF666666);
            opacityText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
            row.addView(opacityText);

            // 加载按钮（将配置值加载到设置界面）
            Button loadBtn = new Button(this);
            loadBtn.setText("加载");
            loadBtn.setTextSize(12);
            loadBtn.setBackgroundColor(0xFFE0E0E0);
            loadBtn.setTextColor(0xFF333333);
            LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 32);
            loadBtn.setLayoutParams(loadLp);
            loadBtn.setOnClickListener(v -> {
                config.setLeftColor(profile.leftColor);
                config.setRightColor(profile.rightColor);
                config.setLeftOpacity(profile.leftOpacity);
                config.setRightOpacity(profile.rightOpacity);
                notifyOverlayUpdate();
                recreate(); // 重新加载界面以更新控件值
            });
            row.addView(loadBtn);

            // 删除按钮
            Button deleteBtn = new Button(this);
            deleteBtn.setText("删除");
            deleteBtn.setTextSize(12);
            deleteBtn.setBackgroundColor(0xFFFFCDD2);
            deleteBtn.setTextColor(0xFF333333);
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 32);
            delLp.setMargins(4, 0, 0, 0);
            deleteBtn.setLayoutParams(delLp);
            deleteBtn.setOnClickListener(v -> {
                config.removeBrightnessProfile(profile.brightness);
                refreshProfileList();
                notifyOverlayUpdate();
            });
            row.addView(deleteBtn);

            profileList.addView(row);
        }

        // 更新提示文字
        TextView hint = findViewById(R.id.brightnessHint);
        if (profiles.isEmpty()) {
            hint.setText(getString(R.string.brightness_profile_hint));
        } else {
            hint.setText(getString(R.string.brightness_profile_active, profiles.size()));
        }
    }

    /** 预览用的颜色（含透明度） */
    private int applyAlphaPreview(int color, int opacity) {
        int alpha = Math.round(opacity * 2.55f);
        alpha = Math.max(0, Math.min(255, alpha));
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 更新当前亮度显示
        TextView currentBrightness = findViewById(R.id.currentBrightness);
        int brightnessPercent = getCurrentBrightnessPercent();
        currentBrightness.setText(getString(R.string.current_brightness, brightnessPercent));
        refreshProfileList();
    }

    private void initOpacity() {
        // 左屏透明度
        SeekBar lsb = findViewById(R.id.leftOpacitySeekBar);
        TextView ltv = findViewById(R.id.leftOpacityValue);
        lsb.setMax(100); lsb.setProgress(config.getLeftOpacity());
        ltv.setText(config.getLeftOpacity() + "%");
        lsb.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                ltv.setText(p+"%");
                config.setLeftOpacity(p); notifyOverlayUpdate();
            }
        });
        bindButtons(R.id.leftOpacityMinus, R.id.leftOpacityPlus, R.id.leftOpacitySeekBar, R.id.leftOpacityValue, 1, 100);

        // 右屏透明度
        SeekBar rsb = findViewById(R.id.rightOpacitySeekBar);
        TextView rtv = findViewById(R.id.rightOpacityValue);
        rsb.setMax(100); rsb.setProgress(config.getRightOpacity());
        rtv.setText(config.getRightOpacity() + "%");
        rsb.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                rtv.setText(p+"%");
                config.setRightOpacity(p); notifyOverlayUpdate();
            }
        });
        bindButtons(R.id.rightOpacityMinus, R.id.rightOpacityPlus, R.id.rightOpacitySeekBar, R.id.rightOpacityValue, 1, 100);
    }

    private void initMode() {
        RadioGroup rg = findViewById(R.id.modeRadioGroup);
        boolean isCustom = config.getMode().equals("custom");
        ((RadioButton)findViewById(isCustom ? R.id.customAreaRadio : R.id.halfScreenRadio)).setChecked(true);
        findViewById(R.id.customAreaSection).setVisibility(isCustom ? View.VISIBLE : View.GONE);
        rg.setOnCheckedChangeListener((g, id) -> {
            boolean c = id == R.id.customAreaRadio;
            config.setMode(c ? "custom" : "half");
            findViewById(R.id.customAreaSection).setVisibility(c ? View.VISIBLE : View.GONE);
            notifyOverlayUpdate();
        });
    }

    private void initAutoFold() {
        CheckBox cb = findViewById(R.id.autoFoldCheckbox);
        cb.setChecked(config.isAutoFoldDetect());
        cb.setOnCheckedChangeListener((b, v) -> { config.setAutoFoldDetect(v); notifyOverlayUpdate(); });
    }

    private void initColor(boolean isLeft) {
        int color = isLeft ? config.getLeftColor() : config.getRightColor();
        int rId = isLeft ? R.id.leftRedSeekBar : R.id.rightRedSeekBar;
        int gId = isLeft ? R.id.leftGreenSeekBar : R.id.rightGreenSeekBar;
        int bId = isLeft ? R.id.leftBlueSeekBar : R.id.rightBlueSeekBar;
        int pId = isLeft ? R.id.leftColorPreview : R.id.rightColorPreview;
        int rValId = isLeft ? R.id.leftRedValue : R.id.rightRedValue;
        int gValId = isLeft ? R.id.leftGreenValue : R.id.rightGreenValue;
        int bValId = isLeft ? R.id.leftBlueValue : R.id.rightBlueValue;
        int rMinusId = isLeft ? R.id.leftRedMinus : R.id.rightRedMinus;
        int rPlusId = isLeft ? R.id.leftRedPlus : R.id.rightRedPlus;
        int gMinusId = isLeft ? R.id.leftGreenMinus : R.id.rightGreenMinus;
        int gPlusId = isLeft ? R.id.leftGreenPlus : R.id.rightGreenPlus;
        int bMinusId = isLeft ? R.id.leftBlueMinus : R.id.rightBlueMinus;
        int bPlusId = isLeft ? R.id.leftBluePlus : R.id.rightBluePlus;

        SeekBar rB = findViewById(rId), gB = findViewById(gId), bB = findViewById(bId);
        TextView prev = findViewById(pId);
        TextView rTv = findViewById(rValId), gTv = findViewById(gValId), bTv = findViewById(bValId);

        int r = (color>>16)&0xFF, g = (color>>8)&0xFF, b = color&0xFF;
        rB.setMax(255); rB.setProgress(r); rTv.setText(r+"");
        gB.setMax(255); gB.setProgress(g); gTv.setText(g+"");
        bB.setMax(255); bB.setProgress(b); bTv.setText(b+"");
        prev.setBackgroundColor(color); prev.setText(fmt(color));

        SeekBar.OnSeekBarChangeListener l = new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                int rv = rB.getProgress(), gv = gB.getProgress(), bv = bB.getProgress();
                int c = Color.rgb(rv, gv, bv);
                rTv.setText(rv+""); gTv.setText(gv+""); bTv.setText(bv+"");
                prev.setBackgroundColor(c); prev.setText(fmt(c));
                if(isLeft) config.setLeftColor(0xFF000000|c); else config.setRightColor(0xFF000000|c);
                notifyOverlayUpdate();
            }
        };
        rB.setOnSeekBarChangeListener(l); gB.setOnSeekBarChangeListener(l); bB.setOnSeekBarChangeListener(l);

        // +/- 每次调整5
        bindButtons(rMinusId, rPlusId, rId, rValId, 5, 255);
        bindButtons(gMinusId, gPlusId, gId, gValId, 5, 255);
        bindButtons(bMinusId, bPlusId, bId, bValId, 5, 255);
    }

    private String fmt(int c) { return String.format("RGB(%d,%d,%d)", (c>>16)&0xFF, (c>>8)&0xFF, c&0xFF); }

    private void initCustomArea() {
        bindSeekBar(R.id.customLeftStartBar, R.id.customLeftStartValue, config.getCustomLeftStart(), v->config.setCustomLeftStart(v));
        bindSeekBar(R.id.customLeftEndBar, R.id.customLeftEndValue, config.getCustomLeftEnd(), v->config.setCustomLeftEnd(v));
        bindSeekBar(R.id.customRightStartBar, R.id.customRightStartValue, config.getCustomRightStart(), v->config.setCustomRightStart(v));
        bindSeekBar(R.id.customRightEndBar, R.id.customRightEndValue, config.getCustomRightEnd(), v->config.setCustomRightEnd(v));
        bindSeekBar(R.id.customTopBar, R.id.customTopValue, config.getCustomTop(), v->config.setCustomTop(v));
        bindSeekBar(R.id.customBottomBar, R.id.customBottomValue, config.getCustomBottom(), v->config.setCustomBottom(v));

        // +/- 每次调整1%
        bindButtons(R.id.customLeftStartMinus, R.id.customLeftStartPlus, R.id.customLeftStartBar, R.id.customLeftStartValue, 1, 100);
        bindButtons(R.id.customLeftEndMinus, R.id.customLeftEndPlus, R.id.customLeftEndBar, R.id.customLeftEndValue, 1, 100);
        bindButtons(R.id.customRightStartMinus, R.id.customRightStartPlus, R.id.customRightStartBar, R.id.customRightStartValue, 1, 100);
        bindButtons(R.id.customRightEndMinus, R.id.customRightEndPlus, R.id.customRightEndBar, R.id.customRightEndValue, 1, 100);
        bindButtons(R.id.customTopMinus, R.id.customTopPlus, R.id.customTopBar, R.id.customTopValue, 1, 100);
        bindButtons(R.id.customBottomMinus, R.id.customBottomPlus, R.id.customBottomBar, R.id.customBottomValue, 1, 100);
    }

    private void bindSeekBar(int barId, int tvId, int init, java.util.function.IntConsumer setter) {
        SeekBar bar = findViewById(barId);
        TextView tv = findViewById(tvId);
        bar.setMax(100); bar.setProgress(init); tv.setText(init+"%");
        bar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                tv.setText(p+"%");
                setter.accept(p); notifyOverlayUpdate();
            }
        });
    }
}
