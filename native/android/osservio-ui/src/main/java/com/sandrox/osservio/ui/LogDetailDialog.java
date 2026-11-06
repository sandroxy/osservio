package com.sandrox.osservio.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sandrox.osservio.core.LogEvent;

/** Log details have no history dependency and can display either live or retained evidence. */
final class LogDetailDialog {
    private LogDetailDialog() { }
    static AlertDialog create(Context context, LogEvent event) {
        int inset = Math.round(16 * context.getResources().getDisplayMetrics().density);
        TextView text = new TextView(context); text.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        text.setTextIsSelectable(true); text.setPadding(inset, inset, inset, inset);
        String marker = (event.isPrivate ? context.getString(R.string.osservio_crumb_private) + "\n" : "")
            + (event.isTruncated ? context.getString(R.string.osservio_truncated) + "\n" : "");
        text.setText(context.getString(R.string.osservio_log_evidence_detail, RuntimePresentation.date(event.timestampMillis),
            context.getString(R.string.osservio_http_session), event.sessionId,
            context.getString(R.string.osservio_detail, event.level.name(), event.sequence, marker, event.message)));
        ScrollView scroll = new ScrollView(context); scroll.addView(text);
        return new AlertDialog.Builder(context).setTitle(event.category).setView(scroll).setNegativeButton(R.string.osservio_close, null)
            .setPositiveButton(R.string.osservio_copy, (dialog, which) -> {
                ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", event.message));
            }).create();
    }
}
