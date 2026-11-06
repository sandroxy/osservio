package com.sandrox.osservio.core;

import java.nio.charset.StandardCharsets;

/** Body text is separate from event metadata. Available empty text is distinct from missing content. */
public final class IncidentPayload {
    public enum State { AVAILABLE, NOT_SELECTED, UNAVAILABLE, BUSY, BUDGET_EXCEEDED }
    public final String id;
    public final State state;
    public final String text;
    public final int textByteCount;
    private IncidentPayload(String id, State state, String text) {
        this.id = id; this.state = state; this.text = text;
        textByteCount = text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
    /** Restores already sanitized content; this is not a raw-body privacy policy. */
    public static IncidentPayload restore(String id, State state, String text) {
        if ((!EvidenceValidation.id(id) && !EvidenceValidation.httpBodyId(id)) || state == null
            || (state == State.AVAILABLE) != (text != null) || (text != null && !EvidenceValidation.text(text, 65536))) throw EvidenceValidation.invalid();
        return new IncidentPayload(id, state, text);
    }
}
