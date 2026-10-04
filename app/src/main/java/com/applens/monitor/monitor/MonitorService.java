package com.applens.monitor.monitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.LogSettings;
import com.applens.monitor.core.Prefs;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;
import com.applens.monitor.log.DiagnosticLog;
import com.applens.monitor.model.EventCategory;
import com.applens.monitor.model.EventItem;
import com.applens.monitor.ui.DetailActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Foreground service that keeps a monitoring session alive while the user browses
 * other screens. Also owns the DNS capture VPN lifecycle.
 *
 * <p><b>One request, one lifecycle.</b> Starting a session while another one runs
 * (or while a stop is still being processed) used to be expressed as two intents,
 * "stop" followed by "start", which raced with the service's own destruction: the
 * stop's {@code stopSelf()} could take the freshly started session down with it,
 * and {@code onDestroy()} stopped whichever engine happened to be current. Every
 * request now carries a sequence number; a request only acts when it is still the
 * newest one, teardown uses {@code stopSelf(startId)} so a newer start survives,
 * and a session that is killed by the system is picked back up instead of being
 * silently dropped after a few seconds.</p>
 */
public class MonitorService extends Service {

    public static final String ACTION_START = "com.applens.monitor.START";
    public static final String ACTION_STOP = "com.applens.monitor.STOP";
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_UID = "uid";
    public static final String EXTRA_VPN = "vpn";
    public static final String EXTRA_LAUNCH = "launch";
    public static final String EXTRA_REASON = "reason";

    private static final String CHANNEL_ID = "applens_monitoring";
    private static final int NOTIFICATION_ID = 0xA11;
    /** A stored session older than this is not resumed after a process restart. */
    private static final long RESUME_WINDOW_MS = 6 * 60 * 60 * 1000L;

    private static volatile MonitorEngine engine;

    // ------------------------------------------------------------------
    // Request sequencing
    // ------------------------------------------------------------------

    private static final AtomicLong SEQUENCE = new AtomicLong();
    /** Id of the newest start/stop request; older requests abort when they differ. */
    private static volatile long newestRequest;

