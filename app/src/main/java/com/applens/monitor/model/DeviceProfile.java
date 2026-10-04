package com.applens.monitor.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Flexible, sectioned key/value profile used by the ACCESS tab: device identity,
 * network configuration, location, sensors, root state and app identifiers.
 */
public class DeviceProfile {

    public static final class Entry {
        public final String label;
        public final String value;
        public final String note;
        public final int colorRes;

        public Entry(String label, String value, String note, int colorRes) {
            this.label = label;
            this.value = value;
            this.note = note == null ? "" : note;
            this.colorRes = colorRes;
        }

        public boolean hasValue() {
            return value != null && !value.isEmpty() && !"n/a".equalsIgnoreCase(value);
        }
    }

    public static final class Section {
        public final String title;
        public final int iconRes;
        public final List<Entry> entries = new ArrayList<>();

        public Section(String title, int iconRes) {
            this.title = title;
            this.iconRes = iconRes;
        }
    }

    public final List<Section> sections = new ArrayList();

    public Section section(String title, int iconRes) {
        for (Section s : sections) {
            if (s.title.equals(title)) {
                return s;
            }
        }
        Section s = new Section(title, iconRes);
        sections.add(s);
        return s;
    }

    public void add(String section, int iconRes, String label, String value) {
        add(section, iconRes, label, value, "", 0);
    }

    public void add(String section, int iconRes, String label, String value, String note, int colorRes) {
        Section s = section(section, iconRes);
        for (Entry e : s.entries) {
            if (e.label.equals(label)) {
                return;
            }
        }
        s.entries.add(new Entry(label, value, note, colorRes));
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }
}
