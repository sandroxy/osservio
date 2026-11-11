package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InspectorToolbarActionsTest {
    @Test public void overflowKeepsExitVisibleWithoutReplacingOriginalActions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            LinearLayout bar = new LinearLayout(context);
            InspectorToolbarActions actions = new InspectorToolbarActions(bar);
            View first = new View(context), second = new View(context), third = new View(context), exit = new View(context);
            actions.replace(Arrays.asList(first, second));
            assertEquals(2, bar.getChildCount()); assertSame(first, bar.getChildAt(0));
            actions.add(third);
            assertEquals(1, bar.getChildCount());
            assertEquals(context.getString(R.string.osservio_ui_actions), bar.getChildAt(0).getContentDescription());
            actions.exit(exit); assertEquals(2, bar.getChildCount()); assertSame(exit, bar.getChildAt(1));
            first.setEnabled(false); actions.replace(Arrays.asList(first, second));
            assertEquals(2, bar.getChildCount()); assertNotSame(first, bar.getChildAt(0));
            second.setVisibility(View.GONE); actions.render();
            assertSame(first, bar.getChildAt(0)); assertFalse(first.isEnabled()); assertSame(exit, bar.getChildAt(1));
            actions.replace(Collections.emptyList()); assertEquals(1, bar.getChildCount()); assertSame(exit, bar.getChildAt(0));
        });
    }
}
