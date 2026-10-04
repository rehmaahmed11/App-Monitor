package com.applens.monitor.monitor;

import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.model.EventCategory;
import com.applens.monitor.model.EventItem;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns the system log into a privacy-relevant activity feed for the selected
 * application. {@code logcat} is read as root so lines belonging to the target can
 * be matched by pid, and system_server lines are matched by package name.
 */
public final class LogcatMonitor {

    /** One logcat signature → (category, human title). */
    private static final Object[][] SIGNATURES = {
            // {substring, category, title}
            {"ActivityTaskManager: START", EventCategory.ACTIVITY, "Activity started"},
            {"ActivityTaskManager: START u0", EventCategory.ACTIVITY, "Activity started"},
            {"Displayed ", EventCategory.ACTIVITY, "Activity displayed"},
            {"START u0 {", EventCategory.ACTIVITY, "Activity started"},
            {"wm_on_resume_called", EventCategory.ACTIVITY, "Activity resumed"},
            {"wm_on_pause_called", EventCategory.ACTIVITY, "Activity paused"},
            {"Start proc ", EventCategory.PROCESS, "Process started"},
            {"Killing ", EventCategory.PROCESS, "Process killed"},
            {"ANR in ", EventCategory.PERFORMANCE, "ANR detected"},
            {"am_proc_start", EventCategory.PROCESS, "Process start (ams)"},
            {"am_kill", EventCategory.PROCESS, "Process killed (ams)"},
            {"CameraService", EventCategory.CAMERA, "Camera service access"},
            {"CameraDevice", EventCategory.CAMERA, "Camera device open"},
            {"CameraManagerGlobal", EventCategory.CAMERA, "Camera enumeration"},
            {"ActivityManager: openCamera", EventCategory.CAMERA, "Camera opened"},
            {"MediaPlayer", EventCategory.MEDIA, "Media playback"},
            {"NuPlayer", EventCategory.MEDIA, "Media codec / playback"},
            {"MediaCodec", EventCategory.MEDIA, "Media codec"},
            {"AudioRecord", EventCategory.MICROPHONE, "Audio recording"},
            {"AudioFlinger", EventCategory.MICROPHONE, "Audio HAL client"},
            {"AAudioStream", EventCategory.MICROPHONE, "AAudio stream"},
            {"AudioTrack", EventCategory.MICROPHONE, "Audio playback"},
            {"GnssLocationProvider", EventCategory.LOCATION, "GNSS fix"},
            {"LocationManagerService", EventCategory.LOCATION, "Location request"},
            {"FusedLocationProvider", EventCategory.LOCATION, "Fused location request"},
            {"LocationManager", EventCategory.LOCATION, "Location API"},
            {"ClipboardService", EventCategory.CLIPBOARD, "Clipboard access"},
            {"SensorService", EventCategory.SENSORS, "Sensor access"},
            {"NotificationManagerService", EventCategory.NOTIFICATION, "Notification posted"},
            {"WebViewFactory", EventCategory.ACTIVITY, "WebView initialised"},
            {"chromium", EventCategory.ACTIVITY, "Web content"},
            {"AccountManager", EventCategory.SECURITY, "Account manager access"},
            {"ContentProvider", EventCategory.STORAGE, "Content provider access"},
            {"SQLiteDatabase", EventCategory.DATABASE, "SQLite activity"},
            {"StrictMode", EventCategory.SECURITY, "StrictMode violation"},
            {"PackageManagerService", EventCategory.SYSTEM, "Package query"},
            {"ConnectivityService", EventCategory.NETWORK, "Connectivity change"},
            {"TrafficStats", EventCategory.NETWORK, "Traffic statistics"},
            {"JobService", EventCategory.PROCESS, "Job scheduled"},
            {"WakeLock", EventCategory.SYSTEM, "Wake lock"},
            {"installPackage", EventCategory.SECURITY, "Package install"},
            {"deletePackage", EventCategory.SECURITY, "Package removal"},
            {"permission", EventCategory.SECURITY, "Permission activity"},
            {"HttpURLConnection", EventCategory.NETWORK, "HTTP request"},
            {"okhttp", EventCategory.NETWORK, "HTTP request"},
            {"SSL", EventCategory.SECURITY, "TLS handshake"},
            {"FirebaseMessaging", EventCategory.NETWORK, "Push message"},
            {"GCM", EventCategory.NETWORK, "Push message"},
    };

