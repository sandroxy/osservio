package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentExporter;
import com.sandrox.osservio.core.SystemDiagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only frozen evidence. Opening an attachment never queries a live provider. */
@SuppressLint("ViewConstructor")
final class IncidentSnapshotView extends LinearLayout implements InspectorScreen.Content {
    private final IncidentSnapshot snapshot;
    private final EditText search;
    private final TextView footer, status;
    private final ImageButton back, copy;
    private final Button delete;
    private final LinearLayout controls;
    private final List<BreadcrumbEvent> events = new ArrayList<>();
    private final Rows rows = new Rows();
    private InspectorScreen detail;
    private IncidentSharePresentation sharing;
    private ImageButton share;

    IncidentSnapshotView(Context context, IncidentSnapshot snapshot, Runnable goBack, Runnable deleteSnapshot, IncidentExporter exporter) {
        super(context); this.snapshot = snapshot; setOrientation(VERTICAL);
        controls = new LinearLayout(context); controls.setGravity(android.view.Gravity.END);
        back = InspectorWidgets.icon(context, R.drawable.osservio_back, R.string.osservio_ui_back, goBack);
        copy = InspectorWidgets.icon(context, R.drawable.osservio_copy, R.string.osservio_archive_copy_summary, () -> copy(IncidentPresentation.summary(context, snapshot)));
        controls.addView(back); controls.addView(copy); addView(controls, new LayoutParams(-1, -2));
        ListView list = new ListView(context); list.setDivider(null); list.setDividerHeight(0);
        list.setPadding(dp(16), 0, dp(16), dp(16)); list.setClipToPadding(false);
        LinearLayout header = new LinearLayout(context); header.setOrientation(VERTICAL); header.setPadding(0, dp(8), 0, 0);
        status = caption(() -> ""); status.setVisibility(GONE); status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(status, new LayoutParams(-1, -2));
        search = InspectorWidgets.search(context, R.string.osservio_crumb_search, "", ignored -> filter());
        search.setVisibility(snapshot.breadcrumbs == null ? GONE : VISIBLE); header.addView(search, new LayoutParams(-1, -2));
        if (exporter != null) {
            share = InspectorWidgets.icon(context, R.drawable.osservio_share, R.string.osservio_incident_share, () -> { }); controls.addView(share);
            sharing = new IncidentSharePresentation(this, share, exporter, snapshot, message -> status(message == 0 ? "" : context.getString(message)));
        }
        SystemDiagnostic.Kind kind = snapshot.systemDiagnostic == null ? null : snapshot.systemDiagnostic.kind;
        LinearLayout metadata = section(header, () -> IncidentPresentation.title(context, kind));
        field(metadata, () -> IncidentPresentation.timeLabel(context, kind), () -> RuntimePresentation.date(context, snapshot.timestampMillis), null);
        field(metadata, () -> "ID", () -> snapshot.id, null);
        field(metadata, () -> context.getString(R.string.osservio_incident_note), () -> IncidentPresentation.note(context, snapshot.note),
            snapshot.note == null ? null : () -> showText(R.string.osservio_incident_note, IncidentPresentation.note(context, snapshot.note)));
        if (snapshot.systemDiagnostic != null) field(metadata, () -> context.getString(R.string.osservio_diagnostic_title),
            () -> SystemDiagnosticPresentation.kind(context, snapshot.systemDiagnostic.kind),
            () -> open(R.string.osservio_diagnostic_title, new SystemDiagnosticView(context, snapshot.systemDiagnostic)));
        header.addView(caption(() -> IncidentPresentation.hint(context, snapshot)));
        LinearLayout environment = section(header, () -> context.getString(R.string.osservio_runtime_environment));
        if (snapshot.environment == null) header.addView(caption(() -> context.getString(R.string.osservio_incident_not_attached)));
        else field(environment, () -> context.getString(R.string.osservio_runtime_snapshot),
            () -> RuntimePresentation.date(context, snapshot.environment.timestampMillis) + " · " + snapshot.environment.platform.name().toLowerCase(Locale.ROOT),
            () -> open(R.string.osservio_runtime_environment, EnvironmentInspectorView.snapshotView(context, snapshot.environment)));
        LinearLayout attachments = section(header, () -> context.getString(R.string.osservio_incident_evidence_title));
        attachment(attachments, R.string.osservio_logs, snapshot.logs == null ? null : snapshot.logs.events.size(),
            () -> open(R.string.osservio_logs, new IncidentEvidenceView(context, snapshot.logs, () -> { })));
        attachment(attachments, R.string.osservio_ui_http, snapshot.http == null ? null : snapshot.http.events.size(),
            () -> open(R.string.osservio_ui_http, new IncidentEvidenceView(context, snapshot.http, () -> { })));
        attachment(attachments, R.string.osservio_ws_title, snapshot.webSockets == null ? null : snapshot.webSockets.events.size(),
            () -> open(R.string.osservio_ws_title, new IncidentEvidenceView(context, snapshot.webSockets, () -> { })));
        TextView eventsTitle = InspectorWidgets.sectionLabel(context); InspectorAppearance.text(eventsTitle, R.string.osservio_runtime_breadcrumbs); header.addView(eventsTitle);
        header.addView(caption(() -> IncidentPresentation.counts(context, snapshot.breadcrumbs)));
        list.addHeaderView(header, null, false);
        LinearLayout ending = new LinearLayout(context); ending.setOrientation(VERTICAL);
        footer = caption(() -> ""); ending.addView(footer);
        delete = InspectorWidgets.button(context, R.string.osservio_incident_delete, deleteSnapshot);
        InspectorAppearance.style(delete, () -> { delete.setTextColor(InspectorTheme.color(context, R.color.osservio_error)); InspectorTheme.surface(delete, true, 22); });
        LayoutParams deletion = new LayoutParams(-1, -2); deletion.topMargin = dp(24); ending.addView(delete, deletion);
        list.addFooterView(ending, null, false); list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index >= 0 && index < events.size() && detail == null) show(BreadcrumbDetailDialog.create(context, events.get(index)));
        });
        addView(list, new LayoutParams(-1, 0, 1)); InspectorAppearance.content(this, this::filter); filter();
    }
    @Override public void bindScreen(InspectorScreen screen) {
        screen.title(() -> IncidentPresentation.title(getContext(), snapshot.systemDiagnostic == null ? null : snapshot.systemDiagnostic.kind));
        screen.addAction(copy); if (share != null) screen.addAction(share); controls.setVisibility(GONE);
    }
    void busy(boolean busy) {
        if (busy && sharing != null) sharing.cancel();
        if (share != null) share.setEnabled(!busy);
        back.setEnabled(!busy); delete.setEnabled(!busy); status(busy ? getContext().getString(R.string.osservio_archive_deleting) : "");
    }
    private void status(String text) { status.setText(text); status.setVisibility(text.isEmpty() ? GONE : VISIBLE); }
    void error(String message) { status(message); }
    void dismissDialogs() {
        if (sharing != null) sharing.cancel();
        if (!InspectorNavigation.owns(getContext()) && detail != null) detail.dismiss();
    }
    @Override protected void onDetachedFromWindow() { dismissDialogs(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility); if (sharing != null && !isShown()) sharing.cancel();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); if (sharing != null && visibility != VISIBLE) sharing.cancel();
    }
    private LinearLayout section(LinearLayout header, InspectorAppearance.Value<String> title) {
        TextView label = InspectorWidgets.sectionLabel(getContext()); InspectorAppearance.text(label, title); header.addView(label);
        LinearLayout group = InspectorWidgets.group(getContext()); header.addView(group, new LayoutParams(-1, -2)); return group;
    }
    private void field(LinearLayout group, InspectorAppearance.Value<String> title, InspectorAppearance.Value<String> value, Runnable action) {
        InspectorWidgets.ValueRow row = new InspectorWidgets.ValueRow(getContext());
        Runnable render = () -> row.bind(title.get(), value.get(), action != null); InspectorAppearance.content(row, render); render.run();
        row.value.setMaxLines(4); row.value.setEllipsize(android.text.TextUtils.TruncateAt.END); row.action(action); InspectorWidgets.addGrouped(group, row);
    }
    private void attachment(LinearLayout group, int title, Integer count, Runnable open) {
        field(group, () -> getContext().getString(title), () -> IncidentPresentation.count(getContext(), count), count == null ? null : open);
    }
    private void open(int title, View content) { if (detail == null) show(InspectorDialogs.screen(getContext(), title, content)); }
    private void show(InspectorScreen screen) {
        InspectorWidgets.hideKeyboard(this); detail = screen;
        screen.setOnDismissListener(ignored -> { if (detail == screen) detail = null; }); screen.show();
    }
    private void filter() {
        if (footer == null) return;
        events.clear(); String query = search.getText().toString().toLowerCase(Locale.ROOT);
        if (snapshot.breadcrumbs != null) for (int i = snapshot.breadcrumbs.events.size() - 1; i >= 0; i--) {
            BreadcrumbEvent event = snapshot.breadcrumbs.events.get(i);
            if (query.isEmpty() || event.label.toLowerCase(Locale.ROOT).contains(query)
                || RuntimePresentation.kind(getContext(), event.kind).toLowerCase(Locale.ROOT).contains(query)
                || (event.message != null && event.message.toLowerCase(Locale.ROOT).contains(query))) events.add(event);
        }
        footer.setText(events.isEmpty() && snapshot.breadcrumbs != null ? getContext().getString(R.string.osservio_ws_no_matches) : "");
        footer.setVisibility(footer.length() == 0 ? GONE : VISIBLE); rows.notifyDataSetChanged();
    }
    private void showText(int title, String value) {
        InspectorContentReader content = new InspectorContentReader(getContext()); content.display(value, false); open(title, content);
    }
    private void copy(String value) {
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", value));
    }
    private TextView caption(InspectorAppearance.Value<String> value) {
        TextView text = InspectorWidgets.caption(getContext()); InspectorAppearance.text(text, value); text.setPadding(dp(16), dp(8), dp(16), dp(8)); return text;
    }
    private int dp(int value) { return InspectorTheme.dp(getContext(), value); }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return events.size(); }
        @Override public Object getItem(int position) { return events.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.ValueRow row = recycled instanceof InspectorWidgets.ValueRow ? (InspectorWidgets.ValueRow) recycled : new InspectorWidgets.ValueRow(getContext());
            BreadcrumbEvent event = events.get(position);
            row.bind(RuntimePresentation.kind(getContext(), event.kind) + " · " + event.label,
                RuntimePresentation.date(getContext(), event.timestampMillis) + " · #" + event.sequence + (event.message == null ? "" : "\n" + event.message), true);
            row.title.setMaxLines(2); row.title.setEllipsize(android.text.TextUtils.TruncateAt.END); row.value.setMaxLines(3); row.value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            InspectorWidgets.groupedRow(row, position == 0, position == getCount() - 1); return row;
        }
    }
}
