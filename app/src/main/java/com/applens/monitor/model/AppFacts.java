package com.applens.monitor.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything {@code dumpsys package} reveals about one application. */
public class AppFacts {

    public String pkg = "";
    public int uid = -1;
    public int versionCode;
    public String versionName = "";
    public int targetSdk;
    public int minSdk;
    public long firstInstall;
    public long lastUpdate;
    public String installer = "";
    public String dataDir = "";
    public String sourceDir = "";
    public String primaryCpuAbi = "";
    public String seInfo = "none";
    public String sharedUserId = "";
    public String gids = "";
    public boolean enabled = true;
    public boolean system = false;
    public int pkgFlags = 0;
    public final List<String> pkgFlagsText = new ArrayList<>();

    public final List<PermissionItem> permissions = new ArrayList<>();
    public final Map<String, String> appOps = new LinkedHashMap<>();
    public final List<String> activities = new ArrayList<>();
    public final List<String> services = new ArrayList<>();
    public final List<String> receivers = new ArrayList<>();
    public final List<String> providers = new ArrayList<>();

    public String dumpsysAvailable = "";

    public int grantedCount() {
        int n = 0;
        for (PermissionItem p : permissions) {
            if (p.granted) {
                n++;
            }
        }
        return n;
    }

    public int dangerousCount() {
        int n = 0;
        for (PermissionItem p : permissions) {
            if (p.dangerous) {
                n++;
            }
        }
        return n;
    }

    public int dangerousGrantedCount() {
        int n = 0;
        for (PermissionItem p : permissions) {
            if (p.dangerous && p.granted) {
                n++;
            }
        }
        return n;
    }

    public List<PermissionItem> byGroup(String group) {
        List<PermissionItem> out = new ArrayList<>();
        for (PermissionItem p : permissions) {
            if (p.group.equals(group)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Groups that actually contain at least one permission, in display order. */
    public List<String> presentGroups() {
        List<String> out = new ArrayList<>();
        for (String g : PermissionItem.ORDER) {
            for (PermissionItem p : permissions) {
                if (p.group.equals(g)) {
                    out.add(g);
                    break;
                }
            }
        }
        return out;
    }
}
