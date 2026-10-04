package com.applens.monitor.core;

import android.content.Context;
import android.os.Environment;

import java.io.File;

/**
 * User configurable output settings for the plain-text reports.
 *
 * <p>The folder is stored as a real filesystem path (root can write anywhere), but
 * a folder chosen through the system picker arrives as a {@code content://…/tree/…}
 * URI; such a URI is converted back into a path here whenever the storage provider
 * is the ordinary external-storage one.</p>
 */
public final class LogSettings {

    /** Where reports go unless the user changes it. */
    public static final String DEFAULT_FOLDER = "/sdcard/AppLens";
    /** Alternate folders offered as one-tap chips in the settings screen. */
    public static final String[] SUGGESTED_FOLDERS = {
            "/sdcard/AppLens",
            "/sdcard/Download/AppLens",
            "/sdcard/Documents/AppLens",
            "/sdcard/AppLens/reports",
    };

    private LogSettings() {
    }

    // ------------------------------------------------------------------
    // Report file
    // ------------------------------------------------------------------

    public static boolean reportEnabled() {
        return Prefs.bool(Prefs.K_LOG_TO_SDCARD, true);
    }

    public static void setReportEnabled(boolean enabled) {
        Prefs.put(Prefs.K_LOG_TO_SDCARD, enabled);
    }

    /** One sub folder per monitored package, or every report in the same folder. */
    public static boolean folderPerApp() {
        return Prefs.bool(Prefs.K_LOG_PER_APP, true);
    }

    public static void setFolderPerApp(boolean value) {
        Prefs.put(Prefs.K_LOG_PER_APP, value);
    }

    /** Write the full app/device snapshot and the end-of-session summary. */
    public static boolean fullReport() {
        return Prefs.bool(Prefs.K_SNAPSHOT_REPORT, true);
    }

    public static void setFullReport(boolean value) {
        Prefs.put(Prefs.K_SNAPSHOT_REPORT, value);
    }

    /** Minutes between two heartbeat blocks inside a long record (0 disables). */
    public static int heartbeatMinutes() {
        return Math.max(0, Math.min(60, Prefs.integer(Prefs.K_HEARTBEAT_MIN, 5)));
    }

    public static void setHeartbeatMinutes(int minutes) {
        Prefs.put(Prefs.K_HEARTBEAT_MIN, Math.max(0, Math.min(60, minutes)));
    }

    // ------------------------------------------------------------------
    // DNS capture
    // ------------------------------------------------------------------

    /** Whether monitoring starts the local DNS capture VPN by default. */
    public static boolean dnsCapture() {
        return Prefs.bool(Prefs.K_DNS_VPN, true);
    }

    public static void setDnsCapture(boolean enabled) {
        Prefs.put(Prefs.K_DNS_VPN, enabled);
    }

    // ------------------------------------------------------------------
    // Folder handling
    // ------------------------------------------------------------------

    /** The configured output folder, always an absolute filesystem path. */
    public static String folder() {
        return normalize(Prefs.str(Prefs.K_LOG_DIR, DEFAULT_FOLDER));
    }

    public static void setFolder(String path) {
        Prefs.put(Prefs.K_LOG_DIR, normalize(path));
    }

    public static String sharedRoot() {
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root != null) {
                return root.getAbsolutePath();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "/sdcard";
    }

    /** The folder the next session will write into for {@code pkg}. */
    public static String folderFor(String pkg) {
        String base = folder();
        if (!folderPerApp()) {
            return base;
        }
        return base + "/" + safeName(pkg);
    }

    /** A package name that is usable as a directory name. */
    public static String safeName(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "unknown";
        }
        return pkg.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Makes a user supplied path absolute and strips the usual decorations:
     * quotes, a trailing slash and the {@code content://} form produced by the
     * system folder picker.
     */
    public static String normalize(String raw) {
        String path = raw == null ? "" : raw.trim();
        if (path.isEmpty()) {
            return DEFAULT_FOLDER;
        }
        if (path.length() > 1 && (path.startsWith("\"") || path.startsWith("'"))) {
            path = path.substring(1);
        }
        if (path.length() > 1 && (path.endsWith("\"") || path.endsWith("'"))) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.startsWith("content://")) {
            String resolved = fromTreeUri(path);
            if (resolved != null) {
                path = resolved;
            }
        }
        String root = sharedRoot();
        if (!path.startsWith("/")) {
            path = root + "/" + path;
        }
        // "/storage/emulated/0/..." and "/sdcard/..." are the same volume; keep the
        // friendlier form so the path shown in the UI matches what the user typed.
        try {
            String primary = "/storage/emulated/0";
            if (path.equals(primary)) {
                path = root;
            } else if (path.startsWith(primary + "/")) {
                path = root + path.substring(primary.length());
            }
        } catch (Throwable ignored) {
            // noop
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * Converts {@code content://com.android.externalstorage.documents/tree/primary%3AFoo}
     * into {@code /sdcard/Foo}. Returns {@code null} for providers that do not map
     * onto the ordinary shared volume.
     */
    public static String fromTreeUri(String uri) {
        try {
            android.net.Uri parsed = android.net.Uri.parse(uri);
            String last = parsed.getLastPathSegment();
            if (last == null) {
                return null;
            }
            String decoded = android.net.Uri.decode(last);
            if (decoded.startsWith("primary:")) {
                return sharedRoot() + "/" + decoded.substring("primary:".length());
            }
            if (decoded.startsWith("raw:")) {
                return decoded.substring("raw:".length());
            }
            int colon = decoded.indexOf(':');
            if (colon > 0) {
                // A secondary volume (SD card): /storage/<id>/<path>
                return "/storage/" + decoded.substring(0, colon) + "/"
                        + decoded.substring(colon + 1);
            }
        } catch (Throwable ignored) {
            // not a usable tree uri
        }
        return null;
    }

    /** Result of a "can we really write here?" check. */
    public static final class Check {
        public final boolean ok;
        public final String path;
        public final String note;

        Check(boolean ok, String path, String note) {
            this.ok = ok;
            this.path = path == null ? "" : path;
            this.note = note == null ? "" : note;
        }
    }

    /**
     * Creates the folder and proves that a file can actually be written inside it,
     * using root first and the ordinary file API second. The caller gets back both
     * the outcome and a sentence that can be shown to the user.
     */
    public static Check ensureWritable(Context ctx, String folder) {
        String path = normalize(folder);
        RootShell root = RootShell.get();
        boolean rootAvailable = root.isRootGranted() || root.ensureRoot();
        if (rootAvailable) {
            RootShell.Check made = root.makeFolder(path);
            if (made.ok) {
                return new Check(true, path, "writable via root (" + made.note + ")");
            }
            if (!fallbackAllowed(ctx, path)) {
                return new Check(false, path, "root could not create it: " + made.note);
            }
        }
        try {
            File dir = new File(path);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return new Check(false, path, "folder could not be created"
                        + (rootAvailable ? "" : " (no root access)"));
            }
            File probe = new File(dir, ".applens_write_test");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(probe, false);
            try {
                fos.write("AppLens write test\n".getBytes("UTF-8"));
                fos.flush();
            } finally {
                fos.close();
            }
            long size = probe.length();
            if (!probe.delete()) {
                probe.deleteOnExit();
            }
            if (size <= 0) {
                return new Check(false, path, "the test file stayed empty");
            }
            return new Check(true, path, "writable");
        } catch (Throwable error) {
            return new Check(false, path, describe(error));
        }
    }

    private static boolean fallbackAllowed(Context ctx, String path) {
        return true;
    }

    public static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
