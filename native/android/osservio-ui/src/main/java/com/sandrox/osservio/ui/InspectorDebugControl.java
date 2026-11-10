package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.widget.ImageButton;
import android.widget.Toast;
import com.sandrox.osservio.core.CaptureController;
import java.util.ArrayList;
import java.util.List;

/** One root control, independent of entry visibility and baseline policy. */
final class InspectorDebugControl {
    final ImageButton button;
    private final Context context;
    private final List<CaptureController> controllers = new ArrayList<>();
    private String lastPresentation;
    InspectorDebugControl(Context context, CaptureController... supplied) {
        this.context = context;
        for (CaptureController controller : supplied) if (controller != null && !controllers.contains(controller)) controllers.add(controller);
        button = InspectorWidgets.icon(context, R.drawable.osservio_debug, R.string.osservio_ui_debug_enable, this::toggle);
        refresh();
    }
    void refresh() {
        int enabled = 0; boolean busy = false, expired = false; long remaining = Long.MAX_VALUE;
        for (CaptureController controller : controllers) {
            CaptureController.Status status = controller.status();
            if (status == null) { busy = true; continue; }
            if (status.debugEnabled) enabled++;
            if (status.debugExpired) expired = true;
            if (status.debugHasTimeLimit) remaining = Math.min(remaining, status.remainingMillis);
        }
        boolean on = enabled == controllers.size();
        int key = busy ? R.string.osservio_busy : on ? R.string.osservio_ui_debug_on : enabled > 0 ? R.string.osservio_ui_debug_partial
            : expired ? R.string.osservio_ui_debug_expired : R.string.osservio_ui_debug_off;
        String value = context.getString(key);
        if (remaining != Long.MAX_VALUE) value += " · " + context.getString(R.string.osservio_ui_debug_remaining, (remaining + 999) / 1000);
        String label = context.getString(on ? R.string.osservio_ui_debug_disable : R.string.osservio_ui_debug_enable);
        String presentation = label + value;
        if (presentation.equals(lastPresentation)) return;
        lastPresentation = presentation;
        button.setEnabled(!busy); button.setSelected(on);
        button.setImageResource(on ? R.drawable.osservio_debug_on : R.drawable.osservio_debug);
        button.setBackground(InspectorTheme.shape(context, InspectorTheme.color(context, on ? R.color.osservio_accent : R.color.osservio_tint), 24, false));
        button.setImageTintList(ColorStateList.valueOf(on ? InspectorTheme.surface(context) : InspectorTheme.accent(context)));
        button.setContentDescription(label);
        if (Build.VERSION.SDK_INT >= 30) button.setStateDescription(value);
        else button.setContentDescription(label + " · " + value);
        if (Build.VERSION.SDK_INT >= 26) button.setTooltipText(value);
    }
    static String describe(Context context, CaptureController.Status status, boolean includeBaseline) {
        if (status == null) return context.getString(R.string.osservio_busy);
        String value = context.getString(status.debugEnabled ? R.string.osservio_ui_debug_on
            : status.debugExpired ? R.string.osservio_ui_debug_expired : R.string.osservio_ui_debug_off);
        if (status.debugHasTimeLimit) value += " · " + context.getString(R.string.osservio_ui_debug_remaining, (status.remainingMillis + 999) / 1000);
        if (includeBaseline) value += " · " + context.getString(status.baselineEnabled ? R.string.osservio_ui_baseline_on : R.string.osservio_ui_baseline_off);
        return value;
    }
    private void toggle() {
        boolean on = true;
        for (CaptureController controller : controllers) {
            CaptureController.Status status = controller.status();
            if (status == null) { Toast.makeText(context, R.string.osservio_busy, Toast.LENGTH_SHORT).show(); refresh(); return; }
            on &= status.debugEnabled;
        }
        boolean failed = false;
        for (CaptureController controller : controllers) {
            try { controller.setDebugEnabled(!on); }
            catch (IllegalStateException | IllegalArgumentException failure) { failed = true; }
        }
        refresh();
        if (failed) Toast.makeText(context, R.string.osservio_ui_debug_control_failed, Toast.LENGTH_LONG).show();
    }
}
