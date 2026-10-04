package com.applens.monitor.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
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
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local capture VPN for DNS.
 *
 * <p>The TUN only routes the device's real DNS resolvers (plus the synthetic
 * {@code 10.111.222.2} server the platform is told to use), so ordinary traffic
 * keeps flowing through Wi-Fi/cellular untouched while every domain lookup is
 * visible to AppLens. Queries are forwarded upstream over a protected socket and
 * the answer is written back into the TUN, so the monitored app sees no
 * difference — and DNS over TLS/TCP is relayed through a local loopback bridge
 * installed with root iptables rules.</p>
 */
public class DnsVpnService extends VpnService {

    public static final String ACTION_START = "com.applens.monitor.vpn.START";
    public static final String ACTION_STOP = "com.applens.monitor.vpn.STOP";
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_UID = "uid";
    public static final String EXTRA_LABEL = "label";

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

    private static volatile boolean running;
    private static volatile String status = "idle";
    private static volatile String error = "";

    private ParcelFileDescriptor tun;
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private final AtomicLong queryCount = new AtomicLong();
    private final Set<String> resolvers = new LinkedHashSet<>();
    private final Set<String> installedRules = ConcurrentHashMap.newKeySet();
    private Thread pump;
    private ServerSocket tcpRelay;
    private ServerSocket tlsRelay;
    private String pkg = "";
    private int uid = -1;
    /** The real resolver queries are forwarded to (never the synthetic TUN one). */
    private volatile String upstream = "";
    private volatile boolean scopedToApp;

    public static boolean isRunning() {
        return running;
    }

    public static String statusText() {
        return status;
    }

    public static String errorText() {
        return error;
    }

    public static long queryCount() {
        return QUERY_COUNTER.get();
    }

