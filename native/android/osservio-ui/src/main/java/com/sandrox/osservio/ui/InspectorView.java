package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.BreadcrumbArchive;
import com.sandrox.osservio.core.EnvironmentProvider;
import com.sandrox.osservio.core.IncidentArchive;
import com.sandrox.osservio.core.IncidentExporter;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.HTTPRecorder;
import com.sandrox.osservio.core.LogHistory;
import com.sandrox.osservio.core.LogRecorder;
import com.sandrox.osservio.core.WebSocketRecorder;
import com.sandrox.osservio.core.WebSocketHistory;
import java.util.Objects;

/** Native inspector with independent language and appearance. Hosts own presentation and outer insets. */
// Runtime dependencies are explicit; XML inflation is not supported.
@SuppressLint("ViewConstructor")
public final class InspectorView extends LinearLayout {
    /** Lets a presenting host match its window bars to the inspector without changing the host theme. */
    public interface AppearanceListener { void changed(boolean dark); }
    private final LogRecorder logRecorder;
    private final LogHistory logHistory;
    private final HTTPRecorder httpRecorder;
    private final HTTPHistory httpHistory;
    private final WebSocketRecorder socketRecorder;
    private final WebSocketHistory socketHistory;
    private final EnvironmentProvider environment;
    private final BreadcrumbRecorder breadcrumbRecorder;
    private final BreadcrumbHistory breadcrumbHistory;
    private final BreadcrumbArchive breadcrumbArchive;
    private final IncidentArchive incidentArchive;
    private final IncidentExporter exporter;
    private final LogInspectorView.State logState = new LogInspectorView.State();
    private final NetworkInspectorView.State networkState = new NetworkInspectorView.State();
    private final AppInspectorView.State appState = new AppInspectorView.State();
    private final IncidentInspectorView.State incidentState = new IncidentInspectorView.State();
    private final View[] pages = new View[4];
    private final LinearLayout[] tabs = new LinearLayout[4];
    private final int[] labels = {R.string.osservio_logs, R.string.osservio_network, R.string.osservio_incident_title, R.string.osservio_ui_app};
    private final int[] icons = {R.drawable.osservio_logs, R.drawable.osservio_network, R.drawable.osservio_problems, R.drawable.osservio_app};
    private int selection;
    private Context themed;
    private FrameLayout body;
    private TextView title;
    private TextView brand;
    private Button close;
    private Runnable onClose;
    private AppearanceListener appearanceListener;
    private boolean dark;
    private boolean english;
    private final Runnable refreshPreferences = this::preferencesChanged;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferences = (storage, key) -> {
        if (InspectorPreferences.LANGUAGE.equals(key) || InspectorPreferences.APPEARANCE.equals(key)) {
            removeCallbacks(refreshPreferences); post(refreshPreferences);
        }
    };

