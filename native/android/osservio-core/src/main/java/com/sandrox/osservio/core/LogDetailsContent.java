package com.sandrox.osservio.core;

import java.nio.charset.StandardCharsets;

/** Immutable, policy-processed detail text, stored separately from indexed metadata. */
public final class LogDetailsContent {
    public final String id;
    public final String text;
    public final int byteCount;
    private LogDetailsContent(String id, String text) { this.id = id; this.text = text; byteCount = text.getBytes(StandardCharsets.UTF_8).length; }
    public static LogDetailsContent restore(String id, String text) {
        if (!EvidenceValidation.id(id) || !EvidenceValidation.text(text, 65536) || text.isEmpty()) throw EvidenceValidation.invalid();
        return new LogDetailsContent(id, text);
    }
}
