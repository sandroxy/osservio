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
import com.sandrox.osservio.core.WebSocketEvent;
import com.sandrox.osservio.core.WebSocketHistory;
import com.sandrox.osservio.core.WebSocketSnapshot;

/** One dialog owns one immutable detail snapshot. Refresh is explicit, so reading never jumps. */
final class WebSocketDetailDialog {
    private final Context context;
    private final WebSocketHistory history;
    private WebSocketEvent event;
    private String notice = "";
    private final TextView text;
    private final Spinner section;
    private final ScrollView scroll;
    final AlertDialog dialog;

    WebSocketDetailDialog(Context context, WebSocketEvent event, WebSocketHistory history) {
        this.context = context; this.event = event; this.history = history;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(16 * context.getResources().getDisplayMetrics().density);
        content.setPadding(inset, 0, inset, 0);
        section = new Spinner(context);
        ArrayAdapter<String> options = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
            new String[] {context.getString(R.string.osservio_http_overview), context.getString(R.string.osservio_ws_payload)});
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
        dialog = new AlertDialog.Builder(context).setTitle(WebSocketPresentation.kind(context, event.kind)).setView(content)
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
        WebSocketSnapshot snapshot = history.snapshot();
        notice = context.getString(snapshot == null ? R.string.osservio_http_stale : R.string.osservio_ws_missing);
        if (snapshot != null) {
            for (WebSocketEvent current : snapshot.events) {
                if (current.id.equals(event.id)) { event = current; notice = ""; break; }
            }
        }
        render();
    }
    private void render() {
        boolean body = section.getSelectedItemPosition() != 0;
        text.setTypeface(body ? Typeface.MONOSPACE : Typeface.DEFAULT);
        String content = body ? WebSocketPresentation.payload(context, event.payload, history)
            : WebSocketPresentation.overview(context, event);
        text.setText(notice.isEmpty() ? content : context.getString(R.string.osservio_http_notice, notice, content));
        scroll.scrollTo(0, 0);
    }
}
