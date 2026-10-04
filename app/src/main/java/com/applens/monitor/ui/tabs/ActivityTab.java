package com.applens.monitor.ui.tabs;

import android.view.View;
import android.widget.Toast;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.model.EventCategory;
import com.applens.monitor.model.EventItem;
import com.applens.monitor.monitor.MonitorHub;
import com.applens.monitor.monitor.MonitorService;
import com.applens.monitor.monitor.MonitorState;
import com.applens.monitor.ui.DetailActivity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** ACTIVITY: the live, filterable activity feed for the monitored application. */
public class ActivityTab extends TabPage {

    private static final EventCategory[] QUICK = {
            EventCategory.ALL, EventCategory.LOCATION, EventCategory.CAMERA,
            EventCategory.MICROPHONE, EventCategory.CONTACTS, EventCategory.PHONE,
            EventCategory.NETWORK, EventCategory.DNS, EventCategory.STORAGE,
            EventCategory.DATABASE, EventCategory.MEDIA, EventCategory.SECURITY,
    };

    private final Set<EventCategory> active = EnumSet.noneOf(EventCategory.class);
    private Row.Adapter adapter;
    private View statusCard;

    public ActivityTab(DetailActivity host) {
        super(host);
    }

    @Override
    public String title() {
        return "ACTIVITY";
    }

    @Override
    protected void onBuild() {
        addSearch("Filter events…", this::onRefresh);
        String[] labels = new String[QUICK.length];
        for (int i = 0; i < QUICK.length; i++) {
            labels[i] = QUICK[i] == EventCategory.ALL
                    ? "ALL" : QUICK[i].label.toUpperCase(Locale.US);
        }
        addChips(labels, null, index -> {
            EventCategory category = QUICK[index];
            if (category == EventCategory.ALL) {
                active.clear();
            } else if (active.contains(category)) {
                active.remove(category);
            } else {
                active.add(category);
            }
            onRefresh();
        });
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
        for (EventItem event : hub.events()) {
            if (!active.isEmpty() && !active.contains(event.category)) {
                continue;
            }
            if (!q.isEmpty()) {
                String haystack = (event.title + " " + event.detail + " " + event.source
                        + " " + event.category.name()).toLowerCase(Locale.US);
                if (!haystack.contains(q)) {
                    continue;
                }
            }
            rows.add(EventRows.toRow(event));
        }
        if (rows.isEmpty()) {
            if (!MonitorService.isRunning() || !host.pkg().equals(hub.pkg)) {
                rows.add(EventRows.emptyRow(R.drawable.ic_pulse, "Not monitoring",
                        "Press START MONITORING to record activity for " + host.pkg()));
            } else {
                MonitorState state = hub.stateSnapshot();
                if (state.lastEvent.isEmpty()) {
                    rows.add(EventRows.emptyRow(R.drawable.ic_history, "Listening…",
                            "No matching activity yet · running "
                                    + Fmt.duration(state.elapsed())));
                }
            }
        }
        adapter.setRows(rows);
    }

    @Override
    public void onEvent(EventItem event) {
        if (host.pkg().equals(MonitorHub.get().pkg) && allowEventRefresh()) {
            onRefresh();
        }
    }

    @Override
    public void onData() {
        onRefresh();
    }
}
