package com.applens.monitor.monitor;

import com.applens.monitor.core.RootShell;
import com.applens.monitor.model.EventCategory;
import com.applens.monitor.model.EventItem;

/**
 * Per-application byte accounting. Three strategies are attempted in order and the
 * one actually in use is reported to the UI so the numbers are never ambiguous:
 *
 * <ol>
 *   <li>{@code /proc/net/xt_qtaguid/stats} — exact per-uid counters (Android ≤ 9)</li>
 *   <li>root {@code iptables} counter chain — exact per-uid totals on modern devices</li>
 *   <li>{@code /proc/<pid>/io} — file I/O of the monitored processes (partial)</li>
 * </ol>
 */
public final class ByteCounter {

    public static final String SOURCE_XT = "xt_qtaguid";
    public static final String SOURCE_IPTABLES = "iptables counters";
    public static final String SOURCE_PROC = "process I/O (partial)";

    private final int uid;
    private final String chain;
    private boolean iptablesInstalled;
    private boolean xtAvailable;
    private String source = "";

    private long lastUp;
    private long lastDown;
    private long lastSample;
    private boolean first = true;

    public ByteCounter(int uid) {
        this.uid = uid;
        this.chain = "APPLENS_" + uid;
    }

    public String source() {
        return source;
    }

    /** Installs / removes the iptables accounting chain. Safe to call repeatedly. */
    public void install() {
        RootShell root = RootShell.get();
        if (!root.ensureRoot() || uid <= 0) {
            return;
        }
        xtAvailable = root.exists("/proc/net/xt_qtaguid/stats");
        if (xtAvailable) {
            source = SOURCE_XT;
        }
        int rc = root.statusOf("iptables -N " + chain + " 2>/dev/null");
        if (rc != 0) {
            rc = root.statusOf("iptables -N " + chain);
        }
        // exec() returns null when the command could not run at all, which used to
        // throw a NullPointerException here — on the thread that boots monitoring,
        // so the whole application went down the moment a session started.
        String probe = root.exec("iptables -L " + chain + " -n >/dev/null 2>&1 && echo ok");
        if (rc == 0 || "ok".equals(probe)) {
            iptablesInstalled = true;
            // output chain (upload) + connmark restore so reply traffic is counted as download
            root.exec("iptables -F " + chain + " 2>/dev/null");
            root.exec("iptables -A " + chain + " -j RETURN 2>/dev/null");
            root.exec("iptables -D OUTPUT -m owner --uid-owner " + uid + " -j " + chain + " 2>/dev/null");
            root.exec("iptables -D INPUT -m owner --uid-owner " + uid + " -j " + chain + " 2>/dev/null");
            root.exec("iptables -I OUTPUT 1 -m owner --uid-owner " + uid + " -j " + chain + " 2>/dev/null");
            root.exec("iptables -I INPUT 1 -m owner --uid-owner " + uid + " -j " + chain + " 2>/dev/null");
            // mirror the counters to the ip6 tables as well
            root.exec("ip6tables -N " + chain + " 2>/dev/null");
            root.exec("ip6tables -A " + chain + " -j RETURN 2>/dev/null");
            root.exec("ip6tables -I OUTPUT 1 -m owner --uid-owner " + uid + " -j " + chain + " 2>/dev/null");
            root.exec("ip6tables -I INPUT 1 -m owner --uid-owner " + uid + " -j " + chain + " 2>/dev/null");
            if (source.isEmpty()) {
                source = SOURCE_IPTABLES;
            }
        } else if (source.isEmpty()) {
            source = SOURCE_PROC;
        }
    }

    public void uninstall() {
        RootShell root = RootShell.get();
        if (!root.isRootGranted()) {
            return;
        }
        root.exec("iptables -D OUTPUT -m owner --uid-owner " + uid + " -j " + chain + " 2>/dev/null");
        root.exec("iptables -D INPUT -m owner --uid-owner " + uid + " -j " + chain + " 2>/dev/null");
        root.exec("iptables -F " + chain + " 2>/dev/null; iptables -X " + chain + " 2>/dev/null");
        root.exec("ip6tables -D OUTPUT -m owner --uid-owner " + uid + " -j " + chain + " 2>/dev/null");
        root.exec("ip6tables -D INPUT -m owner --uid-owner " + uid + " -j " + chain + " 2>/dev/null");
        root.exec("ip6tables -F " + chain + " 2>/dev/null; ip6tables -X " + chain + " 2>/dev/null");
    }

