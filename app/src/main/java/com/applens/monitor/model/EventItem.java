package com.applens.monitor.model;

import com.applens.monitor.core.Fmt;

/** One entry of the real-time activity feed / timeline. */
public class EventItem {

    public long time;
    public EventCategory category = EventCategory.SYSTEM;
    public String title = "";
    public String detail = "";
    public String source = "";
    public String pkg = "";
    public int pid;
    public int uid = -1;
    public boolean important;

    public EventItem() {
    }

    public static EventItem of(EventCategory category, String title, String detail, String source) {
        EventItem e = new EventItem();
        e.time = System.currentTimeMillis();
        e.category = category;
        e.title = title == null ? "" : title;
        e.detail = detail == null ? "" : detail;
        e.source = source == null ? "" : source;
        return e;
    }

    public String clock() {
        return Fmt.clock(time);
    }

    /** Single-line rendering used by the plain-text records on /sdcard. */
    public String toLogLine(String appPkg) {
        StringBuilder sb = new StringBuilder();
        sb.append(Fmt.clock(time)).append("  ");
        sb.append("[").append(category.name()).append("] ");
        sb.append(title);
        if (!detail.isEmpty()) {
            sb.append(" — ").append(Fmt.limit(detail, 400));
        }
        if (!source.isEmpty()) {
            sb.append("  <source: ").append(source).append(">");
        }
        if (pid > 0) {
            sb.append("  <pid: ").append(pid).append(">");
        }
        sb.append("  <app: ").append(appPkg == null || appPkg.isEmpty() ? pkg : appPkg).append(">");
        return sb.toString();
    }
}
