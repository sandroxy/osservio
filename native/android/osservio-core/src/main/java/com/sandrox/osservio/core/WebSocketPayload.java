package com.sandrox.osservio.core;

import java.nio.charset.StandardCharsets;

public final class WebSocketPayload {
    public final String id;
    public final String text;
    public final int byteCount;
    WebSocketPayload(String id, String text) { this.id = id; this.text = text; byteCount = text.getBytes(StandardCharsets.UTF_8).length; }
    public static WebSocketPayload restore(String id, String text) {
        if (!EvidenceValidation.id(id) || !EvidenceValidation.text(text, 65536)) throw EvidenceValidation.invalid();
        return new WebSocketPayload(id, text);
    }
}