    /** Result of one sampling round. */
    public static final class Sample {
        public long up = -1;
        public long down = -1;
        public long upRate;
        public long downRate;
        public String source = "";
    }

    public Sample sample(long procReadBytes, long procWriteBytes) {
        Sample out = new Sample();
        out.source = source;
        long up = -1;
        long down = -1;
        if (xtAvailable) {
            long[] pair = readXtQtaguid(uid);
            if (pair != null) {
                up = pair[0];
                down = pair[1];
            }
        }
        if (up < 0 && iptablesInstalled) {
            long[] pair = readIptables();
            if (pair != null) {
                up = pair[0];
                down = pair[1];
            }
        }
        if (up < 0) {
            up = Math.max(0, procWriteBytes);
            down = Math.max(0, procReadBytes);
            out.source = SOURCE_PROC;
        }
        out.up = up;
        out.down = down;
        long now = System.currentTimeMillis();
        if (!first) {
            long dt = Math.max(1, now - lastSample) / 1000;
            out.upRate = Math.max(0, (up - lastUp) / dt);
            out.downRate = Math.max(0, (down - lastDown) / dt);
        }
        first = false;
        lastUp = up;
        lastDown = down;
        lastSample = now;
        return out;
    }

    private long[] readXtQtaguid(int uid) {
        String dump = RootShell.get().exec("cat /proc/net/xt_qtaguid/stats 2>/dev/null | awk '$3==\"0\" && $5==\"\" {print $1, $2, $4, $8}'",
                15000);
        if (dump == null) {
            xtAvailable = false;
            return null;
        }
        long up = 0;
        long down = 0;
        boolean any = false;
        for (String line : dump.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 4) {
                continue;
            }
            if (parseInt(f[0]) != uid) {
                continue;
            }
            any = true;
            up += parseLong(f[2]);
            down += parseLong(f[3]);
        }
        return any ? new long[]{up, down} : new long[]{0, 0};
    }

    private long[] readIptables() {
        String v4 = RootShell.get().exec("iptables -v -n -L OUTPUT 2>/dev/null | grep 'uid-owner " + uid + "'", 12000);
        String v4in = RootShell.get().exec("iptables -v -n -L INPUT 2>/dev/null | grep 'uid-owner " + uid + "'", 12000);
        String v6 = RootShell.get().exec("ip6tables -v -n -L OUTPUT 2>/dev/null | grep 'uid-owner " + uid + "'", 12000);
        String v6in = RootShell.get().exec("ip6tables -v -n -L INPUT 2>/dev/null | grep 'uid-owner " + uid + "'", 12000);
        long up = 0;
        long down = 0;
        boolean any = false;
        long[] r = accumulate(v4, up);
        if (r != null) {
            up = r[0];
            any = true;
        }
        r = accumulate(v4in, down);
        if (r != null) {
            down = r[0];
            any = true;
        }
        r = accumulate(v6, up);
        if (r != null) {
            up += r[0];
            any = true;
        }
        r = accumulate(v6in, down);
        if (r != null) {
            down += r[0];
            any = true;
        }
        return any ? new long[]{up, down} : null;
    }

    private static long[] accumulate(String listing, long seed) {
        if (listing == null || listing.isEmpty()) {
            return null;
        }
        long total = seed;
        for (String line : listing.split("\n")) {
            String[] f = line.trim().split("\\s+");
            // pkts bytes target ... uid-owner N
            for (int i = 0; i < f.length; i++) {
                if (f[i].equals("uid-owner") && i + 1 < f.length) {
                    long bytes = 0;
                    for (int k = 0; k < i - 1; k++) {
                        if (f[k].matches("\\d+")) {
                            bytes = parseLong(f[k]);
                            break;
                        }
                    }
                    total += bytes;
                    break;
                }
            }
        }
        return new long[]{total};
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Fires a one-off activity event when a large transfer completes. */
    public static EventItem transferEvent(String direction, long bytes, String source) {
        EventItem e = EventItem.of(EventCategory.NETWORK,
                "Network " + direction,
                com.applens.monitor.core.Fmt.bytes(bytes) + " counted via " + source,
                source);
        return e;
    }
}
