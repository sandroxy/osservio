package com.sandrox.osservio.core;

/** Host processing before recording. Runs on the observing thread: no I/O, waiting, or capture reentry.
 * Implementations shared by recorders must be thread-safe. Inputs are bounded. Null, an exception, or oversized output omits the value without a raw fallback. */
@FunctionalInterface
public interface DiagnosticTextPolicy {
    enum Field { URL, HEADER, REQUEST_BODY, RESPONSE_BODY, WEB_SOCKET_MESSAGE }
    final class Input {
        public final Field field;
        /** Lowercase name for HEADER; null for other fields. */
        public final String headerName;
        public final String text;
        public final boolean isTruncated;
        Input(Field field, String headerName, String text, boolean isTruncated) {
            this.field = field; this.headerName = headerName; this.text = text; this.isTruncated = isTruncated;
        }
    }
    String process(Input input) throws Exception;
    DiagnosticTextPolicy UNCHANGED = input -> input.text;

    static String apply(DiagnosticTextPolicy policy, Field field, String headerName, String text, boolean truncated, int limit) {
        try {
            String result = policy.process(new Input(field, headerName, text, truncated));
            return EvidenceValidation.text(result, limit) ? result : null;
        } catch (Exception failure) { return null; }
    }
}
