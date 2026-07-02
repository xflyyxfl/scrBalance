package com.vlab.scrbalance;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.ViewGroup;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.InputStream;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PICK_IMAGE = 1001;
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
        // 保存基准屏幕尺寸（用于旋转检测的参考点）
        saveRefScreenSize();
        initBgColor();
        initBgImage();
        initGrayLevel();
        initBrightnessProfiles();
        initOpacity();
        initMode();
        initAutoFold();
        initColor(true);
        initColor(false);
        initCustomArea();
        initSplitOverlap();
        initPortraitDirection();
        initLandscapeSwap();
        findViewById(R.id.resetBtn).setOnClickListener(v -> { config.resetDefaults(); notifyOverlayUpdate(); recreate(); });
    }

    /** 保存当前屏幕尺寸作为旋转检测的基准参考 */
    private void saveRefScreenSize() {
        Display display = getWindowManager().getDefaultDisplay();
        android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);
        config.setRefScreenW(size.x);
        config.setRefScreenH(size.y);
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

    /** 初始化底色设置 - 改变设置页面背景模拟目标屏幕底色 */
    private void initBgColor() {
        int bgColor = config.getBgColor();
        SeekBar rB = findViewById(R.id.bgRedSeekBar), gB = findViewById(R.id.bgGreenSeekBar), bB = findViewById(R.id.bgBlueSeekBar);
        TextView prev = findViewById(R.id.bgColorPreview);
        TextView rTv = findViewById(R.id.bgRedValue), gTv = findViewById(R.id.bgGreenValue), bTv = findViewById(R.id.bgBlueValue);

        int r = (bgColor>>16)&0xFF, g = (bgColor>>8)&0xFF, b = bgColor&0xFF;
        rB.setMax(255); rB.setProgress(r); rTv.setText(r+"");
        gB.setMax(255); gB.setProgress(g); gTv.setText(g+"");
        bB.setMax(255); bB.setProgress(b); bTv.setText(b+"");
        prev.setBackgroundColor(bgColor); prev.setText(fmt(bgColor));

        SeekBar.OnSeekBarChangeListener l = new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                int rv = rB.getProgress(), gv = gB.getProgress(), bv = bB.getProgress();
                int c = 0xFF000000 | Color.rgb(rv, gv, bv);
                rTv.setText(rv+""); gTv.setText(gv+""); bTv.setText(bv+"");
                prev.setBackgroundColor(c); prev.setText(fmt(c));
                config.setBgColor(c);
                applyBgColor(c);
                // RGB底色变化时同步更新灰度（仅当没有底色图片时）
                if (config.getBgImageUri() == null) {
                    updateGrayFromBgColor();
                }
            }
        };
        rB.setOnSeekBarChangeListener(l); gB.setOnSeekBarChangeListener(l); bB.setOnSeekBarChangeListener(l);

        bindButtons(R.id.bgRedMinus, R.id.bgRedPlus, R.id.bgRedSeekBar, R.id.bgRedValue, 5, 255);
        bindButtons(R.id.bgGreenMinus, R.id.bgGreenPlus, R.id.bgGreenSeekBar, R.id.bgGreenValue, 5, 255);
        bindButtons(R.id.bgBlueMinus, R.id.bgBluePlus, R.id.bgBlueSeekBar, R.id.bgBlueValue, 5, 255);

        findViewById(R.id.bgResetBtn).setOnClickListener(v -> {
            config.setBgColor(AppConfig.DEFAULT_BG_COLOR);
            rB.setProgress(255); gB.setProgress(255); bB.setProgress(255);
            applyBgColor(AppConfig.DEFAULT_BG_COLOR);
            // 重置为白色时同步更新灰度
            updateGrayFromBgColor();
        });

        applyBgColor(bgColor);
    }

    /** 应用底色到ScrollView背景 */
    private void applyBgColor(int bgColor) {
        ScrollView sv = findViewById(R.id.settingsScrollView);
        sv.setBackgroundColor(bgColor);
        // 根据底色亮度自动调整文字颜色，保证可读性
        boolean isDarkBg = luminance(bgColor) < 0.4f;
        int textColor = isDarkBg ? 0xFFCCCCCC : 0xFF333333;
        int hintColor = isDarkBg ? 0xFFAAAAAA : 0xFF888888;
        // 遍历所有TextView设置文字颜色
        LinearLayout root = (LinearLayout) sv.getChildAt(0);
        applyTextColorRecursive(root, textColor, hintColor);
    }

    /** 递归设置所有TextView的文字颜色 */
    private void applyTextColorRecursive(View view, int textColor, int hintColor) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            // 保留预览框的白色文字
            int id = tv.getId();
            if (id == R.id.leftColorPreview || id == R.id.rightColorPreview || id == R.id.bgColorPreview) {
                tv.setTextColor(0xFFFFFFFF);
            } else if (id == R.id.bgColorHint || id == R.id.brightnessHint || id == R.id.currentBrightness) {
                tv.setTextColor(hintColor);
            } else {
                tv.setTextColor(textColor);
            }
        } else if (view instanceof LinearLayout || view instanceof RadioGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTextColorRecursive(group.getChildAt(i), textColor, hintColor);
            }
        }
    }

    /** 计算颜色亮度 */
    private float luminance(int color) {
        int r = (color>>16)&0xFF, g = (color>>8)&0xFF, b = color&0xFF;
        return (0.299f*r + 0.587f*g + 0.114f*b) / 255f;
    }

    /** 初始化底色图片选择器 */
    private void initBgImage() {
        ImageView preview = findViewById(R.id.bgImagePreview);
        String uriStr = config.getBgImageUri();
        if (uriStr != null) {
            try {
                Uri uri = Uri.parse(uriStr);
                InputStream is = getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (is != null) is.close();
                preview.setImageBitmap(bmp);
                // 应用图片为ScrollView背景
                applyBgImage(bmp);
            } catch (Exception e) {
                preview.setImageResource(0);
            }
        }

        findViewById(R.id.bgPickImageBtn).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
        });

        findViewById(R.id.bgRemoveImageBtn).setOnClickListener(v -> {
            config.setBgImageUri(null);
            preview.setImageResource(0);
            // 移除背景图片，恢复RGB底色
            applyBgColor(config.getBgColor());
            // 灰度从RGB底色重新计算
            updateGrayFromBgColor();
            notifyOverlayUpdate();
        });
    }

    /** 处理图片选择结果 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                // 持久化URI权限
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}

                config.setBgImageUri(uri.toString());

                ImageView preview = findViewById(R.id.bgImagePreview);
                try {
                    InputStream is = getContentResolver().openInputStream(uri);
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    if (is != null) is.close();
                    preview.setImageBitmap(bmp);
                    applyBgImage(bmp);
                    // 从图片计算灰度
                    int gray = calcBitmapGrayLevel(bmp);
                    config.setCurrentGrayLevel(gray);
                    updateGrayDisplay();
                    notifyOverlayUpdate();
                } catch (Exception e) {
                    preview.setImageResource(0);
                }
            }
        }
    }

    /** 应用图片为ScrollView背景 */
    private void applyBgImage(Bitmap bmp) {
        ScrollView sv = findViewById(R.id.settingsScrollView);
        Drawable d = new BitmapDrawable(getResources(), bmp);
        sv.setBackground(d);
        // 根据图片亮度自动调整文字颜色
        int gray = calcBitmapGrayLevel(bmp);
        boolean isDarkBg = gray < 40;
        int textColor = isDarkBg ? 0xFFCCCCCC : 0xFF333333;
        int hintColor = isDarkBg ? 0xFFAAAAAA : 0xFF888888;
        LinearLayout root = (LinearLayout) sv.getChildAt(0);
        applyTextColorRecursive(root, textColor, hintColor);
    }

    /** 从Bitmap计算平均灰度（降采样避免内存问题） */
    private int calcBitmapGrayLevel(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        // 降采样：最多取200×200的像素
        int stepX = Math.max(1, w / 200), stepY = Math.max(1, h / 200);
        long sum = 0, count = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int pixel = bmp.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF, g = (pixel >> 8) & 0xFF, b = pixel & 0xFF;
                float lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
                sum += Math.round(lum * 100);
                count++;
            }
        }
        return count > 0 ? (int)(sum / count) : 100;
    }

    /** 灰度等级描述 */
    private String grayLabel(int gray) {
        if (gray >= 90) return "白";
        if (gray >= 70) return "浅灰";
        if (gray >= 50) return "灰";
        if (gray >= 30) return "深灰";
        if (gray >= 10) return "暗灰";
        return "黑";
    }

    /** 初始化灰度等级控件 */
    private void initGrayLevel() {
        int gray = config.getCurrentGrayLevel();
        SeekBar bar = findViewById(R.id.graySeekBar);
        TextView valTv = findViewById(R.id.grayValue);
        TextView display = findViewById(R.id.grayLevelDisplay);

        bar.setProgress(gray);
        valTv.setText(gray + "%");
        display.setText(getString(R.string.gray_level_current, gray, grayLabel(gray)));

        bar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                valTv.setText(p + "%");
                display.setText(getString(R.string.gray_level_current, p, grayLabel(p)));
                config.setCurrentGrayLevel(p);
                notifyOverlayUpdate();
            }
        });

        bindButtons(R.id.grayMinus, R.id.grayPlus, R.id.graySeekBar, R.id.grayValue, 5, 100);
    }

    /** 更新灰度显示（从RGB底色或图片重算后调用） */
    private void updateGrayFromBgColor() {
        int bgColor = config.getBgColor();
        int gray = (int) Math.round(luminance(bgColor) * 100);
        config.setCurrentGrayLevel(gray);
        updateGrayDisplay();
    }

    /** 同步灰度SeekBar和显示 */
    private void updateGrayDisplay() {
        int gray = config.getCurrentGrayLevel();
        SeekBar bar = findViewById(R.id.graySeekBar);
        TextView valTv = findViewById(R.id.grayValue);
        TextView display = findViewById(R.id.grayLevelDisplay);
        bar.setProgress(gray);
        valTv.setText(gray + "%");
        display.setText(getString(R.string.gray_level_current, gray, grayLabel(gray)));
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

    /** 显示添加亮度配置对话框 - 用SeekBar选择亮度百分比和灰度 */
    private void showAddProfileDialog() {
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(40, 20, 40, 20);

        int currentGray = config.getCurrentGrayLevel();

        TextView brightnessLabel = new TextView(this);
        brightnessLabel.setText("亮度百分比(当前: " + getCurrentBrightnessPercent() + "%)");
        brightnessLabel.setTextSize(14);
        brightnessLabel.setTextColor(0xFF333333);
        dialogLayout.addView(brightnessLabel);

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

        TextView grayLabel = new TextView(this);
        grayLabel.setText("灰度等级(当前: " + currentGray + "% " + grayLabel(currentGray) + ")");
        grayLabel.setTextSize(14);
        grayLabel.setTextColor(0xFF333333);
        dialogLayout.addView(grayLabel);

        SeekBar grayBar = new SeekBar(this);
        grayBar.setMax(100);
        grayBar.setProgress(currentGray);
        dialogLayout.addView(grayBar);

        TextView grayValue = new TextView(this);
        grayValue.setText(currentGray + "% " + grayLabel(currentGray));
        grayValue.setTextSize(16);
        grayValue.setGravity(Gravity.CENTER);
        grayValue.setTextColor(0xFF333333);
        dialogLayout.addView(grayValue);

        grayBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                grayValue.setText(p + "% " + grayLabel(p));
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.add_brightness_profile))
                .setView(dialogLayout)
                .setPositiveButton("保存", (d, w) -> {
                    int selectedBrightnessPercent = brightnessBar.getProgress();
                    int selectedGray = grayBar.getProgress();
                    int brightness255 = (int) Math.round(selectedBrightnessPercent * 255.0 / 100.0);
                    // 保存当前设置值作为该亮度+灰度下的配置
                    BrightnessProfile profile = new BrightnessProfile(
                            brightness255,
                            selectedGray,
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

            // 亮度+灰度
            TextView brightnessText = new TextView(this);
            brightnessText.setText(profile.brightnessPercent() + "% · " + profile.grayLevel + "%(" + profile.grayLevelLabel() + ")");
            brightnessText.setTextSize(13);
            brightnessText.setTextColor(0xFF333333);
            brightnessText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
            row.addView(brightnessText);

            // 左色预览
            View leftPreview = new View(this);
            leftPreview.setBackgroundColor(applyAlphaPreview(profile.leftColor, profile.leftOpacity));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(24, 24);
            lp.setMargins(2, 0, 2, 0);
            leftPreview.setLayoutParams(lp);
            row.addView(leftPreview);

            // 右色预览
            View rightPreview = new View(this);
            rightPreview.setBackgroundColor(applyAlphaPreview(profile.rightColor, profile.rightOpacity));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(24, 24);
            rp.setMargins(2, 0, 4, 0);
            rightPreview.setLayoutParams(rp);
            row.addView(rightPreview);

            // 透明度信息
            TextView opacityText = new TextView(this);
            opacityText.setText("L" + profile.leftOpacity + "% R" + profile.rightOpacity + "%");
            opacityText.setTextSize(11);
            opacityText.setTextColor(0xFF666666);
            opacityText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f));
            row.addView(opacityText);

            // 加载按钮（将配置值加载到设置界面）
            Button loadBtn = new Button(this);
            loadBtn.setText("加载");
            loadBtn.setTextSize(11);
            loadBtn.setBackgroundColor(0xFFE0E0E0);
            loadBtn.setTextColor(0xFF333333);
            LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 30);
            loadBtn.setLayoutParams(loadLp);
            loadBtn.setOnClickListener(v -> {
                config.setLeftColor(profile.leftColor);
                config.setRightColor(profile.rightColor);
                config.setLeftOpacity(profile.leftOpacity);
                config.setRightOpacity(profile.rightOpacity);
                notifyOverlayUpdate();
                recreate();
            });
            row.addView(loadBtn);

            // 删除按钮
            Button deleteBtn = new Button(this);
            deleteBtn.setText("删除");
            deleteBtn.setTextSize(11);
            deleteBtn.setBackgroundColor(0xFFFFCDD2);
            deleteBtn.setTextColor(0xFF333333);
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 30);
            delLp.setMargins(4, 0, 0, 0);
            deleteBtn.setLayoutParams(delLp);
            deleteBtn.setOnClickListener(v -> {
                config.removeBrightnessProfile(profile.brightness, profile.grayLevel);
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
        config.setSettingsMode(true); // 暂停亮度插值，让用户手动调整生效
        // 更新当前亮度显示
        TextView currentBrightness = findViewById(R.id.currentBrightness);
        int brightnessPercent = getCurrentBrightnessPercent();
        currentBrightness.setText(getString(R.string.current_brightness, brightnessPercent));
        refreshProfileList();
    }

    @Override
    protected void onPause() {
        super.onPause();
        config.setSettingsMode(false); // 恢复亮度插值
        notifyOverlayUpdate();
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

    /** 初始化边界重叠控件 */
    private void initSplitOverlap() {
        int overlap = config.getSplitOverlap();
        SeekBar bar = findViewById(R.id.overlapSeekBar);
        TextView valTv = findViewById(R.id.overlapValue);

        bar.setMax(50); bar.setProgress(overlap);
        valTv.setText(String.format("%.1f%%", overlap / 10f));

        bar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                valTv.setText(String.format("%.1f%%", p / 10f));
                config.setSplitOverlap(p);
                notifyOverlayUpdate();
            }
        });

        bindButtons(R.id.overlapMinus, R.id.overlapPlus, R.id.overlapSeekBar, R.id.overlapValue, 1, 50);
    }

    /** 初始化竖屏旋转方向控件 */
    private void initPortraitDirection() {
        RadioGroup rg = findViewById(R.id.portraitDirectionGroup);
        String dir = config.getPortraitDirection();
        ((RadioButton) findViewById(dir.equals("cw") ? R.id.portraitCwRadio : R.id.portraitCcwRadio)).setChecked(true);
        rg.setOnCheckedChangeListener((g, id) -> {
            config.setPortraitDirection(id == R.id.portraitCwRadio ? "cw" : "ccw");
            notifyOverlayUpdate();
        });
    }

    /** 初始化横屏左右互换控件 */
    private void initLandscapeSwap() {
        RadioGroup rg = findViewById(R.id.landscapeSwapGroup);
        String swap = config.getLandscapeSwap();
        int checkedId = swap.equals("swap") ? R.id.landscapeSwapRadio :
                        swap.equals("normal") ? R.id.landscapeNormalRadio : R.id.landscapeAutoRadio;
        ((RadioButton) findViewById(checkedId)).setChecked(true);
        rg.setOnCheckedChangeListener((g, id) -> {
            String val = id == R.id.landscapeSwapRadio ? "swap" :
                         id == R.id.landscapeNormalRadio ? "normal" : "auto";
            config.setLandscapeSwap(val);
            notifyOverlayUpdate();
        });
    }
}
