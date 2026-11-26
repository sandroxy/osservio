package com.sandrox.osservio.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.sandrox.osservio.core.KeyValueInspection;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded key metadata only. Scrolling and searching never fetch values. */
@android.annotation.SuppressLint("ViewConstructor") // Explicit host-owned query dependencies; never inflated from XML.
final class StorageInspectorView extends LinearLayout implements InspectorScreen.Content {
    private final KeyValueInspection storage;
    private final KeyValueInspection.Domain domain;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView notice;
    private final EditText search;
    private final InspectorScroll.Page controls;
    private final android.widget.ImageButton refresh;
    private final Rows rows = new Rows();
    private List<String> keys = Collections.emptyList(), shown = Collections.emptyList();
    private KeyValueInspection.Keys index;
    private KeyValueInspection.Request request;
    private KeyValueInspection.Request observation;
    private int generation;
    private boolean attempted;
    private InspectorScreen child;
    private final Runnable filter = this::filter;
    StorageInspectorView(Context context, KeyValueInspection storage, KeyValueInspection.Domain domain) {
        super(context); this.storage = storage; this.domain = domain; setOrientation(VERTICAL);
        int inset = InspectorTheme.dp(context, 16); setPadding(inset, 0, inset, 0);
        LinearLayout header = new LinearLayout(context); header.setOrientation(VERTICAL);
        controls = new InspectorScroll.Page(context); controls.addView(header); addView(controls, new LayoutParams(-1, -2));
        notice = InspectorWidgets.caption(context); notice.setPadding(inset, inset, inset, inset); header.addView(notice);
        refresh = InspectorWidgets.icon(context, R.drawable.osservio_refresh, R.string.osservio_http_refresh, this::read);
        search = InspectorWidgets.search(context, R.string.osservio_storage_search, "", value -> {
            main.removeCallbacks(filter); main.postDelayed(filter, 120);
        });
        LayoutParams searchLayout = new LayoutParams(-1, -2); searchLayout.bottomMargin = inset; header.addView(search, searchLayout);
        search.setVisibility(domain == null ? GONE : VISIBLE);
        InspectorScroll.List list = new InspectorScroll.List(context); list.setDivider(null); list.setAdapter(rows);
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (domain == null || storage.isClosed() || position >= shown.size()) return false;
            android.widget.PopupMenu menu = new android.widget.PopupMenu(context, view);
            String key = shown.get(position);
            menu.getMenu().add(R.string.osservio_storage_copy_key).setOnMenuItemClickListener(item -> {
                if (!storage.isClosed()) {
                    android.content.ClipboardManager clipboard = context.getSystemService(android.content.ClipboardManager.class);
                    if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Osservio", key));
                }
                return true;
            }); menu.show(); return true;
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (storage.isClosed()) { closed(); return; }
            InspectorWidgets.hideKeyboard(this);
            View content = domain == null ? new StorageInspectorView(context, storage, storage.domains.get(position))
                : new StorageValueView(context, storage, domain, shown.get(position));
            child = InspectorDialogs.screen(context, domain == null ? storage.domains.get(position).name : context.getString(R.string.osservio_storage_value), content);
            child.show();
        });
        addView(list, new LayoutParams(-1, 0, 1)); InspectorAppearance.content(this, this::status); status();
    }
    @Override public void bindScreen(InspectorScreen screen) { if (domain != null) screen.addAction(refresh); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); visibility(); }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); visibility(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); visibility(); }
    @Override protected void onDetachedFromWindow() {
        cancel(); unobserve();
        main.removeCallbacks(filter); if (!InspectorNavigation.owns(getContext()) && child != null) child.dismiss(); super.onDetachedFromWindow();
    }
    private void visibility() {
        if (notice == null) return;
        if (!isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE) { cancel(); unobserve(); return; }
        if (observation == null) {
            WeakReference<StorageInspectorView> reference = new WeakReference<>(this); Handler handler = main;
            observation = storage.observeClose(() -> handler.post(() -> { StorageInspectorView view = reference.get(); if (view != null) view.closed(); }));
        }
        if (storage.isClosed()) closed(); else if (domain != null && !attempted) read();
    }
    private void unobserve() { if (observation != null) observation.close(); observation = null; }
    @Override protected void onMeasure(int width, int height) {
        int available = Math.max(0, MeasureSpec.getSize(height) - getPaddingTop() - getPaddingBottom());
        if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED && available > 0) {
            int contentWidth = getChildMeasureSpec(width, getPaddingLeft() + getPaddingRight(), LayoutParams.MATCH_PARENT);
            controls.measure(contentWidth, MeasureSpec.makeMeasureSpec(available / 2, MeasureSpec.AT_MOST));
            controls.getLayoutParams().height = controls.getMeasuredHeight();
        }
        super.onMeasure(width, height);
    }
    private void cancel() { generation++; if (request != null) { request.close(); request = null; attempted = false; } }
    private void closed() { cancel(); keys = shown = Collections.emptyList(); index = null; rows.notifyDataSetChanged(); status(); }
    private void read() {
        if (domain == null || request != null) return;
        if (storage.isClosed()) { closed(); return; }
        attempted = true; final int ticket = ++generation; refresh.setEnabled(false); notice.setText(R.string.osservio_runtime_reading);
        WeakReference<StorageInspectorView> reference = new WeakReference<>(this); Handler handler = main;
        request = storage.keys(domain.id, (value, error) -> handler.post(() -> {
            StorageInspectorView view = reference.get(); if (view == null || ticket != view.generation) return;
            view.request = null; view.refresh.setEnabled(true);
            if (view.storage.isClosed()) { view.closed(); return; }
            if (error != null) { view.keys = Collections.emptyList(); view.index = null; view.filter(); view.notice.setText(error == KeyValueInspection.Error.BUSY ? R.string.osservio_busy : R.string.osservio_runtime_read_failed); }
            else { view.index = value; view.keys = value.keys; view.filter(); }
        }));
    }
    private void filter() {
        String query = search.getText().toString(); List<String> result = new ArrayList<>();
        for (String key : keys) if (InspectorQuery.hasText(key, query)) result.add(key);
        shown = result; rows.notifyDataSetChanged(); status();
    }
    private void status() {
        refresh.setEnabled(!storage.isClosed() && request == null);
        if (storage.isClosed()) { notice.setText(R.string.osservio_storage_closed); return; }
        if (domain == null) { notice.setText(storage.domains.isEmpty() ? R.string.osservio_storage_no_domains : R.string.osservio_storage_hint); return; }
        String text = domain.backend + " · " + domain.scope;
        if (index != null) text += "\n" + getContext().getString(R.string.osservio_storage_read_at) + " · " + RuntimePresentation.date(getContext(), index.readAtMillis)
            + "\n" + shown.size() + " / " + keys.size() + (index.limited ? "\n" + getContext().getString(R.string.osservio_storage_limited) : "");
        if (index != null && keys.isEmpty()) text += "\n" + getContext().getString(R.string.osservio_storage_empty);
        notice.setText(text);
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return storage.isClosed() ? 0 : domain == null ? storage.domains.size() : shown.size(); }
        @Override public Object getItem(int position) { return domain == null ? storage.domains.get(position) : shown.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.ValueRow row = recycled instanceof InspectorWidgets.ValueRow ? (InspectorWidgets.ValueRow) recycled : new InspectorWidgets.ValueRow(getContext());
            KeyValueInspection.Domain item = domain == null ? storage.domains.get(position) : domain;
            String key = domain == null ? item.name : shown.get(position);
            row.bind(key.isEmpty() ? getContext().getString(R.string.osservio_storage_empty_key) : key, domain == null ? item.backend + " · " + item.scope : "", true);
            row.title.setMaxLines(3); row.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            InspectorWidgets.groupedRow(row, position == 0, position == getCount() - 1); return row;
        }
    }
}
