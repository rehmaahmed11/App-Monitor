package com.applens.monitor.model;

import com.applens.monitor.core.Fmt;

/** A network connection owned by the monitored application. */
public class ConnectionItem {

    public String key = "";
    public String remoteHost = "";
    public String remoteIp = "";
    public int remotePort = 0;
    public int localPort = 0;
    public String proto = "TCP";
    public String state = "";
    public long firstSeen;
    public long lastSeen;
    public long bytesUp = -1;
    public long bytesDown = -1;
    public int packets = -1;
    public boolean active = true;
    public int dnsQueries = 0;
    public String network = "";
    public String interfaceName = "";
    public long txQueue;
    public long rxQueue;

    public long duration() {
        return Math.max(0, lastSeen - firstSeen);
    }

    public String target() {
        if (remoteHost != null && !remoteHost.isEmpty()) {
            return remoteHost;
        }
        return remoteIp;
    }

    public String targetWithPort() {
        String t = target();
        return remotePort > 0 ? t + ":" + remotePort : t;
    }

    public String transferText() {
        if (bytesUp < 0 && bytesDown < 0) {
            return "—";
        }
        return "↑ " + Fmt.bytes(bytesUp) + "  ↓ " + Fmt.bytes(bytesDown);
    }

    public String stateLabel() {
        if (!active) {
            return "closed";
        }
        if (state == null || state.isEmpty()) {
            return "active";
        }
        switch (state) {
            case "01":
                return "established";
            case "02":
                return "syn-sent";
            case "03":
                return "syn-recv";
            case "04":
                return "fin-wait1";
            case "05":
                return "fin-wait2";
            case "06":
                return "time-wait";
            case "07":
                return "close";
            case "08":
                return "close-wait";
            case "09":
                return "last-ack";
            case "0A":
                return "listen";
            case "0B":
                return "closing";
            case "07 ":
                return "close";
            default:
                return state;
        }
    }

    public String toLogLine(String appPkg) {
        return Fmt.clock(lastSeen) + "  [NETWORK] " + proto + " " + targetWithPort()
                + "  state=" + stateLabel()
                + "  up=" + Fmt.bytes(bytesUp) + " down=" + Fmt.bytes(bytesDown)
                + "  net=" + Fmt.nz(network, "?")
                + "  <app: " + appPkg + ">";
    }
}
