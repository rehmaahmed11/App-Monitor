package com.applens.monitor.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.LogSettings;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;
import com.applens.monitor.log.DiagnosticLog;
import com.applens.monitor.model.DnsRecord;
import com.applens.monitor.model.EventCategory;
import com.applens.monitor.model.EventItem;
import com.applens.monitor.monitor.DnsHostCache;
import com.applens.monitor.monitor.MonitorHub;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local capture VPN for DNS.
 *
 * <p><b>It must never cost the monitored application its connectivity.</b> The TUN
 * only carries the lookups the application makes against the synthetic resolver
 * AppLens advertises ({@code 10.111.222.2}); no other route is taken over, so
 * ordinary traffic keeps flowing through Wi-Fi/cellular untouched. On top of that
 * the capture is fail-open in three ways:</p>
 *
 * <ol>
 *   <li>before the TUN is established, every candidate resolver is probed; if none
 *       answers, the capture is <em>not</em> started at all;</li>
 *   <li>a lookup is retried against every other resolver before it is given up, and
 *       a previously cached answer is served when the upstream is unreachable;</li>
 *   <li>if forwarding keeps failing anyway, the TUN is closed immediately and the
 *       application falls back to the device's own resolver — recording a lookup is
 *       never worth breaking the application that is being recorded.</li>
 * </ol>
 *
 * <p>DNS over TCP ({@code :53}) and DNS over TLS ({@code :853}) are redirected to
 * loopback relays with root {@code iptables} rules that are restricted to the
 * monitored uid, so the transports themselves are unchanged (DoT is relayed as
 * bytes, never terminated).</p>
 */
public class DnsVpnService extends VpnService {

    public static final String ACTION_START = "com.applens.monitor.vpn.START";
    public static final String ACTION_STOP = "com.applens.monitor.vpn.STOP";
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_UID = "uid";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_REASON = "reason";

    private static final String TUN_ADDRESS = "10.111.222.1";
    private static final String DNS_ADDRESS = "10.111.222.2";
    private static final int PREFIX = 30;
    private static final int MTU = 1500;
    private static final int RELAY_TCP53 = 15353;
    private static final int RELAY_TLS853 = 15853;

    private static final String CHANNEL_ID = "applens_dns_capture";
    private static final int NOTIFICATION_ID = 0xA12;
    /** Every root firewall rule AppLens installs carries one of these comments. */
    private static final String RULE_TAG_TCP = "applens-dns";
    private static final String RULE_TAG_DOT = "applens-dot";

    /** Give a single upstream this long before the next one is tried (ms). */
    private static final int UPSTREAM_TIMEOUT_MS = 2500;
    /** Total time a single lookup may spend walking the resolver list (ms). */
    private static final int LOOKUP_BUDGET_MS = 7000;
    /** Forwarding failures that trigger the fail-open teardown. */
    private static final int FAILURES_BEFORE_FAIL_OPEN = 4;
    private static final long FAILURE_WINDOW_MS = 20000L;
    /** Cached answers are kept this long so an upstream hiccup is invisible. */
    private static final long CACHE_TTL_MS = 120000L;
    private static final int CACHE_MAX_ENTRIES = 512;

    private static volatile boolean running;
    private static volatile String status = "idle";
    private static volatile String error = "";
    private static volatile String detail = "";

    private ParcelFileDescriptor tun;
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final AtomicBoolean starting = new AtomicBoolean(false);
    /**
     * Bounded: a DNS storm must never queue packets until the process is killed.
     * When the queue is full the packet is dropped and the client retries — which
     * is exactly what a resolver does when it is overloaded.
     */
    private final ExecutorService workers = new java.util.concurrent.ThreadPoolExecutor(
            8, 32, 30L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>(1024),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong queryCount = new AtomicLong();
    private final List<String> resolvers = new ArrayList<>();
    private final List<String> upstreams = new ArrayList<>();
    private final Set<String> installedRules = ConcurrentHashMap.newKeySet();
    /** Bounded answer cache: domain|type → last response bytes. */
    private final Map<String, Cached> answerCache =
            Collections.synchronizedMap(new LinkedHashMap<String, Cached>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            });
    private Thread pump;
    private ServerSocket tcpRelay;
    private ServerSocket tlsRelay;
    private String pkg = "";
    private String label = "";
    private int uid = -1;
    /** The resolver queries are forwarded to (never the synthetic TUN one). */
    private volatile String upstream = "";
    private volatile boolean scopedToApp;
    private volatile boolean rulesInstalled;
    private final List<Long> recentFailures = new ArrayList<>();
    private volatile boolean tcpResetReported;
    private ConnectivityManager.NetworkCallback networkCallback;

    public static boolean isRunning() {
        return running;
    }

    public static String statusText() {
        return status;
    }

    public static String errorText() {
        return error;
    }

    public static String detailText() {
        return detail;
    }

    public static long queryCount() {
        return QUERY_COUNTER.get();
    }

    private static final AtomicLong QUERY_COUNTER = new AtomicLong();

    private static final class Cached {
        final byte[] response;
        final long time;

        Cached(byte[] response, long time) {
            this.response = response;
            this.time = time;
        }

