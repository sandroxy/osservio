package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
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
final class IncidentSnapshotView extends LinearLayout {
    private final IncidentSnapshot snapshot;
    private final EditText search;
    private final TextView footer;
    private final TextView status;
    private final android.widget.ImageButton back;
    private final Button delete;
    private final List<BreadcrumbEvent> events = new ArrayList<>();
    private final Rows rows = new Rows();
    private InspectorScreen detail;
    private View evidence;
    private IncidentSharePresentation sharing;
    private Button share;

    IncidentSnapshotView(Context context, IncidentSnapshot snapshot, Runnable goBack, Runnable deleteSnapshot, IncidentExporter exporter) {
        super(context); this.snapshot = snapshot; setOrientation(VERTICAL);
        LinearLayout controls = new LinearLayout(context);
        back = InspectorWidgets.icon(context, R.drawable.osservio_back, R.string.osservio_ui_back, () -> { }); back.setOnClickListener(view -> goBack.run());
        android.widget.ImageButton copy = InspectorWidgets.icon(context, R.drawable.osservio_copy, R.string.osservio_archive_copy_summary, () -> { }); copy.setOnClickListener(view -> copy(IncidentPresentation.summary(context, snapshot)));
        delete = button(R.string.osservio_incident_delete); delete.setOnClickListener(view -> deleteSnapshot.run());
        Button menu = InspectorWidgets.button(context, R.string.osservio_ui_actions, () -> {
            detail = InspectorDialogs.menu(context, R.string.osservio_ui_actions, new String[] {context.getString(R.string.osservio_incident_delete)}, (dialog, which) -> {
                    dialog.dismiss(); detail = null; delete.performClick();
                });
            InspectorScreen shown = detail; shown.setOnDismissListener(ignored -> { if (detail == shown) detail = null; });
        });
        InspectorAppearance.text(menu, () -> "⋯"); menu.setTextSize(22); menu.setContentDescription(context.getString(R.string.osservio_ui_actions));
        back.setVisibility(InspectorNavigation.owns(context) ? GONE : VISIBLE); controls.addView(back);
        View spacer = new View(context); controls.addView(spacer, new LayoutParams(0, 1, 1)); controls.addView(copy);
        controls.addView(menu, new LayoutParams(dp(48), -2));
        addView(controls, new LayoutParams(-1, -2));
        status = caption(""); status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE); addView(status, new LayoutParams(-1, -2));
        search = InspectorWidgets.search(context, R.string.osservio_crumb_search, "", ignored -> { });
        search.setVisibility(snapshot.breadcrumbs == null ? GONE : VISIBLE);
        ListView list = new ListView(context);
        LinearLayout header = new LinearLayout(context); header.setOrientation(VERTICAL);
        header.setPadding(dp(16), 0, dp(16), 0);
        SystemDiagnostic.Kind kind = snapshot.systemDiagnostic == null ? null : snapshot.systemDiagnostic.kind;
        TextView metadata = caption(() -> IncidentPresentation.title(context, kind) + "\n" + IncidentPresentation.timeLabel(context, kind)
            + " · " + RuntimePresentation.date(getContext(), snapshot.timestampMillis) + "\nID: " + snapshot.id);
        metadata.setTextIsSelectable(true); header.addView(metadata, new LayoutParams(-1, -2));
        TextView note = InspectorTheme.text(context, 18, true); InspectorAppearance.text(note, () -> IncidentPresentation.note(context, snapshot.note));
        note.setPadding(0, dp(8), 0, dp(8)); note.setMaxLines(4); note.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (snapshot.note != null) {
            note.setContentDescription(context.getString(R.string.osservio_incident_note) + ": " + IncidentPresentation.note(context, snapshot.note));
            note.setOnClickListener(view -> showText(R.string.osservio_incident_note, IncidentPresentation.note(context, snapshot.note)));
        }
        header.addView(note, new LayoutParams(-1, -2));
        if (exporter != null) {
            share = button(R.string.osservio_incident_share); InspectorWidgets.chip(share, true); header.addView(share, new LayoutParams(-1, -2));
            sharing = new IncidentSharePresentation(this, share, exporter, snapshot,
                message -> status.setText(message == 0 ? "" : context.getString(message)));
        }
        if (snapshot.systemDiagnostic != null) {
            Button diagnostic = button(R.string.osservio_diagnostic_title);
            diagnostic.setOnClickListener(view -> showText(R.string.osservio_diagnostic_title, SystemDiagnosticPresentation.summary(context, snapshot.systemDiagnostic)));
            header.addView(diagnostic, new LayoutParams(-1, -2));
            if (snapshot.systemDiagnostic.appleSignal != null) {
                com.sandrox.osservio.core.AppleSignalCrash crash = snapshot.systemDiagnostic.appleSignal;
                for (boolean images : new boolean[] {false, true}) {
                    Button addresses = button(images ? R.string.osservio_crash_images : R.string.osservio_crash_frames);
                    addresses.setEnabled(!(images ? crash.images.isEmpty() : crash.frames.isEmpty()));
                    addresses.setOnClickListener(view -> openEvidence(new SignalCrashView(context, crash, images, this::closeEvidence)));
                    header.addView(addresses, new LayoutParams(-1, -2));
                }
            }
            if (snapshot.systemDiagnostic.androidExit != null && snapshot.systemDiagnostic.androidExit.tombstone != null) {
                com.sandrox.osservio.core.AndroidTombstone trace = snapshot.systemDiagnostic.androidExit.tombstone;
                Button addresses = button(R.string.osservio_crash_frames); addresses.setEnabled(!trace.frames.isEmpty());
                addresses.setOnClickListener(view -> openEvidence(new SignalCrashView(context, trace, this::closeEvidence)));
                header.addView(addresses, new LayoutParams(-1, -2));
            }
        }
        Button environment = button(R.string.osservio_runtime_environment);
        environment.setEnabled(snapshot.environment != null);
        environment.setOnClickListener(view -> {
            if (snapshot.environment != null) {
                detail = InspectorDialogs.screen(context, R.string.osservio_runtime_environment, EnvironmentInspectorView.snapshotView(context, snapshot.environment));
                detail.setOnDismissListener(ignored -> detail = null); detail.show();
            }
        });
        header.addView(environment, new LayoutParams(-1, -2));
        header.addView(caption(() -> snapshot.environment == null ? context.getString(R.string.osservio_incident_not_attached) : RuntimePresentation.date(getContext(), snapshot.environment.timestampMillis)), new LayoutParams(-1, -2));
        header.addView(caption(() -> context.getString(R.string.osservio_incident_evidence_title)), new LayoutParams(-1, -2));
        attachment(header, () -> context.getString(R.string.osservio_logs), snapshot.logs == null ? null : snapshot.logs.events.size(),
            () -> openEvidence(new IncidentEvidenceView(context, snapshot.logs, this::closeEvidence)));
        attachment(header, () -> "HTTP", snapshot.http == null ? null : snapshot.http.events.size(),
            () -> openEvidence(new IncidentEvidenceView(context, snapshot.http, this::closeEvidence)));
        attachment(header, () -> context.getString(R.string.osservio_ws_title), snapshot.webSockets == null ? null : snapshot.webSockets.events.size(),
            () -> openEvidence(new IncidentEvidenceView(context, snapshot.webSockets, this::closeEvidence)));
        header.addView(caption(() -> IncidentPresentation.counts(context, snapshot.breadcrumbs)), new LayoutParams(-1, -2));
        header.addView(search, new LayoutParams(-1, -2));
        list.addHeaderView(header, null, false);
        list.setDivider(new android.graphics.drawable.ColorDrawable(InspectorTheme.color(context, R.color.osservio_separator))); list.setDividerHeight(dp(1));
        footer = caption(""); list.addFooterView(footer, null, false); list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index >= 0 && index < events.size() && detail == null) {
                detail = BreadcrumbDetailDialog.create(context, events.get(index));
                InspectorScreen shown = detail; shown.setOnDismissListener(ignored -> { if (detail == shown) detail = null; }); shown.show();
            }
        });
        addView(list, new LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        InspectorAppearance.content(this, () -> {
            menu.setContentDescription(context.getString(R.string.osservio_ui_actions));
            if (snapshot.note != null) note.setContentDescription(context.getString(R.string.osservio_incident_note) + ": " + IncidentPresentation.note(context, snapshot.note));
            list.setDivider(new android.graphics.drawable.ColorDrawable(InspectorTheme.color(context, R.color.osservio_separator)));
            filter();
        });
        filter();
    }
    void busy(boolean busy) {
        if (busy && sharing != null) sharing.cancel();
        if (share != null) share.setEnabled(!busy);
        back.setEnabled(!busy); delete.setEnabled(!busy);
        status.setText(busy ? getContext().getString(R.string.osservio_archive_deleting) : "");
    }
    void error(String message) { status.setText(message); }
    boolean navigateBack() {
        if (evidence == null) return false;
        closeEvidence(); return true;
    }
    void dismissDialogs() {
        if (sharing != null) sharing.cancel();
        if (InspectorNavigation.owns(getContext())) return;
        if (detail != null) detail.dismiss(); if (evidence instanceof IncidentEvidenceView) ((IncidentEvidenceView) evidence).dismissDialogs();
    }
    @Override protected void onDetachedFromWindow() { dismissDialogs(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility); if (sharing != null && !isShown()) sharing.cancel();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); if (sharing != null && visibility != VISIBLE) sharing.cancel();
    }
    private void attachment(LinearLayout header, InspectorAppearance.Value<String> title, Integer count, Runnable open) {
        Button button = button(R.string.osservio_ui_read_content); InspectorAppearance.text(button, () -> getContext().getString(R.string.osservio_incident_attachment_count, title.get(), IncidentPresentation.count(getContext(), count)));
        button.setAllCaps(false); button.setMinHeight(dp(48)); button.setEnabled(count != null);
        button.setOnClickListener(view -> open.run()); header.addView(button, new LayoutParams(-1, -2));
    }
    private void openEvidence(View selected) {
        if (sharing != null) sharing.cancel();
        hideKeyboard();
        for (int index = 0; index < getChildCount(); index++) getChildAt(index).setVisibility(GONE);
        evidence = selected; addView(evidence, new LayoutParams(-1, -1));
    }
    private void closeEvidence() {
        hideKeyboard();
        if (evidence != null) { removeView(evidence); evidence = null; }
        for (int index = 0; index < getChildCount(); index++) getChildAt(index).setVisibility(VISIBLE);
        search.setVisibility(snapshot.breadcrumbs == null ? GONE : VISIBLE);
    }
    private void hideKeyboard() {
        InspectorWidgets.hideKeyboard(this);
    }
    private void filter() {
        events.clear(); String query = search.getText().toString().toLowerCase(Locale.ROOT);
        if (snapshot.breadcrumbs != null) for (int i = snapshot.breadcrumbs.events.size() - 1; i >= 0; i--) {
            BreadcrumbEvent event = snapshot.breadcrumbs.events.get(i);
            if (query.isEmpty() || event.label.toLowerCase(Locale.ROOT).contains(query)
                || RuntimePresentation.kind(getContext(), event.kind).toLowerCase(Locale.ROOT).contains(query)
                || (event.message != null && event.message.toLowerCase(Locale.ROOT).contains(query))) events.add(event);
        }
        footer.setText(events.isEmpty() ? getContext().getString(R.string.osservio_incident_empty_detail,
            getContext().getString(snapshot.breadcrumbs == null ? R.string.osservio_incident_not_attached : R.string.osservio_ws_no_matches),
            IncidentPresentation.hint(getContext(), snapshot)) : IncidentPresentation.hint(getContext(), snapshot));
        rows.notifyDataSetChanged();
    }
    private void showText(int title, String value) {
        if (detail != null) return;
        InspectorContentReader content = new InspectorContentReader(getContext()); content.display(value, false);
        InspectorScreen dialog = InspectorDialogs.screen(getContext(), title, content);
        dialog.setOnDismissListener(ignored -> detail = null); detail = dialog; dialog.show();
    }
    private void copy(String value) {
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", value));
    }
    private Button button(int label) {
        Button button = InspectorWidgets.button(getContext(), label, () -> { }); button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        InspectorAppearance.style(button, () -> { button.setTextColor(InspectorTheme.primary(getContext())); InspectorTheme.surface(button, true, 10); }); button.setPadding(dp(12), dp(8), dp(12), dp(8)); return button;
    }
    private TextView caption(String value) { return caption(() -> value); }
    private TextView caption(InspectorAppearance.Value<String> value) {
        TextView text = InspectorWidgets.caption(getContext());
        InspectorAppearance.text(text, value); text.setPadding(0, dp(8), 0, dp(8)); return text;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return events.size(); }
        @Override public Object getItem(int position) { return events.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.EventRow row = recycled instanceof InspectorWidgets.EventRow ? (InspectorWidgets.EventRow) recycled : new InspectorWidgets.EventRow(getContext());
            BreadcrumbEvent event = events.get(position);
            row.bind(RuntimePresentation.kind(getContext(), event.kind), event.label, event.message == null ? "#" + event.sequence : event.message,
                event.timestampMillis, InspectorTheme.accent(getContext())); return row;
        }
    }
}
