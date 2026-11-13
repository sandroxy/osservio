package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Confirmation boundaries use callbacks only, never existing device histories or archives. */
@RunWith(AndroidJUnit4.class)
public final class InspectorConfirmationTest {
    @Test public void cancellationBackAndHostHideNeverConfirmAndAcceptanceRunsOnce() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            FrameLayout modals = new FrameLayout(base); View workspace = new View(base);
            InspectorNavigation navigation = new InspectorNavigation(base, new View(base), modals, workspace);
            Context context = new InspectorNavigation.OwnerContext(base, navigation, 0);
            InspectorScreen page = InspectorDialogs.screen(context, R.string.osservio_ui_retained_run, new View(context)); page.show();
            int[] accepted = {0}, cancelled = {0};
            int[][] labels = {
                {R.string.osservio_clear, R.string.osservio_ui_clear_hint},
                {R.string.osservio_ui_network_clear, R.string.osservio_ui_clear_hint},
                {R.string.osservio_crumb_clear, R.string.osservio_ui_clear_hint},
                {R.string.osservio_archive_delete_all, R.string.osservio_archive_delete_confirmation},
                {R.string.osservio_archive_delete_run, R.string.osservio_archive_delete_confirmation},
                {R.string.osservio_incident_delete_all, R.string.osservio_incident_delete_confirmation},
                {R.string.osservio_incident_delete, R.string.osservio_incident_delete_confirmation}
            };
            for (int[] label : labels) for (int dismissal = 0; dismissal < 4; dismissal++) {
                int previous = accepted[0], previousCancelled = cancelled[0];
                InspectorScreen confirmation = InspectorDialogs.confirm(context, label[0], label[1], label[0], (ignored, which) -> accepted[0]++);
                confirmation.setOnCancelListener(ignored -> cancelled[0]++);
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, workspace.getImportantForAccessibility());
                assertTrue(page.isShowing()); assertEquals(View.VISIBLE, page.view.getVisibility());
                List<Button> actions = buttons(confirmation.view); assertEquals(2, actions.size());
                Button negative = named(actions, context, R.string.osservio_archive_cancel), positive = named(actions, context, label[0]);
                confirmation.view.performClick(); // The backdrop is inert, as on iOS.
                assertTrue(confirmation.isShowing()); assertEquals(previous, accepted[0]);
                if (dismissal == 0) negative.performClick();
                else if (dismissal == 1) navigation.back();
                else if (dismissal == 2) navigation.discardPanels();
                else { positive.performClick(); positive.performClick(); }
                assertFalse(confirmation.isShowing()); assertTrue(page.isShowing());
                assertEquals(previous + (dismissal == 3 ? 1 : 0), accepted[0]);
                assertEquals(previousCancelled + (dismissal == 3 ? 0 : 1), cancelled[0]);
                assertEquals(0, modals.getChildCount());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, workspace.getImportantForAccessibility());
            }
            InspectorScreen pending = InspectorDialogs.confirm(context, R.string.osservio_clear, R.string.osservio_ui_clear_hint,
                R.string.osservio_clear, (ignored, which) -> fail("Disposal must not confirm"));
            Button obsolete = named(buttons(pending.view), context, R.string.osservio_clear); navigation.dispose(); obsolete.performClick();
            assertFalse(pending.isShowing()); assertEquals(0, modals.getChildCount());
        });
    }

    @Test public void centeredCardAndCompleteActionsFitLanguagesThemesAndLargeText() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            String name = "osservio.confirmation.test." + UUID.randomUUID();
            Context isolated = new ContextWrapper(target) {
                @Override public SharedPreferences getSharedPreferences(String ignored, int mode) { return target.getSharedPreferences(name, mode); }
            };
            try {
                for (boolean english : new boolean[] {false, true}) for (boolean dark : new boolean[] {false, true})
                    for (float scale : new float[] {1, 2}) for (boolean landscape : new boolean[] {false, true}) {
                        InspectorPreferences.setEnglish(isolated, english); InspectorPreferences.setDark(isolated, dark);
                        Configuration sizing = new Configuration(); sizing.fontScale = scale;
                        android.view.ContextThemeWrapper host = new android.view.ContextThemeWrapper(isolated, android.R.style.Theme_Material_NoActionBar);
                        host.applyOverrideConfiguration(sizing);
                        Context base = InspectorTheme.context(host);
                        FrameLayout modals = new FrameLayout(base);
                        InspectorNavigation navigation = new InspectorNavigation(base, new View(base), modals, new View(base));
                        Context context = new InspectorNavigation.OwnerContext(base, navigation, 0);
                        InspectorScreen confirmation = InspectorDialogs.confirm(context, R.string.osservio_archive_delete_all,
                            R.string.osservio_archive_delete_confirmation, R.string.osservio_archive_delete_all, (ignored, which) -> fail("Layout must not confirm"));
                        ViewGroup root = confirmation.view;
                        root.setPadding(0, dp(context, 24), 0, dp(context, 24));
                        layout(root, landscape ? 640 : 320, landscape ? 320 : 640);
                        ViewGroup card = (ViewGroup) root.getChildAt(0);
                        assertEquals(root.getWidth() / 2f, (card.getLeft() + card.getRight()) / 2f, 1);
                        assertEquals(root.getHeight() / 2f, (card.getTop() + card.getBottom()) / 2f, 1);
                        assertTrue(card.getTop() >= root.getPaddingTop() + dp(context, 24));
                        assertTrue(card.getBottom() <= root.getHeight() - root.getPaddingBottom() - dp(context, 24));
                        assertTrue(card.getWidth() <= dp(context, 360));
                        List<Button> actions = buttons(card); assertEquals(2, actions.size());
                        for (Button action : actions) {
                            assertTrue(action.getMeasuredHeight() >= dp(context, 48));
                            assertNotNull(action.getLayout());
                            for (int line = 0; line < action.getLineCount(); line++) assertEquals(0, action.getLayout().getEllipsisCount(line));
                            assertEquals(action.length(), action.getLayout().getLineEnd(action.getLineCount() - 1));
                        }
                        Button negative = named(actions, context, R.string.osservio_archive_cancel);
                        Button positive = named(actions, context, R.string.osservio_archive_delete_all);
                        if (negative.getTop() == positive.getTop()) assertTrue(negative.getLeft() < positive.getLeft());
                        else assertTrue("Stacked confirmations keep Cancel at the bottom", negative.getTop() > positive.getTop());
                        Button last = actions.get(actions.size() - 1);
                        ScrollView scroll = (ScrollView) card.getChildAt(0);
                        scroll.setSmoothScrollingEnabled(false); scroll.fullScroll(View.FOCUS_DOWN);
                        Rect bounds = new Rect(0, 0, last.getWidth(), last.getHeight());
                        root.offsetDescendantRectToMyCoords(last, bounds);
                        assertTrue(bounds.bottom <= card.getBottom()); assertTrue(bounds.top >= card.getTop());
                        navigation.dispose();
                    }
            } finally { target.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit(); }
        });
    }
    private static List<Button> buttons(View view) {
        List<Button> result = new ArrayList<>();
        if (view instanceof Button) result.add((Button) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) result.addAll(buttons(((ViewGroup) view).getChildAt(i)));
        return result;
    }
    private static Button named(List<Button> buttons, Context context, int label) {
        for (Button button : buttons) if (context.getString(label).contentEquals(button.getText())) return button;
        throw new AssertionError("Missing confirmation action: " + context.getString(label));
    }
    private static int dp(Context context, int value) { return InspectorTheme.dp(context, value); }
    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(view.getContext(), width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(view.getContext(), height), View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
}
