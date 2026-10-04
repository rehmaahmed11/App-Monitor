package com.applens.monitor.net;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;
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

    private static volatile boolean running;
    private static volatile String status = "idle";
    private static volatile String error = "";

    private ParcelFileDescriptor tun;
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private final AtomicLong queryCount = new AtomicLong();
    private final Set<String> resolvers = new LinkedHashSet<>();
    private final Set<String> installedRules = ConcurrentHashMap.newKeySet();
    private Thread pump;
    private ServerSocket tcpRelay;
    private ServerSocket tlsRelay;
    private String pkg = "";
    private int uid = -1;

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
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
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
            return START_STICKY;
        }
        collectResolvers();
        try {
            tun = establish();
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            status = "failed: " + error;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tun == null) {
            status = "failed: VPN could not be established";
            error = status;
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        status = "capturing";
        error = "";
        installRelayRules();
        startTcpRelay();
        startTlsRelay();
        pump = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "applens-tun");
        pump.setDaemon(true);
        pump.start();
        MonitorHub.get().publish(EventItem.of(EventCategory.DNS, "DNS capture online",
                "TUN active, " + resolvers.size() + " resolver(s) routed, MTU " + MTU,
                "VpnService"));
        return START_STICKY;
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
        try {
            return builder.establish();
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            return null;
        }
    }

    private void collectResolvers() {
        resolvers.clear();
        RootShell root = RootShell.get();
        if (root.ensureRoot()) {
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
        if (uid < 0 || clientUid < 0 || clientUid == uid || belongsToMonitored(clientUid)) {
            record(domain, question, request, srcIp, dstIp, clientUid, "UDP", toscs(tos), now, true);
        }
        try {
            DatagramSocket socket = new DatagramSocket();
            protect(socket);
            socket.setSoTimeout(4000);
            byte[] src = DnsMessage.asciiBytes(srcIp);
            InetAddress replyFrom = InetAddress.getByAddress(src);
            DatagramPacket out = new DatagramPacket(query, query.length, InetAddress.getByName(dstIp), 53);
            socket.send(out);
            byte[] buf = new byte[4096];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            socket.receive(in);
            socket.close();
            byte[] response = new byte[in.getLength()];
            System.arraycopy(in.getData(), in.getOffset(), response, 0, in.getLength());
            writeBack(original, srcIp, dstIp, srcPort, dstPort, tos, response);
        } catch (Throwable t) {
            // upstream timeout or unreachable: drop, the client will retry over TCP
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

    private void installRelayRules() {
        if (!RootShell.get().ensureRoot()) {
            return;
        }
        for (String resolver : resolvers) {
            if (!isIpv4(resolver)) {
                continue;
            }
            String tag = resolver.replace('.', '_');
            RootShell.get().exec("iptables -t nat -A OUTPUT -p tcp -d " + resolver
                    + " --dport 53 -m comment --comment applens-dns -j DNAT --to-destination 127.0.0.1:"
                    + RELAY_TCP53 + " 2>/dev/null");
            installedRules.add("dns:" + tag);
            RootShell.get().exec("iptables -t nat -A OUTPUT -p tcp -d " + resolver
                    + " --dport 853 -m comment --comment applens-dot -j DNAT --to-destination 127.0.0.1:"
                    + RELAY_TLS853 + " 2>/dev/null");
            installedRules.add("dot:" + tag);
        }
    }

    private void removeRelayRules() {
        if (!RootShell.get().isRootGranted()) {
            return;
        }
        for (String resolver : resolvers) {
            if (!isIpv4(resolver)) {
                continue;
            }
            RootShell.get().exec("iptables -t nat -D OUTPUT -p tcp -d " + resolver
                    + " --dport 53 -m comment --comment applens-dns -j DNAT --to-destination 127.0.0.1:"
                    + RELAY_TCP53 + " 2>/dev/null");
            RootShell.get().exec("iptables -t nat -D OUTPUT -p tcp -d " + resolver
                    + " --dport 853 -m comment --comment applens-dot -j DNAT --to-destination 127.0.0.1:"
                    + RELAY_TLS853 + " 2>/dev/null");
        }
        installedRules.clear();
    }

    private void startTcpRelay() {
        startRelay(RELAY_TCP53, "TCP", 53);
    }

    private void startTlsRelay() {
        startRelay(RELAY_TLS853, "DoT", 853);
    }

    private void startRelay(final int port, final String transport, final int remotePort) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ServerSocket server = new ServerSocket(port);
                    if (port == RELAY_TCP53) {
                        tcpRelay = server;
                    } else {
                        tlsRelay = server;
                    }
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
    }

    private void pump(Socket client, String transport, int remotePort) {
        Socket server = null;
        try {
            client.setSoTimeout(30000);
            String host = client.getInetAddress().getHostAddress();
            int port = client.getPort();
            int originalPort = originalDestinationPort(host, port, remotePort);
            DnsRelayState state = new DnsRelayState(transport, originalPort, remotePort);
            server = new Socket(host, originalPort);
            protect(server);
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

    /** Resolves the pre-DNAT destination by matching the client socket in /proc/net/tcp. */
    private int originalDestinationPort(String clientIp, int clientPort, int fallback) {
        String dump = RootShell.get().exec("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null", 12000);
        if (dump == null) {
            return fallback;
        }
        String needle = String.format("%04X", clientPort);
        for (String line : dump.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 4) {
                continue;
            }
            String[] local = f[1].split(":");
            if (local.length != 2 || !local[1].equalsIgnoreCase(needle)) {
                continue;
            }
            String[] remote = f[2].split(":");
            if (remote.length != 2) {
                continue;
            }
            int port = RootShell.hexPort(remote[1]);
            if (port > 0) {
                return port == fallback ? fallback : port;
            }
        }
        return fallback;
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
        try {
            if (tun != null) {
                tun.close();
            }
        } catch (Throwable ignored) {
            // noop
        }
        tun = null;
        removeRelayRules();
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
