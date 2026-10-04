package com.applens.monitor.core;

import android.util.Log;

import com.applens.monitor.log.DiagnosticLog;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Everything AppLens does that a normal application cannot do goes through this
 * class: it owns <em>one</em> negotiated {@code su} session, executes root commands
 * inside it, streams long running root processes (logcat, inotifyd, tcpdump) and
 * reads or writes files that live outside the AppLens sandbox (including
 * /data/data/&lt;pkg&gt; and /sdcard).
 *
 * <p><b>One su process, not thousands.</b> Magisk / KernelSU / APatch raise a
 * "&lt;app&gt; was granted superuser rights" toast for <em>every</em> {@code su}
 * invocation. The monitors poll once per second, so spawning {@code su -c …} per
 * command produced a permanent stream of toasts. Instead a single interactive
 * {@code su} shell is kept open for the lifetime of the process and commands are
 * written to its stdin, terminated by a unique marker that also carries the exit
 * status. One superuser request per app start — the toast appears once.</p>
 *
 * <p>All public methods are safe to call from any thread. Commands are serialised
 * on the session and bounded by a hard timeout, so a hung {@code su} binary can
 * never wedge the UI: the watchdog kills the shell and the next call transparently
 * opens a fresh one.</p>
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

    /** Serialises every command that runs inside the shared session. */
    private final Object execLock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    /** How many times a {@code su} process was spawned since the app started. */
    private final AtomicInteger suInvocations = new AtomicInteger();

    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "applens-su-watchdog");
                t.setDaemon(true);
                return t;
            });

    private volatile Session session;
    private volatile boolean sessionUnsupported;

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
     * Number of {@code su} processes AppLens has started since launch. One for the
     * shared session plus one per long running stream (logcat / inotifyd / tcpdump)
     * — shown in the diagnostics so superuser-toast noise can be verified.
     */
    public int suInvocations() {
        return suInvocations.get();
    }

    /** True while the shared root shell is alive. */
    public boolean hasLiveSession() {
        Session local = session;
        return local != null && local.alive();
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
        if (rootGranted && !forceRetry) {
            return true;
        }
        if (forceRetry) {
            closeSession();
            rootGranted = false;
            sessionUnsupported = false;
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
            // Opening the session *is* the superuser request. Allow enough time for
            // the Magisk/KernelSU/APatch authorization dialog to be answered.
            Session opened = openSession(candidate, ROOT_REQUEST_TIMEOUT_MS);
            if (opened != null) {
                synchronized (execLock) {
                    closeSessionLocked();
                    session = opened;
                }
                workingSu = candidate;
                rootGranted = true;
                sessionUnsupported = false;
                lastError = "";
                Prefs.put(Prefs.K_ROOT_PATH, candidate);
                detectManager();
                return SuAttempt.GRANTED;
            }
            // The session could not be negotiated. Fall back to a classic one-shot
            // request so devices with an unusual su still work (one toast per
            // command there, which is why the session is always preferred).
            String out = runOneShot(candidate, "id -u", ROOT_REQUEST_TIMEOUT_MS);
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
                sessionUnsupported = true;
                lastError = "";
                Prefs.put(Prefs.K_ROOT_PATH, candidate);
                detectManager();
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

    /** Names the superuser manager without asking for a second authorization. */
    private void detectManager() {
        try {
            String probe = exec("command -v magisk >/dev/null 2>&1 && echo magisk; "
                    + "command -v ksud >/dev/null 2>&1 && echo kernelsu; "
                    + "command -v apd >/dev/null 2>&1 && echo apatch", 6000);
            if (probe == null || probe.trim().isEmpty()) {
                manager = "su";
                return;
            }
            if (probe.contains("magisk")) {
                manager = "Magisk";
            } else if (probe.contains("kernelsu")) {
                manager = "KernelSU";
            } else if (probe.contains("apatch")) {
                manager = "APatch";
            } else {
                manager = "su";
            }
        } catch (Throwable ignored) {
            manager = "su";
        }
    }

    // ------------------------------------------------------------------
    // The shared su session
    // ------------------------------------------------------------------

    /** One long lived {@code su} shell; commands are fed through its stdin. */
    private final class Session {

        private final String su;
        private final Process process;
        private final Writer stdin;
        private final BufferedReader stdout;
        private final StringBuilder errors = new StringBuilder();
        private volatile boolean dead;

        Session(String su, Process process) {
            this.su = su;
            this.process = process;
            this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            this.stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 1 << 16);
            Thread pump = new Thread(new Runnable() {
                @Override
                public void run() {
                    BufferedReader err = new BufferedReader(new InputStreamReader(
                            process.getErrorStream(), StandardCharsets.UTF_8), 8192);
                    try {
                        String line;
                        while ((line = err.readLine()) != null) {
                            synchronized (errors) {
                                if (errors.length() > 8192) {
                                    errors.setLength(0);
                                }
                                errors.append(line).append('\n');
                            }
                        }
                    } catch (Throwable ignored) {
                        // the shell is gone
                    }
                }
            }, "applens-su-stderr");
            pump.setDaemon(true);
            pump.start();
        }

        boolean alive() {
            if (dead) {
                return false;
            }
            try {
                return process.isAlive();
            } catch (Throwable t) {
                return false;
            }
        }

        String drainErrors() {
            synchronized (errors) {
                String text = errors.toString();
                errors.setLength(0);
                return text;
            }
        }

        void kill() {
            dead = true;
            try {
                stdin.close();
            } catch (Throwable ignored) {
                // noop
            }
            destroy(process);
        }

        /**
         * Runs one command and returns its stdout, or null when the shell died or
         * the command outlived {@code timeoutMs}. {@code exitCode} (optional, size
         * 1) receives the status.
         */
        String run(String command, int timeoutMs, int[] exitCode) {
            if (dead) {
                return null;
            }
            String marker = "__APPLENS_" + sequence.incrementAndGet() + "_"
                    + Long.toHexString(System.nanoTime()) + "__";
            ScheduledFuture<?> killer = null;
            try {
                killer = watchdog.schedule(new Runnable() {
                    @Override
                    public void run() {
                        // A command that never returns takes the shell with it; the
                        // next caller transparently negotiates a new one.
                        lastError = "Root command timed out (" + su + "): "
                                + Fmt.limit(command.replace('\n', ' '), 90);
                        kill();
                    }
                }, Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);

                stdin.write(command);
                stdin.write("\n");
                stdin.write("echo " + marker + " $?\n");
                stdin.flush();

                StringBuilder out = new StringBuilder();
                int total = 0;
                while (true) {
                    String line = stdout.readLine();
                    if (line == null) {
                        dead = true;
                        String stderr = drainErrors().trim();
                        if (!stderr.isEmpty()) {
                            lastError = Fmt.limit(stderr, 200);
                        }
                        return null;
                    }
                    // The marker can land on the same line as output that did not
                    // end with a newline, so it is searched for, not matched.
                    int at = line.indexOf(marker);
                    if (at >= 0) {
                        if (at > 0 && total <= MAX_OUTPUT) {
                            out.append(line, 0, at).append('\n');
                        }
                        if (exitCode != null && exitCode.length > 0) {
                            exitCode[0] = parseStatus(line.substring(at + marker.length()));
                        }
                        break;
                    }
                    total += line.length() + 1;
                    if (total <= MAX_OUTPUT) {
                        out.append(line).append('\n');
                    }
                }
                return out.toString();
            } catch (Throwable t) {
                dead = true;
                return null;
            } finally {
                if (killer != null) {
                    killer.cancel(false);
                }
            }
        }

        private int parseStatus(String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (Throwable t) {
                return -1;
            }
        }
    }

    /** Starts an interactive root shell and verifies that it really is root. */
    private Session openSession(String candidate, int timeoutMs) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv(candidate));
            pb.redirectErrorStream(false);
            process = pb.start();
            suInvocations.incrementAndGet();
            Session opened = new Session(candidate, process);
            String token = "__APPLENS_ROOT_OK__";
            String probe = opened.run("echo " + token + " $(id -u)", timeoutMs, null);
            if (probe == null || !probe.contains(token + " 0")) {
                opened.kill();
                return null;
            }
            // A predictable shell: C locale, no pager, no command echo.
            opened.run("export LANG=C; export LC_ALL=C; umask 022; true", 5000, null);
            return opened;
        } catch (Throwable t) {
            if (process != null) {
                destroy(process);
            }
            return null;
        }
    }

    private static List<String> argv(String candidate) {
        List<String> parts = new ArrayList<>();
        for (String p : candidate.trim().split("\\s+")) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        if (parts.isEmpty()) {
            parts.add("su");
        }
        return parts;
    }

    /** Must be called with {@link #execLock} held. */
    private void closeSessionLocked() {
        Session local = session;
        session = null;
        if (local != null) {
            local.kill();
        }
    }

    private void closeSession() {
        synchronized (execLock) {
            closeSessionLocked();
        }
    }

    /** Must be called with {@link #execLock} held. */
    private Session sessionLocked() {
        Session local = session;
        if (local != null && local.alive()) {
            return local;
        }
        if (local != null) {
            local.kill();
            session = null;
        }
        if (sessionUnsupported || workingSu.isEmpty()) {
            return null;
        }
        Session fresh = openSession(workingSu, ROOT_REQUEST_TIMEOUT_MS);
        if (fresh == null) {
            // Keep working through one-shot invocations rather than losing root.
            sessionUnsupported = true;
            return null;
        }
        session = fresh;
        return fresh;
    }

    // ------------------------------------------------------------------
    // Command execution
    // ------------------------------------------------------------------

    /** Runs {@code cmd} through the negotiated {@code su} and returns stdout (trimmed). */
    public String exec(String cmd) {
        return exec(cmd, 15000);
    }

    public String exec(String cmd, int timeoutMs) {
        String out = execRaw(cmd, timeoutMs);
        return out == null ? null : out.trim();
    }

    /** Runs {@code cmd} as root and returns the raw (untrimmed) stdout, or null. */
    public String execRaw(String cmd, int timeoutMs) {
        if (cmd == null || cmd.isEmpty() || !ensureRoot()) {
            return null;
        }
        return runRoot(cmd, timeoutMs, null);
    }

    /** Runs a command without root. */
    public String plainCommand(String cmd, int timeoutMs) {
        return runCommand(new String[]{"sh", "-c", cmd}, timeoutMs);
    }

    public boolean isTrue(String cmd) {
        String out = exec(cmd);
        return out != null && !out.isEmpty() && !"0".equals(out.trim());
    }

    /** Runs {@code cmd} and returns its exit status, or -1 when it could not run. */
    public int statusOf(String cmd) {
        if (cmd == null || cmd.isEmpty() || !ensureRoot()) {
            return -1;
        }
        int[] status = new int[]{-1};
        String out = runRoot(cmd, 15000, status);
        if (out == null) {
            return -1;
        }
        return status[0];
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

    /**
     * The single entry point for root commands: the shared session when it is
     * available, a one-shot {@code su -c} otherwise.
     */
    private String runRoot(String cmd, int timeoutMs, int[] exitCode) {
        synchronized (execLock) {
            Session live = sessionLocked();
            if (live != null) {
                String out = live.run(cmd, timeoutMs, exitCode);
                if (out != null) {
                    return out;
                }
                // The shell died mid-command (timeout, su revoked, OOM). Retry once
                // on a brand new session before giving up.
                closeSessionLocked();
                Session retry = sessionLocked();
                if (retry != null) {
                    out = retry.run(cmd, timeoutMs, exitCode);
                    if (out != null) {
                        return out;
                    }
                    closeSessionLocked();
                }
            }
        }
        if (workingSu.isEmpty()) {
            return null;
        }
        if (exitCode == null || exitCode.length == 0) {
            return runOneShot(workingSu, cmd, timeoutMs);
        }
        // One-shot shells cannot report the status out of band, so it is appended
        // to the output and stripped again — the command still runs exactly once.
        String marker = "__APPLENS_RC_" + sequence.incrementAndGet() + "__";
        String out = runOneShot(workingSu, cmd + "\necho " + marker + " $?", timeoutMs);
        if (out == null) {
            return null;
        }
        int idx = out.lastIndexOf(marker);
        if (idx < 0) {
            exitCode[0] = -1;
            return out;
        }
        try {
            exitCode[0] = Integer.parseInt(out.substring(idx + marker.length()).trim());
        } catch (Throwable t) {
            exitCode[0] = -1;
        }
        return out.substring(0, idx);
    }

    private String runOneShot(String candidate, String cmd, int timeoutMs) {
        List<String> parts = argv(candidate);
        parts.add("-c");
        parts.add(cmd);
        suInvocations.incrementAndGet();
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
     *
     * <p>A stream needs a process of its own, so this is the only place besides the
     * shared session that spawns {@code su}. Callers must therefore check that the
     * tool actually exists before starting one.</p>
     */
    public Process startStream(String command, LineSink sink) {
        if (!ensureRoot() || command == null || command.trim().isEmpty()) {
            return null;
        }
        try {
            List<String> parts = argv(workingSu);
            parts.add("-c");
            parts.add(command);
            ProcessBuilder pb = new ProcessBuilder(parts);
            pb.redirectErrorStream(true);
            final Process proc = pb.start();
            suInvocations.incrementAndGet();
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
        // Through the shared session (no extra superuser request) whenever base64
        // is available, which it is on every Android with toybox.
        String encoded = execRaw("base64 " + shQuote(path) + " 2>/dev/null", 20000);
        if (encoded != null && !encoded.trim().isEmpty()) {
            try {
                byte[] data = android.util.Base64.decode(
                        encoded.replace("\n", "").replace("\r", ""), android.util.Base64.DEFAULT);
                if (data != null) {
                    if (maxBytes > 0 && data.length > maxBytes) {
                        byte[] cut = new byte[maxBytes];
                        System.arraycopy(data, 0, cut, 0, maxBytes);
                        return cut;
                    }
                    return data;
                }
            } catch (Throwable ignored) {
                // fall through to the raw reader
            }
        }
        Process proc = null;
        try {
            List<String> parts = argv(workingSu);
            parts.add("-c");
            parts.add("cat " + shQuote(path));
            ProcessBuilder pb = new ProcessBuilder(parts);
            pb.redirectErrorStream(true);
            proc = pb.start();
            suInvocations.incrementAndGet();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = drain(proc.getInputStream(), out);
            try {
                if (!proc.waitFor(10000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    destroy(proc);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            joinQuietly(reader);
            byte[] data = out.toByteArray();
            if (maxBytes > 0 && data.length > maxBytes) {
                byte[] cut = new byte[maxBytes];
                System.arraycopy(data, 0, cut, 0, maxBytes);
                return cut;
            }
            return data;
        } catch (Throwable t) {
            if (proc != null) {
                destroy(proc);
            }
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
        String eof = "APPLENS_EOF_" + Long.toHexString(sequence.incrementAndGet());
        String cmd = "mkdir -p " + shQuote(parentOf(path)) + " 2>/dev/null; cat > " + shQuote(path)
                + " <<'" + eof + "'\n" + content + "\n" + eof;
        return statusOf(cmd) == 0;
    }

    public boolean appendFile(String path, String content) {
        return appendText(path, content, 15000);
    }

    /**
     * Appends UTF-8 text to a file as root through the shared session, so the
     * activity record can be flushed twice a second without a superuser request
     * (and therefore a toast) per flush.
     */
    public boolean appendText(String path, String content, int timeoutMs) {
        if (!ensureRoot() || content == null || content.isEmpty()) {
            return false;
        }
        String eof = "APPLENS_EOF_" + Long.toHexString(sequence.incrementAndGet());
        String body = content.endsWith("\n") ? content : content + "\n";
        String cmd = "mkdir -p " + shQuote(parentOf(path)) + " 2>/dev/null; cat >> " + shQuote(path)
                + " <<'" + eof + "'\n" + body + eof;
        int[] status = new int[]{-1};
        String out = runRoot(cmd, timeoutMs, status);
        return out != null && status[0] == 0;
    }

    public boolean deleteRecursively(String path) {
        return statusOf("rm -rf " + shQuote(path)) == 0;
    }

    /** Outcome of a "create this folder and prove it is writable" check. */
    public static final class Check {
        public final boolean ok;
        public final String path;
        public final String note;

        Check(boolean ok, String path, String note) {
            this.ok = ok;
            this.path = path == null ? "" : path;
            this.note = note == null ? "" : note;
        }
    }

    /**
     * Creates {@code path} as root and writes a probe file inside it, so the caller
     * knows the folder exists <em>and</em> that a report can really be written there
     * (a missing SELinux context or a read-only volume would otherwise only show up
     * as an empty report file much later).
     */
    public Check makeFolder(String path) {
        if (path == null || path.isEmpty()) {
            return new Check(false, path, "empty path");
        }
        if (!ensureRoot()) {
            return new Check(false, path, "no root access");
        }
        String quoted = shQuote(path);
        String probe = path + "/.applens_write_test";
        int mkdir = statusOf("mkdir -p " + quoted + " 2>/dev/null");
        if (mkdir != 0 && !exists(path)) {
            String reason = statusOf("mkdir -p " + quoted) == 0 ? "" : "mkdir failed";
            return new Check(false, path, reason.isEmpty() ? "folder not created" : reason);
        }
        if (!writeFile(probe, "AppLens write test")) {
            return new Check(false, path, "probe file could not be written");
        }
        long size = fileSize(probe);
        exec("rm -f " + shQuote(probe) + " 2>/dev/null");
        if (size <= 0) {
            return new Check(false, path, "probe file stayed empty");
        }
        return new Check(true, path, "root");
    }

    /**
     * Appends arbitrary bytes to a file as root by streaming them through a
     * dedicated shell's stdin. Only used when the session is unavailable, because
     * it costs one superuser request per call.
     */
    public boolean appendStdin(String path, byte[] data, int timeoutMs) {
        if (!ensureRoot() || data == null || data.length == 0) {
            return false;
        }
        Process proc = null;
        try {
            List<String> parts = argv(workingSu);
            parts.add("-c");
            parts.add("mkdir -p " + shQuote(parentOf(path)) + " 2>/dev/null; cat >> " + shQuote(path));
            proc = new ProcessBuilder(parts).start();
            suInvocations.incrementAndGet();
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

    /** Cached {@code which}; returns null (never "") when the tool is missing. */
    private String cachedTool(String current, String binary) {
        if (current == null) {
            return null;
        }
        if (!current.isEmpty()) {
            return current;
        }
        String found = which(binary);
        return found == null ? "" : found;
    }

    private static String orNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    public String logcat() {
        logcatBinary = cachedTool(logcatBinary, "logcat");
        return orNull(logcatBinary);
    }

    public String inotifyd() {
        inotifyBinary = cachedTool(inotifyBinary, "inotifyd");
        return orNull(inotifyBinary);
    }

    public String tcpdump() {
        tcpdumpBinary = cachedTool(tcpdumpBinary, "tcpdump");
        return orNull(tcpdumpBinary);
    }

    public String ps() {
        psBinary = cachedTool(psBinary, "ps");
        return orNull(psBinary);
    }

    public String du() {
        duBinary = cachedTool(duBinary, "du");
        return orNull(duBinary);
    }

    public String busybox() {
        busyboxPath = cachedTool(busyboxPath, "busybox");
        return orNull(busyboxPath);
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
