package com.applens.monitor.log;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.RootShell;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes every activity record to a plain text file on shared storage under
 * {@code /sdcard/AppLens/&lt;package&gt;/}. Root is used when available so the file
 * lands directly in the shared volume; otherwise the app falls back to its own
 * external files directory and (on Android 11+) asks for all-files access.
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

    private String currentPath = "";
    private String currentFallback;
    private boolean useRoot = true;
    private boolean enabled = true;
    private long writtenBytes;

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

    /** Creates a new record file for a monitoring session. */
    public synchronized String open(Context ctx, String pkg, String label) {
        String stamp = Fmt.stamp(System.currentTimeMillis());
        String safePkg = pkg == null || pkg.isEmpty() ? "unknown" : pkg.replaceAll("[^A-Za-z0-9._-]", "_");
        String fileName = "activity_" + stamp + ".txt";
        useRoot = RootShell.get().isRootGranted();
        if (useRoot) {
            String base = RootShell.get().externalStoragePath() + "/AppLens/" + safePkg;
            currentPath = base + "/" + fileName;
            currentFallback = null;
            RootShell.get().exec("mkdir -p " + RootShell.shQuote(base));
        } else {
            File dir = externalBase(ctx, safePkg);
            if (dir != null) {
                File file = new File(dir, fileName);
                currentFallback = file.getAbsolutePath();
            }
            currentPath = "";
        }
        if (path() == null) {
            enabled = false;
            return null;
        }
        writeHeader(ctx, pkg, label, stamp);
        return path();
    }

    private static File externalBase(Context ctx, String safePkg) {
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root != null && Environment.isExternalStorageManager()) {
                File dir = new File(new File(root, "AppLens"), safePkg);
                if (!dir.exists() && !dir.mkdirs()) {
                    return null;
                }
                return dir;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext == null) {
                return null;
            }
            File dir = new File(new File(ext, "AppLens"), safePkg);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            return dir;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void writeHeader(Context ctx, String pkg, String label, String stamp) {
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
        sb.append("Root            : ").append(RootShell.get().isRootGranted()
                ? "granted via " + RootShell.get().suPath() : "not granted").append('\n');
        sb.append("File            : ").append(stamp).append('\n');
        sb.append("---------------------------------------------------------------------\n");
        writeRaw(sb.toString());
        flush();
    }

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
            if (useRoot) {
                // Through the shared root shell: a dedicated `su` per flush meant a
                // superuser toast every 1.5 seconds for the whole session.
                if (!RootShell.get().appendText(currentPath, chunk, 15000)) {
                    RootShell.get().appendStdin(currentPath, data, 15000);
                }
            } else if (currentFallback != null) {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(currentFallback, true);
                try {
                    fos.write(data);
                    fos.flush();
                } finally {
                    fos.close();
                }
            }
            writtenBytes += data.length;
        } catch (Throwable ignored) {
            // logging must never crash monitoring
        } finally {
            flushing.set(false);
        }
    }

    /** Lists every record file AppLens has produced, newest first. */
    public static List<File> listRecords(Context ctx) {
        List<File> out = new ArrayList<>();
        try {
            File base = Environment.getExternalStorageDirectory();
            if (base != null) {
                File dir = new File(base, "AppLens");
                collect(dir, out);
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                collect(new File(ext, "AppLens"), out);
            }
        } catch (Throwable ignored) {
            // fall through
        }
        File own = new File(ctx.getFilesDir(), "AppLens");
        collect(own, out);
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    private static void collect(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File f : kids) {
            if (f.isFile() && f.getName().endsWith(".txt")) {
                out.add(f);
            } else if (f.isDirectory()) {
                collect(f, out);
            }
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

    /** Exports arbitrary text next to the records (used by the share action). */
    public static boolean export(Context ctx, String fileName, String content) {
        try {
            File dir = externalBase(ctx, "exports");
            if (dir == null) {
                return false;
            }
            File file = new File(dir, fileName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, false);
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
    }
}
