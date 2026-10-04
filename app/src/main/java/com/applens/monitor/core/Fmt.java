package com.applens.monitor.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Formatting helpers shared by the whole app. */
public final class Fmt {

    private static final ThreadLocal<SimpleDateFormat> CLOCK = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        }
    };

    private static final ThreadLocal<SimpleDateFormat> STAMP = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        }
    };

    private static final ThreadLocal<SimpleDateFormat> DATE_TIME = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.US);
        }
    };

    private static final ThreadLocal<SimpleDateFormat> DATE = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("dd MMM yyyy", Locale.US);
        }
    };

    private Fmt() {
    }

    public static String clock(long millis) {
        return CLOCK.get().format(new Date(millis));
    }

    public static String hms(long millis) {
        return CLOCK.get().format(new Date(millis)).substring(0, 8);
    }

    public static String stamp(long millis) {
        return STAMP.get().format(new Date(millis));
    }

    public static String dateTime(long millis) {
        if (millis <= 0) {
            return "n/a";
        }
        return DATE_TIME.get().format(new Date(millis));
    }

    public static String date(long millis) {
        if (millis <= 0) {
            return "n/a";
        }
        return DATE.get().format(new Date(millis));
    }

    /** 1024 based byte formatting, e.g. {@code 184 KB}. */
    public static String bytes(long value) {
        if (value < 0) {
            return "n/a";
        }
        if (value < 1024) {
            return value + " B";
        }
        double kb = value / 1024.0;
        if (kb < 1024) {
            return (kb < 10 ? trim1(kb) : Math.round(kb)) + " KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return (mb < 10 ? trim1(mb) : Math.round(mb)) + " MB";
        }
        double gb = mb / 1024.0;
        return (gb < 10 ? trim1(gb) : Math.round(gb)) + " GB";
    }

    public static String bytesShort(long value) {
        if (value < 0) {
            return "-";
        }
        if (value < 1024) {
            return value + "B";
        }
        double kb = value / 1024.0;
        if (kb < 1024) {
            return (kb < 10 ? trim1(kb) : Math.round(kb)) + "K";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return (mb < 10 ? trim1(mb) : Math.round(mb)) + "M";
        }
        return (mb / 1024.0 < 10 ? trim1(mb / 1024.0) : Math.round(mb / 1024.0)) + "G";
    }

    public static String rate(long bytesPerSecond) {
        return bytesShort(bytesPerSecond) + "/s";
    }

    private static String trim1(double v) {
        String s = String.format(Locale.US, "%.1f", v);
        if (s.endsWith(".0")) {
            return s.substring(0, s.length() - 2);
        }
        return s;
    }

    /** Compact duration, e.g. {@code 1h 04m 12s} or {@code 3.2s}. */
    public static String duration(long millis) {
        if (millis < 0) {
            return "n/a";
        }
        if (millis < 1000) {
            return millis + "ms";
        }
        long s = TimeUnit.MILLISECONDS.toSeconds(millis);
        if (s < 60) {
            return trim1(millis / 1000.0) + "s";
        }
        long m = s / 60;
        if (m < 60) {
            return m + "m " + pad(s % 60) + "s";
        }
        long h = m / 60;
        if (h < 24) {
            return h + "h " + pad(m % 60) + "m";
        }
        long d = h / 24;
        return d + "d " + pad(h % 24) + "h";
    }

    private static String pad(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    public static String ago(long millis) {
        if (millis <= 0) {
            return "never";
        }
        long diff = System.currentTimeMillis() - millis;
        if (diff < 5000) {
            return "just now";
        }
        if (diff < 60000) {
            return (diff / 1000) + "s ago";
        }
        if (diff < 3600000) {
            return (diff / 60000) + "m ago";
        }
        if (diff < 86400000) {
            return (diff / 3600000) + "h ago";
        }
        if (diff < 30L * 86400000) {
            return (diff / 86400000) + "d ago";
        }
        return date(millis);
    }

    public static String nz(String s, String fallback) {
        if (s == null) {
            return fallback;
        }
        String t = s.trim();
        return t.isEmpty() ? fallback : t;
    }

    public static String join(String sep, Iterable<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(v);
        }
        return sb.toString();
    }

    public static String limit(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }
}
