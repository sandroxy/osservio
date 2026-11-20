package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorFeedUpdateTest {
    @Test public void undoUsesTheViewportAndExpiresWhenTheQueryOrPageChanges() throws Exception {
        android.app.Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        InspectorMaterialTestActivity host = InspectorWindowTestSupport.start(InspectorMaterialTestActivity.class);
        InspectorFeedHeader[] header = new InspectorFeedHeader[1];
        View[] viewport = new View[1];
        android.widget.ListView[] list = new android.widget.ListView[1];
        View[] notice = new View[1];
        InspectorQuery[] active = {new InspectorQuery()};
        active[0].select(InspectorQuery.Field.LEVEL, "ERROR");
        InspectorFeedHeader.Groups groups = () -> InspectorFilterOptions.logs(header[0].getContext(), Collections.emptyList());
        try {
            runner.runOnMainSync(() -> {
                host.entry.detach();
                Context context = InspectorTheme.context(host);
                header[0] = new InspectorFeedHeader(context, R.string.osservio_search, "", text -> { }, () -> { }, target -> { }, () -> { }, true);
                list[0] = new android.widget.ListView(context);
                list[0].addHeaderView(header[0], null, false);
                list[0].setAdapter(new android.widget.ArrayAdapter<>(context, android.R.layout.simple_list_item_1, Collections.nCopies(30, "record")));
                viewport[0] = header[0].viewport(list[0]); host.setContentView(viewport[0]);
                header[0].onQueryChange = value -> { active[0] = value; header[0].setQuery(value, groups, 30, 30); };
                header[0].setQuery(active[0], groups, 30, 30); header[0].setPaused(true);
            });
            runner.waitForIdleSync();
            runner.runOnMainSync(() -> {
                View close = token(header[0], true); assertNotNull(close);
                assertTrue(close.getWidth() >= InspectorTheme.dp(close.getContext(), 48));
                close.performClick();
            });
            runner.waitForIdleSync();
            runner.runOnMainSync(() -> {
                Button undo = button(viewport[0], R.string.osservio_ui_undo_filter); assertNotNull(undo);
                notice[0] = (View) undo.getParent(); assertTrue(notice[0].isShown());
                assertSame(viewport[0], notice[0].getParent());
                assertTrue(notice[0].getTop() > viewport[0].getHeight() / 2);
                Button latest = button(viewport[0], R.string.osservio_ui_follow_latest); assertNotNull(latest);
                assertTrue(latest.getBottom() <= notice[0].getTop());
                undo.performClick(); assertTrue(active[0].allows(InspectorQuery.Field.LEVEL, "ERROR"));
                assertEquals(1, active[0].activeCount()); assertEquals(View.GONE, notice[0].getVisibility());
                token(header[0], true).performClick();
                active[0].text = "new search"; header[0].setQuery(active[0], groups, 1, 30);
                assertEquals(View.GONE, notice[0].getVisibility()); undo.performClick();
                assertEquals("new search", active[0].text); assertEquals(0, active[0].activeCount());
                active[0].select(InspectorQuery.Field.LEVEL, "ERROR"); header[0].setQuery(active[0], groups, 1, 30);
                token(header[0], true).performClick(); viewport[0].setVisibility(View.GONE);
                assertEquals(View.GONE, notice[0].getVisibility()); undo.performClick(); assertEquals(0, active[0].activeCount());
                viewport[0].setVisibility(View.VISIBLE);
                active[0].select(InspectorQuery.Field.LEVEL, "ERROR"); header[0].setQuery(active[0], groups, 1, 30);
                ((ViewGroup) viewport[0]).removeView(list[0]);
            });
            runner.waitForIdleSync();
            runner.runOnMainSync(() -> {
                assertFalse(header[0].isAttachedToWindow());
                token(header[0], true).performClick(); assertTrue(notice[0].isShown());
            });
            long deadline = android.os.SystemClock.uptimeMillis() + 9000;
            boolean[] expired = {false};
            while (!expired[0] && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(100);
                runner.runOnMainSync(() -> expired[0] = notice[0].getVisibility() == View.GONE);
            }
            assertTrue("Offscreen headers must not defer the Undo timeout until reattachment", expired[0]);
        } finally { runner.runOnMainSync(host::finish); }
    }

    private static Button button(View view, int title) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(view.getContext().getString(title))) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = button(((ViewGroup) view).getChildAt(i), title); if (found != null) return found;
        }
        return null;
    }
    @Test public void liveCountsKeepFilterControlsAndTheirCurrentQuery() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            assertEquals(context.getString(R.string.osservio_ui_path_exists, "/id"), InspectorFilterOptions.jsonCondition(context, "/id", ""));
            assertEquals("/id = 42", InspectorFilterOptions.jsonCondition(context, "/id", "42"));
            InspectorFilterSheet.Target[] editing = {null};
            InspectorFeedHeader header = new InspectorFeedHeader(context, R.string.osservio_search, "", text -> { }, () -> { }, target -> editing[0] = target, () -> { }, true);
            InspectorQuery query = new InspectorQuery(); query.select(InspectorQuery.Field.LEVEL, "ERROR"); query.text = "first";
            InspectorFeedHeader.Groups groups = () -> InspectorFilterOptions.logs(context, Collections.emptyList());
            header.setQuery(query, groups, 1, 10);
            View original = token(header, false); assertNotNull(original);
            header.setQuery(query, () -> { fail("Count-only updates must not rebuild filter candidates"); return Collections.emptyList(); }, 2, 20);
            assertSame(original, token(header, false));
            assertEquals(context.getString(R.string.osservio_ui_result_count, 2, 20), header.results.getText().toString());
            query.text = "latest"; header.setQuery(query, groups, 1, 20);
            InspectorQuery[] applied = {null}; header.onQueryChange = value -> applied[0] = value;
            View edit = token(header, false), close = token(header, true);
            assertSame(edit.getParent(), close.getParent());
            assertTrue(((ViewGroup) edit.getParent()).getClipToOutline());
            edit.setPressed(true); assertFalse(close.isPressed()); edit.setPressed(false);
            edit.performClick();
            assertEquals(InspectorQuery.Field.LEVEL, editing[0].field); assertNull(applied[0]);
            token(header, true).performClick(); assertNotNull(applied[0]);
            assertEquals("latest", applied[0].text); assertEquals(0, applied[0].activeCount());
        });
    }
    private static View token(View view, boolean remove) {
        if ((view instanceof Button || view instanceof android.widget.ImageButton) && view.getContentDescription() != null
            && view.getContentDescription().toString().contains("ERROR") && (view instanceof android.widget.ImageButton) == remove) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = token(((ViewGroup) view).getChildAt(i), remove); if (found != null) return found;
        }
        return null;
    }
}
