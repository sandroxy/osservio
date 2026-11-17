package com.sandrox.osservio.okhttp;

import com.sandrox.osservio.core.NetworkError;

/** Called synchronously inside recorder admission; does not traverse causes or format stack traces. */
final class NetworkErrorObservation {
    private NetworkErrorObservation() { }
    static NetworkError.Input read(Throwable error) {
        return new NetworkError.Input(null, null, error.getClass().getName(), error.getMessage());
    }
}
