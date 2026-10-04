package com.applens.monitor.ui.tabs;

import com.applens.monitor.R;
import com.applens.monitor.model.EventItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Converts activity events into the shared list row model. */
public final class EventRows {

    private EventRows() {
    }

    public static Row toRow(EventItem event) {
        Row row = new Row();
        row.iconRes = event.category.iconRes;
        row.colorRes = event.category.colorRes;
        row.title = event.title;
        StringBuilder subtitle = new StringBuilder();
        if (!event.detail.isEmpty()) {
            subtitle.append(event.detail);
        }
        if (!event.source.isEmpty()) {
            if (subtitle.length() > 0) {
                subtitle.append("  ·  ");
            }
            subtitle.append(event.source);
        }
        if (event.pid > 0) {
            subtitle.append("  ·  pid ").append(event.pid);
        }
        row.subtitle = subtitle.toString();
        row.time = event.clock();
        row.meta = event.category.label.toUpperCase(Locale.US);
        row.badgeColorRes = event.category.colorRes;
        row.badge = event.category.name();
        return row;
    }

    public static List<Row> toRows(List<EventItem> events) {
        List<Row> rows = new ArrayList<>(events.size());
        for (EventItem event : events) {
            rows.add(toRow(event));
        }
        return rows;
    }

    public static Row emptyRow(int iconRes, String title, String subtitle) {
        Row row = new Row();
        row.iconRes = iconRes;
        row.colorRes = R.color.muted_2;
        row.title = title;
        row.subtitle = subtitle;
        return row;
    }
}
