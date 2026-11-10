package com.sandrox.osservio.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.sandrox.osservio.core.CaptureController;
import java.util.ArrayList;
import java.util.List;

/** Visible-only status updates; baseline changes never end a debug session. */
// Capture controllers are required; this view is never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorCaptureSettingsView extends ScrollView {
    private final CaptureController baseline;
    private final List<CaptureController> controllers = new ArrayList<>();
    private final List<TextView> values = new ArrayList<>();
    private final Switch baselineSwitch;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean attached, updating;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!attached || !isShown() || getWindowVisibility() != VISIBLE) return;
            update(); handler.postDelayed(this, 500);
        }
    };
    InspectorCaptureSettingsView(Context context, CaptureController baseline, int[] labels, CaptureController... supplied) {
        super(context); this.baseline = baseline; setFillViewport(true);
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 16); content.setPadding(inset, inset, inset, inset); addView(content);
        baselineSwitch = new Switch(context); InspectorAppearance.text(baselineSwitch, R.string.osservio_ui_baseline_capture);
        InspectorAppearance.style(baselineSwitch, () -> { baselineSwitch.setTextColor(InspectorTheme.primary(context)); InspectorTheme.surface(baselineSwitch, false, 20); });
        baselineSwitch.setTextSize(16);
        baselineSwitch.setMinHeight(InspectorTheme.dp(context, 56)); baselineSwitch.setPadding(inset, inset, inset, inset);
        baselineSwitch.setVisibility(baseline == null ? GONE : VISIBLE); content.addView(baselineSwitch);
        if (baseline != null) {
            TextView hint = InspectorWidgets.caption(context); InspectorAppearance.text(hint, R.string.osservio_crumb_baseline_hint); content.addView(hint);
        }
        baselineSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (updating) return;
            if (baseline == null || !baseline.setBaselineEnabled(enabled)) Toast.makeText(context, R.string.osservio_busy, Toast.LENGTH_SHORT).show();
            update();
        });
        List<Integer> names = new ArrayList<>();
        for (int index = 0; index < supplied.length; index++) {
            if (supplied[index] != null && !controllers.contains(supplied[index])) { controllers.add(supplied[index]); names.add(labels[index]); }
        }
        for (int index = 0; index < controllers.size(); index++) {
            TextView title = InspectorTheme.text(context, 16, true); int label = names.get(index);
            InspectorAppearance.text(title, () -> controllers.size() == 1 ? "Osservio" : context.getString(label)); content.addView(title);
            TextView value = InspectorWidgets.caption(context); content.addView(value); values.add(value);
        }
        InspectorAppearance.content(this, this::update); update();
    }
    private void update() {
        updating = true;
        CaptureController.Status status = baseline == null ? null : baseline.status(); baselineSwitch.setEnabled(status != null);
        if (status != null) baselineSwitch.setChecked(status.baselineEnabled);
        for (int index = 0; index < controllers.size(); index++) {
            TextView text = values.get(index); String value = InspectorDebugControl.describe(getContext(), controllers.get(index).status(), true);
            if (!value.contentEquals(text.getText())) text.setText(value);
        }
        updating = false;
    }
    private void schedule() {
        if (handler == null || refresh == null) return; handler.removeCallbacks(refresh);
        if (attached && isShown() && getWindowVisibility() == VISIBLE) handler.post(refresh);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; schedule(); }
    @Override protected void onDetachedFromWindow() { attached = false; handler.removeCallbacks(refresh); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View changedView, int visibility) { super.onVisibilityChanged(changedView, visibility); schedule(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); schedule(); }
}