    /** Categories worth interrupting the dashboard for. */
    private static final Set<EventCategory> IMPORTANT = java.util.Collections.unmodifiableSet(
            java.util.EnumSet.of(EventCategory.LOCATION, EventCategory.CAMERA,
                    EventCategory.MICROPHONE, EventCategory.CONTACTS, EventCategory.PHONE,
                    EventCategory.CLIPBOARD, EventCategory.SECURITY, EventCategory.SENSORS));

    private final String pkg;
    private final ActivitySink sink;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> seenProcessLines = ConcurrentHashMap.newKeySet();

    private Process process;

    public interface ActivitySink {
        void onEvent(EventItem event);
    }

    public LogcatMonitor(String pkg, ActivitySink sink) {
        this.pkg = pkg;
        this.sink = sink;
    }

    public boolean isSupported() {
        return RootShell.get().logcat() != null;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        String binary = RootShell.get().logcat();
        if (binary == null) {
            running.set(false);
            return;
        }
        // "-T 1" starts at the end of the buffer. Without it logcat replays every
        // line already in main+system+events — tens of thousands of them — as fast
        // as the pipe allows, which flooded the activity feed the moment a session
        // started and left the UI thread with no chance to keep up.
        String cmd = binary + " -v threadtime -T 1 -b main -b system -b events";
        process = RootShell.get().startStream(cmd, new RootShell.LineSink() {
            @Override
            public void onLine(String line) {
                handle(line);
            }
        });
        if (process == null) {
            running.set(false);
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
        if (line == null || line.isEmpty() || !running.get()) {
            return;
        }
        int pid = pidOf(line);
        boolean ours = pid > 0 && pids.contains(pid);
        boolean mentions = line.contains(pkg);
        if (!ours && !mentions) {
            return;
        }
        // de-duplicate spammy lines
        String fingerprint = line.length() > 96 ? line.substring(0, 96) : line;
        if (!seenProcessLines.add(fingerprint + "@" + (pid > 0 ? pid : 0))) {
            return;
        }
        if (seenProcessLines.size() > 3000) {
            seenProcessLines.clear();
        }
        Object[] match = classify(line);
        EventItem event = new EventItem();
        event.time = nowFromLogcat(line);
        event.pid = pid;
        event.pkg = pkg;
        if (match == null) {
            event.category = EventCategory.SYSTEM;
            event.title = "Log";
            event.detail = Fmt.limit(line, 180);
            event.source = "logcat";
        } else {
            event.category = (EventCategory) match[0];
            event.title = (String) match[1];
            event.detail = Fmt.limit(line, 180);
            event.source = "logcat:" + tagOf(line);
            // "important" forces a full dashboard repaint, so it is reserved for
            // the privacy-relevant categories rather than every matched line.
            event.important = IMPORTANT.contains(event.category);
        }
        if (sink != null) {
            sink.onEvent(event);
        }
    }

    /** pids currently owned by the monitored application. */
    public final Set<Integer> pids = ConcurrentHashMap.newKeySet();

    public void setPids(Set<Integer> newPids) {
        pids.clear();
        if (newPids != null) {
            pids.addAll(newPids);
        }
    }

    private static Object[] classify(String line) {
        for (Object[] sig : SIGNATURES) {
            if (line.contains((String) sig[0])) {
                return sig;
            }
        }
        return null;
    }

    /** Extracts the pid from a threadtime formatted logcat line. */
    static int pidOf(String line) {
        int first = line.indexOf(' ');
        if (first < 0) {
            return 0;
        }
        int second = line.indexOf(' ', first + 1);
        if (second < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(line.substring(first + 1, second).trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String tagOf(String line) {
        int first = line.indexOf(' ');
        if (first < 0) {
            return "?";
        }
        int second = line.indexOf(' ', first + 1);
        if (second < 0) {
            return "?";
        }
        int third = line.indexOf(' ', second + 1);
        if (third < 0) {
            return "?";
        }
        int fourth = line.indexOf(' ', third + 1);
        if (fourth < 0) {
            return "?";
        }
        return line.substring(third + 1, fourth).trim();
    }

    private static long nowFromLogcat(String line) {
        // "MM-dd HH:mm:ss.mmm  pid tid ..."
        int first = line.indexOf(' ');
        if (first > 0) {
            String stamp = line.substring(0, first);
            String today = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.US)
                    .format(new java.util.Date());
            try {
                long t = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                        .parse(today + " " + stamp).getTime();
                if (t > 0) {
                    return t;
                }
            } catch (Throwable ignored) {
                // fall back to now
            }
        }
        return System.currentTimeMillis();
    }

}
