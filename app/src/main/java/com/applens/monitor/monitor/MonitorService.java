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

    public static boolean isRunning() {
        return engine != null && engine.isRunning();
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
        } catch (Throwable ignored) {
            // the UI surfaces a warning if this fails
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
        startForegroundCompat(buildNotification(label, pkg));
        if (engine == null) {
            engine = new MonitorEngine(this, pkg, label, uid);
        }
        if (!engine.isRunning()) {
            ActivityLogWriter.get().open(this, pkg, label);
            engine.start();
        }
        if (useVpn) {
            startVpn(pkg, label, uid);
        }
        MonitorHub.get().publishState();
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
        } catch (Throwable ignored) {
            // still return START_STICKY so the engine keeps running
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
        } catch (Throwable ignored) {
            // DNS capture falls back to /proc based observation
        }
    }

    private void teardown() {
        if (engine != null) {
            engine.stop();
            engine = null;
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
        if (engine != null) {
            engine.stop();
            engine = null;
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