    /**
     * Every start and every stop runs here, in submission order. It is static and
     * never shut down on purpose: a "stop this session, start that one" tap
     * destroys and recreates the service, and the old session's teardown (root
     * streams, iptables chains) must still finish before the new session installs
     * its own. A per-instance executor let the two overlap.
     */
    private static final ExecutorService bootstrap =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "applens-bootstrap");
                t.setDaemon(true);
                return t;
            });

    private final Handler main = new Handler(Looper.getMainLooper());
    /** True once this instance asked to go away; used by onDestroy(). */
    private volatile boolean stopping;
    private int lastStartId;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            // Keep the record size in the shared state so the dashboard can show the
            // report growing while the session runs.
            try {
                MonitorHub.get().setRecordBytes(ActivityLogWriter.get().writtenBytes());
            } catch (Throwable ignored) {
                // the size is decorative
            }
            MonitorState state = MonitorHub.get().stateSnapshot();
            if (state.phase == MonitorState.Phase.RUNNING
                    || state.phase == MonitorState.Phase.STARTING) {
                refreshNotification(state);
            }
            boolean live = state.phase != MonitorState.Phase.IDLE;
            if (live || !stopping) {
                // Keep ticking while live and for one extra beat after a stop so
                // the final "stopped" notification is not left behind.
                main.postDelayed(this, 1000);
            }
        }
    };

    private static final Object engineLock = new Object();

    public static boolean isRunning() {
        MonitorEngine local = engine;
        return local != null && local.isRunning();
    }

    /** The phase the UI should render; safe to call from any thread. */
    public static MonitorState.Phase phase() {
        return MonitorHub.get().phase();
    }

    public static void start(Context ctx, String pkg, String label, int uid, boolean useVpn) {
        start(ctx, pkg, label, uid, useVpn, false);
    }

    public static void start(Context ctx, String pkg, String label, int uid, boolean useVpn,
                             boolean launchTarget) {
        Intent intent = buildIntent(ctx, pkg, label, uid, useVpn, launchTarget);
        send(ctx, intent);
    }

    public static void stop(Context ctx) {
        stop(ctx, "stopped by user");
    }

    public static void stop(Context ctx, String reason) {
        Intent intent = new Intent(ctx, MonitorService.class);
        intent.setAction(ACTION_STOP);
        intent.putExtra(EXTRA_REASON, reason);
        send(ctx, intent);
    }

    private static Intent buildIntent(Context ctx, String pkg, String label, int uid,
                                      boolean useVpn, boolean launchTarget) {
        Intent intent = new Intent(ctx, MonitorService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_PKG, pkg);
        intent.putExtra(EXTRA_LABEL, label);
        intent.putExtra(EXTRA_UID, uid);
        intent.putExtra(EXTRA_VPN, useVpn);
        intent.putExtra(EXTRA_LAUNCH, launchTarget);
        return intent;
    }

    private static void send(Context ctx, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Throwable error) {
            // Android 12+ refuses a foreground-service start from the background;
            // record it instead of dying with an uncaught exception.
            DiagnosticLog.recordProblem("Could not reach the monitoring service", error);
            MonitorHub.get().markFailed("the system refused to start the monitoring service: "
                    + LogSettings.describe(error));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        stopping = false;
        // Android kills the process with ForegroundServiceDidNotStartInTimeException
        // if a service started through startForegroundService() does not post its
        // notification, so this has to happen before any early return.
        String pkg = intent == null ? null : intent.getStringExtra(EXTRA_PKG);
        String label = intent == null ? null : intent.getStringExtra(EXTRA_LABEL);
        boolean foreground = startForegroundCompat(buildNotification(
                label == null || label.isEmpty() ? MonitorHub.get().label : label,
                pkg == null || pkg.isEmpty() ? MonitorHub.get().pkg : pkg));
        if (!foreground) {
            // Without a foreground service Android tears the process down within
            // seconds. Stopping cleanly is the honest outcome here; the reason is
            // shown on the dashboard and recorded in the diagnostics.
            MonitorHub.get().markFailed("the monitoring notification could not be posted "
                    + "(foreground service refused)");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startTicker();

        if (intent == null) {
            // The system restarted the service after a kill (low memory, battery
            // optimiser, force stop). Resume the stored session when there is one,
            // otherwise there is nothing to continue.
            if (!resumeStoredSession()) {
                teardown("the session was interrupted and could not be resumed", startId);
            }
            return START_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            String reason = intent.getStringExtra(EXTRA_REASON);
            teardown(reason == null || reason.isEmpty() ? "stopped by user" : reason, startId);
            return START_NOT_STICKY;
        }
        if (pkg == null || pkg.isEmpty()) {
            teardown("no application was selected", startId);
            return START_NOT_STICKY;
        }
        int uid = intent.getIntExtra(EXTRA_UID, -1);
        boolean useVpn = intent.getBooleanExtra(EXTRA_VPN, LogSettings.dnsCapture());
        boolean launchTarget = intent.getBooleanExtra(EXTRA_LAUNCH, false);
        requestStart(pkg, label, uid, useVpn, launchTarget);
        return START_STICKY;
    }

    // ------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------

    private void requestStart(final String pkg, final String label, final int uid,
                              final boolean useVpn, final boolean launchTarget) {
        final long request = SEQUENCE.incrementAndGet();
        newestRequest = request;
        MonitorHub hub = MonitorHub.get();
        MonitorEngine current = engine;
        boolean sameSession = current != null && pkg.equals(hub.pkg)
                && (current.isRunning() || hub.phase() == MonitorState.Phase.STARTING);
        if (sameSession) {
            // Already this application's session (running, or still coming up): a
            // second tap must not restart it, and a stop that was cancelled by this
            // very start must not leave the UI stuck on "stopping".
            if (current.isRunning() && hub.phase() != MonitorState.Phase.RUNNING) {
                hub.markRunning();
            }
            hub.publishStateNow();
            return;
        }
        hub.begin(pkg, label, uid);
        hub.publishStateNow();
        DiagnosticLog.beginSession("monitoring " + pkg + (useVpn ? " with DNS capture" : ""));
        rememberSession(pkg, label, uid, useVpn);
        if (useVpn) {
            startVpn(pkg, label, uid);
        }
        final Context app = getApplicationContext();
        bootstrap.execute(new Runnable() {
            @Override
            public void run() {
                if (newestRequest != request) {
                    // A stop or a newer start replaced this request while it was
                    // queueing; acting now would resurrect a cancelled session.
                    return;
                }
                MonitorEngine previous;
                synchronized (engineLock) {
                    previous = engine;
                    engine = null;
                }
                if (previous != null) {
                    previous.setStopReason("replaced by a new monitoring session");
                    try {
                        previous.stop();
                    } catch (Throwable error) {
                        DiagnosticLog.recordProblem("The previous session did not stop cleanly",
                                error);
                    }
                    ActivityLogWriter.get().close();
                }
                try {
                    String recordPath = ActivityLogWriter.get().open(app, pkg, label);
                    MonitorHub hub = MonitorHub.get();
                    hub.setRecord(recordPath, ActivityLogWriter.get().status());
                    hub.publishStateNow();
                    MonitorEngine local = new MonitorEngine(app, pkg, label, uid);
                    synchronized (engineLock) {
                        engine = local;
                    }
                    if (newestRequest != request) {
                        return;
                    }
                    local.start();
                    hub.publishStateNow();
                    if (launchTarget) {
                        launchTarget(app, pkg);
                    }
                } catch (Throwable error) {
                    // Monitoring must degrade, never crash: a sampler that cannot
                    // start is a diagnostics entry, not a dead application.
                    DiagnosticLog.recordProblem("Monitoring could not be started for " + pkg,
                            error);
                    MonitorHub.get().markFailed("monitoring could not be started: "
                            + LogSettings.describe(error));
                    MonitorHub.get().publishStateNow();
                }
            }
        });
    }

    /**
     * True when a session was remembered and is still recent enough to continue.
     * Called for the null-intent restart Android performs on START_STICKY.
     */
    private boolean resumeStoredSession() {
        String pkg = Prefs.str(Prefs.K_SESSION_PKG, "");
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        long started = Prefs.sp().getLong(Prefs.K_SESSION_STARTED, 0L);
        if (started <= 0 || System.currentTimeMillis() - started > RESUME_WINDOW_MS) {
            Prefs.clearSession();
            return false;
        }
        MonitorEngine current = engine;
        if (current != null && current.isRunning() && pkg.equals(MonitorHub.get().pkg)) {
            return true;
        }
        String label = Prefs.str(Prefs.K_SESSION_LABEL, pkg);
        int uid = Prefs.integer(Prefs.K_SESSION_UID, -1);
        boolean vpn = Prefs.bool(Prefs.K_SESSION_VPN, LogSettings.dnsCapture());
        MonitorHub.get().publish(EventItem.of(EventCategory.SYSTEM, "Session resumed",
                "AppLens was restarted by the system and picked the session for " + pkg
                        + " back up", "MonitorService"));
        requestStart(pkg, label, uid, vpn, false);
        return true;
    }

    private static void rememberSession(String pkg, String label, int uid, boolean useVpn) {
        Prefs.sp().edit()
                .putString(Prefs.K_SESSION_PKG, pkg)
                .putString(Prefs.K_SESSION_LABEL, label == null ? pkg : label)
                .putInt(Prefs.K_SESSION_UID, uid)
                .putBoolean(Prefs.K_SESSION_VPN, useVpn)
                .putLong(Prefs.K_SESSION_STARTED, System.currentTimeMillis())
                .apply();
    }

    /** Brings the monitored application to the front once the samplers are live. */
    private static void launchTarget(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        boolean requested = false;
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(launch);
                requested = true;
            }
        } catch (Throwable ignored) {
            // Background activity starts can be refused; the root path covers it.
        }
        if (requested) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (isTargetInFront(pkg)) {
                report(pkg, "launcher intent");
                return;
            }
        }
        // Either the app has no launcher activity, or Android refused the start
        // because the request came from a service. Root can always do it.
        try {
            RootShell root = RootShell.get();
            if (!root.isRootGranted()) {
                DiagnosticLog.recordThrottledProblem("launch-target",
                        "Could not bring " + pkg + " to the foreground",
                        new IllegalStateException("No launcher activity and no root access"));
                return;
            }
            if (root.statusOf("monkey -p " + RootShell.shQuote(pkg)
                    + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1") == 0) {
                report(pkg, "root monkey");
                return;
            }
            root.exec("am start -n \"$(cmd package resolve-activity --brief "
                    + RootShell.shQuote(pkg) + " | tail -1)\" >/dev/null 2>&1", 10000);
            report(pkg, "root am start");
        } catch (Throwable error) {
            DiagnosticLog.recordThrottledProblem("launch-target",
                    "Could not launch " + pkg, error);
        }
    }

    /** Puts the launch in the activity feed and the record file. */
    private static void report(String pkg, String how) {
        try {
            EventItem event = EventItem.of(EventCategory.ACTIVITY,
                    "Application launched by AppLens",
                    pkg + " was brought to the foreground after the monitors came up (" + how + ")",
                    "MonitorService");
            event.pkg = pkg;
            MonitorHub.get().publish(event);
            ActivityLogWriter.get().writeRaw(event.toLogLine(pkg));
        } catch (Throwable ignored) {
            // never let reporting break the launch
        }
    }

    /**
     * True when {@code pkg} owns the focused window. Android silently drops an
     * activity start a service is not allowed to make, so the only way to know
     * whether the launch worked is to look at what is actually on screen.
     */
    private static boolean isTargetInFront(String pkg) {
        try {
            RootShell root = RootShell.get();
            if (!root.isRootGranted()) {
                // Without root nothing better can be attempted anyway.
                return true;
            }
            String focus = root.exec("dumpsys activity activities 2>/dev/null"
                    + " | grep -m1 -E 'mResumedActivity|topResumedActivity'", 8000);
            if (focus == null || focus.trim().isEmpty()) {
                focus = root.exec("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus", 8000);
            }
            if (focus == null || focus.trim().isEmpty()) {
                // No answer: fall back to "is it running at all".
                String pids = root.exec("pidof " + RootShell.shQuote(pkg) + " 2>/dev/null", 6000);
                return pids != null && !pids.trim().isEmpty();
            }
            return focus.contains(pkg + "/");
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Stopping
    // ------------------------------------------------------------------

    /**
     * Stops the session on the shared worker and lets this start request go away.
     * {@code stopSelf(startId)} — not {@code stopSelf()} — so a start that arrived
     * while the teardown was queueing keeps the service (and its session) alive.
     */
    private void teardown(final String reason, final int startId) {
        final long request = SEQUENCE.incrementAndGet();
        newestRequest = request;
        stopping = true;
        stopVpnIfRunning();
        MonitorHub.get().markStopping();
        MonitorHub.get().publishStateNow();
        main.removeCallbacks(ticker);
        bootstrap.execute(new Runnable() {
            @Override
            public void run() {
                if (newestRequest != request) {
                    // A new start superseded the stop while it was queueing.
                    return;
                }
                MonitorEngine stopping;
                synchronized (engineLock) {
                    stopping = engine;
                    engine = null;
                }
                if (stopping != null) {
                    stopping.setStopReason(reason);
                    try {
                        stopping.stop();
                    } catch (Throwable error) {
                        DiagnosticLog.recordProblem("Monitoring could not be stopped cleanly", error);
                    }
                }
                try {
                    ActivityLogWriter writer = ActivityLogWriter.get();
                    writer.flush();
                    MonitorHub.get().setRecordBytes(writer.writtenBytes());
                    writer.close();
                } catch (Throwable error) {
                    DiagnosticLog.recordThrottledProblem("record-flush",
                            "The activity record could not be flushed", error);
                }
                Prefs.clearSession();
                DiagnosticLog.endSession();
                MonitorHub.get().end(reason);
                MonitorHub.get().publishStateNow();
            }
        });
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
            // noop
        }
        stopSelf(startId);
    }

    /** Starts the DNS capture VPN for the session. Failure is never fatal. */
    private void startVpn(String pkg, String label, int uid) {
        Intent vpn = new Intent(this, com.applens.monitor.net.DnsVpnService.class);
        vpn.setAction(com.applens.monitor.net.DnsVpnService.ACTION_START);
        vpn.putExtra(com.applens.monitor.net.DnsVpnService.EXTRA_PKG, pkg);
        vpn.putExtra(com.applens.monitor.net.DnsVpnService.EXTRA_LABEL, label);
        vpn.putExtra(com.applens.monitor.net.DnsVpnService.EXTRA_UID, uid);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(vpn);
            } else {
                startService(vpn);
            }
        } catch (Throwable error) {
            DiagnosticLog.recordProblem("Could not start the DNS capture VPN; monitoring"
                    + " continues without it", error);
        }
    }

    private void stopVpn() {
        Intent vpn = new Intent(this, com.applens.monitor.net.DnsVpnService.class);
        vpn.setAction(com.applens.monitor.net.DnsVpnService.ACTION_STOP);
        vpn.putExtra(com.applens.monitor.net.DnsVpnService.EXTRA_REASON, "the monitoring session ended");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(vpn);
            } else {
                startService(vpn);
            }
        } catch (Throwable ignored) {
            // The capture service is already gone.
        }
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(ticker);
        if (!stopping) {
            // The system destroyed the service without a stop request. If the
            // process is still alive the samplers keep running and the service is
            // brought straight back up, so the session is not cut off after a few
            // seconds by a lifecycle hiccup.
            MonitorEngine current = engine;
            if (current != null && current.isRunning()) {
                DiagnosticLog.recordThrottledProblem("service-restart",
                        "The monitoring service was destroyed unexpectedly; restarting it",
                        new IllegalStateException("onDestroy without a stop request"));
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(buildIntent(getApplicationContext(),
                                MonitorHub.get().pkg, MonitorHub.get().label,
                                MonitorHub.get().uid, dnsVpnRunning(), false));
                    } else {
                        startService(buildIntent(getApplicationContext(),
                                MonitorHub.get().pkg, MonitorHub.get().label,
                                MonitorHub.get().uid, dnsVpnRunning(), false));
                    }
                } catch (Throwable ignored) {
                    // Android can refuse a background foreground-service start; the
                    // samplers are still running and the next user action restores
                    // the notification.
                }
            }
        }
        super.onDestroy();
    }

    private static boolean dnsVpnRunning() {
        return com.applens.monitor.net.DnsVpnService.isRunning();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private void startTicker() {
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 1000);
    }

    /** Called once a second so the notification shows the live session. */
    private void refreshNotification(MonitorState state) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(state));
            }
        } catch (Throwable ignored) {
            // the notification is decorative
        }
    }

    /** @return false when the platform refused to put the service in the foreground. */
    private boolean startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Throwable error) {
            DiagnosticLog.recordProblem("Could not start the monitoring foreground service", error);
            return false;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "AppLens monitoring", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Live status while an application is monitored");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        } catch (Throwable ignored) {
            // noop
        }
    }

    private Notification buildNotification(String label, String pkg) {
        return buildNotification(MonitorHub.get().stateSnapshot(), label, pkg);
    }

    private Notification buildNotification(MonitorState state) {
        return buildNotification(state, MonitorHub.get().label, MonitorHub.get().pkg);
    }

    private Notification buildNotification(MonitorState state, String label, String pkg) {
        String title = Fmt.nz(label, Fmt.nz(pkg, "An application")) + " is monitored";
        if (state.phase == MonitorState.Phase.STARTING) {
            title = "Starting monitoring for " + Fmt.nz(label, Fmt.nz(pkg, "an application"));
        } else if (state.phase == MonitorState.Phase.STOPPING) {
            title = "Stopping monitoring for " + Fmt.nz(label, Fmt.nz(pkg, "an application"));
        } else if (state.phase == MonitorState.Phase.IDLE) {
            title = "AppLens monitoring stopped";
        }
        StringBuilder text = new StringBuilder();
        if (state.phase == MonitorState.Phase.RUNNING) {
            text.append(Fmt.duration(state.elapsed())).append(" · ")
                    .append(state.eventCount).append(" events · ")
                    .append(Fmt.rate(state.downRate)).append(" down");
        } else if (state.phase == MonitorState.Phase.IDLE) {
            text.append(state.stopReason.isEmpty() ? "No session running" : state.stopReason);
        } else {
            text.append("Preparing the monitors…");
        }
        Intent open = new Intent(this, DetailActivity.class)
                .putExtra(DetailActivity.EXTRA_PKG, pkg)
                .putExtra(DetailActivity.EXTRA_LABEL, label)
                .putExtra(DetailActivity.EXTRA_UID, MonitorHub.get().uid);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle(title)
                .setContentText(text.toString())
                .setSmallIcon(R.drawable.ic_pulse)
                .setOngoing(state.phase != MonitorState.Phase.IDLE)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending);
        String record = state.recordPath;
        if (record == null || record.isEmpty()) {
            builder.setSubText(state.recordNote.isEmpty() ? "no record file" : state.recordNote);
        } else {
            builder.setSubText("record: " + record);
        }
        Intent stop = new Intent(this, MonitorService.class)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_REASON, "stopped from the notification");
        builder.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop),
                "Stop", PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        try {
            builder.setColor(0xFF22D3EE);
        } catch (Throwable ignored) {
            // pre-21 devices
        }
        return builder.build();
    }

    /**
     * Asks the capture VPN to go away. Only called while it is really up: starting
     * the VPN service just to tell it to stop used to post a "preparing DNS
     * capture" notification for a service that was never running.
     */
    private void stopVpnIfRunning() {
        if (com.applens.monitor.net.DnsVpnService.isRunning()) {
            stopVpn();
        }
    }
}
