package com.sandrox.osservio.core;

public interface BreadcrumbHistory {
    /** Null means contention, not empty history. */
    BreadcrumbSnapshot snapshot();
    void clear();
}
