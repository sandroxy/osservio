package com.sandrox.osservio.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import com.sandrox.osservio.core.EnvironmentSnapshot;

/** Saved environment facts and copying share the same immutable attachment. */
@android.annotation.SuppressLint("ViewConstructor") // Requires a frozen attachment; not XML-inflatable.
final class EnvironmentSnapshotView extends LinearLayout implements InspectorScreen.Content {
    private final ImageButton copy;
    private final LinearLayout controls;
    EnvironmentSnapshotView(Context context, EnvironmentSnapshot snapshot) {
        super(context); setOrientation(VERTICAL);
        copy = InspectorWidgets.icon(context, R.drawable.osservio_copy, R.string.osservio_runtime_copy_environment, () -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", RuntimePresentation.environment(context, snapshot)));
        });
        controls = new LinearLayout(context); controls.setGravity(android.view.Gravity.END); controls.addView(copy); addView(controls);
        addView(EnvironmentInspectorView.factsView(context, snapshot, true), new LayoutParams(-1, 0, 1));
    }
    @Override public void bindScreen(InspectorScreen screen) {
        screen.title(R.string.osservio_runtime_environment); screen.addAction(copy); controls.setVisibility(GONE);
    }
}
