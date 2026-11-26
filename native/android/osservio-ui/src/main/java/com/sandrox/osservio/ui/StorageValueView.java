package com.sandrox.osservio.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import com.sandrox.osservio.core.KeyValueInspection;
import java.lang.ref.WeakReference;

/** One explicit value read. No background refresh and no value retention after leaving this page. */
@android.annotation.SuppressLint("ViewConstructor") // Explicit host-owned query dependencies; never inflated from XML.
final class StorageValueView extends LinearLayout implements InspectorScreen.Content {
    private final KeyValueInspection storage;
    private final KeyValueInspection.Domain domain;
    private final String key;
    private final InspectorContentReader reader;
    private final android.widget.ImageButton refresh;
    private final Handler main = new Handler(Looper.getMainLooper());
    private KeyValueInspection.Request request;
    private KeyValueInspection.Request observation;
    private int generation;
    private boolean loaded;
    StorageValueView(Context context, KeyValueInspection storage, KeyValueInspection.Domain domain, String key) {
        super(context); this.storage = storage; this.domain = domain; this.key = key; setOrientation(VERTICAL);
        reader = new InspectorContentReader(context); addView(reader, new LayoutParams(-1, -1));
        refresh = InspectorWidgets.icon(context, R.drawable.osservio_refresh, R.string.osservio_http_refresh, this::read);
    }
    @Override public void bindScreen(InspectorScreen screen) { reader.bindScreen(screen); screen.addAction(refresh); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); visibility(); }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); visibility(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); visibility(); }
    @Override protected void onDetachedFromWindow() { clear(); unobserve(); super.onDetachedFromWindow(); }
    private void visibility() {
        if (reader == null) return;
        if (!isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE) { clear(); unobserve(); return; }
        if (observation == null) {
            WeakReference<StorageValueView> reference = new WeakReference<>(this); Handler handler = main;
            observation = storage.observeClose(() -> handler.post(() -> { StorageValueView view = reference.get(); if (view != null) view.read(); }));
        }
        if (!loaded && request == null) read();
    }
    private void unobserve() { if (observation != null) observation.close(); observation = null; }
    private void clear() {
        generation++; if (request != null) request.close(); request = null; loaded = false;
        if (reader != null) reader.display(InspectorReadContent.unavailable(""), false);
    }
    private void read() {
        clear();
        if (storage.isClosed()) { reader.display(InspectorReadContent.unavailable(getContext().getString(R.string.osservio_storage_closed)), false); refresh.setEnabled(false); return; }
        refresh.setEnabled(false); int ticket = ++generation;
        reader.display(InspectorReadContent.unavailable(getContext().getString(R.string.osservio_runtime_reading)), false);
        WeakReference<StorageValueView> reference = new WeakReference<>(this); Handler handler = main;
        request = storage.read(domain.id, key, (value, error) -> handler.post(() -> {
            StorageValueView view = reference.get(); if (view == null || ticket != view.generation) return;
            view.request = null; view.loaded = true; view.refresh.setEnabled(!view.storage.isClosed());
            if (view.storage.isClosed() || error != null) {
                view.reader.display(InspectorReadContent.unavailable(view.getContext().getString(view.storage.isClosed() ? R.string.osservio_storage_closed
                    : error == KeyValueInspection.Error.BUSY ? R.string.osservio_busy : R.string.osservio_runtime_read_failed)), false); return;
            }
            view.received(value);
        }));
    }
    private void received(KeyValueInspection.Read read) {
        Context context = getContext(); KeyValueInspection.Value value = read.value;
        InspectorAppearance.Value<String> origin = () -> domain.name + " · " + domain.backend + " · " + domain.scope + "\n" + key;
        InspectorAppearance.Value<String> notice = () -> context.getString(R.string.osservio_storage_read_at) + " · " + RuntimePresentation.date(context, read.readAtMillis)
            + "\n" + (value.state == KeyValueInspection.State.AVAILABLE ? kind(context, value.kind) : context.getString(state(value.state)))
            + (value.truncated ? "\n" + context.getString(R.string.osservio_storage_truncated) : "")
            + (value.state == KeyValueInspection.State.AVAILABLE && value.text.isEmpty() ? "\n" + context.getString(R.string.osservio_storage_empty_value) : "")
            + "\n" + context.getString(R.string.osservio_storage_read_hint);
        reader.display((value.state == KeyValueInspection.State.AVAILABLE ? new InspectorReadContent(value.text, notice, !value.truncated)
            : InspectorReadContent.unavailable(notice)).withContext(origin), true);
    }
    static int state(KeyValueInspection.State state) {
        switch (state) {
            case MISSING: return R.string.osservio_storage_missing;
            case TOO_LARGE: return R.string.osservio_storage_too_large;
            default: return R.string.osservio_storage_unsupported;
        }
    }
    static String kind(Context context, KeyValueInspection.Kind kind) {
        int[] labels = {R.string.osservio_storage_kind_string, R.string.osservio_storage_kind_boolean, R.string.osservio_storage_kind_integer,
            R.string.osservio_storage_kind_number, R.string.osservio_storage_kind_stringset, R.string.osservio_storage_kind_array,
            R.string.osservio_storage_kind_object, R.string.osservio_storage_kind_data, R.string.osservio_storage_kind_date};
        return context.getString(labels[kind.ordinal()]);
    }
}
