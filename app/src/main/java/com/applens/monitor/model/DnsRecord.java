package com.applens.monitor.model;

import com.applens.monitor.core.Fmt;

import java.util.ArrayList;
import java.util.List;

/** A single DNS query observed for the monitored application. */
public class DnsRecord {

    public long time;
    public String domain = "";
    public String qtype = "A";
    public String transport = "UDP";
    public String server = "";
    public String pkg = "";
    public int uid = -1;
    public boolean resolved = true;
    public int queryCount = 1;
    public long ttl = 0;
    public long rcode = 0;
    public List<String> answers = new ArrayList<>();
    public String source = "VPN";
    public String appLabel = "";

    public static String typeName(int type) {
        switch (type) {
            case 1:
                return "A";
            case 2:
                return "NS";
            case 5:
                return "CNAME";
            case 6:
                return "SOA";
            case 12:
                return "PTR";
            case 15:
                return "MX";
            case 16:
                return "TXT";
            case 28:
                return "AAAA";
            case 33:
                return "SRV";
            case 35:
                return "NAPTR";
            case 43:
                return "DS";
            case 48:
                return "DNSKEY";
            case 64:
                return "SVCB";
            case 65:
                return "HTTPS";
            case 99:
                return "SPF";
            case 251:
                return "IXFR";
            case 252:
                return "AXFR";
            case 255:
                return "ANY";
            default:
                return "TYPE" + type;
        }
    }

    public static String typeCode(String name) {
        if (name == null) {
            return "?";
        }
        switch (name.toUpperCase()) {
            case "A":
                return "1";
            case "NS":
                return "2";
            case "CNAME":
                return "5";
            case "SOA":
                return "6";
            case "PTR":
                return "12";
            case "MX":
                return "15";
            case "TXT":
                return "16";
            case "AAAA":
                return "28";
            case "SRV":
                return "33";
            case "DNSKEY":
                return "48";
            case "HTTPS":
                return "65";
            case "ANY":
                return "255";
            default:
                return "?";
        }
    }

    public String answerText() {
        if (answers == null || answers.isEmpty()) {
            return resolved ? "—" : "no answer";
        }
        return Fmt.join(", ", answers);
    }

    public String toLogLine(String appPkg) {
        return Fmt.clock(time) + "  [DNS] " + domain + "  type=" + qtype
                + "  transport=" + transport
                + "  answers=" + answerText()
                + "  queried=" + queryCount + "x"
                + "  server=" + Fmt.nz(server, "?")
                + "  <app: " + (pkg.isEmpty() ? appPkg : pkg) + ">"
                + "  <source: " + source + ">";
    }
}
