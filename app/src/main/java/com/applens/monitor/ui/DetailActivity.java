package com.applens.monitor.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.LogSettings;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;
import com.applens.monitor.model.AppFacts;
import com.applens.monitor.model.AppItem;
import com.applens.monitor.model.ConnectionItem;
import com.applens.monitor.model.EventItem;
import com.applens.monitor.monitor.MonitorHub;
import com.applens.monitor.monitor.MonitorService;
import com.applens.monitor.monitor.MonitorState;
import com.applens.monitor.net.DnsVpnService;
import com.applens.monitor.repo.AppRepository;
import com.applens.monitor.repo.PackageFactsParser;
import com.applens.monitor.ui.tabs.AccessTab;
import com.applens.monitor.ui.tabs.ActivityTab;
import com.applens.monitor.ui.tabs.DataTab;
import com.applens.monitor.ui.tabs.DnsTab;
import com.applens.monitor.ui.tabs.NetworkTab;
import com.applens.monitor.ui.tabs.OverviewTab;
import com.applens.monitor.ui.tabs.PermissionsTab;
import com.applens.monitor.ui.tabs.TabPage;
import com.applens.monitor.ui.tabs.TimelineTab;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Application detail dashboard: header, monitoring toggle, live counters and the
 * eight monitoring tabs.
 */
public class DetailActivity extends Activity {

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_UID = "uid";

