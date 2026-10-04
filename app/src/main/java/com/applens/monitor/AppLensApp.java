package com.applens.monitor;

import android.app.Application;

import com.applens.monitor.core.Prefs;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.DiagnosticLog;
import com.applens.monitor.net.DnsVpnService;

/**
 * Application entry point. Boots the shared preferences store and kicks off the
 * one-off root negotiation so the rest of the app never blocks on {@code su}.
 */
public class AppLensApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DiagnosticLog.init(this);
        Prefs.init(this);
        DiagnosticLog.inspectPreviousProcessExit(this);
        Thread root = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (RootShell.get().ensureRoot()) {
                        // A session that was killed (crash, force stop, battery
                        // optimiser) cannot remove its own DNS redirects, and a
                        // redirect pointing at a dead local port breaks name
                        // resolution for the app it was installed for. Clear any
                        // leftovers before anything else happens.
                        DnsVpnService.purgeStaleRules();
                    }
                } catch (Throwable error) {
                    DiagnosticLog.recordProblem("Start-up root check failed", error);
                }
            }
        }, "applens-root");
        root.setDaemon(true);
        root.start();
    }
}
