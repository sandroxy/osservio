package com.sandrox.osservio.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** An explicit, immutable content read. This view never queries live history. */
final class InspectorContentReader extends LinearLayout {
    private final TextView text;
    private final TextView count;
    private final ScrollView scroll;
    private final ScrollView controls;
    private final EditText search;
    private final LinearLayout searching;
    private final Button previous;
    private final Button next;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String content = "";
    private InspectorTextSearch matches = new InspectorTextSearch("", "");
    private int selection;
    private boolean searchDirty;
    private final Runnable searchContent = this::searchContent;
    private void searchContent() {
        matches = new InspectorTextSearch(content, search.getText().toString()); selection = 0; searchDirty = false; highlight(true);
    }
    InspectorContentReader(Context context) {
        super(context); setOrientation(VERTICAL);
        int inset = InspectorTheme.dp(context, 16); setPadding(inset, 0, inset, 0);
        LinearLayout header = new LinearLayout(context); header.setOrientation(VERTICAL);
        controls = new ScrollView(context); controls.addView(header); addView(controls, new LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(context);
        Button copy = InspectorWidgets.button(context, R.string.osservio_ui_copy_all, this::copy);
        Button find = InspectorWidgets.button(context, R.string.osservio_ui_search_content, this::toggleSearch);
        actions.addView(copy, new LayoutParams(0, -2, 1)); actions.addView(find, new LayoutParams(0, -2, 1)); header.addView(actions);
        searching = new LinearLayout(context); searching.setOrientation(VERTICAL); searching.setVisibility(GONE);
        search = InspectorWidgets.search(context, R.string.osservio_ui_search_content, "", value -> {
            searchDirty = true; handler.removeCallbacks(searchContent); handler.postDelayed(searchContent, 120);
        });
        searching.addView(search, new LayoutParams(-1, -2));
        LinearLayout navigation = new LinearLayout(context); navigation.setGravity(Gravity.CENTER_VERTICAL);
        count = InspectorWidgets.caption(context); navigation.addView(count, new LayoutParams(0, -2, 1));
        previous = InspectorWidgets.button(context, R.string.osservio_ui_previous_match, () -> move(-1)); previous.setText("↑");
        previous.setContentDescription(context.getString(R.string.osservio_ui_previous_match));
        next = InspectorWidgets.button(context, R.string.osservio_ui_next_match, () -> move(1)); next.setText("↓");
        next.setContentDescription(context.getString(R.string.osservio_ui_next_match));
        navigation.addView(previous, new LayoutParams(InspectorTheme.dp(context, 48), -2));
        navigation.addView(next, new LayoutParams(InspectorTheme.dp(context, 48), -2)); searching.addView(navigation); header.addView(searching);
        text = InspectorTheme.text(context, 14, false); text.setTextIsSelectable(true); text.setPadding(0, inset, 0, inset);
        text.setLineSpacing(InspectorTheme.dp(context, 3), 1); scroll = new ScrollView(context); scroll.addView(text);
        addView(scroll, new LayoutParams(-1, 0, 1));
    }
    @Override protected void onMeasure(int width, int height) {
        // Keep content reachable with the keyboard or large text in a short viewport.
        int available = MeasureSpec.getSize(height);
        if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED && available > 0) {
            controls.measure(width, MeasureSpec.makeMeasureSpec(available / 2, MeasureSpec.AT_MOST));
            controls.getLayoutParams().height = controls.getMeasuredHeight();
        }
        super.onMeasure(width, height);
    }
    void display(String value, boolean code) {
        handler.removeCallbacks(searchContent); content = value;
        text.setTypeface(code ? Typeface.MONOSPACE : Typeface.DEFAULT);
        matches = new InspectorTextSearch(value, searching.getVisibility() == VISIBLE ? search.getText().toString() : "");
        selection = 0; highlight(false); scroll.scrollTo(0, 0);
    }
    private void toggleSearch() {
        boolean show = searching.getVisibility() != VISIBLE; searching.setVisibility(show ? VISIBLE : GONE);
        if (show) { search.requestFocus(); searchContent.run(); }
        else {
            handler.removeCallbacks(searchContent); search.clearFocus();
            android.view.inputmethod.InputMethodManager keyboard = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
            matches = new InspectorTextSearch("", ""); highlight(false);
        }
    }
    private void move(int direction) {
        if (matches.matches.isEmpty()) return;
        selection = (selection + direction + matches.matches.size()) % matches.matches.size(); highlight(true);
    }
    private void highlight(boolean reveal) {
        count.setText(matches.limited ? getContext().getString(R.string.osservio_ui_match_limit, InspectorTextSearch.LIMIT)
            : getContext().getString(R.string.osservio_ui_match_position, matches.matches.isEmpty() ? 0 : selection + 1, matches.matches.size()));
        previous.setEnabled(!matches.matches.isEmpty()); next.setEnabled(!matches.matches.isEmpty());
        if (matches.matches.isEmpty()) { text.setText(content); return; }
        int[] match = matches.matches.get(selection);
        SpannableString marked = new SpannableString(content);
        marked.setSpan(new BackgroundColorSpan(InspectorTheme.color(getContext(), R.color.osservio_tint)), match[0], match[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setText(marked);
        if (reveal) text.post(() -> {
            if (text.getLayout() != null) scroll.smoothScrollTo(0, text.getLayout().getLineTop(text.getLayout().getLineForOffset(match[0])));
        });
    }
    private void copy() {
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        if (clipboard != null) { clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", content)); Toast.makeText(getContext(), R.string.osservio_ui_copied, Toast.LENGTH_SHORT).show(); }
    }
    @Override protected void onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow(); }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (searchDirty && searching.getVisibility() == VISIBLE) handler.post(searchContent);
    }
}
