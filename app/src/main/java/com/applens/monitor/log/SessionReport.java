package com.applens.monitor.log;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.LogSettings;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.model.AppFacts;
import com.applens.monitor.model.ConnectionItem;
import com.applens.monitor.model.DeviceProfile;
import com.applens.monitor.model.DnsRecord;
import com.applens.monitor.model.EventCategory;
import com.applens.monitor.model.EventItem;
import com.applens.monitor.model.PermissionItem;
import com.applens.monitor.model.ProcessStat;
import com.applens.monitor.model.StorageItem;
import com.applens.monitor.monitor.MonitorState;
import com.applens.monitor.repo.DeviceRepository;
import com.applens.monitor.repo.PackageFactsParser;
import com.applens.monitor.repo.ProcessRepository;
import com.applens.monitor.repo.StorageRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the parts of the record that make it a complete report instead of a bare
 * event stream: a full application/device snapshot when a session starts, a
 * periodic heartbeat, and a closing summary with every counter the monitors kept.
 *
 * <p>The individual record lines are written by the monitors through
 * {@link ActivityLogWriter}; everything here is appended to the same file, so a
 * single {@code activity_*.txt} contains the whole story of one session.</p>
 */
public final class SessionReport {

    private static final int MAX_COMPONENTS_LISTED = 40;

    private SessionReport() {
    }

    private static void line(String text) {
        ActivityLogWriter.get().writeRaw(text);
    }

    private static void section(String title) {
        line("");
        line("---------------------------------------------------------------------");
        line(" " + title);
        line("---------------------------------------------------------------------");
    }

    private static void kv(String key, String value) {
        line("  " + pad(key, 18) + ": " + (value == null ? "" : value));
    }

    private static String pad(String text, int width) {
        StringBuilder sb = new StringBuilder(text == null ? "" : text);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Start of session snapshot
    // ------------------------------------------------------------------

    /**
     * Dumps everything the app can learn about the target and the device into the
     * record. Runs on a worker thread — {@code dumpsys}, {@code du} and the device
     * probe are all root round-trips.
     */
    public static void writeStart(Context ctx, String pkg, String label, int uid, String recordPath) {
        try {
            section("SNAPSHOT — target application and device ("
                    + Fmt.dateTime(System.currentTimeMillis()) + ")");
            writeIdentity(ctx, pkg, uid);
            AppFacts facts = writeFacts(pkg);
            writePermissions(facts);
            writeAppOps(facts);
            writeComponents(facts);
            writeStorage(ctx, pkg);
            writeProcesses(pkg);
            writeDevice(ctx);
            writeConfiguration(ctx, pkg, recordPath);
        } catch (Throwable error) {
            line("  snapshot could not be completed: " + LogSettings.describe(error));
        }
        ActivityLogWriter.get().flush();
    }

    private static void writeIdentity(Context ctx, String pkg, int uid) {
        line(" [IDENTITY]");
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo info = pm.getPackageInfo(pkg, 0);
            ApplicationInfo app = info.applicationInfo;
            kv("uid", String.valueOf(app != null ? app.uid : uid));
            kv("version", Fmt.nz(info.versionName, "?") + " (" + versionCode(info) + ")");
            kv("target SDK", String.valueOf(app != null ? app.targetSdkVersion : -1));
            kv("min SDK", info.applicationInfo == null ? "?" : String.valueOf(info.applicationInfo.minSdkVersion));
            kv("installed", Fmt.dateTime(info.firstInstallTime));
            kv("updated", Fmt.dateTime(info.lastUpdateTime));
            if (app != null) {
                kv("data dir", app.dataDir);
                kv("apk", app.sourceDir);
                kv("native libs", nz(app.nativeLibraryDir));
                kv("flags", String.valueOf(app.flags));
                kv("enabled", String.valueOf(app.enabled));
            }
        } catch (Throwable error) {
            kv("package manager", "unavailable: " + LogSettings.describe(error));
        }
    }

    private static AppFacts writeFacts(String pkg) {
        RootShell root = RootShell.get();
        if (!root.ensureRoot()) {
            line("  (no root: permission, AppOps and component dumps are unavailable)");
            return null;
        }
        try {
            String dump = root.exec("dumpsys package " + RootShell.shQuote(pkg), 25000);
            AppFacts facts = PackageFactsParser.parse(pkg, dump);
            if (facts != null) {
                kv("installer", Fmt.nz(facts.installer, "unknown"));
                kv("shared user", Fmt.nz(facts.sharedUserId, "none"));
                kv("selinux", Fmt.nz(facts.seInfo, "none"));
                kv("gids", Fmt.nz(facts.gids, "—"));
                kv("pkg flags", facts.pkgFlagsText.isEmpty()
                        ? "—" : Fmt.join(", ", facts.pkgFlagsText));
            }
            return facts;
        } catch (Throwable error) {
            line("  package dump failed: " + LogSettings.describe(error));
            return null;
        }
    }

