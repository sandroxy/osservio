package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.view.View;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.core.EnvironmentProvider;
import java.util.Objects;

/** Core queries only: hosts own the environment provider and lifecycle collectors. */
// Explicit dependencies require programmatic construction.
@SuppressLint("ViewConstructor")
public final class RuntimeInspectorView extends LinearLayout {
    public RuntimeInspectorView(Context context, EnvironmentProvider provider, BreadcrumbRecorder recorder, BreadcrumbHistory history) {
        super(context); setOrientation(VERTICAL);
        RadioGroup selection = new RadioGroup(context); selection.setOrientation(HORIZONTAL);
        RadioButton environmentTab = tab(R.string.osservio_runtime_environment);
        RadioButton breadcrumbsTab = tab(R.string.osservio_runtime_breadcrumbs);
        selection.addView(environmentTab, new RadioGroup.LayoutParams(0, -2, 1));
        selection.addView(breadcrumbsTab, new RadioGroup.LayoutParams(0, -2, 1));
        addView(selection, new LayoutParams(-1, -2));
        EnvironmentInspectorView environment = new EnvironmentInspectorView(context, Objects.requireNonNull(provider, "provider"));
        BreadcrumbInspectorView breadcrumbs = new BreadcrumbInspectorView(context, Objects.requireNonNull(recorder, "recorder"), Objects.requireNonNull(history, "history"));
        breadcrumbs.setVisibility(GONE);
        addView(environment, new LayoutParams(-1, 0, 1)); addView(breadcrumbs, new LayoutParams(-1, 0, 1));
        selection.check(environmentTab.getId());
        selection.setOnCheckedChangeListener((group, checked) -> {
            environment.setVisibility(checked == environmentTab.getId() ? VISIBLE : GONE);
            breadcrumbs.setVisibility(checked == breadcrumbsTab.getId() ? VISIBLE : GONE);
        });
    }
    private RadioButton tab(int label) {
        RadioButton button = new RadioButton(getContext()); button.setId(View.generateViewId()); button.setText(label);
        button.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density)); return button;
    }
}
