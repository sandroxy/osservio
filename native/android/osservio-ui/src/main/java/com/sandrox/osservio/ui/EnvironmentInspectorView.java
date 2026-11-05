package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sandrox.osservio.core.EnvironmentProvider;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import java.lang.ref.WeakReference;

// Query dependencies are supplied programmatically; this is not an XML-inflatable view.
@SuppressLint("ViewConstructor")
final class EnvironmentInspectorView extends LinearLayout {
    private final EnvironmentProvider provider;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Button refresh;
    private final Button copy;
    private final TextView notice;
    private final LinearLayout facts;
    private EnvironmentSnapshot snapshot;
    private boolean reading;
    private boolean attempted;
    EnvironmentInspectorView(Context context, EnvironmentProvider provider) {
        super(context); this.provider = provider;
        setOrientation(VERTICAL); setPadding(dp(16), dp(8), dp(16), 0);
        LinearLayout controls = new LinearLayout(context);
        refresh = new Button(context); refresh.setText(R.string.osservio_http_refresh); refresh.setAllCaps(false);
        refresh.setOnClickListener(view -> read());
        copy = new Button(context); copy.setText(R.string.osservio_runtime_copy_environment); copy.setAllCaps(false); copy.setEnabled(false);
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard != null && snapshot != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", RuntimePresentation.environment(context, snapshot)));
        });
        controls.addView(refresh, new LayoutParams(0, -2, 1)); controls.addView(copy, new LayoutParams(0, -2, 1));
        addView(controls, new LayoutParams(-1, -2));
        notice = new TextView(context); notice.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        addView(notice, new LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(context);
        facts = new LinearLayout(context); facts.setOrientation(VERTICAL);
        scroll.addView(facts); addView(scroll, new LayoutParams(-1, 0, 1));
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); readIfNeeded(); }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); readIfNeeded(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); readIfNeeded(); }
    private void readIfNeeded() {
        if (provider != null && facts != null && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE && !attempted) read();
    }
    private void read() {
        if (reading) return;
        reading = true; attempted = true; refresh.setEnabled(false); notice.setText(R.string.osservio_runtime_reading);
        WeakReference<EnvironmentInspectorView> reference = new WeakReference<>(this);
        Handler handler = main;
        provider.read((value, error) -> handler.post(() -> {
            EnvironmentInspectorView view = reference.get();
            if (view != null) view.received(value, error);
        }));
    }
    private void received(EnvironmentSnapshot value, EnvironmentProvider.Error error) {
        reading = false; refresh.setEnabled(true);
        if (value == null || error != null) {
            String message = getContext().getString(error == EnvironmentProvider.Error.BUSY ? R.string.osservio_busy : R.string.osservio_runtime_read_failed);
            if (snapshot != null) message += "\n" + getContext().getString(R.string.osservio_runtime_previous) + " · " + RuntimePresentation.date(snapshot.timestampMillis);
            notice.setText(message); return;
        }
        snapshot = value; copy.setEnabled(true);
        notice.setText(getContext().getString(R.string.osservio_runtime_pair, getContext().getString(R.string.osservio_runtime_snapshot), RuntimePresentation.date(value.timestampMillis)));
        facts.removeAllViews();
        for (EnvironmentSnapshot.Key key : EnvironmentSnapshot.Key.values()) {
            TextView name = new TextView(getContext()); name.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
            name.setText(RuntimePresentation.keyLabel(key)); name.setPadding(0, dp(16), 0, dp(4));
            facts.addView(name, new LayoutParams(-1, -2));
            TextView content = new TextView(getContext()); content.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
            content.setText(RuntimePresentation.value(getContext(), value.values.get(key), key)); content.setTextIsSelectable(true);
            facts.addView(content, new LayoutParams(-1, -2));
        }
        TextView explanation = new TextView(getContext()); explanation.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        explanation.setText(R.string.osservio_runtime_environment_hint); explanation.setPadding(0, dp(16), 0, dp(16));
        facts.addView(explanation, new LayoutParams(-1, -2));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
