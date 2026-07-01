package com.vlab.scrbalance;

import org.json.JSONObject;

/**
 * 亮度校色配置 - 绑定某个亮度值下的颜色和透明度设置
 */
public class BrightnessProfile {

    public int brightness; // 0-255 (系统亮度范围)
    public int leftColor;
    public int rightColor;
    public int leftOpacity;
    public int rightOpacity;

    public BrightnessProfile(int brightness, int leftColor, int rightColor, int leftOpacity, int rightOpacity) {
        this.brightness = brightness;
        this.leftColor = leftColor;
        this.rightColor = rightColor;
        this.leftOpacity = leftOpacity;
        this.rightOpacity = rightOpacity;
    }

    public BrightnessProfile(JSONObject json) {
        this.brightness = json.optInt("brightness", 128);
        this.leftColor = json.optInt("leftColor", AppConfig.DEFAULT_LEFT_COLOR);
        this.rightColor = json.optInt("rightColor", AppConfig.DEFAULT_RIGHT_COLOR);
        this.leftOpacity = json.optInt("leftOpacity", AppConfig.DEFAULT_LEFT_OPACITY);
        this.rightOpacity = json.optInt("rightOpacity", AppConfig.DEFAULT_RIGHT_OPACITY);
    }

    public JSONObject toJSONObject() {
        JSONObject json = new JSONObject();
        try {
            json.put("brightness", brightness);
            json.put("leftColor", leftColor);
            json.put("rightColor", rightColor);
            json.put("leftOpacity", leftOpacity);
            json.put("rightOpacity", rightOpacity);
        } catch (Exception ignored) {}
        return json;
    }

    /** 系统亮度(0-255)转为百分比(0-100) */
    public int brightnessPercent() {
        return (int) Math.round(brightness * 100.0 / 255.0);
    }
}
