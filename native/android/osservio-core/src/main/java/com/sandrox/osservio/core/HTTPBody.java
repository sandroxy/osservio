package com.sandrox.osservio.core;

import java.nio.charset.StandardCharsets;

public final class HTTPBody {
    public final String id;
    public final String text;
    public final int byteCount;
    HTTPBody(String id, String text) { this.id = id; this.text = text; byteCount = text.getBytes(StandardCharsets.UTF_8).length; }
    public static HTTPBody restore(String id, String text) {
        if (!EvidenceValidation.httpBodyId(id) || !EvidenceValidation.text(text, 65536)) throw EvidenceValidation.invalid();
        return new HTTPBody(id, text);
    }
}
