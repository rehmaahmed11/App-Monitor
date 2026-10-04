package com.applens.monitor.ui.tabs;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.model.ConnectionItem;
import com.applens.monitor.monitor.DnsHostCache;
import com.applens.monitor.monitor.MonitorHub;
import com.applens.monitor.monitor.MonitorService;
import com.applens.monitor.monitor.MonitorState;
import com.applens.monitor.ui.DetailActivity;
import com.applens.monitor.ui.UiKit;
import com.applens.monitor.ui.widget.SparklineView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** NETWORK: live connection table with throughput graphs and totals. */
public class NetworkTab extends TabPage {

    private SparklineView upChart;
    private SparklineView downChart;
    private TextView summaryText;
    private LinearLayout header;
    private Row.Adapter adapter;
    private long lastSample;
    private final Map<String, Long> hostBytes = new LinkedHashMap<>();

    public NetworkTab(DetailActivity host) {
        super(host);
    }

    @Override
    public String title() {
        return "NETWORK";
    }

    @Override
    protected void onBuild() {
        addSearch("Filter host, IP or port…", this::onRefresh);
        ensureCharts();
        header = UiKit.card(host);
        root.addView(header);
        adapter = new Row.Adapter(host);
        useList(adapter);
    }

    @Override
    public void onRefresh() {
        if (header == null) {
            return;
        }
        renderHeader();
        renderList();
    }

    private void ensureCharts() {
        if (upChart != null) {
            return;
        }
        upChart = new SparklineView(host);
        upChart.setColor(UiKit.color(host, R.color.accent));
        downChart = new SparklineView(host);
        downChart.setColor(UiKit.color(host, R.color.accent2));
    }

    private void renderHeader() {
        header.removeAllViews();
        MonitorState state = MonitorHub.get().stateSnapshot();
        boolean mine = MonitorService.isRunning() && host.pkg().equals(MonitorHub.get().pkg);

        header.addView(UiKit.text(host, "THROUGHPUT", 10, R.color.muted));

        ensureCharts();
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(host, 58));
        chartLp.bottomMargin = UiKit.dp(host, 6);
        header.addView(upChart, chartLp);
        header.addView(downChart, chartLp);

        long now = System.currentTimeMillis();
        if (lastSample == 0 || now - lastSample > 900) {
            lastSample = now;
            upChart.addSample(state.upRate);
            downChart.addSample(state.downRate);
        }
        upChart.setHeader("↑ upload", Fmt.rate(state.upRate));
        downChart.setHeader("↓ download", Fmt.rate(state.downRate));

        header.addView(UiKit.divider(host));
        header.addView(UiKit.keyValue(host, "Total upload",
                state.bytesUp < 0 ? "n/a" : Fmt.bytes(state.bytesUp), R.color.accent));
        header.addView(UiKit.keyValue(host, "Total download",
                state.bytesDown < 0 ? "n/a" : Fmt.bytes(state.bytesDown), R.color.accent2));
        header.addView(UiKit.keyValue(host, "Counter source",
                Fmt.nz(state.byteSource, "not sampling"), R.color.text_dim));
        boolean perFlow = state.sources.contains("tcpdump-flows");
        header.addView(UiKit.keyValue(host, "Per-connection bytes",
                perFlow ? "tcpdump flow accounting" : "per-uid totals only",
                perFlow ? R.color.ok : R.color.text_dim));
        header.addView(UiKit.keyValue(host, "Active connections",
                String.valueOf(state.connectionCount), R.color.text));
        header.addView(UiKit.keyValue(host, "DNS queries",
                state.dnsQueries + " (" + state.distinctDomains + " domains)", R.color.text));
        header.addView(UiKit.keyValue(host, "Sources",
                state.sources.isEmpty() ? "none" : String.join(", ", state.sources),
                R.color.text_dim));
        if (!mine) {
            summaryText = UiKit.text(host,
                    "Idle — start monitoring to record connections.", 12, R.color.text_faint);
            summaryText.setPadding(0, UiKit.dp(host, 8), 0, 0);
            header.addView(summaryText);
        }
    }

    private void renderList() {
        List<ConnectionItem> connections = new ArrayList<>(host.connections());
        String q = query().toLowerCase(Locale.US);
        List<Row> rows = new ArrayList<>();
        List<ConnectionItem> filtered = new ArrayList<>();
        for (ConnectionItem item : connections) {
            if (!q.isEmpty()) {
                String hay = (item.target() + " " + item.remoteIp + " " + item.proto
                        + " " + item.remotePort).toLowerCase(Locale.US);
                if (!hay.contains(q)) {
                    continue;
                }
            }
            filtered.add(item);
        }
        Collections.sort(filtered, (a, b) -> {
            if (a.active != b.active) {
                return a.active ? -1 : 1;
            }
            return Long.compare(b.lastSeen, a.lastSeen);
        });
        for (ConnectionItem item : filtered) {
            accumulate(item);
            rows.add(toRow(item));
        }
        if (rows.isEmpty()) {
            rows.add(EventRows.emptyRow(R.drawable.ic_network, "No connections",
                    MonitorService.isRunning()
                            ? "Nothing observed for " + host.pkg() + " yet"
                            : "Press START MONITORING to capture traffic"));
        }
        adapter.setRows(rows);
    }

    private void accumulate(ConnectionItem item) {
        if (item.bytesUp >= 0 && item.bytesUp > 0) {
            hostBytes.put(item.key, item.bytesUp);
        }
    }

    private Row toRow(ConnectionItem item) {
        Row row = new Row();
        row.iconRes = item.proto.startsWith("UDP") ? R.drawable.ic_network : R.drawable.ic_dns;
        row.colorRes = item.active ? R.color.accent : R.color.muted_2;
        String host = DnsHostCache.reverseOrSelf(
                item.remoteHost == null || item.remoteHost.isEmpty()
                        ? item.remoteIp : item.remoteHost);
        row.title = host + (item.remotePort > 0 ? ":" + item.remotePort : "");
        StringBuilder sub = new StringBuilder();
        sub.append(item.proto).append(' ').append(item.stateLabel());
        if (item.localPort > 0) {
            sub.append(" · local :").append(item.localPort);
        }
        if (item.txQueue > 0 || item.rxQueue > 0) {
            sub.append(" · queued ").append(Fmt.bytesShort(item.txQueue + item.rxQueue));
        }
        if (host.equals(item.remoteIp)) {
            sub.append(" · ").append(item.remoteIp);
        }
        row.subtitle = sub.toString();
        row.time = Fmt.hms(item.lastSeen);
        if (item.bytesUp > 0 || item.bytesDown > 0) {
            row.meta = "↑ " + Fmt.bytesShort(item.bytesUp) + "  ↓ " + Fmt.bytesShort(item.bytesDown);
        } else {
            row.meta = "active " + Fmt.duration(item.duration());
        }
        row.badgeColorRes = item.active ? R.color.ok : R.color.muted_2;
        return row;
    }

    @Override
    public void onData() {
        onRefresh();
    }
}
