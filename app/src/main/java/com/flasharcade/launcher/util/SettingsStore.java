package com.flasharcade.launcher.util;

import android.content.Context;
import android.content.SharedPreferences;

public final class SettingsStore {
    public static final String QUALITY_LOW = "low";
    public static final String QUALITY_MEDIUM = "medium";
    public static final String QUALITY_HIGH = "high";
    public static final String SCALE_SHOW_ALL = "showAll";
    public static final String SCALE_NO_BORDER = "noBorder";
    public static final String SCALE_EXACT_FIT = "exactFit";
    public static final String SCALE_NO_SCALE = "noScale";
    public static final String[] CONTROL_NAMES = {"up", "down", "left", "right"};
    private static final String PREFS = "flash_arcade_settings";
    private final SharedPreferences preferences;

    public SettingsStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public float gameScale() { return positiveFloat("game_scale", 1f); }
    public void setGameScale(float value) { putPositiveFloat("game_scale", value); }
    public float overlayScale() { return positiveFloat("overlay_scale", 1f); }
    public void setOverlayScale(float value) { putPositiveFloat("overlay_scale", value); }
    public int safeMarginPercent() { return preferences.getInt("safe_margin", 0); }
    public void setSafeMarginPercent(int value) { preferences.edit().putInt("safe_margin", clamp(value, 0, 30)).apply(); }
    public String quality() { return preferences.getString("quality", QUALITY_HIGH); }
    public void setQuality(String value) {
        if (QUALITY_LOW.equals(value) || QUALITY_MEDIUM.equals(value) || QUALITY_HIGH.equals(value)) {
            preferences.edit().putString("quality", value).apply();
        }
    }
    public String scaleMode() { return preferences.getString("scale_mode", SCALE_SHOW_ALL); }
    public void setScaleMode(String value) {
        if (SCALE_SHOW_ALL.equals(value) || SCALE_NO_BORDER.equals(value)
                || SCALE_EXACT_FIT.equals(value) || SCALE_NO_SCALE.equals(value)) {
            preferences.edit().putString("scale_mode", value).apply();
        }
    }
    public boolean controlsVisible() { return preferences.getBoolean("controls_visible", true); }
    public void setControlsVisible(boolean value) { preferences.edit().putBoolean("controls_visible", value).apply(); }
    public int controlX(String name) { return preferences.getInt("control_" + name + "_x", defaultX(name)); }
    public void setControlX(String name, int value) { preferences.edit().putInt("control_" + name + "_x", clamp(value, 0, 100)).apply(); }
    public int controlY(String name) { return preferences.getInt("control_" + name + "_y", defaultY(name)); }
    public void setControlY(String name, int value) { preferences.edit().putInt("control_" + name + "_y", clamp(value, 0, 100)).apply(); }
    public float controlSize(String name) { return positiveFloat("control_" + name + "_size", 54f); }
    public void setControlSize(String name, float value) { putPositiveFloat("control_" + name + "_size", value); }

    public static int defaultX(String name) {
        switch (name) {
            case "up": return 82;
            case "down": return 82;
            case "left": return 70;
            case "right": return 94;
            default: return 50;
        }
    }

    public static int defaultY(String name) {
        switch (name) {
            case "up": return 68;
            case "down": return 91;
            case "left": return 80;
            case "right": return 80;
            default: return 50;
        }
    }

    private float positiveFloat(String key, float fallback) {
        float value = preferences.getFloat(key, fallback);
        return isPositiveFinite(value) ? value : fallback;
    }

    private void putPositiveFloat(String key, float value) {
        if (isPositiveFinite(value)) preferences.edit().putFloat(key, value).apply();
    }

    private static boolean isPositiveFinite(float value) {
        return value > 0f && !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
