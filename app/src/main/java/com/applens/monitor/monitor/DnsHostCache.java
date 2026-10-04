package com.applens.monitor.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps destination IPs to the host names seen in DNS answers, so the connection
 * table can show {@code graph.facebook.com} instead of a bare address.
 */
public final class DnsHostCache {

    private static final int MAX = 4000;
    private static final Map<String, String> byIp = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    private DnsHostCache() {
    }

    public static void put(String ip, String host) {
        if (ip == null || host == null || ip.isEmpty() || host.isEmpty()) {
            return;
        }
        byIp.put(ip, host);
        lastSeen.put(ip, System.currentTimeMillis());
        if (byIp.size() > MAX) {
            evict();
        }
    }

    public static void putAll(Iterable<String> ips, String host) {
        if (ips == null || host == null) {
            return;
        }
        for (String ip : ips) {
            put(ip, host);
        }
    }

    public static String host(String ip) {
        return ip == null ? null : byIp.get(ip);
    }

    public static String reverseOrSelf(String ip) {
        if (ip == null || ip.isEmpty()) {
            return ip;
        }
        String host = byIp.get(ip);
        return host == null ? ip : host;
    }

    private static void evict() {
        long cutoff = System.currentTimeMillis() - 10 * 60 * 1000L;
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Long> e : lastSeen.entrySet()) {
            if (e.getValue() < cutoff) {
                stale.add(e.getKey());
            }
        }
        for (String key : stale) {
            lastSeen.remove(key);
            byIp.remove(key);
        }
        if (byIp.size() > MAX) {
            byIp.clear();
            lastSeen.clear();
        }
    }
}
