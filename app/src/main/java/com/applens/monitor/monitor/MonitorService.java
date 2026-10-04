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
        Intent intent = new Intent(ctx, MonitorService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_PKG, pkg);
        intent.putExtra(EXTRA_LABEL, label);
        intent.putExtra(EXTRA_UID, uid);
        intent.putExtra(EXTRA_VPN, useVpn);
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
            ctx.startService(intent);
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
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            teardown();
            return START_NOT_STICKY;
        }
        String pkg = intent.getStringExtra(EXTRA_PKG);
        String label = intent.getStringExtra(EXTRA_LABEL);
        int uid = intent.getIntExtra(EXTRA_UID, -1);
        boolean useVpn = intent.getBooleanExtra(EXTRA_VPN, true);
        if (pkg == null || pkg.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        pendingPkg = pkg;
        pendingLabel = label;
        pendingUid = uid;
        startForegroundCompat(buildNotification(label, pkg));
        if (useVpn) {
            startVpn(pkg, label, uid);
        }
        // Opening the record file and negotiating the root session spawns processes,
        // so it must not happen on the service main thread.
        bootstrap.execute(new Runnable() {
            @Override
            public void run() {
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
            }
        });
        return START_STICKY;
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
        synchronized (engineLock) {
            if (engine != null) {
                engine.stop();
                engine = null;
            }
        }
        Intent vpn = new Intent(this, com.applens.monitor.net.DnsVpnService.class);
        vpn.setAction(com.applens.monitor.net.DnsVpnService.ACTION_STOP);
        try {
            startService(vpn);
        } catch (Throwable ignored) {
            // noop
        }
        ActivityLogWriter.get().flush();
        MonitorHub.get().end();
        MonitorHub.get().publishState();
        stopForeground(true);
        stopSelf();
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
        String title = Fmt.nz(label, pkg) + " is monitored";
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
