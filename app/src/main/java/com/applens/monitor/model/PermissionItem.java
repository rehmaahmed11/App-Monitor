package com.applens.monitor.model;

import java.util.ArrayList;
import java.util.List;

/** A single permission together with everything the system reveals about it. */
public class PermissionItem {

    public String name = "";
    public String group = "OTHER";
    public String groupLabel = "Other";
    public boolean dangerous = false;
    public boolean granted = false;
    public boolean requested = false;
    public boolean installTime = false;
    public boolean restricted = false;
    public String flags = "";
    public String appOp = "";
    public String appOpMode = "";
    public String protection = "";

    public static String shortName(String permission) {
        if (permission == null) {
            return "";
        }
        int idx = permission.lastIndexOf('.');
        String tail = idx >= 0 ? permission.substring(idx + 1) : permission;
        if (tail.startsWith("permission")) {
            return tail.substring("permission".length());
        }
        if (tail.startsWith("READ_") || tail.startsWith("WRITE_") || tail.startsWith("ACCESS_")) {
            return tail;
        }
        return tail;
    }

    public static String protectionLevel(String permission) {
        if (permission == null) {
            return "normal";
        }
        if (permission.contains("privileged")) {
            return "signature|privileged";
        }
        if (permission.contains("signature")) {
            return "signature";
        }
        if (permission.contains("dangerous")) {
            return "dangerous";
        }
        return "normal";
    }

    /**
     * Maps a raw android permission to one of the friendly groups used by the
     * PERMISSIONS tab.
     */
    public static String groupOf(String permission) {
        if (permission == null) {
            return "OTHER";
        }
        String p = permission;
        if (p.contains("ACCESS_FINE_LOCATION") || p.contains("ACCESS_COARSE_LOCATION")
                || p.contains("ACCESS_BACKGROUND_LOCATION") || p.endsWith("LOCATION_MODE")
                || p.contains("ACCESS_MOCK_LOCATION")) {
            return "LOCATION";
        }
        if (p.contains("CAMERA")) {
            return "CAMERA";
        }
        if (p.contains("RECORD_AUDIO") || p.contains("MICROPHONE")) {
            return "AUDIO";
        }
        if (p.contains("READ_CONTACTS") || p.contains("WRITE_CONTACTS")
                || p.contains("GET_ACCOUNTS") || p.contains("CONTACTS")) {
            return "CONTACTS";
        }
        if (p.contains("READ_PHONE_STATE") || p.contains("CALL_PHONE") || p.contains("READ_CALL_LOG")
                || p.contains("WRITE_CALL_LOG") || p.contains("ADD_VOICEMAIL") || p.contains("USE_SIP")
                || p.contains("READ_PRIME_PHONE_NUMBER") || p.contains("ANSWER_PHONE_CALLS")
                || p.contains("MANAGE_OWN_CALLS") || p.contains("PROCESS_OUTGOING_CALLS")) {
            return "PHONE";
        }
        if (p.contains("STORAGE") || p.contains("READ_EXTERNAL") || p.contains("WRITE_EXTERNAL")
                || p.contains("MEDIA") || p.contains("READ_MEDIA") || p.contains("IMAGES")
                || p.contains("VIDEO") || p.contains("AUDIO_MEDIA") || p.contains("MANAGE_EXTERNAL")) {
            return "STORAGE";
        }
        if (p.contains("SEND_SMS") || p.contains("RECEIVE_SMS") || p.contains("READ_SMS")
                || p.contains("WRITE_SMS") || p.contains("RECEIVE_MMS") || p.contains("BROADCAST_SMS")) {
            return "SMS";
        }
        if (p.contains("CALENDAR")) {
            return "CALENDAR";
        }
        if (p.contains("NOTIFICATION") || p.contains("BIND_NOTIFICATION")) {
            return "NOTIFICATIONS";
        }
        if (p.contains("BLUETOOTH") || p.contains("NFC") || p.contains("UWB") || p.contains("NEARBY_WIFI")) {
            return "NEARBY";
        }
        if (p.contains("SENSOR") || p.contains("BODY_SENSORS") || p.contains("ACTIVITY_RECOGNITION")) {
            return "SENSORS";
        }
        if (p.contains("PACKAGE_USAGE_STATS") || p.contains("QUERY_ALL_PACKAGES")
                || p.contains("GET_TASKS") || p.contains("GET_ACCOUNTS")) {
            return "USAGE";
        }
        if (p.contains("BILLING") || p.contains("IN_APP") || p.contains("PURCHASE")) {
            return "BILLING";
        }
        return "OTHER";
    }

    public static String groupLabel(String group) {
        if (group == null) {
            return "Other";
        }
        switch (group) {
            case "LOCATION":
                return "Location";
            case "CAMERA":
                return "Camera";
            case "AUDIO":
                return "Microphone";
            case "CONTACTS":
                return "Contacts";
            case "PHONE":
                return "Phone";
            case "STORAGE":
                return "Storage / Media";
            case "SMS":
                return "SMS";
            case "CALENDAR":
                return "Calendar";
            case "NOTIFICATIONS":
                return "Notifications";
            case "NEARBY":
                return "Nearby devices";
            case "SENSORS":
                return "Sensors";
            case "USAGE":
                return "Usage access";
            case "BILLING":
                return "In-app billing";
            default:
                return "Other";
        }
    }

    public static final List<String> ORDER = new ArrayList<>();

    static {
        ORDER.add("LOCATION");
        ORDER.add("CAMERA");
        ORDER.add("AUDIO");
        ORDER.add("CONTACTS");
        ORDER.add("PHONE");
        ORDER.add("STORAGE");
        ORDER.add("SMS");
        ORDER.add("CALENDAR");
        ORDER.add("NOTIFICATIONS");
        ORDER.add("NEARBY");
        ORDER.add("SENSORS");
        ORDER.add("USAGE");
        ORDER.add("BILLING");
        ORDER.add("OTHER");
    }
}
