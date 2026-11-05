package com.sandrox.osservio.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.HTTPRecorder;
import com.sandrox.osservio.core.HTTPSnapshot;

/** One dialog owns one immutable detail snapshot. Refresh is explicit, so reading never jumps. */
final class HTTPDetailDialog {
    private final Context context;
    private final HTTPHistory history;
    private final HTTPRecorder recorder;
    private HTTPEvent event;
    private String notice = "";
    private final TextView text;
    private final Spinner section;
    private final ScrollView scroll;
    final AlertDialog dialog;

    HTTPDetailDialog(Context context, HTTPEvent event, HTTPHistory history, HTTPRecorder recorder) {
        this.context = context; this.event = event; this.history = history; this.recorder = recorder;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(16 * context.getResources().getDisplayMetrics().density);
        content.setPadding(inset, 0, inset, 0);
        section = new Spinner(context);
        ArrayAdapter<String> options = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
            new String[] {context.getString(R.string.osservio_http_overview), context.getString(R.string.osservio_http_request_body),
                context.getString(R.string.osservio_http_response_body)});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        section.setAdapter(options);
        section.setMinimumHeight(inset * 3);
        content.addView(section, new LinearLayout.LayoutParams(-1, -2));
        text = new TextView(context);
        text.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        text.setTextIsSelectable(true);
        text.setPadding(0, inset, 0, inset);
        scroll = new ScrollView(context);
        scroll.addView(text);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        dialog = new AlertDialog.Builder(context).setTitle(event.method).setView(content)
            .setNegativeButton(R.string.osservio_close, null)
            .setNeutralButton(R.string.osservio_http_refresh, null)
            .setPositiveButton(R.string.osservio_http_copy, null).create();
        section.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { render(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> refresh());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", text.getText()));
            });
            refresh();
        });
    }
    private void refresh() {
        recorder.maintenance();
        HTTPSnapshot snapshot = history.snapshot();
        notice = context.getString(snapshot == null ? R.string.osservio_http_stale : R.string.osservio_http_missing);
        if (snapshot != null) {
            for (HTTPEvent current : snapshot.events) {
                if (current.id.equals(event.id)) { event = current; notice = ""; break; }
            }
        }
        render();
    }
    private void render() {
        boolean body = section.getSelectedItemPosition() != 0;
        text.setTypeface(body ? Typeface.MONOSPACE : Typeface.DEFAULT);
        String content = body ? HTTPPresentation.body(context,
            section.getSelectedItemPosition() == 1 ? event.requestBody : event.responseBody, history)
            : HTTPPresentation.overview(context, event);
        text.setText(notice.isEmpty() ? content : context.getString(R.string.osservio_http_notice, notice, content));
        scroll.scrollTo(0, 0);
    }
}
