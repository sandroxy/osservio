package com.sandrox.osservio.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sandrox.osservio.core.EnvironmentProvider;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import com.sandrox.osservio.core.IncidentArchive;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.LogMessage;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.WebSocketHistory;
import com.sandrox.osservio.core.IncidentHTTP;
import com.sandrox.osservio.core.IncidentWebSockets;
import com.sandrox.osservio.core.IncidentPayload;
import java.lang.ref.WeakReference;
import java.util.Map;

/** Metadata stays frozen while editing. Opted-in bodies are copied once from those references. */
final class IncidentComposer {
    private final Context context;
    private final IncidentSnapshot mark;
    private final IncidentArchive archive;
    private final EnvironmentProvider provider;
    private final HTTPHistory httpHistory;
    private final WebSocketHistory webSocketHistory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final InspectorScreen dialog;
    private final android.widget.Button saveButton;
    private final android.widget.Button cancelButton;
    private final boolean[] initialOptions;
    private InspectorScreen discardConfirmation;
    private final Switch includeEnvironment;
    private final Switch includeBreadcrumbs;
    private final Switch includeNote;
    private final Switch includeLogs;
    private final Switch includeHTTP;
    private final Switch includeHTTPBodies;
    private final Switch includeWebSockets;
    private final Switch includeWebSocketPayloads;
    private final EditText editor;
    private final TextView environmentStatus;
    private final TextView notice;
    private final TextView httpBodiesStatus;
    private final TextView socketPayloadsStatus;
    private final Runnable saved;
    private EnvironmentSnapshot environment;
    private boolean environmentPending = true;
    private boolean saving;
    private int generation;
    private IncidentHTTP httpWithBodies;
    private IncidentWebSockets socketsWithPayloads;
    private boolean httpContentFailed;
    private boolean socketContentFailed;