    private static void writePermissions(AppFacts facts) {
        if (facts == null) {
            return;
        }
        line("");
        line(" [PERMISSIONS] requested=" + facts.permissions.size()
                + " granted=" + facts.grantedCount()
                + " dangerous=" + facts.dangerousCount()
                + " (dangerous granted=" + facts.dangerousGrantedCount() + ")");
        List<PermissionItem> items = new ArrayList<>(facts.permissions);
        Collections.sort(items, (a, b) -> a.name.compareTo(b.name));
        for (PermissionItem item : items) {
            String state = item.granted ? "GRANTED" : "denied";
            line("    " + (item.granted ? "[✓]" : "[ ]") + " " + item.groupLabel
                    + " · " + PermissionItem.shortName(item.name)
                    + " (" + (item.dangerous ? "dangerous" : "normal") + ", " + state + ")"
                    + (item.appOpMode.isEmpty() ? "" : " appop=" + item.appOp + ":" + item.appOpMode));
        }
    }

    private static void writeAppOps(AppFacts facts) {
        if (facts == null || facts.appOps.isEmpty()) {
            return;
        }
        line("");
        line(" [APP OPS] (" + facts.appOps.size() + " entries)");
        for (Map.Entry<String, String> entry : facts.appOps.entrySet()) {
            line("    " + entry.getKey() + " = " + entry.getValue());
        }
    }

    private static void writeComponents(AppFacts facts) {
        if (facts == null) {
            return;
        }
        line("");
        line(" [COMPONENTS] activities=" + facts.activities.size()
                + " services=" + facts.services.size()
                + " receivers=" + facts.receivers.size()
                + " providers=" + facts.providers.size());
        writeComponentList("activities", facts.activities);
        writeComponentList("services", facts.services);
        writeComponentList("receivers", facts.receivers);
        writeComponentList("providers", facts.providers);
    }

    private static void writeComponentList(String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        line("    " + title + ":");
        int shown = 0;
        for (String value : values) {
            if (shown >= MAX_COMPONENTS_LISTED) {
                line("      … " + (values.size() - shown) + " more");
                break;
            }
            line("      " + value);
            shown++;
        }
    }

    private static void writeStorage(Context ctx, String pkg) {
        try {
            StorageRepository.Breakdown breakdown =
                    StorageRepository.breakdown(pkg, dataDirOf(ctx, pkg));
            line("");
            line(" [STORAGE AT START] total=" + Fmt.bytes(breakdown.total)
                    + " data=" + Fmt.bytes(breakdown.dataDirBytes)
                    + " files=" + (breakdown.fileCount < 0 ? "?" : String.valueOf(breakdown.fileCount))
                    + " databases=" + breakdown.databaseCount
                    + " cache=" + Fmt.bytes(breakdown.cacheSizeBytes));
            if (!breakdown.largestFile.isEmpty()) {
                kv("largest file", breakdown.largestFile + " ("
                        + Fmt.bytes(breakdown.largestFileBytes) + ")");
            }
            for (StorageItem item : breakdown.items) {
                if (item.bytes < 0) {
                    continue;
                }
                line("    " + pad(item.name, 16) + ": " + Fmt.bytes(item.bytes)
                        + (item.path.isEmpty() ? "" : "  " + item.path));
            }
        } catch (Throwable error) {
            line("  storage breakdown failed: " + LogSettings.describe(error));
        }
    }

    private static String dataDirOf(Context ctx, String pkg) {
        try {
            ApplicationInfo info = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return info != null ? info.dataDir : "/data/user/0/" + pkg;
        } catch (Throwable ignored) {
            return "/data/user/0/" + pkg;
        }
    }

