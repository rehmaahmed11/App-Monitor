package com.applens.monitor.log;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.LogSettings;
import com.applens.monitor.core.RootShell;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes every activity record to a plain text file in the folder the user chose
 * (default {@code /sdcard/AppLens/<package>/activity_*.txt}).
 *
 * <p>Root is used when available so the file lands directly in the shared volume;
 * otherwise the writer falls back — in order — to the chosen folder through the
 * ordinary file API, to the app's own external files directory and finally to its
 * internal storage. Every candidate is <em>verified</em> by flushing the header and
 * checking that bytes really reached the disk, so "the report file was created" is
 * a statement the app can back up instead of an assumption.</p>
 */
public final class ActivityLogWriter {

    /** Hard cap on buffered, not yet written text. */
    private static final int MAX_BUFFER_BYTES = 1024 * 1024;

    private static volatile ActivityLogWriter instance;

    private final StringBuilder buffer = new StringBuilder();
    private final Object lock = new Object();
    private final ScheduledExecutorService flusher =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "applens-log");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    private Context appContext;
    private String currentPath = "";
    private String currentFallback;
    private String statusNote = "";
    private String configuredFolder = LogSettings.DEFAULT_FOLDER;
    private boolean useRoot = true;
    private boolean enabled = true;
    private long writtenBytes;
    private long lastWriteFailure;

    private ActivityLogWriter() {
        flusher.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                flush();
            }
        }, 1500, 1500, TimeUnit.MILLISECONDS);
    }

    public static ActivityLogWriter get() {
        ActivityLogWriter local = instance;
        if (local == null) {
            synchronized (ActivityLogWriter.class) {
                local = instance;
                if (local == null) {
                    local = new ActivityLogWriter();
                    instance = local;
                }
            }
        }
        return local;
    }

    public boolean isOpen() {
        return !currentPath.isEmpty();
    }

    public String path() {
        return currentPath.isEmpty() ? currentFallback : currentPath;
    }

    public long writtenBytes() {
        return writtenBytes;
    }

    /** Human readable sentence about where the record really goes, for the UI. */
    public String status() {
        return statusNote;
    }

    /** True when the record is being written to a usable file. */
    public boolean isSaving() {
        return enabled && path() != null && !path().isEmpty();
    }

    public String configuredFolder() {
        return configuredFolder;
    }

    // ------------------------------------------------------------------
    // Session files
    // ------------------------------------------------------------------

    /** A place a record file could live, in the order they are tried. */
    private static final class Candidate {
        final String folder;
        final boolean viaRoot;
        final String note;

        Candidate(String folder, boolean viaRoot, String note) {
            this.folder = folder;
            this.viaRoot = viaRoot;
            this.note = note;
        }
    }

    /**
     * Opens a new record file for a monitoring session and returns its path, or
     * {@code null} when the report file is disabled / nothing is writable.
     */
    public synchronized String open(Context ctx, String pkg, String label) {
        if (ctx != null) {
            appContext = ctx.getApplicationContext();
        }
        currentPath = "";
        currentFallback = null;
        writtenBytes = 0;
        statusNote = "";
        enabled = LogSettings.reportEnabled();
        configuredFolder = LogSettings.folder();
        if (!enabled) {
            statusNote = "report file disabled in settings";
            return null;
        }
        String stamp = Fmt.stamp(System.currentTimeMillis());
        String safePkg = LogSettings.safeName(pkg);
        String fileName = "activity_" + stamp + ".txt";
        String header = buildHeader(ctx, pkg, label, stamp);

        List<Candidate> candidates = candidates(safePkg, fileName);
        for (Candidate candidate : candidates) {
            currentPath = "";
            currentFallback = null;
            useRoot = candidate.viaRoot;
            if (candidate.viaRoot) {
                currentPath = candidate.folder + "/" + fileName;
            } else {
                currentFallback = candidate.folder + "/" + fileName;
            }
            // The header is written synchronously and the result checked: opening a
            // record file must not quietly succeed into a folder that is not
            // writable (which is how an "empty report" happens).
            if (writeNow(header) && verify()) {
                statusNote = candidate.note;
                writtenBytes += header.getBytes(StandardCharsets.UTF_8).length;
                return path();
            }
        }
        enabled = false;
        statusNote = "no writable folder — report file not created";
        currentPath = "";
        currentFallback = null;
        return null;
    }

    private List<Candidate> candidates(String safePkg, String fileName) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean root = RootShell.get().isRootGranted() || RootShell.get().ensureRoot();
        String folder = configuredFolder;
        String perApp = LogSettings.folderPerApp() ? folder + "/" + safePkg : folder;
        if (root) {
            addCandidate(out, seen, perApp, true,
                    "writing to the folder you chose (" + perApp + ") via root");
            addCandidate(out, seen, LogSettings.DEFAULT_FOLDER + "/" + safePkg, true,
                    "the folder you chose was not writable — fell back to "
                            + LogSettings.DEFAULT_FOLDER + " via root");
        }
        File external = null;
        try {
            external = appContext == null ? null : appContext.getExternalFilesDir(null);
        } catch (Throwable ignored) {
            // no external storage
        }
        if (external != null) {
            addCandidate(out, seen, new File(new File(external, "AppLens"), safePkg).getAbsolutePath(),
                    false, "root is unavailable — the record goes to the app's own external folder");
        }
        try {
            if (appContext != null) {
                addCandidate(out, seen, new File(new File(appContext.getFilesDir(), "AppLens"),
                        safePkg).getAbsolutePath(), false,
                        "no external storage available — the record goes to app storage");
            }
        } catch (Throwable ignored) {
            // no internal storage either
        }
        return out;
    }

    private static void addCandidate(List<Candidate> out, Set<String> seen, String folder,
                                     boolean viaRoot, String note) {
        if (folder == null || folder.isEmpty() || !seen.add(folder)) {
            return;
        }
        out.add(new Candidate(folder, viaRoot, note));
    }

    /**
     * Writes text straight to the current target (root first, then the app-visible
     * copy) and reports whether it really went out. Used for the header and the
     * folder probe, where "it probably worked" is not good enough.
     */
    private boolean writeNow(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try {
            if (useRoot) {
                if (RootShell.get().appendText(currentPath, text, 20000)
                        || RootShell.get().appendStdin(currentPath, data, 20000)) {
                    return true;
                }
                return false;
            }
            if (currentFallback == null) {
                return false;
            }
            File file = new File(currentFallback);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return false;
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, true);
            try {
                fos.write(data);
                fos.flush();
            } finally {
                fos.close();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Confirms that the bytes really arrived on disk. */
    private boolean verify() {
        String target = path();
        if (target == null || target.isEmpty()) {
            return false;
        }
        if (useRoot) {
            return RootShell.get().fileSize(target) > 0;
        }
        try {
            File file = new File(target);
            return file.exists() && file.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String buildHeader(Context ctx, String pkg, String label, String stamp) {
        StringBuilder sb = new StringBuilder();
        sb.append("=====================================================================\n");
        sb.append(" AppLens activity record\n");
        sb.append("=====================================================================\n");
        sb.append("Session start   : ").append(Fmt.dateTime(System.currentTimeMillis())).append('\n');
        sb.append("Application     : ").append(Fmt.nz(label, "")).append('\n');
        sb.append("Package         : ").append(Fmt.nz(pkg, "")).append('\n');
        sb.append("Device          : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append('\n');
        sb.append("Android         : ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Build           : ").append(Build.FINGERPRINT).append('\n');
        sb.append("Record file     : ").append(path()).append('\n');
        sb.append("Output folder   : ").append(LogSettings.folder())
                .append(LogSettings.folderPerApp() ? " (one folder per app)" : "").append('\n');
        sb.append("Full report     : ").append(LogSettings.fullReport()
                ? "yes — snapshot at start, summary at stop" : "off (events only)").append('\n');
        sb.append("Root            : ").append(RootShell.get().isRootGranted()
                ? "granted via " + RootShell.get().suPath() : "not granted").append('\n');
        sb.append("File stamp      : ").append(stamp).append('\n');
        sb.append("---------------------------------------------------------------------\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    public void writeRaw(String line) {
        if (!enabled || line == null) {
            return;
        }
        boolean flushNow = false;
        synchronized (lock) {
            buffer.append(line);
            if (!line.endsWith("\n")) {
                buffer.append('\n');
            }
            if (buffer.length() > MAX_BUFFER_BYTES) {
                // The writer is not keeping up (no root, storage gone). Drop the
                // oldest half rather than growing until the process is killed.
                buffer.delete(0, buffer.length() - MAX_BUFFER_BYTES / 2);
            }
            flushNow = buffer.length() > 48 * 1024;
        }
        if (flushNow) {
            // Never flush on the caller's thread: writeRaw() is called from the UI
            // and from every sampler, and a flush goes through root.
            try {
                flusher.execute(new Runnable() {
                    @Override
                    public void run() {
                        flush();
                    }
                });
            } catch (Throwable ignored) {
                // the periodic flusher will pick it up
            }
        }
    }

    public void write(com.applens.monitor.model.EventItem event, String pkg) {
        writeRaw(event.toLogLine(pkg));
    }

    /** Appends everything buffered to disk. Safe to call from any thread. */
    public void flush() {
        if (!enabled || path() == null) {
            return;
        }
        String chunk;
        synchronized (lock) {
            if (buffer.length() == 0) {
                return;
            }
            chunk = buffer.toString();
            buffer.setLength(0);
        }
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            byte[] data = chunk.getBytes(StandardCharsets.UTF_8);
            boolean ok = false;
            if (useRoot) {
                // Through the shared root shell: a dedicated `su` per flush meant a
                // superuser toast every 1.5 seconds for the whole session.
                ok = RootShell.get().appendText(currentPath, chunk, 15000)
                        || RootShell.get().appendStdin(currentPath, data, 15000);
                if (!ok) {
                    // Root went away mid-session: keep the record by switching to
                    // the app-visible copy when there is one.
                    if (currentFallback != null) {
                        useRoot = false;
                    } else {
                        noteWriteFailure("root write to " + currentPath + " failed");
                    }
                }
            }
            if (!ok && !useRoot && currentFallback != null) {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(currentFallback, true);
                try {
                    fos.write(data);
                    fos.flush();
                    ok = true;
                } finally {
                    fos.close();
                }
            }
            if (ok) {
                writtenBytes += data.length;
            }
        } catch (Throwable error) {
            noteWriteFailure("record write failed: " + LogSettings.describe(error));
        } finally {
            flushing.set(false);
        }
    }

    /** Records a write problem once every five minutes instead of per flush. */
    private void noteWriteFailure(String message) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (now - lastWriteFailure < 300000L) {
                return;
            }
            lastWriteFailure = now;
        }
        statusNote = message;
        try {
            DiagnosticLog.recordThrottledProblem("record-write", message,
                    new java.io.IOException(message));
        } catch (Throwable ignored) {
            // diagnostics must never break writing
        }
    }

    // ------------------------------------------------------------------
    // Reading back
    // ------------------------------------------------------------------

    /**
     * Every record file AppLens produced, newest first. Files written through root
     * are enumerated with root too, so records still show up when the app itself
     * cannot list the shared folder (Android 11+ without all-files access).
     */
    public static List<File> listRecords(Context ctx) {
        List<File> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            File base = Environment.getExternalStorageDirectory();
            if (base != null) {
                collect(new File(base, "AppLens"), out, seen);
            }
        } catch (Throwable ignored) {
            // fall through
        }
        String configured = LogSettings.folder();
        if (!configured.isEmpty()) {
            collect(new File(configured), out, seen);
        }
        try {
            if (ctx != null) {
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) {
                    collect(new File(ext, "AppLens"), out, seen);
                }
                collect(new File(ctx.getFilesDir(), "AppLens"), out, seen);
            }
        } catch (Throwable ignored) {
            // fall through
        }
        collectViaRoot(new File(configured), out, seen);
        collectViaRoot(new File(LogSettings.DEFAULT_FOLDER), out, seen);
        Collections.sort(out, (a, b) -> Long.compare(sortKey(b), sortKey(a)));
        return out;
    }

    /**
     * Files enumerated through root do not carry a usable modification time in the
     * app process, so the timestamp embedded in the record name is used instead.
     */
    private static long sortKey(File file) {
        long modified = file.lastModified();
        if (modified > 0) {
            return modified;
        }
        String name = file.getName();
        if (name.startsWith("activity_") && name.length() >= 25) {
            try {
                java.text.SimpleDateFormat format =
                        new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US);
                java.util.Date date = format.parse(name.substring("activity_".length(),
                        "activity_".length() + 19));
                if (date != null) {
                    return date.getTime();
                }
            } catch (Throwable ignored) {
                // fall through to 0
            }
        }
        return 0;
    }

    private static void collect(File dir, List<File> out, Set<String> seen) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File f : kids) {
            if (f.isFile() && f.getName().endsWith(".txt")) {
                if (seen.add(f.getAbsolutePath())) {
                    out.add(f);
                }
            } else if (f.isDirectory()) {
                collect(f, out, seen);
            }
        }
    }

    /**
     * Adds the files root can see. {@code ls -ln} is used because it prints one
     * line per file with a numeric owner and a stable column order, which is what
     * makes parsing it safe.
     */
    private static void collectViaRoot(File dir, List<File> out, Set<String> seen) {
        if (dir == null || dir.getAbsolutePath().isEmpty()) {
            return;
        }
        RootShell root = RootShell.get();
        if (!root.isRootGranted()) {
            return;
        }
        try {
            String listing = root.exec("ls -ln " + RootShell.shQuote(dir.getAbsolutePath())
                    + "/*/*.txt " + RootShell.shQuote(dir.getAbsolutePath())
                    + "/*.txt 2>/dev/null", 12000);
            if (listing == null || listing.isEmpty()) {
                return;
            }
            for (String line : listing.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("total ")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 9 || !parts[0].startsWith("-")) {
                    continue;
                }
                long size;
                try {
                    size = Long.parseLong(parts[4]);
                } catch (Throwable ignored) {
                    continue;
                }
                String path = parts[parts.length - 1];
                if (!path.startsWith("/") || !path.endsWith(".txt")) {
                    continue;
                }
                if (size <= 0 || !seen.add(path)) {
                    continue;
                }
                out.add(new File(path));
            }
        } catch (Throwable ignored) {
            // listing through root is best effort
        }
    }

    public static String readText(File file, int maxBytes) {
        try {
            byte[] raw = new byte[(int) Math.min(maxBytes, Math.max(1, file.length()))];
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            try {
                int n = fis.read(raw);
                return new String(raw, 0, Math.max(0, n), StandardCharsets.UTF_8);
            } finally {
                fis.close();
            }
        } catch (Throwable t) {
            if (RootShell.get().isRootGranted()) {
                String s = RootShell.get().readFile(file.getAbsolutePath());
                if (s != null) {
                    return s.length() > maxBytes ? s.substring(0, maxBytes) : s;
                }
            }
            return "";
        }
    }

    /**
     * Returns a file the app process can open, copying a root-only record into the
     * app's external files directory when necessary (sharing and opening a record
     * must work on Android 11+ without all-files access too).
     */
    public static File readableForShare(Context ctx, File file) {
        if (file == null) {
            return null;
        }
        try {
            if (file.canRead()) {
                return file;
            }
        } catch (Throwable ignored) {
            // fall through to the root copy
        }
        RootShell root = RootShell.get();
        if (!root.isRootGranted() || root.readFile(file.getAbsolutePath()) == null) {
            return file;
        }
        try {
            File dir = new File(ctx.getExternalFilesDir(null), "share");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return file;
            }
            File copy = new File(dir, file.getName());
            String content = root.readFile(file.getAbsolutePath());
            java.io.FileOutputStream fos = new java.io.FileOutputStream(copy, false);
            try {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            } finally {
                fos.close();
            }
            return copy;
        } catch (Throwable ignored) {
            return file;
        }
    }

    /** Exports arbitrary text next to the records (used by the share action). */
    public static boolean export(Context ctx, String fileName, String content) {
        try {
            String folder = LogSettings.folder();
            RootShell root = RootShell.get();
            if (root.isRootGranted()) {
                RootShell.Check check = root.makeFolder(folder);
                if (check.ok) {
                    return root.writeFile(folder + "/" + fileName, content);
                }
            }
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                return false;
            }
            File target = new File(new File(dir, "exports"), fileName);
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) {
                return false;
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(target, false);
            try {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            } finally {
                fos.close();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static ByteArrayOutputStream toStream(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        out.write(raw, 0, raw.length);
        return out;
    }

    public synchronized void close() {
        flush();
        currentPath = "";
        currentFallback = null;
    }
}
