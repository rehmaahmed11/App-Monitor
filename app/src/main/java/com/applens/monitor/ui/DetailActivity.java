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
    private TextView launchButton;
    private LinearLayout tabRow;
    private android.widget.FrameLayout tabHost;
    private LinearLayout liveStats;

    private final List<TabPage> pages = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();
    private int currentTab;
    private boolean pendingStart;

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
        launchButton = findViewById(R.id.launchButton);
        tabRow = findViewById(R.id.tabRow);
        tabHost = (android.widget.FrameLayout) findViewById(R.id.tabContent);
        liveStats = findViewById(R.id.liveStats);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        monitorButton.setOnClickListener(v -> onMonitorClicked());
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

    private void onMonitorClicked() {
        if (MonitorService.isRunning() && MonitorHub.get().pkg.equals(pkg)) {
            MonitorService.stop(this);
            return;
        }
        // A new session replaces whatever was monitored before.
        MonitorService.stop(this);
        startMonitoring();
    }

    private void startMonitoring() {
        Intent consent = null;
        try {
            consent = VpnService.prepare(this);
        } catch (Throwable ignored) {
            // no VPN UI available
        }
        if (consent != null) {
            pendingStart = true;
            try {
                startActivityForResult(consent, REQ_VPN);
            } catch (Throwable t) {
                pendingStart = false;
                launchMonitor(false);
            }
        } else {
            launchMonitor(true);
        }
    }

    private void launchMonitor(boolean withVpn) {
        MonitorHub.get().begin(pkg, label, uid);
        MonitorService.start(this, pkg, label, uid, withVpn);
        Toast.makeText(this, withVpn
                        ? "Monitoring started with DNS capture"
                        : "Monitoring started (DNS capture unavailable)", Toast.LENGTH_SHORT).show();
        updateMonitorButton();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) {
            return;
        }
        boolean granted = resultCode == RESULT_OK;
        if (pendingStart) {
            pendingStart = false;
            launchMonitor(granted);
        }
    }

    private void launchApp() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "No launcher activity", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Unable to launch", Toast.LENGTH_SHORT).show();
        }
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

    private void updateMonitorButton() {
        boolean mine = MonitorService.isRunning() && MonitorHub.get().pkg.equals(pkg);
        monitorButton.setText(mine ? getString(R.string.stop_monitoring)
                : getString(R.string.start_monitoring));
        monitorButton.setBackgroundResource(mine ? R.drawable.bg_button_stop : R.drawable.bg_button_primary);
        // The label sits on top of the accent gradient: dark navy when idle, danger red while
        // monitoring. Always pass a colour *resource* here, never a raw ARGB literal — see UiKit.color.
        monitorButton.setTextColor(UiKit.color(this, mine ? R.color.danger : R.color.text_on_primary));
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
        statUp.setText(Fmt.bytesShort(state.bytesUp));
        statDown.setText(Fmt.bytesShort(state.bytesDown));
        statDns.setText(String.valueOf(state.dnsQueries));
        statEvents.setText(String.valueOf(state.eventCount));
        StringBuilder status = new StringBuilder();
        status.append(state.active ? "Monitoring " + Fmt.duration(state.elapsed())
                : "Not monitoring");
        if (state.active) {
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
        }
        if (DnsVpnService.isRunning()) {
            status.append(" · DNS VPN ").append(DnsVpnService.statusText());
            if (!DnsVpnService.errorText().isEmpty()) {
                status.append(" (").append(DnsVpnService.errorText()).append(')');
            }
        }
        monitorStatus.setText(status.toString());
        updateMonitorButton();
        refreshCurrent();
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
