package com.applens.monitor.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.Prefs;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;
import com.applens.monitor.log.DiagnosticLog;
import com.applens.monitor.model.AppItem;
import com.applens.monitor.repo.AppRepository;
import com.applens.monitor.repo.ProcessRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dashboard: previously scanned applications, search, sorting, filters and a full
 * on-device rescan that also resolves sizes and running state through root.
 */
public class MainActivity extends Activity {

    private static final int[] SORT_LABELS = {R.string.sort_name, R.string.sort_package,
            R.string.sort_install, R.string.sort_update, R.string.sort_size};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "applens-scan");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService rootIo = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "applens-root-check");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> visible = new ArrayList<>();

    private AppAdapter adapter;
    private EditText searchInput;
    private TextView statusText;
    private TextView subtitleText;
    private TextView rootChip;
    private TextView sortButton;
    private TextView emptyText;
    private ProgressBar loading;
    private LinearLayout filterRow;
    private LinearLayout recentsRow;
    private View recentsBlock;

    private int sortMode;
    private int filterMode;
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        sortMode = Prefs.integer(Prefs.K_SORT, 0);
        filterMode = Prefs.integer(Prefs.K_FILTER_CAT, 0);

        searchInput = findViewById(R.id.searchInput);
        statusText = findViewById(R.id.statusText);
        subtitleText = findViewById(R.id.subtitleText);
        rootChip = findViewById(R.id.rootChip);
        sortButton = findViewById(R.id.sortButton);
        emptyText = findViewById(R.id.emptyText);
        loading = findViewById(R.id.loading);
        filterRow = findViewById(R.id.filterRow);
        recentsRow = findViewById(R.id.recentsRow);
        recentsBlock = findViewById(R.id.recentsBlock);
        ListView list = findViewById(R.id.appList);

        adapter = new AppAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppItem item = adapter.getItem(position);
            openDetail(item);
        });
        UiKit.applyRipple(list);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.US);
                applyFilters();
            }
        });

        sortButton.setOnClickListener(v -> showSortMenu());
        rootChip.setOnClickListener(v -> requestRoot(true));
        findViewById(R.id.scanButton).setOnClickListener(v -> startScan());
        findViewById(R.id.diagnosticsButton).setOnClickListener(v ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        findViewById(R.id.logsButton).setOnClickListener(v ->
                startActivity(new Intent(this, LogsActivity.class)));

        buildFilterChips();
        updateSortLabel();
        updateRootChip();

        // Show the previous scan straight away, then refresh in the background.
        List<AppItem> snapshot = AppRepository.loadSnapshot(this);
        if (!snapshot.isEmpty()) {
            all.addAll(snapshot);
            applyFilters();
            statusText.setText(snapshot.size() + " applications from the previous scan"
                    + " · " + Fmt.ago(AppRepository.lastScanTime(this)));
        }
        askNotificationPermission();
        buildRecents();
        if (all.isEmpty()) {
            startScan();
        } else {
            refreshRunningState();
        }
        requestRoot(false);
        if (DiagnosticLog.consumePendingReport()) {
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Intent diagnostics = new Intent(this, DiagnosticsActivity.class);
                diagnostics.putExtra(DiagnosticsActivity.EXTRA_RECOVERED_REPORT, true);
                startActivity(diagnostics);
            });
        }
    }

    private static final int REQ_NOTIFY = 77;

    private void askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            return;
        }
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
            }
        } catch (Throwable ignored) {
            // the monitoring service works without the notification
        }
    }

    // ------------------------------------------------------------------
    // Root
    // ------------------------------------------------------------------

    private void requestRoot(final boolean userInitiated) {
        rootChip.setText("ROOT …");
        rootChip.setTextColor(UiKit.color(this, R.color.accent));
        rootChip.setBackground(UiKit.pill(UiKit.color(this, R.color.accent_dim),
                UiKit.dp(this, 20)));
        rootIo.execute(() -> {
            RootShell root = RootShell.get();
            boolean granted = userInitiated ? root.requestRootAccess() : root.ensureRoot();
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                updateRootChip();
                if (userInitiated) {
                    Toast.makeText(this, granted
                                    ? "Root granted via " + root.suPath()
                                    : "Root access not granted. Allow AppLens in Magisk Superuser, "
                                            + "then tap ROOT to retry.",
                            Toast.LENGTH_LONG).show();
                }
                if (granted && all.isEmpty()) {
                    startScan();
                }
            });
        });
    }

    private void updateRootChip() {
        RootShell root = RootShell.get();
        boolean checking = root.isRootChecking();
        boolean granted = root.isRootGranted();
        rootChip.setText(granted ? "ROOT ✓" : checking ? "ROOT …" : "NO ROOT");
        int color = granted ? R.color.ok : checking ? R.color.accent : R.color.danger;
        int background = granted ? R.color.ok_dim
                : checking ? R.color.accent_dim : R.color.danger_dim;
        rootChip.setTextColor(UiKit.color(this, color));
        rootChip.setBackground(UiKit.pill(UiKit.color(this, background), UiKit.dp(this, 20)));
        rootChip.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8),
                UiKit.dp(this, 12), UiKit.dp(this, 8));
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    private void startScan() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        loading.setVisibility(View.VISIBLE);
        statusText.setText("Scanning installed applications…");
        final boolean includeSystem = filterMode != 2;
        io.execute(() -> {
            List<AppItem> scanned = AppRepository.scan(this, true, (done, total, current) ->
                    main.post(() -> statusText.setText("Scanning… " + done + "/" + total
                            + "  " + Fmt.limit(current, 34))));
            AppRepository.resolveInstallers(scanned);
            AppRepository.resolveRunning(scanned, ProcessRepository.uidPidMap());
            main.post(() -> {
                all.clear();
                all.addAll(scanned);
                applyFilters();
                AppRepository.saveSnapshot(this, scanned);
                List<String> packages = new ArrayList<>();
                for (AppItem item : scanned) {
                    if (!item.system) {
                        packages.add(item.pkg);
                    }
                }
                AppRepository.recordScan(this, packages);
                buildRecents();
                loading.setVisibility(View.GONE);
                scanning.set(false);
                statusText.setText(scanned.size() + " applications scanned · sizes resolving…");
            });
            AppRepository.resolveSizes(this, scanned, (done, total, current) ->
                    main.post(() -> {
                        applyFilters();
                        statusText.setText("Sizes " + done + "/" + total + " · " + current);
                    }));
            main.post(() -> {
                applyFilters();
                statusText.setText(scanned.size() + " applications · total "
                        + AppRepository.sizeOfAll(this, scanned));
                ActivityLogWriter.get().writeRaw(Fmt.clock(System.currentTimeMillis())
                        + "  [SCAN] " + scanned.size() + " applications scanned");
            });
        });
    }

    private void refreshRunningState() {
        io.execute(() -> {
            List<int[]> map = ProcessRepository.uidPidMap();
            main.post(() -> {
                AppRepository.resolveRunning(all, map);
                applyFilters();
            });
        });
    }

    // ------------------------------------------------------------------
    // Filtering / sorting
    // ------------------------------------------------------------------

    private void buildFilterChips() {
        filterRow.removeAllViews();
        String[] labels = {getString(R.string.filter_all), getString(R.string.filter_user),
                getString(R.string.filter_system), getString(R.string.filter_running),
                getString(R.string.filter_monitored)};
        for (int i = 0; i < labels.length; i++) {
            final int mode = i;
            TextView chip = UiKit.chip(this, labels[i], mode == filterMode);
            chip.setOnClickListener(v -> {
                filterMode = mode;
                Prefs.put(Prefs.K_FILTER_CAT, mode);
                buildFilterChips();
                applyFilters();
            });
            filterRow.addView(chip);
        }
    }

    private void showSortMenu() {
        PopupMenu menu = new PopupMenu(this, sortButton);
        for (int i = 0; i < SORT_LABELS.length; i++) {
            menu.getMenu().add(0, i, i, SORT_LABELS[i]);
        }
        menu.setOnMenuItemClickListener(item -> {
            sortMode = item.getItemId();
            Prefs.put(Prefs.K_SORT, sortMode);
            updateSortLabel();
            applyFilters();
            return true;
        });
        menu.show();
    }

    private void updateSortLabel() {
        sortButton.setText(SORT_LABELS[Math.max(0, Math.min(sortMode, SORT_LABELS.length - 1))]);
    }

    private void applyFilters() {
        visible.clear();
        String monitorPkg = com.applens.monitor.monitor.MonitorHub.get().isActive()
                ? com.applens.monitor.monitor.MonitorHub.get().pkg : "";
        for (AppItem item : all) {
            if (!query.isEmpty()) {
                boolean match = item.displayLabel().toLowerCase(Locale.US).contains(query)
                        || item.pkg.toLowerCase(Locale.US).contains(query);
                if (!match) {
                    continue;
                }
            }
            if (filterMode == 1 && item.system) {
                continue;
            }
            if (filterMode == 2 && !item.system) {
                continue;
            }
            if (filterMode == 3 && !item.running) {
                continue;
            }
            if (filterMode == 4 && !item.pkg.equals(monitorPkg)) {
                continue;
            }
            visible.add(item);
        }
        Collections.sort(visible, AppRepository.comparatorFor(sortMode));
        adapter.setItems(visible);
        emptyText.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        emptyText.setText(all.isEmpty() ? "Scanning installed applications…"
                : getString(R.string.no_apps_found));
        long total = 0;
        for (AppItem item : all) {
            total += Math.max(0, item.totalSize());
        }
        subtitleText.setText(visible.size() + " of " + all.size() + " shown · "
                + Fmt.bytes(total) + " indexed");
    }

    private void buildRecents() {
        List<String> recent = AppRepository.recentPackages(this);
        if (recent.isEmpty()) {
            recent = AppRepository.scanHistory(this);
        }
        recentsRow.removeAllViews();
        recentsBlock.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
        for (String pkg : recent) {
            AppItem known = findByPackage(pkg);
            final String label = known != null ? known.displayLabel() : pkg;
            TextView chip = UiKit.chip(this, label, false);
            chip.setOnClickListener(v -> {
                query = label.toLowerCase(Locale.US);
                searchInput.setText(label);
            });
            recentsRow.addView(chip);
        }
    }

    private AppItem findByPackage(String pkg) {
        for (AppItem item : all) {
            if (item.pkg.equals(pkg)) {
                return item;
            }
        }
        return null;
    }

    private void openDetail(AppItem item) {
        if (item == null) {
            return;
        }
        AppRepository.rememberOpened(this, item.pkg);
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra(DetailActivity.EXTRA_PKG, item.pkg);
        intent.putExtra(DetailActivity.EXTRA_LABEL, item.displayLabel());
        intent.putExtra(DetailActivity.EXTRA_UID, item.uid);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateRootChip();
        refreshRunningState();
    }

    /** Used by the detail screen to come back with fresh information. */
    public void setStatusText(CharSequence text) {
        statusText.setText(text);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        // Let an in-flight superuser prompt finish; interrupting it would turn an
        // unanswered Magisk dialog into a false denial.
        rootIo.shutdown();
    }
}
