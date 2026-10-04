package com.applens.monitor.core;

import android.util.Log;

import com.applens.monitor.log.DiagnosticLog;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything AppLens does that a normal application cannot do goes through this
 * class: it owns a negotiated {@code su} session, executes one-shot root commands,
 * streams long running root processes (logcat, inotifyd, tcpdump) and reads or
 * writes files that live outside the AppLens sandbox (including /data/data/&lt;pkg&gt;
 * and /sdcard).
 *
 * <p>All public methods are safe to call from any thread. Commands are executed
 * with a hard timeout so a hung {@code su} binary can never wedge the UI.</p>
 */
public final class RootShell {

    public static final String TAG = "AppLens";

    /** Candidate {@code su} binaries, tried in order. */
    private static final String[] SU_CANDIDATES = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
    };

    /** Maximum stdout/stderr we ever buffer for a single command (8 MiB). */
    private static final int MAX_OUTPUT = 8 * 1024 * 1024;
    private static final int ROOT_REQUEST_TIMEOUT_MS = 60000;

    private enum SuAttempt {
        GRANTED, REJECTED, UNAVAILABLE
    }

    private static volatile RootShell instance;

    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<String> extraPaths = new CopyOnWriteArrayList<>();

    private volatile boolean rootGranted;
    private volatile boolean rootAttempted;
    private volatile String workingSu = "";
    private volatile String manager = "Unknown";
    private volatile String lastError = "";
    private volatile String logcatBinary = "";
    private volatile String inotifyBinary = "";
    private volatile String tcpdumpBinary = "";
    private volatile String psBinary = "";
    private volatile String duBinary = "";
    private volatile String busyboxPath = "";

    private RootShell() {
    }

    public static RootShell get() {
        RootShell local = instance;
        if (local == null) {
            synchronized (RootShell.class) {
                local = instance;
                if (local == null) {
                    local = new RootShell();
                    instance = local;
                }
            }
        }
        return local;
    }

    // ------------------------------------------------------------------
    // Root negotiation
    // ------------------------------------------------------------------

    public boolean isRootGranted() {
        return rootGranted;
    }

    /** True while waiting for the superuser manager to answer a root request. */
    public boolean isRootChecking() {
        return checking.get();
    }

    public String suPath() {
        return workingSu;
    }

    /** Detected superuser manager, e.g. Magisk / KernelSU / APatch. */
    public String manager() {
        return manager;
    }

    public String lastError() {
        return lastError;
    }

    /**
     * Attempts to obtain a root session. The first successful negotiation is cached,
     * which is what makes the "grant once, works forever" behaviour of Magisk /
     * KernelSU apply here.
     */
    public synchronized boolean ensureRoot() {
        return ensureRootLocked(false);
    }

    /** Explicit retry used when the user taps the root-status chip. */
    public synchronized boolean requestRootAccess() {
        return ensureRootLocked(true);
    }

    private boolean ensureRootLocked(boolean forceRetry) {
        if (rootGranted) {
            return true;
        }
        // Cache the result so scans do not repeatedly trigger superuser prompts.
        // An explicit tap can retry after the user changes the Magisk policy.
        if (rootAttempted && !forceRetry) {
            return false;
        }
        rootAttempted = true;
        // Other callers wait here instead of seeing a stale "denied" result while
        // a permission prompt is still open on another thread.
        checking.set(true);
        try {
            String savedPath = Prefs.str(Prefs.K_ROOT_PATH, "");
            if (savedPath != null && !savedPath.trim().isEmpty()) {
                SuAttempt attempt = trySu(savedPath.trim());
                if (attempt == SuAttempt.GRANTED) {
                    return true;
                }
                if (attempt == SuAttempt.REJECTED) {
                    return false;
                }
            }
            for (String extra : extraPaths) {
                SuAttempt attempt = trySu(extra);
                if (attempt == SuAttempt.GRANTED) {
                    return true;
                }
                if (attempt == SuAttempt.REJECTED) {
                    return false;
                }
            }
            for (String candidate : SU_CANDIDATES) {
                if (candidate.equals(savedPath) || extraPaths.contains(candidate)) {
                    continue;
                }
                SuAttempt attempt = trySu(candidate);
                if (attempt == SuAttempt.GRANTED) {
                    return true;
                }
                if (attempt == SuAttempt.REJECTED) {
                    return false;
                }
            }
            // Some KernelSU / APatch builds only expose su through a helper.
            for (String candidate : new String[]{"magisk su", "ksud su", "apd su"}) {
                SuAttempt attempt = trySu(candidate);
                if (attempt == SuAttempt.GRANTED) {
                    return true;
                }
                if (attempt == SuAttempt.REJECTED) {
                    return false;
                }
            }
            rootGranted = false;
            lastError = "No su binary responded";
        } catch (Throwable t) {
            rootGranted = false;
            lastError = String.valueOf(t.getMessage());
            Log.w(TAG, "root negotiation failed", t);
            DiagnosticLog.recordProblem("Root access negotiation failed", t);
        } finally {
            checking.set(false);
        }
        return rootGranted;
    }

    private SuAttempt trySu(String candidate) {
        if (!isSuAvailable(candidate)) {
            return SuAttempt.UNAVAILABLE;
        }
        try {
            // `su -c id -u` prints exactly `0` when elevated. Allow enough time for
            // Magisk/KernelSU/APatch's first-time authorization dialog to be answered.
            String out = runSu(candidate, "id -u", ROOT_REQUEST_TIMEOUT_MS);
            if (out == null) {
                // The executable was present but did not answer in time. Do not try
                // another alias and accidentally open a second authorization prompt.
                lastError = "Superuser request timed out";
                return SuAttempt.REJECTED;
            }
            String identity = out.trim();
            if ("0".equals(identity) || identity.startsWith("uid=0(")) {
                workingSu = candidate;
                rootGranted = true;
                lastError = "";
                Prefs.put(Prefs.K_ROOT_PATH, candidate);
                return SuAttempt.GRANTED;
            }
            lastError = "Superuser access was not granted";
            return SuAttempt.REJECTED;
        } catch (Throwable ignored) {
            return SuAttempt.UNAVAILABLE;
        }
    }

    private boolean isSuAvailable(String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return false;
        }
        String executable = candidate.trim().split("\\s+")[0];
        if (executable.contains("/")) {
            java.io.File file = new java.io.File(executable);
            return file.isFile() && file.canExecute();
        }
        String found = plainCommand("command -v " + shQuote(executable) + " 2>/dev/null", 2000);
        return found != null && !found.trim().isEmpty();
    }

    /** Registers an extra {@code su} location discovered on the device. */
    public void addSuPath(String path) {
        if (path != null && !path.isEmpty() && !extraPaths.contains(path)) {
            extraPaths.add(path);
        }
    }

    // ------------------------------------------------------------------
    // Command execution
    // ------------------------------------------------------------------

    /** Runs {@code cmd} through the negotiated {@code su} and returns stdout (trimmed). */
    public String exec(String cmd) {
        return exec(cmd, 15000);
    }

    public String exec(String cmd, int timeoutMs) {
        if (cmd == null || cmd.isEmpty()) {
            return null;
        }
        if (!ensureRoot()) {
            return null;
        }
        String out = runSu(workingSu, cmd, timeoutMs);
        return out == null ? null : out.trim();
    }

    /** Runs {@code cmd} as root and returns the raw (untrimmed) stdout, or null. */
    public String execRaw(String cmd, int timeoutMs) {
        if (cmd == null || cmd.isEmpty() || !ensureRoot()) {
            return null;
        }
        return runSu(workingSu, cmd, timeoutMs);
    }

    /** Runs a command without root. */
    public String plainCommand(String cmd, int timeoutMs) {
        return runCommand(new String[]{"sh", "-c", cmd}, timeoutMs);
    }

    public boolean isTrue(String cmd) {
        String out = exec(cmd);
        return out != null && !out.isEmpty() && !"0".equals(out.trim());
    }

    public int statusOf(String cmd) {
        String out = exec(cmd + "; echo __rc=$?");
        if (out == null) {
            return -1;
        }
        int idx = out.lastIndexOf("__rc=");
        if (idx < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(out.substring(idx + 5).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public List<String> execLines(String cmd) {
        return execLines(cmd, 15000);
    }

    public List<String> execLines(String cmd, int timeoutMs) {
        String raw = execRaw(cmd, timeoutMs);
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            out.add(line);
        }
        return out;
    }

    private String runSu(String candidate, String cmd, int timeoutMs) {
        List<String> parts = new ArrayList<>();
        for (String p : candidate.split(" ")) {
            parts.add(p);
        }
        parts.add("-c");
        parts.add(cmd);
        return runCommand(parts.toArray(new String[0]), timeoutMs);
    }

    private String runCommand(String[] argv, int timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            p = pb.start();
            final Process proc = p;
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread to = drain(proc.getInputStream(), out);
            Thread te = drain(proc.getErrorStream(), err);
            boolean finished;
            try {
                finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            if (!finished) {
                destroy(proc);
                joinQuietly(to, te);
                return null;
            }
            joinQuietly(to, te);
            if (out.size() > MAX_OUTPUT) {
                return new String(out.toByteArray(), 0, MAX_OUTPUT, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (out.size() == 0 && err.size() > 0) {
                return new String(err.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            if (p != null) {
                destroy(p);
            }
            DiagnosticLog.recordThrottledProblem("root-command", "Root command failed to start", t);
            return null;
        }
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[8192];
                try {
                    int n;
                    int total = 0;
                    while ((n = in.read(buf)) > 0) {
                        sink.write(buf, 0, n);
                        total += n;
                        if (total > MAX_OUTPUT) {
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                    // stream closed
                }
            }
        }, "applens-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void destroy(Process p) {
        try {
            p.destroy();
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private static void joinQuietly(Thread... threads) {
        for (Thread t : threads) {
            try {
                t.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------
    // Long running root processes
    // ------------------------------------------------------------------

    public interface LineSink {
        void onLine(String line);
    }

    /**
     * Starts a long running root process and pumps its stdout into {@code sink}.
     * Returns the {@link Process} so the caller can terminate it, or null.
     */
    public Process startStream(String command, LineSink sink) {
        if (!ensureRoot()) {
            return null;
        }
        try {
            List<String> parts = new ArrayList<>();
            for (String p : workingSu.split(" ")) {
                parts.add(p);
            }
            parts.add("-c");
            parts.add(command);
            ProcessBuilder pb = new ProcessBuilder(parts);
            pb.redirectErrorStream(true);
            final Process proc = pb.start();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8), 1 << 16);
                    try {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (sink != null) {
                                sink.onLine(line);
                            }
                        }
                    } catch (Throwable ignored) {
                        // stream terminated
                    }
                }
            }, "applens-stream");
            reader.setDaemon(true);
            reader.start();
            return proc;
        } catch (Throwable t) {
            lastError = String.valueOf(t.getMessage());
            DiagnosticLog.recordThrottledProblem("root-stream", "Unable to start a root monitoring stream", t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // File helpers
    // ------------------------------------------------------------------

    public static String shQuote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public byte[] readBytes(String path, int maxBytes) {
        if (!ensureRoot()) {
            return null;
        }
        try {
            List<String> parts = new ArrayList<>();
            for (String p : workingSu.split(" ")) {
                parts.add(p);
            }
            parts.add("-c");
            parts.add("cat " + shQuote(path));
            ProcessBuilder pb = new ProcessBuilder(parts);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            drain(proc.getInputStream(), out);
            try {
                proc.waitFor(10000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] data = out.toByteArray();
            if (maxBytes > 0 && data.length > maxBytes) {
                byte[] cut = new byte[maxBytes];
                System.arraycopy(data, 0, cut, 0, maxBytes);
                return cut;
            }
            return data;
        } catch (Throwable t) {
            return null;
        }
    }

    public String readFile(String path) {
        byte[] data = readBytes(path, 4 * 1024 * 1024);
        return data == null ? null : new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    public List<String> readLines(String path, int maxLines) {
        String text = readFile(path);
        if (text == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (maxLines > 0 && out.size() >= maxLines) {
                break;
            }
            out.add(line);
        }
        return out;
    }

    public boolean writeFile(String path, String content) {
        if (!ensureRoot()) {
            return false;
        }
        String cmd = "mkdir -p " + shQuote(parentOf(path)) + " 2>/dev/null; cat > " + shQuote(path)
                + " <<'APPLENS_EOF'\n" + content + "\nAPPLENS_EOF";
        return statusOf(cmd) == 0;
    }

    public boolean appendFile(String path, String content) {
        if (!ensureRoot()) {
            return false;
        }
        String cmd = "mkdir -p " + shQuote(parentOf(path)) + " 2>/dev/null; cat >> " + shQuote(path)
                + " <<'APPLENS_EOF'\n" + content + "\nAPPLENS_EOF";
        return statusOf(cmd) == 0;
    }

    public boolean deleteRecursively(String path) {
        return statusOf("rm -rf " + shQuote(path)) == 0;
    }

    /**
     * Appends arbitrary bytes to a file as root by streaming them through the shell's
     * stdin. Unlike {@link #appendFile} this is safe for any content, which matters
     * for the plain-text activity records written to /sdcard.
     */
    public boolean appendStdin(String path, byte[] data, int timeoutMs) {
        if (!ensureRoot() || data == null || data.length == 0) {
            return false;
        }
        Process proc = null;
        try {
            List<String> parts = new ArrayList<>();
            for (String p : workingSu.split(" ")) {
                parts.add(p);
            }
            parts.add("-c");
            parts.add("mkdir -p " + shQuote(parentOf(path)) + " 2>/dev/null; cat >> " + shQuote(path));
            proc = new ProcessBuilder(parts).start();
            java.io.OutputStream os = proc.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            boolean done = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                destroy(proc);
                return false;
            }
            return proc.exitValue() == 0;
        } catch (Throwable t) {
            if (proc != null) {
                destroy(proc);
            }
            return false;
        }
    }

    /** Reads the absolute path of external storage as seen by root. */
    public String externalStoragePath() {
        String out = exec("echo $EXTERNAL_STORAGE 2>/dev/null");
        if (out != null && out.startsWith("/")) {
            return out;
        }
        out = exec("ls -d /sdcard /storage/emulated/0 2>/dev/null | head -1");
        return out == null || out.isEmpty() ? "/sdcard" : out;
    }

    public boolean exists(String path) {
        return statusOf("test -e " + shQuote(path)) == 0;
    }

    public long fileSize(String path) {
        String out = exec("stat -c %s " + shQuote(path) + " 2>/dev/null");
        if (out == null) {
            return -1L;
        }
        try {
            return Long.parseLong(out.trim().split("\\s+")[0]);
        } catch (Throwable t) {
            return -1L;
        }
    }

    public String parentOf(String path) {
        int idx = path.lastIndexOf('/');
        if (idx <= 0) {
            return "/";
        }
        return path.substring(0, idx);
    }

    // ------------------------------------------------------------------
    // Tool discovery (cached)
    // ------------------------------------------------------------------

    public String which(String binary) {
        String out = exec("command -v " + shQuote(binary) + " 2>/dev/null");
        if (out == null) {
            return null;
        }
        out = out.trim();
        return out.isEmpty() ? null : out;
    }

    public String logcat() {
        if (logcatBinary.isEmpty()) {
            String p = which("logcat");
            logcatBinary = p == null ? "" : p;
        }
        return logcatBinary;
    }

    public String inotifyd() {
        if (inotifyBinary.isEmpty()) {
            String p = which("inotifyd");
            inotifyBinary = p == null ? "" : p;
        }
        return inotifyBinary;
    }

    public String tcpdump() {
        if (tcpdumpBinary.isEmpty()) {
            String p = which("tcpdump");
            tcpdumpBinary = p == null ? "" : p;
        }
        return tcpdumpBinary;
    }

    public String ps() {
        if (psBinary.isEmpty()) {
            String p = which("ps");
            psBinary = p == null ? "" : p;
        }
        return psBinary;
    }

    public String du() {
        if (duBinary.isEmpty()) {
            String p = which("du");
            duBinary = p == null ? "" : p;
        }
        return duBinary;
    }

    public String busybox() {
        if (busyboxPath.isEmpty()) {
            String p = which("busybox");
            busyboxPath = p == null ? "" : p;
        }
        return busyboxPath;
    }

    public boolean hasBusyboxApplet(String applet) {
        String bb = busybox();
        if (bb == null) {
            return false;
        }
        return statusOf(shQuote(bb) + " " + applet + " --help >/dev/null 2>&1") == 0;
    }

    /** Disables aapt/runtime permission enforcement by re-granting everything the app asked for. */
    public boolean grantAllRuntimePermissions(String pkg) {
        if (!ensureRoot()) {
            return false;
        }
        String out = exec("dumpsys package " + shQuote(pkg) + " 2>/dev/null | "
                + "sed -n 's/^ *\\([a-zA-Z0-9_.]*\\): granted=.*/\\1/p' | sort -u");
        if (out == null || out.isEmpty()) {
            return false;
        }
        int granted = 0;
        for (String line : out.split("\n")) {
            String perm = line.trim();
            if (perm.isEmpty() || perm.contains(" ") || perm.indexOf('.') < 0) {
                continue;
            }
            if (statusOf("pm grant " + shQuote(pkg) + " " + shQuote(perm)) == 0) {
                granted++;
            }
        }
        return granted > 0;
    }

    /** Resets an application's runtime permissions (root). */
    public boolean resetRuntimePermissions(String pkg) {
        if (!ensureRoot()) {
            return false;
        }
        return statusOf("pm reset-permissions >/dev/null 2>&1; cmd appops reset " + shQuote(pkg)) == 0;
    }

    // ------------------------------------------------------------------
    // /proc parsing helpers
    // ------------------------------------------------------------------

    /** Converts a /proc/net hex address ({@code 0100007F:1F90}) to dotted-quad. */
    public static String hexToIp(String hex) {
        if (hex == null) {
            return "?";
        }
        long value;
        try {
            value = Long.parseLong(hex, 16);
        } catch (Throwable t) {
            return hex;
        }
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "." + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }

    /** Converts a /proc/net IPv6 hex address (32 hex chars, little endian words) to text. */
    public static String hexToIp6(String hex) {
        if (hex == null || hex.length() != 32) {
            return hex == null ? "??" : hex;
        }
        long[] words = new long[4];
        try {
            for (int i = 0; i < 4; i++) {
                // /proc stores each 32 bit word in host (little endian) byte order
                words[i] = Long.parseLong(hex.substring(i * 8, i * 8 + 8), 16);
            }
        } catch (Throwable t) {
            return hex;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                sb.append(':');
            }
            int w = (int) (words[i] & 0xFFFFFFFFL);
            for (int j = 1; j >= 0; j--) {
                int group = (w >> (j * 16)) & 0xFFFF;
                if (j == 0) {
                    sb.append(Integer.toHexString(group));
                } else {
                    String s = Integer.toHexString(group);
                    for (int p = s.length(); p < 4; p++) {
                        sb.append('0');
                    }
                    sb.append(s);
                }
            }
        }
        return sb.toString();
    }

    public static int hexPort(String hexPort) {
        try {
            return Integer.parseInt(hexPort, 16);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Converts a dotted quad back into the little-endian hex form used by
     * {@code /proc/net/tcp}, so a socket can be located by its peer address.
     */
    public static String dottedToHex(String ip) {
        if (ip == null) {
            return "";
        }
        String[] f = ip.split("\\.");
        if (f.length != 4) {
            return "";
        }
        try {
            long v = 0;
            for (int i = 3; i >= 0; i--) {
                v = (v << 8) | (Long.parseLong(f[i]) & 0xFF);
            }
            return String.format("%08X", v);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Closes a stream ignoring errors. */
    public static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // noop
            }
        }
    }
}
