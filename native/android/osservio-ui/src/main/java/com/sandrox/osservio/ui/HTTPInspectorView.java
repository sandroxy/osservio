package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.HTTPRecorder;
import java.util.Objects;

/** Standalone HTTP inspection using the same native network workflow as the full inspector. */
// Retains the public programmatic constructor for hosts embedding a single protocol.
@SuppressLint("ViewConstructor")
public final class HTTPInspectorView extends NetworkInspectorView {
    public HTTPInspectorView(Context context, HTTPRecorder recorder, HTTPHistory history) {
        super(context, Objects.requireNonNull(recorder, "recorder"), Objects.requireNonNull(history, "history"), null, null, new State());
    }
}
