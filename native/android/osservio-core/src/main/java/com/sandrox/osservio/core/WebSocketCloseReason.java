package com.sandrox.osservio.core;

import java.nio.charset.StandardCharsets;

/** Application close text, distinct from the numeric close code and transport outcome. */
public final class WebSocketCloseReason {
    @FunctionalInterface public interface Source { String read() throws Exception; }
    public final String text;
    public final boolean isLimited, isProcessed;
    public final int textByteCount;
    private WebSocketCloseReason(String text, boolean limited, boolean processed) {
        this.text = text; isLimited = limited; isProcessed = processed;
        textByteCount = text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
    static WebSocketCloseReason capture(Source source, DiagnosticTextPolicy policy) {
        if (source == null) return null;
        String raw;
        try { raw = source.read(); }
        catch (Exception failure) { return new WebSocketCloseReason(null, true, false); }
        if (raw == null) return null;
        BoundedText bounded = BoundedText.prefix(raw, 512);
        if (bounded == null) return new WebSocketCloseReason(null, true, false);
        String value = DiagnosticTextPolicy.apply(policy, DiagnosticTextPolicy.Field.WEB_SOCKET_CLOSE_REASON, null, bounded.text, bounded.truncated, 512);
        return new WebSocketCloseReason(value, bounded.truncated, !bounded.text.equals(value));
    }
    public static WebSocketCloseReason restore(String text, boolean limited, boolean processed) {
        if ((text != null && !EvidenceValidation.text(text, 512)) || (text == null && !limited && !processed)) throw EvidenceValidation.invalid();
        return new WebSocketCloseReason(text, limited, processed);
    }
}
