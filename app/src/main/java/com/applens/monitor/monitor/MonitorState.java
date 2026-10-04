package com.applens.monitor.monitor;

import java.util.ArrayList;
import java.util.List;

/** Live counters shown on the monitoring header and the NETWORK tab. */
public class MonitorState {

    /**
     * Where the session currently is. The UI renders the start/stop button from
     * this instead of asking the service, so the button can never claim "running"
     * while the monitors are still coming up (or "idle" while they are shutting
     * down).
     */
    public enum Phase {
        /** No session. */
        IDLE("Idle"),
        /** The session was requested; samplers are coming up. */
        STARTING("Starting…"),
        /** Samplers are live and the record file is open. */
        RUNNING("Monitoring"),
        /** The user (or the system) asked to stop; teardown is running. */
        STOPPING("Stopping…");

        public final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    public boolean active;
    public Phase phase = Phase.IDLE;
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

    /** Why the previous session ended ("stopped by user", "service killed", …). */
    public String stopReason = "";
    /** Where the plain-text record is being written, or an explanation. */
    public String recordPath = "";
    public String recordNote = "";
    public long recordBytes;
    /** Set when the session could not be brought up at all. */
    public String startError = "";
    /** DNS capture state as reported by the VPN service. */
    public String vpnStatus = "";
    public String vpnError = "";

    public long elapsed() {
        return startedAt <= 0 ? 0 : System.currentTimeMillis() - startedAt;
    }

    /** True while the session should be shown as live (starting or running). */
    public boolean live() {
        return phase == Phase.RUNNING || phase == Phase.STARTING;
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
        c.phase = phase;
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
        c.stopReason = stopReason;
        c.recordPath = recordPath;
        c.recordNote = recordNote;
        c.recordBytes = recordBytes;
        c.startError = startError;
        c.vpnStatus = vpnStatus;
        c.vpnError = vpnError;
        c.sources.addAll(sources);
        return c;
    }
}
