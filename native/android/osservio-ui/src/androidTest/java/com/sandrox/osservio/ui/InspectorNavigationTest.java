package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Geometry and view-state checks; real gestures and attached windows are consumer tests. */
@RunWith(AndroidJUnit4.class)
public final class InspectorNavigationTest {
    @Test public void protocolChoicesStackWhenLargeTextCannotFitWithoutChangingSelection() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Configuration config = new Configuration(base.getResources().getConfiguration()); config.fontScale = 2;
            Context context = InspectorTheme.context(base.createConfigurationContext(config));
            int[] calls = {0};
            android.widget.RadioGroup choices = (android.widget.RadioGroup) InspectorWidgets.segments(context,
                new String[] {"All", "HTTP", "WebSocket"}, 2, index -> calls[0]++);
            layout(choices, 300, 600);
            assertEquals(android.widget.LinearLayout.VERTICAL, choices.getOrientation());
            for (int index = 0; index < choices.getChildCount(); index++) assertEquals(1, ((android.widget.RadioButton) choices.getChildAt(index)).getLineCount());
            assertTrue(((android.widget.RadioButton) choices.getChildAt(2)).isChecked()); assertEquals(0, calls[0]);
            layout(choices, 1000, 600); assertEquals(android.widget.LinearLayout.HORIZONTAL, choices.getOrientation());
            assertTrue(((android.widget.RadioButton) choices.getChildAt(2)).isChecked()); assertEquals(0, calls[0]);
        });
    }
    @Test public void fourSectionsPreserveSearchAndCaptureInShortLargeTextLayouts() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Configuration configuration = new Configuration(base.getResources().getConfiguration()); configuration.fontScale = 2;
            Context context = base.createConfigurationContext(configuration);
            CaptureController capture = new CaptureController(); capture.startDetailed();
            MemoryLogStore logs = new MemoryLogStore(); MemoryHTTPStore http = new MemoryHTTPStore(); MemoryWebSocketStore sockets = new MemoryWebSocketStore();
            InspectorView inspector = new InspectorView(context, new LogRecorder(logs, capture), logs,
                new HTTPRecorder(http, capture, new HTTPPrivacyPolicy()), http,
                new WebSocketRecorder(sockets, capture, new WebSocketPrivacyPolicy()), sockets);
            layout(inspector, 568, 180);
            EditText search = first(inspector, EditText.class); assertNotNull(search); search.setText("retained query");
            for (int label : new int[] {R.string.osservio_network, R.string.osservio_incident_title, R.string.osservio_ui_app, R.string.osservio_logs}) {
                View tab = description(inspector, label); assertNotNull(tab); assertTrue(tab.performClick()); layout(inspector, 568, 180);
                assertTrue(tab.isSelected()); assertTrue(tab.getHeight() >= InspectorTheme.dp(context, 48));
            }
            assertEquals("retained query", first(inspector, EditText.class).getText().toString());
            int readingHeight = first(inspector, ListView.class).getHeight();
            assertTrue("Short-window reading height: " + readingHeight, readingHeight >= InspectorTheme.dp(context, 48));
            assertEquals(CaptureController.Mode.DETAILED, capture.status().mode);
            assertTrue(capture.stopAll());
        });
    }
    @Test public void contentRemainsReachableWithExpandedSearchAndLargeText() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Configuration configuration = new Configuration(base.getResources().getConfiguration()); configuration.fontScale = 2;
            Context context = InspectorTheme.context(base.createConfigurationContext(configuration));
            InspectorContentReader reader = new InspectorContentReader(context); reader.display("Line one\nLine two\nLine three", true);
            layout(reader, 568, 180);
            View find = description(reader, R.string.osservio_ui_search_content);
            assertNotNull(find); assertTrue(find instanceof EditText); ((EditText) find).setText("Line");
            layout(reader, 568, 180);
            List<ScrollView> scrolling = new ArrayList<>(); collect(reader, ScrollView.class, scrolling);
            assertEquals(2, scrolling.size());
            for (ScrollView scroll : scrolling) assertTrue(scroll.getHeight() >= InspectorTheme.dp(context, 48));
        });
    }
    @Test public void tabStacksKeepReadersAndTemporaryPanelsDoNotSurviveCollapse() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            CaptureController capture = new CaptureController(true, false, LogClock.SYSTEM);
            MemoryLogStore logs = new MemoryLogStore(); MemoryHTTPStore http = new MemoryHTTPStore();
            InspectorView inspector = new InspectorView(context, new LogRecorder(logs, capture), logs,
                new HTTPRecorder(http, capture, new HTTPPrivacyPolicy()), http);
            inspector.setOnMinimizeListener(() -> { }); layout(inspector, 390, 844);
            View debug = description(inspector, R.string.osservio_ui_debug_enable); assertNotNull(debug); debug.performClick();
            assertTrue(capture.status().debugEnabled);
            InspectorNavigation navigation = InspectorNavigation.from(inspector.getContext());
            InspectorScreen reader = InspectorDialogs.screen(inspector.getContext(), "reader", new android.widget.TextView(context));
            reader.show(); layout(inspector, 390, 844);
            assertFalse(navigation.isAtRoot()); assertFalse(debug.isShown());
            View network = description(inspector, R.string.osservio_network); assertNotNull(network); network.performClick();
            assertTrue(navigation.isAtRoot()); assertEquals(View.GONE, reader.view.getVisibility());
            description(inspector, R.string.osservio_logs).performClick();
            assertEquals(View.VISIBLE, reader.view.getVisibility());
            final boolean[] applied = {false};
            InspectorScreen filters = InspectorFilterSheet.show(inspector.getContext(), new InspectorQuery(), new ArrayList<>(), query -> applied[0] = true);
            assertNull(description(filters.view, R.string.osservio_ui_minimize));
            inspector.discardTransientPanels(); assertFalse(filters.isShowing()); assertFalse(applied[0]);
            assertTrue(reader.isShowing()); assertTrue(inspector.navigateBack()); assertFalse(reader.isShowing());
            description(inspector, R.string.osservio_ui_debug_disable).performClick();
            assertEquals(CaptureController.Mode.BASELINE, capture.status().mode);
            inspector.disposePresentation();
        });
    }

    @Test public void runtimeFilterKindsRemainVisibleWithoutNetworkScopeOrHeaderGap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            CaptureController capture = new CaptureController(); MemoryLogStore logs = new MemoryLogStore();
            MemoryHTTPStore requests = new MemoryHTTPStore();
            InspectorView inspector = new InspectorView(context, new LogRecorder(logs, capture), logs, new HTTPRecorder(requests, capture, new HTTPPrivacyPolicy()), requests);
            layout(inspector, 390, 844);
            InspectorFilterSheet.Group kinds = new InspectorFilterSheet.Group(InspectorQuery.Field.KIND, context.getString(R.string.osservio_ui_kind))
                .option("APP_ACTIVE", "Active");
            InspectorQuery[] applied = {null};
            InspectorScreen filters = InspectorFilterSheet.show(inspector.getContext(), new InspectorQuery(), java.util.Collections.singletonList(kinds), value -> applied[0] = value);
            layout(inspector, 390, 844);
            ListView list = first(filters.view, ListView.class); assertNotNull(list);
            assertEquals("The overview must not reserve a choice-search header", 0, list.getHeaderViewsCount());
            List<InspectorWidgets.ValueRow> summaries = new ArrayList<>(); collect(filters.view, InspectorWidgets.ValueRow.class, summaries);
            InspectorWidgets.ValueRow group = null;
            for (InspectorWidgets.ValueRow row : summaries) if (row.title.getText().toString().equals(kinds.title)) group = row;
            assertNotNull(group); group.performClick(); layout(inspector, 390, 844);
            for (int index = 0; index < list.getCount(); index++) {
                View row = list.getAdapter().getView(index, null, list);
                if (row instanceof android.widget.CheckedTextView && ((android.widget.CheckedTextView) row).getText().toString().equals("Active")) {
                    list.performItemClick(row, index, index); break;
                }
            }
            List<Button> buttons = new ArrayList<>(); collect(filters.view, Button.class, buttons);
            Button apply = null; for (Button button : buttons) if (button.getText().toString().equals(button.getContext().getString(R.string.osservio_ui_apply))) apply = button;
            assertNotNull(apply); apply.performClick();
            assertNotNull(applied[0]); assertTrue(applied[0].allows(InspectorQuery.Field.KIND, "APP_ACTIVE"));
            assertEquals(java.util.Collections.singleton("APP_ACTIVE"), applied[0].values.get(InspectorQuery.Field.KIND));
            assertNull(applied[0].transport()); inspector.disposePresentation(); capture.stopAll();
        });
    }

    @Test public void savedConnectionContextCannotBeClearedByFilterReset() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            CaptureController capture = new CaptureController(); capture.startDetailed();
            try {
                MemoryWebSocketStore store = new MemoryWebSocketStore();
                WebSocketRecorder recorder = new WebSocketRecorder(store, capture, new WebSocketPrivacyPolicy());
                recorder.begin("wss://example.test/first", java.util.Collections.emptyList(), WebSocketEvent.Source.HOST);
                recorder.begin("wss://example.test/second", java.util.Collections.emptyList(), WebSocketEvent.Source.HOST);
                IncidentWebSockets saved = IncidentWebSockets.capture(store.snapshot(), System.currentTimeMillis(), store, false);
                IncidentEvidenceView viewer = new IncidentEvidenceView(context, saved, () -> { });
                viewer.scopeConnection(saved.events.get(0).connectionId); layout(viewer, 390, 844);
                ListView list = first(viewer, ListView.class); InspectorFeedHeader header = first(viewer, InspectorFeedHeader.class);
                assertEquals(1, list.getCount() - list.getHeaderViewsCount() - list.getFooterViewsCount());
                InspectorQuery query = new InspectorQuery(); query.select(InspectorQuery.Field.DIRECTION, "INCOMING"); header.onQueryChange.apply(query);
                assertEquals(0, list.getCount() - list.getHeaderViewsCount() - list.getFooterViewsCount());
                query.resetFilters(); header.onQueryChange.apply(query);
                assertEquals(1, list.getCount() - list.getHeaderViewsCount() - list.getFooterViewsCount());
            } finally { capture.stopAll(); }
        });
    }

    @Test public void retainedPageRebindsPreferencesWithoutReplacingQueryOrJsonExpansion() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            boolean wasEnglish = InspectorPreferences.english(base), wasDark = InspectorPreferences.dark(base);
            InspectorPalette wasPalette = InspectorPreferences.palette(base);
            try {
                InspectorPreferences.setEnglish(base, false); InspectorPreferences.setDark(base, false);
                android.widget.FrameLayout container = new android.widget.FrameLayout(base);
                View workspace = new View(base);
                InspectorNavigation navigation = new InspectorNavigation(base, new View(base), container, workspace);
                Context context = new InspectorNavigation.OwnerContext(InspectorTheme.context(base), navigation, 0);
                assertSame(context, InspectorTheme.context(context));
                InspectorContentReader reader = new InspectorContentReader(context);
                reader.display("{\"profile\":{\"city\":\"Paris\"}}", true);
                List<InspectorJsonTreeView> trees = new ArrayList<>();
                collectAll(reader, InspectorJsonTreeView.class, trees);
                InspectorJsonTreeView tree = trees.get(0);
                try { tree.display(InspectorJsonDocument.parse("{\"profile\":{\"city\":\"Paris\"}}", () -> false)); }
                catch (InspectorJsonDocument.ParseFailure error) { throw new AssertionError(error); }
                tree.performItemClick(tree.getAdapter().getView(1, null, tree), 1, tree.getAdapter().getItemId(1));
                int expanded = tree.getAdapter().getCount();
                description(reader, R.string.osservio_ui_search_content).performClick();
                first(reader, EditText.class).setText("city");
                InspectorScreen screen = InspectorDialogs.screen(context, R.string.osservio_http_response_body, reader); screen.show();
                InspectorPreferences.setEnglish(base, true); InspectorPreferences.setDark(base, true); InspectorPreferences.setPalette(base, InspectorPalette.BLUE);
                navigation.refreshAppearance(InspectorTheme.context(base));
                assertEquals("city", first(reader, EditText.class).getText().toString());
                assertEquals(expanded, tree.getAdapter().getCount());
                assertNotNull(description(screen.view, R.string.osservio_ui_copy_all));
                assertEquals("Copy all", description(screen.view, R.string.osservio_ui_copy_all).getContentDescription().toString());
                assertEquals(InspectorTheme.canvas(InspectorTheme.context(base)), InspectorTheme.canvas(context));
                assertTrue(screen.isShowing()); navigation.dispose();
            } finally { InspectorPreferences.setEnglish(base, wasEnglish); InspectorPreferences.setDark(base, wasDark); InspectorPreferences.setPalette(base, wasPalette); }
        });
    }
    @Test public void bottomTabsOverlayScrollableContentWithoutHidingTheLastRowOrInsetGrowth() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            CaptureController capture = new CaptureController();
            MemoryLogStore logs = new MemoryLogStore(); MemoryHTTPStore http = new MemoryHTTPStore();
            InspectorView inspector = new InspectorView(context, new LogRecorder(logs, capture), logs,
                new HTTPRecorder(http, capture, new HTTPPrivacyPolicy()), http);
            inspector.setOnMinimizeListener(() -> { });
            for (int i = 0; i < 3; i++) { inspector.requestLayout(); layout(inspector, 390, 844); }
            ListView list = first(inspector, ListView.class);
            View tab = description(inspector, R.string.osservio_logs);
            android.graphics.Rect listRect = new android.graphics.Rect(0, 0, list.getWidth(), list.getHeight());
            android.graphics.Rect tabRect = new android.graphics.Rect(0, 0, tab.getWidth(), tab.getHeight());
            inspector.offsetDescendantRectToMyCoords(list, listRect); inspector.offsetDescendantRectToMyCoords(tab, tabRect);
            assertTrue("History continues behind the tab bar", listRect.bottom > tabRect.bottom);
            assertTrue("Last row can scroll above the controls", list.getPaddingBottom() >= listRect.bottom - tabRect.top);
            int inset = list.getPaddingBottom();
            for (int i = 0; i < 4; i++) { inspector.requestLayout(); layout(inspector, 390, 844); }
            assertEquals("Insets must not accumulate", inset, list.getPaddingBottom());
            InspectorNavigation navigation = InspectorNavigation.from(inspector.getContext());
            ScrollView scroll = new InspectorScroll.Page(inspector.getContext());
            android.widget.TextView evidence = new android.widget.TextView(context); StringBuilder lines = new StringBuilder(); for (int row = 0; row < 100; row++) lines.append("Retained detail\n");
            evidence.setText(lines); scroll.addView(evidence);
            InspectorScreen reader = InspectorDialogs.screen(inspector.getContext(), "Detail", scroll); reader.show();
            for (int i = 0; i < 3; i++) { inspector.requestLayout(); layout(inspector, 390, 844); }
            assertEquals(inset, scroll.getPaddingBottom());
            scroll.scrollTo(0, InspectorTheme.dp(context, 200));
            for (int i = 0; i < 3; i++) { inspector.requestLayout(); layout(inspector, 390, 844); }
            assertEquals("Scrolling must not remove or accumulate the safe reading inset", inset, scroll.getPaddingBottom());
            android.graphics.Rect target = new android.graphics.Rect(0, scroll.getScrollY() + scroll.getHeight() - 50,
                30, scroll.getScrollY() + scroll.getHeight() - 10);
            int requestedBottom = target.bottom;
            evidence.requestRectangleOnScreen(target, true);
            assertTrue("Requested content stays above the bottom controls",
                requestedBottom - scroll.getScrollY() <= scroll.getHeight() - scroll.getPaddingBottom());
            InspectorScreen panel = InspectorDialogs.panel(inspector.getContext(), R.string.osservio_ui_filters, new ScrollView(context)); panel.show();
            layout(inspector, 390, 844);
            assertEquals("Sheets cover tabs and receive no tab inset", 0, first(panel.view, ScrollView.class).getPaddingBottom());
            navigation.dispose();
        });
    }

    private static <T extends View> void collectAll(View view, Class<T> type, List<T> result) {
        if (type.isInstance(view)) result.add(type.cast(view));
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collectAll(((ViewGroup) view).getChildAt(i), type, result);
    }

    private static void layout(View view, int width, int height) {
        int w = InspectorTheme.dp(view.getContext(), width), h = InspectorTheme.dp(view.getContext(), height);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
    }
    private static View description(View view, int label) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.getContentDescription() != null && view.getContext().getString(label).contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = description(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    private static <T extends View> T first(View view, Class<T> type) {
        List<T> values = new ArrayList<>(); collect(view, type, values); return values.isEmpty() ? null : values.get(0);
    }
    private static <T extends View> void collect(View view, Class<T> type, List<T> result) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (type.isInstance(view)) result.add(type.cast(view));
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), type, result);
    }
}
