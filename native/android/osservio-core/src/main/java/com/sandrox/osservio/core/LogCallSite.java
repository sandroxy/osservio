package com.sandrox.osservio.core;

/** Supplied location; prefer a logical filename over a developer's absolute path. */
public final class LogCallSite {
    public final String file;
    public final String function;
    public final Integer line;
    public LogCallSite(String file, String function, Integer line) {
        this.file = file; this.function = function; this.line = line;
    }
}
