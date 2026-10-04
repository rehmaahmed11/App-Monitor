package com.applens.monitor.log;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.applens.monitor.BuildConfig;
import com.applens.monitor.core.RootShell;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small, private, bounded diagnostic log for errors that are otherwise easy to
 * lose when Android terminates the app. Fatal Java crashes are synchronously
 * saved before Android's normal crash handler is invoked.
 */
public final class DiagnosticLog {

    private static final String TAG = "AppLensDiagnostics";
    private static final String FILE_NAME = "applens-diagnostics.txt";
    private static final String PREFS_NAME = "applens_diagnostic_state";
    private static final String KEY_PENDING_REPORT = "pending_report";
    private static final String KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp";
    private static final String KEY_SESSION_ACTIVITY = "session_activity";
    private static final String KEY_SESSION_STARTED = "session_started";
    private static final long MAX_LOG_BYTES = 512L * 1024L;
    private static final int MAX_ENTRY_BYTES = 128 * 1024;
    private static final int MAX_EXIT_TRACE_BYTES = 48 * 1024;
    private static final long THROTTLE_WINDOW_MS = 5 * 60 * 1000L;

    private static final Object FILE_LOCK = new Object();
    private static final Object THROTTLE_LOCK = new Object();
    private static final Map<String, Long> LAST_REPORTED = new HashMap<>();

    private static volatile Context appContext;
    private static boolean handlerInstalled;

    private DiagnosticLog() {
    }

