package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Page actions keep their identity when the visible toolbar folds them into a menu. */
final class InspectorToolbarActions {
    interface Provider { View navigationAction(); }
    private final LinearLayout bar;
    private final List<View> items = new ArrayList<>();
    private final View more;
    private View exit;
    InspectorToolbarActions(LinearLayout bar) {
        this.bar = bar;
        more = InspectorWidgets.icon(bar.getContext(), R.drawable.osservio_more, R.string.osservio_ui_actions, this::openMenu);
    }
    void exit(View view) { exit = view; render(); }
    void add(View view) { if (!items.contains(view)) items.add(view); render(); }
    void replace(List<View> views) { items.clear(); items.addAll(views); render(); }
    void refreshAppearance() {
        for (View item : items) InspectorAppearance.refresh(item);
        InspectorAppearance.refresh(more); render();
    }
    void render() {
        List<View> visible = new ArrayList<>();
        for (View item : items) if (item.getVisibility() == View.VISIBLE) visible.add(item);
        boolean fixed = exit != null && exit.getVisibility() == View.VISIBLE;
        if (visible.size() > (fixed ? 1 : 2)) { visible.clear(); visible.add(more); }
        if (fixed) visible.add(exit);
        boolean same = visible.size() == bar.getChildCount();
        for (int i = 0; same && i < visible.size(); i++) same = visible.get(i) == bar.getChildAt(i);
        if (same) return;
        bar.removeAllViews();
        for (View view : visible) {
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            bar.addView(view);
        }
    }
    private void openMenu() {
        Context context = bar.getContext();
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 16); content.setPadding(inset, 0, inset, inset);
        android.widget.ScrollView scroll = new InspectorScroll.Page(context); scroll.addView(content);
        InspectorScreen menu = InspectorDialogs.panel(context, R.string.osservio_ui_actions, scroll); menu.compact();
        List<View> available = new ArrayList<>();
        for (View item : items) if (item.getVisibility() == View.VISIBLE) available.add(item);
        for (int index = 0; index < available.size(); index++) {
            View item = available.get(index);
            boolean destructive = Boolean.TRUE.equals(item.getTag(R.id.osservio_destructive_action));
            boolean first = index == 0 || destructive != Boolean.TRUE.equals(available.get(index - 1).getTag(R.id.osservio_destructive_action));
            boolean last = index + 1 == available.size() || destructive != Boolean.TRUE.equals(available.get(index + 1).getTag(R.id.osservio_destructive_action));
            int tint = destructive ? InspectorTheme.color(context, R.color.osservio_error) : InspectorTheme.accent(context);
            android.widget.CheckedTextView row = InspectorWidgets.choice(context);
            CharSequence label = item.getContentDescription();
            row.setText(label != null ? label : item instanceof TextView ? ((TextView) item).getText() : "");
            if (destructive) row.setTextColor(tint);
            row.setEnabled(item.isEnabled()); row.setAlpha(item.isEnabled() ? 1 : 0.45f);
            if (item instanceof ImageView) {
                android.graphics.drawable.Drawable source = ((ImageView) item).getDrawable();
                if (source != null && source.getConstantState() != null) {
                    android.graphics.drawable.Drawable icon = source.getConstantState().newDrawable().mutate();
                    icon.setTint(tint); int size = InspectorTheme.dp(context, 22); icon.setBounds(0, 0, size, size);
                    row.setCompoundDrawablesRelative(icon, null, null, null); row.setCompoundDrawablePadding(inset);
                }
            }
            InspectorWidgets.groupedRow(row, first, last);
            row.setOnClickListener(ignored -> {
                if (!item.isEnabled() || item.getVisibility() != View.VISIBLE) return;
                menu.dismiss(); item.performClick();
            });
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
            if (first && index > 0) layout.topMargin = InspectorTheme.dp(context, 16);
            content.addView(row, layout);
        }
        menu.show();
    }
}
