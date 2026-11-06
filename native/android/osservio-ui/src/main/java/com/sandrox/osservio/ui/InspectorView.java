package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.HorizontalScrollView;
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.BreadcrumbArchive;
import com.sandrox.osservio.core.EnvironmentProvider;
import com.sandrox.osservio.core.IncidentArchive;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.HTTPRecorder;
import com.sandrox.osservio.core.LogHistory;
import com.sandrox.osservio.core.LogRecorder;
import com.sandrox.osservio.core.WebSocketRecorder;
import com.sandrox.osservio.core.WebSocketHistory;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;

/** Native sections sharing host-owned histories and capture. The host owns presentation and insets. */
// Explicit runtime dependencies require programmatic construction.
@SuppressLint("ViewConstructor")
public final class InspectorView extends LinearLayout {
    private final RadioGroup tabs;
    private final Map<Integer, View> sections = new LinkedHashMap<>();
    public InspectorView(Context context, LogRecorder logRecorder, LogHistory logHistory,
                         HTTPRecorder httpRecorder, HTTPHistory httpHistory) {
        super(context);
        setOrientation(VERTICAL);
        tabs = new RadioGroup(context);
        tabs.setOrientation(HORIZONTAL);
        HorizontalScrollView navigation = new HorizontalScrollView(context);
        navigation.setHorizontalScrollBarEnabled(false); navigation.setFillViewport(true);
        navigation.addView(tabs);
        addView(navigation, new LayoutParams(-1, -2));
        addSection(R.string.osservio_logs, new LogInspectorView(context, logRecorder, logHistory));
        addSection(R.string.osservio_network, new HTTPInspectorView(context, httpRecorder, httpHistory));
        tabs.setOnCheckedChangeListener((group, checked) -> {
            View focused = findFocus();
            if (focused != null) focused.clearFocus();
            InputMethodManager keyboard = context.getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
            for (Map.Entry<Integer, View> section : sections.entrySet()) {
                section.getValue().setVisibility(section.getKey() == checked ? VISIBLE : GONE);
            }
        });
    }
    public InspectorView(Context context, LogRecorder logRecorder, LogHistory logHistory,
                         HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                         WebSocketRecorder webSocketRecorder, WebSocketHistory webSocketHistory) {
        this(context, logRecorder, logHistory, httpRecorder, httpHistory);
        WebSocketInspectorView sockets = new WebSocketInspectorView(context,
            Objects.requireNonNull(webSocketRecorder, "webSocketRecorder"), Objects.requireNonNull(webSocketHistory, "webSocketHistory"));
        addSection(R.string.osservio_ws_title, sockets);
    }
    private void addSection(int label, View view) {
        RadioButton button = tab(getContext(), label);
        boolean first = sections.isEmpty();
        view.setVisibility(first ? VISIBLE : GONE);
        sections.put(button.getId(), view);
        tabs.addView(button, new RadioGroup.LayoutParams(-2, -2));
        addView(view, new LayoutParams(-1, 0, 1));
        if (first) tabs.check(button.getId());
    }
    public InspectorView(Context context, LogRecorder logRecorder, LogHistory logHistory,
                         HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                         WebSocketRecorder webSocketRecorder, WebSocketHistory webSocketHistory,
                         EnvironmentProvider environment, BreadcrumbRecorder breadcrumbRecorder, BreadcrumbHistory breadcrumbHistory) {
        this(context, logRecorder, logHistory, httpRecorder, httpHistory, webSocketRecorder, webSocketHistory,
            environment, breadcrumbRecorder, breadcrumbHistory, null);
    }
    public InspectorView(Context context, LogRecorder logRecorder, LogHistory logHistory,
                         HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                         WebSocketRecorder webSocketRecorder, WebSocketHistory webSocketHistory,
                         EnvironmentProvider environment, BreadcrumbRecorder breadcrumbRecorder, BreadcrumbHistory breadcrumbHistory,
                         BreadcrumbArchive breadcrumbArchive) {
        this(context, logRecorder, logHistory, httpRecorder, httpHistory, webSocketRecorder, webSocketHistory,
            environment, breadcrumbRecorder, breadcrumbHistory, breadcrumbArchive, null);
    }
    public InspectorView(Context context, LogRecorder logRecorder, LogHistory logHistory,
                         HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                         WebSocketRecorder webSocketRecorder, WebSocketHistory webSocketHistory,
                         EnvironmentProvider environment, BreadcrumbRecorder breadcrumbRecorder, BreadcrumbHistory breadcrumbHistory,
                         BreadcrumbArchive breadcrumbArchive, IncidentArchive incidentArchive) {
        this(context, logRecorder, logHistory, httpRecorder, httpHistory, webSocketRecorder, webSocketHistory);
        addSection(R.string.osservio_runtime_title, new RuntimeInspectorView(context, environment, breadcrumbRecorder, breadcrumbHistory, breadcrumbArchive));
        if (incidentArchive != null) addSection(R.string.osservio_incident_title,
            new IncidentInspectorView(context, incidentArchive, environment, breadcrumbHistory, logHistory, httpHistory, webSocketHistory));
    }
    private static RadioButton tab(Context context, int label) {
        RadioButton button = new RadioButton(context);
        button.setId(View.generateViewId()); button.setText(label);
        button.setMinHeight(Math.round(48 * context.getResources().getDisplayMetrics().density));
        return button;
    }
}
