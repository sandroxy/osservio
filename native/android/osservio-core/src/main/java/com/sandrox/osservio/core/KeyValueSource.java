package com.sandrox.osservio.core;

/** A host-selected read-only domain. Methods run on the inspection worker, never the UI thread.
 * Implementations must not mutate, hydrate, migrate, expire or touch access timestamps.
 * Bind an immutable account scope; do not consult a mutable current-user variable.
 */
public interface KeyValueSource {
    interface KeyVisitor {
        /** Stop enumeration immediately when false is returned. Keys are never shortened. */
        boolean visit(String key);
    }
    /** True only when enumeration reached the end of the registered scope. */
    boolean visitKeys(KeyVisitor visitor) throws Exception;
    KeyValueInspection.Value read(String key) throws Exception;
}
