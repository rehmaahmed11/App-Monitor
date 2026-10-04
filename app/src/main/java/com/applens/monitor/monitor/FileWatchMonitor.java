package com.applens.monitor.monitor;

import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;
import com.applens.monitor.model.EventCategory;
import com.applens.monitor.model.EventItem;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches the monitored application's private storage with root {@code inotifyd}
 * (or a polling fallback) and turns every create / write / delete into an activity
 * event, which is how database writes, cache abuse and media downloads surface.
 */
public final class FileWatchMonitor {

    private static final String[] EVENTS = {
            "CREATE", "MODIFY", "CLOSE_WRITE", "DELETE", "MOVED_FROM", "MOVED_TO",
            "ATTRIB", "DELETE_SELF", "MOVE_SELF", "ACCESS",
    };

    private final String pkg;
    private final String[] paths;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ActivitySink sink;
    private Process process;

    public interface ActivitySink {
        void onEvent(EventItem event);
    }

    public FileWatchMonitor(String pkg, String dataDir, ActivitySink sink) {
        this.pkg = pkg;
        this.sink = sink;
        this.paths = new String[]{
                dataDir,
                "/sdcard/Android/data/" + pkg,
                "/sdcard/Android/media/" + pkg,
        };
    }

    public boolean isSupported() {
        return RootShell.get().inotifyd() != null;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        String binary = RootShell.get().inotifyd();
        if (binary == null) {
            return;
        }
        StringBuilder cmd = new StringBuilder();
        for (String path : paths) {
            if (path == null || path.isEmpty()) {
                continue;
            }
            if (cmd.length() > 0) {
                cmd.append(" ; ");
            }
            cmd.append(binary).append(" ").append(RootShell.shQuote(path))
                    .append(" m,e,w,x,y 2>/dev/null & ");
        }
        cmd.append("wait");
        process = RootShell.get().startStream(cmd.toString(), new RootShell.LineSink() {
            @Override
            public void onLine(String line) {
                handle(line);
            }
        });
        if (process != null) {
            MonitorHub.get().publish(EventItem.of(EventCategory.STORAGE, "File watch active",
                    "inotify on " + paths.length + " director(ies)", "inotifyd"));
        }
    }

    public void stop() {
        running.set(false);
        if (process != null) {
            try {
                process.destroy();
            } catch (Throwable ignored) {
                // noop
            }
            process = null;
        }
    }

    private void handle(String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        String event = detectEvent(line);
        String[] parts = line.trim().split("\\s+");
        String file = parts.length > 0 ? parts[parts.length - 1] : line;
        if (file.startsWith("/") && !file.contains(" ")) {
            // the whole line is the path when inotifyd prints "%w %e %f" with an empty name
            file = file;
        }
        EventItem e = EventItem.of(EventCategory.STORAGE, "File " + event.toLowerCase(Locale.US), file, "inotifyd");
        e.pkg = pkg;
        if (looksLikeDatabase(file)) {
            e.category = EventCategory.DATABASE;
            e.title = "Database write";
        } else if (file.contains("/cache/") || file.contains("/code_cache/")) {
            e.title = "Cache write";
        }
        MonitorHub.get().addFileEvents(1);
        if (sink != null) {
            sink.onEvent(e);
        }
        ActivityLogWriter.get().writeRaw(com.applens.monitor.core.Fmt.clock(e.time) + "  " + e.toLogLine(pkg));
    }

    private static boolean looksLikeDatabase(String file) {
        String f = file.toLowerCase(Locale.US);
        return f.contains("/databases/") || f.endsWith(".db") || f.endsWith(".db-wal")
                || f.endsWith(".db-shm") || f.endsWith(".sqlite");
    }

    private static String detectEvent(String line) {
        String upper = line.toUpperCase(Locale.US);
        for (String candidate : EVENTS) {
            if (upper.contains(candidate)) {
                return candidate;
            }
        }
        return "MODIFY";
    }
}
