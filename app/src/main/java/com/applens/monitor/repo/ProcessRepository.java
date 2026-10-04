package com.applens.monitor.repo;

import android.os.SystemClock;

import com.applens.monitor.core.RootShell;
import com.applens.monitor.model.ProcessStat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Process discovery and {@code /proc} harvesting. Everything is read through a
 * single root round-trip per poll so the sampler can run at 1 Hz without melting
 * the device.
 */
public final class ProcessRepository {

    /** One row of the process table. */
    public static final class ProcRow {
        public final int pid;
        public final int uid;
        public final String name;

        ProcRow(int pid, int uid, String name) {
            this.pid = pid;
            this.uid = uid;
            this.name = name;
        }
    }

    private static volatile boolean psFormResolved;
    private static volatile String psForm = "";

    private ProcessRepository() {
    }

    public static boolean matchesPackage(String processName, String pkg) {
        if (processName == null || pkg == null) {
            return false;
        }
        return processName.equals(pkg) || processName.startsWith(pkg + ":");
    }

    /** Full process table as {@code [uid, pid]} pairs. */
    public static List<int[]> uidPidMap() {
        List<int[]> out = new ArrayList<>();
        for (ProcRow row : processTable()) {
            out.add(new int[]{row.uid, row.pid});
        }
        return out;
    }

    public static List<ProcRow> processTable() {
        List<ProcRow> out = new ArrayList<>();
        RootShell root = RootShell.get();
        if (!root.ensureRoot()) {
            return out;
        }
        String[] candidates = {
                "ps -A -o PID,UID,NAME 2>/dev/null",
                "ps -A -o PID,UID,ARGS 2>/dev/null",
                "ps -e -o pid,uid,args 2>/dev/null",
        };
        for (String cmd : candidates) {
            List<String> lines = root.execLines(cmd, 12000);
            int parsed = parsePs(lines, out, true);
            if (parsed > 4) {
                psFormResolved = true;
                psForm = cmd;
                return out;
            }
            out.clear();
        }
        // /proc fallback: reliable but slower, used only if ps is unavailable
        out.clear();
        procFallback(out);
        return out;
    }

