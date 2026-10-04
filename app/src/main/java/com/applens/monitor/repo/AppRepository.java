package com.applens.monitor.repo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.DiagnosticLog;
import com.applens.monitor.model.AppItem;
import com.applens.monitor.model.PermissionItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Scans, enriches and persists the list of installed applications. */
public final class AppRepository {

    public interface Progress {
        void onProgress(int done, int total, String current);
    }

    private static final int SIZE_CHUNK = 40;

    private AppRepository() {
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    /** Blocking full scan. Must not be called on the main thread. */
    public static List<AppItem> scan(Context ctx, final boolean includeSystem, final Progress cb) {
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
        } catch (Throwable t) {
            DiagnosticLog.recordProblem("Installed-app scan failed", t);
            packages = new ArrayList<>();
        }
        List<AppItem> out = new ArrayList<>(packages.size());
        int i = 0;
        for (PackageInfo info : packages) {
            i++;
            if (cb != null && (i % 8 == 0 || i == packages.size())) {
                cb.onProgress(i, packages.size(), info.packageName);
            }
            try {
                AppItem item = AppItem.from(ctx, info);
                if (!includeSystem && item.system) {
                    continue;
                }
                applyPermissionFlags(item, info);
                out.add(item);
            } catch (Throwable error) {
                // Skip the broken entry, but retain one diagnostic if this keeps happening.
                DiagnosticLog.recordThrottledProblem("package-entry",
                        "Could not read one installed-app entry", error);
            }
        }
        Collections.sort(out, AppItem.BY_NAME);
        return out;
    }

