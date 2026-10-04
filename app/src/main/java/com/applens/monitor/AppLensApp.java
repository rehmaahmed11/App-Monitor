package com.applens.monitor;

import android.app.Application;

import com.applens.monitor.core.Prefs;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.DiagnosticLog;

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
                RootShell.get().ensureRoot();
            }
        }, "applens-root");
        root.setDaemon(true);
        root.start();
    }
}
