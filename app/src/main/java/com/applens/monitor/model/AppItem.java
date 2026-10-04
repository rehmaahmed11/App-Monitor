package com.applens.monitor.model;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.drawable.Drawable;

import com.applens.monitor.core.Fmt;

import java.util.Comparator;

/** A single installed application as surfaced by the scanner. */
public class AppItem {

    public String pkg = "";
    public String label = "";
    public String versionName = "";
    public long versionCode = 0;
    public int uid = -1;
    public int targetSdk = 0;
    public int minSdk = 0;
    public String sourceDir = "";
    public String dataDir = "";
    public String nativeLibDir = "";
    public String processName = "";
    public String installer = "";
    public boolean system = false;
    public boolean updated = false;
    public boolean enabled = true;
    public long firstInstall = 0;
    public long lastUpdate = 0;

    public long apkSize = -1;
    public long dataSize = -1;
    public long cacheSize = -1;
    public long nativeSize = -1;
    public long externalSize = -1;
    public boolean sizesResolved = false;

    public int activities;
    public int services;
    public int receivers;
    public int providers;
    public int requestedPermissions;
    public int grantedPermissions;
    public int dangerousPermissions;
    public int grantedDangerous;

    public boolean running = false;
    public int pid = 0;
    public long pssKb = 0;
    public long lastScanTime = 0;

    private Drawable icon;

    public String displayLabel() {
        return label == null || label.isEmpty() ? pkg : label;
    }

    public long totalSize() {
        if (apkSize < 0 && dataSize < 0) {
            return -1;
        }
        return Math.max(0, apkSize) + Math.max(0, dataSize);
    }

    /** Lazily resolves and caches the launcher icon. */
    public Drawable icon(Context ctx) {
        if (icon == null) {
            try {
                icon = ctx.getPackageManager().getApplicationIcon(pkg);
            } catch (Throwable t) {
                try {
                    icon = ctx.getPackageManager().getDefaultActivityIcon();
                } catch (Throwable ignored) {
                    icon = null;
                }
            }
        }
        return icon;
    }

    public static AppItem from(Context ctx, PackageInfo info) {
        AppItem item = new AppItem();
        item.pkg = info.packageName;
        ApplicationInfo ai = info.applicationInfo;
        item.uid = ai != null ? ai.uid : -1;
        item.sourceDir = ai != null ? ai.sourceDir : "";
        item.dataDir = ai != null ? ai.dataDir : "";
        item.nativeLibDir = ai != null ? ai.nativeLibraryDir : "";
        item.processName = (ai != null && ai.processName != null) ? ai.processName : info.packageName;
        item.targetSdk = ai != null ? ai.targetSdkVersion : 0;
        item.minSdk = ai != null ? ai.minSdkVersion : 0;
        item.enabled = ai == null || ai.enabled;
        try {
            item.firstInstall = info.firstInstallTime;
            item.lastUpdate = info.lastUpdateTime;
        } catch (Throwable ignored) {
            // not available on every build
        }
        if (ai != null) {
            item.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            item.updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    || (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        }
        item.versionName = info.versionName == null ? "" : info.versionName;
        item.versionCode = versionCode(info);
        try {
            item.label = String.valueOf(ai.loadLabel(ctx.getPackageManager()));
        } catch (Throwable t) {
            item.label = info.packageName;
        }
        try {
            item.activities = info.activities != null ? info.activities.length : 0;
            item.services = info.services != null ? info.services.length : 0;
            item.receivers = info.receivers != null ? info.receivers.length : 0;
            item.providers = info.providers != null ? info.providers.length : 0;
            item.requestedPermissions = info.requestedPermissions != null ? info.requestedPermissions.length : 0;
        } catch (Throwable ignored) {
            // package parsing is best effort
        }
        item.lastScanTime = System.currentTimeMillis();
        return item;
    }

    public static long versionCode(PackageInfo info) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return info.getLongVersionCode();
            }
        } catch (Throwable ignored) {
            // fall through to the legacy field
        }
        return info.versionCode;
    }

    public static final Comparator<AppItem> BY_NAME = new Comparator<AppItem>() {
        @Override
        public int compare(AppItem a, AppItem b) {
            int c = a.displayLabel().compareToIgnoreCase(b.displayLabel());
            return c != 0 ? c : a.pkg.compareToIgnoreCase(b.pkg);
        }
    };

    public static final Comparator<AppItem> BY_PACKAGE = new Comparator<AppItem>() {
        @Override
        public int compare(AppItem a, AppItem b) {
            return a.pkg.compareToIgnoreCase(b.pkg);
        }
    };

    public static final Comparator<AppItem> BY_INSTALL = new Comparator<AppItem>() {
        @Override
        public int compare(AppItem a, AppItem b) {
            return Long.compare(b.firstInstall, a.firstInstall);
        }
    };

    public static final Comparator<AppItem> BY_UPDATE = new Comparator<AppItem>() {
        @Override
        public int compare(AppItem a, AppItem b) {
            return Long.compare(b.lastUpdate, a.lastUpdate);
        }
    };

    public static final Comparator<AppItem> BY_SIZE = new Comparator<AppItem>() {
        @Override
        public int compare(AppItem a, AppItem b) {
            return Long.compare(b.totalSize(), a.totalSize());
        }
    };

    public String sizeText() {
        long total = totalSize();
        if (total < 0) {
            return sizesResolved ? "0 B" : "…";
        }
        return Fmt.bytes(total);
    }

    public String shortType() {
        if (running) {
            return "RUNNING";
        }
        return system ? "SYSTEM" : "USER";
    }

    /** Compact single line used by the plain-text log writer. */
    public String toLogLine() {
        return pkg + " | uid=" + uid + " | v" + versionName + " (" + versionCode + ")"
                + " | " + shortType() + " | size=" + sizeText();
    }
}
