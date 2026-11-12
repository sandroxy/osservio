package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.MemoryBreadcrumbStore;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InspectorSavedDetailsTest {
    @Test public void historicalEnvironmentHasCopyWithoutARefreshAndKeepsItsOriginalTime() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            Map<EnvironmentSnapshot.Key, EnvironmentSnapshot.Fact> facts = new EnumMap<>(EnvironmentSnapshot.Key.class);
            for (EnvironmentSnapshot.Key key : EnvironmentSnapshot.Key.values()) facts.put(key, EnvironmentSnapshot.Fact.UNAVAILABLE);
            EnvironmentSnapshot snapshot = EnvironmentSnapshot.capture(EnvironmentSnapshot.Platform.ANDROID, facts, 123);
            EnvironmentSnapshotView content = new EnvironmentSnapshotView(context, snapshot);
            InspectorScreen page = InspectorDialogs.screen(context, R.string.osservio_runtime_environment, content);
            assertNotNull(description(page.view, context.getString(R.string.osservio_runtime_copy_environment)));
            assertNull(description(page.view, context.getString(R.string.osservio_http_refresh)));
            assertNotNull(text(page.view, RuntimePresentation.date(context, 123) + " · android"));
            assertNotNull(text(page.view, context.getString(R.string.osservio_incident_snapshot_hint)));
            assertEquals(123, snapshot.timestampMillis);
        });
    }
    @Test public void snapshotSearchHasBreathingRoomAtNormalAndLargeTextSizes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            CaptureController capture = new CaptureController(); capture.setBaselineEnabled(true);
            MemoryBreadcrumbStore store = new MemoryBreadcrumbStore();
            new BreadcrumbRecorder(store, capture).record(BreadcrumbEvent.Kind.HOST_MARKER, "fixture.event", LogMessage.publicText("visible event"), null, BreadcrumbEvent.Retention.MEMORY_ONLY);
            IncidentSnapshot snapshot = IncidentSnapshot.capture(null, null, store.snapshot());
            for (float scale : new float[] {1, 2}) {
                Configuration configuration = new Configuration(base.getResources().getConfiguration()); configuration.fontScale = scale;
                Context context = InspectorTheme.context(base.createConfigurationContext(configuration));
                IncidentSnapshotView view = new IncidentSnapshotView(context, snapshot, () -> { }, () -> fail("Layout must not delete"), null);
                int width = InspectorTheme.dp(context, 390), height = InspectorTheme.dp(context, 844);
                layout(view, width, height);
                ListView list = first(view, ListView.class); EditText search = first(view, EditText.class);
                assertNotNull(list); assertNotNull(search);
                list.setSelectionFromTop(list.getHeaderViewsCount(), InspectorTheme.dp(context, 400)); layout(view, width, height);
                View row = list.getChildAt(list.getHeaderViewsCount() - list.getFirstVisiblePosition()); assertNotNull(row);
                Rect searchBounds = new Rect(), rowBounds = new Rect(); search.getDrawingRect(searchBounds); row.getDrawingRect(rowBounds);
                view.offsetDescendantRectToMyCoords(search, searchBounds); view.offsetDescendantRectToMyCoords(row, rowBounds);
                assertTrue("Search and rows must remain separated at scale " + scale, rowBounds.top - searchBounds.bottom >= InspectorTheme.dp(context, 10));
            }
            capture.close();
        });
    }
    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height);
    }
    private static View description(View view, String value) {
        if (value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()) && view.getVisibility() == View.VISIBLE) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = description(((ViewGroup) view).getChildAt(i), value); if (found != null) return found; }
        return null;
    }
    private static TextView text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = text(((ViewGroup) view).getChildAt(i), value); if (found != null) return found; }
        return null;
    }
    private static <T extends View> T first(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { T found = first(((ViewGroup) view).getChildAt(i), type); if (found != null) return found; }
        return null;
    }
}