        boolean fresh() {
            return System.currentTimeMillis() - time < CACHE_TTL_MS;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        // The service is launched with startForegroundService(); Android kills the
        // whole process with ForegroundServiceDidNotStartInTimeException unless
        // startForeground() runs within a few seconds — on *every* path, including
        // the stop and the null-intent restart.
        enterForeground("Preparing DNS capture");
        String action = intent == null ? null : intent.getAction();
        if (intent == null || ACTION_STOP.equals(action)) {
            String reason = intent == null ? "" : intent.getStringExtra(EXTRA_REASON);
            stopEverything(Fmt.nz(reason, "capture stopped"));
            return START_NOT_STICKY;
        }
        pkg = intent.getStringExtra(EXTRA_PKG);
        if (pkg == null) {
            pkg = "";
        }
        String extraLabel = intent.getStringExtra(EXTRA_LABEL);
        label = extraLabel == null ? pkg : extraLabel;
        uid = intent.getIntExtra(EXTRA_UID, -1);
        stop.set(false);
        QUERY_COUNTER.set(0);
        if (running) {
            enterForeground(statusLine());
            return START_STICKY;
        }
        if (!starting.compareAndSet(false, true)) {
            return START_STICKY;
        }
        // Reading the resolvers, establishing the TUN and installing firewall rules
        // all block — several seconds when root is involved. None of it may happen
        // on the service main thread, which is the same thread the UI draws on.
        Thread bringUp = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    bringUp();
                } catch (Throwable error) {
                    failOpen("the capture could not be prepared: "
                            + LogSettings.describe(error));
                } finally {
                    starting.set(false);
                }
            }
        }, "applens-vpn-start");
        bringUp.setDaemon(true);
        bringUp.start();
        return START_STICKY;
    }

    // ------------------------------------------------------------------
    // Bring-up
    // ------------------------------------------------------------------

    private void bringUp() {
        collectResolvers();
        if (upstreams.isEmpty()) {
            failOpen("no DNS resolver could be discovered on this network");
            return;
        }
        String reachable = probeUpstreams();
        if (reachable == null) {
            failOpen("none of the " + upstreams.size()
                    + " resolvers answered a test lookup — DNS capture stays off so "
                    + "the application keeps its normal internet access");
            return;
        }
        upstream = reachable;
        ParcelFileDescriptor descriptor;
        try {
            descriptor = establish();
        } catch (Throwable t) {
            failOpen("the VPN could not be established: " + LogSettings.describe(t));
            return;
        }
        if (descriptor == null) {
            failOpen("the VPN could not be established");
            return;
        }
        tun = descriptor;
        running = true;
        status = "capturing";
        error = "";
        detail = (scopedToApp ? "scoped to " + pkg : "device wide")
                + " · upstream " + upstream + " · " + upstreams.size() + " resolver(s)";
        // Bind the relays first: the firewall rules are only installed once there
        // is something listening, otherwise DNS over TCP/TLS would black-hole.
        boolean tcpUp = startTcpRelay();
        boolean tlsUp = startTlsRelay();
        installRelayRules(tcpUp, tlsUp);
        pump = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "applens-tun");
        pump.setDaemon(true);
        pump.start();
        watchNetworks();
        enterForeground(statusLine());
        publishVpnState();
        MonitorHub.get().publish(EventItem.of(EventCategory.DNS, "DNS capture online",
                detail + ", MTU " + MTU, "VpnService"));
    }

    /**
     * Sends a root-NS query to every discovered resolver and returns the first one
     * that answers. This is what keeps the capture from being installed on a
     * network where forwarding cannot work — the classic way a DNS-capturing VPN
     * leaves an application without internet.
     */
    private String probeUpstreams() {
        List<String> candidates = new ArrayList<>(upstreams);
        for (String candidate : candidates) {
            DatagramSocket socket = null;
            try {
                byte[] probe = DnsMessage.query("", DnsMessage.TYPE_NS);
                socket = new DatagramSocket();
                protect(socket);
                socket.setSoTimeout(1200);
                socket.send(new DatagramPacket(probe, probe.length,
                        InetAddress.getByName(candidate), 53));
                byte[] buf = new byte[1500];
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                socket.receive(in);
                if (in.getLength() > 0) {
                    return candidate;
                }
            } catch (Throwable ignored) {
                // try the next resolver
            } finally {
                closeQuietly(socket);
            }
        }
        return null;
    }

    private ParcelFileDescriptor establish() {
        VpnService.Builder builder = new Builder();
        builder.setSession("AppLens DNS capture");
        builder.setMtu(MTU);
        builder.addAddress(TUN_ADDRESS, PREFIX);
        builder.addDnsServer(DNS_ADDRESS);
        // Only the synthetic resolver is routed into the TUN. The device's real
        // resolvers are *not* routed: a /32 route would also capture every other
        // connection the application makes to that address (a router that is both a
        // resolver and a web server, for example), and a dropped packet there looks
        // exactly like "the app lost its internet".
        builder.addRoute(DNS_ADDRESS, 32);
        // Only the monitored application is put inside the TUN. Every other app on
        // the device — and AppLens itself — keeps resolving through the normal
        // network path, so a capture problem can never take the phone offline.
        scopedToApp = false;
        if (pkg != null && !pkg.isEmpty() && !pkg.equals(getPackageName())) {
            try {
                builder.addAllowedApplication(pkg);
                scopedToApp = true;
            } catch (Throwable notInstalled) {
                scopedToApp = false;
            }
        }
        if (!scopedToApp) {
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Throwable ignored) {
                // noop
            }
        }
        try {
            return builder.establish();
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            return null;
        }
    }

    /**
     * Every resolver the device knows about, best first. The active network is the
     * natural source; the root probes cover ROMs and IPv6-only links where the
     * framework list is empty.
     */
    private void collectResolvers() {
        resolvers.clear();
        LinkedHashSet<String> found = new LinkedHashSet<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                LinkProperties props = active == null ? null : cm.getLinkProperties(active);
                addResolvers(found, props);
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                    if (capabilities != null
                            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        addResolvers(found, cm.getLinkProperties(network));
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through to the root probes
        }
        if (found.isEmpty()) {
            RootShell root = RootShell.get();
            if (root.isRootGranted()) {
                // net.dns* has been empty since Android 9, but older ROMs still use it.
                String props = root.exec("getprop | grep -i dns 2>/dev/null");
                if (props != null) {
                    for (String line : props.split("\n")) {
                        int colon = line.indexOf(':');
                        String value = colon >= 0 ? line.substring(colon + 1) : line;
                        for (String part : value.replaceAll("[\\[\\]]", " ").split("\\s+")) {
                            addResolver(found, part);
                        }
                    }
                }
                String ip = root.exec("ip -4 route show default 2>/dev/null | head -3");
                if (ip != null) {
                    for (String line : ip.split("\n")) {
                        for (String part : line.split("\\s+")) {
                            if (part.contains(".") && !part.equals("default")
                                    && !line.trim().startsWith(part)) {
                                addResolver(found, part);
                            }
                        }
                    }
                }
            }
        }
        resolvers.addAll(found);
        upstreams.clear();
        for (String resolver : resolvers) {
            if (isIpv4(resolver) && !DNS_ADDRESS.equals(resolver) && !TUN_ADDRESS.equals(resolver)) {
                upstreams.add(resolver);
            }
        }
    }

    private static void addResolvers(Set<String> out, LinkProperties props) {
        if (props == null) {
            return;
        }
        try {
            for (InetAddress address : props.getDnsServers()) {
                if (address != null) {
                    addResolver(out, address.getHostAddress());
                }
            }
        } catch (Throwable ignored) {
            // noop
        }
    }

    private static void addResolver(Set<String> out, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || !isIpv4(trimmed)) {
            return;
        }
        out.add(trimmed);
    }

    // ------------------------------------------------------------------
    // Fail open
    // ------------------------------------------------------------------

    /**
     * Gives up on capturing DNS and lets the application use the device's own
     * resolver again. Always recorded, so the user sees why the DNS tab goes quiet
     * instead of wondering why their application broke.
     */
    private void failOpen(String reason) {
        boolean wasRunning = running;
        closeTun();
        running = false;
        rulesInstalled = false;
        status = "unavailable";
        error = reason;
        detail = "DNS capture off — " + reason;
        DiagnosticLog.recordThrottledProblem("vpn-fail-open",
                "DNS capture disabled to keep " + Fmt.nz(label, pkg) + " online: " + reason,
                new IllegalStateException(reason));
        try {
            EventItem event = EventItem.of(EventCategory.DNS,
                    wasRunning ? "DNS capture stopped itself" : "DNS capture not started",
                    reason + ". " + Fmt.nz(label, pkg)
                            + " keeps using the device's own DNS servers.",
                    "VpnService");
            event.pkg = pkg;
            MonitorHub.get().publish(event);
            ActivityLogWriter.get().writeRaw(event.toLogLine(pkg));
        } catch (Throwable ignored) {
            // reporting must never break the capture
        }
        publishVpnState();
        removeRelayRules();
        unregisterNetworkCallback();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
            // noop
        }
        stopSelf();
    }

    private void submit(Runnable task) {
        try {
            workers.execute(task);
        } catch (Throwable overloaded) {
            // Queue full (or the service is shutting down): dropping a packet is
            // the correct back-pressure for DNS.
        }
    }

    private void closeTun() {
        ParcelFileDescriptor descriptor = tun;
        tun = null;
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (Throwable ignored) {
                // noop
            }
        }
    }

    /** Registers a forwarding failure and fails open when they keep happening. */
    private void noteForwardFailure(String target, Throwable cause) {
        synchronized (recentFailures) {
            long now = System.currentTimeMillis();
            recentFailures.add(now);
            while (!recentFailures.isEmpty() && now - recentFailures.get(0) > FAILURE_WINDOW_MS) {
                recentFailures.remove(0);
            }
            if (recentFailures.size() >= FAILURES_BEFORE_FAIL_OPEN) {
                int failed = recentFailures.size();
                recentFailures.clear();
                DiagnosticLog.recordProblem("DNS forwarding keeps failing",
                        cause == null ? new java.io.IOException("unreachable " + target) : cause);
                failOpen(failed + " lookups in a row could not be forwarded to " + target);
                return;
            }
        }
        DiagnosticLog.recordThrottledProblem("dns-upstream",
                "DNS query could not be forwarded to " + target, cause);
    }

    private void publishVpnState() {
        try {
            MonitorHub.get().setVpn(status, error);
            MonitorHub.get().publishState();
        } catch (Throwable ignored) {
            // noop
        }
    }

    // ------------------------------------------------------------------
    // Network changes
    // ------------------------------------------------------------------

    /**
     * Re-resolves the resolver list when the default network changes. Without this
     * a capture that was installed on Wi-Fi would keep sending lookups to a
     * resolver that the phone cannot reach after switching to mobile data.
     */
    private void watchNetworks() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    recheckResolvers();
                }

                @Override
                public void onLost(Network network) {
                    recheckResolvers();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties props) {
                    recheckResolvers();
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Throwable error) {
            DiagnosticLog.recordThrottledProblem("vpn-network-watch",
                    "The network change watcher could not be registered", error);
        }
    }

    private void recheckResolvers() {
        if (!running || stop.get()) {
            return;
        }
        submit(new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> before = new ArrayList<>(upstreams);
                    collectResolvers();
                    if (upstreams.isEmpty()) {
                        failOpen("the network changed and no resolver is reachable any more");
                        return;
                    }
                    if (upstreams.equals(before)) {
                        return;
                    }
                    upstream = probeUpstreams();
                    if (upstream == null) {
                        failOpen("the network changed and none of the new resolvers answered");
                        return;
                    }
                    detail = (scopedToApp ? "scoped to " + pkg : "device wide")
                            + " · upstream " + upstream + " · " + upstreams.size() + " resolver(s)";
                    publishVpnState();
                    enterForeground(statusLine());
                } catch (Throwable error) {
                    DiagnosticLog.recordThrottledProblem("vpn-network-change",
                            "Reacting to a network change failed", error);
                }
            }
        });
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        if (callback == null) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(callback);
            }
        } catch (Throwable ignored) {
            // noop
        }
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "AppLens DNS capture", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Active while DNS lookups of the monitored app are recorded");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        } catch (Throwable ignored) {
            // noop
        }
    }

    private String statusLine() {
        return (scopedToApp ? Fmt.nz(label, Fmt.nz(pkg, "monitored app")) : "all applications")
                + " · upstream " + Fmt.nz(upstream, "n/a")
                + (error.isEmpty() ? "" : " · " + error);
    }

    private void enterForeground(String text) {
        try {
            createChannel();
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            builder.setContentTitle("AppLens DNS capture")
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_dns)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            Notification notification = builder.build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable failure) {
            DiagnosticLog.recordThrottledProblem("vpn-foreground",
                    "DNS capture service could not enter the foreground", failure);
        }
    }

    static boolean isIpv4(String s) {
        if (s == null) {
            return false;
        }
        String[] f = s.split("\\.");
        if (f.length != 4) {
            return false;
        }
        for (String p : f) {
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                if (p.charAt(i) < '0' || p.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // TUN read loop
    // ------------------------------------------------------------------

    private void readLoop() {
        byte[] buffer = new byte[MTU + 64];
        try {
            FileInputStream in = new FileInputStream(tun.getFileDescriptor());
            while (!stop.get()) {
                int read = in.read(buffer);
                if (read <= 0) {
                    continue;
                }
                final byte[] packet = new byte[read];
                System.arraycopy(buffer, 0, packet, 0, read);
                submit(new Runnable() {
                    @Override
                    public void run() {
                        handlePacket(packet);
                    }
                });
            }
        } catch (Throwable t) {
            if (!stop.get()) {
                error = "tun read failed: " + t.getMessage();
            }
        }
    }

    private void handlePacket(byte[] packet) {
        if (packet.length < 20) {
            return;
        }
        int version = (packet[0] & 0xF0) >> 4;
        if (version != 4) {
            return;
        }
        int ihl = (packet[0] & 0x0F) * 4;
        if (ihl < 20 || packet.length < ihl + 8) {
            return;
        }
        int protocol = packet[9] & 0xFF;
        int tos = packet[1] & 0xFF;
        String srcIp = ipv4(packet, 12);
        String dstIp = ipv4(packet, 16);
        int srcPort = ((packet[ihl] & 0xFF) << 8) | (packet[ihl + 1] & 0xFF);
        int dstPort = ((packet[ihl + 2] & 0xFF) << 8) | (packet[ihl + 3] & 0xFF);
        if (protocol == 17) {
            int payloadOffset = ihl + 8;
            int payloadLength = (packet[ihl + 4] & 0xFF) << 8 | (packet[ihl + 5] & 0xFF);
            if (dstPort != 53 || payloadOffset + payloadLength > packet.length) {
                return;
            }
            byte[] query = new byte[payloadLength];
            System.arraycopy(packet, payloadOffset, query, 0, payloadLength);
            forwardQuery(packet, srcIp, dstIp, srcPort, dstPort, tos, query);
            return;
        }
        if (protocol == 6 && (dstPort == 53 || dstPort == 853)) {
            // DNS over TCP / TLS is handled by the loopback relay (redirected with
            // iptables). Without root there is no relay, so the connection is
            // refused instead of being left to time out: the client retries over
            // UDP straight away and the application keeps working.
            if (!rulesInstalled) {
                sendTcpReset(packet, ihl, srcIp, dstIp, srcPort, dstPort);
            }
        }
    }

    private void forwardQuery(byte[] original, String srcIp, String dstIp, int srcPort,
                              int dstPort, int tos, byte[] query) {
        DnsMessage.Message request = DnsMessage.parse(query, 0, query.length);
        DnsMessage.Question question = request.primary();
        String domain = question == null ? "" : question.name;
        long now = System.currentTimeMillis();
        int clientUid = SocketUidResolver.resolveUdpUid(srcIp, srcPort);
        String cacheKey = cacheKey(domain, question);
        if (uid < 0 || clientUid < 0 || clientUid == uid || belongsToMonitored(clientUid)) {
            record(domain, question, request, srcIp, upstream, clientUid, "UDP", toscs(tos),
                    now, true);
        }
        byte[] response = forwardUpstream(query, cacheKey);
        if (response == null) {
            return;
        }
        writeBack(original, srcIp, dstIp, srcPort, dstPort, tos, response);
    }

    /** Walks the resolver list, caches the answer and reports total failure. */
    private byte[] forwardUpstream(byte[] query, String cacheKey) {
        long deadline = System.currentTimeMillis() + LOOKUP_BUDGET_MS;
        Throwable lastError = null;
        for (String candidate : new ArrayList<>(upstreams)) {
            if (System.currentTimeMillis() >= deadline || stop.get()) {
                break;
            }
            try {
                byte[] response = ask(candidate, query);
                if (response != null) {
                    upstream = candidate;
                    if (cacheKey != null) {
                        answerCache.put(cacheKey, new Cached(response, System.currentTimeMillis()));
                    }
                    return response;
                }
            } catch (Throwable error) {
                lastError = error;
            }
        }
        Cached cached = cacheKey == null ? null : answerCache.get(cacheKey);
        if (cached != null && cached.fresh()) {
            // The upstream hiccup is invisible to the application: it gets the
            // answer it would have received a moment earlier.
            return cached.response;
        }
        noteForwardFailure(upstream, lastError);
        return DnsMessage.serverFailure(query);
    }

    private byte[] ask(String resolver, byte[] query) throws Exception {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            protect(socket);
            socket.setSoTimeout(UPSTREAM_TIMEOUT_MS);
            socket.send(new DatagramPacket(query, query.length,
                    InetAddress.getByName(resolver), 53));
            byte[] buf = new byte[4096];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            socket.receive(in);
            byte[] response = new byte[in.getLength()];
            System.arraycopy(in.getData(), in.getOffset(), response, 0, in.getLength());
            return response;
        } finally {
            closeQuietly(socket);
        }
    }

    private static String cacheKey(String domain, DnsMessage.Question question) {
        if (domain == null || domain.isEmpty() || question == null) {
            return null;
        }
        return domain.toLowerCase(java.util.Locale.US) + "|" + question.type;
    }

    private boolean belongsToMonitored(int clientUid) {
        return uid > 0 && clientUid == uid;
    }

    private void record(String domain, DnsMessage.Question question, DnsMessage.Message message,
                        String clientIp, String server, int clientUid, String transport,
                        String toS, long time, boolean queryOnly) {
        if (domain == null || domain.isEmpty()) {
            return;
        }
        DnsRecord record = new DnsRecord();
        record.time = time;
        record.domain = domain;
        record.qtype = question == null ? "?" : DnsMessage.typeName(question.type);
        record.transport = transport;
        record.server = server;
        record.uid = clientUid;
        record.pkg = pkg;
        record.source = "VPN" + (toS.isEmpty() ? "" : " dscp" + toS);
        record.resolved = message.isResponse();
        for (DnsMessage.Record answer : message.answers) {
            if (answer.data != null && !answer.data.isEmpty()) {
                record.answers.add(answer.data);
            }
        }
        if (record.answers.isEmpty() && message.answers.isEmpty() && message.isResponse()) {
            record.answers.add("no answer");
        }
        for (DnsMessage.Record answer : message.answers) {
            if (answer.data == null) {
                continue;
            }
            if (answer.type == DnsMessage.TYPE_A) {
                DnsHostCache.put(answer.data, domain);
            } else if (answer.type == DnsMessage.TYPE_CNAME) {
                DnsHostCache.put(answer.name, answer.data);
            }
        }
        queryCount.incrementAndGet();
        QUERY_COUNTER.incrementAndGet();
        DnsRecord stored = MonitorHub.get().recordDns(record);
        if (queryOnly && stored.queryCount == 1) {
            MonitorHub.get().publish(EventItem.of(EventCategory.DNS,
                    "DNS " + record.qtype + " query",
                    domain + (record.answers.isEmpty() ? "" : " → " + Fmt.join(", ", record.answers)),
                    "VpnService/DNS → " + Fmt.nz(server, "?")));
        }
        ActivityLogWriter.get().writeRaw(Fmt.clock(time) + "  [DNS] " + record.toLogLine(pkg));
    }

    /** Synthesises the reply packet and injects it into the TUN. */
    private void writeBack(byte[] original, String srcIp, String dstIp, int srcPort,
                           int dstPort, int tos, byte[] response) {
        try {
            int ihl = (original[0] & 0x0F) * 4;
            int maxPayload = MTU - ihl - 8;
            byte[] payload = response;
            if (payload.length > maxPayload) {
                byte[] truncated = DnsMessage.truncate(response, 0, response.length);
                if (truncated == null) {
                    return;
                }
                payload = truncated;
            }
            byte[] packet = new byte[ihl + 8 + payload.length];
            System.arraycopy(original, 0, packet, 0, ihl);
            packet[0] = (byte) 0x45;
            packet[1] = (byte) tos;
            packet[2] = 0;
            packet[3] = 0;
            int total = packet.length;
            packet[2] = (byte) ((total >> 8) & 0xFF);
            packet[3] = (byte) (total & 0xFF);
            packet[6] = 0x40; // DF
            packet[8] = (byte) 64; // TTL
            packet[9] = 17; // UDP
            System.arraycopy(ipv4Bytes(dstIp), 0, packet, 12, 4);
            System.arraycopy(ipv4Bytes(srcIp), 0, packet, 16, 4);
            packet[10] = 0;
            packet[11] = 0;
            int headerSum = checksum(packet, 0, ihl, 4);
            packet[10] = (byte) ((headerSum >> 8) & 0xFF);
            packet[11] = (byte) (headerSum & 0xFF);
            int u = ihl;
            packet[u] = (byte) ((53 >> 8) & 0xFF);
            packet[u + 1] = 53;
            packet[u + 2] = (byte) ((dstPort >> 8) & 0xFF);
            packet[u + 3] = (byte) (dstPort & 0xFF);
            int udpLen = 8 + payload.length;
            packet[u + 4] = (byte) ((udpLen >> 8) & 0xFF);
            packet[u + 5] = (byte) (udpLen & 0xFF);
            packet[u + 6] = 0;
            packet[u + 7] = 0; // UDP checksum optional over IPv4
            System.arraycopy(payload, 0, packet, u + 8, payload.length);
            writePacket(packet);
        } catch (Throwable ignored) {
            // a failed write just means the client retries
        }
    }

    /** RST for a TCP DNS connection we cannot relay (no root for the redirect). */
    private void sendTcpReset(byte[] original, int ihl, String srcIp, String dstIp,
                              int srcPort, int dstPort) {
        try {
            int tcpOffset = ihl;
            long ack = 0;
            for (int i = 0; i < 4; i++) {
                ack = (ack << 8) | (original[tcpOffset + 8 + i] & 0xFF);
            }
            byte[] packet = new byte[40];
            packet[0] = 0x45;
            packet[2] = 0;
            packet[3] = 40;
            packet[6] = 0x40;
            packet[8] = 64;
            packet[9] = 6;
            System.arraycopy(ipv4Bytes(dstIp), 0, packet, 12, 4);
            System.arraycopy(ipv4Bytes(srcIp), 0, packet, 16, 4);
            int sum = checksum(packet, 0, 20, 5);
            packet[10] = (byte) ((sum >> 8) & 0xFF);
            packet[11] = (byte) (sum & 0xFF);
            int t = 20;
            packet[t] = (byte) ((dstPort >> 8) & 0xFF);
            packet[t + 1] = (byte) dstPort;
            packet[t + 2] = (byte) ((srcPort >> 8) & 0xFF);
            packet[t + 3] = (byte) srcPort;
            packet[t + 12] = 0x50; // data offset 5
            packet[t + 13] = 0x14; // RST + ACK
            packet[t + 14] = 0;
            packet[t + 15] = 0;
            packet[t + 16] = 0;
            packet[t + 17] = 1; // window
            for (int i = 0; i < 4; i++) {
                packet[t + 8 + i] = (byte) ((ack >> (8 * (3 - i))) & 0xFF);
            }
            writePacket(packet);
            if (!tcpResetReported) {
                tcpResetReported = true;
                DiagnosticLog.recordThrottledProblem("vpn-tcp-dns",
                        "DNS over TCP could not be relayed (no root for the redirect); "
                                + "connections are refused so the app retries over UDP",
                        new IllegalStateException("iptables redirect unavailable"));
            }
        } catch (Throwable ignored) {
            // a dropped packet is no worse than a reset here
        }
    }

    private synchronized void writePacket(byte[] packet) {
        ParcelFileDescriptor descriptor = tun;
        if (descriptor == null) {
            return;
        }
        try {
            FileOutputStream out = new FileOutputStream(descriptor.getFileDescriptor());
            out.write(packet);
            out.flush();
        } catch (Throwable ignored) {
            // tun closed
        }
    }

    private static String toscs(int tos) {
        int dscp = tos >> 2;
        return dscp > 0 ? String.valueOf(dscp) : "";
    }

    static String ipv4(byte[] p, int off) {
        return (p[off] & 0xFF) + "." + (p[off + 1] & 0xFF) + "." + (p[off + 2] & 0xFF) + "." + (p[off + 3] & 0xFF);
    }

    static byte[] ipv4Bytes(String ip) {
        String[] f = ip.split("\\.");
        byte[] out = new byte[4];
        for (int i = 0; i < 4 && i < f.length; i++) {
            try {
                out[i] = (byte) Integer.parseInt(f[i]);
            } catch (Throwable ignored) {
                // noop
            }
        }
        return out;
    }

    static int checksum(byte[] buf, int offset, int length, int skipWordIndex) {
        long sum = 0;
        int i = 0;
        while (i < length) {
            int word = ((buf[offset + i] & 0xFF) << 8) | (buf[offset + i + 1] & 0xFF);
            if (i != skipWordIndex * 2) {
                sum += word;
            }
            i += 2;
        }
        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum & 0xFFFF);
    }

    // ------------------------------------------------------------------
    // DNS over TCP / TLS loopback relay
    // ------------------------------------------------------------------

    /**
     * Redirects the monitored app's DNS-over-TCP / DNS-over-TLS to the local relay.
     *
     * <p>Two safety properties matter here, because a DNAT rule outlives the
     * process that installed it: the rules are restricted to the monitored uid
     * (never the whole device), they only match ports 53 and 853 (so no other
     * traffic to those addresses is touched) and they are only installed once the
     * relay is actually listening. {@link #purgeStaleRules()} removes anything a
     * crash left behind the next time AppLens starts.</p>
     */
    private void installRelayRules(boolean tcpUp, boolean tlsUp) {
        if (!RootShell.get().ensureRoot() || uid <= 0) {
            return;
        }
        List<String> destinations = new ArrayList<>(upstreams);
        if (!destinations.contains(DNS_ADDRESS)) {
            destinations.add(DNS_ADDRESS);
        }
        for (String destination : destinations) {
            if (!isIpv4(destination)) {
                continue;
            }
            if (tcpUp) {
                added(RootShell.get().statusOf("iptables -t nat -A OUTPUT -p tcp -d " + destination
                        + " --dport 53 -m owner --uid-owner " + uid
                        + " -m comment --comment " + RULE_TAG_TCP
                        + " -j DNAT --to-destination 127.0.0.1:" + RELAY_TCP53 + " 2>/dev/null") == 0,
                        "dns:" + destination);
            }
            if (tlsUp) {
                added(RootShell.get().statusOf("iptables -t nat -A OUTPUT -p tcp -d " + destination
                        + " --dport 853 -m owner --uid-owner " + uid
                        + " -m comment --comment " + RULE_TAG_DOT
                        + " -j DNAT --to-destination 127.0.0.1:" + RELAY_TLS853 + " 2>/dev/null") == 0,
                        "dot:" + destination);
            }
        }
        rulesInstalled = !installedRules.isEmpty();
    }

    private void added(boolean ok, String key) {
        if (ok) {
            installedRules.add(key);
        }
    }

    private void removeRelayRules() {
        RootShell root = RootShell.get();
        if (!root.isRootGranted()) {
            installedRules.clear();
            return;
        }
        List<String> destinations = new ArrayList<>(upstreams);
        if (!destinations.contains(DNS_ADDRESS)) {
            destinations.add(DNS_ADDRESS);
        }
        for (String destination : destinations) {
            if (!isIpv4(destination)) {
                continue;
            }
            root.exec("iptables -t nat -D OUTPUT -p tcp -d " + destination
                    + " --dport 53 -m owner --uid-owner " + uid
                    + " -m comment --comment " + RULE_TAG_TCP
                    + " -j DNAT --to-destination 127.0.0.1:" + RELAY_TCP53 + " 2>/dev/null", 8000);
            root.exec("iptables -t nat -D OUTPUT -p tcp -d " + destination
                    + " --dport 853 -m owner --uid-owner " + uid
                    + " -m comment --comment " + RULE_TAG_DOT
                    + " -j DNAT --to-destination 127.0.0.1:" + RELAY_TLS853 + " 2>/dev/null", 8000);
        }
        installedRules.clear();
        rulesInstalled = false;
        purgeStaleRules();
    }

    /**
     * Deletes every DNS redirect AppLens ever installed, whatever session created
     * it. Called when a capture stops and once at application start, so a crashed
     * session can never leave the device with DNS pointing at a dead local port.
     */
    public static void purgeStaleRules() {
        RootShell root = RootShell.get();
        if (!root.isRootGranted()) {
            return;
        }
        try {
            for (String tag : new String[]{RULE_TAG_TCP, RULE_TAG_DOT}) {
                // Walk the rule list by number, newest first, so the indices stay
                // valid while matching rules are removed.
                String listing = root.exec("iptables -t nat -S OUTPUT 2>/dev/null", 8000);
                if (listing == null || listing.isEmpty()) {
                    return;
                }
                for (String line : listing.split("\n")) {
                    if (!line.contains("--comment " + tag) && !line.contains("--comment \"" + tag + "\"")) {
                        continue;
                    }
                    String rule = line.trim();
                    if (!rule.startsWith("-A OUTPUT")) {
                        continue;
                    }
                    root.exec("iptables -t nat -D" + rule.substring(2) + " 2>/dev/null", 8000);
                }
            }
        } catch (Throwable error) {
            DiagnosticLog.recordThrottledProblem("vpn-rule-purge",
                    "Could not remove leftover DNS redirect rules", error);
        }
    }

    private boolean startTcpRelay() {
        return startRelay(RELAY_TCP53, "TCP", 53);
    }

    private boolean startTlsRelay() {
        return startRelay(RELAY_TLS853, "DoT", 853);
    }

    /** Binds the relay socket synchronously; returns false when the port is taken. */
    private boolean startRelay(final int port, final String transport, final int remotePort) {
        final ServerSocket server;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress("127.0.0.1", port));
        } catch (Throwable t) {
            DiagnosticLog.recordThrottledProblem("vpn-relay-" + port,
                    "DNS " + transport + " relay could not listen on 127.0.0.1:" + port, t);
            return false;
        }
        if (port == RELAY_TCP53) {
            tcpRelay = server;
        } else {
            tlsRelay = server;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!stop.get()) {
                        final Socket client = server.accept();
                        submit(new Runnable() {
                            @Override
                            public void run() {
                                pump(client, transport, remotePort);
                            }
                        });
                    }
                } catch (Throwable ignored) {
                    // relay stopped
                }
            }
        }, "applens-relay-" + port);
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void pump(Socket client, String transport, int remotePort) {
        Socket server = null;
        try {
            client.setSoTimeout(30000);
            // The pre-DNAT destination is not visible to a Java socket, so the
            // stream is forwarded to the resolver the redirect was installed for —
            // connecting back to the client address (127.0.0.1) could only ever
            // produce a refused connection, which is what broke DoT lookups.
            String host = upstreamFor(null);
            DnsRelayState state = new DnsRelayState(transport, remotePort, remotePort, client);
            server = new Socket();
            // Bind first so the socket has a file descriptor to protect, then keep
            // the relay's own traffic outside the TUN.
            server.bind(new java.net.InetSocketAddress(0));
            protect(server);
            server.connect(new java.net.InetSocketAddress(host, remotePort), 8000);
            server.setSoTimeout(30000);
            final Socket finalClient = client;
            final Socket finalServer = server;
            Thread up = new Thread(new Runnable() {
                @Override
                public void run() {
                    copy(finalClient, finalServer, state);
                }
            }, "applens-relay-up");
            up.setDaemon(true);
            up.start();
            copy(server, client, state);
            up.join(200);
        } catch (Throwable ignored) {
            // relay finished
        } finally {
            closeQuietly(client);
            closeQuietly(server);
        }
    }

    private void copy(Socket from, Socket to, DnsRelayState state) {
        try {
            byte[] buf = new byte[8192];
            InputStreamHolder in = new InputStreamHolder(from);
            int n;
            while ((n = in.read(buf)) > 0) {
                to.getOutputStream().write(buf, 0, n);
                to.getOutputStream().flush();
                if (in.lastLength > 0) {
                    state.feed(in.lastBuffer, 0, in.lastLength);
                }
            }
        } catch (Throwable ignored) {
            // closed
        }
    }

    private void closeQuietly(java.io.Closeable s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
                // noop
            }
        }
    }

    /**
     * Where a query actually has to go. Queries addressed to the synthetic server
     * AppLens advertises (10.111.222.2) exist only inside the TUN — forwarding them
     * back to that address is what used to drop every lookup the app made.
     */
    private String upstreamFor(String destination) {
        if (isIpv4(destination) && !DNS_ADDRESS.equals(destination) && !TUN_ADDRESS.equals(destination)) {
            return destination;
        }
        String real = upstream;
        if (isIpv4(real)) {
            return real;
        }
        for (String candidate : upstreams) {
            if (isIpv4(candidate)) {
                return candidate;
            }
        }
        return "8.8.8.8";
    }

    // ------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------

    private void stopEverything(String reason) {
        stop.set(true);
        boolean wasRunning = running;
        running = false;
        status = wasRunning ? "stopped" : "idle";
        error = "";
        detail = reason;
        closeQuietly(tcpRelay);
        closeQuietly(tlsRelay);
        tcpRelay = null;
        tlsRelay = null;
        closeTun();
        unregisterNetworkCallback();
        // Removing the redirects touches root, which must not run on the service
        // main thread; the TUN is already closed so DNS is restored either way.
        final Thread cleanup = new Thread(new Runnable() {
            @Override
            public void run() {
                removeRelayRules();
            }
        }, "applens-vpn-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
        publishVpnState();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
            // noop
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stop.set(true);
        running = false;
        closeTun();
        unregisterNetworkCallback();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopEverything("the VPN permission was revoked");
        super.onRevoke();
    }

    /** Parses DNS messages out of a TCP framed stream for logging purposes. */
    final class DnsRelayState {
        final String transport;
        final int localPort;
        final int remotePort;
        final Socket client;

        DnsRelayState(String transport, int localPort, int remotePort, Socket client) {
            this.transport = transport;
            this.localPort = localPort;
            this.remotePort = remotePort;
            this.client = client;
        }

        void feed(byte[] buf, int off, int len) {
            if (transport.equals("DoT")) {
                return;
            }
            int p = off;
            int end = off + len;
            while (p + 2 <= end) {
                int length = ((buf[p] & 0xFF) << 8) | (buf[p + 1] & 0xFF);
                if (length <= 0 || p + 2 + length > end) {
                    break;
                }
                DnsMessage.Message message = DnsMessage.parse(buf, p + 2, length);
                DnsMessage.Question q = message.primary();
                if (q != null) {
                    int clientUid = client == null ? -1
                            : SocketUidResolver.resolveTcpUid(
                                    client.getInetAddress().getHostAddress(),
                                    client.getPort());
                    String server = "127.0.0.1:" + localPort;
                    record(q.name, q, message, "127.0.0.1", server, clientUid, "TCP", "",
                            System.currentTimeMillis(), true);
                }
                p += 2 + length;
            }
        }
    }

    /** Small helper so the relay keeps the last chunk for DNS framing. */
    private static final class InputStreamHolder {
        private final Socket socket;
        byte[] lastBuffer = new byte[0];
        int lastLength;

        InputStreamHolder(Socket socket) {
            this.socket = socket;
        }

        int read(byte[] buf) throws java.io.IOException {
            lastBuffer = buf;
            lastLength = socket.getInputStream().read(buf);
            return lastLength;
        }
    }
}
