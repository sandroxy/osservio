package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.TextView;

/** Small native presentation primitives; no history or capture ownership. */
final class InspectorWidgets {
    interface TextChange { void changed(String value); }
    interface Selection { void selected(int value); }
    private InspectorWidgets() { }
    static ImageButton icon(Context context, int symbol, int label, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(symbol); button.setImageTintList(android.content.res.ColorStateList.valueOf(InspectorTheme.accent(context)));
        button.setContentDescription(context.getString(label));
        int size = InspectorTheme.dp(context, 48), inset = InspectorTheme.dp(context, 12);
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size)); button.setPadding(inset, inset, inset, inset);
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(
            InspectorTheme.color(context, R.color.osservio_tint)), InspectorTheme.shape(context, InspectorTheme.surface(context), 24, false), null));
        button.setOnClickListener(ignored -> action.run());
        if (android.os.Build.VERSION.SDK_INT >= 26) button.setTooltipText(context.getString(label));
        return button;
    }
    static void hideKeyboard(View view) {
        View focused = view.findFocus(); if (focused != null) focused.clearFocus();
        view.setFocusableInTouchMode(true); view.requestFocus();
        android.view.inputmethod.InputMethodManager keyboard = view.getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
    static Button button(Context context, int label, Runnable action) {
        Button button = new Button(context); button.setText(label); button.setAllCaps(false);
        button.setTextSize(13); button.setMinHeight(InspectorTheme.dp(context, 48));
        button.setMinimumWidth(0); button.setMinWidth(0); button.setSingleLine(false);
        button.setOnClickListener(ignored -> action.run()); return button;
    }
    static EditText search(Context context, int hint, String value, TextChange changed) {
        EditText search = new EditText(context); search.setSingleLine(true); search.setTextSize(15);
        search.setTextColor(InspectorTheme.primary(context)); search.setHintTextColor(InspectorTheme.secondary(context));
        search.setHint(hint); search.setContentDescription(context.getString(hint));
        search.setFilters(new InputFilter[] {new InputFilter.LengthFilter(512)});
        search.setMinHeight(InspectorTheme.dp(context, 48)); search.setPadding(InspectorTheme.dp(context, 14), 0, InspectorTheme.dp(context, 14), 0);
        search.setBackground(InspectorTheme.shape(context, InspectorTheme.surface(context), 16, false)); search.setText(value);
        android.graphics.drawable.Drawable symbol = context.getDrawable(R.drawable.osservio_search);
        if (symbol != null) symbol.setTint(InspectorTheme.secondary(context));
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(symbol, null, null, null);
        search.setCompoundDrawablePadding(InspectorTheme.dp(context, 8));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { changed.changed(text.toString()); }
            @Override public void afterTextChanged(Editable text) { }
        });
        return search;
    }
    static TextView caption(Context context) {
        TextView text = InspectorTheme.text(context, 12, false); text.setTextColor(InspectorTheme.secondary(context));
        text.setPadding(0, InspectorTheme.dp(context, 8), 0, InspectorTheme.dp(context, 8)); return text;
    }
    static View segments(Context context, String[] labels, int selected, Selection changed) {
        android.widget.RadioGroup bar = new android.widget.RadioGroup(context);
        boolean large = context.getResources().getConfiguration().fontScale >= 1.5f;
        bar.setOrientation(large ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int inset = InspectorTheme.dp(context, 4); bar.setPadding(inset, inset, inset, inset);
        bar.setBackground(InspectorTheme.shape(context, InspectorTheme.color(context, R.color.osservio_tint), 20, false));
        for (int i = 0; i < labels.length; i++) {
            android.widget.RadioButton button = new android.widget.RadioButton(context);
            button.setId(View.generateViewId()); button.setTag(i); button.setButtonDrawable(null);
            button.setText(labels[i]); button.setTextSize(14); button.setGravity(Gravity.CENTER); button.setAllCaps(false);
            button.setMinHeight(InspectorTheme.dp(context, 44)); button.setPadding(inset * 2, inset, inset * 2, inset);
            int[][] states = {new int[] {android.R.attr.state_checked}, new int[0]};
            button.setTextColor(new android.content.res.ColorStateList(states,
                new int[] {InspectorTheme.accent(context), InspectorTheme.secondary(context)}));
            android.graphics.drawable.StateListDrawable fill = new android.graphics.drawable.StateListDrawable();
            fill.addState(states[0], InspectorTheme.shape(context, InspectorTheme.surface(context), 16, false));
            fill.addState(states[1], new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(
                InspectorTheme.color(context, R.color.osservio_separator)), fill, null));
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
        View divider = new View(context); divider.setBackgroundColor(InspectorTheme.color(context, R.color.osservio_separator));
        divider.setLayoutParams(new LinearLayout.LayoutParams(-1, InspectorTheme.dp(context, 1))); return divider;
    }
    static void chip(Button button, boolean selected) {
        Context context = button.getContext();
        button.setTextColor(selected ? InspectorTheme.accent(context) : InspectorTheme.secondary(context));
        button.setBackground(InspectorTheme.shape(context, selected ? InspectorTheme.color(context, R.color.osservio_tint) : InspectorTheme.surface(context), 20, true));
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
            int inset = InspectorTheme.dp(context, 16); setPadding(inset, InspectorTheme.dp(context, 4), inset, InspectorTheme.dp(context, 4));
            LinearLayout card = new LinearLayout(context); card.setOrientation(VERTICAL); card.setPadding(inset, inset, inset, inset);
            card.setBackground(InspectorTheme.shape(context, InspectorTheme.surface(context), 18, false));
            card.setDuplicateParentStateEnabled(true);
            addView(card, new LayoutParams(-1, -2));
            LinearLayout top = new LinearLayout(context); top.setGravity(Gravity.CENTER_VERTICAL);
            badge = InspectorTheme.text(context, 12, true); time = InspectorTheme.text(context, 11, false);
            time.setTextColor(InspectorTheme.secondary(context)); time.setGravity(Gravity.END);
            top.addView(badge, new LayoutParams(0, -2, 1)); top.addView(time, new LayoutParams(0, -2, 1));
            card.addView(top, new LayoutParams(-1, -2));
            title = InspectorTheme.text(context, 15, true); title.setMaxLines(3); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(0, InspectorTheme.dp(context, 7), 0, InspectorTheme.dp(context, 5));
            card.addView(title, new LayoutParams(-1, -2));
            metadata = InspectorTheme.text(context, 12, false); metadata.setTextColor(InspectorTheme.secondary(context));
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
