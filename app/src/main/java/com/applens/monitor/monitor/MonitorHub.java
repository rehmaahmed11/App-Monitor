package com.applens.monitor.monitor;

import com.applens.monitor.core.Bus;
import com.applens.monitor.model.ConnectionItem;
import com.applens.monitor.model.DnsRecord;
import com.applens.monitor.model.EventItem;
import com.applens.monitor.model.ProcessStat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-wide, thread safe store shared between the monitoring service and the
 * UI. Keeps the rolling activity feed, the live connection table and the DNS
 * history so any tab can bind to the same data without re-querying.
 */
public final class MonitorHub {

    private static final int MAX_EVENTS = 4000;

    private static volatile MonitorHub instance;

    public final Bus<EventItem> events = new Bus<>();
    public final Bus<MonitorState> state = new Bus<>();
    public final Bus<Boolean> tick = new Bus<>();

    private final List<EventItem> eventLog = new ArrayList<>();
    private final Map<String, ConnectionItem> connections = new LinkedHashMap<>();
    private final Map<String, DnsRecord> dns = new LinkedHashMap<>();
    private final List<ProcessStat> processes = new ArrayList<>();
    private final MonitorState live = new MonitorState();

    public volatile String pkg = "";
    public volatile String label = "";
    public volatile int uid = -1;
    public volatile int pid = 0;

    private MonitorHub() {
    }

    public static MonitorHub get() {
        MonitorHub local = instance;
        if (local == null) {
            synchronized (MonitorHub.class) {
                local = instance;
                if (local == null) {
                    local = new MonitorHub();
                    instance = local;
                }
            }
        }
        return local;
    }

    // ------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------

    public synchronized void begin(String pkg, String label, int uid) {
        this.pkg = pkg;
        this.label = label;
        this.uid = uid;
        eventLog.clear();
        connections.clear();
        dns.clear();
        processes.clear();
        live.active = true;
        live.startedAt = System.currentTimeMillis();
        live.eventCount = 0;
        live.connectionCount = 0;
        live.dnsQueries = 0;
        live.distinctDomains = 0;
        live.processCount = 0;
        live.pssKb = 0;
        live.fileEvents = 0;
        live.bytesUp = -1;
        live.bytesDown = -1;
        live.upRate = 0;
        live.downRate = 0;
        live.lastEvent = "";
        live.sources.clear();
    }

    public synchronized void end() {
        live.active = false;
    }

    public boolean isActive() {
        return live.active;
    }

    public MonitorState stateSnapshot() {
        synchronized (this) {
            return live.copy();
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    public void publish(EventItem event) {
        if (event == null) {
            return;
        }
        synchronized (this) {
            eventLog.add(0, event);
            while (eventLog.size() > MAX_EVENTS) {
                eventLog.remove(eventLog.size() - 1);
            }
            live.eventCount++;
            live.lastEvent = event.title;
        }
        events.post(event);
    }

    public List<EventItem> events() {
        synchronized (this) {
            return new ArrayList<>(eventLog);
        }
    }

    // ------------------------------------------------------------------
    // Connections
    // ------------------------------------------------------------------

    public void upsertConnection(ConnectionItem item) {
        synchronized (this) {
            connections.put(item.key, item);
            live.connectionCount = countActive();
        }
    }

    private int countActive() {
        int n = 0;
        for (ConnectionItem c : connections.values()) {
            if (c.active) {
                n++;
            }
        }
        return n;
    }

    public void markClosedExcept(java.util.Set<String> liveKeys) {
        synchronized (this) {
            for (ConnectionItem c : connections.values()) {
                if (c.active && !liveKeys.contains(c.key)) {
                    c.active = false;
                    c.lastSeen = System.currentTimeMillis();
                }
            }
            live.connectionCount = countActive();
        }
    }

    public List<ConnectionItem> connections() {
        synchronized (this) {
            return new ArrayList<>(connections.values());
        }
    }

    public ConnectionItem connectionByRemoteIp(String ip) {
        synchronized (this) {
            for (ConnectionItem c : connections.values()) {
                if (c.remoteIp.equals(ip)) {
                    return c;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // DNS
    // ------------------------------------------------------------------

    public DnsRecord recordDns(DnsRecord record) {
        DnsRecord out;
        synchronized (this) {
            String key = record.domain + "|" + record.qtype;
            DnsRecord existing = dns.get(key);
            if (existing == null) {
                record.queryCount = 1;
                dns.put(key, record);
                out = record;
                live.distinctDomains = dns.size();
            } else {
                existing.time = record.time;
                existing.queryCount++;
                existing.transport = record.transport;
                existing.server = record.server;
                existing.answers = record.answers;
                existing.resolved = record.resolved;
                existing.pkg = record.pkg;
                existing.uid = record.uid;
                out = existing;
            }
            live.dnsQueries++;
        }
        return out;
    }

    public List<DnsRecord> dnsRecords() {
        synchronized (this) {
            List<DnsRecord> out = new ArrayList<>(dns.values());
            out.sort((a, b) -> Long.compare(b.time, a.time));
            return out;
        }
    }

    // ------------------------------------------------------------------
    // Processes
    // ------------------------------------------------------------------

    public void setProcesses(List<ProcessStat> stats) {
        synchronized (this) {
            processes.clear();
            if (stats != null) {
                processes.addAll(stats);
            }
            live.processCount = processes.size();
            long pss = 0;
            for (ProcessStat s : processes) {
                pss += s.pssKb > 0 ? s.pssKb : s.rssKb;
            }
            live.pssKb = pss;
        }
    }

    public List<ProcessStat> processes() {
        synchronized (this) {
            return new ArrayList<>(processes);
        }
    }

    // ------------------------------------------------------------------
    // Byte counters
    // ------------------------------------------------------------------

    public synchronized void addSource(String source) {
        live.addSource(source);
    }

    public synchronized void setBytes(long up, long down, long upRate, long downRate, String source) {
        live.bytesUp = up;
        live.bytesDown = down;
        live.upRate = upRate;
        live.downRate = downRate;
        if (source != null && !source.isEmpty()) {
            live.byteSource = source;
        }
    }

    public synchronized void addFileEvents(int n) {
        live.fileEvents += n;
    }

    /** Publishes the current state to every observer. */
    public void publishState() {
        state.post(stateSnapshot());
    }

    public static List<EventItem> emptyList() {
        return Collections.<EventItem>emptyList();
    }
}
