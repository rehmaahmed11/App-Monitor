package com.applens.monitor.ui.tabs;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.model.AppFacts;
import com.applens.monitor.model.AppItem;
import com.applens.monitor.model.ProcessStat;
import com.applens.monitor.monitor.MonitorHub;
import com.applens.monitor.monitor.MonitorState;
import com.applens.monitor.repo.ProcessRepository;
import com.applens.monitor.ui.DetailActivity;
import com.applens.monitor.ui.UiKit;

/** OVERVIEW: identity, install metadata, components, processes and quick actions. */
public class OverviewTab extends TabPage {

    private LinearLayout factsCard;
    private LinearLayout processCard;
    private TextView headline;

    public OverviewTab(DetailActivity host) {
        super(host);
    }

    @Override
    public String title() {
        return "OVERVIEW";
    }

    @Override
    protected void onBuild() {
        LinearLayout b = body();
        headline = UiKit.text(host, host.label(), 17, R.color.text);
        headline.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.addView(headline, matchWrap());

        factsCard = UiKit.card(host);
        b.addView(factsCard);
        processCard = UiKit.card(host);
        b.addView(processCard);
    }

    @Override
    public void onRefresh() {
        renderFacts();
        renderProcesses();
    }

    private void renderFacts() {
        if (factsCard == null) {
            return;
        }
        factsCard.removeAllViews();
        factsCard.addView(UiKit.text(host, "IDENTITY", 10, R.color.muted));

        AppItem app = host.app();
        AppFacts facts = host.facts();
        add("Package", host.pkg());
        if (app != null) {
            add("Version", app.versionName + " (" + app.versionCode + ")");
            add("UID", String.valueOf(app.uid));
            add("Process", app.processName);
            add("Size", app.sizeText());
        } else if (facts != null) {
            add("Version", facts.versionName + " (" + facts.versionCode + ")");
            add("UID", facts.uid > 0 ? String.valueOf(facts.uid) : "n/a");
        }
        if (facts != null) {
            add("Target SDK", facts.targetSdk > 0 ? String.valueOf(facts.targetSdk) : "n/a");
            add("Min SDK", facts.minSdk > 0 ? String.valueOf(facts.minSdk) : "n/a");
            add("Installed", Fmt.dateTime(facts.firstInstall));
            add("Last update", Fmt.dateTime(facts.lastUpdate));
            if (!facts.installer.isEmpty()) {
                add("Installer", facts.installer);
            }
            if (!facts.primaryCpuAbi.isEmpty()) {
                add("Primary ABI", facts.primaryCpuAbi);
            }
            if (!facts.sharedUserId.isEmpty() && !facts.sharedUserId.equals("android.uid.system")) {
                add("Shared UID", facts.sharedUserId);
            }
            add("SE info", facts.seInfo);
            if (!facts.gids.isEmpty()) {
                add("GIDs", facts.gids);
            }
            add("Source", facts.sourceDir);
            add("Data dir", facts.dataDir);
            add("Components", facts.activities.size() + " activities · " + facts.services.size()
                    + " services · " + facts.receivers.size() + " receivers · "
                    + facts.providers.size() + " providers");
            if (!facts.pkgFlagsText.isEmpty()) {
                add("Flags", String.join(" ", facts.pkgFlagsText));
            }
        }
        if (app != null) {
            add("Activities declared", String.valueOf(app.activities));
            add("Services declared", String.valueOf(app.services));
            add("Receivers declared", String.valueOf(app.receivers));
            add("Providers declared", String.valueOf(app.providers));
        }
        MonitorState state = MonitorHub.get().stateSnapshot();
        if (state.active && MonitorHub.get().pkg.equals(host.pkg())) {
            factsCard.addView(UiKit.divider(host));
            add("Monitoring", Fmt.duration(state.elapsed()) + " · " + state.eventCount + " events",
                    R.color.ok);
            add("Byte source", Fmt.nz(state.byteSource, "n/a"));
        }

        factsCard.addView(UiKit.divider(host));
        LinearLayout actions = UiKit.row(host);
        TextView open = UiKit.button(host, "OPEN APP", R.drawable.bg_button_ghost);
        open.setOnClickListener(v -> launch());
        actions.addView(open, new LinearLayout.LayoutParams(0, UiKit.dp(host, 40), 1f));
        TextView info = UiKit.button(host, "APP INFO", R.drawable.bg_button_ghost);
        info.setOnClickListener(v -> settings());
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, UiKit.dp(host, 40), 1f);
        infoLp.leftMargin = UiKit.dp(host, 8);
        actions.addView(info, infoLp);
        TextView stop = UiKit.button(host, "FORCE STOP", R.drawable.bg_button_ghost);
        stop.setOnClickListener(v -> forceStop());
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, UiKit.dp(host, 40), 1f);
        stopLp.leftMargin = UiKit.dp(host, 8);
        actions.addView(stop, stopLp);
        factsCard.addView(actions);
    }

    private void add(String key, String value) {
        add(key, value, R.color.text);
    }

    private void add(String key, String value, int colorRes) {
        factsCard.addView(UiKit.keyValue(host, key, Fmt.nz(value, "n/a"), colorRes));
    }

    private void renderProcesses() {
        if (processCard == null) {
            return;
        }
        processCard.removeAllViews();
        processCard.addView(UiKit.text(host, "PROCESSES", 10, R.color.muted));
        java.util.List<ProcessStat> stats = MonitorHub.get().processes();
        if (stats.isEmpty()) {
            host.io().execute(() -> {
                java.util.List<ProcessStat> fresh = ProcessRepository.snapshot(host.pkg());
                host.main().post(() -> {
                    MonitorHub.get().setProcesses(fresh);
                    if (isCurrent()) {
                        renderProcesses();
                    }
                });
            });
            processCard.addView(UiKit.text(host, "Reading /proc…", 12, R.color.text_faint));
            return;
        }
        for (ProcessStat stat : stats) {
            processCard.addView(UiKit.keyValue(host, stat.name + " (pid " + stat.pid + ")",
                    stat.stateLabel() + " · " + stat.memText() + " · " + stat.threads + " thr",
                    R.color.text));
            processCard.addView(UiKit.keyValue(host, "  file descriptors",
                    stat.openFiles + " open · " + stat.sockets + " sockets · "
                            + stat.nativeLibraries + " native libs", R.color.text_dim));
            processCard.addView(UiKit.keyValue(host, "  io",
                    "r " + Fmt.bytes(stat.readBytes) + " · w " + Fmt.bytes(stat.writeBytes)
                            + " · syscr " + stat.rchar + " · syscw " + stat.wchar, R.color.text_dim));
        }
        long totalRss = 0;
        for (ProcessStat stat : stats) {
            totalRss += stat.rssKb;
        }
        processCard.addView(UiKit.divider(host));
        processCard.addView(UiKit.keyValue(host, "Total resident",
                Fmt.bytes(totalRss * 1024), R.color.accent));
    }

    private boolean isCurrent() {
        return processCard != null && processCard.getParent() != null;
    }

    private void launch() {
        try {
            Intent intent = host.getPackageManager().getLaunchIntentForPackage(host.pkg());
            if (intent != null) {
                host.startActivity(intent);
            }
        } catch (Throwable ignored) {
            // noop
        }
    }

    private void settings() {
        try {
            host.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", host.pkg(), null)));
        } catch (Throwable ignored) {
            // noop
        }
    }

    private void forceStop() {
        host.io().execute(() -> {
            RootShell.get().exec("am force-stop " + RootShell.shQuote(host.pkg()));
            host.main().post(() -> com.applens.monitor.log.ActivityLogWriter.get()
                    .writeRaw(Fmt.clock(System.currentTimeMillis())
                            + "  [ACTION] force-stop " + host.pkg()));
        });
    }
}
