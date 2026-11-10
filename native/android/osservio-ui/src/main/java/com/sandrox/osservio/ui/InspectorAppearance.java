package com.sandrox.osservio.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** View-owned bindings run only on an explicit preference change, preserving reading state. */
final class InspectorAppearance {
    interface Value<T> { T get(); }
    private InspectorAppearance() { }
    static void style(View view, Runnable apply) { view.setTag(R.id.osservio_appearance_style, apply); apply.run(); }
    static void text(TextView view, Value<String> value) {
        Runnable apply = () -> view.setText(value.get()); view.setTag(R.id.osservio_appearance_text, apply); apply.run();
    }
    static void text(TextView view, int resource) { text(view, () -> view.getContext().getString(resource)); }
    static void content(View view, Runnable apply) { view.setTag(R.id.osservio_appearance_content, apply); }
    static void refresh(View view) {
        for (int key : new int[] {R.id.osservio_appearance_style, R.id.osservio_appearance_text}) {
            Object binding = view.getTag(key); if (binding instanceof Runnable) ((Runnable) binding).run();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) refresh(group.getChildAt(i));
        }
        Object content = view.getTag(R.id.osservio_appearance_content);
        if (content instanceof Runnable) ((Runnable) content).run();
    }
}
