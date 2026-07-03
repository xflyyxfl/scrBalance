package com.vlab.scrbalance;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 应用配置管理 - 所有写入使用commit()确保即时持久化
 */
public class AppConfig {

    private static final String PREFS_NAME = "scr_balance_prefs";

    private static final String KEY_ENABLED = "overlay_enabled";
    private static final String KEY_LEFT_COLOR = "left_color";
    private static final String KEY_RIGHT_COLOR = "right_color";
    private static final String KEY_LEFT_OPACITY = "left_opacity";
    private static final String KEY_RIGHT_OPACITY = "right_opacity";
    private static final String KEY_MODE = "overlay_mode";
    private static final String KEY_AUTO_FOLD = "auto_fold_detect";
    private static final String KEY_CUSTOM_LEFT_START = "custom_left_start";
    private static final String KEY_CUSTOM_LEFT_END = "custom_left_end";
    private static final String KEY_CUSTOM_RIGHT_START = "custom_right_start";
    private static final String KEY_CUSTOM_RIGHT_END = "custom_right_end";
    private static final String KEY_CUSTOM_TOP = "custom_top";
    private static final String KEY_CUSTOM_BOTTOM = "custom_bottom";
    private static final String KEY_BRIGHTNESS_PROFILES = "brightness_profiles";
    private static final String KEY_SETTINGS_MODE = "settings_mode";
    private static final String KEY_BG_COLOR = "bg_color";
    private static final String KEY_BG_IMAGE_URI = "bg_image_uri";
    private static final String KEY_CURRENT_GRAY_LEVEL = "current_gray_level";
    private static final String KEY_SPLIT_POSITION = "split_position"; // 分割位置(0-2000, 每单位0.05%, 1000=50%)
    private static final String KEY_OVERLAY_SWAP = "overlay_swap";   // 左右互换(true=互换profile分配)
    private static final String KEY_OVERLAY_ROTATE = "overlay_rotate"; // 旋转偏移: 0=自动, 90/180/270=手动

    // Defaults
    public static final int DEFAULT_LEFT_COLOR = 0xFFFFE0B2;
    public static final int DEFAULT_RIGHT_COLOR = 0xFFB3E5FC;
    public static final int DEFAULT_LEFT_OPACITY = 30;
    public static final int DEFAULT_RIGHT_OPACITY = 30;
    public static final String DEFAULT_MODE = "half";
    public static final boolean DEFAULT_AUTO_FOLD = true;
    public static final int DEFAULT_CUSTOM_LEFT_START = 0;
    public static final int DEFAULT_CUSTOM_LEFT_END = 50;
    public static final int DEFAULT_CUSTOM_RIGHT_START = 50;
    public static final int DEFAULT_CUSTOM_RIGHT_END = 100;
    public static final int DEFAULT_CUSTOM_TOP = 0;
    public static final int DEFAULT_CUSTOM_BOTTOM = 100;
    public static final int DEFAULT_BG_COLOR = 0xFFFFFFFF;
    public static final int DEFAULT_GRAY_LEVEL = 100; // 默认白色底色→灰度100
    public static final int DEFAULT_SPLIT_POSITION = 1000; // 1000=50.00%
    public static final boolean DEFAULT_OVERLAY_SWAP = false;
    public static final int DEFAULT_OVERLAY_ROTATE = 0; // 0=自动从尺寸判断

    private final SharedPreferences prefs;

