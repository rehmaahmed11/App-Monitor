package com.applens.monitor.repo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.model.DeviceProfile;

import java.util.List;
import java.util.Locale;

/**
 * Builds the ACCESS tab: the full device / network / location / identifier
 * fingerprint that the platform (plus root) is willing to expose.
 */
public final class DeviceRepository {

    private static final String S = "Device";
    private static final String N = "Network";
    private static final String I = "Identifiers";
    private static final String L = "Location";
    private static final String H = "Hardware";
    private static final String R_ = "Root & security";

    private DeviceRepository() {
    }

    @SuppressLint("HardwareIds")
    public static DeviceProfile build(Context ctx) {
        DeviceProfile p = new DeviceProfile();
        buildIdentity(p);
        buildRoot(p);
        buildNetwork(ctx, p);
        buildIdentifiers(ctx, p);
        buildLocation(ctx, p);
        buildHardware(ctx, p);
        return p;
    }

    private static void buildIdentity(DeviceProfile p) {
        p.add(S, R.drawable.ic_info, "Manufacturer", Fmt.nz(Build.MANUFACTURER, "n/a"));
        p.add(S, R.drawable.ic_info, "Brand", Fmt.nz(Build.BRAND, "n/a"));
        p.add(S, R.drawable.ic_info, "Model", Fmt.nz(Build.MODEL, "n/a"));
        p.add(S, R.drawable.ic_info, "Device", Fmt.nz(Build.DEVICE, "n/a"));
        p.add(S, R.drawable.ic_info, "Product", Fmt.nz(Build.PRODUCT, "n/a"));
        p.add(S, R.drawable.ic_info, "Hardware", Fmt.nz(Build.HARDWARE, "n/a"));
        p.add(S, R.drawable.ic_info, "Board", Fmt.nz(Build.BOARD, "n/a"));
        p.add(S, R.drawable.ic_info, "Bootloader", Fmt.nz(Build.BOOTLOADER, "n/a"));
        p.add(S, R.drawable.ic_fingerprint, "Build fingerprint", Build.FINGERPRINT);
        p.add(S, R.drawable.ic_chip, "Android", Build.VERSION.RELEASE + " (API "
                + Build.VERSION.SDK_INT + ")");
        p.add(S, R.drawable.ic_shield, "Security patch", Fmt.nz(Build.VERSION.SECURITY_PATCH, "n/a"));
        p.add(S, R.drawable.ic_history, "Build date", Fmt.dateTime(Build.TIME));
    }

    private static void buildRoot(DeviceProfile p) {
        RootShell root = RootShell.get();
        p.add(R_, R.drawable.ic_lock, "Superuser", root.isRootGranted() ? "Granted" : "Not granted",
                root.suPath(), root.isRootGranted() ? R.color.ok : R.color.danger);
        String manager = detectManager();
        p.add(R_, R.drawable.ic_lock, "Root manager", manager);
        String selinux = RootShell.get().exec("getenforce 2>/dev/null");
        p.add(R_, R.drawable.ic_shield, "SELinux", Fmt.nz(selinux, "unknown"));
        String kernel = RootShell.get().exec("uname -a 2>/dev/null");
        p.add(R_, R.drawable.ic_chip, "Kernel", Fmt.nz(kernel, "n/a"));
        String suVer = RootShell.get().exec("magisk -v 2>/dev/null || ksud -V 2>/dev/null");
        p.add(R_, R.drawable.ic_chip, "su version", Fmt.nz(suVer, "n/a"));
    }

    private static String detectManager() {
        RootShell root = RootShell.get();
        String[][] probes = {
                {"/data/adb/magisk", "Magisk"},
                {"/data/adb/ksu", "KernelSU"},
                {"/data/adb/ap", "APatch"},
                {"/data/adb/modules", "modules loaded"},
                {"/system/app/Superuser.apk", "SuperSU"},
        };
        for (String[] probe : probes) {
            if (root.exists(probe[0])) {
                return probe[1];
            }
        }
        return root.isRootGranted() ? "Unknown (su present)" : "None detected";
    }

    private static void buildNetwork(Context ctx, DeviceProfile p) {
        RootShell root = RootShell.get();
        String type = "Unknown";
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                    if (caps != null) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            type = "Wi-Fi";
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            type = "Cellular";
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            type = "Ethernet";
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            type = "VPN";
                        }
                        LinkProperties lp = cm.getLinkProperties(active);
                        if (lp != null && lp.getLinkAddresses().size() > 0) {
                            for (Object o : lp.getLinkAddresses()) {
                                if (o instanceof android.net.LinkAddress) {
                                    android.net.LinkAddress la = (android.net.LinkAddress) o;
                                    p.add(N, R.drawable.ic_network, "Primary address",
                                            la.getAddress().getHostAddress() + "/" + la.getPrefixLength());
                                    break;
                                }
                            }
                        }
                        if (lp != null && !lp.getDnsServers().isEmpty()) {
                            StringBuilder dns = new StringBuilder();
                            for (Object o : lp.getDnsServers()) {
                                if (dns.length() > 0) {
                                    dns.append(", ");
                                }
                                dns.append(o.toString());
                            }
                            p.add(N, R.drawable.ic_dns, "DNS servers", dns.toString());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // keep the default
        }
        p.add(N, R.drawable.ic_network, "Network type", type);

