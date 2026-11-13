package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded phase occurrences, not additive durations. Protocol/cache describe the final observed response. */
public final class HTTPMetrics {
    public enum Phase { DNS, CONNECT, TLS, REQUEST, REQUEST_HEADERS, REQUEST_BODY, WAITING, RESPONSE, RESPONSE_HEADERS, RESPONSE_BODY }
    public enum Cache { UNKNOWN, NETWORK, LOCAL, VALIDATED, SERVER_PUSH }
    public static final class Timing {
        public final Phase phase;
        public final long startMillis;
        public final Long endMillis;
        /** Offsets share the collector's call origin. A null end is incomplete, never zero duration. */
        public Timing(Phase phase, long startMillis, Long endMillis) {
            if (phase == null || startMillis < 0 || endMillis != null && endMillis < startMillis) throw EvidenceValidation.invalid();
            this.phase = phase; this.startMillis = startMillis; this.endMillis = endMillis;
        }
    }
    public final List<Timing> phases;
    public final String protocolName;
    public final Cache cache;
    public final Boolean connectionReused;
    public final boolean limited;
    public HTTPMetrics(List<Timing> phases, String protocolName, Cache cache, Boolean connectionReused, boolean limited) {
        if (phases == null || phases.size() > 32 || cache == null || protocolName != null && !validProtocol(protocolName)) throw EvidenceValidation.invalid();
        ArrayList<Timing> copy = new ArrayList<>(phases.size());
        for (Timing timing : phases) { if (timing == null) throw EvidenceValidation.invalid(); copy.add(timing); }
        this.phases = Collections.unmodifiableList(copy); this.protocolName = protocolName; this.cache = cache;
        this.connectionReused = connectionReused; this.limited = limited;
    }
    private static boolean validProtocol(String value) {
        if (value.isEmpty() || value.length() > 32) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 33 || value.charAt(i) > 126) return false;
        return true;
    }
    public int textByteCount() { return protocolName == null ? 0 : protocolName.length(); }
}
