package com.applens.monitor.monitor;

import android.content.Context;

import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;
import com.applens.monitor.log.DiagnosticLog;
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

    private final Context context;
    private final String pkg;
    private final String label;
    private final int uid;

    private final MonitorHub hub = MonitorHub.get();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<Integer, Long> lastCpuTicks = new HashMap<>();
    private final Set<Long> socketInodes = new HashSet<>();
    private final Set<String> knownConnections = new HashSet<>();

    private ScheduledExecutorService scheduler;
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

        socketMonitor = new SocketMonitor(pkg);
        byteCounter = new ByteCounter(uid);

        if (RootShell.get().ensureRoot()) {
            byteCounter.install();
            hub.addSource("byte:" + byteCounter.source());
        }

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

        if (com.applens.monitor.net.DnsVpnService.isRunning()) {
            hub.addSource("vpn-dns");
        }
        if (RootShell.get().tcpdump() != null) {
            tcpdump = new TcpdumpMonitor(pkg);
            tcpdump.start();
            hub.addSource("tcpdump");
            flowMonitor = new TcpdumpFlowMonitor();
            flowMonitor.start(this::onPacket);
            hub.addSource("tcpdump-flows");
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
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (logcatMonitor != null) {
            logcatMonitor.stop();
        }
        if (fileWatch != null) {
            fileWatch.stop();
        }
        if (tcpdump != null) {
            tcpdump.stop();
        }
        if (flowMonitor != null) {
            flowMonitor.stop();
        }
        if (byteCounter != null) {
            byteCounter.uninstall();
        }
        EventItem e = EventItem.of(EventCategory.PROCESS, "Monitoring stopped",
                "Captured " + hub.stateSnapshot().eventCount + " events", "AppLens");
        hub.publish(e);
        ActivityLogWriter.get().writeRaw("--- monitoring session ended ---");
        ActivityLogWriter.get().flush();
        hub.publishState();
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
                    if (delta > 0) {
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
                    + " 2>/dev/null | head -40", 20000);
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

    /** Central publish path: hub + plain-text record. */
    public void publish(EventItem event) {
        if (event == null) {
            return;
        }
        if (event.pkg == null || event.pkg.isEmpty()) {
            event.pkg = pkg;
        }
        hub.publish(event);
        ActivityLogWriter.get().writeRaw(event.toLogLine(pkg));
        if (event.important) {
            hub.publishState();
        }
    }

    public List<String> sources() {
        return new ArrayList<>(hub.stateSnapshot().sources);
    }
}
