package com.applens.monitor.ui.tabs;

import android.view.View;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.model.EventItem;
import com.applens.monitor.monitor.MonitorHub;
import com.applens.monitor.monitor.MonitorService;
import com.applens.monitor.monitor.MonitorState;
import com.applens.monitor.ui.DetailActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** TIMELINE: the merged, chronological record of everything observed. */
public class TimelineTab extends TabPage {

    private Row.Adapter adapter;

    public TimelineTab(DetailActivity host) {
        super(host);
    }

    @Override
    public String title() {
        return "TIMELINE";
    }

    @Override
    protected void onBuild() {
        addSearch("Search the record…", this::onRefresh);
        adapter = new Row.Adapter(host);
        useList(adapter);
    }

    @Override
    public void onRefresh() {
        if (adapter == null) {
            return;
        }
        MonitorHub hub = MonitorHub.get();
        String q = query().toLowerCase(Locale.US);
        List<Row> rows = new ArrayList<>();
        String lastClock = "";
        for (EventItem event : hub.events()) {
            if (!q.isEmpty()) {
                String haystack = (event.title + " " + event.detail + " " + event.source)
                        .toLowerCase(Locale.US);
                if (!haystack.contains(q)) {
                    continue;
                }
            }
            Row row = EventRows.toRow(event);
            String clock = event.clock().substring(0, 8);
            row.time = clock.equals(lastClock) ? "" : clock;
            lastClock = clock;
            rows.add(row);
        }
        if (rows.isEmpty()) {
            MonitorState state = hub.stateSnapshot();
            rows.add(EventRows.emptyRow(R.drawable.ic_timeline,
                    MonitorService.isRunning() ? "Timeline is empty" : "Not monitoring",
                    MonitorService.isRunning()
                            ? "Records appear here as events are captured · session "
                            + Fmt.duration(state.elapsed())
                            : "Press START MONITORING to build a record for " + host.pkg()));
        }
        adapter.setRows(rows);
    }

    @Override
    public void onEvent(EventItem event) {
        if (host.pkg().equals(MonitorHub.get().pkg)) {
            onRefresh();
        }
    }
}