    IncidentComposer(Context context, IncidentSnapshot mark, IncidentArchive archive, EnvironmentProvider provider,
                     HTTPHistory httpHistory, WebSocketHistory webSocketHistory, Runnable saved, Runnable dismissed) {
        this.context = context; this.mark = mark; this.archive = archive; this.provider = provider; this.saved = saved;
        this.httpHistory = httpHistory; this.webSocketHistory = webSocketHistory;
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(8), dp(16), dp(16));
        LinearLayout metadata = InspectorWidgets.group(context);
        InspectorWidgets.ValueRow time = new InspectorWidgets.ValueRow(context);
        time.bind(context.getString(R.string.osservio_incident_frozen_time), RuntimePresentation.date(context, mark.timestampMillis), false);
        metadata.addView(time); content.addView(metadata, params());
        content.addView(label(context.getString(R.string.osservio_incident_mark_hint)), params());
        notice = label(""); notice.setVisibility(View.GONE); notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); content.addView(notice, params());
        LinearLayout options = InspectorWidgets.group(context); content.addView(options, params());
        includeEnvironment = option(R.string.osservio_incident_include_environment, true);
        environmentStatus = label(context.getString(R.string.osservio_incident_environment_loading)); addOption(options, includeEnvironment, environmentStatus);
        includeBreadcrumbs = option(R.string.osservio_incident_include_breadcrumbs, true);
        addOption(options, includeBreadcrumbs, label(mark.breadcrumbs == null ? context.getString(R.string.osservio_incident_breadcrumbs_failed) : IncidentPresentation.counts(context, mark.breadcrumbs)));
        includeLogs = option(R.string.osservio_incident_include_logs, mark.logs != null);
        addOption(options, includeLogs, label(mark.logs == null ? context.getString(R.string.osservio_incident_window_unavailable)
            : IncidentPresentation.window(context, mark.logs.observedAtMillis, mark.logs.events.size(), mark.logs.omittedEventCount, mark.logs.sourceEvictedCount, null)));
        includeHTTP = option(R.string.osservio_incident_include_http, mark.http != null);
        addOption(options, includeHTTP, label(mark.http == null ? context.getString(R.string.osservio_incident_window_unavailable)
            : IncidentPresentation.window(context, mark.http.observedAtMillis, mark.http.events.size(), mark.http.omittedEventCount, mark.http.sourceEvictedEvents, mark.http.sourceEvictedBodies)));
        includeHTTPBodies = option(R.string.osservio_incident_include_http_bodies, false);
        httpBodiesStatus = label(context.getString(mark.http != null && !mark.http.payloads.isEmpty() && httpHistory != null
            ? R.string.osservio_incident_body_choice_hint : R.string.osservio_incident_body_none)); addOption(options, includeHTTPBodies, httpBodiesStatus);
        includeWebSockets = option(R.string.osservio_incident_include_websockets, mark.webSockets != null);
        addOption(options, includeWebSockets, label(mark.webSockets == null ? context.getString(R.string.osservio_incident_window_unavailable)
            : IncidentPresentation.window(context, mark.webSockets.observedAtMillis, mark.webSockets.events.size(), mark.webSockets.omittedEventCount, mark.webSockets.sourceEvictedEvents, mark.webSockets.sourceEvictedPayloads)));
        includeWebSocketPayloads = option(R.string.osservio_incident_include_ws_payloads, false);
        socketPayloadsStatus = label(context.getString(mark.webSockets != null && !mark.webSockets.payloads.isEmpty() && webSocketHistory != null
            ? R.string.osservio_incident_body_choice_hint : R.string.osservio_incident_body_none)); addOption(options, includeWebSocketPayloads, socketPayloadsStatus);
        includeNote = option(R.string.osservio_incident_include_note, false); addOption(options, includeNote, null);
        editor = new EditText(context); editor.setHint(R.string.osservio_incident_note); editor.setMinLines(3); editor.setMaxLines(6);
        InspectorTheme.typography(editor, 16, false); editor.setPadding(dp(16), dp(12), dp(16), dp(12));
        InspectorAppearance.style(editor, () -> { editor.setTextColor(InspectorTheme.primary(context)); editor.setHintTextColor(InspectorTheme.secondary(context)); InspectorTheme.surface(editor, false, 20); });
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI); editor.setVisibility(View.GONE);
        editor.setFilters(new InputFilter[] {(source, start, end, destination, replacedStart, replacedEnd) -> {
            // Bound before constructing the replacement. Returning the selected destination preserves
            // existing text when a replacement is refused, including its composing spans.
            if (end - start > 4096 || destination.length() > 4096) {
                editor.setError(context.getString(R.string.osservio_incident_note_limit));
                return destination.subSequence(replacedStart, replacedEnd);
            }
            StringBuilder proposed = new StringBuilder(destination.length() + end - start);
            proposed.append(destination, 0, replacedStart).append(source, start, end).append(destination, replacedEnd, destination.length());
            if (!validNote(proposed)) {
                editor.setError(context.getString(R.string.osservio_incident_note_limit));
                return destination.subSequence(replacedStart, replacedEnd);
            }
            editor.setError(null); return null;
        }});
        content.addView(editor, params());
        content.addView(label(context.getString(R.string.osservio_incident_note_hint) + "\n" + context.getString(R.string.osservio_incident_note_limit)), params());
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(true); scroll.addView(content);
        dialog = InspectorDialogs.panel(context, R.string.osservio_incident_mark, scroll);
        cancelButton = dialog.leadingAction(R.string.osservio_archive_cancel, dialog::cancel);
        saveButton = dialog.action(R.string.osservio_incident_save, this::save);
        initialOptions = new boolean[] {true, true, mark.logs != null, mark.http != null, false, mark.webSockets != null, false, false};
        dialog.setBackInterceptor(this::confirmDiscard);
        includeEnvironment.setOnCheckedChangeListener((button, checked) -> updateControls());
        includeHTTP.setOnCheckedChangeListener((button, checked) -> updateControls());
        includeWebSockets.setOnCheckedChangeListener((button, checked) -> updateControls());
        includeHTTPBodies.setOnCheckedChangeListener((button, checked) -> {
            if (checked && httpWithBodies == null && mark.http != null && httpHistory != null) {
                try {
                    httpWithBodies = mark.http.includingBodies(httpHistory, 12288);
                    httpBodiesStatus.setText(contentStatus(httpWithBodies.payloads, httpWithBodies.retainedPayloadBytes));
                } catch (IllegalArgumentException failure) {
                    httpContentFailed = true; includeHTTPBodies.setChecked(false); httpBodiesStatus.setText(R.string.osservio_incident_body_failed);
                }
            }
            updateControls();
        });
        includeWebSocketPayloads.setOnCheckedChangeListener((button, checked) -> {
            if (checked && socketsWithPayloads == null && mark.webSockets != null && webSocketHistory != null) {
                try {
                    socketsWithPayloads = mark.webSockets.includingPayloads(webSocketHistory, 12288);
                    socketPayloadsStatus.setText(contentStatus(socketsWithPayloads.payloads, socketsWithPayloads.retainedPayloadBytes));
                } catch (IllegalArgumentException failure) {
                    socketContentFailed = true; includeWebSocketPayloads.setChecked(false); socketPayloadsStatus.setText(R.string.osservio_incident_body_failed);
                }
            }
            updateControls();
        });
        includeNote.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) editor.clearFocus(); editor.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        dialog.setOnShowListener(ignored -> {
            updateControls(); readEnvironment();
        });
        dialog.setOnDismissListener(ignored -> {
            generation++;
            if (discardConfirmation != null) discardConfirmation.dismiss();
            dismissed.run();
        });
    }
    void show() { dialog.show(); }
    void dismiss() { dialog.dismiss(); }
    private boolean hasChanges() {
        Switch[] options = {includeEnvironment, includeBreadcrumbs, includeLogs, includeHTTP, includeHTTPBodies, includeWebSockets, includeWebSocketPayloads, includeNote};
        for (int index = 0; index < options.length; index++) if (options[index].isChecked() != initialOptions[index]) return true;
        return editor.length() != 0;
    }
    private boolean confirmDiscard() {
        if (saving || discardConfirmation != null) return true;
        if (!hasChanges()) return false;
        InspectorWidgets.hideKeyboard(dialog.view);
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(16));
        content.addView(label(context.getString(R.string.osservio_incident_discard_hint)), params());
        ScrollView scroll = new ScrollView(context); scroll.addView(content);
        InspectorScreen confirmation = InspectorDialogs.panel(context, R.string.osservio_incident_discard_title, scroll);
        confirmation.compact();
        LinearLayout choices = InspectorWidgets.group(context); content.addView(choices, params());
        android.widget.Button keep = InspectorWidgets.button(context, R.string.osservio_incident_keep_editing, confirmation::dismiss);
        android.widget.Button discard = InspectorWidgets.button(context, R.string.osservio_incident_discard,
            () -> { confirmation.dismiss(); dialog.dismiss(); });
        InspectorWidgets.addGrouped(choices, keep); InspectorWidgets.addGrouped(choices, discard);
        InspectorAppearance.style(discard, () -> {
            discard.setTextColor(InspectorTheme.color(context, R.color.osservio_error)); InspectorTheme.feedback(discard, 20);
        });
        discardConfirmation = confirmation;
        confirmation.setOnDismissListener(ignored -> { if (discardConfirmation == confirmation) discardConfirmation = null; });
        confirmation.show(); return true;
    }
    private void readEnvironment() {
        int request = ++generation;
        WeakReference<IncidentComposer> reference = new WeakReference<>(this); Handler handler = main;
        provider.read((value, error) -> handler.post(() -> {
            IncidentComposer composer = reference.get();
            if (composer == null || composer.generation != request || !composer.environmentPending || !composer.dialog.isShowing()) return;
            composer.environmentPending = false;
            composer.environment = error == null ? value : null; composer.environmentReceived();
        }));
        handler.postDelayed(() -> {
            IncidentComposer composer = reference.get();
            if (composer == null || composer.generation != request || !composer.environmentPending || !composer.dialog.isShowing()) return;
            composer.environmentPending = false; composer.generation++; composer.environmentReceived();
        }, 5000);
    }
    private void environmentReceived() {
        environmentStatus.setText(environment == null ? context.getString(R.string.osservio_incident_environment_failed)
            : RuntimePresentation.date(context, environment.timestampMillis));
        updateControls();
    }
    private void updateControls() {
        if (!dialog.isShowing()) return;
        saveButton.setEnabled(!saving && (!includeEnvironment.isChecked() || !environmentPending));
        cancelButton.setEnabled(!saving);
        dialog.setCancelable(!saving);
        includeEnvironment.setEnabled(!saving); includeBreadcrumbs.setEnabled(!saving); includeNote.setEnabled(!saving); editor.setEnabled(!saving);
        includeLogs.setEnabled(!saving && mark.logs != null);
        includeHTTP.setEnabled(!saving && mark.http != null); includeWebSockets.setEnabled(!saving && mark.webSockets != null);
        includeHTTPBodies.setEnabled(!saving && includeHTTP.isChecked() && httpHistory != null && mark.http != null && !mark.http.payloads.isEmpty() && !httpContentFailed);
        includeWebSocketPayloads.setEnabled(!saving && includeWebSockets.isChecked() && webSocketHistory != null && mark.webSockets != null && !mark.webSockets.payloads.isEmpty() && !socketContentFailed);
    }
    private String contentStatus(Map<String, IncidentPayload> payloads, int bytes) {
        int available = 0; for (IncidentPayload value : payloads.values()) if (value.state == IncidentPayload.State.AVAILABLE) available++;
        return context.getString(R.string.osservio_incident_body_selected, available, payloads.size(), bytes)
            + "\n" + context.getString(R.string.osservio_incident_body_choice_hint);
    }
    private void save() {
        if (saving || (includeEnvironment.isChecked() && environmentPending)) return;
        IncidentSnapshot snapshot;
        try {
            IncidentSnapshot.Note note = includeNote.isChecked() && editor.length() != 0
                ? IncidentSnapshot.Note.capture(LogMessage.publicText(editor.getText().toString())) : null;
            snapshot = IncidentSnapshot.restore(mark.id, mark.timestampMillis, note, includeEnvironment.isChecked() ? environment : null,
                includeBreadcrumbs.isChecked() ? mark.breadcrumbs : null, includeLogs.isChecked() ? mark.logs : null,
                includeHTTP.isChecked() ? (includeHTTPBodies.isChecked() ? httpWithBodies : mark.http) : null,
                includeWebSockets.isChecked() ? (includeWebSocketPayloads.isChecked() ? socketsWithPayloads : mark.webSockets) : null);
        } catch (IllegalArgumentException failure) { notice(context.getString(R.string.osservio_incident_mark_failed)); return; }
        saving = true; notice(context.getString(R.string.osservio_incident_saving)); updateControls();
        WeakReference<IncidentComposer> reference = new WeakReference<>(this); Handler handler = main;
        archive.save(snapshot, error -> handler.post(() -> {
            IncidentComposer composer = reference.get(); if (composer == null || !composer.dialog.isShowing()) return;
            composer.saving = false; composer.updateControls();
            if (error == null) { composer.editor.setText(""); composer.saved.run(); composer.dialog.dismiss(); }
            else composer.notice(PersistencePresentation.error(composer.context, error));
        }));
    }
    static boolean validNote(CharSequence text) {
        int bytes = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) return false;
                bytes += 4;
            } else if (Character.isLowSurrogate(value)) return false;
            else bytes += value < 128 ? 1 : value < 2048 ? 2 : 3;
            if (bytes > 4096) return false;
        }
        return true;
    }
    private Switch option(int label, boolean checked) {
        Switch option = new Switch(context); option.setText(label); option.setContentDescription(context.getString(label)); option.setChecked(checked);
        option.setMinHeight(dp(56)); option.setPadding(dp(16), dp(12), dp(16), dp(8));
        InspectorAppearance.style(option, () -> { InspectorWidgets.styleSwitch(option); option.setBackground(null); }); return option;
    }
    private void addOption(LinearLayout group, Switch option, TextView detail) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.VERTICAL); row.addView(option, params());
        if (detail != null) { detail.setPadding(dp(16), 0, dp(16), dp(12)); row.addView(detail, params()); }
        InspectorWidgets.addGrouped(group, row);
    }
    private void notice(String value) { notice.setText(value); notice.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE); }
    private TextView label(String value) {
        TextView text = InspectorWidgets.caption(context); text.setTextSize(12);
        text.setText(value); text.setPadding(dp(16), dp(8), dp(16), dp(12)); return text;
    }
    private LinearLayout.LayoutParams params() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
