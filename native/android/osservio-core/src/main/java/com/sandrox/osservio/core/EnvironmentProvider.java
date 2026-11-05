package com.sandrox.osservio.core;

public interface EnvironmentProvider {
    enum Error { BUSY, UNAVAILABLE }
    interface Completion {
        /** Exactly one argument is non-null. May run on any thread, including immediately for busy queries. */
        void complete(EnvironmentSnapshot snapshot, Error error);
    }
    void read(Completion completion);
}
