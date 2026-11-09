package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import com.sandrox.osservio.core.WebSocketHistory;
import com.sandrox.osservio.core.WebSocketRecorder;
import java.util.Objects;

/** Standalone WebSocket inspection using the same native network workflow as the full inspector. */
// Retains the public programmatic constructor for hosts embedding a single protocol.
@SuppressLint("ViewConstructor")
public final class WebSocketInspectorView extends NetworkInspectorView {
    public WebSocketInspectorView(Context context, WebSocketRecorder recorder, WebSocketHistory history) {
        super(context, null, null, Objects.requireNonNull(recorder, "recorder"), Objects.requireNonNull(history, "history"), new State());
    }
}
