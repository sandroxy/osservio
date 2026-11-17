package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.NetworkError;
import com.sandrox.osservio.core.WebSocketCloseReason;
import java.util.ArrayList;
import java.util.List;

/** Error and close text stay in detail and explicit search, without enlarging normal list rows. */
final class NetworkErrorPresentation {
    private NetworkErrorPresentation() { }
    static String notice(Context context, boolean limited, boolean processed) {
        List<String> values = new ArrayList<>();
        if (limited) values.add(context.getString(R.string.osservio_network_details_limited));
        if (processed) values.add(context.getString(R.string.osservio_network_details_processed));
        return android.text.TextUtils.join("\n", values);
    }
    static String text(Context context, NetworkError error) {
        if (error == null) return "";
        StringBuilder text = new StringBuilder();
        int[] labels = {R.string.osservio_network_error_message, R.string.osservio_network_error_code,
            R.string.osservio_network_error_type, R.string.osservio_network_error_domain};
        String[] values = {error.message, error.code, error.type, error.domain};
        for (int index = 0; index < labels.length; index++) if (values[index] != null) {
            if (text.length() > 0) text.append('\n');
            text.append(context.getString(labels[index])).append(": ").append(values[index]);
        }
        String note = notice(context, error.isLimited, error.isProcessed);
        if (!note.isEmpty()) { if (text.length() > 0) text.append('\n'); text.append(note); }
        return text.toString();
    }
    static String text(Context context, WebSocketCloseReason reason) {
        if (reason == null) return "";
        return context.getString(R.string.osservio_network_close_reason) + ": "
            + (reason.text == null ? context.getString(R.string.osservio_network_text_unavailable) : reason.text)
            + "\n" + notice(context, reason.isLimited, reason.isProcessed);
    }
    static void append(InspectorDetailPage page, Context context, NetworkError error, WebSocketCloseReason reason, InspectorAppearance.Value<String> identity) {
        if (error != null) {
            page.section(R.string.osservio_network_error_details);
            if (error.message != null) reader(page, context, R.string.osservio_network_error_message, error.message,
                () -> notice(context, error.isLimited, error.isProcessed), identity);
            if (error.code != null) page.field(R.string.osservio_network_error_code, error.code);
            if (error.type != null) page.field(R.string.osservio_network_error_type, error.type);
            if (error.domain != null) page.field(R.string.osservio_network_error_domain, error.domain);
            if (error.message == null && error.code == null && error.type == null && error.domain == null)
                page.value(() -> context.getString(R.string.osservio_network_text_unavailable));
            page.note(() -> notice(context, error.isLimited, error.isProcessed));
        }
        if (reason != null) {
            page.section(R.string.osservio_network_close_reason);
            if (reason.text != null) page.value(() -> reason.text.isEmpty() ? context.getString(R.string.osservio_ui_content_empty) : InspectorRecordPresentation.compact(reason.text, 600),
                () -> page.read(R.string.osservio_network_close_reason, new InspectorReadContent(reason.text,
                    () -> notice(context, reason.isLimited, reason.isProcessed), false).withContext(identity)), R.string.osservio_ui_open_reader);
            else page.value(() -> context.getString(R.string.osservio_network_text_unavailable));
            page.note(() -> notice(context, reason.isLimited, reason.isProcessed));
        }
    }
    private static void reader(InspectorDetailPage page, Context context, int title, String text, InspectorAppearance.Value<String> notice, InspectorAppearance.Value<String> identity) {
        page.disclosure(title, () -> text.isEmpty() ? context.getString(R.string.osservio_ui_content_empty) : InspectorRecordPresentation.compact(text, 600),
            () -> page.read(title, new InspectorReadContent(text, notice, false).withContext(identity)));
    }
}
