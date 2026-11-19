package com.sandrox.osservio.ui;

import java.util.IdentityHashMap;
import java.util.Map;

/** Admission accounts for recording margins, before allocating GPU resources. Not a byte cap. */
final class InspectorGlassBudget {
    static final int MARGIN = 32;
    static final long MAX_SURFACE_PIXELS = 786_432;
    static final long MAX_SELECTION_PIXELS = 3_145_728;
    // One bottom bar plus an inline segmented control and small page actions.
    // Individual selection and recording limits remain independent of this combined cap.
    static final long MAX_SESSION_PIXELS = 4_718_592;
    private final Map<Object, Long> reservations = new IdentityHashMap<>();
    private long used;
    long pixels() { return used; }
    boolean reserve(Object surface, int width, int height) {
        long w = (long) width + 2 * MARGIN, h = (long) height + 2 * MARGIN;
        if (width <= 0 || height <= 0 || w > 4096 || h > 4096) return false;
        return reserve(surface, w * h, w * h, w, h);
    }
    /** A layered material reserves every logical node, including shadow and recording margins. */
    boolean reserve(Object surface, long largest, long required, long width, long height) {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || largest <= 0 || required < largest) return false;
        long previous = reservations.containsKey(surface) ? reservations.get(surface) : 0;
        if (largest > MAX_SURFACE_PIXELS || required > MAX_SESSION_PIXELS - pixels() + previous) return false;
        if (required != previous) { reservations.put(surface, required); used += required - previous; }
        return true;
    }
    void release(Object surface) { Long previous = reservations.remove(surface); if (previous != null) used -= previous; }
}
