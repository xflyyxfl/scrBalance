package com.vlab.scrbalance;

import android.widget.SeekBar;

/**
 * SeekBar监听器的简化实现
 */
public class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
    @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}
    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
}