    public InspectorView(Context context, LogRecorder logRecorder, LogHistory logHistory, HTTPRecorder httpRecorder, HTTPHistory httpHistory) {
        this(context, logRecorder, logHistory, httpRecorder, httpHistory, null, null, null, null, null, null, null, null);
    }
    public InspectorView(Context context, LogRecorder logRecorder, LogHistory logHistory, HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                         WebSocketRecorder socketRecorder, WebSocketHistory socketHistory) {
        this(context, logRecorder, logHistory, httpRecorder, httpHistory, socketRecorder, socketHistory, null, null, null, null, null, null);
    }
    public InspectorView(Context context, LogRecorder logRecorder, LogHistory logHistory, HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                         WebSocketRecorder socketRecorder, WebSocketHistory socketHistory, EnvironmentProvider environment,
                         BreadcrumbRecorder breadcrumbRecorder, BreadcrumbHistory breadcrumbHistory) {
        this(context, logRecorder, logHistory, httpRecorder, httpHistory, socketRecorder, socketHistory, environment, breadcrumbRecorder, breadcrumbHistory, null, null, null);
    }
    public InspectorView(Context context, LogRecorder logRecorder, LogHistory logHistory, HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                         WebSocketRecorder socketRecorder, WebSocketHistory socketHistory, EnvironmentProvider environment,
                         BreadcrumbRecorder breadcrumbRecorder, BreadcrumbHistory breadcrumbHistory, BreadcrumbArchive breadcrumbArchive) {
        this(context, logRecorder, logHistory, httpRecorder, httpHistory, socketRecorder, socketHistory, environment, breadcrumbRecorder, breadcrumbHistory, breadcrumbArchive, null, null);
    }
    public InspectorView(Context context, LogRecorder logRecorder, LogHistory logHistory, HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                         WebSocketRecorder socketRecorder, WebSocketHistory socketHistory, EnvironmentProvider environment,
                         BreadcrumbRecorder breadcrumbRecorder, BreadcrumbHistory breadcrumbHistory, BreadcrumbArchive breadcrumbArchive, IncidentArchive incidentArchive) {
        this(context, logRecorder, logHistory, httpRecorder, httpHistory, socketRecorder, socketHistory, environment, breadcrumbRecorder, breadcrumbHistory, breadcrumbArchive, incidentArchive, null);
    }
    public InspectorView(Context context, LogRecorder logRecorder, LogHistory logHistory, HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                         WebSocketRecorder socketRecorder, WebSocketHistory socketHistory, EnvironmentProvider environment,
                         BreadcrumbRecorder breadcrumbRecorder, BreadcrumbHistory breadcrumbHistory, BreadcrumbArchive breadcrumbArchive,
                         IncidentArchive incidentArchive, IncidentExporter exporter) {
        super(context);
        this.logRecorder = Objects.requireNonNull(logRecorder, "logRecorder"); this.logHistory = Objects.requireNonNull(logHistory, "logHistory");
        this.httpRecorder = Objects.requireNonNull(httpRecorder, "httpRecorder"); this.httpHistory = Objects.requireNonNull(httpHistory, "httpHistory");
        if ((socketRecorder == null) != (socketHistory == null)) throw new IllegalArgumentException("WebSocket recorder and history must be supplied together");
        if ((breadcrumbRecorder == null) != (breadcrumbHistory == null)) throw new IllegalArgumentException("Breadcrumb recorder and history must be supplied together");
        if (incidentArchive != null && (environment == null || breadcrumbHistory == null)) throw new IllegalArgumentException("Problem inspection requires environment and breadcrumbs");
        this.socketRecorder = socketRecorder; this.socketHistory = socketHistory; this.environment = environment;
        this.breadcrumbRecorder = breadcrumbRecorder; this.breadcrumbHistory = breadcrumbHistory; this.breadcrumbArchive = breadcrumbArchive;
        this.incidentArchive = incidentArchive; this.exporter = exporter;
        setOrientation(VERTICAL); rebuild();
    }
    /** Optional close affordance. The callback belongs to the presenting host. Main thread only. */
    public void setOnCloseListener(Runnable listener) { onClose = listener; close.setVisibility(listener == null ? GONE : VISIBLE); }
    /** Immediately reports the current inspector appearance, then subsequent appearance changes. */
    public void setAppearanceListener(AppearanceListener listener) {
        appearanceListener = listener; if (listener != null) listener.changed(dark);
    }
    /** Returns true when Back was consumed by an embedded problem or evidence page. Main thread only. */
    public boolean navigateBack() {
        return pages[selection] instanceof IncidentInspectorView && ((IncidentInspectorView) pages[selection]).navigateBack();
    }
    private void rebuild() {
        for (View page : pages) savePosition(page);
        removeAllViews();
        for (int i = 0; i < pages.length; i++) pages[i] = null;
        dark = InspectorPreferences.dark(getContext()); english = InspectorPreferences.english(getContext());
        themed = InspectorTheme.context(getContext()); setBackgroundColor(InspectorTheme.canvas(themed));
        LinearLayout bar = new LinearLayout(themed); bar.setGravity(Gravity.CENTER_VERTICAL);
        int inset = InspectorTheme.dp(themed, 16); bar.setPadding(inset, 0, inset, 0);
        LinearLayout heading = new LinearLayout(themed); heading.setOrientation(VERTICAL);
        title = InspectorTheme.text(themed, 22, true); heading.addView(title);
        brand = InspectorWidgets.caption(themed); brand.setText(R.string.osservio_product_name); brand.setPadding(0, 0, 0, InspectorTheme.dp(themed, 6));
        heading.addView(brand); bar.addView(heading, new LayoutParams(0, -2, 1));
        close = InspectorWidgets.button(themed, R.string.osservio_close, () -> { if (onClose != null) onClose.run(); });
        close.setVisibility(onClose == null ? GONE : VISIBLE); bar.addView(close, new LayoutParams(-2, -2));
        addView(bar, new LayoutParams(-1, -2)); addView(InspectorWidgets.divider(themed));
        body = new FrameLayout(themed); addView(body, new LayoutParams(-1, 0, 1));
        addView(InspectorWidgets.divider(themed));
        LinearLayout navigation = new LinearLayout(themed); navigation.setBackgroundColor(InspectorTheme.surface(themed));
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            LinearLayout tab = new LinearLayout(themed); tab.setOrientation(VERTICAL); tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, InspectorTheme.dp(themed, 9), 0, InspectorTheme.dp(themed, 9));
            tab.setMinimumHeight(InspectorTheme.dp(themed, 64)); tab.setFocusable(true); tab.setContentDescription(themed.getString(labels[i]));
            tab.setOnClickListener(ignored -> select(index)); tab.setBackgroundResource(android.R.drawable.list_selector_background);
            tab.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
                }
            });
            ImageView icon = new ImageView(themed); icon.setImageResource(icons[i]); icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            tab.addView(icon, new LayoutParams(InspectorTheme.dp(themed, 22), InspectorTheme.dp(themed, 22)));
            TextView label = InspectorTheme.text(themed, 12, false); label.setText(labels[i]); label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); label.setPadding(0, InspectorTheme.dp(themed, 4), 0, 0);
            tab.addView(label, new LayoutParams(-1, -2)); tabs[i] = tab; navigation.addView(tab, new LayoutParams(0, -2, 1));
        }
        addView(navigation, new LayoutParams(-1, -2)); select(selection);
        compactChrome(getHeight());
    }
    private void select(int index) {
        View focused = findFocus(); if (focused != null) focused.clearFocus();
        InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        savePosition(pages[selection]); selection = index;
        if (pages[index] == null) {
            if (index == 0) pages[index] = new LogInspectorView(themed, logRecorder, logHistory, logState);
            else if (index == 1) pages[index] = new NetworkInspectorView(themed, httpRecorder, httpHistory, socketRecorder, socketHistory, networkState);
            else if (index == 2) pages[index] = incidentArchive == null ? unavailable()
                : new IncidentInspectorView(themed, incidentArchive, environment, breadcrumbHistory, logHistory, httpHistory, socketHistory, exporter, incidentState);
            else pages[index] = new AppInspectorView(themed, environment, breadcrumbRecorder, breadcrumbHistory, breadcrumbArchive, logRecorder, httpRecorder, socketRecorder, appState);
            body.addView(pages[index], new FrameLayout.LayoutParams(-1, -1));
        }
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] != null) pages[i].setVisibility(i == index ? VISIBLE : GONE);
            int color = i == index ? InspectorTheme.accent(themed) : InspectorTheme.secondary(themed);
            tabs[i].setSelected(i == index); ((ImageView) tabs[i].getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
            ((TextView) tabs[i].getChildAt(1)).setTextColor(color);
        }
        title.setText(labels[index]);
    }
    private View unavailable() {
        LinearLayout message = new LinearLayout(themed); message.setOrientation(VERTICAL); message.setGravity(Gravity.CENTER);
        int inset = InspectorTheme.dp(themed, 32); message.setPadding(inset, inset, inset, inset);
        TextView title = InspectorTheme.text(themed, 18, true); title.setText(R.string.osservio_ui_no_archive); title.setGravity(Gravity.CENTER); message.addView(title);
        TextView hint = InspectorWidgets.caption(themed); hint.setText(R.string.osservio_ui_no_archive_hint); hint.setGravity(Gravity.CENTER); message.addView(hint);
        return message;
    }
    private void savePosition(View page) {
        if (page instanceof LogInspectorView) ((LogInspectorView) page).saveReadingPosition();
        else if (page instanceof NetworkInspectorView) ((NetworkInspectorView) page).saveReadingPosition();
        else if (page instanceof AppInspectorView) ((AppInspectorView) page).saveReadingPosition();
        else if (page instanceof IncidentInspectorView) ((IncidentInspectorView) page).saveReadingPosition();
    }
    private void preferencesChanged() {
        if (dark == InspectorPreferences.dark(getContext()) && english == InspectorPreferences.english(getContext())) return;
        rebuild(); if (appearanceListener != null) appearanceListener.changed(dark);
    }
    @Override protected void onMeasure(int width, int height) {
        if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED)
            compactChrome(Math.max(0, MeasureSpec.getSize(height) - getPaddingTop() - getPaddingBottom()));
        super.onMeasure(width, height);
    }
    private void compactChrome(int height) {
        if (brand == null || height == 0) return;
        boolean compact = height < InspectorTheme.dp(themed, 360);
        brand.setVisibility(compact ? GONE : VISIBLE); title.setTextSize(compact ? 18 : 22);
        for (LinearLayout tab : tabs) if (tab != null) {
            tab.getChildAt(0).setVisibility(compact ? GONE : VISIBLE);
            tab.setMinimumHeight(InspectorTheme.dp(themed, compact ? 48 : 64));
            tab.setPadding(0, InspectorTheme.dp(themed, compact ? 4 : 9), 0, InspectorTheme.dp(themed, compact ? 4 : 9));
        }
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); InspectorPreferences.storage(getContext()).registerOnSharedPreferenceChangeListener(preferences); preferencesChanged();
    }
    @Override protected void onDetachedFromWindow() {
        InspectorPreferences.storage(getContext()).unregisterOnSharedPreferenceChangeListener(preferences); removeCallbacks(refreshPreferences); super.onDetachedFromWindow();
    }
}
