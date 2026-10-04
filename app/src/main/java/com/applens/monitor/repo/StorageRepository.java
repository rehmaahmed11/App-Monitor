package com.applens.monitor.repo;

import com.applens.monitor.core.RootShell;
import com.applens.monitor.model.StorageItem;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Root powered storage accounting for an application's private data. */
public final class StorageRepository {

    public static final class Breakdown {
        public final List<StorageItem> items = new ArrayList<>();
        public long total;
        public long dataDirBytes = -1;
        public String dataDir = "";
        public int fileCount = -1;
        public int databaseCount;
        public int cacheSizeBytes;
        public long largestFileBytes;
        public String largestFile = "";
    }

    private StorageRepository() {
    }

    private static final String[] DATA_SUBDIRS = {
            "databases", "files", "cache", "shared_prefs", "lib", "no_backup", "code_cache",
            "app_webview", "databases", "misc", "device_protected", "credential_protected",
    };

    public static Breakdown breakdown(String pkg, String dataDir) {
        Breakdown out = new Breakdown();
        RootShell root = RootShell.get();
        out.dataDir = dataDir;
        if (dataDir == null || dataDir.isEmpty()) {
            return out;
        }
        if (!root.ensureRoot()) {
            return out;
        }
        StringBuilder cmd = new StringBuilder("for p in");
        List<String> paths = new ArrayList<>();
        paths.add(dataDir);
        for (String sub : DATA_SUBDIRS) {
            paths.add(dataDir + "/" + sub);
        }
        for (String p : paths) {
            cmd.append(' ').append(RootShell.shQuote(p));
        }
        cmd.append("; do if [ -d \"$p\" ]; then printf '%s\\t' \"$p\"; du -sk \"$p\" 2>/dev/null | cut -f1; else printf '%s\\t-1\\n' \"$p\"; fi; done;");
        cmd.append("printf 'FILES\\t'; find ").append(RootShell.shQuote(dataDir))
                .append(" -type f 2>/dev/null | wc -l;");
        cmd.append("printf 'DBS\\t'; find ").append(RootShell.shQuote(dataDir))
                .append(" -path '*/databases/*' -type f 2>/dev/null | wc -l;");
        cmd.append("printf 'BIG\\t'; find ").append(RootShell.shQuote(dataDir))
                .append(" -type f -printf '%s %p\\n' 2>/dev/null | sort -rn | head -1;");
        cmd.append("printf 'EXT\\t'; du -sk ").append(RootShell.shQuote("/sdcard/Android/data/" + pkg))
                .append(" 2>/dev/null | cut -f1;");

        Map<String, Long> sizes = new LinkedHashMap<>();
        String bigLine = "";
        String extLine = "";
        for (String line : root.execLines(cmd.toString(), 60000)) {
            String[] f = line.split("\t", 2);
            if (f.length < 2) {
                continue;
            }
            String key = f[0].trim();
            String value = f[1].trim();
            if (key.equals("FILES")) {
                out.fileCount = parseInt(value);
            } else if (key.equals("DBS")) {
                out.databaseCount = parseInt(value);
            } else if (key.equals("BIG")) {
                bigLine = value;
            } else if (key.equals("EXT")) {
                extLine = value;
            } else {
                sizes.put(key, parseLong(value));
            }
        }

        for (Map.Entry<String, Long> e : sizes.entrySet()) {
            String path = e.getKey();
            long kb = e.getValue() == null ? -1 : e.getValue();
            if (kb < 0) {
                continue;
            }
            if (path.equals(dataDir)) {
                out.dataDirBytes = kb * 1024;
                continue;
            }
            String sub = path.substring(dataDir.length() + 1);
            StorageItem item = new StorageItem(prettify(sub), path, kb * 1024);
            out.items.add(item);
        }
        out.items.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        for (int i = 0; i < out.items.size(); i++) {
            out.items.get(i).colorIndex = i;
        }
        for (StorageItem item : out.items) {
            if (item.name.toLowerCase().contains("cache")) {
                out.cacheSizeBytes = (int) item.bytes;
            }
        }
        if (bigLine != null && !bigLine.isEmpty()) {
            String[] f = bigLine.split("\\s+", 2);
            if (f.length == 2) {
                out.largestFileBytes = parseLong(f[0]);
                out.largestFile = f[1];
            }
        }
        long total = out.dataDirBytes < 0 ? 0 : out.dataDirBytes;
        for (StorageItem item : out.items) {
            total += item.bytes;
        }
        if (bigLine != null) {
            // biggest file is already part of the total
        }
        long ext = parseLong(extLine);
        if (ext > 0) {
            out.items.add(new StorageItem("External (Android/data)", "/sdcard/Android/data/" + pkg, ext * 1024));
            total += ext * 1024;
        }
        out.total = total;
        return out;
    }

    private static String prettify(String sub) {
        String s = sub.replace('_', ' ').trim();
        if (s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** APK size, including split APKs. */
    public static long apkBytes(String sourceDir) {
        if (sourceDir == null || sourceDir.isEmpty()) {
            return 0;
        }
        long total = 0;
        try {
            File f = new File(sourceDir);
            if (f.isFile()) {
                return f.length();
            }
        } catch (Throwable ignored) {
            // fall through to root
        }
        String out = RootShell.get().exec("stat -c %s " + RootShell.shQuote(sourceDir) + " 2>/dev/null");
        return parseLong(out);
    }

    /** Number of native libraries shipped inside the APK. */
    public static int nativeLibraryCount(String pkg) {
        String out = RootShell.get().exec("unzip -l $(pm path " + RootShell.shQuote(pkg)
                + " 2>/dev/null | head -1 | cut -d: -f2) 2>/dev/null | grep -c 'lib/.*\\.so'");
        return Math.max(0, parseInt(out));
    }
}
