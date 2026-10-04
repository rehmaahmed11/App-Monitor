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
    /** Never repaint the dashboard more often than this (ms). */
    private static final long STATE_INTERVAL_MS = 400;
    /** Never deliver more than one activity burst per this many ms. */
    private static final long EVENT_INTERVAL_MS = 200;

    private static volatile MonitorHub instance;

    public final Bus<EventItem> events = new Bus<>();
    public final Bus<MonitorState> state = new Bus<>();
    public final Bus<Boolean> tick = new Bus<>();

    private final List<EventItem> eventLog = new ArrayList<>();
    private final Map<String, ConnectionItem> connections = new LinkedHashMap<>();
    private final Map<String, DnsRecord> dns = new LinkedHashMap<>();
    private final List<ProcessStat> processes = new ArrayList<>();
    private final MonitorState live = new MonitorState();

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Object eventGate = new Object();
    private final Object stateGate = new Object();
    private EventItem pendingEvent;
    private boolean eventPostScheduled;
    private boolean statePostScheduled;
    private volatile long lastEventPost;
    private volatile long lastStatePost;

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

    /**
     * Records an event and tells the UI about it.
     *
     * <p>A busy application can produce thousands of log lines per second. Posting
     * one main-thread message per event used to starve the UI thread completely —
     * the dashboard froze, Android could not even finish the transition to the app
     * being monitored, and the session ended in an ANR kill. Events are therefore
     * coalesced: the store is always exact, while observers are woken at most five
     * times a second with the newest event.</p>
     */
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
        if (!events.hasSubscribers()) {
            return;
        }
        boolean schedule;
        synchronized (eventGate) {
            pendingEvent = event;
            schedule = !eventPostScheduled;
            if (schedule) {
                eventPostScheduled = true;
            }
        }
        if (!schedule) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        long delay = Math.max(0, lastEventPost + EVENT_INTERVAL_MS - now);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                EventItem latest;
                synchronized (eventGate) {
                    latest = pendingEvent;
                    pendingEvent = null;
                    eventPostScheduled = false;
                }
                lastEventPost = android.os.SystemClock.uptimeMillis();
                if (latest != null) {
                    events.postNow(latest);
                }
            }
        }, delay);
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

    /**
     * Publishes the current state to every observer, at most a few times a second.
     * Every repaint rebuilds the visible tab, so an unthrottled call from each
     * sampler is what used to make the dashboard unusable.
     */
    public void publishState() {
        if (!state.hasSubscribers()) {
            state.post(stateSnapshot());
            return;
        }
        synchronized (stateGate) {
            if (statePostScheduled) {
                return;
            }
            statePostScheduled = true;
        }
        long now = android.os.SystemClock.uptimeMillis();
        long delay = Math.max(0, lastStatePost + STATE_INTERVAL_MS - now);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (stateGate) {
                    statePostScheduled = false;
                }
                lastStatePost = android.os.SystemClock.uptimeMillis();
                state.postNow(stateSnapshot());
            }
        }, delay);
    }

    /** Publishes immediately, used when a session starts or stops. */
    public void publishStateNow() {
        state.post(stateSnapshot());
    }

    public static List<EventItem> emptyList() {
        return Collections.<EventItem>emptyList();
    }
}