    private static void applyPermissionFlags(AppItem item, PackageInfo info) {
        String[] perms = info.requestedPermissions;
        int[] flags = info.requestedPermissionsFlags;
        if (perms == null) {
            return;
        }
        item.requestedPermissions = perms.length;
        int granted = 0;
        int dangerous = 0;
        int dangerousGranted = 0;
        for (int i = 0; i < perms.length; i++) {
            String p = perms[i];
            boolean isGranted = flags != null && i < flags.length
                    && (flags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
            if (isGranted) {
                granted++;
            }
            if (PermissionItem.protectionLevel(p).contains("dangerous")) {
                dangerous++;
                if (isGranted) {
                    dangerousGranted++;
                }
            }
        }
        item.grantedPermissions = granted;
        item.dangerousPermissions = dangerous;
        item.grantedDangerous = dangerousGranted;
    }

    /** Reads the installer package name for every app in one root call. */
    public static void resolveInstallers(List<AppItem> items) {
        List<String> names = new ArrayList<>(items.size());
        for (AppItem item : items) {
            names.add(item.pkg);
        }
        resolveInstallerNames(names, items);
    }

    private static void resolveInstallerNames(List<String> packages, List<AppItem> items) {
        if (packages.isEmpty() || !RootShell.get().ensureRoot()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("for p in");
        int n = 0;
        for (String pkg : packages) {
            if (n++ >= SIZE_CHUNK * 4) {
                break;
            }
            sb.append(' ').append(RootShell.shQuote(pkg));
        }
        sb.append("; do printf '%s\\t' \"$p\"; cmd package list-packages -i \"$p\" 2>/dev/null | head -1; done");
        for (String line : RootShell.get().execLines(sb.toString(), 40000)) {
            String[] parts = line.split("\t");
            if (parts.length < 2) {
                continue;
            }
            String pkg = parts[0].trim();
            String installer = parts[1].trim();
            if (installer.startsWith("package:")) {
                installer = installer.substring(8);
            }
            if (installer.isEmpty()) {
                continue;
            }
            for (AppItem item : items) {
                if (item.pkg.equals(pkg)) {
                    item.installer = installer;
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Sizes
    // ------------------------------------------------------------------

    /**
     * Resolves APK + data sizes through root {@code du}/{@code stat}. Reports progress
     * per application so the list can fill in as it goes.
     */
    public static void resolveSizes(final Context ctx, final List<AppItem> items, final Progress cb) {
        if (items.isEmpty()) {
            return;
        }
        RootShell root = RootShell.get();
        if (!root.ensureRoot()) {
            for (AppItem item : items) {
                item.sizesResolved = true;
                item.apkSize = 0;
                item.dataSize = 0;
            }
            return;
        }
        int done = 0;
        for (int start = 0; start < items.size(); start += SIZE_CHUNK) {
            int end = Math.min(items.size(), start + SIZE_CHUNK);
            final List<AppItem> chunk = new ArrayList<>(items.subList(start, end));
            StringBuilder cmd = new StringBuilder("for p in");
            for (AppItem item : chunk) {
                cmd.append(' ').append(RootShell.shQuote(item.pkg));
            }
            cmd.append("; do p=\"$p\";");
            cmd.append(" a=$(pm path \"$p\" 2>/dev/null | head -1 | cut -d: -f2);");
            cmd.append(" s=0; [ -n \"$a\" ] && s=$(stat -c %s \"$a\" 2>/dev/null || echo 0);");
            cmd.append(" d=$(pm path \"$p\" 2>/dev/null | wc -l);");
            cmd.append(" dd=$(dumpsys package \"$p\" 2>/dev/null | sed -n 's/^ *dataDir=//p' | head -1);");
            cmd.append(" du_val=0; [ -n \"$dd\" ] && du_val=$(du -sk \"$dd\" 2>/dev/null | cut -f1);");
            cmd.append(" printf '%s\\t%s\\t%s\\n' \"$p\" \"$s\" \"$du_val\";");
            cmd.append(" done");
            for (String line : root.execLines(cmd.toString(), 60000)) {
                String[] parts = line.split("\t");
                if (parts.length < 3) {
                    continue;
                }
                String pkg = parts[0].trim();
                for (AppItem item : chunk) {
                    if (!item.pkg.equals(pkg)) {
                        continue;
                    }
                    item.apkSize = parseLong(parts[1]);
                    long kb = parseLong(parts[2]);
                    item.dataSize = kb <= 0 ? 0 : kb * 1024;
                    item.sizesResolved = true;
                    done++;
                    if (cb != null) {
                        cb.onProgress(done, items.size(), pkg);
                    }
                }
            }
            for (AppItem item : chunk) {
                item.sizesResolved = true;
            }
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Running state
    // ------------------------------------------------------------------

    /** Marks which applications currently own at least one process. */
    public static void resolveRunning(List<AppItem> items, List<int[]> uidPidMap) {
        if (uidPidMap == null) {
            return;
        }
        for (AppItem item : items) {
            item.running = false;
            item.pid = 0;
            for (int[] pair : uidPidMap) {
                if (pair[0] == item.uid) {
                    item.running = true;
                    item.pid = pair[1];
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private static final String SNAPSHOT = "applens_app_snapshot";

    public static void saveSnapshot(Context ctx, List<AppItem> items) {
        StringBuilder sb = new StringBuilder();
        for (AppItem item : items) {
            if (item.system) {
                continue;
            }
            sb.append(item.pkg).append('\t')
                    .append(item.label).append('\t')
                    .append(item.versionName).append('\t')
                    .append(item.versionCode).append('\t')
                    .append(item.uid).append('\t')
                    .append(item.firstInstall).append('\t')
                    .append(item.lastUpdate).append('\t')
                    .append(item.apkSize).append('\t')
                    .append(item.dataSize).append('\t')
                    .append(item.system ? 1 : 0).append('\t')
                    .append(item.running ? 1 : 0).append('\n');
        }
        ctx.getSharedPreferences("applens_prefs", Context.MODE_PRIVATE)
                .edit().putString(SNAPSHOT, sb.toString()).apply();
    }

    /** Restores the last scan instantly so the dashboard is never empty. */
    public static List<AppItem> loadSnapshot(Context ctx) {
        String data = ctx.getSharedPreferences("applens_prefs", Context.MODE_PRIVATE)
                .getString(SNAPSHOT, "");
        List<AppItem> out = new ArrayList<>();
        if (data == null || data.isEmpty()) {
            return out;
        }
        for (String line : data.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t");
            if (f.length < 11) {
                continue;
            }
            AppItem item = new AppItem();
            item.pkg = f[0];
            item.label = f[1];
            item.versionName = f[2];
            item.versionCode = parseLong(f[3]);
            item.uid = (int) parseLong(f[4]);
            item.firstInstall = parseLong(f[5]);
            item.lastUpdate = parseLong(f[6]);
            item.apkSize = parseLong(f[7]);
            item.dataSize = parseLong(f[8]);
            item.system = "1".equals(f[9]);
            item.running = "1".equals(f[10]);
            item.sizesResolved = true;
            out.add(item);
        }
        Collections.sort(out, AppItem.BY_NAME);
        return out;
    }

    // ------------------------------------------------------------------
    // Scan history ("previously scanned")
    // ------------------------------------------------------------------

    private static final String HISTORY = "applens_scan_history";

    public static void recordScan(Context ctx, List<String> packages) {
        String existing = ctx.getSharedPreferences("applens_prefs", Context.MODE_PRIVATE)
                .getString(HISTORY, "");
        List<String> list = new ArrayList<>();
        if (existing != null) {
            for (String s : existing.split("\n")) {
                if (!s.isEmpty()) {
                    list.add(s);
                }
            }
        }
        long now = System.currentTimeMillis();
        for (String pkg : packages) {
            list.remove(pkg);
            list.add(0, pkg);
        }
        while (list.size() > 40) {
            list.remove(list.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            sb.append(s).append('\n');
        }
        ctx.getSharedPreferences("applens_prefs", Context.MODE_PRIVATE)
                .edit().putString(HISTORY, sb.toString())
                .putLong("last_scan_time", now)
                .apply();
    }

    public static List<String> scanHistory(Context ctx) {
        String data = ctx.getSharedPreferences("applens_prefs", Context.MODE_PRIVATE)
                .getString(HISTORY, "");
        List<String> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        for (String s : data.split("\n")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    public static long lastScanTime(Context ctx) {
        return ctx.getSharedPreferences("applens_prefs", Context.MODE_PRIVATE).getLong("last_scan_time", 0);
    }

    public static void rememberOpened(Context ctx, String pkg) {
        String existing = ctx.getSharedPreferences("applens_prefs", Context.MODE_PRIVATE)
                .getString("recent_packages", "");
        List<String> list = new ArrayList<>();
        if (existing != null) {
            for (String s : existing.split("\n")) {
                if (!s.isEmpty()) {
                    list.add(s);
                }
            }
        }
        list.remove(pkg);
        list.add(0, pkg);
        while (list.size() > 12) {
            list.remove(list.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            sb.append(s).append('\n');
        }
        ctx.getSharedPreferences("applens_prefs", Context.MODE_PRIVATE)
                .edit().putString("recent_packages", sb.toString()).apply();
    }

    public static List<String> recentPackages(Context ctx) {
        String data = ctx.getSharedPreferences("applens_prefs", Context.MODE_PRIVATE)
                .getString("recent_packages", "");
        List<String> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        for (String s : data.split("\n")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    public static Comparator<AppItem> comparatorFor(int sortMode) {
        switch (sortMode) {
            case 1:
                return AppItem.BY_PACKAGE;
            case 2:
                return AppItem.BY_INSTALL;
            case 3:
                return AppItem.BY_UPDATE;
            case 4:
                return AppItem.BY_SIZE;
            default:
                return AppItem.BY_NAME;
        }
    }

    public static String sizeOfAll(Context ctx, List<AppItem> items) {
        long total = 0;
        for (AppItem item : items) {
            total += Math.max(0, item.totalSize());
        }
        return Fmt.bytes(total);
    }
}
