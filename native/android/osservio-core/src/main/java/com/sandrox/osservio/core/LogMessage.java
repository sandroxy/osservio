package com.sandrox.osservio.core;

/** A host classification. Private input is discarded immediately. */
public final class LogMessage {
    final String text;
    final boolean isPrivate;

    private LogMessage(String text, boolean isPrivate) {
        this.text = text;
        this.isPrivate = isPrivate;
    }

    public static LogMessage privateText(String text) {
        return new LogMessage("[private]", true);
    }

    /** Use only for text the host has reviewed as safe to show and copy. */
    public static LogMessage publicText(String text) {
        return new LogMessage(text, false);
    }
}
