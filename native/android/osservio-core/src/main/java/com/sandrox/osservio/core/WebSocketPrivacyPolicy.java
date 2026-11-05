package com.sandrox.osservio.core;

import java.util.Collections;
import java.util.Set;

public final class WebSocketPrivacyPolicy {
    public final boolean publicMessageText;
    public final int messageByteLimit;
    final HTTPPrivacyPolicy metadata;
    public WebSocketPrivacyPolicy() { this(false, Collections.emptySet(), false, 4096); }
    /** Applies to every text message. Binary content and close reasons are never copied. */
    public WebSocketPrivacyPolicy(boolean publicURLPath, Set<String> publicHeaderNames, boolean publicMessageText, int messageByteLimit) {
        if (messageByteLimit < 1 || messageByteLimit > 65536) throw new IllegalArgumentException("Invalid WebSocket text limit");
        this.publicMessageText = publicMessageText; this.messageByteLimit = messageByteLimit;
        metadata = new HTTPPrivacyPolicy(publicURLPath, publicHeaderNames, HTTPPrivacyPolicy.BodyPolicy.OMIT, 16384);
    }
}
