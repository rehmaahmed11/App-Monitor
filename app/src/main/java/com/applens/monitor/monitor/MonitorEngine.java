package com.applens.monitor.monitor;

import android.content.Context;

import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.LogSettings;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;
import com.applens.monitor.log.DiagnosticLog;
import com.applens.monitor.log.SessionReport;
import com.applens.monitor.model.ConnectionItem;
import com.applens.monitor.model.EventCategory;
import com.applens.monitor.model.EventItem;
import com.applens.monitor.model.ProcessStat;
import com.applens.monitor.repo.ProcessRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The monitoring orchestrator. Owns every sampler for the selected application and
 * keeps the rolling feeds in {@link MonitorHub} plus the plain-text record on
 * shared storage.
 */
public final class MonitorEngine {

    private static final long SOCKET_PERIOD_MS = 1000;
    private static final long PROCESS_PERIOD_MS = 1500;
    private static final long BYTES_PERIOD_MS = 4000;
    private static final long MEMORY_PERIOD_MS = 12000;
    /** Upper bound on events handed to the UI feed per second. */
    private static final int MAX_EVENTS_PER_SECOND = 60;
    /** Minimum CPU time (in scheduler ticks) before a process is worth reporting. */
    private static final long CPU_TICKS_THRESHOLD = 5;

    private final Context context;
    private final String pkg;
    private final String label;
    private final int uid;

    private final MonitorHub hub = MonitorHub.get();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<Integer, Long> lastCpuTicks = new HashMap<>();
    private final Set<Long> socketInodes = new HashSet<>();
    private final Set<String> knownConnections = new HashSet<>();

    private final Object rateLock = new Object();
    private long windowStart;
    private int windowCount;
    private long droppedInWindow;
    private long lastThrottleNotice;

