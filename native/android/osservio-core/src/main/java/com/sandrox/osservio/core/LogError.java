package com.sandrox.osservio.core;

/** A caught/reported error, not a process crash. The SDK never generates an alleged original stack. */
public final class LogError {
    public final String type;
    public final String message;
    public final String domain;
    public final String code;
    public final String stackTrace;
    public LogError(String type, String message, String domain, String code, String stackTrace) {
        this.type = type; this.message = message; this.domain = domain; this.code = code; this.stackTrace = stackTrace;
    }
}