    public AppConfig(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() { return prefs.getBoolean(KEY_ENABLED, false); }
    public void setEnabled(boolean v) { prefs.edit().putBoolean(KEY_ENABLED, v).commit(); }

    public int getLeftColor() { return prefs.getInt(KEY_LEFT_COLOR, DEFAULT_LEFT_COLOR); }
    public void setLeftColor(int v) { prefs.edit().putInt(KEY_LEFT_COLOR, v).commit(); }

    public int getRightColor() { return prefs.getInt(KEY_RIGHT_COLOR, DEFAULT_RIGHT_COLOR); }
    public void setRightColor(int v) { prefs.edit().putInt(KEY_RIGHT_COLOR, v).commit(); }

    public int getLeftOpacity() { return prefs.getInt(KEY_LEFT_OPACITY, DEFAULT_LEFT_OPACITY); }
    public void setLeftOpacity(int v) { prefs.edit().putInt(KEY_LEFT_OPACITY, v).commit(); }

    public int getRightOpacity() { return prefs.getInt(KEY_RIGHT_OPACITY, DEFAULT_RIGHT_OPACITY); }
    public void setRightOpacity(int v) { prefs.edit().putInt(KEY_RIGHT_OPACITY, v).commit(); }

    public String getMode() { return prefs.getString(KEY_MODE, DEFAULT_MODE); }
    public void setMode(String v) { prefs.edit().putString(KEY_MODE, v).commit(); }

    public boolean isAutoFoldDetect() { return prefs.getBoolean(KEY_AUTO_FOLD, DEFAULT_AUTO_FOLD); }
    public void setAutoFoldDetect(boolean v) { prefs.edit().putBoolean(KEY_AUTO_FOLD, v).commit(); }

    public int getCustomLeftStart() { return prefs.getInt(KEY_CUSTOM_LEFT_START, DEFAULT_CUSTOM_LEFT_START); }
    public void setCustomLeftStart(int v) { prefs.edit().putInt(KEY_CUSTOM_LEFT_START, v).commit(); }

    public int getCustomLeftEnd() { return prefs.getInt(KEY_CUSTOM_LEFT_END, DEFAULT_CUSTOM_LEFT_END); }
    public void setCustomLeftEnd(int v) { prefs.edit().putInt(KEY_CUSTOM_LEFT_END, v).commit(); }

    public int getCustomRightStart() { return prefs.getInt(KEY_CUSTOM_RIGHT_START, DEFAULT_CUSTOM_RIGHT_START); }
    public void setCustomRightStart(int v) { prefs.edit().putInt(KEY_CUSTOM_RIGHT_START, v).commit(); }

    public int getCustomRightEnd() { return prefs.getInt(KEY_CUSTOM_RIGHT_END, DEFAULT_CUSTOM_RIGHT_END); }
    public void setCustomRightEnd(int v) { prefs.edit().putInt(KEY_CUSTOM_RIGHT_END, v).commit(); }

    public int getCustomTop() { return prefs.getInt(KEY_CUSTOM_TOP, DEFAULT_CUSTOM_TOP); }
    public void setCustomTop(int v) { prefs.edit().putInt(KEY_CUSTOM_TOP, v).commit(); }

    public int getCustomBottom() { return prefs.getInt(KEY_CUSTOM_BOTTOM, DEFAULT_CUSTOM_BOTTOM); }
    public void setCustomBottom(int v) { prefs.edit().putInt(KEY_CUSTOM_BOTTOM, v).commit(); }

    /** 获取亮度校色配置列表（按亮度排序） */
    public List<BrightnessProfile> getBrightnessProfiles() {
        String json = prefs.getString(KEY_BRIGHTNESS_PROFILES, "[]");
        List<BrightnessProfile> profiles = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                profiles.add(new BrightnessProfile(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
        Collections.sort(profiles, (a, b) -> a.brightness - b.brightness);
        return profiles;
    }

    /** 保存亮度校色配置列表 */
    public void saveBrightnessProfiles(List<BrightnessProfile> profiles) {
        Collections.sort(profiles, (a, b) -> a.brightness - b.brightness);
        JSONArray arr = new JSONArray();
        for (BrightnessProfile p : profiles) {
            arr.put(p.toJSONObject());
        }
        prefs.edit().putString(KEY_BRIGHTNESS_PROFILES, arr.toString()).commit();
    }

    /** 添加或更新一个亮度配置（相同亮度+灰度则更新） */
    public void addOrUpdateBrightnessProfile(BrightnessProfile profile) {
        List<BrightnessProfile> profiles = getBrightnessProfiles();
        boolean updated = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).brightness == profile.brightness && profiles.get(i).grayLevel == profile.grayLevel) {
                profiles.set(i, profile);
                updated = true;
                break;
            }
        }
        if (!updated) profiles.add(profile);
        saveBrightnessProfiles(profiles);
    }

    /** 删除指定亮度+灰度的配置 */
    public void removeBrightnessProfile(int brightness, int grayLevel) {
        List<BrightnessProfile> profiles = getBrightnessProfiles();
        profiles.removeIf(p -> p.brightness == brightness && p.grayLevel == grayLevel);
        saveBrightnessProfiles(profiles);
    }

    /** 设置模式标志：当用户在校色设置界面时暂停亮度插值，直接使用手动设置值 */
    public boolean isSettingsMode() { return prefs.getBoolean(KEY_SETTINGS_MODE, false); }
    public void setSettingsMode(boolean v) { prefs.edit().putBoolean(KEY_SETTINGS_MODE, v).commit(); }

    /** 底色（预览背景色） */
    public int getBgColor() { return prefs.getInt(KEY_BG_COLOR, DEFAULT_BG_COLOR); }
    public void setBgColor(int v) { prefs.edit().putInt(KEY_BG_COLOR, v).commit(); }

    /** 底色图片URI */
    public String getBgImageUri() { return prefs.getString(KEY_BG_IMAGE_URI, null); }
    public void setBgImageUri(String v) { prefs.edit().putString(KEY_BG_IMAGE_URI, v).commit(); }

    /** 当前灰度等级（用于运行时2D插值的灰度维度） */
    public int getCurrentGrayLevel() { return prefs.getInt(KEY_CURRENT_GRAY_LEVEL, DEFAULT_GRAY_LEVEL); }
    public void setCurrentGrayLevel(int v) { prefs.edit().putInt(KEY_CURRENT_GRAY_LEVEL, v).commit(); }

    /** 分割位置(0-2000, 每单位0.05%, 1000=50.00%) */
    public int getSplitPosition() { return prefs.getInt(KEY_SPLIT_POSITION, DEFAULT_SPLIT_POSITION); }
    public void setSplitPosition(int v) { prefs.edit().putInt(KEY_SPLIT_POSITION, v).commit(); }

    /** 左右互换: true=互换profile分配(左色→右半, 右色→左半) */
    public boolean isOverlaySwap() { return prefs.getBoolean(KEY_OVERLAY_SWAP, DEFAULT_OVERLAY_SWAP); }
    public void setOverlaySwap(boolean v) { prefs.edit().putBoolean(KEY_OVERLAY_SWAP, v).commit(); }

    /** 旋转偏移: 0=自动, 90/180/270=手动校正方向 */
    public int getOverlayRotate() { return prefs.getInt(KEY_OVERLAY_ROTATE, DEFAULT_OVERLAY_ROTATE); }
    public void setOverlayRotate(int v) { prefs.edit().putInt(KEY_OVERLAY_ROTATE, v).commit(); }

    public void resetDefaults() { prefs.edit().clear().commit(); }
}
