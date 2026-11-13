package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises sheet dismissal and actual surface/inset geometry without consumer data. */
@RunWith(AndroidJUnit4.class)
public final class InspectorSheetTest {
    @Test public void logContextSelectionIsOptInAndDoesNotReadAgainAfterEviction() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            String id = "11111111-1111-1111-1111-111111111111", value = "{\"arguments\":[\"captured\"]}";
            LogEvent event = LogEvent.restore(id, 1, 1, LogLevel.INFO, "fixture", "Summary", false, false,
                LogDetailsReference.restore(LogDetailsReference.State.AVAILABLE, id, value.length()));
            LogSnapshot source = new LogSnapshot(java.util.Collections.singletonList(event), event.textByteCount, 0, 1);
            IncidentSnapshot mark = IncidentSnapshot.capture(null, null, null, IncidentLogs.capture(source, 3), null, null);
            LogDetailsContent[] payload = {LogDetailsContent.restore(id, value)}; int[] reads = {0};
            LogDetailsHistory history = new LogDetailsHistory() {
                @Override public Lookup details(String key) { reads[0]++; return payload[0] == null ? Lookup.unavailable() : Lookup.available(payload[0]); }
                @Override public LogSnapshot snapshot() { fail("Composer must not refresh metadata"); return null; }
                @Override public void clear() { payload[0] = null; }
            };
            Context base = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            FrameLayout container = new FrameLayout(base); InspectorNavigation navigation = new InspectorNavigation(base, new View(base), container, new View(base));
            Context context = new InspectorNavigation.OwnerContext(base, navigation, 0);
            IncidentSnapshot[] saved = {null};
            IncidentArchive archive = new NoWritesArchive() {
                @Override public void save(IncidentSnapshot snapshot, Completion completion) { saved[0] = snapshot; completion.complete(null); }
            };
            IncidentComposer composer = new IncidentComposer(context, mark, archive, completion -> { }, null, null, history, () -> { }, () -> { });
            composer.show();
            ((Switch) text(container, context.getString(R.string.osservio_incident_include_environment))).setChecked(false);
            Switch details = (Switch) text(container, context.getString(R.string.osservio_incident_include_log_details));
            assertFalse(details.isChecked()); assertEquals(0, reads[0]); details.setChecked(true); assertEquals(1, reads[0]);
            InspectorReadContent opened = InspectorLogDetails.content(context, event, history, null); history.clear();
            int readCount = reads[0]; details.setChecked(false); details.setChecked(true); assertEquals(readCount, reads[0]);
            assertEquals(value, opened.text); assertTrue(opened.available);
            text(container, context.getString(R.string.osservio_incident_save)).performClick();
            assertNotNull(saved[0]); assertEquals(value, saved[0].logs.payloads.get(id).text); assertEquals(3, saved[0].logs.observedAtMillis);
            composer.dismiss(); navigation.dispose();
        });
    }
    @Test public void sheetSurfaceReachesBottomWhileContentRespectsNavigationAndKeyboardInsets() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            for (boolean compact : new boolean[] {false, true}) for (int bottom : new int[] {24, 280}) {
                TextView content = new TextView(context); content.setText("Content above the system area");
                InspectorScreen sheet = InspectorDialogs.panel(context, R.string.osservio_ui_filters, content);
                if (compact) sheet.compact();
                sheet.view.setPadding(0, dp(context, 24), 0, dp(context, bottom));
                layout(sheet.view, 320, 640);
                ViewGroup card = (ViewGroup) sheet.view.getChildAt(0);
                assertEquals(sheet.view.getHeight(), card.getBottom());
                assertTrue(card.getTop() > sheet.view.getPaddingTop());
                assertTrue(card.getTop() + content.getBottom() <= sheet.view.getHeight() - sheet.view.getPaddingBottom());
                Bitmap pixels = Bitmap.createBitmap(card.getWidth(), card.getHeight(), Bitmap.Config.ARGB_8888);
                card.getBackground().setBounds(0, 0, card.getWidth(), card.getHeight());
                card.getBackground().draw(new Canvas(pixels));
                assertEquals(0, Color.alpha(pixels.getPixel(1, 1)));
                assertEquals(0, Color.alpha(pixels.getPixel(card.getWidth() - 2, 1)));
                assertEquals(InspectorTheme.canvas(context), pixels.getPixel(1, card.getHeight() - 2));
                assertEquals(InspectorTheme.canvas(context), pixels.getPixel(card.getWidth() - 2, card.getHeight() - 2));
                pixels.recycle();
            }
        });
    }

    @Test public void draftCancelCanContinueOrDiscardAndHostHideAlwaysRemovesPanels() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            FrameLayout container = new FrameLayout(base);
            InspectorNavigation navigation = new InspectorNavigation(base, new View(base), container, new View(base));
            navigation.setMinimize(() -> fail("A sheet must not minimize the inspector"));
            Context context = new InspectorNavigation.OwnerContext(base, navigation, 0);
            int[] dismissed = {0};
            IncidentArchive archive = new NoWritesArchive();
            Runnable open = () -> new IncidentComposer(context, IncidentSnapshot.capture(null, null, null), archive,
                completion -> { }, null, null, () -> fail("Dismissal saved a snapshot"), () -> dismissed[0]++).show();
            open.run();
            assertNotNull(text(container, context.getString(R.string.osservio_archive_cancel)));
            navigation.back(); assertTrue(navigation.isAtRoot()); assertEquals(1, dismissed[0]);
            open.run();
            Switch attachment = (Switch) text(container, context.getString(R.string.osservio_incident_include_environment));
            attachment.setChecked(false);
            navigation.back();
            Button keep = (Button) text(container, context.getString(R.string.osservio_incident_keep_editing));
            assertNotNull(keep); keep.performClick();
            assertFalse(navigation.isAtRoot()); assertFalse(attachment.isChecked());
            navigation.back();
            text(container, context.getString(R.string.osservio_incident_discard)).performClick();
            assertTrue(navigation.isAtRoot()); assertEquals(2, dismissed[0]);
            open.run();
            ((Switch) text(container, context.getString(R.string.osservio_incident_include_environment))).setChecked(false);
            navigation.back(); // The host may hide while the confirmation itself is open.
            navigation.discardPanels();
            assertTrue(navigation.isAtRoot()); assertEquals(3, dismissed[0]); assertEquals(0, container.getChildCount());
            InspectorScreen pending = InspectorDialogs.panel(context, R.string.osservio_incident_share, new View(context));
            int[] cancellations = {0}; pending.setOnCancelListener(ignored -> cancellations[0]++);
            pending.show(); navigation.discardPanels();
            assertEquals(1, cancellations[0]); assertTrue(navigation.isAtRoot());
            navigation.dispose();
        });
    }
    private static int dp(Context context, int value) { return InspectorTheme.dp(context, value); }
    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(view.getContext(), width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(view.getContext(), height), View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
    private static TextView text(View view, String value) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            TextView found = text(((ViewGroup) view).getChildAt(index), value); if (found != null) return found;
        }
        return null;
    }
    private static class NoWritesArchive implements IncidentArchive {
        @Override public PersistenceStatus persistenceStatus() { return null; }
        @Override public void save(IncidentSnapshot snapshot, Completion completion) { fail("Dismissal must not write"); }
        @Override public void readIncidents(SummariesCompletion completion) { fail("Composer must not query the archive"); }
        @Override public void readIncident(String id, SnapshotCompletion completion) { fail("Composer must not query the archive"); }
        @Override public void deleteIncident(String id, Completion completion) { fail("Composer must not delete"); }
        @Override public void deleteAllIncidents(Completion completion) { fail("Composer must not delete"); }
    }
}