    private static void writeProcesses(String pkg) {
        try {
            List<ProcessStat> stats = ProcessRepository.snapshot(pkg);
            line("");
            line(" [PROCESSES AT START] " + stats.size() + " process(es)");
            for (ProcessStat stat : stats) {
                line("    pid " + stat.pid + "  " + stat.name
                        + "  uid=" + stat.uid
                        + "  rss=" + Fmt.bytes(stat.rssKb * 1024)
                        + "  threads=" + stat.threads
                        + "  fds=" + stat.openFiles
                        + "  sockets=" + stat.sockets
                        + "  native=" + stat.nativeLibraries
                        + "  state=" + stat.stateLabel());
            }
        } catch (Throwable error) {
            line("  process table failed: " + LogSettings.describe(error));
        }
    }

    private static void writeDevice(Context ctx) {
        try {
            DeviceProfile profile = DeviceRepository.build(ctx);
            line("");
            line(" [DEVICE]");
            for (DeviceProfile.Section section : profile.sections) {
                line("    " + section.title + ":");
                for (DeviceProfile.Entry entry : section.entries) {
                    if (!entry.hasValue()) {
                        continue;
                    }
                    line("      " + pad(entry.label, 22) + ": " + entry.value
                            + (entry.note.isEmpty() ? "" : "  (" + entry.note + ")"));
                }
            }
        } catch (Throwable error) {
            line("  device profile failed: " + LogSettings.describe(error));
        }
    }

    private static void writeConfiguration(Context ctx, String pkg, String recordPath) {
        RootShell root = RootShell.get();
        line("");
        line(" [MONITORING CONFIGURATION]");
        kv("record path", nz(recordPath));
        kv("output folder", LogSettings.folder()
                + (LogSettings.folderPerApp() ? " (one folder per app)" : ""));
        kv("full report", LogSettings.fullReport() ? "yes" : "no");
        kv("heartbeat", LogSettings.heartbeatMinutes() + " min");
        kv("dns capture", LogSettings.dnsCapture()
                ? "enabled: " + com.applens.monitor.net.DnsVpnService.statusText()
                : "disabled in settings");
        kv("root", root.isRootGranted() ? root.suPath() + " via " + root.manager() : "not granted");
        kv("su invocations", String.valueOf(root.suInvocations()));
        kv("tools", "logcat=" + nz(root.logcat()) + " inotifyd=" + nz(root.inotifyd())
                + " tcpdump=" + nz(root.tcpdump()) + " du=" + nz(root.du()));
        kv("app version", versionOf(ctx));
        kv("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
    }

    private static String versionOf(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName + " (" + versionCode(info) + ")";
        } catch (Throwable ignored) {
            return "?";
        }
    }

    // ------------------------------------------------------------------
    // Heartbeat
    // ------------------------------------------------------------------

    /** Periodic mini summary so a long record shows what happened in between. */
    public static void writeHeartbeat(String pkg, MonitorState state,
                                      List<DnsRecord> dns, List<ConnectionItem> connections) {
        if (state == null) {
            return;
        }
        section("HEARTBEAT — " + Fmt.duration(state.elapsed()) + " of monitoring");
        kv("events", String.valueOf(state.eventCount));
        kv("connections", state.connectionCount + " active / " + connections.size() + " seen");
        kv("dns queries", state.dnsQueries + " (" + state.distinctDomains + " domains)");
        kv("processes", String.valueOf(state.processCount));
        kv("memory (PSS)", state.pssKb > 0 ? Fmt.bytes(state.pssKb * 1024) : "n/a");
        kv("traffic", "↑ " + Fmt.bytes(state.bytesUp) + "  ↓ " + Fmt.bytes(state.bytesDown)
                + (state.byteSource.isEmpty() ? "" : "  via " + state.byteSource));
        kv("file events", String.valueOf(state.fileEvents));
        kv("sources", state.sources.isEmpty() ? "—" : Fmt.join(", ", state.sources));
        List<DnsRecord> top = new ArrayList<>(dns);
        Collections.sort(top, (a, b) -> Integer.compare(b.queryCount, a.queryCount));
        if (!top.isEmpty()) {
            line("  top domains:");
            for (int i = 0; i < Math.min(10, top.size()); i++) {
                DnsRecord record = top.get(i);
                line("    " + pad(record.domain, 40) + " " + record.qtype
                        + " ×" + record.queryCount
                        + (record.answers.isEmpty() ? "" : "  → " + Fmt.join(", ", record.answers)));
            }
        }
        ActivityLogWriter.get().flush();
    }

    // ------------------------------------------------------------------
    // End of session summary
    // ------------------------------------------------------------------

