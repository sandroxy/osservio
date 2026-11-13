package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.MemoryHTTPStore;
import com.sandrox.osservio.store.MemoryLogStore;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Deletion delivery uses a controlled in-memory port; no consumer evidence is touched. */
@RunWith(AndroidJUnit4.class)
public final class InspectorRetainedDeletionTest {
    @Test public void failedDeletionKeepsTheRunReadableAndAllowsRetry() {
        Fixture[] state = new Fixture[1];
        main(() -> {
            Fixture fixture = state[0] = new Fixture(); fixture.confirm();
            assertEquals(1, fixture.archive.attempts); assertFalse(fixture.delete.isEnabled());
            fixture.archive.complete(PersistenceStatus.Error.STORAGE_FULL);
        });
        idle();
        main(() -> {
            Fixture fixture = state[0];
            assertTrue(fixture.screen.isShowing()); assertTrue(fixture.delete.isEnabled());
            assertNotNull(text(fixture.screen.view, PersistencePresentation.error(fixture.context, PersistenceStatus.Error.STORAGE_FULL)));
            fixture.confirm(); assertEquals(2, fixture.archive.attempts);
            fixture.archive.complete(null);
        });
        idle();
        main(() -> {
            Fixture fixture = state[0]; assertFalse(fixture.screen.isShowing());
            assertTrue(InspectorNavigation.from(fixture.context).isAtRoot()); fixture.close();
        });
    }
    @Test public void leavingTheRunRejectsLateDeletionNavigation() {
        Fixture[] state = new Fixture[1]; InspectorScreen[] next = new InspectorScreen[1];
        main(() -> {
            Fixture fixture = state[0] = new Fixture(); fixture.confirm();
            assertTrue(fixture.root.navigateBack());
            next[0] = InspectorDialogs.screen(fixture.context, "Another record", new TextView(fixture.context)); next[0].show();
            fixture.archive.complete(null);
        });
        idle();
        main(() -> {
            assertTrue(next[0].isShowing()); assertFalse(state[0].screen.isShowing()); state[0].close();
        });
    }
    private static final class Fixture {
        final DelayedArchive archive = new DelayedArchive();
        final CaptureController capture = new CaptureController();
        final InspectorView root;
        final Context context;
        final InspectorScreen screen;
        final View delete;
        Fixture() {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            MemoryLogStore logs = new MemoryLogStore(); MemoryHTTPStore http = new MemoryHTTPStore();
            root = new InspectorView(base, new LogRecorder(logs, capture), logs, new HTTPRecorder(http, capture, new HTTPPrivacyPolicy()), http);
            context = root.getContext();
            RetainedRun run = new RetainedRun("fixture-run", EnvironmentSnapshot.Platform.ANDROID, 123, 100, 200, 0, 0, false);
            screen = InspectorDialogs.screen(context, R.string.osservio_ui_retained_run,
                new RetainedBreadcrumbView(context, new RetainedBreadcrumbs(run, Collections.emptyList()), archive));
            screen.show();
            root.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 390, 844);
            delete = description(screen.view, context.getString(R.string.osservio_archive_delete_run)); assertNotNull(delete);
        }
        void confirm() {
            int previous = archive.attempts; assertTrue(delete.performClick());
            assertEquals("Opening confirmation must not delete", previous, archive.attempts);
            TextView confirmation = button(root, context.getString(R.string.osservio_archive_delete_run)); assertNotNull(confirmation);
            assertTrue(confirmation.performClick());
        }
        void close() { root.disposePresentation(); capture.close(); }
    }
    private static final class DelayedArchive implements BreadcrumbArchive {
        int attempts;
        Completion pending;
        @Override public void deleteRun(String id, Completion completion) {
            assertEquals("fixture-run", id); assertNull(pending); attempts++; pending = completion;
        }
        void complete(PersistenceStatus.Error error) { Completion callback = pending; pending = null; assertNotNull(callback); callback.complete(error); }
        @Override public PersistenceStatus persistenceStatus() { throw new AssertionError("No status query is needed in a frozen detail"); }
        @Override public void readRuns(RunsCompletion completion) { throw new AssertionError("No archive refresh"); }
        @Override public void readRun(String id, RunCompletion completion) { throw new AssertionError("No run refresh"); }
        @Override public void flush(Completion completion) { throw new AssertionError("No flush"); }
        @Override public void deleteAllRuns(Completion completion) { throw new AssertionError("Only the selected run may be deleted"); }
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    private static View description(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = description(((ViewGroup) view).getChildAt(i), label); if (found != null) return found; }
        return null;
    }
    private static TextView text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = text(((ViewGroup) view).getChildAt(i), value); if (found != null) return found; }
        return null;
    }
    private static TextView button(View view, String value) {
        if (view instanceof android.widget.Button && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = button(((ViewGroup) view).getChildAt(i), value); if (found != null) return found; }
        return null;
    }
}
