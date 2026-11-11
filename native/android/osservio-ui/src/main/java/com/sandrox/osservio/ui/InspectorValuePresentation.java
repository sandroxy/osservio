package com.sandrox.osservio.ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class InspectorValuePresentation {
    private InspectorValuePresentation() { }
    private static final ThreadLocal<SimpleDateFormat> SHORT_TIME = formatter("HH:mm:ss.SSS");
    private static final ThreadLocal<SimpleDateFormat> FULL_TIME = formatter("yyyy-MM-dd HH:mm:ss.SSS");
    private static ThreadLocal<SimpleDateFormat> formatter(String pattern) {
        return new ThreadLocal<SimpleDateFormat>() { @Override protected SimpleDateFormat initialValue() { return new SimpleDateFormat(pattern, Locale.ROOT); } };
    }
    static String eventTime(long millis, boolean full) {
        SimpleDateFormat formatter = full ? FULL_TIME.get() : SHORT_TIME.get();
        formatter.setTimeZone(TimeZone.getDefault()); return formatter.format(new Date(millis));
    }
    static String bytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"}; double number = bytes / 1024.0; int index = 0;
        while (number >= 1024 && index < units.length - 1) { number /= 1024; index++; }
        return String.format(Locale.ROOT, "%.1f %s", number, units[index]);
    }
}
