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
        int size = InspectorTheme.dp(context, 48), inset = InspectorTheme.dp(context, 13);
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
        button.setBackgroundTintList(null);
        InspectorTheme.surface(button, true, 24);
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
            InspectorTheme.feedback(button, 20);
        });
        button.setOnClickListener(ignored -> action.run()); return button;
    }
    static void primaryAction(Button button) {
        Context context = button.getContext(); button.setTextSize(15);
        int horizontal = InspectorTheme.dp(context, 20), vertical = InspectorTheme.dp(context, 12);
        button.setPadding(horizontal, vertical, horizontal, vertical);
        InspectorAppearance.style(button, () -> {
            button.setBackgroundTintList(null); button.setTextColor(InspectorTheme.accent(context));
            button.setBackground(InspectorTheme.ripple(context,
                InspectorTheme.shape(context, InspectorTheme.color(context, R.color.osservio_tint), 1000, false), null));
        });
    }
    static CharSequence actionTitle(Context context, int label, int icon) {
        android.text.SpannableString text = new android.text.SpannableString("\uFFFC\u00A0 " + context.getString(label));
        // Center the icon in the text line on API 23+, without raising the text baseline.
        android.text.style.ImageSpan span = new android.text.style.ImageSpan(symbol(context, icon, 20, InspectorTheme.accent(context))) {
            @Override public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end, android.graphics.Paint.FontMetricsInt metrics) {
                if (metrics != null) {
                    paint.getFontMetricsInt(metrics);
                    int extra = Math.max(0, getDrawable().getBounds().height() - (metrics.descent - metrics.ascent));
                    metrics.ascent -= extra / 2; metrics.descent += extra - extra / 2;
                    metrics.top = Math.min(metrics.top, metrics.ascent); metrics.bottom = Math.max(metrics.bottom, metrics.descent);
                }
                return getDrawable().getBounds().width();
            }
            @Override public void draw(android.graphics.Canvas canvas, CharSequence text, int start, int end, float x,
                                       int top, int baseline, int bottom, android.graphics.Paint paint) {
                canvas.save(); canvas.translate(x, (top + bottom - getDrawable().getBounds().height()) / 2f);
                getDrawable().draw(canvas); canvas.restore();
            }
        };
        text.setSpan(span, 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); return text;
    }
    static EditText search(Context context, int hint, String value, TextChange changed) {
        EditText search = new EditText(context); search.setSingleLine(true); InspectorTheme.typography(search, 15, false);
        InspectorAppearance.style(search, () -> styleSearch(search, hint));
        search.setFilters(new InputFilter[] {new InputFilter.LengthFilter(512)});
        search.setMinHeight(InspectorTheme.dp(context, 48)); search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(InspectorTheme.dp(context, 14), InspectorTheme.dp(context, 8), InspectorTheme.dp(context, 14), InspectorTheme.dp(context, 8));
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
        search.setBackgroundTintList(null);
        search.setBackground(InspectorTheme.shape(context, InspectorTheme.surface(context), 24, false));
        search.setCompoundDrawablesRelative(symbol(context, R.drawable.osservio_search, 19, InspectorTheme.secondary(context)), null, null, null);
    }
    static TextView caption(Context context) {
        TextView text = InspectorTheme.text(context, 13, false); InspectorAppearance.style(text, () -> text.setTextColor(InspectorTheme.secondary(context)));
        text.setPadding(0, InspectorTheme.dp(context, 8), 0, InspectorTheme.dp(context, 8)); return text;
    }
    static TextView sectionLabel(Context context) {
        TextView text = caption(context);
        InspectorTheme.typography(text, 15, true);
        text.setPadding(InspectorTheme.dp(context, 16), InspectorTheme.dp(context, 20), InspectorTheme.dp(context, 16), InspectorTheme.dp(context, 8));
        return text;
    }
    static LinearLayout group(Context context) {
        LinearLayout group = new LinearLayout(context); group.setOrientation(LinearLayout.VERTICAL);
        InspectorAppearance.style(group, () -> InspectorTheme.surface(group, false, 22));
        group.setClipToOutline(true); return group;
    }
    static void addGrouped(LinearLayout group, View row) {
        if (group.getChildCount() > 0) {
            View separator = divider(group.getContext());
            if (row instanceof ValueRow) ((LinearLayout.LayoutParams) separator.getLayoutParams()).leftMargin = ((ValueRow) row).textInset;
            group.addView(separator);
        }
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    static final class ValueRow extends LinearLayout {
        final TextView title, value;
        private final android.widget.ImageView arrow;
        private int textInset;
        ValueRow(Context context) {
            super(context); setGravity(Gravity.CENTER_VERTICAL); setBaselineAligned(false);
            int inset = InspectorTheme.dp(context, 16);
            textInset = inset;
            setPadding(inset, InspectorTheme.dp(context, 12), inset, InspectorTheme.dp(context, 12));
            setMinimumHeight(InspectorTheme.dp(context, 52));
            LinearLayout labels = new LinearLayout(context); labels.setOrientation(VERTICAL);
            title = InspectorTheme.text(context, 16, false); labels.addView(title, new LayoutParams(-1, -2));
            value = InspectorTheme.text(context, 13, false);
            value.setPadding(0, InspectorTheme.dp(context, 3), 0, 0); labels.addView(value, new LayoutParams(-1, -2));
            addView(labels, new LayoutParams(0, -2, 1));
            arrow = new android.widget.ImageView(context); arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams arrowLayout = new LayoutParams(InspectorTheme.dp(context, 16), InspectorTheme.dp(context, 20)); arrowLayout.leftMargin = InspectorTheme.dp(context, 12);
            addView(arrow, arrowLayout); arrow.setVisibility(GONE);
            InspectorAppearance.style(this, () -> {
                value.setTextColor(InspectorTheme.secondary(context));
                arrow.setImageDrawable(symbol(context, R.drawable.osservio_forward, 16, InspectorTheme.secondary(context)));
                if (isClickable()) InspectorTheme.feedback(this, 0);
            });
        }
        void bind(String heading, String detail, boolean navigates) {
            title.setText(heading); value.setText(detail); value.setVisibility(detail.isEmpty() ? GONE : VISIBLE);
            arrow.setVisibility(navigates ? VISIBLE : GONE);
        }
        void action(Runnable action) {
            setOnClickListener(action == null ? null : view -> action.run());
            setFocusable(action != null); setClickable(action != null);
            if (action != null) InspectorTheme.feedback(this, 0); else setBackground(null);
        }
        void icon(int resource) {
            android.widget.ImageView icon = new android.widget.ImageView(getContext()); icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            InspectorAppearance.style(icon, () -> icon.setImageDrawable(symbol(getContext(), resource, 22, InspectorTheme.accent(getContext()))));
            LayoutParams layout = new LayoutParams(InspectorTheme.dp(getContext(), 24), InspectorTheme.dp(getContext(), 24)); layout.rightMargin = InspectorTheme.dp(getContext(), 16); addView(icon, 0, layout);
            textInset = getPaddingLeft() + layout.width + layout.rightMargin;
        }
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
        row.setBackground(InspectorTheme.ripple(context, background, fill));
    }
    static CheckedTextView choice(Context context) {
        CheckedTextView row = new CheckedTextView(context); InspectorTheme.typography(row, 16, false);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setMinHeight(InspectorTheme.dp(context, 50));
        row.setPadding(InspectorTheme.dp(context, 16), InspectorTheme.dp(context, 13), InspectorTheme.dp(context, 16), InspectorTheme.dp(context, 13));
        InspectorAppearance.style(row, () -> {
            row.setTextColor(InspectorTheme.primary(context));
            row.setCheckMarkDrawable(checkmark(context));
        });
        return row;
    }
    static android.graphics.drawable.Drawable checkmark(Context context) {
        android.graphics.drawable.StateListDrawable check = new android.graphics.drawable.StateListDrawable();
        check.addState(new int[] {android.R.attr.state_checked}, symbol(context, R.drawable.osservio_check, 22, InspectorTheme.accent(context)));
        // TextView measures the current state before forwarding the checkbox state.
        // Both states need intrinsic size or an initially unchecked mark gets zero bounds.
        android.graphics.drawable.GradientDrawable empty = new android.graphics.drawable.GradientDrawable();
        empty.setColor(android.graphics.Color.TRANSPARENT); empty.setSize(InspectorTheme.dp(context, 22), InspectorTheme.dp(context, 22));
        check.addState(new int[0], empty); return check;
    }
    static void styleSwitch(android.widget.Switch control) {
        Context context = control.getContext(); InspectorTheme.typography(control, 16, false);
        control.setTextColor(InspectorTheme.primary(context)); control.setSplitTrack(false);
        control.setSwitchPadding(InspectorTheme.dp(context, 16)); control.setSwitchMinWidth(InspectorTheme.dp(context, 50));
        control.setThumbTintList(null); control.setTrackTintList(null);
        android.graphics.drawable.GradientDrawable thumb = InspectorTheme.shape(context, android.graphics.Color.WHITE, 15, false);
        thumb.setSize(InspectorTheme.dp(context, 26), InspectorTheme.dp(context, 26));
        control.setThumbDrawable(new android.graphics.drawable.InsetDrawable(thumb, InspectorTheme.dp(context, 2)));
        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        for (boolean checked : new boolean[] {true, false}) {
            android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable() {
                @Override public boolean getPadding(android.graphics.Rect padding) {
                    int inset = InspectorTheme.dp(context, 2); padding.set(inset, inset, inset, inset); return true;
                }
            };
            fill.setColor(InspectorTheme.color(context, checked ? R.color.osservio_accent : R.color.osservio_separator));
            fill.setCornerRadius(InspectorTheme.dp(context, 16));
            fill.setSize(InspectorTheme.dp(context, 50), InspectorTheme.dp(context, 30));
            track.addState(checked ? new int[] {android.R.attr.state_checked} : new int[0], fill);
        }
        control.setTrackDrawable(track); InspectorTheme.surface(control, true, 20);
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
        bar.setOrientation(LinearLayout.HORIZONTAL);
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
            button.setBackgroundTintList(null); button.setBackground(InspectorTheme.ripple(context, fill,
                InspectorTheme.shape(context, android.graphics.Color.WHITE, 16, false)));
            });
            android.widget.RadioGroup.LayoutParams layout = new android.widget.RadioGroup.LayoutParams(0, -2, 1);
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
        button.setBackground(new android.graphics.drawable.InsetDrawable(InspectorTheme.ripple(context,
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
            InspectorAppearance.style(card, () -> InspectorTheme.surface(card, true, 18));
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
        }
        void bind(String label, String primary, String secondary, long timestamp, int tone) {
            badge.setText(label); badge.setTextColor(tone); title.setText(primary); metadata.setText(secondary);
            time.setText(time(timestamp));
        }
    }
}