    private static final AtomicLong QUERY_COUNTER = new AtomicLong();

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
            stopEverything();
            return START_NOT_STICKY;
        }
        pkg = intent.getStringExtra(EXTRA_PKG);
        if (pkg == null) {
            pkg = "";
        }
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
                } finally {
                    starting.set(false);
                }
            }
        }, "applens-vpn-start");
        bringUp.setDaemon(true);
        bringUp.start();
        return START_STICKY;
    }

    private void bringUp() {
        collectResolvers();
        ParcelFileDescriptor descriptor;
        try {
            descriptor = establish();
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            status = "failed: " + error;
            DiagnosticLog.recordProblem("DNS capture VPN could not be established; "
                    + "monitoring continues without it", t);
            stopEverything();
            return;
        }
        if (descriptor == null) {
            status = "failed: VPN could not be established";
            error = status;
            DiagnosticLog.recordProblem("DNS capture VPN could not be established; "
                            + "monitoring continues without it",
                    new IllegalStateException("VpnService.Builder.establish() returned null"));
            stopEverything();
            return;
        }
        tun = descriptor;
        running = true;
        status = "capturing";
        error = "";
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
        enterForeground(statusLine());
        MonitorHub.get().publish(EventItem.of(EventCategory.DNS, "DNS capture online",
                (scopedToApp ? "Scoped to " + pkg : "Device wide")
                        + ", upstream " + Fmt.nz(upstream, "n/a") + ", MTU " + MTU,
                "VpnService"));
    }

    private ParcelFileDescriptor establish() {
        VpnService.Builder builder = new Builder();
        builder.setSession("AppLens DNS capture");
        builder.setMtu(MTU);
        builder.addAddress(TUN_ADDRESS, PREFIX);
        builder.addDnsServer(DNS_ADDRESS);
        for (String resolver : resolvers) {
            if (isIpv4(resolver)) {
                builder.addRoute(resolver, 32);
            }
        }
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

    private void collectResolvers() {
        resolvers.clear();
        // The framework knows the resolvers of the active network on every Android
        // version; net.dns* system properties have been empty since Android 9.
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                LinkProperties props = active == null ? null : cm.getLinkProperties(active);
                if (props != null) {
                    for (InetAddress address : props.getDnsServers()) {
                        String text = address == null ? null : address.getHostAddress();
                        if (isIpv4(text)) {
                            resolvers.add(text);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through to the root probes
        }
        RootShell root = RootShell.get();
        if (resolvers.isEmpty() && root.isRootGranted()) {
            String prop = root.exec("getprop | grep -E '^\\[net\\.dns[0-9]+\\]' 2>/dev/null", 8000);
            if (prop != null) {
                for (String line : prop.split("\n")) {
                    String value = line.replaceAll("^\\[net\\.dns[0-9]+\\]:\\s*\\[", "").replace("]", "").trim();
                    if (isIpv4(value)) {
                        resolvers.add(value);
                    }
                }
            }
            String resolv = root.exec("cat /system/etc/resolv.conf /etc/resolv.conf 2>/dev/null "
                    + "| grep -E '^nameserver' | awk '{print $2}'", 8000);
            if (resolv != null) {
                for (String value : resolv.split("\\s+")) {
                    if (isIpv4(value)) {
                        resolvers.add(value);
                    }
                }
            }
        }
        if (resolvers.isEmpty()) {
            // Capture-only fallbacks: traffic is only forwarded if an app really sends it there.
            resolvers.add("8.8.8.8");
            resolvers.add("1.1.1.1");
        }
        upstream = firstRealResolver();
    }

    /** The first resolver that is a real server rather than our own TUN address. */
    private String firstRealResolver() {
        for (String resolver : resolvers) {
            if (isIpv4(resolver) && !DNS_ADDRESS.equals(resolver) && !TUN_ADDRESS.equals(resolver)) {
                return resolver;
            }
        }
        return "8.8.8.8";
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
        return isIpv4(real) ? real : firstRealResolver();
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
        return (scopedToApp ? Fmt.nz(pkg, "monitored app") : "all applications")
                + " · upstream " + Fmt.nz(upstream, "n/a");
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
                workers.execute(new Runnable() {
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
        if (protocol != 17) {
            return;
        }
        int tos = packet[1] & 0xFF;
        String srcIp = ipv4(packet, 12);
        String dstIp = ipv4(packet, 16);
        int srcPort = ((packet[ihl] & 0xFF) << 8) | (packet[ihl + 1] & 0xFF);
        int dstPort = ((packet[ihl + 2] & 0xFF) << 8) | (packet[ihl + 3] & 0xFF);
        int payloadOffset = ihl + 8;
        int payloadLength = (packet[ihl + 4] & 0xFF) << 8 | (packet[ihl + 5] & 0xFF);
        if (dstPort != 53 || payloadOffset + payloadLength > packet.length) {
            return;
        }
        byte[] query = new byte[payloadLength];
        System.arraycopy(packet, payloadOffset, query, 0, payloadLength);
        forwardQuery(packet, srcIp, dstIp, srcPort, dstPort, tos, query);
    }

    private void forwardQuery(byte[] original, String srcIp, String dstIp, int srcPort,
                              int dstPort, int tos, byte[] query) {
        DnsMessage.Message request = DnsMessage.parse(query, 0, query.length);
        DnsMessage.Question question = request.primary();
        String domain = question == null ? "" : question.name;
        long now = System.currentTimeMillis();
        int clientUid = SocketUidResolver.resolveUdpUid(srcIp, srcPort);
        String target = upstreamFor(dstIp);
        if (uid < 0 || clientUid < 0 || clientUid == uid || belongsToMonitored(clientUid)) {
            record(domain, question, request, srcIp, target, clientUid, "UDP", toscs(tos), now, true);
        }
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            protect(socket);
            socket.setSoTimeout(5000);
            // Forward to a *real* resolver. The address the app used may be the
            // synthetic 10.111.222.2 we advertise, which exists only in the TUN.
            DatagramPacket out = new DatagramPacket(query, query.length,
                    InetAddress.getByName(target), 53);
            socket.send(out);
            byte[] buf = new byte[4096];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            socket.receive(in);
            byte[] response = new byte[in.getLength()];
            System.arraycopy(in.getData(), in.getOffset(), response, 0, in.getLength());
            writeBack(original, srcIp, dstIp, srcPort, dstPort, tos, response);
        } catch (Throwable t) {
            // Upstream timed out or is unreachable. Hand the client a SERVFAIL so it
            // fails fast and retries over its own path instead of hanging on a
            // lookup that will never be answered.
            byte[] failure = DnsMessage.serverFailure(query);
            if (failure != null) {
                writeBack(original, srcIp, dstIp, srcPort, dstPort, tos, failure);
            }
            DiagnosticLog.recordThrottledProblem("dns-upstream",
                    "DNS query could not be forwarded to " + target, t);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                    // noop
                }
            }
        }
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
                    "VpnService/DNS → " + server));
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

    private synchronized void writePacket(byte[] packet) {
        if (tun == null) {
            return;
        }
        try {
            FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
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
     * (never the whole device) and they are only installed once the relay is
     * actually listening. {@link #purgeStaleRules()} removes anything a crash left
     * behind the next time AppLens starts.</p>
     */
    private void installRelayRules(boolean tcpUp, boolean tlsUp) {
        if (!RootShell.get().ensureRoot() || uid <= 0) {
            return;
        }
        String owner = " -m owner --uid-owner " + uid;
        for (String resolver : resolvers) {
            if (!isIpv4(resolver)) {
                continue;
            }
            String tag = resolver.replace('.', '_');
            if (tcpUp) {
                RootShell.get().exec("iptables -t nat -A OUTPUT -p tcp -d " + resolver
                        + " --dport 53" + owner + " -m comment --comment " + RULE_TAG_TCP
                        + " -j DNAT --to-destination 127.0.0.1:" + RELAY_TCP53 + " 2>/dev/null", 8000);
                installedRules.add("dns:" + tag);
            }
            if (tlsUp) {
                RootShell.get().exec("iptables -t nat -A OUTPUT -p tcp -d " + resolver
                        + " --dport 853" + owner + " -m comment --comment " + RULE_TAG_DOT
                        + " -j DNAT --to-destination 127.0.0.1:" + RELAY_TLS853 + " 2>/dev/null", 8000);
                installedRules.add("dot:" + tag);
            }
        }
    }

    private void removeRelayRules() {
        if (!RootShell.get().isRootGranted() || installedRules.isEmpty()) {
            return;
        }
        String owner = " -m owner --uid-owner " + uid;
        for (String resolver : resolvers) {
            if (!isIpv4(resolver)) {
                continue;
            }
            RootShell.get().exec("iptables -t nat -D OUTPUT -p tcp -d " + resolver
                    + " --dport 53" + owner + " -m comment --comment " + RULE_TAG_TCP
                    + " -j DNAT --to-destination 127.0.0.1:" + RELAY_TCP53 + " 2>/dev/null", 8000);
            RootShell.get().exec("iptables -t nat -D OUTPUT -p tcp -d " + resolver
                    + " --dport 853" + owner + " -m comment --comment " + RULE_TAG_DOT
                    + " -j DNAT --to-destination 127.0.0.1:" + RELAY_TLS853 + " 2>/dev/null", 8000);
        }
        installedRules.clear();
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
                        workers.execute(new Runnable() {
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
            DnsRelayState state = new DnsRelayState(transport, remotePort, remotePort);
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
                    state.feed(in.lastBuffer, 0, in.lastLength, false);
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

    private void stopEverything() {
        stop.set(true);
        running = false;
        status = "stopped";
        closeQuietly(tcpRelay);
        closeQuietly(tlsRelay);
        tcpRelay = null;
        tlsRelay = null;
        try {
            if (tun != null) {
                tun.close();
            }
        } catch (Throwable ignored) {
            // noop
        }
        tun = null;
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
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
            // noop
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopEverything();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopEverything();
        super.onRevoke();
    }

    /** Parses DNS messages out of a TCP framed stream for logging purposes. */
    final class DnsRelayState {
        final String transport;
        final int localPort;
        final int remotePort;
        DnsRelayState(String transport, int localPort, int remotePort) {
            this.transport = transport;
            this.localPort = localPort;
            this.remotePort = remotePort;
        }

        void feed(byte[] buf, int off, int len, boolean fromServer) {
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
                    ActivityLogWriter.get().writeRaw(Fmt.clock(System.currentTimeMillis())
                            + "  [DNS] " + q.name + "  type=" + DnsMessage.typeName(q.type)
                            + "  transport=TCP  server=127.0.0.1:" + localPort
                            + "  <app: " + (pkg == null ? "" : pkg) + ">  <source: VPN relay>");
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
