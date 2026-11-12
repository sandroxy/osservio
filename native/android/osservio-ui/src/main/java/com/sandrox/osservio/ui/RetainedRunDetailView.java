package com.sandrox.osservio.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import com.sandrox.osservio.core.RetainedRun;
import java.util.Locale;

@android.annotation.SuppressLint("ViewConstructor") // Requires an immutable run; not XML-inflatable.
final class RetainedRunDetailView extends LinearLayout implements InspectorScreen.Content {
    private final ImageButton copy;
    private final LinearLayout controls;
    RetainedRunDetailView(Context context, RetainedRun run) {
        super(context); setOrientation(VERTICAL);
        copy = InspectorWidgets.icon(context, R.drawable.osservio_copy, R.string.osservio_archive_copy_summary, () -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", PersistencePresentation.run(context, run)));
        });
        controls = new LinearLayout(context); controls.setGravity(android.view.Gravity.END); controls.addView(copy); addView(controls);
        InspectorDetailPage page = new InspectorDetailPage(context);
        page.section(R.string.osservio_ui_overview);
        page.field(R.string.osservio_ui_run_kind, () -> context.getString(run.isCurrent ? R.string.osservio_archive_current : R.string.osservio_archive_previous_run));
        page.field(R.string.osservio_ui_run_started, () -> InspectorValuePresentation.eventTime(run.recordingStartedAtMillis, true));
        page.field(R.string.osservio_ui_run_last_event, () -> InspectorValuePresentation.eventTime(run.lastRecordedAtMillis, true));
        page.field(R.string.osservio_ui_run_events, run.eventCount); page.field(R.string.osservio_ui_run_evicted, run.evictedEvents);
        page.note(R.string.osservio_archive_snapshot);
        page.section(R.string.osservio_ui_technical_details); page.field(R.string.osservio_ui_platform, run.platform.name().toLowerCase(Locale.ROOT));
        page.field(RuntimePresentation.keyLabel(com.sandrox.osservio.core.EnvironmentSnapshot.Key.PROCESS_ID), run.processId);
        page.field(R.string.osservio_ui_run_identity, run.id); addView(page, new LayoutParams(-1, 0, 1));
    }
    @Override public void bindScreen(InspectorScreen screen) {
        screen.title(R.string.osservio_ui_run_details); screen.addAction(copy); controls.setVisibility(GONE);
    }
}
