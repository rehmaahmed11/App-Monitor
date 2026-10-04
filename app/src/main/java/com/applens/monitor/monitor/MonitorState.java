package com.applens.monitor.monitor;

import java.util.ArrayList;
import java.util.List;

/** Live counters shown on the monitoring header and the NETWORK tab. */
public class MonitorState {

    public boolean active;
    public long startedAt;
    public int eventCount;
    public int connectionCount;
    public int dnsQueries;
    public int distinctDomains;
    public int processCount;
    public long pssKb;
    public long fileEvents;
    public long bytesUp = -1;
    public long bytesDown = -1;
    public long upRate;
    public long downRate;
    public String byteSource = "";
    public final List<String> sources = new ArrayList<>();
    public String lastEvent = "";

    public long elapsed() {
        return startedAt <= 0 ? 0 : System.currentTimeMillis() - startedAt;
    }

    public void addSource(String s) {
        if (s != null && !sources.contains(s)) {
            sources.add(s);
        }
    }

    public void removeSource(String s) {
        sources.remove(s);
    }

    public MonitorState copy() {
        MonitorState c = new MonitorState();
        c.active = active;
        c.startedAt = startedAt;
        c.eventCount = eventCount;
        c.connectionCount = connectionCount;
        c.dnsQueries = dnsQueries;
        c.distinctDomains = distinctDomains;
        c.processCount = processCount;
        c.pssKb = pssKb;
        c.fileEvents = fileEvents;
        c.bytesUp = bytesUp;
        c.bytesDown = bytesDown;
        c.upRate = upRate;
        c.downRate = downRate;
        c.byteSource = byteSource;
        c.lastEvent = lastEvent;
        c.sources.addAll(sources);
        return c;
    }
}
