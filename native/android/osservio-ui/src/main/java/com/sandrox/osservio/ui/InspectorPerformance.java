package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Trace;

/** Fixed, content-free trace sections. Begin/end must remain on the same thread. */
final class InspectorPerformance {
    enum Point {
        LOG_REFRESH("Osservio.Log.refresh"), NETWORK_REFRESH("Osservio.Network.refresh"),
        LOG_APPLY("Osservio.Log.apply"), NETWORK_APPLY("Osservio.Network.apply"),
        RECORD_SEARCH("Osservio.Record.search"), JSON_PARSE("Osservio.JSON.parse"), FORM_PARSE("Osservio.Form.parse"),
        OPEN("Osservio.Inspector.open"), COLLAPSE("Osservio.Inspector.collapse"), TAB("Osservio.Inspector.tab");
        final String label;
        Point(String label) { this.label = label; }
    }
    private InspectorPerformance() { }
    // The synchronous caller closes this section in finally; no callback owns it.
    @SuppressLint("UnclosedTrace")
    static boolean begin(Point point) {
        if (Build.VERSION.SDK_INT >= 29 && !Trace.isEnabled()) return false;
        // API 23–28 have no public enabled query; beginSection itself is inactive without tracing.
        Trace.beginSection(point.label); return true;
    }
    static void end(boolean active) { if (active) Trace.endSection(); }
    static void mark(Point point) { boolean active = begin(point); end(active); }
}
