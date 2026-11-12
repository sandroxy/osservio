package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.sandrox.osservio.core.BreadcrumbArchive;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.core.EnvironmentProvider;
import com.sandrox.osservio.core.HTTPRecorder;
import com.sandrox.osservio.core.LogRecorder;
import com.sandrox.osservio.core.WebSocketRecorder;

/** Host information and inspector-only preferences, outside the live reading streams. */
// Dependencies require programmatic construction.
@SuppressLint("ViewConstructor")
final class AppInspectorView extends InspectorScroll.Page {
    static final class State { int scrollY; }
    private final State state;
    private final LinearLayout content;
    private LinearLayout group;
    private final LogRecorder logs;
    private final HTTPRecorder http;
    private final WebSocketRecorder sockets;
    private final BreadcrumbRecorder breadcrumbs;
    private InspectorScreen dialog;
    AppInspectorView(Context context, EnvironmentProvider environment, BreadcrumbRecorder recorder, BreadcrumbHistory history,
                     BreadcrumbArchive archive, LogRecorder logs, HTTPRecorder http, WebSocketRecorder sockets, State state) {
        super(context); this.state = state; this.logs = logs; this.http = http; this.sockets = sockets; this.breadcrumbs = recorder;
        setFillViewport(true); setVerticalScrollBarEnabled(false); setBackgroundColor(InspectorTheme.canvas(context));
        content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 16); content.setPadding(inset, 0, inset, inset); addView(content);
        section(R.string.osservio_ui_app_device);
        row(R.drawable.osservio_device, R.string.osservio_ui_environment, environment == null ? context.getString(R.string.osservio_ui_not_configured) : "",
            environment == null ? null : () -> open(R.string.osservio_runtime_environment, new EnvironmentInspectorView(context, environment)));
        row(R.drawable.osservio_clock, R.string.osservio_ui_current_timeline, history == null ? context.getString(R.string.osservio_ui_not_configured) : "",
            history == null || recorder == null ? null : () -> open(R.string.osservio_ui_current_timeline, new BreadcrumbInspectorView(context, recorder, history)));
        row(R.drawable.osservio_archive, R.string.osservio_ui_saved_runs, archive == null ? context.getString(R.string.osservio_ui_not_configured) : "",
            archive == null ? null : () -> open(R.string.osservio_ui_saved_runs, new BreadcrumbArchiveView(context, archive)));
        section(R.string.osservio_ui_inspector);
        row(R.drawable.osservio_globe, R.string.osservio_ui_language, context.getString(InspectorPreferences.english(context)
            ? R.string.osservio_ui_english : R.string.osservio_ui_chinese), () -> choose(false));
        row(R.drawable.osservio_appearance, R.string.osservio_ui_appearance, context.getString(InspectorPreferences.dark(context)
            ? R.string.osservio_ui_dark : R.string.osservio_ui_light), () -> choose(true));
        TextView hint = InspectorWidgets.caption(context); hint.setText(R.string.osservio_ui_preferences_hint); hint.setPadding(inset, InspectorTheme.dp(context, 8), inset, 0); content.addView(hint);
        section(R.string.osservio_ui_capture_status);
        row(R.drawable.osservio_filter, R.string.osservio_ui_capture_settings, "", this::captureStatus);
        post(() -> scrollTo(0, state.scrollY));
    }
    private void section(int title) {
        TextView text = InspectorWidgets.sectionLabel(getContext()); text.setText(title); content.addView(text);
        group = InspectorWidgets.group(getContext()); content.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }
    private void row(int icon, int title, String value, Runnable action) {
        InspectorWidgets.ValueRow row = new InspectorWidgets.ValueRow(getContext());
        row.bind(getContext().getString(title), value, action != null); row.icon(icon); row.action(action);
        InspectorWidgets.addGrouped(group, row);
    }
    private void choose(boolean appearance) {
        Context context = getContext();
        if (appearance) { open(R.string.osservio_ui_appearance, new InspectorThemeView(context)); return; }
        open(R.string.osservio_ui_language, new InspectorLanguageView(context));
    }
    private void captureStatus() {
        Context context = getContext();
        open(R.string.osservio_ui_capture_settings, new InspectorCaptureSettingsView(context, breadcrumbs == null ? null : breadcrumbs.captureController,
            new int[] {R.string.osservio_logs, R.string.osservio_ui_http, R.string.osservio_ws_title, R.string.osservio_runtime_breadcrumbs},
            logs.captureController, http.captureController, sockets == null ? null : sockets.captureController, breadcrumbs == null ? null : breadcrumbs.captureController));
    }
    private void open(int title, View view) {
        dialog = InspectorDialogs.screen(getContext(), title, view); clearOnDismiss(); dialog.show();
    }
    private void clearOnDismiss() {
        InspectorScreen shown = dialog; shown.setOnDismissListener(ignored -> { if (dialog == shown) dialog = null; });
    }
    void saveReadingPosition() { state.scrollY = getScrollY(); }
    @Override protected void onDetachedFromWindow() { saveReadingPosition(); if (!InspectorNavigation.owns(getContext()) && dialog != null) dialog.dismiss(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility); if (!InspectorNavigation.owns(getContext()) && !isShown() && dialog != null) dialog.dismiss();
    }
}
