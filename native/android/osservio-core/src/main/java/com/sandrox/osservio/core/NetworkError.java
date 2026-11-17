package com.sandrox.osservio.core;

import java.nio.charset.StandardCharsets;

/** Bounded, host-processed error facts. No exception, cause chain, or user-info object is retained. */
public final class NetworkError {
    /** Raw fields supplied synchronously only after capture admission. */
    public static final class Input {
        public final String domain, code, type, message;
        public Input(String domain, String code, String type, String message) {
            this.domain = domain; this.code = code; this.type = type; this.message = message;
        }
    }
    @FunctionalInterface public interface Source { Input read() throws Exception; }
    public final String domain, code, type, message;
    /** At least one field was truncated, malformed, or unavailable from the source. */
    public final boolean isLimited;
    /** Host processing changed or omitted at least one field. */
    public final boolean isProcessed;
    public final int textByteCount;
    private NetworkError(String domain, String code, String type, String message, boolean limited, boolean processed) {
        this.domain = domain; this.code = code; this.type = type; this.message = message;
        isLimited = limited; isProcessed = processed;
        textByteCount = size(domain) + size(code) + size(type) + size(message);
    }
    static NetworkError capture(Source source, DiagnosticTextPolicy policy) {
        if (source == null) return null;
        Input input;
        try { input = source.read(); }
        catch (Exception failure) { return new NetworkError(null, null, null, null, true, false); }
        if (input == null) return null;
        String[] raw = {input.domain, input.code, input.type, input.message};
        String[] values = new String[4]; int[] limits = {256, 64, 256, 4096};
        DiagnosticTextPolicy.Field[] fields = {DiagnosticTextPolicy.Field.NETWORK_ERROR_DOMAIN,
            DiagnosticTextPolicy.Field.NETWORK_ERROR_CODE, DiagnosticTextPolicy.Field.NETWORK_ERROR_TYPE,
            DiagnosticTextPolicy.Field.NETWORK_ERROR_MESSAGE};
        boolean limited = false, processed = false;
        for (int index = 0; index < raw.length; index++) {
            if (raw[index] == null) continue;
            BoundedText bounded = BoundedText.prefix(raw[index], limits[index]);
            if (bounded == null) { limited = true; continue; }
            values[index] = DiagnosticTextPolicy.apply(policy, fields[index], null, bounded.text, bounded.truncated, limits[index]);
            limited |= bounded.truncated;
            processed |= !bounded.text.equals(values[index]);
        }
        if (!limited && !processed && values[0] == null && values[1] == null && values[2] == null && values[3] == null) return null;
        return new NetworkError(values[0], values[1], values[2], values[3], limited, processed);
    }
    /** Restore policy-processed evidence without interpreting or reformatting native error codes. */
    public static NetworkError restore(String domain, String code, String type, String message, boolean limited, boolean processed) {
        if (!valid(domain, 256) || !valid(code, 64) || !valid(type, 256) || !valid(message, 4096)
            || (domain == null && code == null && type == null && message == null && !limited && !processed)) throw EvidenceValidation.invalid();
        return new NetworkError(domain, code, type, message, limited, processed);
    }
    private static boolean valid(String value, int limit) { return value == null || EvidenceValidation.text(value, limit); }
    private static int size(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }
}
