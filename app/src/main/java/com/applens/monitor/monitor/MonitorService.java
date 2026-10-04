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
import android.os.IBinder;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;
import com.applens.monitor.log.DiagnosticLog;
import com.applens.monitor.ui.DetailActivity;

/**
 * Foreground service that keeps a monitoring session alive while the user browses
 * other screens. Also owns the DNS capture VPN lifecycle.
 */
public class MonitorService extends Service {

    public static final String ACTION_START = "com.applens.monitor.START";
    public static final String ACTION_STOP = "com.applens.monitor.STOP";
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_UID = "uid";
    public static final String EXTRA_VPN = "vpn";
    public static final String EXTRA_LAUNCH = "launch";

    private static final String CHANNEL_ID = "applens_monitoring";
    private static final int NOTIFICATION_ID = 0xA11;

    private static volatile MonitorEngine engine;

    private final java.util.concurrent.ExecutorService bootstrap =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "applens-bootstrap");
                t.setDaemon(true);
                return t;
            });

    private final Object engineLock = new Object();
    private volatile String pendingPkg = "";
    private volatile String pendingLabel = "";
    private volatile int pendingUid = -1;

    public static boolean isRunning() {
        MonitorEngine local = engine;
        return local != null && local.isRunning();
    }

    public static void start(Context ctx, String pkg, String label, int uid, boolean useVpn) {
        start(ctx, pkg, label, uid, useVpn, false);
    }

    public static void start(Context ctx, String pkg, String label, int uid, boolean useVpn,
                             boolean launchTarget) {
        Intent intent = new Intent(ctx, MonitorService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_PKG, pkg);
        intent.putExtra(EXTRA_LABEL, label);
        intent.putExtra(EXTRA_UID, uid);
        intent.putExtra(EXTRA_VPN, useVpn);
        intent.putExtra(EXTRA_LAUNCH, launchTarget);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Throwable error) {
            DiagnosticLog.recordProblem("Could not start the monitoring service", error);
        }
    }

    public static void stop(Context ctx) {
        Intent intent = new Intent(ctx, MonitorService.class);
        intent.setAction(ACTION_STOP);
        try {
            // The service posts its notification on every path, so a foreground
            // start is safe here and is the only form allowed from the background.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Throwable ignored) {
            // noop
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android kills the process with ForegroundServiceDidNotStartInTimeException
        // if a service started through startForegroundService() does not post its
        // notification, so this has to happen before any early return.
        String pkg = intent == null ? null : intent.getStringExtra(EXTRA_PKG);
        String label = intent == null ? null : intent.getStringExtra(EXTRA_LABEL);
        startForegroundCompat(buildNotification(
                label == null || label.isEmpty() ? MonitorHub.get().label : label,
                pkg == null || pkg.isEmpty() ? MonitorHub.get().pkg : pkg));
        if (intent == null) {
            teardown();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            teardown();
            return START_NOT_STICKY;
        }
        int uid = intent.getIntExtra(EXTRA_UID, -1);
        boolean useVpn = intent.getBooleanExtra(EXTRA_VPN, true);
        boolean launchTarget = intent.getBooleanExtra(EXTRA_LAUNCH, false);
        if (pkg == null || pkg.isEmpty()) {
            teardown();
            return START_NOT_STICKY;
        }
        pendingPkg = pkg;
        pendingLabel = label;
        pendingUid = uid;
        // A session that dies without a Java exception (ANR kill, low memory, a
        // force stop) leaves this breadcrumb behind; the next launch turns it into
        // a diagnostics entry instead of an empty report screen.
        DiagnosticLog.beginSession("monitoring " + pkg + (useVpn ? " with DNS capture" : ""));
        if (useVpn) {
            startVpn(pkg, label, uid);
        }
        final boolean shouldLaunch = launchTarget;
        // Opening the record file and negotiating the root session spawns processes,
        // so it must not happen on the service main thread.
        bootstrap.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    MonitorEngine local;
                    synchronized (engineLock) {
                        if (engine == null) {
                            engine = new MonitorEngine(MonitorService.this, pendingPkg,
                                    pendingLabel, pendingUid);
                        }
                        local = engine;
                    }
                    if (!local.isRunning()) {
                        ActivityLogWriter.get().open(MonitorService.this, pendingPkg, pendingLabel);
                        local.start();
                    }
                    MonitorHub.get().publishState();
                    if (shouldLaunch) {
                        launchTarget(pendingPkg);
                    }
                } catch (Throwable error) {
                    // Monitoring must degrade, never crash: a sampler that cannot
                    // start is a diagnostics entry, not a dead application.
                    DiagnosticLog.recordProblem("Monitoring could not be started for "
                            + pendingPkg, error);
                    MonitorHub.get().publishState();
                }
            }
        });
        return START_STICKY;
    }

    /**
     * Brings the monitored application to the front once the samplers are live, so
     * its start-up is part of the record. The launcher intent is tried first and
     * root {@code am start} is the fallback for apps without one.
     */
    private void launchTarget(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        boolean requested = false;
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(launch);
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
            if (isTargetRunning(pkg)) {
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
                    + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1") != 0) {
                root.exec("am start -n \"$(cmd package resolve-activity --brief "
                        + RootShell.shQuote(pkg) + " | tail -1)\" >/dev/null 2>&1", 10000);
            }
        } catch (Throwable error) {
            DiagnosticLog.recordThrottledProblem("launch-target",
                    "Could not launch " + pkg, error);
        }
    }

    private boolean isTargetRunning(String pkg) {
        try {
            RootShell root = RootShell.get();
            if (!root.isRootGranted()) {
                // Without root assume the launch worked rather than starting it twice.
                return true;
            }
            String pids = root.exec("pidof " + RootShell.shQuote(pkg) + " 2>/dev/null", 6000);
            return pids != null && !pids.trim().isEmpty();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable error) {
            DiagnosticLog.recordProblem("Could not start the monitoring foreground service", error);
        }
    }

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
            DiagnosticLog.recordProblem("Could not start DNS capture VPN; monitoring may continue without it",
                    error);
        }
    }

    private void teardown() {
        pendingPkg = "";
        final MonitorEngine stopping;
        synchronized (engineLock) {
            stopping = engine;
            engine = null;
        }
        stopVpn();
        MonitorHub.get().end();
        MonitorHub.get().publishState();
        DiagnosticLog.endSession();
        // Stopping the samplers tears down root streams and firewall counters and
        // the final flush writes through root, so none of it may run on the main
        // thread; the service itself goes away immediately.
        Thread closer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (stopping != null) {
                        stopping.stop();
                    }
                } catch (Throwable error) {
                    DiagnosticLog.recordProblem("Monitoring could not be stopped cleanly", error);
                }
                try {
                    ActivityLogWriter.get().flush();
                } catch (Throwable error) {
                    DiagnosticLog.recordThrottledProblem("record-flush",
                            "The activity record could not be flushed", error);
                }
            }
        }, "applens-teardown");
        closer.setDaemon(true);
        closer.start();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
            // noop
        }
        stopSelf();
    }

    private void stopVpn() {
        Intent vpn = new Intent(this, com.applens.monitor.net.DnsVpnService.class);
        vpn.setAction(com.applens.monitor.net.DnsVpnService.ACTION_STOP);
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
        bootstrap.shutdown();
        synchronized (engineLock) {
            if (engine != null) {
                engine.stop();
                engine = null;
            }
        }
        MonitorHub.get().end();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
        MonitorState state = MonitorHub.get().stateSnapshot();
        String title = Fmt.nz(label, Fmt.nz(pkg, "An application")) + " is monitored";
        String text = Fmt.duration(state.elapsed()) + " · " + state.eventCount + " events · "
                + Fmt.rate(state.downRate) + " down";
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
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_pulse)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending);
        Intent stop = new Intent(this, MonitorService.class).setAction(ACTION_STOP);
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

}
