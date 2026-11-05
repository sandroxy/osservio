package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.HTTPRecorder;
import com.sandrox.osservio.core.LogHistory;
import com.sandrox.osservio.core.LogRecorder;

/** Native sections sharing host-owned histories and capture. The host owns presentation and insets. */
// Explicit runtime dependencies require programmatic construction.
@SuppressLint("ViewConstructor")
public final class InspectorView extends LinearLayout {
    public InspectorView(Context context, LogRecorder logRecorder, LogHistory logHistory,
                         HTTPRecorder httpRecorder, HTTPHistory httpHistory) {
        super(context);
        setOrientation(VERTICAL);
        RadioGroup tabs = new RadioGroup(context);
        tabs.setOrientation(HORIZONTAL);
        RadioButton logs = tab(context, R.string.osservio_logs);
        RadioButton network = tab(context, R.string.osservio_network);
        tabs.addView(logs, new RadioGroup.LayoutParams(0, -2, 1));
        tabs.addView(network, new RadioGroup.LayoutParams(0, -2, 1));
        tabs.check(logs.getId());
        addView(tabs, new LayoutParams(-1, -2));
        LogInspectorView logView = new LogInspectorView(context, logRecorder, logHistory);
        HTTPInspectorView httpView = new HTTPInspectorView(context, httpRecorder, httpHistory);
        httpView.setVisibility(GONE);
        addView(logView, new LayoutParams(-1, 0, 1));
        addView(httpView, new LayoutParams(-1, 0, 1));
        tabs.setOnCheckedChangeListener((group, checked) -> {
            View focused = findFocus();
            if (focused != null) focused.clearFocus();
            InputMethodManager keyboard = context.getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
            logView.setVisibility(checked == logs.getId() ? VISIBLE : GONE);
            httpView.setVisibility(checked == network.getId() ? VISIBLE : GONE);
        });
    }
    private static RadioButton tab(Context context, int label) {
        RadioButton button = new RadioButton(context);
        button.setId(View.generateViewId()); button.setText(label);
        button.setMinHeight(Math.round(48 * context.getResources().getDisplayMetrics().density));
        return button;
    }
}
