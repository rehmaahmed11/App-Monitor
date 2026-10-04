package com.applens.monitor.monitor;

import com.applens.monitor.core.RootShell;

import java.util.Locale;

/**
 * Optional per-connection byte accounting through root {@code tcpdump}. When
 * tcpdump is installed the packet lengths are matched against the sockets already
 * attributed to the monitored application, which yields exact per-flow upload and
 * download totals. Without tcpdump the app falls back to per-uid counters.
 */
public final class TcpdumpFlowMonitor {

    public interface FlowSink {
        /** @param outbound true when the packet leaves the device. */
        void onPacket(String localIp, int localPort, String remoteIp, int remotePort,
                      String proto, int bytes, boolean outbound);
    }

    private Process process;
    private volatile boolean running;

    public boolean isSupported() {
        return RootShell.get().tcpdump() != null;
    }

    public void start(final FlowSink sink) {
        if (running) {
            return;
        }
        String binary = RootShell.get().tcpdump();
        if (binary == null) {
            return;
        }
        running = true;
        process = RootShell.get().startStream(
                binary + " -i any -n -l -tt -q 'ip or ip6' 2>/dev/null",
                new RootShell.LineSink() {
                    @Override
                    public void onLine(String line) {
                        parse(line, sink);
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

    /**
     * Parses one tcpdump line such as
     * {@code 1696591234.123456 IP 10.0.0.5.54321 > 8.8.8.8.443: Flags [.], length 517}.
     */
    static void parse(String line, FlowSink sink) {
        if (line == null || sink == null || line.length() < 20) {
            return;
        }
        int gt = line.indexOf('>');
        if (gt < 0) {
            return;
        }
        int ipIndex = line.indexOf("IP6 ");
        boolean v6 = ipIndex >= 0;
        int search = v6 ? ipIndex + 3 : line.indexOf("IP ");
        if (search < 0 || search > gt) {
            return;
        }
        String left = line.substring(search, gt).trim();
        String right = line.substring(gt + 1).trim();
        int rightSpace = right.indexOf(' ');
        if (rightSpace > 0) {
            right = right.substring(0, rightSpace);
        }
        if (right.endsWith(":")) {
            right = right.substring(0, right.length() - 1);
        }

        String[] leftParts = splitEndpoint(left);
        String[] rightParts = splitEndpoint(right);
        if (leftParts == null || rightParts == null) {
            return;
        }
        int length = parseLength(line);
        if (length < 0) {
            return;
        }
        sink.onPacket(leftParts[0], leftParts[1], rightParts[0], rightParts[1],
                v6 ? "TCP6" : "TCP", length, true);
        sink.onPacket(rightParts[0], rightParts[1], leftParts[0], leftParts[1],
                v6 ? "TCP6" : "TCP", length, false);
    }

    private static String[] splitEndpoint(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        int colon = value.lastIndexOf('.');
        if (colon <= 0 || colon == value.length() - 1) {
            return null;
        }
        String port = value.substring(colon + 1);
        int portValue;
        try {
            portValue = Integer.parseInt(port);
        } catch (Throwable t) {
            return null;
        }
        if (portValue <= 0 || portValue > 65535) {
            return null;
        }
        return new String[]{value.substring(0, colon), portValue};
    }

    private static int parseLength(String line) {
        int idx = line.toLowerCase(Locale.US).lastIndexOf("length ");
        if (idx < 0) {
            return -1;
        }
        int start = idx + 7;
        int end = start;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        try {
            return Integer.parseInt(line.substring(start, end));
        } catch (Throwable t) {
            return -1;
        }
    }
}
