package com.applens.monitor.monitor;

import com.applens.monitor.core.RootShell;
import com.applens.monitor.model.ConnectionItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Connection discovery through {@code /proc/net}. Every TCP/UDP socket is matched
 * against the inodes owned by the monitored application's processes, which yields
 * the exact destination, port, protocol and state for each flow.
 */
public final class SocketMonitor {

    private final String pkg;
    private final Map<String, ConnectionItem> byKey = new HashMap<>();

    public SocketMonitor(String pkg) {
        this.pkg = pkg;
    }

    public static final class RawSocket {
        public String proto;
        public String localIp;
        public int localPort;
        public String remoteIp;
        public int remotePort;
        public String state;
        public int uid;
        public long inode;
        public long txQueue;
        public long rxQueue;
    }

    /** Parses {@code /proc/net/tcp|tcp6|udp|udp6} into socket records. */
    public static List<RawSocket> readSocketTable() {
        List<RawSocket> out = new ArrayList<>();
        RootShell root = RootShell.get();
        if (!root.ensureRoot()) {
            return out;
        }
        StringBuilder cmd = new StringBuilder();
        String[] files = {"tcp", "tcp6", "udp", "udp6"};
        for (String f : files) {
            cmd.append("echo '#F").append(f).append("#'; cat /proc/net/").append(f).append(" 2>/dev/null;");
        }
        String dump = root.execRaw(cmd.toString(), 15000);
        if (dump == null) {
            return out;
        }
        String current = "";
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#F")) {
                current = line.substring(2, line.length() - 1);
                continue;
            }
            if (line.startsWith("sl") || line.startsWith("  sl")) {
                continue;
            }
            RawSocket s = parseLine(line, current);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static RawSocket parseLine(String line, String proto) {
        String[] f = line.split("\\s+");
        // sl local rem st tx:rx tr:when retrnsmt uid timeout inode ...
        if (f.length < 10) {
            return null;
        }
        try {
            RawSocket s = new RawSocket();
            s.proto = proto.toUpperCase(java.util.Locale.US);
            String[] local = f[1].split(":");
            String[] remote = f[2].split(":");
            if (local.length != 2 || remote.length != 2) {
                return null;
            }
            s.localPort = Integer.parseInt(local[1], 16);
            s.remotePort = Integer.parseInt(remote[1], 16);
            boolean v6 = proto.endsWith("6");
            s.localIp = v6 ? RootShell.hexToIp6(local[0]) : RootShell.hexToIp(local[0]);
            s.remoteIp = v6 ? RootShell.hexToIp6(remote[0]) : RootShell.hexToIp(remote[0]);
            s.state = f[3];
            String[] queues = f[4].split(":");
            s.txQueue = parseLong(queues.length > 0 ? queues[0] : "0");
            s.rxQueue = parseLong(queues.length > 1 ? queues[1] : "0");
            s.uid = parseInt(f[7]);
            s.inode = parseLong(f[9]);
            if (s.uid <= 0 && s.inode <= 0) {
                return null;
            }
            return s;
        } catch (Throwable t) {
            return null;
        }
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
            return 0;
        }
    }

    /**
     * Polls the socket table and returns the connections owned by {@code pkg}.
     * {@code inodes} is refreshed by the caller from {@code /proc/<pid>/fd}.
     */
    public List<ConnectionItem> poll(Set<Long> inodes, int uid) {
        List<RawSocket> all = readSocketTable();
        Set<String> liveKeys = new HashSet<>();
        List<ConnectionItem> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (RawSocket s : all) {
            boolean mine = (uid >= 0 && s.uid == uid) || inodes.contains(s.inode);
            if (!mine) {
                continue;
            }
            if (s.remotePort == 0 || s.remoteIp == null || s.remoteIp.startsWith("0.0.0.0")) {
                continue;
            }
            String key = s.proto + "|" + s.remoteIp + ":" + s.remotePort + "|" + s.localPort;
            liveKeys.add(key);
            ConnectionItem item = byKey.get(key);
            if (item == null) {
                item = new ConnectionItem();
                item.key = key;
                item.proto = s.proto;
                item.remoteIp = s.remoteIp;
                item.remotePort = s.remotePort;
                item.localPort = s.localPort;
                item.firstSeen = now;
                byKey.put(key, item);
            }
            item.state = s.state;
            item.lastSeen = now;
            item.active = true;
            item.txQueue = s.txQueue;
            result.add(item);
        }
        for (Map.Entry<String, ConnectionItem> e : byKey.entrySet()) {
            if (!liveKeys.contains(e.getKey())) {
                e.getValue().active = false;
                e.getValue().lastSeen = now;
            }
        }
        return result;
    }
}