        String addrs = root.exec("ip -o addr show 2>/dev/null | grep -v 'inet6 fe80'", 12000);
        if (addrs != null && !addrs.isEmpty()) {
            p.add(N, R.drawable.ic_network, "Interface addresses", addrs.replaceAll("\\s+", " ").trim());
        }
        String v4 = root.exec("ip -4 -o addr show 2>/dev/null | awk '{print $2\" \"$4}'", 12000);
        if (v4 != null && !v4.isEmpty()) {
            p.add(N, R.drawable.ic_network, "IPv4 interfaces", v4.replaceAll("\\s+", " ").trim());
        }
        String route = root.exec("ip route show default 2>/dev/null", 8000);
        p.add(N, R.drawable.ic_network, "Default route", Fmt.nz(route, "n/a"));
        String dnsProp = root.exec("getprop | grep -E 'net\\.dns[0-9]|dhcp\\.dns' 2>/dev/null", 8000);
        if (dnsProp != null && !dnsProp.isEmpty()) {
            p.add(N, R.drawable.ic_dns, "Resolver config", dnsProp.replaceAll("\\s+", " ").trim());
        }

        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    p.add(N, R.drawable.ic_network, "Wi-Fi SSID", Fmt.nz(unescape(info.getSSID()), "not connected"));
                    p.add(N, R.drawable.ic_network, "BSSID", Fmt.nz(info.getBSSID(), "n/a"));
                    p.add(N, R.drawable.ic_network, "Wi-Fi frequency",
                            info.getFrequency() > 0 ? info.getFrequency() + " MHz" : "n/a");
                    p.add(N, R.drawable.ic_network, "Wi-Fi RSSI", info.getRssi() + " dBm");
                    p.add(N, R.drawable.ic_network, "Wi-Fi link speed", info.getLinkSpeed() + " Mbps");
                    p.add(N, R.drawable.ic_network, "Wi-Fi MAC", Fmt.nz(info.getMacAddress(), "n/a"));
                }
            }
        } catch (Throwable ignored) {
            // location services disabled
        }
    }

    private static String unescape(String s) {
        if (s == null) {
            return null;
        }
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    @SuppressLint("HardwareIds")
    private static void buildIdentifiers(Context ctx, DeviceProfile p) {
        try {
            String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            p.add(I, R.drawable.ic_key, "Android ID", Fmt.nz(androidId, "n/a"));
        } catch (Throwable ignored) {
            // noop
        }
        String serial = null;
        try {
            serial = android.os.Build.SERIAL;
        } catch (Throwable ignored) {
            // noop
        }
        if (serial == null || serial.isEmpty() || "unknown".equalsIgnoreCase(serial)) {
            serial = RootShell.get().exec("getprop ro.serialno 2>/dev/null");
        }
        p.add(I, R.drawable.ic_key, "Serial", Fmt.nz(serial, "n/a"));
        String imei = null;
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null && RootShell.get().isRootGranted()) {
                imei = RootShell.get().exec("dumpsys iphonesubinfo 2>/dev/null | sed -n "
                        + "'s/.*DeviceId = \\(.*\\)/\\1/p' | head -1");
            }
        } catch (Throwable ignored) {
            // no telephony
        }
        p.add(I, R.drawable.ic_phone, "IMEI (root)", Fmt.nz(imei, "n/a"));
        String imei2 = RootShell.get().exec("getprop ril.MSISDN 2>/dev/null || getprop gsm.sim.operator.numeric 2>/dev/null");
        p.add(I, R.drawable.ic_phone, "SIM / MCC-MNC", Fmt.nz(imei2, "n/a"));

        String macs = RootShell.get().exec("cat /sys/class/net/*/address 2>/dev/null", 8000);
        if (macs != null && !macs.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String m : macs.split("\n")) {
                if (m.trim().isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(m.trim());
            }
            p.add(I, R.drawable.ic_network, "Hardware MACs", sb.toString());
        }
        String fp = RootShell.get().exec("settings get secure android_id 2>/dev/null");
        p.add(I, R.drawable.ic_fingerprint, "SSID (secure)", Fmt.nz(fp, "n/a"));
    }

    @SuppressLint("MissingPermission")
    private static void buildLocation(Context ctx, DeviceProfile p) {
        String dump = RootShell.get().exec("dumpsys location 2>/dev/null | head -60", 15000);
        if (dump != null && !dump.isEmpty()) {
            String last = null;
            for (String line : dump.split("\n")) {
                if (line.contains("last location=") || line.contains("last location ")) {
                    last = line.trim();
                    break;
                }
            }
            if (last != null) {
                int a = last.indexOf("last location=");
                String value = a > 0 ? last.substring(a + 14).trim() : last;
                p.add(L, R.drawable.ic_gps, "Last system location", value);
            }
            p.add(L, R.drawable.ic_gps, "Location providers", Fmt.limit(dump.replaceAll("\\s+", " "), 300));
        }
        try {
            LocationCompat.addBestLastKnown(ctx, p);
        } catch (Throwable ignored) {
            // permissions may be denied
        }
    }

    private static void buildHardware(Context ctx, DeviceProfile p) {
        String abis = joinAbis(Build.SUPPORTED_ABIS);
        p.add(H, R.drawable.ic_chip, "Supported ABIs", abis);
        p.add(H, R.drawable.ic_chip, "Primary ABI", Fmt.nz(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : null, "n/a"));
        try {
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm != null) {
                List<Sensor> sensors = sm.getSensorList(Sensor.TYPE_ALL);
                StringBuilder sb = new StringBuilder();
                int steppers = 0;
                for (Sensor s : sensors) {
                    if (s.getType() == Sensor.TYPE_STEP_COUNTER || s.getType() == Sensor.TYPE_STEP_DETECTOR) {
                        steppers++;
                    }
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(s.getName());
                }
                p.add(H, R.drawable.ic_pulse, "Sensors (" + sensors.size() + ")", Fmt.limit(sb.toString(), 260));
                p.add(H, R.drawable.ic_pulse, "Hardware step counter", steppers > 0 ? "Present" : "Absent");
            }
        } catch (Throwable ignored) {
            // no sensors
        }
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                String extra = "";
                android.content.Intent battery = ctx.registerReceiver(null,
                        new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                if (battery != null) {
                    int temp = battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0);
                    int plug = battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1);
                    extra = " · " + (temp / 10f) + "°C · "
                            + (plug == 0 ? "discharging" : "charging");
                }
                p.add(H, R.drawable.ic_chip, "Battery", level + "%" + extra);
            }
        } catch (Throwable ignored) {
            // no battery service
        }
        p.add(H, R.drawable.ic_chip, "Screen", Build.MANUFACTURER + " " + Build.DEVICE
                + " · " + ctx.getResources().getConfiguration().screenWidthDp + "x"
                + ctx.getResources().getConfiguration().screenHeightDp + "dp");
        p.add(H, R.drawable.ic_chip, "Locale", Locale.getDefault().toString());
        boolean multiUser = false;
        try {
            android.os.UserManager um =
                    (android.os.UserManager) ctx.getSystemService(Context.USER_SERVICE);
            multiUser = um != null && um.getUserProfiles().size() > 1;
        } catch (Throwable ignored) {
            // noop
        }
        p.add(H, R.drawable.ic_info, "User profiles", multiUser ? "Multiple (work profile present)" : "Single");
    }

    private static String joinAbis(String[] values) {
        if (values == null || values.length == 0) {
            return "n/a";
        }
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(v);
        }
        return sb.toString();
    }

    /** Small indirection so the location code stays optional at runtime. */
    static final class LocationCompat {
        private LocationCompat() {
        }

        static void addBestLastKnown(Context ctx, DeviceProfile p) {
            try {
                android.location.LocationManager lm =
                        (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                if (lm == null) {
                    return;
                }
                List<String> providers = lm.getProviders(true);
                android.location.Location best = null;
                for (String provider : providers) {
                    android.location.Location loc = lm.getLastKnownLocation(provider);
                    if (loc != null && (best == null || loc.getTime() > best.getTime())) {
                        best = loc;
                    }
                }
                if (best != null) {
                    p.add(L, R.drawable.ic_gps, "Last known (AppLens)",
                            String.format(Locale.US, "%.6f, %.6f ±%.0f m · %s · %s",
                                    best.getLatitude(), best.getLongitude(), best.getAccuracy(),
                                    best.getProvider(), Fmt.ago(best.getTime())));
                } else {
                    p.add(L, R.drawable.ic_gps, "Last known (AppLens)", "unavailable");
                }
            } catch (SecurityException e) {
                p.add(L, R.drawable.ic_gps, "Last known (AppLens)", "permission required");
            } catch (Throwable ignored) {
                // noop
            }
        }
    }

    /** Checks whether AppLens is allowed to see other packages. */
    public static boolean canQueryAllPackages(Context ctx) {
        try {
            return ctx.getPackageManager().queryIntentActivities(
                    new android.content.Intent(android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0).size() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission(Context ctx, String permission) {
        try {
            return ctx.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }
}
