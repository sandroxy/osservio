package com.sandrox.osservio.core;

public interface LogClock {
    long monotonicMillis();
    long wallTimeMillis();

    LogClock SYSTEM = new LogClock() {
        @Override public long monotonicMillis() { return System.nanoTime() / 1_000_000L; }
        @Override public long wallTimeMillis() { return System.currentTimeMillis(); }
    };
}
