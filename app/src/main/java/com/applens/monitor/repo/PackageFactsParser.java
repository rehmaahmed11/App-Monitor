package com.applens.monitor.repo;

import com.applens.monitor.core.RootShell;
import com.applens.monitor.model.AppFacts;
import com.applens.monitor.model.PermissionItem;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for {@code dumpsys package <pkg>} and {@code appops get <pkg>} output.
 * Everything here runs against the root shell because the platform hides most of
 * this information from a normal application.
 */
public final class PackageFactsParser {

    private static final Pattern INSTALL_TIME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)");
    private static final Pattern PERM_STATE = Pattern.compile("^\\s*([a-zA-Z0-9_.]+):\\s*granted=(true|false)\\s*,?\\s*(.*)$");
    private static final Pattern APPOP_STATE = Pattern.compile("^\\s*([A-Z0-9_]+):\\s*(allow|ignore|deny|default|error|foreground|allowed)(.*)$");
    private static final Pattern BARE_PERMISSION = Pattern.compile("^\\s*([a-z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+)\\s*$");

    private enum Section {
        NONE, REQUESTED, INSTALL, RUNTIME, USER, PROVIDERS, OTHER
    }

    private PackageFactsParser() {
    }

    public static AppFacts parse(String pkg, String dump) {
        AppFacts facts = new AppFacts();
        facts.pkg = pkg;
        if (dump == null || dump.isEmpty()) {
            return facts;
        }
        facts.dumpsysAvailable = "ok";

        Map<String, PermissionItem> byName = new LinkedHashMap<>();
        Map<String, Boolean> requested = new HashMap<>();
        Section section = Section.NONE;
        String[] lines = dump.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.US);

            if (lower.equals("requested permissions:")) {
                section = Section.REQUESTED;
                continue;
            }
            if (lower.equals("install permissions:")) {
                section = Section.INSTALL;
                continue;
            }
            if (lower.equals("runtime permissions:")) {
                section = Section.RUNTIME;
                continue;
            }
            if (lower.equals("enabled components:") || lower.equals("disabled components:")
                    || lower.equals("requested overrides:")) {
                section = Section.OTHER;
                continue;
            }
            if (line.startsWith("User ")) {
                section = Section.USER;
                continue;
            }
            if (line.startsWith("Key Set Manager") || line.startsWith("Queries")) {
                section = Section.OTHER;
                continue;
            }
            if (section == Section.OTHER) {
                continue;
            }

            if (section == Section.REQUESTED) {
                Matcher m = BARE_PERMISSION.matcher(line);
                if (m.matches()) {
                    String name = m.group(1);
                    if (name.startsWith("android.") || name.contains(".")) {
                        requested.put(name, Boolean.TRUE);
                        PermissionItem item = byName.get(name);
                        if (item == null) {
                            item = newItem(name);
                            byName.put(name, item);
                        }
                        item.requested = true;
                    }
                    continue;
                }
                section = Section.NONE;
            }

            Matcher state = PERM_STATE.matcher(line);
            if (state.matches()) {
                String name = state.group(1);
                if (name.indexOf('.') < 0) {
                    continue;
                }
                PermissionItem item = byName.get(name);
                if (item == null) {
                    item = newItem(name);
                    byName.put(name, item);
                }
                item.granted = "true".equals(state.group(2));
                item.requested = true;
                item.flags = state.group(3) == null ? "" : state.group(3).replace("flags=[", "").replace("]", "").trim();
                item.installTime = section == Section.INSTALL;
                item.restricted = item.flags.contains("RESTRICTED");
                continue;
            }

            Matcher op = APPOP_STATE.matcher(line);
            if (op.matches() && section != Section.REQUESTED) {
                String opName = op.group(1);
                if (opName.indexOf('_') > 0 && !opName.contains(".") && opName.length() > 3) {
                    PermissionItem item = byName.get(opName);
                    if (item == null) {
                        continue;
                    }
                    item.appOp = opName;
                    item.appOpMode = op.group(2);
                }
                continue;
            }

            facts = parseField(facts, line);
        }

        facts.permissions.addAll(byName.values());
        for (Map.Entry<String, Boolean> e : requested.entrySet()) {
            if (!byName.containsKey(e.getKey())) {
                facts.permissions.add(newItem(e.getKey()));
            }
        }
        return facts;
    }

    private static PermissionItem newItem(String name) {
        PermissionItem item = new PermissionItem();
        item.name = name;
        item.group = PermissionItem.groupOf(name);
        item.groupLabel = PermissionItem.groupLabel(item.group);
        item.protection = PermissionItem.protectionLevel(name);
        item.dangerous = item.protection.contains("dangerous");
        return item;
    }

    private static AppFacts parseField(AppFacts facts, String line) {
        if (line.startsWith("userId=")) {
            facts.uid = intOf(line.substring(7));
        } else if (line.startsWith("uid=") && facts.uid < 0) {
            facts.uid = intOf(line.substring(4));
        } else if (line.startsWith("versionCode=")) {
            String rest = line.substring(12);
            int space = rest.indexOf(' ');
            String v = space > 0 ? rest.substring(0, space) : rest;
            facts.versionCode = longOf(v);
            if (rest.contains("minSdk=")) {
                facts.minSdk = intOf(part(rest, "minSdk="));
            }
            if (rest.contains("targetSdk=")) {
                facts.targetSdk = intOf(part(rest, "targetSdk="));
            }
        } else if (line.startsWith("versionName=")) {
            facts.versionName = line.substring(12).trim();
        } else if (line.startsWith("firstInstallTime=")) {
            facts.firstInstall = timeOf(line.substring(17));
        } else if (line.startsWith("lastUpdateTime=")) {
            facts.lastUpdate = timeOf(line.substring(16));
        } else if (line.startsWith("installerPackageName=")) {
            facts.installer = line.substring(22).trim();
        } else if (line.startsWith("dataDir=")) {
            facts.dataDir = line.substring(8).trim();
        } else if (line.startsWith("codePath=")) {
            facts.sourceDir = line.substring(9).trim();
        } else if (line.startsWith("primaryCpuAbi=")) {
            facts.primaryCpuAbi = line.substring(14).trim();
        } else if (line.startsWith("seinfo=")) {
            facts.seInfo = line.substring(7).trim();
        } else if (line.startsWith("sharedUserId=")) {
            facts.sharedUserId = line.substring(13).trim();
        } else if (line.startsWith("gids=")) {
            facts.gids = line.substring(5).trim();
        } else if (line.startsWith("flags=[") || line.startsWith("pkgFlags=[")) {
            facts.pkgFlags = intOf(line.replaceAll("[^0-9]", ""));
            for (String f : line.replaceAll("[\\[\\]]", "").split("[,|]")) {
                if (f.trim().length() > 2) {
                    facts.pkgFlagsText.add(f.trim());
                }
            }
        } else if (line.startsWith("enabledComponents:") || line.startsWith("enabled=")) {
            if (line.startsWith("enabled=")) {
                facts.enabled = !"false".equals(line.substring(8).trim());
            }
        }
        return facts;
    }

    private static String part(String rest, String key) {
        int idx = rest.indexOf(key);
        if (idx < 0) {
            return "0";
        }
        int start = idx + key.length();
        int end = start;
        while (end < rest.length() && (Character.isDigit(rest.charAt(end)) || rest.charAt(end) == '-')) {
            end++;
        }
        return rest.substring(start, end);
    }

    private static int intOf(String s) {
        return (int) longOf(s);
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

    private static long timeOf(String raw) {
        if (raw == null) {
            return 0;
        }
        Matcher m = INSTALL_TIME.matcher(raw.trim());
        if (!m.find()) {
            return 0;
        }
        String v = m.group(1);
        String[] patterns = {"yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss"};
        for (String p : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(p, Locale.US);
                return sdf.parse(v).getTime();
            } catch (Throwable ignored) {
                // try the next pattern
            }
        }
        return 0;
    }

    /**
     * Parses {@code appops get <pkg>} into an operation → mode map.
     */
    public static Map<String, String> parseAppOps(String dump) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dump == null) {
            return out;
        }
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("Uid ") || line.startsWith("Package ")) {
                continue;
            }
            Matcher mm = APPOP_STATE.matcher(line);
            if (mm.matches()) {
                out.put(mm.group(1), mm.group(2));
            }
        }
        return out;
    }

    /** Splits the component dumps (activities / services / receivers) out of a `dumpsys package` blob. */
    public static void appendComponents(String dump, List<String> activities, List<String> services,
                                         List<String> receivers, List<String> providers) {
        if (dump == null) {
            return;
        }
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("Activity Resolver:") || line.contains("ActivityInfo{")) {
                String name = extractComponent(line);
                if (name != null) {
                    activities.add(name);
                }
            } else if (line.contains("ServiceInfo{")) {
                String name = extractComponent(line);
                if (name != null) {
                    services.add(name);
                }
            } else if (line.contains("ReceiverInfo{")) {
                String name = extractComponent(line);
                if (name != null) {
                    receivers.add(name);
                }
            } else if (line.contains("ProviderInfo{")) {
                String name = extractComponent(line);
                if (name != null) {
                    providers.add(name);
                }
            }
        }
    }

    private static String extractComponent(String line) {
        int idx = line.indexOf('{');
        if (idx < 0) {
            return null;
        }
        int end = line.indexOf('}', idx);
        if (end < 0) {
            return null;
        }
        String name = line.substring(idx + 1, end).trim();
        int space = name.indexOf(' ');
        if (space > 0) {
            name = name.substring(0, space);
        }
        return name.isEmpty() ? null : name;
    }

    /** Extracts the component names from a PackageManager style dump of the whole package. */
    public static List<String> listComponents(List<String> source) {
        return new ArrayList<>(source);
    }
}
