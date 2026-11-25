package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import com.sandrox.osservio.core.IncidentPayload;
import com.sandrox.osservio.core.LogDetailsHistory;
import java.util.Map;

/** Fixed neighboring logs use the ordinary recycled rows and detail readers. */
@android.annotation.SuppressLint("ViewConstructor")
final class LogContextView extends InspectorScroll.List {
    private final InspectorLogContext window;
    private final LogDetailsHistory history;
    private final Map<String, IncidentPayload> saved;
    private boolean positioned;
    private InspectorScreen detail;

    LogContextView(Context context, InspectorLogContext window, LogDetailsHistory history, Map<String, IncidentPayload> saved) {
        super(context); this.window = window; this.history = history; this.saved = saved;
        setDivider(null); setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        TextView note = InspectorWidgets.caption(context);
        int inset = InspectorTheme.dp(context, 16); note.setPadding(inset * 2, inset, inset * 2, inset);
        InspectorAppearance.text(note, () -> context.getString(R.string.osservio_ui_log_neighbors_hint));
        Rows rows = new Rows(); addHeaderView(note, null, false); setAdapter(rows);
        InspectorAppearance.content(this, rows::notifyDataSetChanged);
        setOnItemClickListener((parent, view, position, id) -> {
            int index = position - getHeaderViewsCount();
            if (index < 0 || index >= window.events.size() || detail != null) return;
            detail = LogDetailDialog.create(context, window.events.get(index), history, saved);
            detail.setOnDismissListener(ignored -> detail = null); detail.show();
        });
    }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (!positioned && height > 0) { positioned = true; setSelectionFromTop(window.targetIndex + getHeaderViewsCount(), height / 3); }
    }
    @Override protected void onDetachedFromWindow() {
        if (!InspectorNavigation.owns(getContext()) && detail != null) detail.dismiss();
        super.onDetachedFromWindow();
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return window.events.size(); }
        @Override public Object getItem(int position) { return window.events.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.EventRow row = recycled instanceof InspectorWidgets.EventRow ? (InspectorWidgets.EventRow) recycled : new InspectorWidgets.EventRow(getContext());
            InspectorRecordPresentation.Row summary = InspectorRecordPresentation.log(getContext(), window.events.get(position), null);
            if (position == window.targetIndex) summary = new InspectorRecordPresentation.Row(
                summary.badge + " · " + getContext().getString(R.string.osservio_ui_selected_record), summary.title, summary.subtitle, summary.time, summary.tone);
            summary.bind(row, getContext()); row.grouped(position == 0, position == getCount() - 1); return row;
        }
    }
}