    private static final int REQ_VPN = 4242;
    /** Minimum time between two full repaints of the visible tab. */
    private static final long MIN_REFRESH_MS = 500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "applens-detail");
        t.setDaemon(true);
        return t;
    });

    private String pkg = "";
    private String label = "";
    private String installer = "";
    private int uid = -1;

    private AppItem app;
    private AppFacts facts;

    private ImageView appIcon;
    private TextView appName;
    private TextView appPackage;
    private TextView appMeta;
    private TextView monitorButton;
    private TextView monitorStatus;
    private TextView recordText;
    private TextView launchButton;
    private LinearLayout tabRow;
    private android.widget.FrameLayout tabHost;
    private LinearLayout liveStats;

    private final List<TabPage> pages = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();
    private int currentTab;
    private boolean pendingStart;
    private boolean pendingVpn;
    private boolean refreshPending;
    private long lastRefresh;
    private boolean uiTicking;

    private TextView statUp;
    private TextView statDown;
    private TextView statDns;
    private TextView statEvents;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        uid = getIntent().getIntExtra(EXTRA_UID, -1);
        if (pkg == null) {
            pkg = "";
        }
        if (label == null) {
            label = "";
        }

        appIcon = findViewById(R.id.appIcon);
        appName = findViewById(R.id.appName);
        appPackage = findViewById(R.id.appPackage);
        appMeta = findViewById(R.id.appMeta);
        monitorButton = findViewById(R.id.monitorButton);
        monitorStatus = findViewById(R.id.monitorStatus);
        recordText = findViewById(R.id.recordText);
        launchButton = findViewById(R.id.launchButton);
        tabRow = findViewById(R.id.tabRow);
        tabHost = (android.widget.FrameLayout) findViewById(R.id.tabContent);
        liveStats = findViewById(R.id.liveStats);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        monitorButton.setOnClickListener(v -> onMonitorClicked());
        // Long press starts the session with the DNS capture switched off for this
        // run — the quickest way to a working application when its own network
        // behaviour has to be left completely untouched.
        monitorButton.setOnLongClickListener(v -> {
            if (monitorRunningHere()) {
                return false;
            }
            startMonitoring(false);
            return true;
        });
        findViewById(R.id.settingsButton).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        launchButton.setOnClickListener(v -> launchApp());
        launchButton.setOnLongClickListener(v -> {
            openSystemSettings();
            return true;
        });

        appName.setText(label.isEmpty() ? pkg : label);
        appPackage.setText(pkg);
        appMeta.setText(uid > 0 ? "uid " + uid : "reading package details…");
        if (label.isEmpty()) {
            label = pkg;
        }

        buildLiveStats();
        buildPages();
        selectTab(0);

        loadFacts();
        bindHub();
        updateMonitorButton();
    }

    // ------------------------------------------------------------------
    // Header / data
    // ------------------------------------------------------------------

    private void loadFacts() {
        io.execute(() -> {
            AppItem loaded = null;
            try {
                PackageManager pm = getPackageManager();
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
                loaded = AppItem.from(this, info);
            } catch (Throwable ignored) {
                loaded = null;
            }
            AppFacts parsed = null;
            if (RootShell.get().ensureRoot()) {
                String dump = RootShell.get().exec("dumpsys package " + RootShell.shQuote(pkg), 25000);
                parsed = PackageFactsParser.parse(pkg, dump);
                String appOps = RootShell.get().exec("appops get " + RootShell.shQuote(pkg), 15000);
                if (appOps != null) {
                    parsed.appOps.putAll(PackageFactsParser.parseAppOps(appOps));
                }
                if (loaded != null && installer.isEmpty()) {
                    String installerOutput = RootShell.get().exec("cmd package list-packages -i "
                            + RootShell.shQuote(pkg) + " 2>/dev/null | head -1");
                    installer = installerOutput == null ? ""
                            : Fmt.nz(installerOutput.replace("package:", "").trim(), "");
                }
            } else if (loaded != null) {
                parsed = new AppFacts();
                parsed.pkg = pkg;
                parsed.uid = loaded.uid;
                parsed.firstInstall = loaded.firstInstall;
                parsed.lastUpdate = loaded.lastUpdate;
                parsed.dataDir = loaded.dataDir;
                parsed.sourceDir = loaded.sourceDir;
                parsed.versionName = loaded.versionName;
                parsed.versionCode = loaded.versionCode;
                parsed.targetSdk = loaded.targetSdk;
                parsed.minSdk = loaded.minSdk;
            }
            final AppItem finalApp = loaded;
            final AppFacts finalFacts = parsed;
            final String finalInstaller = installer;
            main.post(() -> {
                app = finalApp;
                facts = finalFacts;
                installer = finalInstaller;
                if (app != null) {
                    if (uid <= 0) {
                        uid = app.uid;
                    }
                    appIcon.setImageDrawable(app.icon(this));
                    appName.setText(app.displayLabel());
                    appPackage.setText(app.pkg);
                    appMeta.setText("v" + Fmt.nz(app.versionName, "?")
                            + " (" + app.versionCode + ") · uid " + app.uid
                            + " · target SDK " + app.targetSdk
                            + (installer.isEmpty() ? "" : " · " + installer));
                    if (!installer.isEmpty()) {
                        app.installer = installer;
                    }
                } else {
                    appName.setText(label);
                    appPackage.setText(pkg);
                }
                ActivityLogWriter.get().writeRaw(Fmt.clock(System.currentTimeMillis())
                        + "  [DETAIL] opened " + pkg);
                for (TabPage page : pages) {
                    page.onData();
                }
                refreshCurrent();
            });
        });
    }

    public AppItem app() {
        return app;
    }

    public AppFacts facts() {
        return facts;
    }

    public String pkg() {
        return pkg;
    }

    public String label() {
        return label == null || label.isEmpty() ? pkg : label;
    }

    public int uid() {
        return uid;
    }

    public ExecutorService io() {
        return io;
    }

    public Handler main() {
        return main;
    }

    // ------------------------------------------------------------------
    // Live stats
    // ------------------------------------------------------------------

    private void buildLiveStats() {
        liveStats.removeAllViews();
        statUp = addStat("UPLOAD", "—");
        statDown = addStat("DOWNLOAD", "—");
        statDns = addStat("DNS", "0");
        statEvents = addStat("EVENTS", "0");
    }

    private TextView addStat(String label, String value) {
        View view = View.inflate(this, R.layout.item_stat, null);
        TextView labelView = view.findViewById(R.id.statLabel);
        TextView valueView = view.findViewById(R.id.statValue);
        labelView.setText(label);
        valueView.setText(value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = UiKit.dp(this, 6);
        view.setLayoutParams(lp);
        liveStats.addView(view);
        return valueView;
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    private void buildPages() {
        pages.clear();
        tabViews.clear();
        tabRow.removeAllViews();
        pages.add(new OverviewTab(this));
        pages.add(new PermissionsTab(this));
        pages.add(new ActivityTab(this));
        pages.add(new NetworkTab(this));
        pages.add(new DnsTab(this));
        pages.add(new DataTab(this));
        pages.add(new AccessTab(this));
        pages.add(new TimelineTab(this));
        for (int i = 0; i < pages.size(); i++) {
            final int index = i;
            TextView tab = UiKit.chip(this, pages.get(i).title(), i == 0);
            tab.setOnClickListener(v -> selectTab(index));
            tabRow.addView(tab);
            tabViews.add(tab);
        }
    }

    public void selectTab(int index) {
        if (index < 0 || index >= pages.size()) {
            return;
        }
        currentTab = index;
        for (int i = 0; i < tabViews.size(); i++) {
            TextView view = tabViews.get(i);
            boolean active = i == index;
            view.setTextColor(UiKit.color(this, active ? R.color.accent : R.color.text_dim));
            view.setBackground(UiKit.rounded(
                    UiKit.color(this, active ? R.color.surface : R.color.surface_alt),
                    UiKit.color(this, active ? R.color.accent : R.color.stroke),
                    UiKit.dp(this, 20)));
        }
        tabHost.removeAllViews();
        TabPage page = pages.get(index);
        View content = page.view();
        if (content.getParent() != null) {
            ((android.view.ViewGroup) content.getParent()).removeView(content);
        }
        tabHost.addView(content, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        page.onShow();
    }

    public void refreshCurrent() {
        if (currentTab >= 0 && currentTab < pages.size()) {
            pages.get(currentTab).onShow();
        }
    }

    // ------------------------------------------------------------------
    // Monitoring
    // ------------------------------------------------------------------

    /** True when this screen's application is the one being monitored. */
    private boolean monitorRunningHere() {
        MonitorHub hub = MonitorHub.get();
        return pkg.equals(hub.pkg) && hub.phase() != MonitorState.Phase.IDLE;
    }

    private void onMonitorClicked() {
        if (monitorRunningHere()) {
            MonitorService.stop(this, "stopped by user");
            return;
        }
        // One request: the service replaces whatever session was running. Asking it
        // to stop first used to race with its own destruction and could take the
        // new session down with it after a few seconds.
        startMonitoring(LogSettings.dnsCapture());
    }

    private void startMonitoring(boolean withVpn) {
        Intent consent = null;
        if (withVpn) {
            try {
                consent = VpnService.prepare(this);
            } catch (Throwable ignored) {
                // no VPN UI available
            }
        }
        if (consent != null) {
            pendingStart = true;
            pendingVpn = true;
            try {
                startActivityForResult(consent, REQ_VPN);
            } catch (Throwable t) {
                pendingStart = false;
                launchMonitor(false);
            }
        } else {
            launchMonitor(withVpn);
        }
    }

    private void launchMonitor(boolean withVpn) {
        // The samplers come up first and then the app is brought to the front, so
        // its start-up — the most interesting part — is inside the record.
        MonitorService.start(this, pkg, label, uid, withVpn, true);
        Toast.makeText(this, (withVpn
                ? "Monitoring started with DNS capture · opening "
                : "Monitoring started (no DNS capture) · opening ") + label(),
                Toast.LENGTH_SHORT).show();
        updateMonitorButton();
        renderStatus(MonitorHub.get().stateSnapshot());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) {
            return;
        }
        boolean granted = resultCode == RESULT_OK && pendingVpn;
        if (pendingStart) {
            pendingStart = false;
            pendingVpn = false;
            launchMonitor(granted);
        }
    }

    private void launchApp() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);
                return;
            }
        } catch (Throwable ignored) {
            // fall through to the root launcher
        }
        // Apps without an exported launcher activity (or a disabled one) still
        // start through the activity manager.
        Toast.makeText(this, "Starting " + label() + "…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            final boolean ok = RootShell.get().isRootGranted()
                    && RootShell.get().statusOf("monkey -p " + RootShell.shQuote(pkg)
                    + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1") == 0;
            main.post(() -> {
                if (!ok) {
                    Toast.makeText(this, "No launcher activity for " + pkg,
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void openSystemSettings() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", pkg, null));
            startActivity(intent);
        } catch (Throwable ignored) {
            // noop
        }
    }

    /**
     * The button is rendered from the shared session phase, not from a second
     * source of truth: "starting", "running" and "stopping" are impossible to
     * confuse with "idle", which is what made start/stop look wrong before.
     */
    private void updateMonitorButton() {
        MonitorHub hub = MonitorHub.get();
        boolean mine = monitorRunningHere();
        MonitorState.Phase phase = mine ? hub.phase() : MonitorState.Phase.IDLE;
        String text;
        int background;
        int color;
        switch (phase) {
            case STARTING:
                text = "STARTING…";
                background = R.drawable.bg_button_ghost;
                color = R.color.accent;
                break;
            case RUNNING:
                text = getString(R.string.stop_monitoring);
                background = R.drawable.bg_button_stop;
                color = R.color.danger;
                break;
            case STOPPING:
                text = "STOPPING…";
                background = R.drawable.bg_button_ghost;
                color = R.color.warn;
                break;
            default:
                text = getString(R.string.start_monitoring);
                background = R.drawable.bg_button_primary;
                color = R.color.text_on_primary;
                break;
        }
        monitorButton.setText(text);
        monitorButton.setBackgroundResource(background);
        // The label sits on top of the accent gradient: dark navy when idle, danger red while
        // monitoring. Always pass a colour *resource* here, never a raw ARGB literal — see UiKit.color.
        monitorButton.setTextColor(UiKit.color(this, color));
    }

    // ------------------------------------------------------------------
    // Live bindings
    // ------------------------------------------------------------------

    private void bindHub() {
        MonitorHub hub = MonitorHub.get();
        stateConsumer = this::onState;
        eventConsumer = this::onEvent;
        hub.state.subscribe(stateConsumer);
        hub.events.subscribe(eventConsumer);
    }

    private Consumer<MonitorState> stateConsumer;
    private Consumer<EventItem> eventConsumer;

    private void onState(MonitorState state) {
        if (!pkg.equals(hubPkg())) {
            return;
        }
        renderStatus(state);
        updateMonitorButton();
        scheduleRefresh();
    }

    /** Idempotent status renderer, also driven by the one-second UI ticker. */
    private void renderStatus(MonitorState state) {
        if (state == null) {
            state = MonitorHub.get().stateSnapshot();
        }
        boolean mine = pkg.equals(hubPkg());
        MonitorState.Phase phase = mine ? state.phase : MonitorState.Phase.IDLE;
        statUp.setText(Fmt.bytesShort(state.bytesUp));
        statDown.setText(Fmt.bytesShort(state.bytesDown));
        statDns.setText(String.valueOf(state.dnsQueries));
        statEvents.setText(String.valueOf(state.eventCount));

        StringBuilder status = new StringBuilder();
        switch (phase) {
            case STARTING:
                status.append("Starting the monitors…");
                break;
            case RUNNING:
                status.append("● Monitoring ").append(Fmt.duration(state.elapsed()));
                status.append(" · ").append(state.eventCount).append(" events");
                status.append(" · ").append(state.connectionCount).append(" connections");
                status.append(" · ").append(state.distinctDomains).append(" domains");
                if (state.processCount > 0) {
                    status.append(" · ").append(state.processCount).append(" proc");
                }
                if (state.fileEvents > 0) {
                    status.append(" · ").append(state.fileEvents).append(" file events");
                }
                if (!state.byteSource.isEmpty()) {
                    status.append(" · bytes via ").append(state.byteSource);
                }
                break;
            case STOPPING:
                status.append("Stopping…");
                break;
            default:
                status.append("Not monitoring");
                if (!mine) {
                    status.append(" — ").append(Fmt.nz(MonitorHub.get().label,
                            MonitorHub.get().pkg)).append(" is");
                }
                break;
        }
        if (phase != MonitorState.Phase.IDLE && !state.vpnStatus.isEmpty()) {
            status.append(" · DNS capture ").append(state.vpnStatus);
            if (!state.vpnError.isEmpty()) {
                status.append(" (").append(state.vpnError).append(')');
            }
        }
        if (!state.startError.isEmpty()) {
            status.append("\n").append(state.startError);
        }
        if (phase == MonitorState.Phase.IDLE && !state.stopReason.isEmpty()) {
            status.append("\nLast session: ").append(state.stopReason);
        }
        monitorStatus.setText(status.toString());

        if (recordText != null) {
            String path = state.recordPath;
            if (path == null || path.isEmpty()) {
                recordText.setText(state.recordNote.isEmpty()
                        ? "Record file: not started" : "Record file: " + state.recordNote);
            } else {
                recordText.setText("Record file: " + path
                        + (state.recordBytes > 0 ? "  (" + Fmt.bytes(state.recordBytes) + ")" : ""));
            }
        }
    }

    /** Keeps the elapsed time and the record size live between state publishes. */
    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            if (!uiTicking) {
                return;
            }
            renderStatus(MonitorHub.get().stateSnapshot());
            updateMonitorButton();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (!uiTicking) {
            uiTicking = true;
            main.postDelayed(uiTicker, 1000);
        }
        renderStatus(MonitorHub.get().stateSnapshot());
        updateMonitorButton();
    }

    @Override
    protected void onPause() {
        uiTicking = false;
        main.removeCallbacks(uiTicker);
        super.onPause();
    }

    /**
     * Repaints the visible tab at most twice a second. Rebuilding a tab means
     * discarding and re-inflating every row, so doing it on every state tick is
     * what made the dashboard — and with it the activity transition to the app
     * being monitored — crawl to a halt.
     */
    private void scheduleRefresh() {
        if (refreshPending) {
            return;
        }
        refreshPending = true;
        long now = android.os.SystemClock.uptimeMillis();
        long delay = Math.max(0, lastRefresh + MIN_REFRESH_MS - now);
        main.postDelayed(() -> {
            refreshPending = false;
            lastRefresh = android.os.SystemClock.uptimeMillis();
            if (!isFinishing() && !isDestroyed()) {
                refreshCurrent();
            }
        }, delay);
    }

    private void onEvent(EventItem event) {
        if (!pkg.equals(hubPkg())) {
            return;
        }
        if (currentTab >= 0 && currentTab < pages.size()) {
            pages.get(currentTab).onEvent(event);
        }
    }

    private String hubPkg() {
        return MonitorHub.get().pkg;
    }

    public List<ConnectionItem> connections() {
        return MonitorHub.get().connections();
    }

    @Override
    protected void onDestroy() {
        MonitorHub.get().state.unsubscribe(stateConsumer);
        MonitorHub.get().events.unsubscribe(eventConsumer);
        super.onDestroy();
        io.shutdownNow();
    }
}
