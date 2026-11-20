package com.sandrox.osservio.ui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Stable editors for a private filter draft, expanded inside the same sheet. */
final class InspectorFilterConditions {
    final LinearLayout view;
    final EditText exclude, path, value, duration;
    private final InspectorQuery draft;
    private final Context context;
    private final TextView excludeSummary, fieldSummary;
    private boolean syncing;
    InspectorFilterConditions(Context context, InspectorQuery draft, Runnable changed) {
        this.context = context; this.draft = draft;
        view = column();
        TextView heading = InspectorWidgets.sectionLabel(context); heading.setText(R.string.osservio_ui_content_filters); view.addView(heading);
        LinearLayout card = column(); card.setBackground(InspectorTheme.shape(context, InspectorTheme.surface(context), 20, false));
        card.setClipToOutline(true);
        view.addView(card);
        exclude = input(R.string.osservio_ui_exclude, 512); path = input(R.string.osservio_ui_field_path, 256);
        value = input(R.string.osservio_ui_field_value, 512); duration = input(R.string.osservio_ui_duration, 19);
        duration.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout excludeEditor = editor(), fieldEditor = editor(); excludeEditor.addView(exclude);
        TextView hint = InspectorWidgets.caption(context); hint.setText(R.string.osservio_ui_field_hint);
        fieldEditor.addView(hint); fieldEditor.addView(path); fieldEditor.addView(value);
        excludeEditor.setVisibility(draft.excludedText.isEmpty() ? View.GONE : View.VISIBLE);
        fieldEditor.setVisibility(draft.fieldPath.isEmpty() && draft.fieldValue.isEmpty() ? View.GONE : View.VISIBLE);
        excludeSummary = row(card, R.string.osservio_ui_exclude, excludeEditor);
        card.addView(InspectorWidgets.divider(context));
        fieldSummary = row(card, R.string.osservio_ui_json_field, fieldEditor);
        for (EditText field : new EditText[] {exclude, path, value, duration}) {
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable text) {
                    if (syncing || android.view.inputmethod.BaseInputConnection.getComposingSpanStart(text) >= 0) return;
                    read(); summaries(); changed.run();
                }
            });
        }
        sync();
    }
    private LinearLayout column() { LinearLayout result = new LinearLayout(context); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private LinearLayout editor() {
        LinearLayout result = column(); int inset = InspectorTheme.dp(context, 12); result.setPadding(inset, 0, inset, inset); return result;
    }
    private TextView row(LinearLayout card, int label, LinearLayout editor) {
        InspectorFilterRow row = new InspectorFilterRow(context);
        row.bind(context.getString(label), context.getString(R.string.osservio_ui_not_set), false);
        InspectorTheme.feedback(row, 0);
        row.disclosureRotation(editor.getVisibility() == View.VISIBLE ? -90 : 90);
        row.setOnClickListener(v -> {
            if (editor.getVisibility() == View.VISIBLE) InspectorWidgets.hideKeyboard(editor);
            editor.setVisibility(editor.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            row.disclosureRotation(editor.getVisibility() == View.VISIBLE ? -90 : 90);
        });
        card.addView(row, new LinearLayout.LayoutParams(-1, -2)); card.addView(editor, new LinearLayout.LayoutParams(-1, -2)); return row.value;
    }
    private EditText input(int hint, int limit) {
        EditText field = InspectorWidgets.search(context, hint, "", ignored -> { });
        InspectorAppearance.style(field, () -> {
            field.setTextColor(InspectorTheme.primary(context)); field.setHintTextColor(InspectorTheme.secondary(context));
            field.setHint(context.getString(hint)); field.setContentDescription(context.getString(hint));
            field.setCompoundDrawables(null, null, null, null);
            field.setBackground(InspectorTheme.shape(context, InspectorTheme.canvas(context), 12, true));
        });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = InspectorTheme.dp(context, 6); field.setLayoutParams(layout);
        field.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(limit)}); return field;
    }
    void sync() {
        syncing = true; exclude.setText(draft.excludedText); path.setText(draft.fieldPath); value.setText(draft.fieldValue);
        duration.setText(draft.minimumDurationMillis == 0 ? "" : Long.toString(draft.minimumDurationMillis)); syncing = false; summaries();
    }
    private void summaries() {
        excludeSummary.setText(draft.excludedText.isEmpty() ? context.getString(R.string.osservio_ui_not_set) : draft.excludedText);
        fieldSummary.setText(draft.fieldPath.isEmpty() ? context.getString(R.string.osservio_ui_not_set) : InspectorFilterOptions.jsonCondition(context, draft.fieldPath, draft.fieldValue));
    }
    private void read() {
        draft.excludedText = exclude.getText().toString(); draft.fieldPath = path.getText().toString(); draft.fieldValue = value.getText().toString();
        long millis = 0; try { if (duration.length() > 0) millis = Long.parseLong(duration.getText().toString()); } catch (NumberFormatException invalid) { /* Validated on Apply. */ }
        if (millis > 0) draft.select(InspectorQuery.Field.TRANSPORT, "http"); draft.minimumDurationMillis = Math.max(0, millis);
    }
    boolean valid() {
        read();
        if (!draft.fieldPath.isEmpty() && InspectorJsonField.components(draft.fieldPath) == null) return false;
        try { return duration.length() == 0 || Long.parseLong(duration.getText().toString()) >= 0; }
        catch (NumberFormatException invalid) { return false; }
    }
}
