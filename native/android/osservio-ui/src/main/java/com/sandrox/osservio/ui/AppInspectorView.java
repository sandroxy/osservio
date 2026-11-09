package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sandrox.osservio.core.BreadcrumbArchive;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.core.CaptureController;
import com.sandrox.osservio.core.EnvironmentProvider;
import com.sandrox.osservio.core.HTTPRecorder;
import com.sandrox.osservio.core.LogRecorder;
import com.sandrox.osservio.core.WebSocketRecorder;

/** Host information and inspector-only preferences, outside the live reading streams. */
// Dependencies require programmatic construction.
@SuppressLint("ViewConstructor")
final class AppInspectorView extends ScrollView {
    static final class State { int scrollY; }
    private final State state;
    private final LinearLayout content;
    private final LogRecorder logs;
    private final HTTPRecorder http;
    private final WebSocketRecorder sockets;
    private AlertDialog dialog;
    AppInspectorView(Context context, EnvironmentProvider environment, BreadcrumbRecorder recorder, BreadcrumbHistory history,
                     BreadcrumbArchive archive, LogRecorder logs, HTTPRecorder http, WebSocketRecorder sockets, State state) {
        super(context); this.state = state; this.logs = logs; this.http = http; this.sockets = sockets;
        setFillViewport(true); setBackgroundColor(InspectorTheme.canvas(context));
        content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 16); content.setPadding(inset, 0, inset, inset); addView(content);
        TextView intro = InspectorWidgets.caption(context); intro.setText(R.string.osservio_ui_app_intro); content.addView(intro);
        section(R.string.osservio_ui_environment);
        row(R.string.osservio_ui_app_device, environment == null ? context.getString(R.string.osservio_ui_not_configured) : "",
            environment == null ? null : () -> open(R.string.osservio_ui_app_device, new EnvironmentInspectorView(context, environment)));
        row(R.string.osservio_ui_current_timeline, history == null ? context.getString(R.string.osservio_ui_not_configured) : "",
            history == null || recorder == null ? null : () -> open(R.string.osservio_ui_current_timeline, new BreadcrumbInspectorView(context, recorder, history)));
        row(R.string.osservio_ui_saved_runs, archive == null ? context.getString(R.string.osservio_ui_not_configured) : "",
            archive == null ? null : () -> open(R.string.osservio_ui_saved_runs, new BreadcrumbArchiveView(context, archive)));
        section(R.string.osservio_ui_inspector);
        row(R.string.osservio_ui_language, context.getString(InspectorPreferences.english(context)
            ? R.string.osservio_ui_english : R.string.osservio_ui_chinese), () -> choose(false));
        row(R.string.osservio_ui_appearance, context.getString(InspectorPreferences.dark(context)
            ? R.string.osservio_ui_dark : R.string.osservio_ui_light), () -> choose(true));
        row(R.string.osservio_ui_capture_status, "", this::captureStatus);
        TextView hint = InspectorWidgets.caption(context); hint.setText(R.string.osservio_ui_preferences_hint); content.addView(hint);
        post(() -> scrollTo(0, state.scrollY));
    }
    private void section(int title) {
        TextView text = InspectorWidgets.caption(getContext()); text.setText(title); text.setTextColor(InspectorTheme.accent(getContext()));
        text.setPadding(0, InspectorTheme.dp(getContext(), 24), 0, InspectorTheme.dp(getContext(), 8)); content.addView(text);
    }
    private void row(int title, String value, Runnable action) {
        Context context = getContext(); int inset = InspectorTheme.dp(context, 16);
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(inset, inset, inset, inset);
        LinearLayout labels = new LinearLayout(context); labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = InspectorTheme.text(context, 16, true); name.setText(title); labels.addView(name);
        if (!value.isEmpty()) {
            TextView detail = InspectorWidgets.caption(context); detail.setText(value); detail.setPadding(0, InspectorTheme.dp(context, 4), 0, 0);
            labels.addView(detail);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView arrow = InspectorTheme.text(context, 22, false); arrow.setText("›"); arrow.setTextColor(InspectorTheme.secondary(context));
            arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(arrow);
            row.setOnClickListener(ignored -> action.run()); row.setFocusable(true);
        }
        InspectorTheme.surface(row, action != null, 12);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.bottomMargin = InspectorTheme.dp(context, 6);
        content.addView(row, layout);
    }
    private void choose(boolean appearance) {
        Context context = getContext();
        String[] choices = appearance ? new String[] {context.getString(R.string.osservio_ui_light), context.getString(R.string.osservio_ui_dark)}
            : new String[] {context.getString(R.string.osservio_ui_chinese), context.getString(R.string.osservio_ui_english)};
        int selected = (appearance ? InspectorPreferences.dark(context) : InspectorPreferences.english(context)) ? 1 : 0;
        dialog = new AlertDialog.Builder(context).setTitle(appearance ? R.string.osservio_ui_appearance : R.string.osservio_ui_language)
            .setSingleChoiceItems(choices, selected, (selection, which) -> {
                selection.dismiss();
                if (appearance) InspectorPreferences.setDark(context, which == 1);
                else InspectorPreferences.setEnglish(context, which == 1);
            }).setNegativeButton(R.string.osservio_archive_cancel, null).show();
    }
    private void captureStatus() {
        Context context = getContext();
        LinearLayout details = new LinearLayout(context); details.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 16); details.setPadding(inset, inset, inset, inset);
        LogRecorder.Status log = logs.status();
        statusRow(details, context.getString(R.string.osservio_logs), context.getString(log == null ? R.string.osservio_busy
            : log.isRecording ? R.string.osservio_ui_capturing : R.string.osservio_ui_not_capturing));
        statusRow(details, "HTTP", mode(http.captureController));
        if (sockets != null) statusRow(details, "WebSocket", mode(sockets.captureController));
        TextView note = InspectorWidgets.caption(context); note.setText(R.string.osservio_ui_preferences_hint); details.addView(note);
        ScrollView scroll = new ScrollView(context); scroll.addView(details); open(R.string.osservio_ui_capture_status, scroll);
    }
    private void statusRow(LinearLayout parent, String title, String value) {
        TextView name = InspectorTheme.text(getContext(), 16, true); name.setText(title); parent.addView(name);
        TextView detail = InspectorWidgets.caption(getContext()); detail.setText(value); parent.addView(detail);
        parent.addView(InspectorWidgets.divider(getContext()));
    }
    private String mode(CaptureController controller) {
        CaptureController.Status status = controller.status();
        return getContext().getString(status == null ? R.string.osservio_busy : status.mode == CaptureController.Mode.DETAILED
            ? R.string.osservio_ui_detailed : status.mode == CaptureController.Mode.BASELINE
            ? R.string.osservio_ui_baseline : R.string.osservio_ui_not_capturing);
    }
    private void open(int title, View view) {
        dialog = InspectorDialogs.screen(getContext(), getContext().getString(title), view); dialog.show();
    }
    void saveReadingPosition() { state.scrollY = getScrollY(); }
    @Override protected void onDetachedFromWindow() { saveReadingPosition(); if (dialog != null) dialog.dismiss(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility); if (!isShown() && dialog != null) dialog.dismiss();
    }
}
