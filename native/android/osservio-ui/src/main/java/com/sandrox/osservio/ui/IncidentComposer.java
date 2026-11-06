package com.sandrox.osservio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sandrox.osservio.core.EnvironmentProvider;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import com.sandrox.osservio.core.IncidentArchive;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.LogMessage;
import java.lang.ref.WeakReference;

/** One explicit manual draft. Its original time and breadcrumb window never move while editing. */
final class IncidentComposer {
    private final Context context;
    private final IncidentSnapshot mark;
    private final IncidentArchive archive;
    private final EnvironmentProvider provider;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AlertDialog dialog;
    private final CheckBox includeEnvironment;
    private final CheckBox includeBreadcrumbs;
    private final CheckBox includeNote;
    private final EditText editor;
    private final TextView environmentStatus;
    private final TextView notice;
    private final Runnable saved;
    private EnvironmentSnapshot environment;
    private boolean environmentPending = true;
    private boolean saving;
    private int generation;

    IncidentComposer(Context context, IncidentSnapshot mark, IncidentArchive archive, EnvironmentProvider provider, Runnable saved, Runnable dismissed) {
        this.context = context; this.mark = mark; this.archive = archive; this.provider = provider; this.saved = saved;
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(8), dp(20), dp(16));
        content.addView(label(context.getString(R.string.osservio_incident_frozen_time) + " · " + RuntimePresentation.date(mark.timestampMillis)), params());
        content.addView(label(context.getString(R.string.osservio_incident_mark_hint)), params());
        includeEnvironment = option(R.string.osservio_incident_include_environment, true); content.addView(includeEnvironment, params());
        environmentStatus = label(context.getString(R.string.osservio_incident_environment_loading)); content.addView(environmentStatus, params());
        includeBreadcrumbs = option(R.string.osservio_incident_include_breadcrumbs, true); content.addView(includeBreadcrumbs, params());
        content.addView(label(mark.breadcrumbs == null ? context.getString(R.string.osservio_incident_breadcrumbs_failed) : IncidentPresentation.counts(context, mark.breadcrumbs)), params());
        includeNote = option(R.string.osservio_incident_include_note, false); content.addView(includeNote, params());
        editor = new EditText(context); editor.setHint(R.string.osservio_incident_note); editor.setMinLines(3); editor.setMaxLines(6);
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
        notice = label(""); notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); content.addView(notice, params());
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(true); scroll.addView(content);
        dialog = new AlertDialog.Builder(context).setTitle(R.string.osservio_incident_mark).setView(scroll)
            .setNegativeButton(R.string.osservio_archive_cancel, null).setPositiveButton(R.string.osservio_incident_save, null).create();
        includeEnvironment.setOnCheckedChangeListener((button, checked) -> updateControls());
        includeNote.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) editor.clearFocus(); editor.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> save()); updateControls(); readEnvironment();
        });
        dialog.setOnDismissListener(ignored -> { generation++; dismissed.run(); });
    }
    void show() { dialog.show(); }
    void dismiss() { dialog.dismiss(); }
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
            : RuntimePresentation.date(environment.timestampMillis));
        updateControls();
    }
    private void updateControls() {
        if (!dialog.isShowing()) return;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!saving && (!includeEnvironment.isChecked() || !environmentPending));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!saving);
        dialog.setCancelable(!saving); dialog.setCanceledOnTouchOutside(!saving);
        includeEnvironment.setEnabled(!saving); includeBreadcrumbs.setEnabled(!saving); includeNote.setEnabled(!saving); editor.setEnabled(!saving);
    }
    private void save() {
        if (saving || (includeEnvironment.isChecked() && environmentPending)) return;
        IncidentSnapshot snapshot;
        try {
            IncidentSnapshot.Note note = includeNote.isChecked() && editor.length() != 0
                ? IncidentSnapshot.Note.capture(LogMessage.publicText(editor.getText().toString())) : null;
            snapshot = IncidentSnapshot.restore(mark.id, mark.timestampMillis, note, includeEnvironment.isChecked() ? environment : null,
                includeBreadcrumbs.isChecked() ? mark.breadcrumbs : null);
        } catch (IllegalArgumentException failure) { notice.setText(R.string.osservio_incident_mark_failed); return; }
        saving = true; notice.setText(R.string.osservio_incident_saving); updateControls();
        WeakReference<IncidentComposer> reference = new WeakReference<>(this); Handler handler = main;
        archive.save(snapshot, error -> handler.post(() -> {
            IncidentComposer composer = reference.get(); if (composer == null || !composer.dialog.isShowing()) return;
            composer.saving = false; composer.updateControls();
            if (error == null) { composer.editor.setText(""); composer.saved.run(); composer.dialog.dismiss(); }
            else composer.notice.setText(PersistencePresentation.error(composer.context, error));
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
    private CheckBox option(int label, boolean checked) {
        CheckBox option = new CheckBox(context); option.setText(label); option.setChecked(checked); option.setMinHeight(dp(48)); return option;
    }
    private TextView label(String value) {
        TextView text = new TextView(context); text.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        text.setText(value); text.setPadding(0, dp(6), 0, dp(6)); return text;
    }
    private LinearLayout.LayoutParams params() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
