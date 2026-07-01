package com.vlab.scrbalance;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

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
        initOpacity();
        initMode();
        initAutoFold();
        initColor(true);
        initColor(false);
        initCustomArea();
        findViewById(R.id.resetBtn).setOnClickListener(v -> { config.resetDefaults(); notifyOverlayUpdate(); recreate(); });
    }

    private void initOpacity() {
        SeekBar sb = findViewById(R.id.opacitySeekBar);
        TextView tv = findViewById(R.id.opacityValue);
        sb.setMax(100); sb.setProgress(config.getOpacity());
        tv.setText(config.getOpacity() + "%");
        sb.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                tv.setText(p+"%");
                config.setOpacity(p); notifyOverlayUpdate();
            }
        });
        // +/- 每次调整1%
        bindButtons(R.id.opacityMinus, R.id.opacityPlus, R.id.opacitySeekBar, R.id.opacityValue, 1, 100);
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