    /**
     * Closing summary: totals, per-category counts, every domain and connection the
     * session saw, and the record file itself. Written before the buffer is flushed
     * by the caller, so it always lands in the session's own file.
     */
    public static void writeSummary(Context ctx, String pkg, String label, String reason,
                                    MonitorState state, List<EventItem> events,
                                    List<DnsRecord> dns, List<ConnectionItem> connections,
                                    List<ProcessStat> processes, String recordPath,
                                    long recordBytes) {
        try {
            if (state == null) {
                state = new MonitorState();
            }
            section("SESSION SUMMARY — " + Fmt.dateTime(System.currentTimeMillis()));
            kv("application", Fmt.nz(label, pkg));
            kv("package", pkg);
            kv("stop reason", nz(reason));
            kv("monitored for", Fmt.duration(state.elapsed()));
            kv("record file", nz(recordPath));
            kv("record size", Fmt.bytes(recordBytes));
            kv("writer status", ActivityLogWriter.get().status());
            kv("events", String.valueOf(state.eventCount));
            kv("dns queries", state.dnsQueries + " over " + state.distinctDomains + " domains");
            kv("connections", connections.size());
            kv("processes seen", processes.size());
            kv("file events", String.valueOf(state.fileEvents));
            kv("traffic", "↑ " + Fmt.bytes(state.bytesUp) + "  ↓ " + Fmt.bytes(state.bytesDown)
                    + (state.byteSource.isEmpty() ? "" : "  via " + state.byteSource));
            kv("data sources", state.sources.isEmpty() ? "—" : Fmt.join(", ", state.sources));

            Map<EventCategory, Integer> byCategory = new LinkedHashMap<>();
            for (EventItem event : events) {
                EventCategory category = event.category == null ? EventCategory.SYSTEM : event.category;
                Integer count = byCategory.get(category);
                byCategory.put(category, count == null ? 1 : count + 1);
            }
            if (!byCategory.isEmpty()) {
                line("");
                line("  events by category:");
                List<Map.Entry<EventCategory, Integer>> ordered =
                        new ArrayList<>(byCategory.entrySet());
                Collections.sort(ordered, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
                for (Map.Entry<EventCategory, Integer> entry : ordered) {
                    line("    " + pad(entry.getKey().label, 16) + " " + entry.getValue());
                }
            }

            if (!dns.isEmpty()) {
                line("");
                line("  domains resolved (" + dns.size() + "):");
                List<DnsRecord> sorted = new ArrayList<>(dns);
                Collections.sort(sorted, (a, b) -> Integer.compare(b.queryCount, a.queryCount));
                for (DnsRecord record : sorted) {
                    line("    " + Fmt.clock(record.time) + "  " + pad(record.domain, 40)
                            + " " + record.qtype + "  ×" + record.queryCount
                            + "  transport=" + record.transport
                            + "  server=" + nz(record.server)
                            + "  answers=" + record.answerText());
                }
            }

            if (!connections.isEmpty()) {
                line("");
                line("  connections (" + connections.size() + "):");
                List<ConnectionItem> sorted = new ArrayList<>(connections);
                Collections.sort(sorted, (a, b) -> Long.compare(b.lastSeen, a.lastSeen));
                for (ConnectionItem item : sorted) {
                    line("    " + Fmt.clock(item.lastSeen) + "  " + pad(item.proto, 5)
                            + " " + pad(item.targetWithPort(), 34)
                            + " state=" + pad(item.stateLabel(), 12)
                            + " up=" + Fmt.bytes(item.bytesUp) + " down=" + Fmt.bytes(item.bytesDown)
                            + " for=" + Fmt.duration(item.duration()));
                }
            }

            if (!processes.isEmpty()) {
                line("");
                line("  processes at stop:");
                for (ProcessStat stat : processes) {
                    line("    pid " + stat.pid + "  " + pad(stat.name, 28)
                            + " rss=" + Fmt.bytes(stat.rssKb * 1024)
                            + " threads=" + stat.threads
                            + " fds=" + stat.openFiles
                            + " cpu_ticks=" + (stat.utimeTicks + stat.stimeTicks));
                }
            }
            line("");
            line("=====================================================================");
            line(" End of record — " + Fmt.dateTime(System.currentTimeMillis()));
            line("=====================================================================");
        } catch (Throwable error) {
            line("  the session summary could not be completed: " + LogSettings.describe(error));
        }
        ActivityLogWriter.get().flush();
    }

    /** {@code getLongVersionCode()} is API 28+, the app supports API 26. */
    private static long versionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private static String nz(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }
}
