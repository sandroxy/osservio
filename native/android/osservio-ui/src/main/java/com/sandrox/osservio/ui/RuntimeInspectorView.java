package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import com.sandrox.osservio.core.BreadcrumbArchive;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.core.EnvironmentProvider;
import java.util.Objects;

/** Standalone runtime context using the same scoped native presentation as the App section. */
@SuppressLint("ViewConstructor")
public final class RuntimeInspectorView extends LinearLayout {
    public RuntimeInspectorView(Context context, EnvironmentProvider provider, BreadcrumbRecorder recorder, BreadcrumbHistory history) {
        this(context, provider, recorder, history, null);
    }
    public RuntimeInspectorView(Context host, EnvironmentProvider provider, BreadcrumbRecorder recorder, BreadcrumbHistory history, BreadcrumbArchive archive) {
        super(InspectorTheme.context(host)); Context context = getContext(); setOrientation(VERTICAL);
        setBackgroundColor(InspectorTheme.canvas(context));
        View[] pages = archive == null ? new View[2] : new View[3];
        pages[0] = new EnvironmentInspectorView(context, Objects.requireNonNull(provider, "provider"));
        pages[1] = new BreadcrumbInspectorView(context, Objects.requireNonNull(recorder, "recorder"), Objects.requireNonNull(history, "history"));
        if (archive != null) pages[2] = new BreadcrumbArchiveView(context, archive);
        String[] labels = archive == null ? new String[] {context.getString(R.string.osservio_runtime_environment), context.getString(R.string.osservio_runtime_breadcrumbs)}
            : new String[] {context.getString(R.string.osservio_runtime_environment), context.getString(R.string.osservio_runtime_breadcrumbs), context.getString(R.string.osservio_archive_title)};
        addView(InspectorWidgets.segments(context, labels, 0, selected -> {
            View focused = findFocus(); if (focused != null) focused.clearFocus();
            android.view.inputmethod.InputMethodManager keyboard = context.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
            for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == selected ? VISIBLE : GONE);
        }), new LayoutParams(-1, -2));
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == 0 ? VISIBLE : GONE); addView(pages[i], new LayoutParams(-1, 0, 1));
        }
    }
}
