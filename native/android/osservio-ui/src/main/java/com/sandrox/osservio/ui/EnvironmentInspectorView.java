package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.sandrox.osservio.core.EnvironmentProvider;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import java.lang.ref.WeakReference;

// Query dependencies require programmatic construction.
@SuppressLint("ViewConstructor")
final class EnvironmentInspectorView extends LinearLayout implements InspectorScreen.Content {
    private final EnvironmentProvider provider;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final android.widget.ImageButton refresh;
    private final android.widget.ImageButton copy;
    private final TextView notice;
    private final android.widget.FrameLayout facts;
    private final LinearLayout controls;
    private EnvironmentSnapshot snapshot;
    private boolean reading;
    private boolean attempted;
    private int generation;
    EnvironmentInspectorView(Context context, EnvironmentProvider provider) {
        super(context); this.provider = provider; setOrientation(VERTICAL);
        refresh = InspectorWidgets.icon(context, R.drawable.osservio_refresh, R.string.osservio_http_refresh, this::read);
        copy = InspectorWidgets.icon(context, R.drawable.osservio_copy, R.string.osservio_runtime_copy_environment, () -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard != null && snapshot != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", RuntimePresentation.environment(context, snapshot)));
        }); copy.setEnabled(false);
        controls = new LinearLayout(context); controls.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        controls.addView(refresh); controls.addView(copy); addView(controls, new LayoutParams(-1, -2));
        notice = InspectorWidgets.caption(context); int inset = InspectorTheme.dp(context, 16); notice.setPadding(inset, 0, inset, inset);
        addView(notice, new LayoutParams(-1, -2));
        facts = new android.widget.FrameLayout(context); addView(facts, new LayoutParams(-1, 0, 1));
        notice.setVisibility(GONE);
    }
    @Override public void bindScreen(InspectorScreen screen) {
        screen.title(R.string.osservio_runtime_environment); screen.addAction(refresh); screen.addAction(copy); controls.setVisibility(GONE);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); visibility(); }
    @Override protected void onDetachedFromWindow() { generation++; reading = false; attempted = false; super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); visibility(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); visibility(); }
    private void visibility() {
        if (provider == null || facts == null) return;
        if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) {
            if (!attempted) read();
        } else { generation++; reading = false; attempted = false; }
    }
    private void read() {
        if (reading) return;
        reading = true; attempted = true; int request = ++generation; refresh.setEnabled(false); notice.setText(R.string.osservio_runtime_reading); notice.setVisibility(VISIBLE);
        WeakReference<EnvironmentInspectorView> reference = new WeakReference<>(this); Handler handler = main;
        provider.read((value, error) -> handler.post(() -> {
            EnvironmentInspectorView view = reference.get();
            if (view != null && request == view.generation) view.received(value, error);
        }));
    }
    private void received(EnvironmentSnapshot value, EnvironmentProvider.Error error) {
        reading = false; refresh.setEnabled(true);
        if (value == null || error != null) {
            String message = getContext().getString(error == EnvironmentProvider.Error.BUSY ? R.string.osservio_busy : R.string.osservio_runtime_read_failed);
            if (snapshot != null) message += "\n" + getContext().getString(R.string.osservio_runtime_previous) + " · " + RuntimePresentation.date(getContext(), snapshot.timestampMillis);
            notice.setText(message); return;
        }
        snapshot = value; copy.setEnabled(true);
        notice.setText(""); notice.setVisibility(GONE);
        facts.removeAllViews(); facts.addView(snapshotView(getContext(), value), new android.widget.FrameLayout.LayoutParams(-1, -1));
    }
    static View snapshotView(Context context, EnvironmentSnapshot snapshot) {
        InspectorDetailPage page = new InspectorDetailPage(context);
        page.section(R.string.osservio_ui_snapshot_time);
        page.value(() -> RuntimePresentation.date(context, snapshot.timestampMillis));
        page.note(R.string.osservio_runtime_environment_hint);
        EnvironmentSnapshot.Key[][] groups = {
            {EnvironmentSnapshot.Key.APP_IDENTIFIER, EnvironmentSnapshot.Key.APP_VERSION, EnvironmentSnapshot.Key.APP_BUILD},
            {EnvironmentSnapshot.Key.OS_VERSION, EnvironmentSnapshot.Key.MANUFACTURER, EnvironmentSnapshot.Key.DEVICE_MODEL,
                EnvironmentSnapshot.Key.ARCHITECTURE, EnvironmentSnapshot.Key.PHYSICAL_MEMORY_BYTES},
            {EnvironmentSnapshot.Key.PROCESS_ID, EnvironmentSnapshot.Key.PROCESS_RESIDENT_BYTES, EnvironmentSnapshot.Key.PROCESS_PROPORTIONAL_BYTES,
                EnvironmentSnapshot.Key.PROCESS_FOOTPRINT_BYTES, EnvironmentSnapshot.Key.RUNTIME_HEAP_BYTES, EnvironmentSnapshot.Key.RUNTIME_HEAP_LIMIT_BYTES},
            {EnvironmentSnapshot.Key.BATTERY_PERCENT, EnvironmentSnapshot.Key.LOW_POWER_MODE, EnvironmentSnapshot.Key.THERMAL_STATE}
        };
        int[] labels = {R.string.osservio_ui_app, R.string.osservio_ui_device, R.string.osservio_ui_process, R.string.osservio_ui_power};
        for (int i = 0; i < groups.length; i++) {
            page.section(labels[i]);
            for (EnvironmentSnapshot.Key key : groups[i]) page.field(RuntimePresentation.keyLabel(key), () -> RuntimePresentation.value(context, snapshot.values.get(key), key));
        }
        return page;
    }
}
