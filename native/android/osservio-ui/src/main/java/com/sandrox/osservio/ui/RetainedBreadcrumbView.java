package com.sandrox.osservio.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.sandrox.osservio.core.BreadcrumbArchive;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.RetainedBreadcrumbs;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The immutable run has its own navigation and reading position. */
@android.annotation.SuppressLint("ViewConstructor") // Requires an archive and its immutable query result.
final class RetainedBreadcrumbView extends LinearLayout implements InspectorScreen.Content {
    private final RetainedBreadcrumbs history;
    private final BreadcrumbArchive archive;
    private final EditText search;
    private final TextView status;
    private final InspectorSearchGroup searchGroup;
    private final ImageButton copy, delete;
    private final LinearLayout controls;
    private final List<BreadcrumbEvent> events = new ArrayList<>();
    private final Rows rows = new Rows();
    private InspectorScreen screen, detail;
    private boolean deleting;
    private int generation;
    RetainedBreadcrumbView(Context context, RetainedBreadcrumbs history, BreadcrumbArchive archive) {
        super(context); this.history = history; this.archive = archive; setOrientation(VERTICAL);
        controls = new LinearLayout(context); controls.setGravity(android.view.Gravity.END);
        copy = InspectorWidgets.icon(context, R.drawable.osservio_copy, R.string.osservio_archive_copy_summary, () -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", PersistencePresentation.run(context, history.run)));
        });
        delete = InspectorWidgets.icon(context, R.drawable.osservio_trash, R.string.osservio_archive_delete_run, this::confirmDeletion);
        controls.addView(copy); controls.addView(delete); addView(controls);
        ListView list = new InspectorScroll.List(context); list.setDivider(null); list.setDividerHeight(0);
        list.setPadding(dp(16), 0, dp(16), dp(16)); list.setClipToPadding(false);
        LinearLayout header = new LinearLayout(context); header.setOrientation(VERTICAL);
        status = InspectorWidgets.caption(context); status.setVisibility(GONE); status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE); header.addView(status);
        TextView title = InspectorWidgets.sectionLabel(context); InspectorAppearance.text(title, R.string.osservio_ui_run_details); header.addView(title);
        LinearLayout group = InspectorWidgets.group(context); header.addView(group);
        InspectorWidgets.ValueRow summary = new InspectorWidgets.ValueRow(context);
        Runnable render = () -> summary.bind(PersistencePresentation.title(context, history.run), PersistencePresentation.counts(context, history.run), true);
        InspectorAppearance.content(summary, render); render.run(); summary.action(() -> show(InspectorDialogs.screen(context, R.string.osservio_ui_run_details, new RetainedRunDetailView(context, history.run))));
        InspectorWidgets.addGrouped(group, summary);
        TextView hint = InspectorWidgets.caption(context); hint.setPadding(dp(16), dp(8), dp(16), 0); InspectorAppearance.text(hint, R.string.osservio_archive_snapshot); header.addView(hint);
        search = InspectorWidgets.search(context, R.string.osservio_crumb_search, "", ignored -> filter());
        list.addHeaderView(header, null, false);
        searchGroup = new InspectorSearchGroup(context, search, R.string.osservio_crumb_search);
        list.addHeaderView(searchGroup, null, false);
        list.setAdapter(rows); list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index >= 0 && index < events.size()) show(BreadcrumbDetailDialog.create(context, events.get(index)));
        });
        addView(list, new LayoutParams(-1, 0, 1)); InspectorAppearance.content(this, this::filter); filter();
    }
    @Override public void bindScreen(InspectorScreen value) {
        screen = value; screen.title(R.string.osservio_ui_retained_run); screen.addAction(copy); screen.addAction(delete); controls.setVisibility(GONE);
    }
    private void show(InspectorScreen value) {
        if (detail != null || deleting) return;
        InspectorWidgets.hideKeyboard(this); detail = value;
        value.setOnDismissListener(ignored -> { if (detail == value) detail = null; }); value.show();
    }
    private void confirmDeletion() {
        show(InspectorDialogs.confirm(getContext(), R.string.osservio_archive_delete_run,
            getContext().getString(R.string.osservio_archive_delete_confirmation), R.string.osservio_archive_delete_confirm, (ignored, button) -> deleteConfirmed()));
    }
    private void deleteConfirmed() {
        if (deleting) return;
        deleting = true; copy.setEnabled(false); delete.setEnabled(false); status(getContext().getString(R.string.osservio_archive_deleting));
        int request = ++generation; Handler main = new Handler(Looper.getMainLooper()); WeakReference<RetainedBreadcrumbView> reference = new WeakReference<>(this);
        archive.deleteRun(history.run.id, error -> main.post(() -> {
            RetainedBreadcrumbView view = reference.get(); if (view == null || request != view.generation) return;
            view.deleting = false; view.copy.setEnabled(true); view.delete.setEnabled(true);
            if (error == null) { view.status(""); if (view.screen != null) view.screen.dismiss(); }
            else view.status(PersistencePresentation.error(view.getContext(), error));
        }));
    }
    private void status(String value) { status.setText(value); status.setVisibility(value.isEmpty() ? GONE : VISIBLE); }
    private void invalidateDelivery() {
        generation++;
        if (deleting) { deleting = false; copy.setEnabled(true); delete.setEnabled(true); status(""); }
    }
    @Override protected void onDetachedFromWindow() {
        invalidateDelivery(); if (!InspectorNavigation.owns(getContext()) && detail != null) detail.dismiss(); super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); if (copy != null && !isShown()) invalidateDelivery(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); if (copy != null && visibility != VISIBLE) invalidateDelivery(); }
    private void filter() {
        if (searchGroup == null) return;
        String query = search.getText().toString().toLowerCase(Locale.ROOT); events.clear();
        for (int i = history.events.size() - 1; i >= 0; i--) {
            BreadcrumbEvent event = history.events.get(i);
            if (query.isEmpty() || event.label.toLowerCase(Locale.ROOT).contains(query)
                || RuntimePresentation.kind(getContext(), event.kind).toLowerCase(Locale.ROOT).contains(query)
                || event.message != null && event.message.toLowerCase(Locale.ROOT).contains(query)) events.add(event);
        }
        searchGroup.bind(getContext().getString(R.string.osservio_ui_record_count, history.events.size()), true,
            events.isEmpty() ? getContext().getString(history.events.isEmpty() ? R.string.osservio_ui_saved_breadcrumbs_empty : R.string.osservio_ws_no_matches) : null,
            !events.isEmpty()); rows.notifyDataSetChanged();
    }
    private int dp(int value) { return InspectorTheme.dp(getContext(), value); }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return events.size(); }
        @Override public Object getItem(int position) { return events.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.ValueRow row = recycled instanceof InspectorWidgets.ValueRow ? (InspectorWidgets.ValueRow) recycled : new InspectorWidgets.ValueRow(getContext());
            BreadcrumbEvent event = events.get(position); row.bind(RuntimePresentation.breadcrumbTitle(getContext(), event),
                InspectorValuePresentation.eventTime(event.timestampMillis, false) + "\n" + RuntimePresentation.breadcrumbSubtitle(getContext(), event), true);
            row.title.setMaxLines(2); row.title.setEllipsize(android.text.TextUtils.TruncateAt.END); row.value.setMaxLines(3); row.value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            InspectorWidgets.groupedRow(row, false, position == getCount() - 1); return row;
        }
    }
}
