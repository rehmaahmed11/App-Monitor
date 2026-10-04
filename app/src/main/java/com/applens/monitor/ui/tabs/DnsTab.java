package com.applens.monitor.ui.tabs;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.model.DnsRecord;
import com.applens.monitor.monitor.MonitorHub;
import com.applens.monitor.monitor.MonitorService;
import com.applens.monitor.monitor.MonitorState;
import com.applens.monitor.net.DnsVpnService;
import com.applens.monitor.ui.DetailActivity;
import com.applens.monitor.ui.UiKit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** DNS: every domain the application resolved, with answers and query counts. */
public class DnsTab extends TabPage {

    private LinearLayout header;
    private Row.Adapter adapter;
    private final Map<String, Integer> typeCounts = new LinkedHashMap<>();

    public DnsTab(DetailActivity host) {
        super(host);
    }

    @Override
    public String title() {
        return "DNS";
    }

    @Override
    protected void onBuild() {
        addSearch("Search domains…", this::onRefresh);
        header = UiKit.card(host);
        root.addView(header);
        adapter = new Row.Adapter(host);
        useList(adapter);
    }

    @Override
    public void onRefresh() {
        renderHeader();
        renderList();
    }

    private void renderHeader() {
        header.removeAllViews();
        MonitorState state = MonitorHub.get().stateSnapshot();
        boolean mine = MonitorService.isRunning() && host.pkg().equals(MonitorHub.get().pkg);
        List<DnsRecord> all = MonitorHub.get().dnsRecords();

        typeCounts.clear();
        for (DnsRecord record : all) {
            typeCounts.merge(record.qtype, 1, Integer::sum);
        }

        header.addView(UiKit.text(host, "DNS ACTIVITY", 10, R.color.muted));
        header.addView(UiKit.keyValue(host, "Queries captured",
                String.valueOf(state.dnsQueries), R.color.accent));
        header.addView(UiKit.keyValue(host, "Distinct domains",
                String.valueOf(all.size()), R.color.accent));
        StringBuilder types = new StringBuilder();
        for (Map.Entry<String, Integer> e : typeCounts.entrySet()) {
            if (types.length() > 0) {
                types.append(" · ");
            }
            types.append(e.getKey()).append(' ').append(e.getValue());
        }
        header.addView(UiKit.keyValue(host, "Query types",
                types.length() == 0 ? "—" : types.toString(), R.color.text_dim));
        header.addView(UiKit.keyValue(host, "Capture layer",
                DnsVpnService.isRunning()
                        ? "VpnService TUN · " + DnsVpnService.statusText()
                        : "/proc + tcpdump fallback", R.color.text_dim));
        if (!DnsVpnService.errorText().isEmpty()) {
            header.addView(UiKit.keyValue(host, "VPN note",
                    DnsVpnService.errorText(), R.color.warn));
        }
        if (!mine) {
            TextView hint = UiKit.text(host,
                    "Idle — start monitoring to capture DNS.", 12, R.color.text_faint);
            hint.setPadding(0, UiKit.dp(host, 8), 0, 0);
            header.addView(hint);
        }
    }

    private void renderList() {
        String q = query().toLowerCase(Locale.US);
        List<Row> rows = new ArrayList<>();
        for (DnsRecord record : MonitorHub.get().dnsRecords()) {
            if (!q.isEmpty() && !record.domain.toLowerCase(Locale.US).contains(q)) {
                continue;
            }
            rows.add(toRow(record));
        }
        if (rows.isEmpty()) {
            rows.add(EventRows.emptyRow(R.drawable.ic_dns, "No DNS activity",
                    "Domains resolved by " + host.pkg() + " will appear here"));
        }
        adapter.setRows(rows);
    }

    private Row toRow(DnsRecord record) {
        Row row = new Row();
        row.iconRes = R.drawable.ic_dns;
        row.colorRes = "AAAA".equals(record.qtype) ? R.color.accent2 : R.color.accent;
        row.title = record.domain;
        StringBuilder sub = new StringBuilder();
        if (!record.answers.isEmpty() && !record.answers.isEmpty()) {
            sub.append(record.answerText());
        } else if (record.resolved) {
            sub.append("no answer");
        } else {
            sub.append("query");
        }
        row.subtitle = sub.toString();
        row.time = Fmt.clock(record.time);
        row.meta = record.qtype + " · " + record.transport + " · " + record.queryCount + "x";
        row.badgeColorRes = record.queryCount > 5 ? R.color.warn : R.color.text_dim;
        return row;
    }

    @Override
    public void onData() {
        // DetailActivity broadcasts package-data updates to every tab, including
        // tabs that have not been opened yet. Do not render until onBuild() has
        // initialized the header and adapter.
        if (header != null && adapter != null) {
            onRefresh();
        }
    }
}
