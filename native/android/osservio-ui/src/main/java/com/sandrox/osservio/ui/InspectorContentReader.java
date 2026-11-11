package com.sandrox.osservio.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
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
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import java.lang.ref.WeakReference;

/** An explicit, immutable content read. This view never queries live history. */
final class InspectorContentReader extends LinearLayout implements InspectorScreen.Content {
    private final TextView text;
    private final TextView count;
    private final ScrollView scroll;
    private final ScrollView controls;
    private final EditText search;
    private final LinearLayout searching;
    private final Button previous;
    private final Button next;
    private final RadioGroup mode;
    private final TextView notice, origin;
    private final LinearLayout actions, matchNavigation;
    private final android.widget.ImageButton copy;
    private final InspectorJsonTreeView tree;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String content = "";
    private InspectorTextSearch matches = new InspectorTextSearch("", "");
    private int selection;
    private boolean searchDirty;
    private InspectorReadContent value = InspectorReadContent.unavailable("");
    private InspectorJsonTask parsing;
    private boolean parsed;
    private boolean structured = true;
    private int generation;
    private int parserNotice;
    private final Runnable searchContent = this::searchContent;
    private void searchContent() {
        matches = new InspectorTextSearch(content, search.getText().toString()); selection = 0; searchDirty = false; highlight(true);
    }
    InspectorContentReader(Context context) {
        super(context); setOrientation(VERTICAL);
        int inset = InspectorTheme.dp(context, 16), gap = InspectorTheme.dp(context, 8); setPadding(inset, gap, inset, 0);
        LinearLayout header = new LinearLayout(context); header.setOrientation(VERTICAL);
        controls = new InspectorScroll.Page(context); controls.addView(header); addView(controls, new LayoutParams(-1, -2));
        mode = (RadioGroup) InspectorWidgets.segments(context, new int[] {R.string.osservio_json_structure, R.string.osservio_json_raw}, 0,
            selected -> { structured = selected == 0; highlight(true); });
        LayoutParams modeLayout = new LayoutParams(-1, -2); modeLayout.bottomMargin = gap;
        mode.setVisibility(GONE); header.addView(mode, modeLayout);
        origin = InspectorWidgets.caption(context); origin.setMaxLines(3); origin.setPadding(inset, gap, inset, gap); header.addView(origin, 0);
        notice = InspectorWidgets.caption(context); notice.setPadding(inset, gap, inset, gap); header.addView(notice);
        actions = new LinearLayout(context);
        copy = InspectorWidgets.icon(context, R.drawable.osservio_copy, R.string.osservio_ui_copy_all, this::copy);
        actions.setGravity(Gravity.END); actions.addView(copy); header.addView(actions);
        searching = new LinearLayout(context); searching.setOrientation(VERTICAL);
        search = InspectorWidgets.search(context, R.string.osservio_ui_search_content, "", value -> {
            searchDirty = true; handler.removeCallbacks(searchContent); handler.postDelayed(searchContent, 120);
        });
        searching.addView(search, new LayoutParams(-1, -2));
        LinearLayout navigation = new LinearLayout(context); navigation.setGravity(Gravity.CENTER_VERTICAL); navigation.setBaselineAligned(false);
        navigation.setPadding(inset, 0, 0, 0); matchNavigation = navigation;
        count = InspectorWidgets.caption(context); navigation.addView(count, new LayoutParams(0, -2, 1));
        previous = InspectorWidgets.button(context, R.string.osservio_ui_previous_match, () -> move(-1)); InspectorAppearance.text(previous, () -> "↑");
        previous.setContentDescription(context.getString(R.string.osservio_ui_previous_match));
        next = InspectorWidgets.button(context, R.string.osservio_ui_next_match, () -> move(1)); InspectorAppearance.text(next, () -> "↓");
        next.setContentDescription(context.getString(R.string.osservio_ui_next_match));
        navigation.addView(previous, new LayoutParams(InspectorTheme.dp(context, 48), -2));
        navigation.addView(next, new LayoutParams(InspectorTheme.dp(context, 48), -2)); searching.addView(navigation); header.addView(searching);
        text = InspectorTheme.text(context, 14, false); text.setTextIsSelectable(true); text.setPadding(0, inset, 0, inset);
        text.setLineSpacing(InspectorTheme.dp(context, 3), 1); scroll = new InspectorScroll.Page(context); scroll.addView(text);
        tree = new InspectorJsonTreeView(context); tree.setVisibility(GONE);
        InspectorAppearance.content(this, () -> {
            previous.setContentDescription(context.getString(R.string.osservio_ui_previous_match));
            next.setContentDescription(context.getString(R.string.osservio_ui_next_match));
            highlight(false);
        });
        FrameLayout reading = new FrameLayout(context);
        InspectorAppearance.style(reading, () -> reading.setBackgroundColor(InspectorTheme.canvas(context)));
        reading.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        reading.addView(tree, new FrameLayout.LayoutParams(-1, -1));
        LayoutParams readingLayout = new LayoutParams(-1, 0, 1); readingLayout.topMargin = gap; addView(reading, readingLayout);
    }
    @Override public void bindScreen(InspectorScreen screen) { screen.addAction(copy); actions.setVisibility(GONE); }
    @Override protected void onMeasure(int width, int height) {
        // Keep content reachable with the keyboard or large text in a short viewport.
        int available = Math.max(0, MeasureSpec.getSize(height) - getPaddingTop() - getPaddingBottom());
        if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED && available > 0) {
            int contentWidth = getChildMeasureSpec(width, getPaddingLeft() + getPaddingRight(), LayoutParams.MATCH_PARENT);
            controls.measure(contentWidth, MeasureSpec.makeMeasureSpec(available / 2, MeasureSpec.AT_MOST));
            controls.getLayoutParams().height = controls.getMeasuredHeight();
        }
        super.onMeasure(width, height);
    }
    void display(String value, boolean code) {
        display(new InspectorReadContent(value, "", code), code);
    }
    void display(InspectorReadContent value, boolean code) {
        cancelParsing(); handler.removeCallbacks(searchContent); content = value.text; this.value = value;
        parsed = false; parserNotice = 0; structured = true; tree.clear(); mode.setVisibility(GONE);
        ((android.widget.RadioButton) mode.getChildAt(0)).setChecked(true);
        text.setTypeface(code ? Typeface.MONOSPACE : Typeface.DEFAULT);
        matches = new InspectorTextSearch(content, searching.getVisibility() == VISIBLE ? search.getText().toString() : "");
        selection = 0; highlight(false); scroll.scrollTo(0, 0);
        if (isAttachedToWindow()) startParsing();
    }
    private void move(int direction) {
        if (matches.matches.isEmpty()) return;
        selection = (selection + direction + matches.matches.size()) % matches.matches.size(); highlight(true);
    }
    private void highlight(boolean reveal) {
        matchNavigation.setVisibility(search.length() == 0 ? GONE : VISIBLE);
        boolean showTree = structured && tree.hasDocument(); tree.setVisibility(showTree ? VISIBLE : GONE); scroll.setVisibility(showTree ? GONE : VISIBLE);
        origin.setText(value.context()); origin.setVisibility(value.context().isEmpty() ? GONE : VISIBLE);
        String message = value.notice();
        if (parserNotice != 0) message += (message.isEmpty() ? "" : "\n") + getContext().getString(parserNotice);
        if (showTree) message += (message.isEmpty() ? "" : "\n") + getContext().getString(R.string.osservio_json_hint);
        notice.setText(message); notice.setVisibility(message.isEmpty() ? GONE : VISIBLE);
        count.setText(matches.limited ? getContext().getString(R.string.osservio_ui_match_limit, InspectorTextSearch.LIMIT)
            : getContext().getString(R.string.osservio_ui_match_position, matches.matches.isEmpty() ? 0 : selection + 1, matches.matches.size()));
        previous.setEnabled(!matches.matches.isEmpty()); next.setEnabled(!matches.matches.isEmpty());
        if (showTree) { tree.focus(matches.matches.isEmpty() ? -1 : matches.matches.get(selection)[0], reveal); return; }
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
    private void cancelParsing() { generation++; if (parsing != null) parsing.cancel(); parsing = null; }
    private void startParsing() {
        if (parsed || parsing != null || !value.allowsJson || !isShown() || getWindowVisibility() != VISIBLE) return;
        int first = 0;
        while (first < Math.min(content.length(), 128) && (content.charAt(first) == ' ' || content.charAt(first) == '\n' || content.charAt(first) == '\r' || content.charAt(first) == '\t')) first++;
        if (first < Math.min(content.length(), 128) && content.charAt(first) != '{' && content.charAt(first) != '[') { parsed = true; return; }
        int ticket = ++generation; parserNotice = R.string.osservio_json_loading;
        WeakReference<InspectorContentReader> owner = new WeakReference<>(this);
        parsing = InspectorJsonTask.submit(content, (document, failure) -> {
            InspectorContentReader reader = owner.get();
            if (reader == null || reader.generation != ticket || !reader.isAttachedToWindow()) return;
            reader.parsing = null; reader.parsed = true; reader.parserNotice = 0;
            if (document != null) { reader.tree.display(document); reader.mode.setVisibility(VISIBLE); }
            else if (failure == InspectorJsonDocument.Failure.INVALID) reader.parserNotice = R.string.osservio_json_invalid;
            else if (failure == InspectorJsonDocument.Failure.LIMITED) reader.parserNotice = R.string.osservio_json_limited;
            else if (failure == InspectorJsonDocument.Failure.CANCELLED) { reader.parsed = false; return; }
            reader.highlight(!reader.matches.matches.isEmpty());
        });
        if (parsing == null) parserNotice = R.string.osservio_json_busy;
        highlight(false);
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (tree == null) return;
        if (isShown() && isAttachedToWindow()) {
            if (searchDirty && searching.getVisibility() == VISIBLE) { handler.removeCallbacks(searchContent); handler.post(searchContent); }
            startParsing();
        } else { cancelParsing(); handler.removeCallbacks(searchContent); }
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (tree == null) return;
        if (visibility == VISIBLE && isAttachedToWindow()) startParsing(); else cancelParsing();
    }
    @Override protected void onDetachedFromWindow() { cancelParsing(); handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow(); }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (searchDirty && searching.getVisibility() == VISIBLE) handler.post(searchContent);
        startParsing();
    }
}
