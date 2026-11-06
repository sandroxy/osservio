package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
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
    private final Button back;
    private final Button delete;
    private final List<BreadcrumbEvent> events = new ArrayList<>();
    private final Rows rows = new Rows();
    private AlertDialog detail;
    private IncidentEvidenceView evidence;

    IncidentSnapshotView(Context context, IncidentSnapshot snapshot, Runnable goBack, Runnable deleteSnapshot) {
        super(context); this.snapshot = snapshot; setOrientation(VERTICAL);
        LinearLayout controls = new LinearLayout(context);
        back = button(R.string.osservio_incident_title); back.setOnClickListener(view -> goBack.run());
        Button copy = button(R.string.osservio_archive_copy_summary); copy.setOnClickListener(view -> copy(IncidentPresentation.summary(context, snapshot)));
        delete = button(R.string.osservio_incident_delete); delete.setOnClickListener(view -> deleteSnapshot.run());
        for (Button button : new Button[] {back, copy, delete}) controls.addView(button, new LayoutParams(0, -2, 1));
        addView(controls, new LayoutParams(-1, -2));
        status = caption(""); status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE); addView(status, new LayoutParams(-1, -2));
        search = new EditText(context); search.setSingleLine(true); search.setHint(R.string.osservio_crumb_search); search.setMinHeight(dp(48));
        addView(search, new LayoutParams(-1, -2));
        ListView list = new ListView(context);
        LinearLayout header = new LinearLayout(context); header.setOrientation(VERTICAL);
        TextView metadata = caption(context.getString(R.string.osservio_incident_frozen_time) + " · " + RuntimePresentation.date(snapshot.timestampMillis) + "\nID: " + snapshot.id);
        metadata.setTextIsSelectable(true); header.addView(metadata, new LayoutParams(-1, -2));
        TextView note = new TextView(context); note.setTextAppearance(android.R.style.TextAppearance_Material_Body1); note.setText(IncidentPresentation.note(context, snapshot.note));
        note.setPadding(0, dp(8), 0, dp(8)); note.setMaxLines(4); note.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (snapshot.note != null) {
            note.setContentDescription(context.getString(R.string.osservio_incident_note) + ": " + IncidentPresentation.note(context, snapshot.note));
            note.setOnClickListener(view -> showText(R.string.osservio_incident_note, IncidentPresentation.note(context, snapshot.note)));
        }
        header.addView(note, new LayoutParams(-1, -2));
        Button environment = button(R.string.osservio_runtime_environment);
        environment.setEnabled(snapshot.environment != null);
        environment.setOnClickListener(view -> { if (snapshot.environment != null) showText(R.string.osservio_runtime_environment, RuntimePresentation.environment(context, snapshot.environment)); });
        header.addView(environment, new LayoutParams(-1, -2));
        header.addView(caption(snapshot.environment == null ? context.getString(R.string.osservio_incident_not_attached) : RuntimePresentation.date(snapshot.environment.timestampMillis)), new LayoutParams(-1, -2));
        header.addView(caption(context.getString(R.string.osservio_incident_evidence_title)), new LayoutParams(-1, -2));
        attachment(header, context.getString(R.string.osservio_logs), snapshot.logs == null ? null : snapshot.logs.events.size(),
            () -> openEvidence(new IncidentEvidenceView(context, snapshot.logs, this::closeEvidence)));
        attachment(header, "HTTP", snapshot.http == null ? null : snapshot.http.events.size(),
            () -> openEvidence(new IncidentEvidenceView(context, snapshot.http, this::closeEvidence)));
        attachment(header, context.getString(R.string.osservio_ws_title), snapshot.webSockets == null ? null : snapshot.webSockets.events.size(),
            () -> openEvidence(new IncidentEvidenceView(context, snapshot.webSockets, this::closeEvidence)));
        header.addView(caption(IncidentPresentation.counts(context, snapshot.breadcrumbs)), new LayoutParams(-1, -2));
        list.addHeaderView(header, null, false);
        footer = caption(""); list.addFooterView(footer, null, false); list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index >= 0 && index < events.size()) showText(R.string.osservio_runtime_breadcrumbs, RuntimePresentation.breadcrumb(context, events.get(index)));
        });
        addView(list, new LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        filter();
    }
    void busy(boolean busy) {
        back.setEnabled(!busy); delete.setEnabled(!busy);
        status.setText(busy ? getContext().getString(R.string.osservio_archive_deleting) : "");
    }
    void error(String message) { status.setText(message); }
    void dismissDialogs() { if (detail != null) detail.dismiss(); if (evidence != null) evidence.dismissDialogs(); }
    @Override protected void onDetachedFromWindow() { dismissDialogs(); super.onDetachedFromWindow(); }
    private void attachment(LinearLayout header, String title, Integer count, Runnable open) {
        Button button = new Button(getContext()); button.setText(getContext().getString(R.string.osservio_incident_attachment_count, title, IncidentPresentation.count(getContext(), count)));
        button.setAllCaps(false); button.setMinHeight(dp(48)); button.setEnabled(count != null);
        button.setOnClickListener(view -> open.run()); header.addView(button, new LayoutParams(-1, -2));
    }
    private void openEvidence(IncidentEvidenceView selected) {
        hideKeyboard();
        for (int index = 0; index < getChildCount(); index++) getChildAt(index).setVisibility(GONE);
        evidence = selected; addView(evidence, new LayoutParams(-1, -1));
    }
    private void closeEvidence() {
        hideKeyboard();
        if (evidence != null) { removeView(evidence); evidence = null; }
        for (int index = 0; index < getChildCount(); index++) getChildAt(index).setVisibility(VISIBLE);
    }
    private void hideKeyboard() {
        View focused = findFocus(); if (focused != null) focused.clearFocus();
        android.view.inputmethod.InputMethodManager keyboard = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
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
            getContext().getString(R.string.osservio_incident_snapshot_hint)) : getContext().getString(R.string.osservio_incident_snapshot_hint));
        rows.notifyDataSetChanged();
    }
    private void showText(int title, String value) {
        if (detail != null) return;
        TextView content = new TextView(getContext()); content.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        content.setText(value); content.setTextIsSelectable(true); content.setPadding(dp(16), dp(16), dp(16), dp(16));
        ScrollView scroll = new ScrollView(getContext()); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle(title).setView(scroll)
            .setNegativeButton(R.string.osservio_close, null).setPositiveButton(R.string.osservio_crumb_copy, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> copy(value)));
        dialog.setOnDismissListener(ignored -> detail = null); detail = dialog; dialog.show();
    }
    private void copy(String value) {
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", value));
    }
    private Button button(int label) { Button button = new Button(getContext()); button.setText(label); button.setAllCaps(false); button.setMinHeight(dp(48)); return button; }
    private TextView caption(String value) {
        TextView text = new TextView(getContext()); text.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        text.setText(value); text.setPadding(0, dp(8), 0, dp(8)); return text;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return events.size(); }
        @Override public Object getItem(int position) { return events.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = new LinearLayout(getContext()); row.setOrientation(VERTICAL); row.setPadding(dp(4), dp(12), dp(4), dp(12));
                TextView title = new TextView(getContext()); title.setTextAppearance(android.R.style.TextAppearance_Material_Body1); title.setMaxLines(2);
                TextView subtitle = caption(""); subtitle.setMaxLines(3);
                row.addView(title, new LayoutParams(-1, -2)); row.addView(subtitle, new LayoutParams(-1, -2));
            }
            BreadcrumbEvent event = events.get(position);
            ((TextView) row.getChildAt(0)).setText(getContext().getString(R.string.osservio_incident_breadcrumb_title, RuntimePresentation.kind(getContext(), event.kind), event.label));
            ((TextView) row.getChildAt(1)).setText(getContext().getString(R.string.osservio_incident_breadcrumb_detail,
                RuntimePresentation.date(event.timestampMillis), event.sequence, event.message == null ? "" : "\n" + event.message));
            return row;
        }
    }
}
