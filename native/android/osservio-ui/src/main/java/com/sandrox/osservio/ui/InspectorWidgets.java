package com.sandrox.osservio.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.CheckedTextView;
import android.widget.TextView;

/** Small native presentation primitives; no history or capture ownership. */
final class InspectorWidgets {
    interface TextChange { void changed(String value); }
    interface Selection { void selected(int value); }
    private InspectorWidgets() { }
    static ImageButton icon(Context context, int symbol, int label, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(symbol);
        InspectorAppearance.style(button, () -> styleIcon(button, label));
        int size = InspectorTheme.dp(context, 48), inset = InspectorTheme.dp(context, 14);
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size)); button.setPadding(inset, inset, inset, inset);
        button.setStateListAnimator(null);
        button.setOnClickListener(ignored -> action.run()); return button;
    }
    private static void styleIcon(ImageButton button, int label) {
        Context context = button.getContext(); button.setImageTintList(android.content.res.ColorStateList.valueOf(InspectorTheme.accent(context)));
        button.setContentDescription(context.getString(label));
        iconBackground(button);
        if (android.os.Build.VERSION.SDK_INT >= 26) button.setTooltipText(context.getString(label));
    }
    static void iconBackground(ImageButton button) {
        Context context = button.getContext();
        button.setBackground(new android.graphics.drawable.InsetDrawable(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(InspectorTheme.color(context, R.color.osservio_tint)),
            InspectorTheme.shape(context, InspectorTheme.surface(context), 24, false), null), InspectorTheme.dp(context, 4)));
    }
    static void hideKeyboard(View view) {
        View focused = view.findFocus(); if (focused != null) focused.clearFocus();
        view.setFocusableInTouchMode(true); view.requestFocus();
        android.view.inputmethod.InputMethodManager keyboard = view.getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
    static Button button(Context context, int label, Runnable action) {
        Button button = new Button(context); InspectorAppearance.text(button, label); button.setAllCaps(false);
        InspectorTheme.typography(button, 15, false); button.setMinHeight(InspectorTheme.dp(context, 48));
        button.setMinimumWidth(0); button.setMinWidth(0); button.setSingleLine(false);
        button.setStateListAnimator(null); button.setPadding(InspectorTheme.dp(context, 12), InspectorTheme.dp(context, 8), InspectorTheme.dp(context, 12), InspectorTheme.dp(context, 8));
        InspectorAppearance.style(button, () -> {
            button.setTextColor(InspectorTheme.accent(context));
            button.setBackgroundTintList(null);
            button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(
                InspectorTheme.color(context, R.color.osservio_tint)), null, InspectorTheme.shape(context, android.graphics.Color.WHITE, 20, false)));
        });
        button.setOnClickListener(ignored -> action.run()); return button;
    }
    static void primaryAction(Button button) {
        Context context = button.getContext(); button.setTextSize(15);
        int horizontal = InspectorTheme.dp(context, 20), vertical = InspectorTheme.dp(context, 12);
        button.setPadding(horizontal, vertical, horizontal, vertical);
        InspectorAppearance.style(button, () -> {
            button.setBackgroundTintList(null); button.setTextColor(InspectorTheme.accent(context));
            button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(
                InspectorTheme.color(context, R.color.osservio_separator)),
                InspectorTheme.shape(context, InspectorTheme.color(context, R.color.osservio_tint), 1000, false), null));
        });
    }
    static EditText search(Context context, int hint, String value, TextChange changed) {
        EditText search = new EditText(context); search.setSingleLine(true); InspectorTheme.typography(search, 15, false);
        InspectorAppearance.style(search, () -> styleSearch(search, hint));
        search.setFilters(new InputFilter[] {new InputFilter.LengthFilter(512)});
        search.setMinHeight(InspectorTheme.dp(context, 48)); search.setPadding(InspectorTheme.dp(context, 14), 0, InspectorTheme.dp(context, 14), 0);
        search.setText(value);
        search.setCompoundDrawablePadding(InspectorTheme.dp(context, 8));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { changed.changed(text.toString()); }
            @Override public void afterTextChanged(Editable text) { }
        });
        return search;
    }
    private static void styleSearch(EditText search, int hint) {
        Context context = search.getContext();
        search.setTextColor(InspectorTheme.primary(context)); search.setHintTextColor(InspectorTheme.secondary(context));
        search.setHint(context.getString(hint)); search.setContentDescription(context.getString(hint));
        search.setBackground(new android.graphics.drawable.InsetDrawable(InspectorTheme.shape(context, InspectorTheme.surface(context), 22, false), 0, InspectorTheme.dp(context, 3), 0, InspectorTheme.dp(context, 3)));
        search.setCompoundDrawablesRelative(symbol(context, R.drawable.osservio_search, 19, InspectorTheme.secondary(context)), null, null, null);
    }
    static TextView caption(Context context) {
        TextView text = InspectorTheme.text(context, 13, false); InspectorAppearance.style(text, () -> text.setTextColor(InspectorTheme.secondary(context)));
        text.setPadding(0, InspectorTheme.dp(context, 8), 0, InspectorTheme.dp(context, 8)); return text;
    }
    static TextView sectionLabel(Context context) {
        TextView text = caption(context);
        text.setPadding(InspectorTheme.dp(context, 16), InspectorTheme.dp(context, 20), InspectorTheme.dp(context, 16), InspectorTheme.dp(context, 8));
        return text;
    }
    static android.graphics.drawable.Drawable symbol(Context context, int resource, int size, int color) {
        android.graphics.drawable.Drawable drawable = context.getDrawable(resource);
        if (drawable != null) { drawable = drawable.mutate(); drawable.setTint(color); int edge = InspectorTheme.dp(context, size); drawable.setBounds(0, 0, edge, edge); }
        return drawable;
    }
    static void groupedRow(View row, boolean first, boolean last) {
        Context context = row.getContext();
        android.graphics.drawable.GradientDrawable fill = InspectorTheme.shape(context, InspectorTheme.surface(context), 0, false);
        float radius = InspectorTheme.dp(context, 20);
        fill.setCornerRadii(new float[] {first ? radius : 0, first ? radius : 0, first ? radius : 0, first ? radius : 0,
            last ? radius : 0, last ? radius : 0, last ? radius : 0, last ? radius : 0});
        android.graphics.drawable.Drawable background = fill;
        if (!last) {
            android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
                fill, new android.graphics.drawable.ColorDrawable(InspectorTheme.color(context, R.color.osservio_separator))});
            layers.setLayerGravity(1, Gravity.BOTTOM); layers.setLayerHeight(1, 1);
            layers.setLayerInset(1, InspectorTheme.dp(context, 16), 0, InspectorTheme.dp(context, 16), 0); background = layers;
        }
        row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(
            InspectorTheme.color(context, R.color.osservio_tint)), background, fill));
    }
    static CheckedTextView choice(Context context) {
        CheckedTextView row = new CheckedTextView(context); InspectorTheme.typography(row, 16, false);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setMinHeight(InspectorTheme.dp(context, 50));
        row.setPadding(InspectorTheme.dp(context, 16), InspectorTheme.dp(context, 13), InspectorTheme.dp(context, 16), InspectorTheme.dp(context, 13));
        InspectorAppearance.style(row, () -> {
            row.setTextColor(InspectorTheme.primary(context));
            android.graphics.drawable.StateListDrawable check = new android.graphics.drawable.StateListDrawable();
            check.addState(new int[] {android.R.attr.state_checked}, symbol(context, R.drawable.osservio_check, 22, InspectorTheme.accent(context)));
            check.addState(new int[0], new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            row.setCheckMarkDrawable(check);
        });
        return row;
    }
    static View segments(Context context, int[] labels, int selected, Selection changed) {
        String[] text = new String[labels.length];
        for (int i = 0; i < labels.length; i++) text[i] = context.getString(labels[i]);
        android.widget.RadioGroup bar = (android.widget.RadioGroup) segments(context, text, selected, changed);
        for (int i = 0; i < labels.length; i++) InspectorAppearance.text((TextView) bar.getChildAt(i), labels[i]);
        return bar;
    }
    static View segments(Context context, String[] labels, int selected, Selection changed) {
        android.widget.RadioGroup bar = new android.widget.RadioGroup(context);
        boolean large = context.getResources().getConfiguration().fontScale >= 1.5f;
        bar.setOrientation(large ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int inset = InspectorTheme.dp(context, 4); bar.setPadding(inset, inset, inset, inset);
        InspectorAppearance.style(bar, () -> bar.setBackground(InspectorTheme.shape(context, InspectorTheme.color(context, R.color.osservio_tint), 20, false)));
        for (int i = 0; i < labels.length; i++) {
            android.widget.RadioButton button = new android.widget.RadioButton(context);
            button.setId(View.generateViewId()); button.setTag(i); button.setButtonDrawable(null);
            button.setText(labels[i]); InspectorTheme.typography(button, 14, false); button.setGravity(Gravity.CENTER); button.setAllCaps(false);
            button.setMinHeight(InspectorTheme.dp(context, 44)); button.setPadding(inset * 2, inset, inset * 2, inset);
            InspectorAppearance.style(button, () -> {
            int[][] states = {new int[] {android.R.attr.state_checked}, new int[0]};
            button.setTextColor(new android.content.res.ColorStateList(states,
                new int[] {InspectorTheme.accent(context), InspectorTheme.secondary(context)}));
            android.graphics.drawable.StateListDrawable fill = new android.graphics.drawable.StateListDrawable();
            fill.addState(states[0], InspectorTheme.shape(context, InspectorTheme.surface(context), 16, false));
            fill.addState(states[1], new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(
                InspectorTheme.color(context, R.color.osservio_separator)), fill, null));
            });
            android.widget.RadioGroup.LayoutParams layout = new android.widget.RadioGroup.LayoutParams(large ? -1 : 0, -2, large ? 0 : 1);
            bar.addView(button, layout);
            if (i == selected) button.setChecked(true);
        }
        bar.setOnCheckedChangeListener((group, id) -> {
            View selectedView = group.findViewById(id);
            if (selectedView != null) changed.selected((Integer) selectedView.getTag());
        });
        return bar;
    }
    static View divider(Context context) {
        View divider = new View(context); InspectorAppearance.style(divider, () -> divider.setBackgroundColor(InspectorTheme.color(context, R.color.osservio_separator)));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, 1);
        layout.setMargins(InspectorTheme.dp(context, 16), 0, InspectorTheme.dp(context, 16), 0); divider.setLayoutParams(layout); return divider;
    }
    static void chip(Button button, boolean selected) {
        Context context = button.getContext();
        button.setBackgroundTintList(null);
        button.setTextColor(selected ? InspectorTheme.accent(context) : InspectorTheme.secondary(context));
        button.setBackground(new android.graphics.drawable.InsetDrawable(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(InspectorTheme.color(context, R.color.osservio_separator)),
            InspectorTheme.shape(context, selected ? InspectorTheme.color(context, R.color.osservio_tint) : InspectorTheme.surface(context), 20, false), null),
            0, InspectorTheme.dp(context, 4), 0, InspectorTheme.dp(context, 4)));
        button.setPadding(InspectorTheme.dp(context, 12), 0, InspectorTheme.dp(context, 12), 0);
        button.setSelected(selected);
    }
    static String time(long millis) {
        return new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(new java.util.Date(millis));
    }
    static final class EventRow extends LinearLayout {
        private final TextView badge;
        private final TextView time;
        private final TextView title;
        private final TextView metadata;
        EventRow(Context context) {
            super(context); setOrientation(VERTICAL);
            int inset = InspectorTheme.dp(context, 16); setPadding(inset, InspectorTheme.dp(context, 3), inset, InspectorTheme.dp(context, 3));
            LinearLayout card = new LinearLayout(context); card.setOrientation(VERTICAL); card.setPadding(inset, InspectorTheme.dp(context, 13), inset, InspectorTheme.dp(context, 13));
            InspectorAppearance.style(card, () -> card.setBackground(InspectorTheme.shape(context, InspectorTheme.surface(context), 18, false)));
            card.setDuplicateParentStateEnabled(true);
            addView(card, new LayoutParams(-1, -2));
            LinearLayout top = new LinearLayout(context); top.setGravity(Gravity.CENTER_VERTICAL);
            badge = InspectorTheme.text(context, 11, true); time = InspectorTheme.text(context, 11, false); time.setFontFeatureSettings("tnum");
            InspectorAppearance.style(time, () -> time.setTextColor(InspectorTheme.secondary(context))); time.setGravity(Gravity.END);
            top.addView(badge, new LayoutParams(0, -2, 1)); top.addView(time, new LayoutParams(0, -2, 1));
            card.addView(top, new LayoutParams(-1, -2));
            title = InspectorTheme.text(context, 15, false); title.setMaxLines(3); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(0, InspectorTheme.dp(context, 6), 0, InspectorTheme.dp(context, 4));
            card.addView(title, new LayoutParams(-1, -2));
            metadata = InspectorTheme.text(context, 12, false); InspectorAppearance.style(metadata, () -> metadata.setTextColor(InspectorTheme.secondary(context)));
            metadata.setMaxLines(3); metadata.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(metadata, new LayoutParams(-1, -2));
            setBackgroundResource(android.R.drawable.list_selector_background);
        }
        void bind(String label, String primary, String secondary, long timestamp, int tone) {
            badge.setText(label); badge.setTextColor(tone); title.setText(primary); metadata.setText(secondary);
            time.setText(time(timestamp));
        }
    }
}
