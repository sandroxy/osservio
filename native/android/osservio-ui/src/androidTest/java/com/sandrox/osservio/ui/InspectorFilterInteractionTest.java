package com.sandrox.osservio.ui;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Attached-window checks for feedback ownership while a filter changes its rows. */
@RunWith(AndroidJUnit4.class)
public final class InspectorFilterInteractionTest {
    @Test public void scopeKeepsMaterialAcrossPickerAndOverviewRecycling() throws Exception {
        InspectorNavigationMaterialActivity host = InspectorWindowTestSupport.start(InspectorNavigationMaterialActivity.class);
        try {
            InspectorScreen sheet = sheet(host);
            await(() -> scope(sheet) != null && scope(sheet).material.renderer != null);
            for (int iteration = 0; iteration < 3; iteration++) {
                InspectorSegmentControl previous = scope(sheet);
                tap(sheet, R.string.osservio_ui_http_status);
                await(() -> button(sheet, R.string.osservio_ui_back) != null);
                await(() -> previous.material.renderer == null);
                tap(sheet, R.string.osservio_ui_back);
                await(() -> scope(sheet) != null);
                Thread.sleep(200);
                main(() -> {
                    assertTrue(InspectorNavigation.from(host.inspector.getContext()).materials.allowed());
                    InspectorSegmentControl control = scope(sheet);
                    assertNotNull("Scope lost its renderer: attached=" + control.isAttachedToWindow()
                        + " shown=" + control.isShown() + " focus=" + control.hasWindowFocus()
                        + " orientation=" + control.getOrientation() + " size=" + control.getWidth() + "," + control.getHeight(), control.material.renderer);
                });
                tap(sheet, R.string.osservio_ui_time);
                tap(sheet, R.string.osservio_ui_time_5);
                await(() -> scope(sheet) != null && scope(sheet).material.renderer != null);
            }
            tap(sheet, R.string.osservio_ui_time);
            tap(sheet, R.string.osservio_ui_filter_refine);
            main(() -> {
                ListView list = InspectorNavigationMaterialActivity.find(sheet.view, ListView.class);
                list.setSelection(list.getCount() - 1);
            });
            await(() -> scope(sheet) == null);
            main(() -> InspectorNavigationMaterialActivity.find(sheet.view, ListView.class).setSelection(0));
            await(() -> scope(sheet) != null && scope(sheet).material.renderer != null);
        } finally { main(host::finish); }
    }

    @Test public void recentRemovalUsesTheSameIconAndDoesNotLoadThePreset() throws Exception {
        InspectorNavigationMaterialActivity host = InspectorWindowTestSupport.start(InspectorNavigationMaterialActivity.class);
        try {
            InspectorScreen[] shown = {null}; InspectorQuery[] applied = {null};
            main(() -> {
                InspectorQuery preset = new InspectorQuery(); preset.recentMinutes = 5;
                InspectorRecentFilters recent = new InspectorRecentFilters("filter-interaction"); recent.update(preset, false);
                shown[0] = InspectorFilterSheet.show(host.inspector.getContext(), new InspectorQuery(), Collections.emptyList(),
                    R.string.osservio_ui_time, false, null, recent, result -> applied[0] = result);
            });
            InspectorScreen sheet = shown[0];
            await(() -> summary(sheet, R.string.osservio_ui_recent_filters) != null); Thread.sleep(350);
            tap(sheet, R.string.osservio_ui_recent_filters);
            View[] remove = {null};
            main(() -> {
                remove[0] = description(sheet.view, sheet.view.getContext().getString(R.string.osservio_ui_forget_filter));
                assertTrue(remove[0] instanceof android.widget.ImageButton);
                android.widget.ImageButton button = (android.widget.ImageButton) remove[0];
                assertEquals(InspectorTheme.dp(button.getContext(), 24), button.getDrawable().getBounds().width());
                assertTrue(button.getWidth() >= InspectorTheme.dp(button.getContext(), 48));
                assertTrue(button.getHeight() >= InspectorTheme.dp(button.getContext(), 48));
            });
            tap(remove[0]);
            await(() -> summary(sheet, R.string.osservio_ui_recent_filters) == null);
            tap(sheet, R.string.osservio_ui_apply);
            main(() -> { assertNotNull(applied[0]); assertEquals(0, applied[0].recentMinutes); });
        } finally { main(host::finish); }
    }

    private static View description(View view, String prefix) {
        if (view.getContentDescription() != null && view.getContentDescription().toString().startsWith(prefix)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = description(((ViewGroup) view).getChildAt(i), prefix); if (found != null) return found;
        }
        return null;
    }

    @Test public void rebuildingDoesNotMovePressFeedbackToAnotherFilter() throws Exception {
        InspectorNavigationMaterialActivity host = InspectorWindowTestSupport.start(InspectorNavigationMaterialActivity.class);
        try {
            InspectorScreen sheet = sheet(host);
            await(() -> summary(sheet, R.string.osservio_ui_time) != null);
            List<String> unexpected = new ArrayList<>();
            main(() -> sheet.view.getViewTreeObserver().addOnPreDrawListener(() -> {
                collectPressed(sheet.view, unexpected);
                return true;
            }));
            tap(sheet, R.string.osservio_ui_time);
            tap(sheet, R.string.osservio_ui_time_5);
            tap(sheet, R.string.osservio_ui_http_status);
            tap(sheet, R.string.osservio_ui_back);
            Thread.sleep(250);
            main(() -> assertTrue("Unrelated rows received press feedback: " + unexpected, unexpected.isEmpty()));
        } finally { main(host::finish); }
    }