    /** Installs the crash hook once per process. */
    public static void init(Context context) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        appContext = application != null ? application : context;
        synchronized (DiagnosticLog.class) {
            if (handlerInstalled) {
                return;
            }
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable error) {
                    try {
                        Context current = appContext;
                        markPendingReport(current);
                        appendEntry(current, "FATAL CRASH", "Uncaught exception",
                                stackTrace(error), thread == null ? "unknown" : thread.getName(), true);
                    } catch (Throwable loggingFailure) {
                        // Never let diagnostics interfere with Android's crash reporting.
                        Log.e(TAG, "Could not save fatal crash details", loggingFailure);
                    }
                    if (previous != null) {
                        previous.uncaughtException(thread, error);
                    } else {
                        Process.killProcess(Process.myPid());
                        System.exit(10);
                    }
                }
            });
            handlerInstalled = true;
        }
    }

    /**
     * Checks Android's process history for crashes, ANRs or memory kills that did
     * not reach the Java uncaught-exception handler (API 30 and newer).
     */
    public static void inspectPreviousProcessExit(Context context) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        if (application == null) {
            application = context;
        }
        String explanation = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                explanation = Api30.inspect(application);
            } catch (Throwable ignored) {
                // Exit history is optional; keep app startup independent of it.
            }
        }
        try {
            // Even without exit history (Android 10 and older, or an exit the system
            // did not classify) an unfinished session is still reported.
            reportUnfinishedSession(application, explanation);
        } catch (Throwable ignored) {
            // noop
        }
    }

    /**
     * Records that a long running, crash-prone activity has begun — monitoring a
     * package, for instance. If the process disappears before
     * {@link #endSession()} runs, the next launch reports it, which is what makes
     * ANRs, low-memory kills and "the app just vanished" visible in the DIAG box
     * even though no Java exception was ever thrown.
     */
    public static void beginSession(String activity) {
        Context context = appContext;
        if (context == null) {
            return;
        }
        try {
            preferences(context).edit()
                    .putString(KEY_SESSION_ACTIVITY, value(activity, "a monitoring session"))
                    .putLong(KEY_SESSION_STARTED, System.currentTimeMillis())
                    .commit();
        } catch (Throwable error) {
            Log.e(TAG, "Could not record the session breadcrumb", error);
        }
    }

    /** Clears the breadcrumb left by {@link #beginSession(String)}. */
    public static void endSession() {
        Context context = appContext;
        if (context == null) {
            return;
        }
        try {
            preferences(context).edit()
                    .remove(KEY_SESSION_ACTIVITY)
                    .remove(KEY_SESSION_STARTED)
                    .commit();
        } catch (Throwable error) {
            Log.e(TAG, "Could not clear the session breadcrumb", error);
        }
    }

    /**
     * Turns a breadcrumb left behind by a process that never came back into a
     * report. Called once at start-up, after the OS exit history was inspected.
     */
    private static void reportUnfinishedSession(Context context, String systemExplanation) {
        if (context == null) {
            return;
        }
        String activity;
        long started;
        try {
            SharedPreferences prefs = preferences(context);
            activity = prefs.getString(KEY_SESSION_ACTIVITY, null);
            started = prefs.getLong(KEY_SESSION_STARTED, 0L);
            if (activity == null || activity.trim().isEmpty()) {
                return;
            }
            prefs.edit().remove(KEY_SESSION_ACTIVITY).remove(KEY_SESSION_STARTED).commit();
        } catch (Throwable error) {
            Log.e(TAG, "Could not read the session breadcrumb", error);
            return;
        }
        StringBuilder details = new StringBuilder();
        details.append("AppLens stopped while ").append(activity).append(".\n");
        if (started > 0) {
            details.append("Session started: ").append(formatTime(started)).append('\n');
            details.append("Ran for: ")
                    .append(Math.max(0L, (System.currentTimeMillis() - started) / 1000L))
                    .append(" s before the process ended\n");
        }
        details.append('\n');
        if (systemExplanation != null && !systemExplanation.isEmpty()) {
            details.append("Android reported: ").append(systemExplanation).append('\n');
        } else {
            details.append("Android did not record a reason, which usually means the process\n")
                    .append("was killed from outside (task swipe, battery optimiser, low memory)\n")
                    .append("or stopped responding while the UI thread was busy.\n");
        }
        markPendingReport(context);
        appendEntry(context, "SESSION ENDED UNEXPECTEDLY",
                "Monitoring session did not finish", details.toString(), "AppLens startup check", true);
    }

    /** Stores an unexpected but handled error so it can still be copied later. */
    public static void recordProblem(String area, Throwable error) {
        if (error == null) {
            return;
        }
        Context context = appContext;
        markPendingReport(context);
        appendEntry(context, "HANDLED ERROR", area, stackTrace(error),
                Thread.currentThread().getName(), true);
    }

    /** Stores an unexpected handled error at most once per key per five minutes. */
    public static void recordThrottledProblem(String key, String area, Throwable error) {
        if (error == null) {
            return;
        }
        String safeKey = key == null ? area : key;
        long now = System.currentTimeMillis();
        synchronized (THROTTLE_LOCK) {
            Long previous = LAST_REPORTED.get(safeKey);
            if (previous != null && now - previous < THROTTLE_WINDOW_MS) {
                return;
            }
            LAST_REPORTED.put(safeKey, now);
        }
        recordProblem(area, error);
    }

    /** Returns the saved reports, newest entries at the end. */
    public static String readAll() {
        Context context = appContext;
        if (context == null) {
            return "";
        }
        File file = diagnosticFile(context);
        synchronized (FILE_LOCK) {
            if (!file.isFile() || file.length() == 0) {
                return "";
            }
            try {
                byte[] bytes = readTail(file, (int) Math.min(MAX_LOG_BYTES, file.length()));
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (Throwable error) {
                Log.e(TAG, "Could not read diagnostic log", error);
                return "Unable to read the diagnostic log: " + error;
            }
        }
    }

    public static long sizeBytes() {
        Context context = appContext;
        if (context == null) {
            return 0L;
        }
        File file = diagnosticFile(context);
        return file.isFile() ? file.length() : 0L;
    }

    public static long lastModified() {
        Context context = appContext;
        if (context == null) {
            return 0L;
        }
        File file = diagnosticFile(context);
        return file.isFile() ? file.lastModified() : 0L;
    }

    /** Clears saved diagnostics and dismisses any pending crash notice. */
    public static boolean clear() {
        Context context = appContext;
        if (context == null) {
            return false;
        }
        synchronized (FILE_LOCK) {
            try (FileOutputStream output = new FileOutputStream(diagnosticFile(context), false)) {
                output.getFD().sync();
            } catch (Throwable error) {
                Log.e(TAG, "Could not clear diagnostic log", error);
                return false;
            }
        }
        try {
            preferences(context).edit().putBoolean(KEY_PENDING_REPORT, false).commit();
        } catch (Throwable error) {
            Log.e(TAG, "Could not dismiss pending diagnostic notice", error);
        }
        synchronized (THROTTLE_LOCK) {
            LAST_REPORTED.clear();
        }
        return true;
    }

    /** True only once after an error; opening the diagnostics screen consumes it. */
    public static boolean consumePendingReport() {
        Context context = appContext;
        if (context == null) {
            return false;
        }
        try {
            SharedPreferences prefs = preferences(context);
            boolean pending = prefs.getBoolean(KEY_PENDING_REPORT, false);
            if (pending) {
                prefs.edit().putBoolean(KEY_PENDING_REPORT, false).commit();
            }
            return pending;
        } catch (Throwable error) {
            Log.e(TAG, "Could not read pending diagnostic notice", error);
            return false;
        }
    }

    /** Dismisses the startup notice when the user opens diagnostics manually. */
    public static void dismissPendingReport() {
        Context context = appContext;
        if (context != null) {
            try {
                preferences(context).edit().putBoolean(KEY_PENDING_REPORT, false).apply();
            } catch (Throwable error) {
                Log.e(TAG, "Could not dismiss pending diagnostic notice", error);
            }
        }
    }

    /** Android 11+ APIs are kept in a separate class so older Android versions can load this class. */
    private static final class Api30 {

        private Api30() {
        }

        /** Returns a one line summary of the newest reportable exit, or "". */
        static String inspect(Context context) {
            android.app.ActivityManager manager = (android.app.ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return "";
            }
            List<android.app.ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 12);
            if (exits == null || exits.isEmpty()) {
                return "";
            }
            SharedPreferences prefs = preferences(context);
            long lastTimestamp = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L);
            long newestTimestamp = lastTimestamp;
            List<android.app.ApplicationExitInfo> fresh = new ArrayList<>();
            for (android.app.ApplicationExitInfo exit : exits) {
                if (exit == null) {
                    continue;
                }
                long timestamp = exit.getTimestamp();
                if (timestamp > lastTimestamp) {
                    fresh.add(exit);
                    newestTimestamp = Math.max(newestTimestamp, timestamp);
                }
            }
            if (fresh.isEmpty()) {
                return "";
            }
            Collections.sort(fresh, new Comparator<android.app.ApplicationExitInfo>() {
                @Override
                public int compare(android.app.ApplicationExitInfo left,
                                   android.app.ApplicationExitInfo right) {
                    return Long.compare(left.getTimestamp(), right.getTimestamp());
                }
            });
            String summary = "";
            for (android.app.ApplicationExitInfo exit : fresh) {
                if (!isReportableExit(exit.getReason())) {
                    continue;
                }
                summary = exitReasonName(exit.getReason()) + " at " + formatTime(exit.getTimestamp())
                        + " (process " + value(exit.getProcessName(), "unknown") + ")";
                markPendingReport(context);
                appendEntry(context, "ANDROID PROCESS EXIT",
                        exitReasonName(exit.getReason()) + " (reason " + exit.getReason() + ")",
                        exitDetails(exit), "Android system", true);
            }
            prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, newestTimestamp).commit();
            return summary;
        }

        private static boolean isReportableExit(int reason) {
            return reason == android.app.ApplicationExitInfo.REASON_CRASH
                    || reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
                    || reason == android.app.ApplicationExitInfo.REASON_ANR
                    || reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY
                    || reason == android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                    || reason == android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                    || reason == android.app.ApplicationExitInfo.REASON_SIGNALED;
        }

        private static String exitReasonName(int reason) {
            switch (reason) {
                case android.app.ApplicationExitInfo.REASON_CRASH:
                    return "Java crash";
                case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE:
                    return "Native crash";
                case android.app.ApplicationExitInfo.REASON_ANR:
                    return "Application not responding (ANR)";
                case android.app.ApplicationExitInfo.REASON_LOW_MEMORY:
                    return "Process killed for low memory";
                case android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                    return "Process initialization failure";
                case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                    return "Process stopped for excessive resource use";
                case android.app.ApplicationExitInfo.REASON_SIGNALED:
                    return "Process terminated by signal";
                default:
                    return "Unexpected process exit";
            }
        }

        private static String exitDetails(android.app.ApplicationExitInfo exit) {
            StringBuilder details = new StringBuilder();
            details.append("Time: ").append(formatTime(exit.getTimestamp())).append('\n');
            details.append("Process: ").append(value(exit.getProcessName(), "unknown")).append('\n');
            details.append("Exit status: ").append(exit.getStatus()).append('\n');
            details.append("PSS KB: ").append(exit.getPss()).append(" · RSS KB: ")
                    .append(exit.getRss()).append('\n');
            String description = exit.getDescription();
            if (description != null && !description.trim().isEmpty()) {
                details.append("Description: ").append(description.trim()).append('\n');
            }
            String trace = readExitTrace(exit);
            if (!trace.isEmpty()) {
                details.append("\nSystem-provided trace:\n").append(trace);
            }
            return details.toString();
        }

        private static String readExitTrace(android.app.ApplicationExitInfo exit) {
            try (InputStream input = exit.getTraceInputStream()) {
                if (input == null) {
                    return "";
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int total = 0;
                int read;
                while (total < MAX_EXIT_TRACE_BYTES
                        && (read = input.read(buffer, 0,
                        Math.min(buffer.length, MAX_EXIT_TRACE_BYTES - total))) != -1) {
                    output.write(buffer, 0, read);
                    total += read;
                }
                String result = new String(output.toByteArray(), StandardCharsets.UTF_8);
                if (total >= MAX_EXIT_TRACE_BYTES) {
                    result += "\n[system trace truncated]\n";
                }
                return result;
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    private static void appendEntry(Context context, String type, String title, String details,
                                    String threadName, boolean includeEnvironment) {
        if (context == null) {
            return;
        }
        StringBuilder entry = new StringBuilder(2048);
        entry.append("\n==================== ").append(type).append(" ====================\n");
        entry.append("Time: ").append(formatTime(System.currentTimeMillis())).append('\n');
        entry.append("Issue: ").append(value(title, "Unspecified error")).append('\n');
        entry.append("Thread: ").append(value(threadName, "unknown")).append('\n');
        if (includeEnvironment) {
            entry.append(environment(context));
        }
        if (details != null && !details.isEmpty()) {
            entry.append("\nDetails:\n").append(details);
            if (!details.endsWith("\n")) {
                entry.append('\n');
            }
        }
        entry.append("============================================================\n");
        appendBytes(context, entry.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String environment(Context context) {
        StringBuilder result = new StringBuilder();
        result.append("App: ").append(context.getPackageName()).append(" ")
                .append(BuildConfig.VERSION_NAME).append('\n');
        result.append("Android: ").append(value(Build.VERSION.RELEASE, "unknown"))
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        result.append("Device: ").append(value(Build.MANUFACTURER, "unknown"))
                .append(' ').append(value(Build.MODEL, "unknown"))
                .append(" [").append(value(Build.DEVICE, "unknown")).append("]\n");
        try {
            RootShell root = RootShell.get();
            result.append("Root access: ").append(root.isRootGranted()
                    ? "granted" : "not granted / unavailable").append('\n');
            result.append("Root shell: ").append(root.hasLiveSession()
                            ? "shared session alive" : "no session")
                    .append(" · su spawns ").append(root.suInvocations())
                    .append(" · manager ").append(root.manager()).append('\n');
        } catch (Throwable ignored) {
            result.append("Root access: unavailable\n");
        }
        try {
            com.applens.monitor.monitor.MonitorHub hub = com.applens.monitor.monitor.MonitorHub.get();
            result.append("Monitoring: ").append(hub.isActive()
                    ? value(hub.pkg, "unknown package") : "idle").append('\n');
        } catch (Throwable ignored) {
            // the hub is optional context
        }
        return result.toString();
    }

    private static String stackTrace(Throwable error) {
        if (error == null) {
            return "No exception details were provided.";
        }
        try {
            StringWriter text = new StringWriter();
            PrintWriter writer = new PrintWriter(text);
            error.printStackTrace(writer);
            writer.flush();
            String result = text.toString();
            if (result.length() > MAX_ENTRY_BYTES / 2) {
                result = result.substring(0, MAX_ENTRY_BYTES / 2)
                        + "\n[stack trace truncated]\n";
            }
            return result;
        } catch (Throwable ignored) {
            return error.getClass().getName() + ": " + value(error.getMessage(), "no message");
        }
    }

    private static void appendBytes(Context context, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        byte[] entry = bytes;
        if (entry.length > MAX_ENTRY_BYTES) {
            String head = new String(entry, 0, Math.min(24 * 1024, entry.length),
                    StandardCharsets.UTF_8);
            String tail = "\n[diagnostic entry truncated to protect local storage]\n";
            entry = (head + tail).getBytes(StandardCharsets.UTF_8);
        }
        File file = diagnosticFile(context);
        synchronized (FILE_LOCK) {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    return;
                }
                long nextSize = file.length() + entry.length;
                if (nextSize > MAX_LOG_BYTES) {
                    int keep = (int) Math.max(0, MAX_LOG_BYTES - entry.length - 96);
                    byte[] tail = file.isFile() ? readTail(file, keep) : new byte[0];
                    int start = firstWholeLineOffset(tail);
                    try (FileOutputStream output = new FileOutputStream(file, false)) {
                        output.write("[Older diagnostic entries trimmed]\n"
                                .getBytes(StandardCharsets.UTF_8));
                        if (start < tail.length) {
                            output.write(tail, start, tail.length - start);
                        }
                        output.write(entry);
                        output.getFD().sync();
                    }
                } else {
                    try (FileOutputStream output = new FileOutputStream(file, true)) {
                        output.write(entry);
                        output.getFD().sync();
                    }
                }
            } catch (Throwable error) {
                Log.e(TAG, "Could not append diagnostic report", error);
            }
        }
    }

    private static byte[] readTail(File file, int maxBytes) throws Exception {
        if (maxBytes <= 0 || !file.isFile()) {
            return new byte[0];
        }
        long length = file.length();
        int count = (int) Math.min(length, maxBytes);
        byte[] data = new byte[count];
        try (FileInputStream input = new FileInputStream(file)) {
            long skip = Math.max(0L, length - count);
            while (skip > 0) {
                long skipped = input.skip(skip);
                if (skipped <= 0) {
                    break;
                }
                skip -= skipped;
            }
            int offset = 0;
            while (offset < count) {
                int read = input.read(data, offset, count - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset == count) {
                return data;
            }
            byte[] actual = new byte[offset];
            System.arraycopy(data, 0, actual, 0, offset);
            return actual;
        }
    }

    private static int firstWholeLineOffset(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    private static File diagnosticFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void markPendingReport(Context context) {
        if (context != null) {
            try {
                preferences(context).edit().putBoolean(KEY_PENDING_REPORT, true).commit();
            } catch (Throwable error) {
                Log.e(TAG, "Could not mark pending diagnostic notice", error);
            }
        }
    }

    private static String formatTime(long timestamp) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
                    .format(new Date(timestamp));
        } catch (Throwable ignored) {
            return Long.toString(timestamp);
        }
    }

    private static String value(String text, String fallback) {
        return text == null || text.trim().isEmpty() ? fallback : text;
    }
}
