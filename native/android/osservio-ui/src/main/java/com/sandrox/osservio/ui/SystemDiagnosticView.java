package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.FrameLayout;
import com.sandrox.osservio.core.AppleSignalCrash;
import com.sandrox.osservio.core.AndroidTombstone;
import com.sandrox.osservio.core.SystemDiagnostic;

/** Labeled historical facts and bounded address browsers; never reads the live environment. */
@android.annotation.SuppressLint("ViewConstructor")
final class SystemDiagnosticView extends FrameLayout implements InspectorScreen.Content {
    private final SystemDiagnostic diagnostic;
    private InspectorDetailPage page;
    private InspectorScreen detail;
    SystemDiagnosticView(Context context, SystemDiagnostic diagnostic) {
        super(context); this.diagnostic = diagnostic; render(); InspectorAppearance.content(this, this::render);
    }
    @Override public void bindScreen(InspectorScreen screen) {
        screen.title(R.string.osservio_diagnostic_title);
        screen.iconAction(R.drawable.osservio_copy, R.string.osservio_archive_copy_summary, () -> {
            ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", SystemDiagnosticPresentation.summary(getContext(), diagnostic)));
        });
    }
    private void render() {
        int position = page == null ? 0 : page.getScrollY(); removeAllViews();
        page = new InspectorDetailPage(getContext()); page.section("");
        for (SystemDiagnosticPresentation.Field field : SystemDiagnosticPresentation.fields(getContext(), diagnostic)) page.row(field.label, () -> field.value, null);
        page.note(() -> SystemDiagnosticPresentation.hint(getContext(), diagnostic));
        AppleSignalCrash crash = diagnostic.appleSignal;
        AndroidTombstone trace = diagnostic.androidExit == null ? null : diagnostic.androidExit.tombstone;
        if (crash != null || trace != null) page.section("");
        if (crash != null) {
            for (boolean images : new boolean[] {false, true}) {
                int count = images ? crash.images.size() : crash.frames.size();
                int title = images ? R.string.osservio_crash_images : R.string.osservio_crash_frames;
                page.row(title, () -> count == 0 ? getContext().getString(R.string.osservio_runtime_unavailable) : Integer.toString(count),
                    count == 0 ? null : () -> open(title, new SignalCrashView(getContext(), crash, images, () -> { })));
            }
        } else if (trace != null) page.row(R.string.osservio_crash_frames,
            () -> SignalCrashPresentation.count(getContext(), trace.frames.size(), trace.frameLimitReached()),
            trace.frames.isEmpty() ? null : () -> open(R.string.osservio_crash_frames, new SignalCrashView(getContext(), trace, () -> { })));
        addView(page, new LayoutParams(-1, -1)); page.post(() -> page.scrollTo(0, position));
    }
    private void open(int title, SignalCrashView content) {
        if (detail != null) return;
        detail = InspectorDialogs.screen(getContext(), title, content); detail.setOnDismissListener(ignored -> detail = null); detail.show();
    }
    @Override protected void onDetachedFromWindow() {
        if (!InspectorNavigation.owns(getContext()) && detail != null) detail.dismiss(); super.onDetachedFromWindow();
    }
}
