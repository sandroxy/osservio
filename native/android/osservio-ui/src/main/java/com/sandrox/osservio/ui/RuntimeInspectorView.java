package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.HorizontalScrollView;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.BreadcrumbArchive;
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.core.EnvironmentProvider;
import java.util.Objects;

/** Core queries only: hosts own the environment provider and lifecycle collectors. */
// Explicit dependencies require programmatic construction.
@SuppressLint("ViewConstructor")
public final class RuntimeInspectorView extends LinearLayout {
    public RuntimeInspectorView(Context context, EnvironmentProvider provider, BreadcrumbRecorder recorder, BreadcrumbHistory history) {
        this(context, provider, recorder, history, null);
    }
    public RuntimeInspectorView(Context context, EnvironmentProvider provider, BreadcrumbRecorder recorder, BreadcrumbHistory history, BreadcrumbArchive archive) {
        super(context); setOrientation(VERTICAL);
        RadioGroup selection = new RadioGroup(context); selection.setOrientation(HORIZONTAL);
        RadioButton environmentTab = tab(R.string.osservio_runtime_environment);
        RadioButton breadcrumbsTab = tab(R.string.osservio_runtime_breadcrumbs);
        selection.addView(environmentTab, new RadioGroup.LayoutParams(-2, -2));
        selection.addView(breadcrumbsTab, new RadioGroup.LayoutParams(-2, -2));
        HorizontalScrollView navigation = new HorizontalScrollView(context);
        navigation.setHorizontalScrollBarEnabled(false); navigation.setFillViewport(true); navigation.addView(selection);
        addView(navigation, new LayoutParams(-1, -2));
        EnvironmentInspectorView environment = new EnvironmentInspectorView(context, Objects.requireNonNull(provider, "provider"));
        BreadcrumbInspectorView breadcrumbs = new BreadcrumbInspectorView(context, Objects.requireNonNull(recorder, "recorder"), Objects.requireNonNull(history, "history"));
        breadcrumbs.setVisibility(GONE);
        addView(environment, new LayoutParams(-1, 0, 1)); addView(breadcrumbs, new LayoutParams(-1, 0, 1));
        RadioButton savedTab = tab(R.string.osservio_archive_title);
        BreadcrumbArchiveView saved = archive == null ? null : new BreadcrumbArchiveView(context, archive);
        if (saved != null) {
            selection.addView(savedTab, new RadioGroup.LayoutParams(-2, -2));
            saved.setVisibility(GONE); addView(saved, new LayoutParams(-1, 0, 1));
        }
        selection.check(environmentTab.getId());
        selection.setOnCheckedChangeListener((group, checked) -> {
            View focused = findFocus(); if (focused != null) focused.clearFocus();
            InputMethodManager keyboard = context.getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
            environment.setVisibility(checked == environmentTab.getId() ? VISIBLE : GONE);
            breadcrumbs.setVisibility(checked == breadcrumbsTab.getId() ? VISIBLE : GONE);
            if (saved != null) saved.setVisibility(checked == savedTab.getId() ? VISIBLE : GONE);
        });
    }
    private RadioButton tab(int label) {
        RadioButton button = new RadioButton(getContext()); button.setId(View.generateViewId()); button.setText(label);
        button.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density)); return button;
    }
}