    private ScheduledExecutorService scheduler;
    /** Serialises the heavier report work (snapshot, heartbeat) off the samplers. */
    private final java.util.concurrent.ExecutorService reportIo =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "applens-report");
                t.setDaemon(true);
                return t;
            });
    private volatile String stopReason = "";
    private LogcatMonitor logcatMonitor;
    private FileWatchMonitor fileWatch;
    private TcpdumpMonitor tcpdump;
    private SocketMonitor socketMonitor;
    private ByteCounter byteCounter;
    private TcpdumpFlowMonitor flowMonitor;
    private final Map<String, ConnectionItem> byFlowKey = new HashMap<>();

    public MonitorEngine(Context context, String pkg, String label, int uid) {
        this.context = context.getApplicationContext();
        this.pkg = pkg;
        this.label = label;
        this.uid = uid;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        hub.begin(pkg, label, uid);
        hub.publish(EventItem.of(EventCategory.PROCESS, "Monitoring started",
                "Target: " + label + " (" + pkg + "), uid " + uid, "AppLens"));
        ActivityLogWriter.get().writeRaw("--- monitoring session for " + pkg + " started ---");
        syncRecordState();

        socketMonitor = new SocketMonitor(pkg);
        byteCounter = new ByteCounter(uid);

        // Every sampler is optional. One that cannot start is a missing data
        // source, never a failed session — and never an uncaught exception on the
        // bootstrap thread, which would take the whole application down.
        if (RootShell.get().ensureRoot()) {
            try {
                byteCounter.install();
                hub.addSource("byte:" + byteCounter.source());
            } catch (Throwable error) {
                DiagnosticLog.recordProblem("Traffic counters could not be installed", error);
            }
        }

        try {
            logcatMonitor = new LogcatMonitor(pkg, new LogcatMonitor.ActivitySink() {
                @Override
                public void onEvent(EventItem event) {
                    publish(event);
                }
            });
            if (logcatMonitor.isSupported()) {
                logcatMonitor.start();
                hub.addSource("logcat");
            }
        } catch (Throwable error) {
            DiagnosticLog.recordProblem("Activity log reader could not be started", error);
        }

        try {
            fileWatch = new FileWatchMonitor(pkg, dataDir(), new FileWatchMonitor.ActivitySink() {
                @Override
                public void onEvent(EventItem event) {
                    publish(event);
                }
            });
            if (fileWatch.isSupported()) {
                fileWatch.start();
                hub.addSource("inotifyd");
            }
        } catch (Throwable error) {
            DiagnosticLog.recordProblem("File watcher could not be started", error);
        }

        if (com.applens.monitor.net.DnsVpnService.isRunning()) {
            hub.addSource("vpn-dns");
        }
        try {
            // tcpdump() is null unless the binary really exists, so nothing is
            // spawned (and no superuser prompt raised) on a device without it.
            if (RootShell.get().tcpdump() != null) {
                tcpdump = new TcpdumpMonitor(pkg);
                tcpdump.start();
                hub.addSource("tcpdump");
                flowMonitor = new TcpdumpFlowMonitor();
                flowMonitor.start(this::onPacket);
                hub.addSource("tcpdump-flows");
            }
        } catch (Throwable error) {
            DiagnosticLog.recordProblem("Packet capture could not be started", error);
        }

        scheduler = Executors.newScheduledThreadPool(3, runnable -> {
            Thread t = new Thread(runnable, "applens-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::sampleProcesses,
                200, PROCESS_PERIOD_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::sampleSockets,
                600, SOCKET_PERIOD_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::sampleBytes,
                3000, BYTES_PERIOD_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::sampleMemory,
                5000, MEMORY_PERIOD_MS, TimeUnit.MILLISECONDS);
        int heartbeatMinutes = LogSettings.heartbeatMinutes();
        if (LogSettings.fullReport() && heartbeatMinutes > 0) {
            long period = heartbeatMinutes * 60_000L;
            scheduler.scheduleWithFixedDelay(this::heartbeat, period, period,
                    TimeUnit.MILLISECONDS);
        }
        if (LogSettings.fullReport()) {
            // The snapshot is heavy (dumpsys, du, device probe): it must never
            // delay the launch of the monitored application.
            reportIo.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        SessionReport.writeStart(context, pkg, label, uid,
                                ActivityLogWriter.get().path());
                    } catch (Throwable error) {
                        DiagnosticLog.recordProblem("The start-of-session snapshot failed", error);
                    }
                }
            });
        }
        hub.markRunning();
        hub.publishStateNow();
    }

    /** Copies the writer's current path/status into the shared state for the UI. */
    public void syncRecordState() {
        ActivityLogWriter writer = ActivityLogWriter.get();
        hub.setRecord(writer.path(), writer.status());
        hub.setRecordBytes(writer.writtenBytes());
    }

    private void heartbeat() {
        if (!running.get()) {
            return;
        }
        try {
            SessionReport.writeHeartbeat(pkg, hub.stateSnapshot(), hub.dnsRecords(),
                    hub.connections());
            syncRecordState();
        } catch (Throwable error) {
            DiagnosticLog.recordThrottledProblem("report-heartbeat",
                    "The periodic report heartbeat failed", error);
        }
    }

    public void stop() {
        stop(stopReason.isEmpty() ? "stopped" : stopReason);
    }

    /** Records why the session ends; the value is written into the summary. */
    public void setStopReason(String reason) {
        if (reason != null && !reason.isEmpty()) {
            stopReason = reason;
        }
    }

    public void stop(String reason) {
        if (reason != null && !reason.isEmpty()) {
            stopReason = reason;
        }
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (logcatMonitor != null) {
            closeQuietly(new Runnable() {
                @Override
                public void run() {
                    logcatMonitor.stop();
                }
            }, "logcat reader");
        }
        if (fileWatch != null) {
            closeQuietly(new Runnable() {
                @Override
                public void run() {
                    fileWatch.stop();
                }
            }, "file watcher");
        }
        if (tcpdump != null) {
            closeQuietly(new Runnable() {
                @Override
                public void run() {
                    tcpdump.stop();
                }
            }, "packet capture");
        }
        if (flowMonitor != null) {
            closeQuietly(new Runnable() {
                @Override
                public void run() {
                    flowMonitor.stop();
                }
            }, "flow accounting");
        }
        if (byteCounter != null) {
            closeQuietly(new Runnable() {
                @Override
                public void run() {
                    byteCounter.uninstall();
                }
            }, "traffic counters");
        }
        MonitorState state = hub.stateSnapshot();
        EventItem e = EventItem.of(EventCategory.PROCESS, "Monitoring stopped",
                "Captured " + state.eventCount + " events (" + stopReason + ")", "AppLens");
        hub.publish(e);
        ActivityLogWriter.get().writeRaw("--- monitoring session ended: " + stopReason + " ---");
        if (LogSettings.fullReport()) {
            try {
                ActivityLogWriter writer = ActivityLogWriter.get();
                SessionReport.writeSummary(context, pkg, label, stopReason, state,
                        hub.events(), hub.dnsRecords(), hub.connections(), hub.processes(),
                        writer.path(), writer.writtenBytes());
            } catch (Throwable error) {
                DiagnosticLog.recordProblem("The session summary could not be written", error);
            }
        }
        ActivityLogWriter.get().flush();
        syncRecordState();
        hub.publishStateNow();
        // The engine is per session; its report thread must not outlive it.
        reportIo.shutdown();
    }

    /** Shutting a sampler down must never prevent the next one from stopping. */
    private static void closeQuietly(Runnable action, String what) {
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (Throwable error) {
            DiagnosticLog.recordThrottledProblem("stop-" + what,
                    "Could not stop the " + what + " cleanly", error);
        }
    }

    private String dataDir() {
        try {
            android.content.pm.ApplicationInfo ai = context.getPackageManager()
                    .getApplicationInfo(pkg, 0);
            return ai != null ? ai.dataDir : "/data/user/0/" + pkg;
        } catch (Throwable t) {
            return "/data/user/0/" + pkg;
        }
    }

    // ------------------------------------------------------------------
    // Samplers
    // ------------------------------------------------------------------

    private void sampleProcesses() {
        if (!running.get()) {
            return;
        }
        try {
            List<ProcessStat> stats = ProcessRepository.snapshot(pkg);
            Set<Integer> pids = new HashSet<>();
            for (ProcessStat stat : stats) {
                pids.add(stat.pid);
                long cpu = stat.utimeTicks + stat.stimeTicks;
                Long previous = lastCpuTicks.get(stat.pid);
                boolean isNew = previous == null;
                if (previous != null) {
                    long delta = cpu - previous;
                    // A couple of scheduler ticks is background noise; reporting it
                    // every 1.5 s per process buried the interesting events.
                    if (delta >= CPU_TICKS_THRESHOLD) {
                        EventItem e = EventItem.of(EventCategory.PERFORMANCE, "CPU activity",
                                stat.name + " used " + delta + " ticks ("
                                        + Fmt.bytes(stat.rssKb * 1024) + " RSS, "
                                        + stat.threads + " threads)", "proc/" + stat.pid + "/stat");
                        e.pid = stat.pid;
                        e.pkg = pkg;
                        publish(e);
                    }
                }
                lastCpuTicks.put(stat.pid, cpu);
                if (isNew) {
                    EventItem e = EventItem.of(EventCategory.PROCESS, "Process started",
                            stat.name + " (pid " + stat.pid + ", uid " + stat.uid + ")", "/proc");
                    e.pid = stat.pid;
                    e.pkg = pkg;
                    e.important = true;
                    publish(e);
                }
            }
            // processes that disappeared
            for (Integer pid : new HashSet<>(lastCpuTicks.keySet())) {
                if (!pids.contains(pid)) {
                    lastCpuTicks.remove(pid);
                    EventItem e = EventItem.of(EventCategory.PROCESS, "Process stopped",
                            "pid " + pid + " exited", "/proc");
                    e.pid = pid;
                    e.pkg = pkg;
                    publish(e);
                }
            }
            if (logcatMonitor != null) {
                logcatMonitor.setPids(pids);
            }
            hub.setProcesses(stats);
            if (!stats.isEmpty()) {
                socketInodes.clear();
                socketInodes.addAll(ProcessRepository.socketInodes(pkg));
            }
        } catch (Throwable error) {
            DiagnosticLog.recordThrottledProblem("monitor-process-sample",
                    "Process monitor sampler failed", error);
        }
    }

    private void sampleSockets() {
        if (!running.get() || socketMonitor == null) {
            return;
        }
        try {
            List<ConnectionItem> items = socketMonitor.poll(socketInodes, uid);
            Set<String> live = new HashSet<>();
            for (ConnectionItem item : items) {
                live.add(item.key);
                if (!knownConnections.contains(item.key)) {
                    knownConnections.add(item.key);
                    EventItem e = EventItem.of(EventCategory.NETWORK, "Connection opened",
                            item.proto + " " + item.targetWithPort() + " (" + item.stateLabel() + ")",
                            "/proc/net");
                    e.pkg = pkg;
                    publish(e);
                }
                attachDnsLabel(item);
                hub.upsertConnection(item);
                byFlowKey.put(flowKey(item.proto, item.localIp(), item.localPort,
                        item.remoteIp, item.remotePort), item);
            }
            for (String key : new HashSet<>(knownConnections)) {
                if (!live.contains(key)) {
                    knownConnections.remove(key);
                }
            }
            hub.markClosedExcept(live);
        } catch (Throwable error) {
            DiagnosticLog.recordThrottledProblem("monitor-socket-sample",
                    "Network connection sampler failed", error);
        }
    }

    private void attachDnsLabel(ConnectionItem item) {
        if (item.remoteHost != null && !item.remoteHost.isEmpty()) {
            return;
        }
        String host = DnsHostCache.host(item.remoteIp);
        if (host != null) {
            item.remoteHost = host;
            item.dnsQueries = 1;
        }
    }

    private static String flowKey(String proto, String localIp, int localPort,
                                  String remoteIp, int remotePort) {
        return proto + "|" + localIp + ":" + localPort + ">" + remoteIp + ":" + remotePort;
    }

    /** Applies a captured packet to the matching connection's byte counters. */
    private void onPacket(String localIp, int localPort, String remoteIp, int remotePort,
                          String proto, int bytes, boolean outbound) {
        String key = flowKey(proto, localIp, localPort, remoteIp, remotePort);
        ConnectionItem item = byFlowKey.get(key);
        if (item == null) {
            item = byFlowKey.get(flowKey(proto, remoteIp, remotePort, localIp, localPort));
            if (item == null) {
                return;
            }
        }
        synchronized (item) {
            if (item.bytesUp < 0) {
                item.bytesUp = 0;
            }
            if (item.bytesDown < 0) {
                item.bytesDown = 0;
            }
            if (outbound) {
                item.bytesUp += bytes;
            } else {
                item.bytesDown += bytes;
            }
        }
    }

    private void sampleBytes() {
        if (!running.get() || byteCounter == null) {
            return;
        }
        try {
            long read = 0;
            long write = 0;
            for (ProcessStat stat : hub.processes()) {
                read += stat.readBytes;
                write += stat.writeBytes;
            }
            ByteCounter.Sample sample = byteCounter.sample(read, write);
            hub.setBytes(sample.up, sample.down, sample.upRate, sample.downRate, sample.source);
            hub.publishState();
        } catch (Throwable error) {
            DiagnosticLog.recordThrottledProblem("monitor-byte-sample",
                    "Traffic counter sampler failed", error);
        }
    }

    private void sampleMemory() {
        if (!running.get()) {
            return;
        }
        try {
            if (!RootShell.get().ensureRoot()) {
                return;
            }
            String dump = RootShell.get().exec("dumpsys meminfo " + RootShell.shQuote(pkg)
                    + " 2>/dev/null | head -40", 8000);
            if (dump == null) {
                return;
            }
            for (String line : dump.split("\n")) {
                String t = line.trim();
                if (t.startsWith("TOTAL PSS:")) {
                    String[] f = t.replace("TOTAL PSS:", "").trim().split("\\s+");
                    if (f.length > 0) {
                        try {
                            long pss = Long.parseLong(f[0]);
                            EventItem e = EventItem.of(EventCategory.PERFORMANCE, "Memory sample",
                                    "Total PSS " + Fmt.bytes(pss * 1024), "dumpsys meminfo");
                            e.pkg = pkg;
                            publish(e);
                        } catch (Throwable ignored) {
                            // malformed
                        }
                    }
                    break;
                }
            }
        } catch (Throwable error) {
            DiagnosticLog.recordThrottledProblem("monitor-memory-sample",
                    "Memory monitor sampler failed", error);
        }
    }

    /**
     * Central publish path: hub + plain-text record.
     *
     * <p>The text record always gets everything. The in-memory feed is capped at
     * {@link #MAX_EVENTS_PER_SECOND}: a chatty application can emit thousands of
     * log lines a second and the UI cannot — and should not — try to draw them
     * all. Whenever the cap bites, one notice is published so the gap is visible
     * instead of silent.</p>
     */
    public void publish(EventItem event) {
        if (event == null) {
            return;
        }
        if (event.pkg == null || event.pkg.isEmpty()) {
            event.pkg = pkg;
        }
        ActivityLogWriter.get().writeRaw(event.toLogLine(pkg));
        if (!admit()) {
            return;
        }
        hub.publish(event);
        if (event.important) {
            hub.publishState();
        }
    }

    /** Token bucket over a one second window; also emits the "throttled" notice. */
    private boolean admit() {
        long now = System.currentTimeMillis();
        long dropped = 0;
        synchronized (rateLock) {
            if (now - windowStart >= 1000L) {
                windowStart = now;
                windowCount = 0;
                if (droppedInWindow > 0 && now - lastThrottleNotice > 5000L) {
                    dropped = droppedInWindow;
                    lastThrottleNotice = now;
                }
                droppedInWindow = 0;
            }
            if (windowCount >= MAX_EVENTS_PER_SECOND) {
                droppedInWindow++;
                return false;
            }
            windowCount++;
        }
        if (dropped > 0) {
            EventItem notice = EventItem.of(EventCategory.SYSTEM, "Activity feed throttled",
                    dropped + " event(s) were written to the record file but not shown here",
                    "AppLens");
            notice.pkg = pkg;
            hub.publish(notice);
        }
        return true;
    }

    public List<String> sources() {
        return new ArrayList<>(hub.stateSnapshot().sources);
    }
}