    private static int parsePs(List<String> lines, List<ProcRow> out, boolean skipHeader) {
        int count = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (skipHeader && i == 0) {
                String lower = line.toLowerCase();
                if (lower.contains("pid") || lower.contains("user")) {
                    continue;
                }
            }
            String[] f = line.split("\\s+");
            if (f.length < 3) {
                continue;
            }
            int pid = parseInt(f[0]);
            int uid = parseInt(f[1]);
            if (pid <= 0 || uid < 0) {
                continue;
            }
            StringBuilder name = new StringBuilder();
            for (int k = 2; k < f.length; k++) {
                if (k > 2) {
                    name.append(' ');
                }
                name.append(f[k]);
            }
            out.add(new ProcRow(pid, uid, name.toString().trim()));
            count++;
        }
        return count;
    }

    private static void procFallback(List<ProcRow> out) {
        String listing = RootShell.get().exec("ls /proc 2>/dev/null | grep -E '^[0-9]+$'", 15000);
        if (listing == null) {
            return;
        }
        StringBuilder cmd = new StringBuilder();
        for (String pid : listing.split("\\s+")) {
            if (pid.isEmpty()) {
                continue;
            }
            cmd.append("printf '").append(pid).append("\\t'; sed -n 's/^Uid:[[:space:]]*//p' /proc/")
                    .append(pid).append("/status 2>/dev/null | awk '{print $1}';");
        }
        List<String> lines = RootShell.get().execLines(cmd.toString(), 30000);
        for (String line : lines) {
            String[] f = line.split("\t");
            if (f.length < 2) {
                continue;
            }
            int pid = parseInt(f[0]);
            int uid = parseInt(f[1]);
            if (pid > 0) {
                out.add(new ProcRow(pid, uid, ""));
            }
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** All pids owned by {@code pkg} (including its {@code :remote} style services). */
    public static List<ProcRow> pidsFor(String pkg) {
        List<ProcRow> out = new ArrayList<>();
        for (ProcRow row : processTable()) {
            if (matchesPackage(row.name, pkg)) {
                out.add(row);
            }
        }
        return out;
    }

    /**
     * Harvests a detailed snapshot for every process of {@code pkg} using one root call.
     */
    public static List<ProcessStat> snapshot(String pkg) {
        List<ProcessStat> out = new ArrayList<>();
        RootShell root = RootShell.get();
        if (!root.ensureRoot()) {
            return out;
        }
        List<ProcRow> rows = pidsFor(pkg);
        if (rows.isEmpty()) {
            return out;
        }
        StringBuilder cmd = new StringBuilder();
        for (ProcRow row : rows) {
            int pid = row.pid;
            cmd.append("echo '#P").append(pid).append("#';");
            cmd.append("cat /proc/").append(pid).append("/stat 2>/dev/null;");
            cmd.append("echo '#S").append(pid).append("#';");
            cmd.append("cat /proc/").append(pid).append("/status 2>/dev/null;");
            cmd.append("echo '#I").append(pid).append("#';");
            cmd.append("cat /proc/").append(pid).append("/io 2>/dev/null;");
            cmd.append("echo '#W").append(pid).append("#';");
            cmd.append("cat /proc/").append(pid).append("/wchan 2>/dev/null;");
            cmd.append("echo '#F").append(pid).append("#';");
            cmd.append("ls -l /proc/").append(pid).append("/fd 2>/dev/null | wc -l;");
            cmd.append("ls -l /proc/").append(pid).append("/fd 2>/dev/null | grep -c 'socket:';");
            cmd.append("grep -c '\\.so' /proc/").append(pid).append("/maps 2>/dev/null;");
            cmd.append("echo '#E").append(pid).append("#';");
        }
        String dump = root.execRaw(cmd.toString(), 30000);
        if (dump == null) {
            return out;
        }
        for (ProcRow row : rows) {
            ProcessStat stat = new ProcessStat();
            stat.pid = row.pid;
            stat.uid = row.uid;
            stat.name = row.name;
            String body = between(dump, "#P" + row.pid + "#", "#S" + row.pid + "#");
            if (body != null) {
                parseStat(body, stat);
            }
            String status = between(dump, "#S" + row.pid + "#", "#I" + row.pid + "#");
            if (status != null) {
                parseStatus(status, stat);
            }
            String io = between(dump, "#I" + row.pid + "#", "#W" + row.pid + "#");
            if (io != null) {
                parseIo(io, stat);
            }
            String wchan = between(dump, "#W" + row.pid + "#", "#F" + row.pid + "#");
            if (wchan != null) {
                stat.wchan = wchan.trim();
            }
            String[] counts = between(dump, "#F" + row.pid + "#", "#E" + row.pid + "#").split("\n");
            if (counts.length > 0) {
                stat.openFiles = parseInt(counts[0].trim());
            }
            if (counts.length > 1) {
                stat.sockets = parseInt(counts[1].trim());
            }
            if (counts.length > 2) {
                stat.nativeLibraries = parseInt(counts[2].trim());
            }
            out.add(stat);
        }
        return out;
    }

    private static String between(String haystack, String start, String end) {
        if (haystack == null) {
            return "";
        }
        int a = haystack.indexOf(start);
        if (a < 0) {
            return "";
        }
        a += start.length();
        int b = haystack.indexOf(end, a);
        if (b < 0) {
            return haystack.substring(a);
        }
        return haystack.substring(a, b);
    }

    private static void parseStat(String body, ProcessStat stat) {
        String trimmed = body.trim();
        int close = trimmed.lastIndexOf(')');
        if (close < 0) {
            return;
        }
        String comm = trimmed.substring(trimmed.indexOf('(') + 1, close);
        if (stat.name == null || stat.name.isEmpty()) {
            stat.name = comm;
        }
        String[] rest = trimmed.substring(close + 1).trim().split("\\s+");
        // rest[0] = state, rest[11] = utime, rest[12] = stime, rest[19] = starttime,
        // rest[20] = vsize, rest[21] = rss
        if (rest.length > 0) {
            stat.state = rest[0];
        }
        if (rest.length > 12) {
            stat.utimeTicks = longOf(rest[11]);
            stat.stimeTicks = longOf(rest[12]);
        }
        if (rest.length > 19) {
            stat.startTimeTicks = longOf(rest[19]);
        }
        if (rest.length > 20) {
            stat.vszKb = longOf(rest[20]);
        }
        if (rest.length > 21) {
            long pages = longOf(rest[21]);
            stat.rssKb = pages * 4;
        }
    }

    private static void parseStatus(String body, ProcessStat stat) {
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.startsWith("VmRSS:")) {
                stat.rssKb = longOf(t.substring(6).replaceAll("[^0-9]", ""));
            } else if (t.startsWith("VmHWM:")) {
                // high water mark kept implicitly
            } else if (t.startsWith("Threads:")) {
                stat.threads = (int) longOf(t.substring(8).replaceAll("[^0-9]", ""));
            } else if (t.startsWith("State:")) {
                String s = t.substring(6).trim();
                stat.state = s.isEmpty() ? stat.state : s.substring(0, 1);
            } else if (t.startsWith("Uid:")) {
                String[] f = t.substring(4).trim().split("\\s+");
                if (f.length >= 2) {
                    stat.uid = (int) longOf(f[1]);
                }
            } else if (t.startsWith("Name:") && (stat.name == null || stat.name.isEmpty())) {
                stat.name = t.substring(5).trim();
            }
        }
    }

    private static void parseIo(String body, ProcessStat stat) {
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.startsWith("rchar:")) {
                stat.rchar = longOf(t.substring(6).replaceAll("[^0-9]", ""));
            } else if (t.startsWith("wchar:")) {
                stat.wchar = longOf(t.substring(6).replaceAll("[^0-9]", ""));
            } else if (t.startsWith("read_bytes:")) {
                stat.readBytes = longOf(t.substring(11).replaceAll("[^0-9]", ""));
            } else if (t.startsWith("write_bytes:")) {
                stat.writeBytes = longOf(t.substring(12).replaceAll("[^0-9]", ""));
            }
        }
    }

    private static long longOf(String s) {
        try {
            String t = s.trim();
            if (t.isEmpty()) {
                return 0;
            }
            return Long.parseLong(t);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Socket inodes owned by every process of {@code pkg}. */
    public static Set<Long> socketInodes(String pkg) {
        Set<Long> inodes = new HashSet<>();
        RootShell root = RootShell.get();
        if (!root.ensureRoot()) {
            return inodes;
        }
        StringBuilder cmd = new StringBuilder();
        for (ProcRow row : pidsFor(pkg)) {
            cmd.append("ls -l /proc/").append(row.pid).append("/fd 2>/dev/null | grep -o 'socket:\\[[0-9]*\\]';");
        }
        if (cmd.length() == 0) {
            return inodes;
        }
        List<String> lines = root.execLines(cmd.toString(), 25000);
        for (String line : lines) {
            int a = line.indexOf('[');
            int b = line.indexOf(']');
            if (a > 0 && b > a) {
                try {
                    inodes.add(Long.parseLong(line.substring(a + 1, b)));
                } catch (Throwable ignored) {
                    // not a socket line
                }
            }
        }
        return inodes;
    }

    /** Uptime in milliseconds of the device, derived from /proc/uptime. */
    public static long uptimeMillis() {
        String up = RootShell.get().plainCommand("cat /proc/uptime", 4000);
        if (up == null) {
            return SystemClock.elapsedRealtime();
        }
        try {
            String[] f = up.trim().split("\\s+");
            return (long) (Double.parseDouble(f[0]) * 1000);
        } catch (Throwable t) {
            return SystemClock.elapsedRealtime();
        }
    }
}
