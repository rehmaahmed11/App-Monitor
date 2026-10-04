package com.applens.monitor.net;

import com.applens.monitor.core.RootShell;
import com.applens.monitor.monitor.SocketMonitor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Attributes a socket to the owning application. DNS packets arriving on the
 * capture TUN carry no uid, but the source port is untouched by the routing, so the
 * socket table gives an exact answer whenever the kernel still has the entry.
 */
public final class SocketUidResolver {

    private static final Map<String, Long> cache = new HashMap<>();
    private static long cacheStamp;

    private SocketUidResolver() {
    }

    /** Resolves the uid that owns a UDP socket bound to {@code (ip, port)}. */
    public static int resolveUdpUid(String ip, int port) {
        return resolve(ip, port, "udp");
    }

    private static int resolve(String ip, int port, String proto) {
        if (ip == null || port <= 0) {
            return -1;
        }
        long now = System.currentTimeMillis();
        if (now - cacheStamp > 1500) {
            cache.clear();
            cacheStamp = now;
        }
        String key = proto + "|" + ip + "|" + port;
        Long cached = cache.get(key);
        if (cached != null) {
            return cached.intValue();
        }
        List<SocketMonitor.RawSocket> table = SocketMonitor.readSocketTable();
        int uid = -1;
        for (SocketMonitor.RawSocket s : table) {
            if (!s.proto.toLowerCase(java.util.Locale.US).startsWith(proto)) {
                continue;
            }
            if (s.localPort == port && ip.equals(s.localIp)) {
                uid = s.uid;
                break;
            }
        }
        cache.put(key, (long) uid);
        return uid;
    }

    /** Resolves the uid of a TCP connection from its local end point. */
    public static int resolveTcpUid(String ip, int port) {
        return resolve(ip, port, "tcp");
    }

    /** Best effort: is this uid the one we are monitoring? */
    public static boolean isMonitored(int uid) {
        return uid > 0 && uid == com.applens.monitor.monitor.MonitorHub.get().uid;
    }

    public static boolean rootAvailable() {
        return RootShell.get().isRootGranted();
    }
}
