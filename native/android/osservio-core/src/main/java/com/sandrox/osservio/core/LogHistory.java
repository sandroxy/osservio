package com.sandrox.osservio.core;

public interface LogHistory {
    /** Null means contention, not an empty history. Retain the previous view. */
    LogSnapshot snapshot();

    /** Clears this live history. Does not start or stop recording. */
    void clear();
}
