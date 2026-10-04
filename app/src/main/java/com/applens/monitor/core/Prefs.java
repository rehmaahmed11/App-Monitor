package com.applens.monitor.core;

import android.content.Context;
import android.content.SharedPreferences;

/** Thin, typed wrapper around the application {@link SharedPreferences}. */
public final class Prefs {

    private static final String NAME = "applens_prefs";
    private static SharedPreferences sp;

    private Prefs() {
    }

    public static void init(Context ctx) {
        if (sp == null) {
            sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
    }

    public static SharedPreferences sp() {
        return sp;
    }

    public static String str(String key, String def) {
        return sp().getString(key, def);
    }

    public static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
    }

    public static int integer(String key, int def) {
        return sp().getInt(key, def);
    }

    public static void put(String key, int value) {
        sp().edit().putInt(key, value).apply();
    }

    public static boolean bool(String key, boolean def) {
        return sp().getBoolean(key, def);
    }

    public static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
    }

    public static void remove(String key) {
        sp().edit().remove(key).apply();
    }

    // ---- well known keys -------------------------------------------------
    public static final String K_SORT = "sort_mode";
    public static final String K_INCLUDE_SYSTEM = "include_system";
    public static final String K_ONLY_RUNNING = "only_running";
    public static final String K_SNAPSHOT_TIME = "snapshot_time";
    public static final String K_SNAPSHOT_COUNT = "snapshot_count";
    public static final String K_RECENT = "recent_packages";
    public static final String K_HISTORY = "scan_history";
    public static final String K_SIZES = "size_cache";
    public static final String K_LOG_TO_SDCARD = "log_to_sdcard";
    public static final String K_LOG_DIR = "log_dir";
    public static final String K_ROOT_PATH = "root_su_path";
    public static final String K_DNS_VPN = "dns_vpn_enabled";
    public static final String K_PERF_SAMPLE = "perf_sample_ms";
    public static final String K_NOTIFY = "notifications";
    public static final String K_FILTER_CAT = "filter_category";
}
