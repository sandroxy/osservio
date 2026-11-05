package com.sandrox.osservio.core;

/** Non-blocking destination for sanitized context. Restart relevance is intent, not proof of durability. */
public interface BreadcrumbSink {
    enum WriteResult { STORED, BUSY, TOO_LARGE }
    WriteResult append(BreadcrumbEvent event);
}
