package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.CaptureController;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InspectorPresentationTest {
    @Test public void scopedThemePreservesHostFontScaling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            android.content.res.Configuration configuration = new android.content.res.Configuration(); configuration.fontScale = 2;
            Context configured = base.createConfigurationContext(configuration);
            assertEquals("Host override", 2f, configured.getResources().getConfiguration().fontScale, 0.01f);
            assertEquals("Localized resources", 2f, new InspectorResources(configured.getResources(), true).getConfiguration().fontScale, 0.01f);
            Context large = InspectorTheme.context(configured);
            assertEquals("Scoped theme", 2f, large.getResources().getConfiguration().fontScale, 0.01f);
            TextView label = InspectorTheme.text(large, 16, false);
            assertTrue("Large text must grow in pixels, not only report a changed configuration", label.getTextSize() >
                android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 24, large.getResources().getDisplayMetrics()));
        });
    }
    @Test public void baselineSettingsLeaveDebugSessionRunning() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            CaptureController capture = new CaptureController(true, true, com.sandrox.osservio.core.LogClock.SYSTEM); String session = capture.status().sessionId;
            InspectorCaptureSettingsView settings = new InspectorCaptureSettingsView(context, capture, new int[] {R.string.osservio_runtime_breadcrumbs}, capture);
            Switch toggle = first(settings, Switch.class); assertNotNull(toggle); assertTrue(toggle.isChecked());
            toggle.setChecked(false);
            assertFalse(capture.status().baselineEnabled); assertTrue(capture.status().debugEnabled); assertEquals(session, capture.status().sessionId);
            toggle.setChecked(true); assertTrue(capture.status().baselineEnabled);
        });
    }
    @Test public void breadcrumbDetailKeepsContentAndCollapsedIdentifiersSeparate() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            BreadcrumbEvent event = BreadcrumbEvent.restore("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
                7, 1_000, 42L, BreadcrumbEvent.Kind.HOST_MARKER, "fixture.ready", "{\"item\":42}", false, false, null, BreadcrumbEvent.Retention.RESTART_RELEVANT);
            InspectorScreen screen = BreadcrumbDetailDialog.create(context, event);
            assertNull(first(screen.view, EditText.class));
            TextView id = text(screen.view, event.id); assertNotNull(id); assertFalse(visible(id, screen.view));
            TextView expand = text(screen.view, context.getString(R.string.osservio_ui_show_identifiers)); assertTrue(expand instanceof Button); expand.performClick();
            assertTrue(visible(id, screen.view));
            TextView hide = text(screen.view, context.getString(R.string.osservio_ui_hide_identifiers)); hide.performClick(); assertFalse(visible(id, screen.view));
            assertNotNull(text(screen.view, "{\"item\":42}"));
            assertNotNull(text(screen.view, context.getString(R.string.osservio_crumb_restart_relevant)));
        });
    }
    @Test public void conditionEditorsPreserveDraftAndProtocolSelection() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            InspectorQuery draft = new InspectorQuery(); draft.text = "main search";
            InspectorFilterConditions editor = new InspectorFilterConditions(context, draft, () -> { });
            editor.path.setText("/data/id"); editor.value.setText("42"); editor.exclude.setText("heartbeat"); editor.duration.setText("100");
            assertTrue(editor.valid()); assertEquals(100, draft.minimumDurationMillis); assertTrue(draft.allows(InspectorQuery.Field.TRANSPORT, "http"));
            draft.select(InspectorQuery.Field.TRANSPORT, "webSocket"); editor.sync();
            assertEquals("", editor.duration.getText().toString()); assertEquals("/data/id", editor.path.getText().toString());
            assertEquals("42", draft.fieldValue); assertEquals("main search", draft.text);
        });
    }
    @Test public void privatePresentationUsesFlagsAndKeepsAvailableEvidence() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            com.sandrox.osservio.core.IncidentSnapshot mark = com.sandrox.osservio.core.IncidentSnapshot.capture(
                com.sandrox.osservio.core.LogMessage.privateText("must not be retained"), null, null);
            com.sandrox.osservio.core.IncidentSummary summary = new com.sandrox.osservio.core.IncidentSummary(
                mark.id, mark.timestampMillis, mark.note, true, 3, 0, 2, null);
            assertEquals("[private]", mark.note.text);
            assertEquals(context.getString(R.string.osservio_incident_manual), IncidentPresentation.listTitle(context, summary));
            String subtitle = IncidentPresentation.listSubtitle(context, summary);
            assertTrue(subtitle.contains(context.getString(R.string.osservio_incident_private_note)));
            assertTrue(subtitle.contains("HTTP: 2")); assertTrue(subtitle.contains(": 0"));
            assertFalse(subtitle.contains("[private]")); assertFalse(subtitle.contains("must not be retained"));
            assertEquals(context.getString(R.string.osservio_incident_private_note), IncidentPresentation.note(context, mark.note));
            assertEquals("[private]", RuntimePresentation.message(context, "[private]", false));
            assertEquals(context.getString(R.string.osservio_ui_private_content), RuntimePresentation.message(context, "[private]", true));
            com.sandrox.osservio.core.IncidentSnapshot literal = com.sandrox.osservio.core.IncidentSnapshot.capture(
                com.sandrox.osservio.core.LogMessage.publicText("[private]"), null, null);
            com.sandrox.osservio.core.IncidentSummary publicSummary = new com.sandrox.osservio.core.IncidentSummary(
                literal.id, literal.timestampMillis, literal.note, false, null);
            assertEquals("[private]", IncidentPresentation.listTitle(context, publicSummary));
            assertTrue(IncidentPresentation.listSubtitle(context, publicSummary).contains(context.getString(R.string.osservio_incident_no_attachments)));
        });
    }

    private static boolean visible(View view, View root) {
        while (view != null) { if (view.getVisibility() != View.VISIBLE) return false; if (view == root) return true; view = view.getParent() instanceof View ? (View) view.getParent() : null; } return false;
    }
    private static TextView text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) { TextView found = text(((ViewGroup) view).getChildAt(index), value); if (found != null) return found; } return null;
    }
    private static <T> T first(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) { T found = first(((ViewGroup) view).getChildAt(index), type); if (found != null) return found; } return null;
    }
}
