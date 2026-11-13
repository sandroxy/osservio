package com.sandrox.osservio.core;

public final class LogDetailsReference {
    public enum State { AVAILABLE, OMITTED, LIMITED, INVALID, UNSUPPORTED }
    public final State state;
    public final String blobId;
    public final int textByteCount;
    private LogDetailsReference(State state, String blobId, int bytes) { this.state = state; this.blobId = blobId; textByteCount = bytes; }
    public static LogDetailsReference restore(State state, String blobId, int bytes) {
        if (state == null || (state == State.AVAILABLE) != (blobId != null)
            || blobId != null && !EvidenceValidation.id(blobId)
            || (state == State.AVAILABLE ? bytes < 1 || bytes > 65536 : bytes != 0)) throw EvidenceValidation.invalid();
        return new LogDetailsReference(state, blobId, bytes);
    }
}
