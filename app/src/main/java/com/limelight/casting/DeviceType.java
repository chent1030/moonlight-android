package com.limelight.casting;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

/**
 * 设备类型自检：电视（带投屏接收能力）还是广告机（纯内容展示）。
 *
 * 判定依据（任一命中即视为电视）：
 *  - uiMode 为 TELEVISION
 *  - 系统声明 android.software.leanback（Android TV）
 *  - 系统声明 android.hardware.type.television
 * 其余（通用 Android 盒子/一体机）判为广告机。
 *
 * 可用 casting_prefs 的 cast_device_type_override 覆盖（调试用）。
 */
public final class DeviceType {

    public static final String TV = "tv";
    public static final String SIGNAGE = "signage";

    private DeviceType() {
    }

    public static String detect(Context ctx) {
        try {
            String override = ctx.getSharedPreferences(CastingControlService.PREFS, Context.MODE_PRIVATE)
                    .getString("cast_device_type_override", "");
            if (override != null && !override.trim().isEmpty()) {
                return override.trim();
            }
        } catch (Exception ignored) {
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            boolean leanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
            boolean tvHardware = pm.hasSystemFeature("android.hardware.type.television");
            int uiMode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
            boolean tvUi = uiMode == Configuration.UI_MODE_TYPE_TELEVISION;
            return (leanback || tvHardware || tvUi) ? TV : SIGNAGE;
        } catch (Exception e) {
            return TV;
        }
    }

    public static boolean isSignage(Context ctx) {
        return SIGNAGE.equals(detect(ctx));
    }
}