    @Test public void recycledSummaryDoesNotStartANewRippleFromItsOldPress() throws Exception {
        InspectorNavigationMaterialActivity host = InspectorWindowTestSupport.start(InspectorNavigationMaterialActivity.class);
        try {
            InspectorScreen sheet = sheet(host);
            main(() -> {
                ListView list = InspectorNavigationMaterialActivity.find(sheet.view, ListView.class);
                InspectorFilterRow previous = summary(sheet, R.string.osservio_ui_http_status);
                previous.setPressed(true);
                int target = -1;
                for (int i = 0; i < list.getAdapter().getCount(); i++) {
                    View row = list.getAdapter().getView(i, null, list);
                    if (row instanceof InspectorFilterRow && ((InspectorFilterRow) row).title.getText().toString()
                        .equals(sheet.view.getContext().getString(R.string.osservio_ui_http_timing))) { target = i; break; }
                }
                assertTrue(target >= 0);
                View rebound = list.getAdapter().getView(target, previous, list);
                assertSame(previous, rebound);
                assertFalse("A recycled filter inherited another field's pressed state", rebound.isPressed());
                for (int state : rebound.getBackground().getState()) assertNotEquals(android.R.attr.state_pressed, state);
            });
        } finally { main(host::finish); }
    }

    @Test public void anOldRowCallbackCannotChangeTheNewDraftScreen() throws Exception {
        InspectorNavigationMaterialActivity host = InspectorWindowTestSupport.start(InspectorNavigationMaterialActivity.class);
        try {
            InspectorScreen sheet = sheet(host);
            View[] stale = {null};
            main(() -> {
                ListView list = InspectorNavigationMaterialActivity.find(sheet.view, ListView.class);
                for (int i = 0; i < list.getAdapter().getCount(); i++) {
                    View row = list.getAdapter().getView(i, null, list);
                    if (row instanceof InspectorFilterRow && ((InspectorFilterRow) row).title.getText().toString()
                        .equals(sheet.view.getContext().getString(R.string.osservio_ui_time))) { stale[0] = row; break; }
                }
                assertNotNull(stale[0]);
            });
            tap(sheet, R.string.osservio_ui_time);
            main(() -> stale[0].performClick());
            main(() -> assertNotNull("A callback from a replaced binding collapsed the current time choices",
                label(sheet.view, sheet.view.getContext().getString(R.string.osservio_ui_time_5))));
        } finally { main(host::finish); }
    }

    private static void collectPressed(View view, List<String> unexpected) {
        if (view instanceof InspectorFilterRow) {
            InspectorFilterRow row = (InspectorFilterRow) view;
            String name = row.title.getText().toString();
            if (row.isPressed() && !name.equals(view.getContext().getString(R.string.osservio_ui_time))
                && !name.equals(view.getContext().getString(R.string.osservio_ui_http_status))) unexpected.add(name);
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collectPressed(((ViewGroup) view).getChildAt(i), unexpected);
    }
    private static InspectorScreen sheet(InspectorNavigationMaterialActivity host) throws Exception {
        await(() -> host.bar().materialReady());
        InspectorScreen[] result = {null};
        main(() -> {
            InspectorQuery query = new InspectorQuery(); query.select(InspectorQuery.Field.TRANSPORT, "http");
            result[0] = InspectorFilterSheet.show(host.inspector.getContext(), query,
                InspectorFilterOptions.network(host.inspector.getContext(), Collections.emptyList(), Collections.emptyList(), query),
                true, ignored -> fail("The draft must not apply during navigation"));
        });
        await(() -> result[0].view.getHeight() > 0 && summary(result[0], R.string.osservio_ui_time) != null);
        Thread.sleep(350);
        return result[0];
    }
    private static InspectorSegmentControl scope(InspectorScreen screen) { return InspectorNavigationMaterialActivity.find(screen.view, InspectorSegmentControl.class); }
    private static InspectorFilterRow summary(InspectorScreen screen, int label) {
        View text = label(screen.view, screen.view.getContext().getString(label));
        return text != null && text.getParent() != null && text.getParent().getParent() instanceof InspectorFilterRow
            ? (InspectorFilterRow) text.getParent().getParent() : null;
    }
    private static Button button(InspectorScreen screen, int title) {
        View view = label(screen.view, screen.view.getContext().getString(title)); return view instanceof Button ? (Button) view : null;
    }
    private static View label(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText()) && view.isShown()) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = label(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    private static void tap(InspectorScreen screen, int label) throws Exception {
        View[] target = {null};
        main(() -> {
            target[0] = label(screen.view, screen.view.getContext().getString(label));
            assertNotNull("Missing " + screen.view.getContext().getString(label), target[0]);
        });
        tap(target[0]);
    }
    private static void tap(View target) throws Exception {
        float[] point = new float[2];
        main(() -> {
            android.graphics.Rect rect = new android.graphics.Rect(); assertTrue(target.getGlobalVisibleRect(rect));
            point[0] = rect.exactCenterX(); point[1] = rect.exactCenterY();
        });
        long down = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1], 0);
            try { InstrumentationRegistry.getInstrumentation().sendPointerSync(event); } finally { event.recycle(); }
            if (action == MotionEvent.ACTION_DOWN) Thread.sleep(120);
        }
        Thread.sleep(180);
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void await(Callable<Boolean> condition) throws Exception {
        long until = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < until) {
            boolean[] done = {false}; main(() -> { try { done[0] = condition.call(); } catch (Exception error) { throw new AssertionError(error); } });
            if (done[0]) return; Thread.sleep(25);
        }
        fail("Filter state did not become ready");
    }
}
