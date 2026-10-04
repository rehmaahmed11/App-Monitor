package com.applens.monitor.monitor;

import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;
import com.applens.monitor.model.DnsRecord;
import com.applens.monitor.model.EventCategory;
import com.applens.monitor.model.EventItem;

import java.util.ArrayList;
import java.util.List;

/**
 * DNS capture through {@code tcpdump} for devices where the capture VPN is not in
 * use (or where {@code tcpdump} is installed by the user). Requires root.
 */
public final class TcpdumpMonitor {

    private final String pkg;
    private Process process;
    private final List<String> pending = new ArrayList<>();
    private volatile boolean running;

    public TcpdumpMonitor(String pkg) {
        this.pkg = pkg;
    }

    public boolean isSupported() {
        return RootShell.get().tcpdump() != null;
    }

    public void start() {
        if (running) {
            return;
        }
        String binary = RootShell.get().tcpdump();
        if (binary == null) {
            return;
        }
        running = true;
        process = RootShell.get().startStream(
                binary + " -i any -n -l -tt -s 0 'udp port 53 or tcp port 53' 2>/dev/null",
                new RootShell.LineSink() {
                    @Override
                    public void onLine(String line) {
                        parse(line);
                    }
                });
    }

    public void stop() {
        running = false;
        if (process != null) {
            try {
                process.destroy();
            } catch (Throwable ignored) {
                // noop
            }
            process = null;
        }
    }

    private void parse(String line) {
        if (line == null || line.length() < 20) {
            return;
        }
        // 1696591234.123456 IP 10.0.0.5.54321 > 8.8.8.8.53: 0x1c3a A? graph.facebook.com. 44
        int gt = line.indexOf('>');
        if (gt < 0) {
            return;
        }
        int ipIndex = line.indexOf("IP ");
        String srcIp = ipIndex >= 0 ? line.substring(ipIndex + 3, gt).trim() : "";
        int lastDot = srcIp.lastIndexOf('.');
        if (lastDot > 0) {
            srcIp = srcIp.substring(0, lastDot);
        }
        String tail = line.substring(gt + 1);
        String domain = null;
        int aIndex = tail.indexOf(" A? ");
        if (aIndex > 0) {
            domain = tail.substring(aIndex + 4).trim();
            int space = domain.indexOf(' ');
            if (space > 0) {
                domain = domain.substring(0, space);
            }
        }
        if (domain == null) {
            int aIndex2 = tail.indexOf(" A ");
            if (aIndex2 > 0) {
                domain = tail.substring(aIndex2 + 3).trim();
                int space = domain.indexOf(' ');
                if (space > 0) {
                    domain = domain.substring(0, space);
                }
            }
        }
        if (domain == null) {
            return;
        }
        while (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (domain.isEmpty()) {
            return;
        }
        DnsHostCache.put(srcIp, domain);
        DnsRecord record = new DnsRecord();
        record.time = System.currentTimeMillis();
        record.domain = domain;
        record.qtype = tail.contains("AAAA") ? "AAAA" : "A";
        record.transport = line.contains("TCP") || line.contains("tcp") ? "TCP" : "UDP";
        record.pkg = pkg;
        record.source = "tcpdump";
        DnsRecord stored = MonitorHub.get().recordDns(record);
        if (stored.queryCount == 1) {
            MonitorHub.get().publish(EventItem.of(EventCategory.DNS, "DNS " + record.qtype + " query",
                    domain, "tcpdump"));
        }
        ActivityLogWriter.get().writeRaw(Fmt.clock(record.time) + "  " + record.toLogLine(pkg));
    }
}
