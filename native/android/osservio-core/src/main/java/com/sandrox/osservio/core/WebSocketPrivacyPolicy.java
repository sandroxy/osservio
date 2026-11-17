package com.sandrox.osservio.core;

import java.util.Set;

public final class WebSocketPrivacyPolicy {
    public final boolean publicMessageText;
    public final int messageByteLimit;
    final HTTPPrivacyPolicy metadata;
    public WebSocketPrivacyPolicy() { this(true, null, true, 4096); }
    /** Applies to every message body. Binary content is not copied; diagnostic close text has its own bounded policy field. */
    public WebSocketPrivacyPolicy(boolean publicURLPath, Set<String> publicHeaderNames, boolean publicMessageText, int messageByteLimit) {
        this(publicURLPath, publicHeaderNames, publicMessageText, messageByteLimit, true, 4096, DiagnosticTextPolicy.UNCHANGED);
    }
    public WebSocketPrivacyPolicy(boolean publicURLPath, Set<String> publicHeaderNames, boolean publicMessageText, int messageByteLimit,
                                  boolean publicURLQuery, int headerValueByteLimit, DiagnosticTextPolicy textPolicy) {
        if (messageByteLimit < 1 || messageByteLimit > 65536) throw new IllegalArgumentException("Invalid WebSocket text limit");
        this.publicMessageText = publicMessageText; this.messageByteLimit = messageByteLimit;
        metadata = new HTTPPrivacyPolicy(publicURLPath, publicHeaderNames, HTTPPrivacyPolicy.BodyPolicy.OMIT, 16384,
            publicURLQuery, headerValueByteLimit, textPolicy);
    }
}
