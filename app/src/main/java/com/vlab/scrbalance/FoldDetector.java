package com.vlab.scrbalance;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * 折叠屏状态检测
 * 通过 hinge angle 传感器和屏幕宽度判断设备是否展开
 */
public class FoldDetector {

    private final Context context;
    private final SensorManager sensorManager;
    private Sensor hingeAngleSensor;
    private SensorEventListener hingeListener;
    private OnFoldStateChangeListener callback;

    private boolean isUnfolded = true;

    public interface OnFoldStateChangeListener {
        void onFoldStateChanged(boolean isUnfolded);
    }

    public FoldDetector(Context context) {
        this.context = context;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    public void setCallback(OnFoldStateChangeListener callback) {
        this.callback = callback;
    }

    public boolean hasHingeAngleSensor() {
        if (sensorManager == null) return false;
        Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (sensor != null) return true;
        for (Sensor s : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            String name = s.getName().toLowerCase();
            if (name.contains("hinge") || name.contains("fold") || name.contains("hall")) {
                return true;
            }
        }
        return false;
    }

    public void startListening() {
        hingeAngleSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (hingeAngleSensor == null) {
            for (Sensor s : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
                String name = s.getName().toLowerCase();
                if (name.contains("hinge") || name.contains("fold") || name.contains("hall")) {
                    hingeAngleSensor = s;
                    break;
                }
            }
        }

        if (hingeAngleSensor != null) {
            hingeListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    float angle = event.values[0];
                    boolean newState = angle > 90f;
                    if (newState != isUnfolded) {
                        isUnfolded = newState;
                        if (callback != null) callback.onFoldStateChanged(isUnfolded);
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };
            sensorManager.registerListener(hingeListener, hingeAngleSensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    /**
     * 基于屏幕宽度判断是否展开
     * 荣耀V Purse展开宽度约2020px，折叠约1080px
     */
    public boolean detectByScreenSize(int screenWidth) {
        boolean newState = screenWidth > 1400;
        if (newState != isUnfolded) {
            isUnfolded = newState;
            if (callback != null) callback.onFoldStateChanged(isUnfolded);
        }
        return isUnfolded;
    }

    public boolean isUnfolded() {
        return isUnfolded;
    }

    public void setUnfolded(boolean unfolded) {
        this.isUnfolded = unfolded;
    }

    public void stopListening() {
        if (hingeListener != null && sensorManager != null) {
            sensorManager.unregisterListener(hingeListener);
            hingeListener = null;
        }
    }
}
