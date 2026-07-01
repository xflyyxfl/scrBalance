package com.vlab.scrbalance;

import android.graphics.Color;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class SettingsActivity extends AppCompatActivity {

    private AppConfig config;

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
        findViewById(R.id.resetBtn).setOnClickListener(v -> { config.resetDefaults(); recreate(); });
    }

    private void initOpacity() {
        SeekBar sb = findViewById(R.id.opacitySeekBar);
        TextView tv = findViewById(R.id.opacityValue);
        sb.setMax(100); sb.setProgress(config.getOpacity());
        tv.setText(config.getOpacity() + "%");
        sb.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { tv.setText(p+"%"); if(f) config.setOpacity(p); }
        });
    }

    private void initMode() {
        RadioGroup rg = findViewById(R.id.modeRadioGroup);
        boolean isCustom = config.getMode().equals("custom");
        ((RadioButton)findViewById(isCustom ? R.id.customAreaRadio : R.id.halfScreenRadio)).setChecked(true);
        findViewById(R.id.customAreaSection).setVisibility(isCustom ? android.view.View.VISIBLE : android.view.View.GONE);
        rg.setOnCheckedChangeListener((g, id) -> {
            boolean c = id == R.id.customAreaRadio;
            config.setMode(c ? "custom" : "half");
            findViewById(R.id.customAreaSection).setVisibility(c ? android.view.View.VISIBLE : android.view.View.GONE);
        });
    }

    private void initAutoFold() {
        CheckBox cb = findViewById(R.id.autoFoldCheckbox);
        cb.setChecked(config.isAutoFoldDetect());
        cb.setOnCheckedChangeListener((b, v) -> config.setAutoFoldDetect(v));
    }

    private void initColor(boolean isLeft) {
        int color = isLeft ? config.getLeftColor() : config.getRightColor();
        int rId = isLeft ? R.id.leftRedSeekBar : R.id.rightRedSeekBar;
        int gId = isLeft ? R.id.leftGreenSeekBar : R.id.rightGreenSeekBar;
        int bId = isLeft ? R.id.leftBlueSeekBar : R.id.rightBlueSeekBar;
        int pId = isLeft ? R.id.leftColorPreview : R.id.rightColorPreview;

        SeekBar rB = findViewById(rId), gB = findViewById(gId), bB = findViewById(bId);
        TextView prev = findViewById(pId);

        rB.setMax(255); rB.setProgress((color>>16)&0xFF);
        gB.setMax(255); gB.setProgress((color>>8)&0xFF);
        bB.setMax(255); bB.setProgress(color&0xFF);
        prev.setBackgroundColor(color);
        prev.setText(fmt(color));

        SeekBar.OnSeekBarChangeListener l = new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                int c = Color.rgb(rB.getProgress(), gB.getProgress(), bB.getProgress());
                prev.setBackgroundColor(c); prev.setText(fmt(c));
                if(f) { if(isLeft) config.setLeftColor(0xFF000000|c); else config.setRightColor(0xFF000000|c); }
            }
        };
        rB.setOnSeekBarChangeListener(l); gB.setOnSeekBarChangeListener(l); bB.setOnSeekBarChangeListener(l);
    }

    private String fmt(int c) { return String.format("RGB(%d,%d,%d)", (c>>16)&0xFF, (c>>8)&0xFF, c&0xFF); }

    private void initCustomArea() {
        bind(R.id.customLeftStartBar, R.id.customLeftStartValue, config.getCustomLeftStart(), v->config.setCustomLeftStart(v));
        bind(R.id.customLeftEndBar, R.id.customLeftEndValue, config.getCustomLeftEnd(), v->config.setCustomLeftEnd(v));
        bind(R.id.customRightStartBar, R.id.customRightStartValue, config.getCustomRightStart(), v->config.setCustomRightStart(v));
        bind(R.id.customRightEndBar, R.id.customRightEndValue, config.getCustomRightEnd(), v->config.setCustomRightEnd(v));
        bind(R.id.customTopBar, R.id.customTopValue, config.getCustomTop(), v->config.setCustomTop(v));
        bind(R.id.customBottomBar, R.id.customBottomValue, config.getCustomBottom(), v->config.setCustomBottom(v));
    }

    private void bind(int barId, int tvId, int init, java.util.function.IntConsumer setter) {
        SeekBar bar = findViewById(barId);
        TextView tv = findViewById(tvId);
        bar.setMax(100); bar.setProgress(init); tv.setText(init+"%");
        bar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { tv.setText(p+"%"); if(f) setter.accept(p); }
        });
    }
}
