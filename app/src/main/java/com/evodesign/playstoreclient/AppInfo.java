package com.evodesign.playstoreclient;

import android.content.Context;
import android.content.pm.PackageManager;

public class AppInfo {
    private final String name;
    private final String packageName;
    private final int versionCode;
    private final String versionName;
    private final String apkUrl;
    private final String iconUrl;
    private boolean isInstalled;

    // Modify the constructor to accept a Context parameter
    public AppInfo(Context context, String name, String packageName, int versionCode, String versionName, String apkUrl, String iconUrl) {
        this.name = name;
        this.packageName = packageName;
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUrl = apkUrl;
        this.iconUrl = iconUrl;
        this.isInstalled = isAppInstalled(context, packageName); // Pass context here
    }

    // Check if the app is installed
    private boolean isAppInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true; // App is installed
        } catch (PackageManager.NameNotFoundException e) {
            return false; // App is not installed
        }
    }

    public String getName() { return name; }
    public String getPackageName() { return packageName; }
    public int getVersionCode() { return versionCode; }
    public String getVersionName() { return versionName; }
    public String getApkUrl() { return apkUrl; }
    public String getIconUrl() { return iconUrl; }
    public boolean isInstalled() { return isInstalled; }
}
