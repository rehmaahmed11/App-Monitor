package com.applens.monitor.model;

import com.applens.monitor.R;

/** Every event AppLens can record, grouped for filtering. */
public enum EventCategory {

    /** Pseudo category used by the filter chips; never produced by a monitor. */
    ALL("All", R.color.accent, R.drawable.ic_pulse),
    PROCESS("Process", R.color.info, R.drawable.ic_chip),
    ACTIVITY("Activity", R.color.accent, R.drawable.ic_apps),
    LOCATION("Location", R.color.ok, R.drawable.ic_gps),
    CAMERA("Camera", R.color.accent2, R.drawable.ic_camera),
    MICROPHONE("Microphone", R.color.accent2, R.drawable.ic_mic),
    CONTACTS("Contacts", R.color.warn, R.drawable.ic_contacts),
    PHONE("Phone", R.color.warn, R.drawable.ic_phone),
    NETWORK("Network", R.color.accent, R.drawable.ic_network),
    DNS("DNS", R.color.accent, R.drawable.ic_dns),
    STORAGE("Storage", R.color.info, R.drawable.ic_folder),
    DATABASE("Database", R.color.info, R.drawable.ic_database),
    MEDIA("Media", R.color.accent2, R.drawable.ic_image),
    NOTIFICATION("Notification", R.color.muted, R.drawable.ic_bell),
    SENSORS("Sensors", R.color.muted, R.drawable.ic_pulse),
    SYSTEM("System", R.color.muted, R.drawable.ic_chip),
    SECURITY("Security", R.color.danger, R.drawable.ic_shield),
    PRIVACY("Privacy", R.color.danger, R.drawable.ic_lock),
    PERFORMANCE("Performance", R.color.muted, R.drawable.ic_memory),
    CLIPBOARD("Clipboard", R.color.warn, R.drawable.ic_file);

    public final String label;
    public final int colorRes;
    public final int iconRes;

    EventCategory(String label, int colorRes, int iconRes) {
        this.label = label;
        this.colorRes = colorRes;
        this.iconRes = iconRes;
    }
}
